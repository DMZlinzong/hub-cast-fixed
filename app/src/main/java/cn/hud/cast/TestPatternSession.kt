package cn.hud.cast

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * 纯输出链路测试（1.7）：不建虚拟屏、不抓屏、不拉高德，
 * 直接往 HUD 的 Surface 上持续画移动彩条。
 *
 * 用途：把「投屏通道通不通」和「高德给不给画面」彻底分开——
 * 彩条出现在 HUD = 通道真实打通；HUD 黑 = 通道问题，与高德无关。
 */
class TestPatternSession {

    interface Listener {
        fun onStarted()
        fun onError(message: String)
        fun onStopped(reason: String)
    }

    var listener: Listener? = null

    /** 已画出的彩条帧数（每 500ms +1），用于确认循环在跑。 */
    @Volatile
    var frameCount: Long = 0L
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var blitter: SurfaceBlitter? = null

    @Volatile
    private var running = false

    private val startedAtMs = System.currentTimeMillis()

    fun start(surface: Surface, viewWidth: Int, viewHeight: Int) {
        if (running) return
        val ht = HandlerThread("hud-cast-test")
        ht.start()
        thread = ht
        val h = Handler(ht.looper)
        handler = h
        h.post {
            try {
                val blit = SurfaceBlitter()
                val texture = blit.init()
                texture.setDefaultBufferSize(HudDisplay.TARGET_WIDTH, HudDisplay.TARGET_HEIGHT)
                blit.setSourceSize(HudDisplay.TARGET_WIDTH, HudDisplay.TARGET_HEIGHT)
                blit.attachWindow(surface, viewWidth, viewHeight)
                blitter = blit
                running = true
                listener?.onStarted()
                h.post(drawLoop)
            } catch (t: Throwable) {
                Log.e("HudCast", "彩条测试启动失败", t)
                stopInternal()
                listener?.onError(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private val drawLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                blitter?.drawTestPattern(System.currentTimeMillis() - startedAtMs)
                frameCount++
            } catch (_: Throwable) {
            }
            handler?.postDelayed(this, 500L)
        }
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        handler?.post {
            try {
                blitter?.attachPreview(surface, width, height)
            } catch (_: Throwable) {
            }
        }
    }

    fun detachPreview() {
        handler?.post { blitter?.detachPreview() }
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
        try {
            blitter?.release()
        } catch (_: Throwable) {
        }
        blitter = null
        val t = thread
        thread = null
        handler = null
        try {
            t?.quitSafely()
        } catch (_: Throwable) {
        }
    }
}
