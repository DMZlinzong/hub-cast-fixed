package cn.hud.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 投屏主服务。
 *
 * 时序：
 *   请求授权（Activity）→ 带着 resultCode/resultData 启动本服务 →
 *   定位 Display 3 → 建 Presentation → SurfaceView 就绪 → 开 ProjectionSession →
 *   VirtualDisplay 镜像源屏 → GL 裁剪 → HUD
 *
 * HUD 掉线只拆 Presentation + Session，不退出服务；屏回来了自动重建，
 * 若系统拒绝复用授权，会通过状态回调提示重新授权。
 */
class HudCastService : Service(), HudPresentation.Callback {

    companion object {
        private const val TAG = "HudCast"
        const val ACTION_START = "cn.hud.cast.START"
        const val ACTION_STOP = "cn.hud.cast.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        /** 1.27：运行中的服务实例，主界面手动绘制模块直接调用 */
        @Volatile
        var instance: HudCastService? = null
            private set

        /**
         * 1.29：投屏是否已经在跑。主界面「打开 App 自动投屏」用它判断，
         * 避免每次进 App 都重复 startService 把正在跑的会话重建一遍。
         */
        @Volatile
        var running: Boolean = false
            private set

        /** 运行模式：0=前台镜像（抓主屏，需录屏授权） 1=后台虚拟屏（免授权，高德拉进虚拟屏） */
        const val MODE_MIRROR = 0
        const val MODE_BACKGROUND = 1
        const val MODE_TEST = 2
        /** HUD 导航卡片：读车机版高德 JSON 数据，在 HUD 上常驻渲染车道/推荐车道/红绿灯倒数 */
        const val MODE_CARD = 3
        const val EXTRA_MODE = "extra_mode"

        const val ACTION_REPROBE = "cn.hud.cast.REPROBE"

        private const val CHANNEL_ID = "hud_cast"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, resultCode: Int, data: Intent) {
            startInternal(context, MODE_MIRROR, resultCode, data)
        }

        fun startBackground(context: Context) {
            startInternal(context, MODE_BACKGROUND, 0, null)
        }

        /** 彩条直连测试：不建虚拟屏、不抓屏、不拉高德，只验证 HUD 输出通道。 */
        fun startTest(context: Context) {
            startInternal(context, MODE_TEST, 0, null)
        }

        /** HUD 导航卡片：直接读车机版高德数据，免授权免抓屏。 */
        fun startCard(context: Context) {
            startInternal(context, MODE_CARD, 0, null)
        }

        /**
         * 开机自启动 / 系统重启服务时使用（1.16）。
         * 与其它入口的差别：返回是否成功提交，方便开机广播侧重试；
         * 不依赖任何 Activity 上下文，因此可以直接用 applicationContext。
         */
        fun startAuto(context: Context, mode: Int): Boolean {
            val intent = Intent(context, HudCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_RESULT_CODE, 0)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (t: Throwable) {
                Log.w(TAG, "开机自启动请求被系统拒绝：${t.javaClass.simpleName} ${t.message}")
                false
            }
        }

        private fun startInternal(
            context: Context,
            mode: Int,
            resultCode: Int,
            data: Intent?
        ) {
            val intent = Intent(context, HudCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_RESULT_CODE, resultCode)
                if (data != null) putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HudCastService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }

