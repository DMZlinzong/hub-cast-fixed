package cn.hud.cast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.sqrt

/** 卡片上的可调元素 */
enum class CardElement { LANES, LIGHT, ROAD, DISTANCE, TURN, ETA, SPEEDLIMIT }

/** 单个元素的布局：中心点归一化坐标（0..1）+ 缩放倍数 */
data class ElementBox(var cx: Float, var cy: Float, var scale: Float)

/** 整张卡片的布局。默认值按 480×240 HUD 的黄金位置摆放。 */
data class CardLayout(
    var lanes: ElementBox = ElementBox(0.50f, 0.60f, 1.0f),
    var light: ElementBox = ElementBox(0.91f, 0.22f, 1.0f),
    var road: ElementBox = ElementBox(0.22f, 0.18f, 1.0f),
    var distance: ElementBox = ElementBox(0.12f, 0.80f, 1.0f),
    var turn: ElementBox = ElementBox(0.13f, 0.42f, 1.0f),
    var eta: ElementBox = ElementBox(0.78f, 0.80f, 1.0f),
    /** 1.53：限速牌（导航默认右下角——车道条几乎全宽，只有上下角有空位） */
    var speed: ElementBox = ElementBox(0.93f, 0.90f, 0.70f)
) {
    fun box(e: CardElement): ElementBox = when (e) {
        CardElement.LANES -> lanes
        CardElement.LIGHT -> light
        CardElement.ROAD -> road
        CardElement.DISTANCE -> distance
        CardElement.TURN -> turn
        CardElement.ETA -> eta
        CardElement.SPEEDLIMIT -> speed
    }

    /** 1.26 起存 6 段（含到达信息）；1.53 起存 7 段（追加限速）。旧格式缺项用默认位置。 */
    fun encode(): String = listOf(lanes, light, road, distance, turn, eta, speed).joinToString(";") {
        "%.4f,%.4f,%.3f".format(it.cx, it.cy, it.scale)
    }

    companion object {
        fun decode(raw: String?): CardLayout {
            val d = CardLayout()
            if (raw.isNullOrBlank()) return d
            val parts = raw.split(";")
            if (parts.size < 4) return d
            val boxes = parts.map { p ->
                val v = p.split(",")
                if (v.size != 3) return@map null
                val cx = v[0].toFloatOrNull() ?: return@map null
                val cy = v[1].toFloatOrNull() ?: return@map null
                val s = v[2].toFloatOrNull() ?: return@map null
                ElementBox(cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), s.coerceIn(0.1f, 3f))
            }
            if (boxes.any { it == null }) return d
            return CardLayout(
                lanes = boxes[0]!!,
                light = boxes[1]!!,
                road = boxes[2]!!,
                distance = boxes[3]!!,
                turn = boxes.getOrNull(4) ?: ElementBox(0.13f, 0.42f, 1.0f),
                eta = boxes.getOrNull(5) ?: ElementBox(0.78f, 0.80f, 1.0f),
                speed = boxes.getOrNull(6) ?: ElementBox(0.93f, 0.90f, 0.70f)
            )
        }

        /**
         * 1.52：巡航布局默认值——红绿灯在上、车道条居中、道路名在下，
         * 与 1.51 的固定巡航版式一致，用户可再自行调整。
         * 1.53：限速牌默认右上角。
         */
        fun cruiseDefault(): CardLayout = CardLayout(
            lanes = ElementBox(0.50f, 0.60f, 0.85f),
            light = ElementBox(0.50f, 0.25f, 1.0f),
            road = ElementBox(0.50f, 0.90f, 1.0f),
            speed = ElementBox(0.88f, 0.26f, 0.75f)
        )
    }
}

/**
 * HUD 常驻导航卡片。
 *
 * 设计坐标系固定 480×240（HUD 实际分辨率），绘制时等比铺满，任何 HUD 尺寸都不变形。
 *
 * 四个可调元素，位置与大小全部来自 [CardLayout]：
 *   LANES    车道指引（含推荐车道绿色高亮）
 *   LIGHT    红绿灯倒数
 *   ROAD     道路名 / 下一步动作
 *   DISTANCE 剩余距离
 *
 * 「常驻」语义：数据断流不隐藏、不白屏，保留最后一帧并降透明度。
 * 编辑态（[editMode]）下给选中元素画虚线框，供主界面拖动调节。
 */
open class HudCardView(context: Context) : View(context) {

