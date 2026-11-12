package cn.hud.cast

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * 后台虚拟屏模式。
 *
 * 原理与前台镜像完全不同：
 *   App 以 owner 身份创建一块 private 虚拟屏，把高德启动到那块屏上。
 *   高德在虚拟屏持续渲染，主屏完全空出来做别的事——这才是真正的"后台"。
 *   虚拟屏的画面从 SurfaceTexture 出来，走同一套 GL 裁剪管线落到 HUD。
 *
 * 全程不需要 MediaProjection / 录屏授权：画面是我们自己的屏，不是抓别人的。
 *
 * 1.2 的时序改动（修复"卡在启动"）：
 *   点开始 → 立即建 GL 管线 + 虚拟屏 → 立即回调拉高德。
 *   HUD 的 SurfaceView 表面什么时候就绪，什么时候 attachWindow 开始往 HUD 画。
 *   两边完全解耦，HUD 没准备好也不会阻塞启动。
 *
 * 已知限制：
 *   1. 虚拟屏收不到触摸事件（没有 input 通道），目的地必须提前在主屏设好；
 *      需要改路线时用「拉回主屏」把高德挪回来。
 *   2. 停止投屏会销毁虚拟屏，高德在那块屏上的 activity 会跟着被销毁
 *      （导航语音由服务继续，不受影响；地图 UI 需要重新打开）。
 */
