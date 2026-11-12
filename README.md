# HudCast — 高德导航 / 巡航画面投到 HUD

> ## ⬇️ 直接下载（免登录直链）
>
> | 文件 | 说明 |
> |---|---|
> | **[HUD_1.78_Fixed.apk](release/HUD_1.78_Fixed.apk)** | 修复版安装包（v1.79 / versionCode 80，v2+v3 签名，MD5 `0277220fd5813357b363e7252dfb30c3`） |
> | [HUD逆向修复报告.md](release/HUD逆向修复报告.md) | 高德 9.5.13 发送端逆向全过程与协议对照 |
> | [修改差异-1.75至1.79.patch](release/修改差异-1.75至1.79.patch) | 全部代码改动 diff |
> | [编译日志-1.79.txt](release/编译日志-1.79.txt) | 构建日志 |
> | [HUD投屏-安装说明.md](release/HUD投屏-安装说明.md) | 安装与回退说明 |
>
> raw 直链：`https://raw.githubusercontent.com/DMZlinzong/hub-cast-fixed/main/release/HUD_1.78_Fixed.apk`
>
> ### 本修复版解决的两个问题
> 1. **巡航不显示红绿灯倒数**：60073（红绿灯倒数，`wh0.java`）恒解析，60075（按键提示音，`yg.java`）过滤；extras 数组倒数提取、多值锁定仲裁、相位推断、绿灯 stuck=3 检测、空闲 15 秒门控。高德巡航界面 2-3 个倒计时只映射当前路口绿灯倒数。
> 2. **车道指引与高德不一致**：取数优先级 位图 Back_icon > 枚举 > 类型 > 扩展 > 文字方向（兜底）> 位标志 > 直行；位图按车机实拍校准（28/62=直+左+掉、57=掉+右、74/31=掉+左）；掉头路口二次刷新实时重映射；简化推送 30 秒防降级。
>
> 已知限制：巡航倒数依赖高德发送端云端授权（`cruise.param.enable_traffic_light` + `CruiseTrafficLightAuth`），车机侧未放行时接收端无法绕过。

把高德地图的导航、巡航画面实时镜像到车机的 **Display 3（480×240 HUD 屏）**，在手机侧完成裁剪与缩放，不修改高德安装包、不依赖 root。

## 为什么是这个方案

拆完 `hud-probe-0.4.8` 和高德 `17.02.0.2010-UCAR59` 之后，结论有两条：

1. `hud-probe` 那条链路是**数据投屏**，不是画面投屏。它监听高德车机版（`com.autonavi.amapauto` / `amapautk`）的 `AUTONAVI_STANDARD_BROADCAST_SEND` 广播，或者 bind 车机版的 `JsonProtocolService` 拿车道级 JSON，然后用 `Presentation` 在 Display 3 上用 Canvas 自己画车道箭头。它从不抓画面。
2. 你手上这份高德是 `com.autonavi.minimap`（UCAR 渠道的手机版内核），**不发那条广播、也没有那个 AIDL 服务**，所以探针的数据通路对它无效；它自己的 `com.amap.bundle.drive.carprojection` 是 HiCar / 车机互联协议，需要对方端配合实现，不是给自己拉流用的。

要拿"画面"，唯一不 root 的可行路径就是 **MediaProjection 抓屏 + 自己往 HUD 的 Surface 上贴**。本工程就是这条路径的最小可用实现。

## 链路

```
高德（默认屏）
   ↓ 系统合成
MediaProjection ── VirtualDisplay(源屏真实分辨率, AUTO_MIRROR)
   ↓
SurfaceTexture (OES 外置纹理)
   ↓  GL：裁剪 UV + 等比缩放
HudPresentation 里的 SurfaceView（Display 3, 480×240）
   ↓
HUD 屏
```

关键取舍：

- **VirtualDisplay 按源屏原始分辨率建**，缩小放在 GL 里做。若直接把 480×240 交给 VirtualDisplay，系统会先降采样，导航文字和车道箭头会糊。
- **裁剪在 GL 的 UV 上做**，不动源画面、不需要二次截图，延迟就是一次 GPU blit，60fps 无压力。
- **HUD 掉线不拆授权**：只停渲染，等 `DisplayListener` 报屏幕回来再重挂 EGL 目标。Android 14 上若系统拒绝复用授权，会明确提示重新授权，不会静默黑屏。

