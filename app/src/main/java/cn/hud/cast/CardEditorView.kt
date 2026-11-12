package cn.hud.cast

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 卡片布局编辑器：把 HUD 卡片原样画在控制页上，直接拖动摆放每个元素。
 *
 * 交互（只在 [editingEnabled] 打开时接管手势）：
 *   · 单指按住某元素拖动 → 移动该元素（超出画面自动夹住）
 *   · 双指捏合          → 缩放当前选中元素（0.1x ~ 3.0x）
 *   · 点空白处          → 取消选择
 *
 * 编辑态下会向父级申请「禁止拦截触摸」，避免和页面上下滚动抢手势；
 * 关闭编辑态时完全放手，页面照常滑动。
 *
 * 每次改动都通过 [onLayoutChanged] 回调，由界面立即持久化并推给正在运行的 HUD。
 */
class CardEditorView(context: Context) : HudCardView(context) {

    var onLayoutChanged: ((CardLayout) -> Unit)? = null
    var onSelectChanged: ((CardElement?) -> Unit)? = null

    /** 编辑模式开关：关闭时不消费任何触摸事件，页面滚动不受影响。 */
    var editingEnabled = false
        set(value) {
            field = value
            if (!value) {
                dragging = null
                pinching = false
            }
            // 视觉提示：编辑中背景带一点蓝，一眼分清当前能不能拖
            setBackgroundColor((if (value) 0xFF0D1B2A else 0xFF000000L).toInt())
            refresh()
        }

    private var dragging: CardElement? = null
    private var pinching = false
    private var lastDesignX = 0f
    private var lastDesignY = 0f
    private var pinchStartDistance = 0f
    private var pinchStartScale = 1f
    private var downX = 0f
    private var downY = 0f

    init {
        editMode = true
        demoMode = true
        layout = CardLayout()
        setBackgroundColor(0xFF000000.toInt())
    }

    fun applyLayout(newLayout: CardLayout) {
        // 1.58：巡航编辑必须写入 cruiseLayout（绘制 / hitTest 都读它），
        // 之前误写导航 layout，导致巡航选中后拖不动、改动也不保存
        if (demoCruise) cruiseLayout = newLayout else layout = newLayout
        refresh()
    }

    fun selectElement(e: CardElement?) {
        selected = e
        onSelectChanged?.invoke(e)
        refresh()
    }

    fun scaleSelected(delta: Float) {
        val sel = selected ?: return
        val box = editLayout().box(sel)
        box.scale = (box.scale * delta).coerceIn(0.1f, 3.0f)
        afterChange()
    }

    /** 滑块直接设置的倍数 */
    fun setSelectedScale(value: Float) {
        val sel = selected ?: return
        editLayout().box(sel).scale = value.coerceIn(0.1f, 3.0f)
        afterChange()
    }

    fun currentScale(): Float = selected?.let { editLayout().box(it).scale } ?: 1f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 编辑开关关闭：不消费事件，父级 ScrollView 正常滚动
        if (!editingEnabled) return false

        val (dx, dy) = toDesign(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 申请独占手势：拖动过程中父级不得拦截（页面不再跟着滚）
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                val hit = hitTest(dx, dy) ?: selected
                selectElement(hit)
                dragging = hit
                pinching = false
                lastDesignX = dx
                lastDesignY = dy
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    dragging = null
                    pinching = selected != null
                    pinchStartDistance = distanceBetween(event)
                    pinchStartScale = selected?.let { editLayout().box(it).scale } ?: 1f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && pinching && selected != null) {
                    val d = distanceBetween(event)
                    if (pinchStartDistance > 4f && d > 4f) {
                        val target = (pinchStartScale * d / pinchStartDistance).coerceIn(0.1f, 3.0f)
                        editLayout().box(selected!!).scale = target
                        afterChange()
                    }
                    return true
                }
                val el = dragging ?: return true
                val box = editLayout().box(el)
                val nx = box.cx + (dx - lastDesignX) / 480f
                val ny = box.cy + (dy - lastDesignY) / 240f
                box.cx = nx.coerceIn(0.04f, 0.96f)
                box.cy = ny.coerceIn(0.06f, 0.94f)
                lastDesignX = dx
                lastDesignY = dy
                afterChange()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                pinching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (abs(event.x - downX) < 4f && abs(event.y - downY) < 4f && selected == null) {
                    selectElement(null)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun distanceBetween(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun afterChange() {
        refresh()
        onLayoutChanged?.invoke(editLayout())
    }
}