    companion object {
        private const val DESIGN_W = 480f
        private const val DESIGN_H = 240f

        private val COLOR_BG = Color.BLACK
        /** 普通车道箭头：近纯白（图二样式） */
        private val COLOR_LANE_NORMAL = Color.parseColor("#F4F8FC")
        private val COLOR_LANE_ADVISED = Color.parseColor("#2FD07E")
        /** 车道分隔竖线 */
        private val COLOR_LANE_SEP = Color.parseColor("#C8D2DC")
        private val COLOR_TEXT = Color.parseColor("#E8EEF4")
        private val COLOR_TEXT_DIM = Color.parseColor("#8A949E")
        private val COLOR_RED = Color.parseColor("#FF4F4F")
        private val COLOR_GREEN = Color.parseColor("#2FD07E")
        private val COLOR_YELLOW = Color.parseColor("#FFC53D")
        private val COLOR_GRAY = Color.parseColor("#5A636C")
        private val COLOR_EDIT = Color.parseColor("#38BDF8")
        /** 红绿灯（1.24 参考图样式）：白色胶囊底 + 黑色数字 */
        private val COLOR_PILL_BG = Color.parseColor("#F2F4F6")
        private val COLOR_NUM_DARK = Color.parseColor("#14181C")
        /** 圆心暗色底：随红/绿/黄状态变化 */
        private val COLOR_INNER_RED = Color.parseColor("#3D1011")
        private val COLOR_INNER_GREEN = Color.parseColor("#0D3320")
        private val COLOR_INNER_YELLOW = Color.parseColor("#3D2F0E")

        /** 车道行基础尺寸（scale=1 时） */
        private const val LANE_CELL_W = 112f
        private const val LANE_HEIGHT = 150f

        /** 1.55：车道指引去背板，直接投在风挡上 */
        /** 推荐车道箭头：蓝色（对应高德推荐车道形态）；普通车道：白色 */
        private val COLOR_LANE_ADVISED_NEW = Color.parseColor("#3D9BFF")
        private val COLOR_LANE_NORMAL_NEW = Color.parseColor("#FFFFFF")
        /** 红绿灯面板（参考图一）：深黑胶囊 */
        private val COLOR_LIGHT_PANEL = Color.parseColor("#101318")

        /** 数据过期时限：车道 / 转向 / 红绿灯超过这个时长没有新数据就不画（1.17） */
        private const val STALE_MS = 8_000L

        /**
         * 1.36：车道消息心跳时间戳（laneMsgAtMs）仍由协议层维护，
         * 但 1.49 起绘制层不再按心跳过期隐藏车道——高德的清空 / 新数据
         * 驱动更新即可，避免高德降低推送频率时车道指引被误隐藏。
         */
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_EDIT
        strokeWidth = 1.6f
    }

    var state = HudCardState()
        private set

    var layout: CardLayout = CardLayout()

    /**
     * 1.52：巡航布局（独立于导航布局）。巡航只用到 LANES / LIGHT / ROAD
     * 三个元素，位置大小由用户在主界面单独编辑；导航布局不受影响。
     */
    var cruiseLayout: CardLayout = CardLayout.cruiseDefault()

    /** 编辑态：画选中框 + 使用示例数据 */
    var editMode = false
    var demoMode = false
    /** 1.52：编辑器预览巡航布局时置 true——演示数据切成巡航态、绘制走巡航分支 */
    var demoCruise = false
    var selected: CardElement? = null

    /** 1.27：强制纯黑（停止投屏时先清屏，防 HUD 显示器保留最后一帧） */
    @Volatile
    var forceBlack = false

    /**
     * 1.35：红绿灯倒数微调偏移（秒）。高德发的倒数与实际灯偶尔不同步，
     * 主界面可全局加/减；显示值最小钳到 1 秒。
     */
    var lightSecondsOffset = 0

    fun clearToBlack() {
        forceBlack = true
        postInvalidateOnAnimation()
    }

    fun update(newState: HudCardState) {
        state = newState
        postInvalidateOnAnimation()
    }

    fun refresh() = postInvalidateOnAnimation()

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        if (forceBlack) {
            canvas.drawColor(Color.BLACK)
            return
        }
        canvas.drawColor(COLOR_BG)
        val scale = minOf(width / DESIGN_W, height / DESIGN_H)
        canvas.save()
        canvas.translate((width - DESIGN_W * scale) / 2f, (height - DESIGN_H * scale) / 2f)
        canvas.scale(scale, scale)

        // 1.34：手动绘制模块已按需求移除，直接使用高德数据。
        val s = if (demoMode) demoState() else state
        val ageMs = if (s.lastUpdateMs <= 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - s.lastUpdateMs
        val stale = !demoMode && ageMs > 15_000L
        val alpha = if (stale) 150 else 255

        // 1.52 巡航精简模式：巡航（非导航）下只显示三样——红绿灯倒计时、
        // 车道指引、当前道路名，其余（大转向箭头 / 剩余距离 / 到达信息 /
        // 状态小字）一律不画；三元素位置大小来自独立的巡航布局
        // [cruiseLayout]，在主界面单独编辑。编辑器 demoCruise=true 时
        // 预览的就是这套巡航布局。
        if (if (demoMode) demoCruise else !s.navActive) {
            // 1.68：车机高德空闲（既没导航也没巡航）时 60075 红绿灯广播
            // 照样推送，之前无条件画灯导致等红灯时 HUD 常挂一个红绿灯。
            // 数据通道空闲（>15 秒没有巡航/导航数据）就只画限速牌，
            // 红绿灯 / 车道 / 道路名都不画；巡航数据恢复即自动重现。
            val cruiseActive = demoMode || !stale
            if (cruiseActive) {
                // 1.60：巡航红绿灯两行显示（直行一行、左转一行）
                drawCruiseLights(canvas, s, alpha, cruiseLayout.light)
                drawLanes(canvas, s, alpha, cruiseLayout.lanes)
                drawRoad(canvas, s, alpha, cruiseLayout.road)
            }
            drawSpeedLimit(canvas, s, alpha, cruiseLayout.speed)
            if (editMode) drawSelection(canvas, s)
            canvas.restore()
            return
        }

        drawStatusHint(canvas, s, alpha)
        drawTurn(canvas, s, alpha)
        drawLanes(canvas, s, alpha)
        drawLight(canvas, s, alpha)
        drawRoad(canvas, s, alpha)
        drawDistance(canvas, s, alpha)
        drawEta(canvas, s, alpha)
        drawSpeedLimit(canvas, s, alpha)
        if (stale) drawStaleHint(canvas)
        if (editMode) drawSelection(canvas, s)
        canvas.restore()
    }