## 目录

```
app/src/main/java/cn/hud/cast/
├── HudDisplay.kt          目标屏定位与校验（规则同 hud-probe）
├── HudPresentation.kt     Display 3 上的全屏不可交互窗口 + SurfaceView
├── SurfaceBlitter.kt      EGL + OES 纹理 + 裁剪/缩放绘制
├── ProjectionSession.kt   MediaProjection / VirtualDisplay / 渲染线程
├── HudCastService.kt      前台服务、生命周期、HUD 掉线重连、状态总线
├── GaodeWatcher.kt        前台判断 + 导航生命周期广播
├── CastConfig.kt          裁剪框 / 缩放方式 / 联动开关
└── MainActivity.kt        控制页（无 AppCompat，纯 Framework UI）
```

## 编译

- AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 35 / minSdk 24
- 零第三方依赖，离线环境把 AGP 与 Kotlin 插件预置到本地仓库即可

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 车机启动后确认 HUD 屏在线：App 首屏会显示 `HUD 检测：Display 3 就绪（480×240）`。若不是就绪，会直接给出被拦的原因（不是 ON / 不支持 Presentation / PRIVATE / 尺寸不对 / 就是默认屏）。
2. 点「开始投屏」，在系统弹窗里允许录屏。
3. 打开高德，进入导航或巡航，HUD 上应当出现画面。
4. 画面位置不对，用「裁剪框」四个滑块调：左/上/右/下按百分比切。导航和巡航各存一套。
5. 「缩放方式」三选一：等比装下（留黑边）、等比铺满（裁边）、直接拉伸（满屏变形）。HUD 是 2:1，源屏一般 16:9，默认建议先试「等比铺满」。
6. 想让它跟着高德自动启停，去授予「使用情况访问」权限，打开「高德离开前台 20 秒后自动停投」。

## 参数起点

| 场景 | 建议裁剪（左, 上, 右, 下） | 说明 |
| --- | --- | --- |
| 导航（横屏车机 1280×720 / 1920×720） | 0.00, 0.06, 0.72, 1.00 | 去掉顶部状态栏，只取左侧地图区，右侧引导卡片不要 |
| 巡航 | 0.00, 0.06, 1.00, 1.00 | 巡航一般全屏地图，只去掉顶栏 |

HUD 是 2:1，源屏是 16:9，两者比例对不上，所以裁剪框最好直接裁成 2:1 的矩形：宽取满时高度就取源屏宽度的一半。以 1280×720 为例，`1280 : 640` 正好 2:1，纵向从 0.06 到 0.95 这一条就够用（`(0.95-0.06) × 720 ≈ 640`）。这样配「等比铺满」既不裁内容也不变形。懒得算就先用「等比铺满」，盯着 HUD 拖滑块调到顺眼。

## 已知限制

- **必须一次性授权录屏**。车载场景没有"免授权"的路子，除非签名成系统应用走 `MediaProjection` 白名单或直接用 `SurfaceControl` 镜像（要 system 权限）。
- **抓的是整块默认屏**。MediaProjection 只能录默认显示，高德被别的窗口盖住时 HUD 上看到的也是被盖住的画面。悬浮窗、导航弹窗会被一起录进去。
- **FLAG_SECURE 的内容录不到**。高德正常不设这个标志。
- 高德自己若开了"HUD 模式"（画面翻转镜像），录出来也是翻的，别同时开。
- 熄屏会停帧。车机常亮场景无影响。

## 还能往上加

- **录制**：同一份 OES 纹理再挂一路 `MediaCodec` 编码 + `MediaMuxer`，就能把导航过程存成 MP4，不需要二次录屏。
- **双路输出**：一个 VirtualDisplay 可以 fan-out 到多个 EGL surface，HUD 和中控同时显示不同裁剪。
- **白名单吸附**：把高德导航页的窗口区域（`AccessibilityService` 或 `WindowManager` 窗口栈）读出来，裁剪框自动跟随，不用手调滑块。
- **回到数据层**：如果哪天换成高德车机版（`amapauto` / `amapautk`），`GaodeWatcher` 里的广播和 `hud-probe` 的 AIDL 就能直接用，那条链路带宽小、可读性好，比投画面更适合 HUD。