        /** 用户手动切换目标屏后让运行中的服务重新探测（1.5）。 */
        fun reprobe(context: Context) {
            val intent = Intent(context, HudCastService::class.java).apply { action = ACTION_REPROBE }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private lateinit var config: CastConfig
    private val mainHandler = Handler(Looper.getMainLooper())

    private var presentation: HudPresentation? = null
    private var session: ProjectionSession? = null
    private var bgSession: BgCastSession? = null
    private var testSession: TestPatternSession? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var watcher: GaodeWatcher? = null

    /** 1.53：协议通道尚未建立时暂存的限速广播（值, 来源类型），建好后补投 */
    private var pendingSpeedLimit: Pair<Int, Int>? = null

    /** 车机版高德 JSON 数据服务客户端（卡片模式用） */
    private var protocol: AmapProtocol? = null
    private var cardState = HudCardState()

    private var mode = MODE_MIRROR

    private var resultCode = 0
    private var resultData: Intent? = null

    private var targetSurface: Surface? = null
    private var surfaceWidth = HudDisplay.TARGET_WIDTH
    private var surfaceHeight = HudDisplay.TARGET_HEIGHT

    private var backgroundSince = 0L
    private var foregroundTicker: Runnable? = null
    private var diagTicker: Runnable? = null
    private var cardWatchdog: Runnable? = null
    private var lastDiagFrames = -1L
    private var lastFrameChangeAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = CastConfig(this)
        createChannel()
        StatusBus.notifier = { status ->
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification("HUD 投屏", status.text)
                )
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        StatusBus.notifier = null
        instance = null
        stopEverything("服务结束")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything("已手动停止")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REPROBE -> {
                // startForegroundService 触发的分支必须先补 startForeground，否则系统会判超时
                startAsForeground()
                if (bgSession != null || session != null) {
                    ensurePresentation()
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                mode = intent.getIntExtra(EXTRA_MODE, MODE_MIRROR)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData = if (mode == MODE_MIRROR) data else null
                if (mode == MODE_MIRROR && (resultCode == 0 || data == null)) {
                    StatusBus.publish(Status.failed("缺少投屏授权数据，请重新授权"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                begin()
            }

            else -> {
                // 系统在进程被杀后用 null intent 重新拉起（START_STICKY）。
                // 开了「开机自启动」就意味着要常驻，此时按配置的模式自己恢复，
                // 否则进程一旦被回收，HUD 就再也不亮了。
                if (config.autoStartOnBoot) {
                    mode = if (config.runMode == MODE_TEST) MODE_TEST else MODE_CARD
                    Log.i(TAG, "服务被系统重启，按常驻配置恢复：mode=$mode")
                    startAsForeground()
                    begin()
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun begin() {
        running = true
        // 三种模式统一接预览总线（1.9 修复：之前只在后台分支接，
        // 彩条测试和镜像模式的预览永远黑屏）
        wirePreviewBus()

        // 1.11：导航广播监听在所有模式启动（之前只有镜像模式启动，
        // 而镜像模式被授权问题卡死后，广播探测完全无法进行）
        if (watcher == null) {
            Log.i(TAG, "服务启动：mode=$mode，创建导航广播监听器")
            val w = GaodeWatcher(
                this,
                onNavStateChanged = { active ->
                    if (config.switchCropByProfile) {
                        config.activeProfile = if (active) CropProfile.NAV else CropProfile.CRUISE
                    }
                    if (mode == MODE_CARD) {
                        // 1.20：导航 ↔ 巡航共用一条数据通路。
                        // 1.27：退出导航时清空导航残留（车道/红绿灯/转向/道路/距离/到达信息）。
                        // 之前 1.22 改常驻后高德退出导航不发清空消息，数据就永久挂在 HUD 上。
                        // 清空后如果高德继续推巡航数据（30407/30424），会自动重新填充；
                        // 没有巡航数据就保持黑屏 + 「巡航中 · 等待数据」提示。
                        // 1.53：限速保留——限速只在变化时推送，清掉后巡航要一直等广播。
                        cardState = if (active) cardState.copy(navActive = true) else HudCardState(
                            connected = cardState.connected,
                            navActive = false,
                            speedLimit = cardState.speedLimit,
                            speedLimitAtMs = cardState.speedLimitAtMs,
                            // 1.60：巡航红绿灯分行数据保留，等新数据刷新
                            cruiseLights = cardState.cruiseLights
                        )
                        pushCardToPresentation()
                        StatusBus.publish(
                            Status.casting(
                                if (active) "导航中：持续读取高德数据 → HUD"
                                else "已退出导航：HUD 已清空，等待巡航数据…"
                            )
                        )
                    }
                },
                onNavInfo = { road, dist, turnMask ->
                    // 卡片模式：广播里的转向 / 道路名 / 剩余距离直接进卡片。
                    // 所有更新都收敛到 cardState 这一个对象上（1.17 之前车道和
                    // 文本两条路径各存一份互相覆盖，是旧车道残留的根因）
                    if (mode == MODE_CARD) {
                        val now = System.currentTimeMillis()
                        val hasTurn = turnMask != null && turnMask != 0
                        cardState = cardState.copy(
                            roadName = road ?: cardState.roadName,
                            distanceText = dist ?: cardState.distanceText,
                            turnMask = if (hasTurn) turnMask!! else cardState.turnMask,
                            turnAtMs = if (hasTurn) now else cardState.turnAtMs,
                            connected = true,
                            navActive = watcher?.navActive ?: cardState.navActive,
                            lastUpdateMs = now
                        )
                        pushCardToPresentation()
                    }
                },
                onLaneJson = { json ->
                    // 1.28：广播车道兜底（13012 / EXTRA_DRIVE_WAY）——AIDL 断联时也画车道
                    if (mode == MODE_CARD) {
                        protocol?.feedLaneJson(json)
                    }
                },
                onLightExtras = { status, red, green, dir, waitRound, srcType, remain, remainList ->
                    // 1.47：红绿灯倒数广播（KEY_TYPE=60075，实车广播类型行定位）
                    if (mode == MODE_CARD) {
                        protocol?.feedLightBroadcast(status, red, green, dir, waitRound, srcType, remain, remainList)
                    }
                },
                onSpeedLimit = { value, srcType ->
                    // 1.53：限速广播泛化捕获，进卡片状态（导航/巡航都显示）
                    if (mode == MODE_CARD) {
                        val proto = protocol
                        if (proto != null) {
                            proto.feedSpeedLimit(value, "广播$srcType")
                            pushCardToPresentation()
                        } else {
                            pendingSpeedLimit = value to srcType
                        }
                    }
                }
            )
            w.start()
            watcher = w
        }

        // 1.13：HUD 导航卡片模式——不抓屏、不授权，直接读车机版高德的 JSON 数据服务
        if (mode == MODE_CARD) {
            StatusBus.publish(Status.casting("HUD 导航卡片：正在连接车机版高德数据服务…"))
            ensurePresentation()
            if (displayListener == null) {
                displayListener = HudDisplay.registerDisplayListener(this) {
                    mainHandler.post { ensurePresentation() }
                }
            }
            if (protocol == null) {
                val proto = AmapProtocol(this) { cardState ->
                    mainHandler.post { applyCardState(cardState) }
                }
                protocol = proto
                // 1.53：补投协议建立前收到的限速广播
                pendingSpeedLimit?.let { (v, src) -> proto.feedSpeedLimit(v, "广播$src") }
                pendingSpeedLimit = null
                proto.start()
            }
            startCardWatchdog()
            return
        }

        if (mode == MODE_TEST) {
            StatusBus.publish(Status.casting("彩条测试中：等待 HUD 画面口…"))
            ensurePresentation()
            return
        }
        if (mode == MODE_BACKGROUND) {
            if (bgSession == null) {
                StatusBus.publish(Status.casting("后台模式：正在创建虚拟屏…"))
                val created = BgCastSession(this, config, config.bgWidth, config.bgHeight, config.bgDpi)
                created.listener = bgListener()
                bgSession = created
                created.start()
                wirePreviewBus()
                PreviewBus.replay()
                startDiagTicker()
            } else {
                StatusBus.publish(Status.casting("后台模式继续运行"))
            }
        } else {
            if (foregroundTicker == null) {
                val ticker = object : Runnable {
                    override fun run() {
                        checkForeground()
                        mainHandler.postDelayed(this, 5_000L)
                    }
                }
                foregroundTicker = ticker
                mainHandler.postDelayed(ticker, 5_000L)
            }
            StatusBus.publish(Status.casting("镜像模式：等待 HUD 就绪…"))
        }

        if (displayListener == null) {
            displayListener = HudDisplay.registerDisplayListener(this) { mainHandler.post { ensurePresentation() } }
        }

        // 镜像模式同样要看门狗：之前只挂在后台模式上，镜像黑屏时没有任何帧数信息
        startDiagTicker()
        ensurePresentation()
    }

    /**
     * 卡片常驻看门狗（1.16）。
     *
     * 开机自启动之后程序不再有人盯着，所以两个环节要自己恢复：
     *   1. 车机版高德被系统回收 / 重启 → 绑定失效，这里自动重新绑定并重注册回调；
     *   2. HUD 窗口被系统关掉（切屏、休眠唤醒）→ 自动重建 Presentation。
     */
    private fun startCardWatchdog() {
        if (cardWatchdog != null) return
        val ticker = object : Runnable {
            override fun run() {
                if (mode == MODE_CARD) {
                    checkGuideStale()
                    if (config.autoReconnect) {
                        val p = protocol
                        if (p == null || !p.isConnected) {
                            Log.i(TAG, "卡片看门狗：数据服务未连接，重新绑定车机版高德…")
                            p?.stop()
                            val rebuilt = AmapProtocol(this@HudCastService) { state ->
                                mainHandler.post { applyCardState(state) }
                            }
                            protocol = rebuilt
                            rebuilt.start()
                        }
                    }
                    if (presentation == null) {
                        ensurePresentation()
                    }
                }
                mainHandler.postDelayed(this, 15_000L)
            }
        }
        cardWatchdog = ticker
        mainHandler.postDelayed(ticker, 15_000L)
        Log.i(TAG, "卡片常驻看门狗已启动：每 15 秒检查数据绑定与 HUD 窗口")
        startEditModeSync()
    }

    /** 1.61：布局编辑同步循环——编辑开关打开时，HUD 投影显示演示画面
     *  （所有元素按当前编辑的导航/巡航布局映射），并持续应用布局改动；
     *  开关关闭后恢复真实高德数据。0.8 秒一轮，开销可忽略。 */
    private var editSync: Runnable? = null
    private var lastEditSyncApplied = false

    private fun startEditModeSync() {
        if (editSync != null) return
        val t = object : Runnable {
            override fun run() {
                syncEditMode()
                mainHandler.postDelayed(this, 800L)
            }
        }
        editSync = t
        mainHandler.postDelayed(t, 800L)
    }

    private fun syncEditMode() {
        val card = presentation?.cardView ?: return
        val editing = mode == MODE_CARD && config.hudEditMode
        if (editing) {
            // 编辑中：演示数据 + 当前编辑的布局，缺数据的元素画占位框，
            // 方便对着 HUD 实屏摆放；布局改动每轮都重新应用
            card.demoMode = true
            card.demoCruise = config.hudEditCruise
            card.editMode = true
            card.layout = config.cardLayout
            card.cruiseLayout = config.cardLayoutCruise
            card.lightSecondsOffset = config.lightSecondsOffset
            card.invalidate()
        } else if (lastEditSyncApplied || card.demoMode) {
            // 刚退出编辑：恢复真实数据
            card.demoMode = false
            card.demoCruise = false
            card.editMode = false
            card.invalidate()
        }
        lastEditSyncApplied = editing
    }

    /**
     * 1.44：到达 / 结束导航后的引导残留清除。
     *
     * 实车发现这版车机导航结束后既不发结束状态广播（8/9/10/12），
     * 也不发清空消息，最后一条 30407（「39m 目的地 · 0.0km·0分钟」）
     * 就永久冻在 HUD 上。两个清除条件（看门狗每 15 秒查一次）：
     *  A. 到达冻结：全程剩余 ≈0（0.0km·0分钟）且当前段距离很近，
     *     引导数据 20 秒没有实际变化 → 判定到达，清空导航元素；
     *  B. 长期冻结：导航中引导数据 10 分钟没有任何变化（中途取消导航
     *     时高德直接停推）→ 清空。
     * 清空后高德若继续推数据（导航其实还在继续），会自动重新填充。
     */
    private fun checkGuideStale() {
        val s = cardState
        if (!s.navActive || s.guideChangedAtMs <= 0L) return
        val idle = System.currentTimeMillis() - s.guideChangedAtMs
        val arrived = s.routeRemainDisM in 0..80 && s.routeRemainTimeS <= 60 &&
            s.segRemainDisM in 1..200
        val shouldClear = (arrived && idle > 20_000L) || idle > 10 * 60_000L
        if (!shouldClear) return
        Log.i(
            TAG, "导航残留清除：到达=$arrived 冻结=${idle / 1000}s " +
                "seg=${s.segRemainDisM}m route=${s.routeRemainDisM}m/${s.routeRemainTimeS}s"
        )
        cardState = HudCardState(
            connected = s.connected,
            navActive = true
        )
        pushCardToPresentation()
    }

    /**
     * 诊断看门狗（1.3）：每 3 秒读一次会话的帧计数，把「卡在哪一环」直接写进状态栏。
     * 黑屏不再是无信息状态——虚拟屏 0 帧说明高德没进虚拟屏，有帧但 HUD 黑说明输出断了。
     */
    private fun startDiagTicker() {
        if (diagTicker != null) return
        val ticker = object : Runnable {
            override fun run() {
                val bg = bgSession
                val mirror = session
                when {
                    bg != null -> {
                        val frames = bg.frameCount
                        val hud = bg.hudAttached
                        val elapsedSec = (System.currentTimeMillis() - bg.startedAtMs) / 1000
                        if (frames != lastDiagFrames) {
                            lastDiagFrames = frames
                            lastFrameChangeAt = System.currentTimeMillis()
                        }
                        val stallMs = System.currentTimeMillis() - lastFrameChangeAt
                        when {
                            hud && frames > 0 && stallMs > 10_000 -> StatusBus.publish(
                                Status.failed(
                                    "诊断：帧数停在 $frames 已超过 10 秒——高德在虚拟屏上停止了渲染" +
                                        "（无焦点窗口被系统降帧）。可开启「渲染心跳」实验开关试试；" +
                                        "或改用前台镜像模式。"
                                )
                            )

                            hud && frames > 0 ->
                                StatusBus.publish(Status.casting("投屏中：虚拟屏已画 $frames 帧 → HUD"))

                            frames > 0 ->
                                StatusBus.publish(
                                    Status.casting("虚拟屏运行中（已收到 $frames 帧），等待 HUD 画面…")
                                )

                            hud && frames == 0L && elapsedSec > 10 -> StatusBus.publish(
                                Status.failed(
                                    "诊断：HUD 已接好、彩条也出了，但虚拟屏 $elapsedSec 秒收不到高德的任何一帧——" +
                                        "高德没有进入虚拟屏（这台车机拦截了跨应用拉起）。请改用「前台镜像」模式。"
                                )
                            )

                            else -> StatusBus.publish(
                                Status.casting("链路测试中：HUD 与预览应显示移动彩条。" +
                                    "彩条正常 = 输出链路畅通，黑屏原因在高德一侧；HUD 也黑 = 链路有问题")
                            )
                        }
                    }

                    mirror != null -> {
                        val frames = mirror.frameCount
                        val elapsedMs = System.currentTimeMillis() - mirror.startedAtMs
                        when {
                            mirror.hudAttached && frames > 0 -> StatusBus.publish(
                                Status.casting(
                                    "镜像投屏中：已画 $frames 帧 → HUD" +
                                        if (mirror.lastSampledBrightness >= 0f) {
                                            " · 画面亮度 ${(mirror.lastSampledBrightness * 100).toInt()}%"
                                        } else ""
                                )
                            )

                            mirror.hudAttached && frames == 0L && elapsedMs > 10_000 -> StatusBus.publish(
                                Status.failed(
                                    "诊断：HUD 已接好、彩条也出了，但录屏通道 $frames 帧——" +
                                        "授权可能已失效，请停止后重新点「开始投屏」授权；" +
                                        "若重新授权仍为 0 帧，说明这台车机禁止录屏抓主屏。"
                                )
                            )

                            mirror.testPatternRunning -> StatusBus.publish(
                                Status.casting("链路测试中：HUD 与预览应显示移动彩条。" +
                                    "彩条正常 = 输出链路畅通，黑屏原因在录屏画面一侧")
                            )

                            else -> Unit
                        }
                    }

                    else -> {
                        diagTicker = null
                        return
                    }
                }
                mainHandler.postDelayed(this, 3_000L)
            }
        }
        diagTicker = ticker
        mainHandler.postDelayed(ticker, 3_000L)
    }

    private fun wirePreviewBus() {
        PreviewBus.setConsumer { s, w, h ->
            bgSession?.attachPreview(s, w, h)
            session?.attachPreview(s, w, h)
            testSession?.attachPreview(s, w, h)
        }
        PreviewBus.setDestroyed {
            bgSession?.detachPreview()
            session?.detachPreview()
            testSession?.detachPreview()
        }
    }

    /** 后台会话的统一回调：把每一步进度都发到状态栏，卡在哪一步一眼可见。 */
    private fun bgListener(): BgCastSession.Listener = object : BgCastSession.Listener {
        override fun onStarted() {
            StatusBus.publish(
                Status.casting("虚拟屏已创建（${config.bgWidth}×${config.bgHeight}）")
            )
        }

        override fun onDisplayReady(displayId: Int, width: Int, height: Int) {
            StatusBus.publish(
                Status.casting("虚拟屏 $displayId 就绪，正在拉起高德…")
            )
            BgEvents.emit(displayId)
        }

        override fun onHudAttached() {
            StatusBus.publish(Status.casting("投屏中：虚拟屏 → HUD"))
        }

        override fun onError(message: String) {
            StatusBus.publish(Status.failed("后台模式失败：$message"))
        }

        override fun onStopped(reason: String) {
            StatusBus.publish(Status.idle(reason))
            bgSession = null
        }
    }

    /**
     * 卡片数据落地（AIDL 数据服务一侧的更新）。
     * 1.17 之前这里直接整对象覆盖 cardState，会把广播路径写入的道路名/距离/转向
     * 冲掉，同时把旧车道一直带着——两条路径互相覆盖正是「过期车道残留」的根因。
     * 现在所有字段统一合并在 cardState 一个对象上。
     */
    private fun applyCardState(state: HudCardState) {
        // 1.46：广播通道滚动记录同步到诊断行（60073 未命中时可见车机实际广播类型）
        watcher?.let {
            if (it.lastBcastTypes.isNotEmpty()) protocol?.bcastDbg = it.lastBcastTypes
            // 1.64：红绿灯类广播完整 extras 同步到诊断行（巡航键名校准）
            if (it.lastLightExtrasDump.isNotEmpty()) protocol?.lightExtrasDbg = it.lastLightExtrasDump
            // 1.80：60073 到达计数同步（诊断行判断发送端门禁状态）
            protocol?.light60073Count = it.light60073Count
        }
        // 1.44：引导数据是否有「实际变化」（距离递减 / 转向 / 道路 / 全程信息）。
        // 行驶中每秒都在变；到达目的地或退出导航后高德停推或重复同一值，
        // 该时间戳停止前进，看门狗据此识别冻结残留。
        val guideChanged = state.segRemainDisM != cardState.segRemainDisM ||
            state.turnMask != cardState.turnMask ||
            state.roadName != cardState.roadName ||
            state.routeRemainDisM != cardState.routeRemainDisM ||
            state.routeRemainTimeS != cardState.routeRemainTimeS
        val nowMs = System.currentTimeMillis()
        cardState = cardState.copy(
            lanes = state.lanes,
            laneEnabled = state.laneEnabled,
            lanesAtMs = state.lanesAtMs,
            laneMsgAtMs = state.laneMsgAtMs,
            lightStatus = state.lightStatus,
            lightSeconds = state.lightSeconds,
            lightTurnMask = state.lightTurnMask,
            lightAtMs = state.lightAtMs,
            // 1.53：限速随协议状态一起落地（广播路径经 feedSpeedLimit 写入协议层）
            speedLimit = state.speedLimit,
            speedLimitAtMs = state.speedLimitAtMs,
            // 1.60：巡航按方向分行的红绿灯倒计时
            cruiseLights = state.cruiseLights,
            connected = state.connected || cardState.connected,
            lastUpdateMs = System.currentTimeMillis()
        )
        // 1.22/1.26：AIDL 30407 引导信息（转向 / 道路名 / 剩余距离 / 全程信息）落地到卡片。
        // 只有真正收到过 30407 才覆盖，避免把广播路径写进来的值冲掉。
        if (protocol?.hasGuideData == true) {
            cardState = cardState.copy(
                turnMask = state.turnMask,
                turnAtMs = if (state.turnMask != 0) state.turnAtMs else cardState.turnAtMs,
                roadName = state.roadName ?: cardState.roadName,
                distanceText = state.distanceText ?: cardState.distanceText,
                routeRemainDisM = state.routeRemainDisM,
                routeRemainTimeS = state.routeRemainTimeS,
                segRemainDisM = state.segRemainDisM,
                guideChangedAtMs = if (guideChanged || cardState.guideChangedAtMs == 0L) {
                    nowMs
                } else {
                    cardState.guideChangedAtMs
                }
            )
        }
        pushCardToPresentation()
    }

    /** 1.31：最近一次转向 / 车道原始值（主界面诊断行显示用）。 */
    val protocolDebug: String
        get() = protocol?.debugInfo ?: ""

    /** 1.35：红绿灯倒数微调偏移（主界面「红绿灯倒数微调」按钮调用）。 */
    fun setLightSecondsOffset(seconds: Int) {
        mainHandler.post {
            presentation?.cardView?.let { card ->
                card.lightSecondsOffset = seconds
                card.refresh()
            }
        }
    }

    /**
     * 1.33：让 HUD 卡片立刻重绘一帧。
     * 手动红绿灯的倒数由截止时刻实时算出，视图自己会用 250ms 心跳刷新，
     * 所以这里不再需要每秒递减的 ticker（之前两个 ticker + 自动数据三方
     * 同时改同一个秒数，正是「倒计时一直闪烁」的根因）。
     */
    private fun refreshCardView() {
        val card = presentation?.cardView
        if (card != null) {
            card.layout = config.cardLayout
            card.cruiseLayout = config.cardLayoutCruise
            card.lightSecondsOffset = config.lightSecondsOffset
            card.refresh()
        } else if (presentation == null) {
            ensurePresentation()
        }
    }

    /** 把当前卡片状态推给 HUD 视图（布局每次从配置读，主界面调整下一帧生效）。 */
    private fun pushCardToPresentation() {
        val snapshot = cardState
        val card = presentation?.cardView
        if (card != null) {
            card.layout = config.cardLayout
            card.cruiseLayout = config.cardLayoutCruise
            card.lightSecondsOffset = config.lightSecondsOffset
            card.update(snapshot)
        } else if (presentation == null) {
            // HUD 还没挂上（或刚掉线），把窗口重试一次
            ensurePresentation()
        }
    }

    private fun ensurePresentation() {
        val probe = HudDisplay.inspect(this, config.targetDisplayId)
        when (probe) {
            is HudDisplay.Probe.Blocked -> {
                StatusBus.publish(Status.failed("HUD 不可用：${probe.reason}"))
                teardownPresentation()
                // 刻意不拆会话：HUD 只是暂时不在线，屏回来要能无缝接上
                session?.detachTarget()
                bgSession?.detachTarget()
            }

            is HudDisplay.Probe.Ready -> {
                val existing = presentation
                if (existing != null && existing.isShowing) {
                    existing.attachIfNeeded()
                    return
                }
                teardownPresentation()
                val p = HudPresentation(this, probe.display, this, cardMode = mode == MODE_CARD)
                presentation = p
                try {
                    p.show()
                    if (mode == MODE_CARD) {
                        p.cardView?.let { card ->
                            card.layout = config.cardLayout
                            card.cruiseLayout = config.cardLayoutCruise
                            card.lightSecondsOffset = config.lightSecondsOffset
                            card.update(cardState)
                        }
                        StatusBus.publish(
                            Status.casting("HUD 卡片已常驻 ${HudDisplay.describe(probe.display)}，等待高德导航数据")
                        )
                    } else {
                        StatusBus.publish(
                            Status.casting("HUD 已连接 ${HudDisplay.describe(probe.display)}，等待画面")
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Presentation 显示失败", t)
                    StatusBus.publish(Status.failed("HUD 窗口创建失败：${t.message}"))
                    presentation = null
                }
            }
        }
    }

    override fun onSurfaceReady(holder: SurfaceHolder) {
        targetSurface = holder.surface
        val frame = holder.surfaceFrame
        surfaceWidth = if (frame.width() > 0) frame.width() else HudDisplay.TARGET_WIDTH
        surfaceHeight = if (frame.height() > 0) frame.height() else HudDisplay.TARGET_HEIGHT

        if (mode == MODE_TEST) {
            val s = testSession
            if (s == null) {
                val created = TestPatternSession()
                created.listener = object : TestPatternSession.Listener {
                    override fun onStarted() {
                        StatusBus.publish(
                            Status.casting("彩条测试运行中：HUD 现在应显示移动彩条（8 色竖条 + 白条）")
                        )
                    }

                    override fun onError(message: String) {
                        StatusBus.publish(Status.failed("彩条测试失败：$message"))
                    }

                    override fun onStopped(reason: String) {
                        StatusBus.publish(Status.idle(reason))
                        testSession = null
                    }
                }
                testSession = created
                created.start(holder.surface, surfaceWidth, surfaceHeight)
                // 会话此时才真正存在，把缓存的预览画面口补投一次（1.10）
                PreviewBus.replay()
            }
            return
        }

        if (mode == MODE_BACKGROUND) {
            val s = bgSession
            if (s == null) {
                // 兜底：HUD 表面比 begin 先到（极端时序）。直接建 session，start 会带上 pending surface
                StatusBus.publish(Status.casting("后台模式：正在创建虚拟屏…"))
                val created = BgCastSession(this, config, config.bgWidth, config.bgHeight, config.bgDpi)
                created.listener = bgListener()
                bgSession = created
                created.attachTarget(holder.surface, surfaceWidth, surfaceHeight)
                wirePreviewBus()
                PreviewBus.replay()
            } else {
                s.attachTarget(holder.surface, surfaceWidth, surfaceHeight)
            }
            return
        }

        val data = resultData ?: return
        val s = session
        if (s == null) {
            val created = ProjectionSession(this, resultCode, data, config)
            created.listener = object : ProjectionSession.Listener {
                override fun onStarted(sourceWidth: Int, sourceHeight: Int) {
                    StatusBus.publish(
                        Status.casting("投屏中：源 ${sourceWidth}×${sourceHeight} → HUD ${surfaceWidth}×${surfaceHeight}")
                    )
                }

                override fun onError(message: String) {
                    StatusBus.publish(Status.failed("投屏失败：$message"))
                }

                override fun onWarning(message: String) {
                    StatusBus.publish(Status.failed("诊断：$message"))
                }

                override fun onStopped(reason: String) {
                    StatusBus.publish(Status.idle(reason))
                    session = null
                }
            }
            session = created
            created.start(holder.surface, surfaceWidth, surfaceHeight)
            // 会话此时才真正存在，把缓存的预览画面口补投一次（1.10）
            PreviewBus.replay()
        } else {
            s.attachTarget(holder.surface, surfaceWidth, surfaceHeight)
        }
    }

    override fun onSurfaceResized(width: Int, height: Int) {
        surfaceWidth = if (width > 0) width else HudDisplay.TARGET_WIDTH
        surfaceHeight = if (height > 0) height else HudDisplay.TARGET_HEIGHT
    }

    override fun onSurfaceDestroyed() {
        targetSurface = null
        // 只停渲染，不拆会话：HUD 窗口重建后能马上接回去
        session?.detachTarget()
        bgSession?.detachTarget()
        // 彩条测试没有会话可挂，表面没了直接停，等重连时重建
        if (mode == MODE_TEST) {
            val t = testSession
            testSession = null
            t?.stop("HUD 表面销毁，等待重连")
        }
    }

    override fun onPresentationDismissed() {
        presentation = null
        targetSurface = null
    }

    private fun checkForeground() {
        if (!config.stopWhenGaodeBackgrounded) {
            backgroundSince = 0L
            return
        }
        val w = watcher ?: return
        when (w.isGaodeForeground()) {
            true -> backgroundSince = 0L
            false -> {
                if (backgroundSince == 0L) {
                    backgroundSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - backgroundSince > 20_000L) {
                    stopEverything("高德已离开前台超过 20 秒，自动停止")
                    stopSelf()
                }
            }

            null -> Unit // 没有使用情况访问权限，无法判断，保持手动模式
        }
    }

    private fun teardownPresentation() {
        val p = presentation
        presentation = null
        if (p == null) return
        try {
            // 1.27：先让卡片画成纯黑屏，稍等一帧再 dismiss——部分车机 HUD 显示器
            // 在窗口销毁后保留最后一帧，直接 dismiss 会把旧卡片画面残留在 HUD 上
            p.cardView?.clearToBlack()
        } catch (_: Throwable) {
        }
        mainHandler.postDelayed({
            try {
                p.dismiss()
            } catch (_: Throwable) {
            }
        }, 350L)
    }

    private fun teardownSession(reason: String) {
        val s = session
        session = null
        s?.stop(reason)
        val b = bgSession
        bgSession = null
        b?.stop(reason)
        val t = testSession
        testSession = null
        t?.stop(reason)
    }

    private fun stopEverything(reason: String) {
        running = false
        foregroundTicker?.let { mainHandler.removeCallbacks(it) }
        foregroundTicker = null
        diagTicker?.let { mainHandler.removeCallbacks(it) }
        diagTicker = null
        cardWatchdog?.let { mainHandler.removeCallbacks(it) }
        editSync?.let { mainHandler.removeCallbacks(it) }
        cardWatchdog = null
        lastDiagFrames = -1L
        lastFrameChangeAt = 0L
        PreviewBus.clear()
        teardownSession(reason)
        teardownPresentation()
        watcher?.stop()
        watcher = null
        protocol?.stop()
        protocol = null
        HudDisplay.unregisterDisplayListener(this, displayListener)
        displayListener = null
        StatusBus.publish(Status.idle(reason))
    }

    private fun startAsForeground() {
        val title = when (mode) {
            MODE_BACKGROUND -> "HUD 后台投屏运行中"
            MODE_TEST -> "HUD 彩条测试运行中"
            MODE_CARD -> "HUD 导航卡片常驻中"
            else -> "HUD 投屏运行中"
        }
        val text = when (mode) {
            MODE_BACKGROUND -> "高德在虚拟屏后台渲染，点击回到控制页"
            MODE_TEST -> "输出链路测试，点击回到控制页"
            MODE_CARD -> "车道 / 推荐车道 / 红绿灯倒数常驻 HUD，点击回到控制页"
            else -> "点击回到控制页"
        }
        val notification = buildNotification(title, text)
        // 卡片 / 彩条模式不需要录屏，用 specialUse；
        // 开机启动时没有任何录屏授权，若声明 mediaProjection 类型会被系统直接拒绝。
        val preferredType = if (mode == MODE_CARD || mode == MODE_TEST) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val started = tryStartForeground(notification, preferredType)
            if (!started) {
                val fallback = if (preferredType == ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                if (!tryStartForeground(notification, fallback)) {
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (t: Throwable) {
                        Log.e(TAG, "startForeground 全部失败：${t.message}")
                    }
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
        }
    }

    /** 返回是否成功进入前台（失败不抛出，交由调用方降级）。 */
    private fun tryStartForeground(notification: Notification, type: Int): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, notification, type)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground(type=$type) 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            101,
            Intent(this, HudCastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止投屏", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "HUD 投屏", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        channel.description = "保持投屏会话的前台通知"
        nm.createNotificationChannel(channel)
    }
}

/** 极简状态总线：服务和界面在同一个进程，够用。 */
data class Status(val text: String, val level: Level) {
    enum class Level { IDLE, CASTING, ERROR }

    companion object {
        fun idle(text: String) = Status(text, Level.IDLE)
        fun casting(text: String) = Status(text, Level.CASTING)
        fun failed(text: String) = Status(text, Level.ERROR)
    }
}

object StatusBus {
    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

    /** 服务可以把通知栏更新挂进来，任何页面退出后进度仍可见。 */
    @Volatile
    var notifier: ((Status) -> Unit)? = null

    @Volatile
    var last: Status = Status.idle("未开始")
        private set

    fun publish(status: Status) {
        last = status
        listeners.forEach { it(status) }
        notifier?.invoke(status)
    }

    fun register(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(last)
    }

    fun unregister(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }
}
