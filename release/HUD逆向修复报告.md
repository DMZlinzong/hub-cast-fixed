# HUD 投屏 1.79（HUD_1.78_Fixed）逆向修复报告

交付日期：2026-11-12 ｜ 基础版本：HUD投屏-1.78 → 交付版本：**1.79 (versionCode 80)**
说明：本任务不适用"反编译 HUD 自己的 APK"——HUD 全部 Kotlin 源码在 `/workspace/hud-cast/`（本项目即 1.36→1.79 的连续迭代源码），修改直接在源码层完成；逆向分析的重心放在**高德 9.5.13 发送端**（用户要求必须由高德实际代码确认协议，不允许猜测）。

---

## 一、逆向工程过程与工具

| 步骤 | 工具 | 结果 |
| --- | --- | --- |
| 高德 APK 解包 | unzip | 166MB 单 dex（6.5MB）+ 65 个 arm64 so |
| Java 层反编译 | jadx 1.5.1（自装） | 2984 个类全部还原（仅 6 个非关键错误） |
| native 层检索 | strings / nm / readelf | 定位 libGbl.so / libhsl.so |
| 关键函数反汇编 | capstone 5.0.7（自装，aarch64） | CheckCruiseTrafficLightCountdown / OnUpdateCruiseTrafficLightCountdown / TrafficLightsCountdownInfoHmiToAl |

高德 APK：`K1TPv1 高德9.5.13_仪表屏_共存版_华阳_珂.apk`（/root/uploads/amap9513/）。

---

## 二、问题一：巡航红绿灯不显示——真正原因（发送端实锤）

### 2.1 高德广播发送端代码（反编译原文）

**60073 = 红绿灯倒数广播（唯一真通道）**，发送类 `wh0`（TrafficLightsCountdownInfoDisAction）：

```java
intent.putExtra(KEY_TYPE, 60073);
intent.putExtra(EXTRA_TRAFFICLIGHTSTATUS,        this.d.getTrafficLightStatus());
intent.putExtra("dir",                           this.d.getDir());
intent.putExtra(EXTRA_REDLIGHTCOUNTDOWNSECONDS,  this.d.getRedLightCountDownSeconds());
intent.putExtra(EXTRA_WAITROUND,                 this.d.getWaitRound());
if (ib.t()) intent.putExtra(EXTRA_GREENLIGHTLASTSECOND, this.d.getGreenLightLastSecond());
```

- `ib.t()` = `i.a || 协议版本 >= 810`，9.5.13 恒为 true → greenLightLastSecond 恒发。
- 数据源：引擎协议 **80189**（`RspTrafficLightsCountdownInfoModel`，字段全为**单值**：dir / redLightCountDownSeconds / trafficLightStatus / waitRound / greenLightLastSecond）→ `v70.distributeDispatch case 80189` → `wh0` → 60073。

**60075 = 按键提示音响应（不是红绿灯！）**，发送类 `yg`（ControlTouchBeepAction）：

```java
intent.putExtra(KEY_TYPE, 60075);
intent.putExtra(EXTRA_TOUCHBEEPRESULT, x.getTouchBeepResult());   // 只有这一个键
```

→ **实车诊断行里巡航时那些 st=-1、r=null、g=null 的"60075 红绿灯广播"，其实是按键提示音响应**，其 extras 永远不含红绿灯键。这解释了此前巡航诊断"判定=无"的全部现象。

### 2.2 巡航红绿灯在引擎侧的存在与门禁（libGbl.so / libhsl.so 反汇编）

- 引擎有独立的巡航红绿灯倒数总线：`BusMessageTypeUpdateCruiseTrafficLightCountdown`，数据为**列表**（每项 dir/phase/standardType/status/lightCountdown/waitNum）——高德巡航屏上的 2-3 个倒数来自这里；
- 导出链：`hsl::GuideInfoExport::OnUpdateCruiseTrafficLightCountdown(list)` → `CheckCruiseTrafficLightCountdown(list)` → `OnTimerCruiseLightCountDown()` → `GINFO_NOTIFYProtocolHmiToAl::TrafficLightsCountdownInfoHmiToAl(GTrafficLightsCountdownInfoSend)` → 80189 → 60073；
- 反汇编 `CheckCruiseTrafficLightCountdown`（0xa11474，0xa4 字节）：返回 `(list.size | this->[0x10EC]) != 0`——**列表非空且导出标志开启才发**；
- 门禁字符串（libGbl.so）：`cruise.param.enable_traffic_light`（引擎参数）、`CruiseTrafficLightAuth`（**云端授权**，`UpdateAuthenticationStatus`）、`Cruise traffic light is disabled by auth, reason: %d / disabled by config`。这两个开关都在引擎配置/云端，**不在 APK 内，接收端无法绕过**。