    private fun demoState(): HudCardState {
        // 1.52：巡航布局编辑时演示巡航画面（只有车道 + 红绿灯 + 道路名）
        val cruise = demoCruise
        return HudCardState(
            lanes = listOf(
                HudLane(directions = 0x2 or 0x10, advised = false, number = 0, icon = 0),
                HudLane(directions = 0x1, advised = true, number = 1, icon = 0),
                HudLane(directions = 0x1 or 0x4, advised = false, number = 2, icon = 0)
            ),
            laneEnabled = true,
            lanesAtMs = System.currentTimeMillis(),
            laneMsgAtMs = System.currentTimeMillis(),
            lightStatus = 2,
            lightSeconds = 32,
            lightAtMs = System.currentTimeMillis(),
            // 1.53 修复：导航演示恢复大转向箭头（1.52 重写 demoState 时漏掉，
            // 导致导航布局预览里转向箭头变成占位框）；巡航无转向数据
            turnMask = if (cruise) 0 else 0x2,
            turnAtMs = System.currentTimeMillis(),
            // 1.49：圆内箭头 = 路口行驶方向（dir 字段），演示为左转灯
            lightTurnMask = 0x2,
            // 1.60：巡航演示两行红绿灯（直行绿 32 / 左转红 15）
            cruiseLights = if (cruise) listOf(
                CruiseLight(dirRaw = 4, status = 2, seconds = 32, dirMask = 0x1, atMs = System.currentTimeMillis()),
                CruiseLight(dirRaw = 1, status = 1, seconds = 15, dirMask = 0x2, atMs = System.currentTimeMillis())
            ) else emptyList(),
            segRemainDisM = if (cruise) 0 else 180,
            connected = true,
            navActive = !cruise,
            lastUpdateMs = System.currentTimeMillis(),
            roadName = "示例：梦芝路",
            speedLimit = 80,
            speedLimitAtMs = System.currentTimeMillis(),
            distanceText = if (cruise) null else "215m",
            routeRemainDisM = if (cruise) 0 else 12500,
            routeRemainTimeS = if (cruise) 0 else 1380
        )
    }