class BgCastSession(
    private val context: Context,
    private val config: CastConfig,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val displayDpi: Int
) {

    interface Listener {
        /** 虚拟屏已就绪，拿着这个 id 去把高德拉进来。必须在主线程的 Activity 里调 startActivity。 */
        fun onDisplayReady(displayId: Int, width: Int, height: Int)
        fun onStarted()
        /** HUD 表面已挂上，画面开始输出。 */
        fun onHudAttached()
        fun onError(message: String)
        fun onStopped(reason: String)
    }

    var listener: Listener? = null

    // ---- 诊断计数（1.3）：服务每 3 秒读一次，状态栏直接报帧数，黑屏能定位到环 ----
    @Volatile
    var frameCount: Long = 0L
        private set

    @Volatile
    var hudAttached: Boolean = false
        private set

    /** 输出链路测试彩条是否还在跑（收到第一帧真实画面后自动停）。 */
    @Volatile
    var testPatternRunning: Boolean = false
        private set

    val startedAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var gotFirstFrame = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var blitter: SurfaceBlitter? = null

    private var drawScheduled = false
    private var running = false

    /** HUD 表面是否已挂上；未挂上时虚拟屏照跑、高德照渲染，只是不往 HUD 画。 */
    @Volatile
    private var renderEnabled = false

    /** 控制页诊断预览是否已挂上；挂上后即使 HUD 没接，也要持续画帧喂给预览。 */
    @Volatile
    private var previewEnabled = false

    /** HUD 还没就绪时先暂存 attach 请求，start 完成后立即挂。 */
    private var pendingSurface: Surface? = null
    private var pendingViewWidth = 0
    private var pendingViewHeight = 0

    @Volatile
    private var cachedCrop: CropRect = CropRect.FULL

    @Volatile
    private var cachedMode: ScaleMode = ScaleMode.FIT

    private var frameCounter = 0

    private val drawRunnable = Runnable {
        drawScheduled = false
        if (!running || (!renderEnabled && !previewEnabled)) return@Runnable
        val st = blitter?.surfaceTexture ?: return@Runnable
        try {
            st.updateTexImage()
            if (++frameCounter % 60 == 0) {
                cachedCrop = config.effectiveCrop()
                cachedMode = config.scaleMode
            }
            blitter?.draw(cachedCrop, cachedMode)
            frameCount++
            gotFirstFrame = true
        } catch (t: Throwable) {
            Log.w(TAG, "绘制帧失败：${t.message}")
        }
    }

    /** 没有真实画面时持续输出测试彩条，把「链路通不通」和「有没有内容」分开验证。 */
    private val testRunnable = object : Runnable {
        override fun run() {
            if (!running || gotFirstFrame) {
                testPatternRunning = false
                return
            }
            try {
                blitter?.drawTestPattern(System.currentTimeMillis() - startedAtMs)
            } catch (_: Throwable) {
            }
            handler?.postDelayed(this, 500L)
        }
    }

    private fun startTestPattern() {
        if (testPatternRunning) return
        testPatternRunning = true
        handler?.post(testRunnable)
        handler?.post(previewKeepAlive)
    }

    /** 预览保活：高德停止出帧时，每 2 秒把最后一帧重画到预览/HUD，窗口重挂后不黑屏。 */
    private val previewKeepAlive = object : Runnable {
        override fun run() {
            if (!running) return
            if (previewEnabled) {
                try {
                    blitter?.draw(cachedCrop, cachedMode)
                } catch (_: Throwable) {
                }
            }
            handler?.postDelayed(this, 2_000L)
        }
    }

    fun detachTarget() {
        renderEnabled = false
        hudAttached = false
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        handler?.post {
            try {
                blitter?.attachPreview(surface, width, height)
                previewEnabled = blitter?.hasPreview() ?: false
                scheduleDraw()
            } catch (t: Throwable) {
                Log.w(TAG, "挂载预览失败：${t.message}")
            }
        }
    }

    fun detachPreview() {
        handler?.post {
            blitter?.detachPreview()
            previewEnabled = false
        }
    }

    private fun scheduleDraw() {
        if (!drawScheduled && running) {
            drawScheduled = true
            handler?.post(drawRunnable)
        }
    }

    /** 启动：建 GL 管线 + 虚拟屏。不等 HUD 表面，HUD 表面由 attachTarget 挂上来。 */
    fun start() {
        if (running) {
            attachPending()
            return
        }
        val ht = HandlerThread("hud-cast-bg")
        ht.start()
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        h.post {
            try {
                startOnGlThread()
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "启动后台虚拟屏失败", t)
                stopInternal()
                listener?.onError(msg)
            }
        }
    }

    private fun startOnGlThread() {
        val h = handler ?: return

        cachedCrop = config.effectiveCrop()
        cachedMode = config.scaleMode

        val blit = SurfaceBlitter()
        val texture = blit.init()
        texture.setDefaultBufferSize(displayWidth, displayHeight)
        texture.setOnFrameAvailableListener({
            if (!drawScheduled && running && (renderEnabled || previewEnabled)) {
                drawScheduled = true
                handler?.post(drawRunnable)
            }
        }, h)
        blit.setSourceSize(displayWidth, displayHeight)
        blitter = blit

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val vd = dm.createVirtualDisplay(
            "hud-cast-bg",
            displayWidth,
            displayHeight,
            displayDpi,
            Surface(texture),
            // 不带 PUBLIC 标志 => private display，owner 即本 App；
            // PRESENTATION 让它能承载 activity；OWN_CONTENT_ONLY 防止它退化成主屏镜像
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            null,
            h
        ) ?: throw IllegalStateException("创建虚拟屏失败（DisplayManager 返回空）")
        virtualDisplay = vd

        running = true
        val id = vd.display?.displayId ?: -1
        if (id < 0) throw IllegalStateException("虚拟屏创建后拿不到 displayId")

        listener?.onStarted()
        listener?.onDisplayReady(id, displayWidth, displayHeight)
        startTestPattern()
        handler?.post { attachPending() }
    }

    /** HUD 表面就绪后挂上来开始画。HUD 掉线重连时反复调用。 */
    fun attachTarget(surface: Surface, viewWidth: Int, viewHeight: Int) {
        if (!running) {
            pendingSurface = surface
            pendingViewWidth = viewWidth
            pendingViewHeight = viewHeight
            start()
            return
        }
        handler?.post {
            val h = handler ?: return@post
            try {
                blitter?.attachWindow(surface, viewWidth, viewHeight)
                renderEnabled = true
                hudAttached = true
                h.post(drawRunnable)
                listener?.onHudAttached()
            } catch (t: Throwable) {
                Log.e(TAG, "挂载 HUD 表面失败", t)
                listener?.onError("挂载 HUD 表面失败：${t.message}")
            }
        }
    }

    private fun attachPending() {
        val s = pendingSurface ?: return
        if (!running) return
        pendingSurface = null
        attachTarget(s, pendingViewWidth, pendingViewHeight)
    }

    /** 把高德启动到虚拟屏。必须由前台 Activity 调用（Activity context 才稳）。 */
    fun launchGaode(activity: Activity) {
        val id = virtualDisplay?.display?.displayId ?: -1
        if (id < 0) {
            listener?.onError("虚拟屏尚未就绪")
            return
        }
        val intent = GaodeLaunch.launchIntent(activity)
            ?: run {
                listener?.onError("没找到已安装的高德地图")
                return
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(id)
        try {
            activity.startActivity(intent, opts.toBundle())
        } catch (t: Throwable) {
            Log.e(TAG, "高德拉入虚拟屏失败", t)
            listener?.onError("高德启动失败：${t.message}")
        }
    }

    /** 把高德挪回主屏（同一个 intent，不带 display 参数）。 */
    fun bringGaodeBack(activity: Activity) {
        val intent = GaodeLaunch.launchIntent(activity) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    fun stop(reason: String) {
        if (handler == null) {
            listener?.onStopped(reason)
            return
        }
        handler?.post { stopInternal() }
        listener?.onStopped(reason)
    }

    private fun stopInternal() {
        running = false
        renderEnabled = false
        previewEnabled = false
        hudAttached = false
        testPatternRunning = false
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            blitter?.release()
        } catch (_: Throwable) {
        }
        blitter = null
        releaseThread()
    }

    private fun releaseThread() {
        val t = thread
        thread = null
        handler = null
        try {
            t?.quitSafely()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "HudCast"
    }
}

/** 高德启动入口：手机版优先，车机版兜底。 */
object GaodeLaunch {
    private val PACKAGES = listOf("com.autonavi.minimap", "com.autonavi.amapauto")

    fun launchIntent(context: Context): Intent? {
        for (pkg in PACKAGES) {
            val intent = try {
                context.packageManager.getLaunchIntentForPackage(pkg)
            } catch (_: Throwable) {
                null
            }
            if (intent != null) return intent
        }
        return null
    }
}

/** 后台模式的跨页事件：虚拟屏就绪后通知 MainActivity 自动把高德拉进去。 */
object BgEvents {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    fun emit(displayId: Int) {
        listeners.forEach { it(displayId) }
    }

    fun register(listener: (Int) -> Unit) {
        listeners.add(listener)
    }

    fun unregister(listener: (Int) -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * 诊断预览总线（1.3）：控制页的 SurfaceView 生命周期事件转发给当前会话，
 * 会话把虚拟屏原始画面画一份到预览上——黑屏时一眼看出是「没进虚拟屏」还是「HUD 断了」。
 *
 * 1.9：缓存最近一次 offer。预览的画面口通常在会话建立之前就注册（页面打开即触发
 * surfaceCreated），此时 consumer 还没挂上、offer 会丢失。缓存后由 setConsumer
 * 在会话接线完成时立即重放，避免「先开页面后点投屏」导致预览永远黑屏。
 */
object PreviewBus {
    private class Offer(val surface: Surface, val width: Int, val height: Int)

    @Volatile
    private var lastOffer: Offer? = null

    @Volatile
    var consumer: ((Surface, Int, Int) -> Unit)? = null
        private set

    @Volatile
    var destroyed: (() -> Unit)? = null
        private set

    fun offer(surface: Surface, width: Int, height: Int) {
        lastOffer = Offer(surface, width, height)
        consumer?.invoke(surface, width, height)
    }

    /** 服务侧接线：挂上的同时立即重放最近一次画面口，消除注册时序差。 */
    fun setConsumer(c: ((Surface, Int, Int) -> Unit)?) {
        consumer = c
        lastOffer?.let { c?.invoke(it.surface, it.width, it.height) }
    }

    /**
     * 会话创建后调用：把缓存的画面口再投递一次。
     * setConsumer 重放时会话往往还没建（会话要等 HUD 表面就绪），
     * 这次重放补上「接线早于会话创建」的空档。
     */
    fun replay() {
        val o = lastOffer ?: return
        consumer?.invoke(o.surface, o.width, o.height)
    }

    fun setDestroyed(d: (() -> Unit)?) {
        destroyed = d
    }

    fun closed() {
        // 画面口已销毁，缓存的 surface 失效，不能留给下次重放
        lastOffer = null
        destroyed?.invoke()
    }

    fun clear() {
        consumer = null
        destroyed = null
        lastOffer = null
    }
}