### 2.3 结论（三选一的判定）

1. 巡航时若引擎导出放行 → 数据走 **60073**（引擎已选好"当前灯"单值）→ HUD 1.76+ 已正确接收解析绘制（含灯色/倒数/本地递减/过期剔除）；
2. 若车上 `cruise.param.enable_traffic_light` 或云端授权未放行 → **高德不发 60073**，任何接收端都无法拿到，这是巡航不显示的最可能根因；
3. 华阳车机层若在 60075 里夹带 `EXTRA_TRAFFIC_LIGHT_REMAIN_SECONDS/COUNTDOWNSECONDS`（实车诊断曾见），HUD 已兼容该键（单值+数组）。

**不允许伪造倒计时**：数据失效 60 秒剔除、空闲 15 秒不画灯、无有效秒数只画灯不画数字、绿灯"恒 3 标志"不显示假倒数——全部保留。

### 2.4 修改点（接收层证据修正）

| 文件 | 方法 | 修改前 | 修改后 |
| --- | --- | --- | --- |
| GaodeWatcher.kt | onReceive 红绿灯触发 | `type==60073 \|\| type==60075 \|\| 有键` —— 60075 beep 广播每次都进解析并**覆盖诊断行 extras** | `type==60073 \|\| 有红绿灯键` —— beep 广播被过滤，不再污染诊断与解析；60075 带键时（华阳夹带）仍兼容 |
| AmapProtocol.kt | feedLightBroadcast | status 缺失即走通用判定 | **waitRound>0 兜底判红**（发送端确认语义：红灯等待轮数），键名形态变化时灯色不丢 |
| GaodeWatcher.kt / AmapProtocol.kt | 注释 | 按猜测描述 60073/60075 | 全部替换为反编译确认的协议事实 |

**HUD 原来错误的代码位置**：GaodeWatcher.kt `onReceive` 中 `type == 60075` 无条件触发红绿灯解析（把提示音响应当红绿灯广播）——1.79 修正。

---

## 三、问题二：车道指引——发送端结构实锤

### 3.1 发送端（mk.java，DriveWayInfoDisAction）

引擎协议 **30424** JSON（`ew.f` 解析）：
`trafficLaneEnabled / trafficLaneSize / trafficLaneInfos[{trafficLaneNo, trafficLaneIcon, trafficLaneExtended, trafficLaneAdvised, trafficLaneExtendedNew, trafficLaneType}]`
→ 13012 广播 `EXTRA_DRIVE_WAY` JSON：

```json
{ "drive_way_enabled": true, "drive_way_size": 3,
  "drive_way_info": [
    { "drive_way_number": 1,
      "drive_way_lane_Back_icon": "62",        ← String.valueOf(int)，字符串形态
      "drive_way_lane_Extended": "0",
      "trafficLaneAdvised": true,
      "trafficLaneExtendedNew": 0,
      "trafficLaneType": 11 } ] }
```

### 3.2 HUD 读取字段核对（逐键对齐）

| 发送端键 | HUD 读取 | 状态 |
| --- | --- | --- |
| drive_way_lane_Back_icon（字符串 int） | `laneTypeFromRaw`（兼容字符串）→ 位图表 | ✅ |
| trafficLaneIcon / trafficLaneType | 次选/兜底枚举 | ✅ |
| trafficLaneAdvised / trafficLaneNo / Extended / ExtendedNew | 全部读取 | ✅ |
| drive_way_number vs 数组索引 | 按 number+数组顺序双通道，无混用 | ✅ |

### 3.3 车道编码映射表（HUD 当前生效，lanePdfToMask）