    private fun drawStatusHint(canvas: Canvas, s: HudCardState, alpha: Int) {
        if (editMode) return
        textPaint.textSize = 13f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = withAlpha(COLOR_TEXT_DIM, alpha)
        val label = when {
            s.lanes.isNotEmpty() -> if (s.navActive) "导航中" else "巡航中"
            s.connected -> if (s.navActive) "导航中 · 等待数据" else "巡航中 · 等待数据"
            else -> "未连接车机版高德"
        }
        canvas.drawText(label, 10f, 18f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    // 车道（1.26 参考图二样式）：深色圆角面板内，每条车道一支实心箭头，
    // 推荐车道纯白、普通车道暗蓝灰，车道之间浅色分隔竖线。
    // 1.36：不再无条件常驻——高德活跃时每秒都在推车道消息（心跳），
    // 超过 15 秒一条都没有 = 高德已收起车道指引，HUD 同步隐藏，
    // 否则过完路口后 HUD 还挂着旧车道，与高德画面不一致。
    private fun drawLanes(canvas: Canvas, s: HudCardState, alpha: Int, box: ElementBox = layout.lanes) {
        val lanes = s.lanes
        if (lanes.isEmpty() || !s.laneEnabled) {
            if (editMode) {
                placeholder(canvas, lanesBounds(), "车道指引")
            }
            return
        }
        val count = lanes.size.coerceAtMost(8)
        // 车道多时压缩格宽，面板不超出屏宽
        val cellW = minOf(LANE_CELL_W, (DESIGN_W - 40f) / count) * box.scale
        val height = LANE_HEIGHT * box.scale
        val pad = 10f * box.scale
        val totalW = cellW * count + pad * 2
        val left = box.cx * DESIGN_W - totalW / 2f
        val top = box.cy * DESIGN_H - height / 2f
        val bottom = top + height

        // 1.55：去掉深色圆角背板，箭头直接投在风挡上

        for (i in 0 until count) {
            val lane = lanes[i]
            val cx = left + pad + cellW * i + cellW / 2f
            val color = withAlpha(
                if (lane.advised) COLOR_LANE_ADVISED_NEW else COLOR_LANE_NORMAL_NEW,
                alpha
            )
            drawSolidArrow(
                canvas, lane.directions, cx,
                top + 12f * box.scale, bottom - 12f * box.scale, color, compact = true
            )
            // 分隔竖线（最后一条后面不画）
            if (i < count - 1) {
                stroke.color = withAlpha(Color.WHITE, alpha * 18 / 100)
                stroke.strokeWidth = 1.5f * box.scale.coerceIn(0.6f, 1.6f)
                val sx = left + pad + cellW * (i + 1)
                canvas.drawLine(sx, top + 12f * box.scale, sx, bottom - 12f * box.scale, stroke)
            }
        }
    }

    // 转向箭头（参考图样式：粗壮实心箭头——宽臂 + 大三角头，无背景板）
    private fun drawTurn(canvas: Canvas, s: HudCardState, alpha: Int) {
        val box = layout.turn
        val cx = box.cx * DESIGN_W
        val top = box.cy * DESIGN_H - 45f * box.scale
        val bottom = box.cy * DESIGN_H + 45f * box.scale
        if (s.turnMask == 0) {
            if (editMode) placeholder(canvas, turnBounds(), "转向箭头")
            return
        }
        drawSolidArrow(canvas, s.turnMask, cx, top, bottom, withAlpha(COLOR_TEXT, alpha))
    }

    /**
     * 1.34：实心车道箭头，严格按参考图样式：
     * - 直行：主干从底部直通顶部，顶端大三角头
     * - 左右转与直行共存：从主干约 46% 高度圆角弯出水平短臂 + 三角头
     * - 纯左右转（无直行）：主干到弯点后圆角弯出（L 形）
     * - 稍左/稍右：斜臂 + 三角头
     * - 掉头：与其它方向共存时从主干中低处向左弯出后向下（钩形，头朝下，
     *   即参考图左车道那个回勾）；纯掉头时主干到顶回环向下
     * compact=true 用于车道格（臂与臂长按格宽收紧，避免压到邻格）。
     */
    private fun drawSolidArrow(
        canvas: Canvas,
        dirs: Int,
        cx: Float,
        top: Float,
        bottom: Float,
        color: Int,
        compact: Boolean = false,
        fitIn: RectF? = null
    ) {
        var mask = dirs and 0x3F
        if (mask == 0) mask = 0x1
        val h = bottom - top
        val armW = h * if (compact) 0.12f else 0.20f    // 臂宽
        val headW = armW * if (compact) 2.4f else 2.6f  // 三角头底宽
        val headL = armW * if (compact) 2.0f else 2.0f  // 三角头长
        val armX = h * if (compact) 0.18f else 0.26f    // 左右转水平臂长
        val armS = h * if (compact) 0.16f else 0.20f    // 稍左/稍右斜臂
        val hasStraight = mask and 0x1 != 0
        val branchY = top + h * if (hasStraight) 0.46f else 0.36f

        stroke.color = color
        stroke.strokeWidth = armW
        stroke.strokeCap = Paint.Cap.BUTT
        fill.color = color

        // 1.35：fitIn 非空时（红绿灯圆心），按箭头内容的包围盒居中并缩放
        // 适配——纯左转/右转箭头重心偏一侧，直接画会偏离圆心（用户照片问题）
        if (fitIn != null) {
            val hasLeft = mask and 0x2 != 0
            val hasRight = mask and 0x4 != 0
            val hasSlightL = mask and 0x8 != 0
            val hasSlightR = mask and 0x20 != 0
            val hasUTurn = mask and 0x10 != 0
            val combinedUTurn = hasUTurn && (hasStraight || hasLeft || hasRight || hasSlightL || hasSlightR)

            val contentTop = when {
                hasStraight -> top
                hasLeft || hasRight -> branchY - headW / 2f
                hasSlightL || hasSlightR -> top + headL - headW * 0.36f
                hasUTurn -> top + headL * 0.25f
                else -> top
            }
            val contentBottom = if (combinedUTurn) top + h * 0.68f + armW * 1.6f + headL else bottom
            val leftExt = maxOf(
                if (hasLeft) armX + headW / 2f else 0f,
                if (hasSlightL) armS + headW * 0.4f else 0f,
                if (hasUTurn) (if (combinedUTurn) armX else armW * 1.8f) + headW / 2f else 0f,
                armW / 2f
            )
            val rightExt = maxOf(
                if (hasRight) armX + headW / 2f else 0f,
                if (hasSlightR) armS + headW * 0.4f else 0f,
                armW / 2f
            )
            val bcx = cx + (rightExt - leftExt) / 2f
            val bcy = (contentTop + contentBottom) / 2f
            val sx = fitIn.width() / (leftExt + rightExt).coerceAtLeast(0.5f)
            val sy = fitIn.height() / (contentBottom - contentTop).coerceAtLeast(0.5f)
            val sc = minOf(sx, sy).coerceIn(0.2f, 1.6f)
            canvas.save()
            canvas.translate(fitIn.centerX(), fitIn.centerY())
            canvas.scale(sc, sc)
            canvas.translate(-bcx, -bcy)
        }

        // 直行：主干贯穿 + 顶端大三角头
        if (hasStraight) {
            canvas.drawLine(cx, bottom, cx, top + headL * 0.65f, stroke)
            solidHead(canvas, cx, top + headL, 0f, -1f, headW, headL)
        }

        // 左转：从主干圆角弯出水平臂 + 三角头
        if (mask and 0x2 != 0) {
            val p = Path()
            if (hasStraight) {
                p.moveTo(cx, branchY + armW * 2.6f)
                p.quadTo(cx, branchY, cx - armW * 1.8f, branchY)
            } else {
                p.moveTo(cx, bottom)
                p.lineTo(cx, branchY + armW * 1.8f)
                p.quadTo(cx, branchY, cx - armW * 1.8f, branchY)
            }
            p.lineTo(cx - armX, branchY)
            canvas.drawPath(p, stroke)
            solidHead(canvas, cx - armX, branchY, -1f, 0f, headW, headL)
        }
        // 右转：镜像
        if (mask and 0x4 != 0) {
            val p = Path()
            if (hasStraight) {
                p.moveTo(cx, branchY + armW * 2.6f)
                p.quadTo(cx, branchY, cx + armW * 1.8f, branchY)
            } else {
                p.moveTo(cx, bottom)
                p.lineTo(cx, branchY + armW * 1.8f)
                p.quadTo(cx, branchY, cx + armW * 1.8f, branchY)
            }
            p.lineTo(cx + armX, branchY)
            canvas.drawPath(p, stroke)
            solidHead(canvas, cx + armX, branchY, 1f, 0f, headW, headL)
        }
        // 稍左 / 稍右：斜臂
        if (mask and 0x8 != 0) {
            val ty = top + headL
            canvas.drawLine(cx, branchY + armW * 0.8f, cx - armS, ty + headL * 0.5f, stroke)
            solidHead(canvas, cx - armS, ty, -0.55f, -1f, headW * 0.85f, headL * 0.9f)
        }
        if (mask and 0x20 != 0) {
            val ty = top + headL
            canvas.drawLine(cx, branchY + armW * 0.8f, cx + armS, ty + headL * 0.5f, stroke)
            solidHead(canvas, cx + armS, ty, 0.55f, -1f, headW * 0.85f, headL * 0.9f)
        }
        // 掉头
        if (mask and 0x10 != 0) {
            val combined = hasStraight || mask and 0x2 != 0 || mask and 0x4 != 0 ||
                mask and 0x8 != 0 || mask and 0x20 != 0
            val r = armW * 1.8f
            val p = Path()
            if (combined) {
                // 与其它方向共存：从主干更低处向左弯出、立即向下（参考图左车道的回勾），
                // 与左右转分支上下错开约 0.2h，不挤在一起
                val y0 = top + h * 0.68f
                p.moveTo(cx, y0 + armW * 2.2f)
                p.quadTo(cx, y0, cx - armW * 1.8f, y0)
                p.lineTo(cx - armX, y0)
                p.lineTo(cx - armX, y0 + armW * 1.2f)
                canvas.drawPath(p, stroke)
                solidHead(canvas, cx - armX, y0 + armW * 1.6f, 0f, 1f, headW, headL)
            } else {
                // 纯掉头：主干到顶回环向左、箭头朝下
                val y1 = top + headL + armW * 0.9f
                p.moveTo(cx, bottom)
                p.lineTo(cx, y1)
                p.quadTo(cx, top + headL * 0.3f, cx - r, top + headL * 0.55f)
                p.lineTo(cx - r, y1 + armW * 0.4f)
                canvas.drawPath(p, stroke)
                solidHead(canvas, cx - r, y1 + armW * 0.6f, 0f, 1f, headW, headL)
            }
        }
        if (fitIn != null) canvas.restore()
    }

    /** 实心三角头：(x,y) 为三角形底边中点，(dx,dy) 指向箭尖。 */
    private fun solidHead(
        canvas: Canvas,
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        headW: Float,
        headL: Float
    ) {
        val norm = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        val ux = dx / norm
        val uy = dy / norm
        val perpX = -uy
        val perpY = ux
        val p = Path()
        p.moveTo(x + ux * headL, y + uy * headL)
        p.lineTo(x + perpX * headW / 2f, y + perpY * headW / 2f)
        p.lineTo(x - perpX * headW / 2f, y - perpY * headW / 2f)
        p.close()
        canvas.drawPath(p, fill)
    }

    // 红绿灯（1.26 参考图一样式）：深黑横向胶囊底，左侧一个大圆——状态色实心圆
    // （红/绿/黄）+ 圆心白色实心箭头（跟随当前路口方向，缺省直行），
    // 右侧红色七段数码管风格的倒计时数字。
    private fun drawLight(canvas: Canvas, s: HudCardState, alpha: Int, box: ElementBox = layout.light) {
        val cx = box.cx * DESIGN_W
        val cy = box.cy * DESIGN_H
        // 1.34：只以「状态」判断是否画红绿灯。倒数走到 0 时仍然显示（这一秒本就是
        // 0 秒），之前把 seconds<=0 也当成「无数据」整块不画，下一秒又出现 → 闪烁。
        if (s.lightStatus <= 0) {
            if (editMode) placeholder(canvas, lightBounds(), "红绿灯")
            return
        }
        drawLightCapsule(canvas, cx, cy, box.scale, s.lightStatus, s.lightSeconds, s.lightTurnMask, alpha)
    }

    /**
     * 1.60：单个红绿灯胶囊（圆 + 圈内方向箭头 + 倒计时数字），从 drawLight
     * 抽出来供巡航两行显示复用——直行一行、左转一行，各画一个胶囊。
     */
    private fun drawLightCapsule(
        canvas: Canvas, cx: Float, cy: Float, scale: Float,
        status: Int, seconds: Int, dirMask: Int, alpha: Int
    ) {
        val r = 24f * scale
        // 1.35：应用全局秒数微调偏移，最少显示 1 秒。
        // 1.65：seconds<0 = 该灯色没有真实倒数（绿灯期间这版车机不发秒数，
        // greenLastSecond 恒 3 是标志值）——不画数字，只画灯色圆，避免假"0"。
        val shownSeconds = if (seconds >= 0) (seconds + lightSecondsOffset).coerceAtLeast(1) else 0
        val digits = if (seconds >= 0) shownSeconds.toString() else ""
        // 1.33：数字位宽固定按 2 位算。之前按实际位数算宽度，倒数 10→9 时
        // 胶囊整体收窄、数字重新居中，看起来就像「在闪」。
        val digitSlots = when {
            digits.isEmpty() -> 0
            digits.length < 2 -> 2
            else -> digits.length
        }
        // 1.40：倒计时改用阿拉伯数字（普通粗体字体），字高按圆半径定。
        numberPaint.textSize = r * 1.5f
        numberPaint.textAlign = Paint.Align.LEFT
        // 定宽：按 "8" 的宽度 × 位数，读数变化时胶囊宽度不变
        val digitW = numberPaint.measureText("8")
        val numW = digitSlots * digitW
        val pillH = r * 2 + 8f * scale
        val pillW = r * 2 + numW + 14f * scale
        val left = cx - pillW / 2f
        val top = cy - pillH / 2f

        // 胶囊底（深黑）
        fill.color = withAlpha(COLOR_LIGHT_PANEL, alpha)
        canvas.drawRoundRect(
            RectF(left, top, left + pillW, top + pillH),
            pillH / 2f, pillH / 2f, fill
        )

        // 状态色：1=红 2=绿 3=黄（1.23 实测校准）；整圆实心状态色
        val statusColor = when (status) {
            1 -> COLOR_RED
            2 -> COLOR_GREEN
            3 -> COLOR_YELLOW
            else -> COLOR_GRAY
        }
        val ringCx = left + 4f * scale + r
        fill.color = withAlpha(statusColor, alpha)
        canvas.drawCircle(ringCx, cy, r, fill)
        // 状态色圆的细亮边
        stroke.color = withAlpha(Color.WHITE, alpha * 40 / 100)
        stroke.strokeWidth = 1.6f * scale
        canvas.drawCircle(ringCx, cy, r - stroke.strokeWidth / 2f, stroke)

        // 圆心白色实心箭头 = 路口行驶方向（dir 字段，Companion 实测表解析；
        // 1.65：无方向数据时不再硬画直行兜底——巡航版 60075 的 dir 恒 -1，
        // 硬画直行会让左拐 / 掉头路口全部错显直行，改为只画灯色圆。
        // 数据层已用推荐车道方向兜底，走到这里的 0 是真没有方向信息。）
        if (dirMask != 0) {
            drawSolidArrow(
                canvas,
                dirMask,
                ringCx,
                cy - r * 0.8f,
                cy + r * 0.8f,
                withAlpha(Color.WHITE, alpha),
                fitIn = RectF(
                    ringCx - r * 0.70f, cy - r * 0.70f,
                    ringCx + r * 0.70f, cy + r * 0.70f
                )
            )
        }

        // 右侧倒计时数字（1.40：阿拉伯数字，取代七段数码管）。
        // 颜色跟随灯色——红灯红数字、绿灯绿数字、黄灯黄数字；
        // 右对齐 + 定宽位槽，个位数也不跳动。
        val digitColor = when (status) {
            2 -> COLOR_GREEN
            3 -> COLOR_YELLOW
            else -> COLOR_RED
        }
        numberPaint.color = withAlpha(digitColor, alpha)
        val fm = numberPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        if (digits.isNotEmpty()) {
            val numLeft = left + r * 2 + 10f * scale + (digitSlots - digits.length) * digitW
            canvas.drawText(digits, numLeft, baseline, numberPaint)
        }
        numberPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * 1.60：巡航红绿灯两行显示——本路口直行一行、左转一行，各带自己的
     * 灯色 / 圈内箭头 / 倒计时。数据来自 60075 / AIDL 的 dir 字段按方向
     * 分行记录；只有一行数据时退化为单胶囊居中。
     */
    private fun drawCruiseLights(canvas: Canvas, s: HudCardState, alpha: Int, box: ElementBox) {
        val now = System.currentTimeMillis()
        // 1.62：60 秒没刷新的方向行剔除（与数据层快照一致，绘制侧兜底）；
        // 数据停推时本地倒数——按 atMs 每秒递减到 0，避免高德巡航只推
        // 一两次后 HUD 数字冻结不动。持续推送时 atMs 每秒刷新，本地递减
        // 不生效（elapsed<1s），两种节奏都不冲突。
        val rows = s.cruiseLights
            .filter { now - it.atMs < 60_000L }
            .take(2)
            .map { r ->
                val el = ((now - r.atMs) / 1000L).toInt()
                if (r.seconds >= 0 && el > 0) r.copy(seconds = (r.seconds - el).coerceAtLeast(0)) else r
            }
        val cx = box.cx * DESIGN_W
        val cy = box.cy * DESIGN_H
        if (rows.isEmpty()) {
            if (s.lightStatus > 0) {
                // 没有方向分行数据时退回单胶囊（沿用主状态，同样本地倒数）
                val el = if (s.lightAtMs > 0) ((now - s.lightAtMs) / 1000L).toInt() else 0
                val sec = if (s.lightSeconds >= 0 && el > 0) {
                    (s.lightSeconds - el).coerceAtLeast(0)
                } else s.lightSeconds
                drawLightCapsule(canvas, cx, cy, box.scale, s.lightStatus, sec, s.lightTurnMask, alpha)
            } else if (editMode) {
                placeholder(canvas, lightBounds(), "红绿灯")
            }
            return
        }
        val r = 24f * box.scale
        val rowGap = (r * 2 + 8f * box.scale) * 1.18f
        val cys = if (rows.size == 1) listOf(cy) else listOf(cy - rowGap / 2f, cy + rowGap / 2f)
        rows.forEachIndexed { i, row ->
            drawLightCapsule(canvas, cx, cys[i], box.scale, row.status, row.seconds, row.dirMask, alpha)
        }
        // 1.62：本地倒数没有数据层心跳驱动，绘制层自己排「每秒一帧」；
        // 只有真有红绿灯内容时才排（含单胶囊退路），空数据不空转
        if (rows.isNotEmpty() || s.lightStatus > 0) postInvalidateDelayed(1000L)
    }

    // 道路名 / 下一步动作
    private fun drawRoad(canvas: Canvas, s: HudCardState, alpha: Int, box: ElementBox = layout.road) {
        val cx = box.cx * DESIGN_W
        val cy = box.cy * DESIGN_H
        val raw = s.roadName
        if (raw == null || raw.isBlank()) {
            if (editMode) placeholder(canvas, roadBounds(), "道路名")
            return
        }
        // 1.56：道路名前加「进入 」前缀（与路名之间空一格）；
        // 1.57：前缀只在导航时显示，巡航不显示；截断宽度按整串计算
        val prefix = if (s.navActive) "进入 " else ""
        var shown: String = prefix + raw
        textPaint.textSize = 19f * box.scale
        textPaint.color = withAlpha(COLOR_TEXT, alpha)
        val maxW = 300f * box.scale
        while (shown.length > prefix.length + 2 && textPaint.measureText(shown) > maxW) {
            shown = shown.dropLast(2)
        }
        if (shown != prefix + raw) shown = shown.dropLast(1) + "…"
        canvas.drawText(shown, cx, cy + 6f * box.scale, textPaint)
    }

    // 剩余距离
    private fun drawDistance(canvas: Canvas, s: HudCardState, alpha: Int) {
        val box = layout.distance
        val cx = box.cx * DESIGN_W
        val cy = box.cy * DESIGN_H
        val text = s.distanceText
        if (text.isNullOrBlank()) {
            if (editMode) placeholder(canvas, distanceBounds(), "剩余距离")
            return
        }
        textPaint.textSize = 34f * box.scale
        textPaint.color = withAlpha(COLOR_TEXT, alpha)
        canvas.drawText(text, cx, cy + 12f * box.scale, textPaint)
    }

    // 1.26 到达信息：全程剩余里程 / 需要时长 / 预计到达时刻
    // （ETA 由系统当前时间 + 高德剩余秒数实时计算，分钟粒度）
    private fun drawEta(canvas: Canvas, s: HudCardState, alpha: Int) {
        val box = layout.eta
        val cx = box.cx * DESIGN_W
        val cy = box.cy * DESIGN_H
        if (s.routeRemainDisM <= 0 && s.routeRemainTimeS <= 0) {
            if (editMode) placeholder(canvas, etaBounds(), "到达信息")
            return
        }
        val disKm = s.routeRemainDisM / 1000f
        val disText = if (disKm >= 10f) "%.0fkm".format(disKm) else "%.1fkm".format(disKm)
        val timeMin = s.routeRemainTimeS / 60
        val timeText = if (timeMin >= 60) "%d小时%d分".format(timeMin / 60, timeMin % 60) else "${timeMin}分钟"
        val eta = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(System.currentTimeMillis() + s.routeRemainTimeS * 1000L))

        val line1 = if (s.routeRemainDisM > 0 && s.routeRemainTimeS > 0) "$disText · $timeText"
        else if (s.routeRemainDisM > 0) disText else timeText
        textPaint.textSize = 16f * box.scale
        textPaint.color = withAlpha(COLOR_TEXT, alpha)
        canvas.drawText(line1, cx, cy - 2f * box.scale, textPaint)
        textPaint.textSize = 14f * box.scale
        textPaint.color = withAlpha(COLOR_TEXT_DIM, alpha)
        canvas.drawText("预计 $eta 到达", cx, cy + 16f * box.scale, textPaint)
    }

    /**
     * 1.53：限速牌——白底圆 + 红色圆环 + 黑色限速数值（国标限速标志样式）。
     * 数据来自广播泛化捕获 / AIDL 深扫（speedLimit 等键，km/h）；
     * 无数据显示空（编辑态画占位框）。巡航与导航都显示。
     */
    private fun drawSpeedLimit(canvas: Canvas, s: HudCardState, alpha: Int, box: ElementBox = layout.speed) {
        val cx = box.cx * DESIGN_W
        val cy = box.cy * DESIGN_H
        val r = 26f * box.scale
        if (s.speedLimit <= 0) {
            if (editMode) placeholder(canvas, speedBounds(box), "限速")
            return
        }
        // 白底圆
        fill.color = withAlpha(Color.WHITE, alpha)
        canvas.drawCircle(cx, cy, r, fill)
        // 红色圆环
        stroke.color = withAlpha(COLOR_RED, alpha)
        stroke.strokeWidth = r * 0.30f
        canvas.drawCircle(cx, cy, r - stroke.strokeWidth / 2f, stroke)
        // 黑色数值（粗体，居中；1.53 三位数自适应缩小，避免撑出红环）
        val txt = s.speedLimit.toString()
        textPaint.textSize = if (txt.length >= 3) r * 0.82f else r * 1.05f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = withAlpha(Color.parseColor("#14181C"), alpha)
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(txt, cx, baseline, textPaint)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawStaleHint(canvas: Canvas) {
        textPaint.textSize = 11f
        textPaint.color = COLOR_TEXT_DIM
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("无新数据 · 保留上一帧", DESIGN_W - 8f, DESIGN_H - 5f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    // ---------------- 编辑态辅助 ----------------

    /** 编辑器当前编辑的布局：巡航演示时是巡航布局，否则是导航布局 */
    /** 1.59：改为 internal——子类 CardEditorView 巡航编辑也要走这里 */
    internal fun editLayout(): CardLayout = if (demoCruise) cruiseLayout else layout

    fun lanesBounds(): RectF {
        val b = editLayout().lanes
        val w = LANE_CELL_W * b.scale * 4f + 20f * b.scale
        val h = LANE_HEIGHT * b.scale
        return RectF(b.cx * DESIGN_W - w / 2, b.cy * DESIGN_H - h / 2, b.cx * DESIGN_W + w / 2, b.cy * DESIGN_H + h / 2)
    }

    fun lightBounds(): RectF {
        val b = editLayout().light
        // 覆盖整个胶囊（圆 + 最多三位数字），按上限估宽
        val r = 24f * b.scale
        val digitW = r * 1.5f * 0.62f   // 粗体阿拉伯数字平均字宽（近似 "8" 的宽度）
        val w = r * 2 + 3 * digitW + 14f * b.scale
        // 1.60：巡航两行显示时热区覆盖两行高度
        val h = (r * 2 + 8f * b.scale) * if (demoCruise) 2.4f else 1f
        return RectF(b.cx * DESIGN_W - w / 2, b.cy * DESIGN_H - h / 2, b.cx * DESIGN_W + w / 2, b.cy * DESIGN_H + h / 2)
    }

    fun roadBounds(): RectF {
        val b = editLayout().road
        val w = 220f * b.scale   // 1.57：含「进入 」前缀后文字更宽，热区同步加宽
        val h = 30f * b.scale
        return RectF(b.cx * DESIGN_W - w / 2, b.cy * DESIGN_H - h / 2, b.cx * DESIGN_W + w / 2, b.cy * DESIGN_H + h / 2)
    }

    fun distanceBounds(): RectF {
        val b = editLayout().distance
        val w = 110f * b.scale
        val h = 42f * b.scale
        return RectF(b.cx * DESIGN_W - w / 2, b.cy * DESIGN_H - h / 2, b.cx * DESIGN_W + w / 2, b.cy * DESIGN_H + h / 2)
    }

    fun turnBounds(): RectF {
        val b = editLayout().turn
        val w = 78f * b.scale
        val h = 96f * b.scale
        return RectF(b.cx * DESIGN_W - w / 2, b.cy * DESIGN_H - h / 2, b.cx * DESIGN_W + w / 2, b.cy * DESIGN_H + h / 2)
    }

    fun etaBounds(): RectF {
        val b = editLayout().eta
        val w = 150f * b.scale
        val h = 44f * b.scale
        return RectF(b.cx * DESIGN_W - w / 2, b.cy * DESIGN_H - h / 2, b.cx * DESIGN_W + w / 2, b.cy * DESIGN_H + h / 2)
    }

    /** 1.53：限速牌编辑框（白底圆外沿） */
    fun speedBounds(box: ElementBox = editLayout().speed): RectF {
        val r = 26f * box.scale + 4f
        return RectF(box.cx * DESIGN_W - r, box.cy * DESIGN_H - r, box.cx * DESIGN_W + r, box.cy * DESIGN_H + r)
    }

    fun boundsOf(e: CardElement): RectF = when (e) {
        CardElement.LANES -> lanesBounds()
        CardElement.LIGHT -> lightBounds()
        CardElement.ROAD -> roadBounds()
        CardElement.DISTANCE -> distanceBounds()
        CardElement.TURN -> turnBounds()
        CardElement.ETA -> etaBounds()
        CardElement.SPEEDLIMIT -> speedBounds()
    }

    /** 屏幕坐标 → 设计坐标 */
    fun toDesign(x: Float, y: Float): Pair<Float, Float> {
        val scale = minOf(width / DESIGN_W, height / DESIGN_H)
        if (scale <= 0f) return 0f to 0f
        val dx = (width - DESIGN_W * scale) / 2f
        val dy = (height - DESIGN_H * scale) / 2f
        return (x - dx) / scale to (y - dy) / scale
    }

    /** 命中测试：返回包含该设计坐标的元素（后画的优先）。
     *  1.52：巡航布局编辑时只命中巡航实际绘制的元素。 */
    fun hitTest(px: Float, py: Float): CardElement? {
        val order = if (demoCruise) {
            listOf(CardElement.LIGHT, CardElement.ROAD, CardElement.SPEEDLIMIT, CardElement.LANES)
        } else {
            listOf(
                CardElement.LIGHT, CardElement.TURN, CardElement.ROAD,
                CardElement.DISTANCE, CardElement.ETA, CardElement.SPEEDLIMIT, CardElement.LANES
            )
        }
        for (e in order) {
            if (boundsOf(e).contains(px, py)) return e
        }
        return null
    }

    private fun drawSelection(canvas: Canvas, s: HudCardState) {
        val sel = selected ?: return
        val r = boundsOf(sel)
        editPaint.color = COLOR_EDIT
        editPaint.strokeWidth = 1.6f
        canvas.drawRoundRect(RectF(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4), 8f, 8f, editPaint)
        textPaint.textSize = 12f
        textPaint.color = COLOR_EDIT
        val name = when (sel) {
            CardElement.LANES -> "车道"
            CardElement.LIGHT -> "红绿灯"
            CardElement.ROAD -> "道路名"
            CardElement.DISTANCE -> "距离"
            CardElement.TURN -> "转向箭头"
            CardElement.ETA -> "到达信息"
            CardElement.SPEEDLIMIT -> "限速"
        }
        canvas.drawText(name, r.centerX(), r.top - 10f, textPaint)
    }

    private fun placeholder(canvas: Canvas, r: RectF, label: String) {
        editPaint.color = withAlpha(COLOR_TEXT_DIM, 160)
        editPaint.strokeWidth = 1.4f
        canvas.drawRoundRect(r, 8f, 8f, editPaint)
        textPaint.textSize = 12f
        textPaint.color = COLOR_TEXT_DIM
        canvas.drawText(label, r.centerX(), r.centerY() + 4f, textPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = (Color.alpha(color) * alpha / 255f).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
