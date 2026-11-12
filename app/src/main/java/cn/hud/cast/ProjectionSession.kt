package cn.hud.cast

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager

/**
 * 一次投屏会话：MediaProjection → VirtualDisplay → SurfaceTexture → GL → HUD Surface。
 *
 * 关键点：
 *  1. 必须先 registerCallback 再 createVirtualDisplay（Android 14 起是硬性要求）。
 *  2. VirtualDisplay 用源屏真实分辨率建，缩放在 GL 里做，保证文字不发糊。
 *  3. 目标 Surface 换掉（HUD 掉线重连）时，只重建 EGL surface，不动 MediaProjection。
 */
class ProjectionSession(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent,
    private val config: CastConfig
) {

    interface Listener {
        fun onStarted(sourceWidth: Int, sourceHeight: Int)
        fun onError(message: String)
        fun onStopped(reason: String)

        /** 诊断警告（1.8）：帧在流但内容全黑等「链路活着、内容死了」的情况。 */
        fun onWarning(message: String) {}
    }

    var listener: Listener? = null

    // ---- 诊断计数（1.3）----
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

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var blitter: SurfaceBlitter? = null
    private var targetSurface: Surface? = null

    private var drawScheduled = false
    private var running = false

    /** 目标 Surface 是否可用；HUD 窗口销毁时置 false，避免往死 surface 上 swap。 */
    @Volatile
    private var renderEnabled = false

    /** 每帧读 SharedPreferences 太浪费，缓存一份，约每秒刷新一次。 */
    @Volatile
    private var cachedCrop: CropRect = CropRect.FULL

    @Volatile
    private var cachedMode: ScaleMode = ScaleMode.FIT

    private var frameCounter = 0

    /** 最近一次全幅亮度采样（0~1），-1 表示尚未采样。诊断「录屏返回黑屏」用。 */
    @Volatile
    var lastSampledBrightness: Float = -1f
        private set

    /** 亮度采样次数。 */
    @Volatile
    var brightnessSamples: Int = 0
        private set

    private var blackWarned = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler?.post {
                if (running) {
                    stopInternal("系统已停止投屏授权", notify = true)
                }
            }
        }
    }

    private val drawRunnable = Runnable {
        drawScheduled = false
        if (!running || !renderEnabled) return@Runnable
        val st = blitter?.surfaceTexture ?: return@Runnable
        try {
            st.updateTexImage()
            if (++frameCounter % 60 == 0) refreshConfigCache()
            blitter?.draw(cachedCrop, cachedMode)
            frameCount++
            gotFirstFrame = true
            // 黑帧检测（1.8）：第 12 / 42 / 72 …帧采样一次全幅亮度；
            // 连续 3 次都接近纯黑，判定车机禁止抓取主屏内容
            if (frameCounter % 30 == 12 && frameCounter <= 102) {
                val b = try {
                    blitter?.sampleFullBrightness() ?: -1f
                } catch (_: Throwable) {
                    -1f
                }
                lastSampledBrightness = b
                brightnessSamples++
                if (b in 0f..0.02f && brightnessSamples >= 3 && !blackWarned) {
                    blackWarned = true
                    listener?.onWarning(
                        "录屏在出帧但画面全黑（亮度≈0）：这台车机禁止抓取主屏内容，" +
                            "录屏镜像路线走不通。"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "绘制帧失败：${t.message}")
        }
    }

    /** 没有真实画面时持续输出测试彩条（1.4）。 */
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
    }

    /** 控制页诊断预览（1.4 起镜像模式也接）。 */
    fun attachPreview(surface: Surface, width: Int, height: Int) {
        handler?.post {
            try {
                blitter?.attachPreview(surface, width, height)
                scheduleDraw()
            } catch (t: Throwable) {
                Log.w(TAG, "挂载预览失败：${t.message}")
            }
        }
    }

    fun detachPreview() {
        handler?.post { blitter?.detachPreview() }
    }

    private fun scheduleDraw() {
        if (!drawScheduled && running) {
            drawScheduled = true
            handler?.post(drawRunnable)
        }
    }

    private fun refreshConfigCache() {
        cachedCrop = config.effectiveCrop()
        cachedMode = config.scaleMode
    }

    /** HUD 窗口销毁了，先停渲染，等新 Surface 来了再 attachTarget。 */
    fun detachTarget() {
        renderEnabled = false
        hudAttached = false
        targetSurface = null
    }

    fun start(surface: Surface, viewWidth: Int, viewHeight: Int) {
        if (running) {
            attachTarget(surface, viewWidth, viewHeight)
            return
        }
        targetSurface = surface

        val ht = HandlerThread("hud-cast-gl")
        ht.start()
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        h.post {
            try {
                startOnGlThread(surface, viewWidth, viewHeight)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "启动投屏失败", t)
                stopInternal("启动投屏失败：$msg", notify = false)
                listener?.onError(msg)
            }
        }
    }

    private fun startOnGlThread(surface: Surface, viewWidth: Int, viewHeight: Int) {
        val h = handler ?: return
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("getMediaProjection 返回 null，需要重新授权")
        projection = mp
        mp.registerCallback(projectionCallback, h)

        val source = sourceMetrics()
        refreshConfigCache()

        val blit = SurfaceBlitter()
        val texture = blit.init()
        texture.setDefaultBufferSize(source.width, source.height)
        texture.setOnFrameAvailableListener({
            if (!drawScheduled && running && (renderEnabled || blitter?.hasPreview() == true)) {
                drawScheduled = true
                handler?.post(drawRunnable)
            }
        }, h)
        blit.setSourceSize(source.width, source.height)
        blitter = blit

        val vd = mp.createVirtualDisplay(
            "hud-cast",
            source.width,
            source.height,
            source.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(texture),
            null,
            h
        )
        virtualDisplay = vd

        // HUD 表面就绪即挂载；挂载失败只影响显示，虚拟屏和授权继续在
        blit.attachWindow(surface, viewWidth, viewHeight)

        running = true
        renderEnabled = true
        hudAttached = true
        listener?.onStarted(source.width, source.height)
        // 首帧可能已经在建 display 期间到达，主动画一次，避免 HUD 黑屏等待
        h.post(drawRunnable)
        startTestPattern()
    }

    /** HUD 的 Surface 换了：重建 EGL 窗口目标，VirtualDisplay 继续跑。 */
    fun attachTarget(surface: Surface, viewWidth: Int, viewHeight: Int) {
        if (!running) {
            start(surface, viewWidth, viewHeight)
            return
        }
        handler?.post {
            try {
                blitter?.attachWindow(surface, viewWidth, viewHeight)
                renderEnabled = true
                hudAttached = true
                handler?.post(drawRunnable)
            } catch (t: Throwable) {
                Log.e(TAG, "重挂 HUD 目标失败", t)
                listener?.onError("重挂 HUD 目标失败：${t.message}")
            }
        }
    }

    fun stop(reason: String) {
        if (handler == null) return
        handler?.post { stopInternal(reason, notify = true) }
    }

    private fun stopInternal(reason: String, notify: Boolean) {
        if (!running) {
            releaseThread()
            return
        }
        running = false
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

        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        targetSurface = null

        if (notify) listener?.onStopped(reason)
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

    private data class SourceMetrics(val width: Int, val height: Int, val densityDpi: Int)

    private fun sourceMetrics(): SourceMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display: Display = wm.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        val dpi = context.resources.displayMetrics.densityDpi
        // 有些车机把 realSize 报成 0，兜个底，避免 VirtualDisplay 建失败
        val w = if (size.x > 0) size.x else 1280
        val h = if (size.y > 0) size.y else 720
        return SourceMetrics(w, h, if (dpi > 0) dpi else 160)
    }

    companion object {
        private const val TAG = "HudCast"
    }
}