| b 值 | 显示 | 来源 |
| --- | --- | --- |
| 0/15 | 直行 | 对照图+实拍 |
| 1/16 | 左转 | 对照图 |
| 2/17/30 | 直+左 | 对照图 |
| 3/18 | 右转 | 对照图 |
| 4/19/32/33 | 直+右 | 对照图 |
| 5/8/20/23 | 掉头 | 对照图 |
| 6/21/34/35 | 左+右 | 对照图 |
| 7/22/36-38 | 直+左+右 | 对照图 |
| 9/10/24/25/39-42 | 掉+直 | 对照图 |
| 11/26/31/43/44/48/**74** | 掉+左 | 对照图+实拍（31/48/74 实拍校准） |
| 12/27/45/46/**57** | 掉+右 | 对照图+实拍（57 实拍校准） |
| **28/62** | 直+左+掉 | **实拍校准**（对照图版本差异） |
| 13 | 可变直 | 对照图 |
| 14/29/47 | 可变掉左 | 对照图 |

> **说明**：b 值（trafficLaneIcon/laneBackIcon）的语义表在 native 渲染层（纹理资源），Java 层无映射代码——APK 内无法提取更权威的表。上表 = 对照图 + 本车 7 轮实拍校准的合成，50-56/58-61/63-73/75+ 未实拍段落入防护逻辑（不落直行、防降级保护、诊断行标"b未映射"）。
> 防降级合并：无方向信息的简化推送 30 秒内不冲掉详细指引（1.72 起）。

---

## 四、红绿灯协议解析说明（HUD 最终逻辑）

1. **通道**：60073（权威）> 华阳 60075 夹带键 > AIDL 80189/全协议深扫（含 `*countdown` 泛化）> 数组形态列表；
2. **灯色**：status 1=红 4=绿 0/2/3/5/6=黄（Companion 校准）；status 缺失时 waitRound>0 兜底红（新增）；仅剩通用倒数时相位推断状态机（倒数跳升红绿互换）；
3. **秒数**：红取红倒数、绿取绿倒数（greenLastSecond 恒 3 标志检测排除假 3）> 通用倒数 > 列表连续性锁定值（1.78：逐秒递减特征锁定本车当前灯组，断链同索引跟随，20 秒过期起锁）；
4. **绘制**：巡航单胶囊（引擎只导出当前灯）、数据停推本地每秒递减、60 秒过期剔除、空闲 15 秒不画。

---

## 五、验证结论

### 已完成（静态+构建级）
- ✅ DEX 关键方法存在：`feedLightBroadcast`(6) / `selectLockedRemain`(3) / `drawCruiseLights`(3)；
- ✅ 签名：v2 + v3 通过（apksigner verify，自签 MD5 774f3155…）；
- ✅ Android 9 兼容：minSdk 24 ≤ 28，无 minSdk 28+ API 新增使用；
- ✅ 版本：versionCode 80 / versionName 1.79（aapt2 badging 校验）；
- ✅ 完整编译日志：`/workspace/编译日志-1.79.txt`；
- ✅ 修改差异：`/workspace/修改差异-1.75至1.79.patch`（979 行，含 1.76-1.79 累计修改）；
- ✅ SHA-256：`d74567efe6e36465c1f5bcad69071d8921895287532ae872847735d0c9ac1cdf`。

### 需上车实测（无法在沙箱模拟）
- 巡航红灯路口：60073 是否到达（诊断行看「红绿灯[广播60073]」）；若到达即显示；
- 若 60073 不到达 → 高德引擎巡航导出门禁（auth/config）未放行，**属高德侧开关，任何接收端修复都无法解决**；此时唯一的观察手段是 logcat 过滤高德进程的 "Cruise traffic light is disabled" 日志确认原因码；
- 车道：多车道/左转/右转/直左/直右/掉头各形态与高德车道条逐一同框核对。

### 安装与回退
- 覆盖安装：`HUD_1.78_Fixed.apk`（= 1.79）与 1.78 同包名同签名，直接安装，布局配置自动保留；
- 回退：安装 `/workspace/HUD投屏-1.78.apk` 即可回退，配置同样保留；
- 崩溃排查：`adb logcat -s HudCast`。

---

## 六、最终结论一览

| 问题 | 真正原因 | 是否修复 |
| --- | --- | --- |
| 巡航红绿灯不显示 | ① 60075 实为提示音响应，被误当红绿灯通道（HUD 已修）；② 红绿灯真通道 60073 在巡航时受引擎参数+云端授权门禁，车机侧很可能未放行（接收端无法绕过） | 接收层已修复并对齐发送端；巡航门禁需上车确认 |
| 车道指引不一致 | 引擎 30424 值经广播透传，b 值语义表在 native 层；本车用扩展编号（28/31/57/62/74）与通用对照图版本差异 | 已按发送端结构逐键对齐 + 实拍校准表 + 未映射防护；新 b 值按实拍逐个补 |
