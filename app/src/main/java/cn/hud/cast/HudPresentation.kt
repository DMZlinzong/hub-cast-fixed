package cn.hud.cast

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager

/**
 * 在 HUD 屏上开一个全屏、不可交互的 Presentation，里面放一个 SurfaceView。
 *
 * 窗口参数照搬 hud-probe 的 HudPresentation：
 *   clearFlags(FLAG_KEEP_SCREEN_ON | FLAG_DIM_BEHIND)
 *   addFlags(FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL)
 *   MATCH_PARENT / gravity=CENTER / format=TRANSLUCENT
 *
 * 这个 SurfaceView 的 Surface 就是画面落地的目标 Surface，
 * 后面 MediaProjection 的 VirtualDisplay 直接往它上面输出。
 */
class HudPresentation(
    context: Context,
    display: Display,
    private val callback: Callback,
    private val cardMode: Boolean = false
) : Presentation(context, display, android.R.style.Theme_Material_NoActionBar) {

    interface Callback {
        fun onSurfaceReady(holder: SurfaceHolder)
        fun onSurfaceResized(width: Int, height: Int)
        fun onSurfaceDestroyed()
        fun onPresentationDismissed()
    }

    private var surfaceView: SurfaceView? = null

    /** 卡片模式下的渲染视图（车道 / 推荐车道 / 红绿灯倒数） */
    var cardView: HudCardView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)

        val window = window ?: return
        window.setTitle(TITLE)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        val lp = window.attributes
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.CENTER
        lp.x = 0
        lp.y = 0
        lp.format = PixelFormat.TRANSLUCENT
        window.attributes = lp

        if (cardMode) {
            val card = HudCardView(context)
            cardView = card
            setContentView(
                card,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnDismissListener { callback.onPresentationDismissed() }
            return
        }

        val view = SurfaceView(context)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                callback.onSurfaceReady(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                callback.onSurfaceResized(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                callback.onSurfaceDestroyed()
            }
        })

        setContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        surfaceView = view

        setOnDismissListener { callback.onPresentationDismissed() }
    }

    /** 只在 HUD 屏真的掉线时才需要主动收窗口。 */
    fun attachIfNeeded() {
        if (!isShowing) {
            try {
                show()
            } catch (_: Throwable) {
                // HUD 屏在 show 的瞬间掉了，交给 DisplayListener 重试
            }
        }
    }

    companion object {
        private const val TITLE = "HUD_CAST_1.0"
    }
}
