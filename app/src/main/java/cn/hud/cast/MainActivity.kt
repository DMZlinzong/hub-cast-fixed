package cn.hud.cast

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 控制页：只保留 HUD 导航卡片（唯一在这台车机上验证可用的路线）。
 * 早期版本的彩条测试、预览窗、虚拟屏/镜像相关调试功能已移除。
 */
class MainActivity : Activity() {

    private lateinit var config: CastConfig

    private lateinit var statusText: TextView
    private lateinit var hudText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CastConfig(this)
        setContentView(buildUi())
        StatusBus.register(statusListener)
        ensureNotificationPermission()
        // 1.29：打开 App 直接开始投屏（默认开启，可在界面里关掉）
        autoStartOnLaunch()
    }

    /**
     * 进主界面就走卡片模式：免授权、免点按钮。
     * 若服务已经在跑（开机自启动 / 之前没停），不重复拉起，避免会话被重建。
     */
    private fun autoStartOnLaunch() {
        if (!config.autoStartOnLaunch) return
        if (HudCastService.running) {
            StatusBus.publish(Status.casting("HUD 卡片已在运行，正在维持…"))
            return
        }
        StatusBus.publish(Status.casting("已自动开始投屏：正在连接车机版高德数据服务…"))
        HudCastService.startCard(this)
    }

    override fun onResume() {
        super.onResume()
        refreshHudProbe()
    }

    override fun onDestroy() {
        StatusBus.unregister(statusListener)
        super.onDestroy()
    }

    private val statusListener: (Status) -> Unit = { status -> runOnUiThread { renderStatus(status) } }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        root.addView(title("HUD 导航卡片"))
        root.addView(
            hint(
                "直接读取车机版高德的官方数据服务，把车道指引、推荐车道、红绿灯倒数、" +
                    "转向箭头、道路名、剩余距离常驻画在 HUD 上。免授权、免抓屏。"
            )
        )

        // 打开 App 自动投屏（1.29）：进主界面就开始，不用点「开始投屏」
        val launchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val launchLabel = TextView(this).apply {
            text = "打开 App 自动投屏（启动即开始，无需点「开始投屏」）"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val launchSwitch = Switch(this).apply {
            isChecked = config.autoStartOnLaunch
            setOnCheckedChangeListener { _, checked ->
                config.autoStartOnLaunch = checked
                if (checked) {
                    autoStartOnLaunch()
                    Toast.makeText(this@MainActivity, "已开启：打开 App 即自动投屏", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "已关闭：需要手动点「开始投屏」", Toast.LENGTH_SHORT).show()
                }
            }
        }
        launchRow.addView(launchLabel)
        launchRow.addView(launchSwitch)
        root.addView(launchRow)

        // 开机自启动：开机后常驻监听，不用每次导航前手动点开始
        val bootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bootLabel = TextView(this).apply {
            text = "开机自启动（常驻监听，不用每次点「开始投屏」）"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bootSwitch = Switch(this).apply {
            isChecked = config.autoStartOnBoot
            setOnCheckedChangeListener { _, checked ->
                config.autoStartOnBoot = checked
                if (checked) {
                    ensureNotificationPermission()
                    // 立刻按常驻方式拉起一次，顺便当场验证服务能起来（不用等下次开机）
                    StatusBus.publish(Status.casting("开机自启动已开启，正在常驻…"))
                    HudCastService.startCard(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        "已开启：下次开机自动常驻 HUD 卡片，导航一开就有内容",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "已关闭：开机后需要手动点「开始投屏」",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        bootRow.addView(bootLabel)
        bootRow.addView(bootSwitch)
        root.addView(bootRow)
        root.addView(
            hint(
                "开启后：开机自动起服务 → 自动绑定车机版高德数据服务 → HUD 卡片常驻，" +
                    "高德重启或被回收会自动重连，全程不用手工操作。\n" +
                    "个别车机限制第三方开机自启动，若开机没生效，用 adb 手动拉起一次即可验证服务本身没问题：\n" +
                    "adb shell am start-foreground-service -n cn.hud.cast/.HudCastService -a cn.hud.cast.START --ei extra_mode 3"
            )
        )

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(statusText)

        hudText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#93A0AC"))
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(hudText)
        buildDisplayPicker(root)
        buildCardEditor(root)
        buildLightOffsetControls(root)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        startButton = button("开始投屏") {
            StatusBus.publish(Status.casting("正在启动 HUD 导航卡片…"))
            HudCastService.startCard(this)
        }
        stopButton = button("停止") {
            HudCastService.stop(this)
            StatusBus.publish(Status.idle("已请求停止"))
        }
        actionRow.addView(startButton)
        actionRow.addView(space(dp(12)))
        actionRow.addView(stopButton)
        root.addView(actionRow)

        // 1.41：整页纯黑背景（车机夜间使用不刺眼）
        root.setBackgroundColor(Color.BLACK)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        scroll.addView(root)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window?.statusBarColor = Color.BLACK
            window?.navigationBarColor = Color.BLACK
        }
        return scroll
    }

    private fun refreshHudProbe() {
        when (val probe = HudDisplay.inspect(this, config.targetDisplayId)) {
            is HudDisplay.Probe.Ready -> {
                val d = probe.display
                val tag = if (probe.strict) "目标 HUD" else if (config.targetDisplayId > 0) "手动指定" else "自动选中副屏"
                hudText.text = "HUD 检测：$tag ${HudDisplay.describe(d)}，Presentation 可用"
            }
            is HudDisplay.Probe.Blocked -> hudText.text = "HUD 检测：${probe.reason}"
        }
        // 1.31：最近一次转向 / 车道原始值——HUD 与高德不一致时报这个数即可校准
        val dbg = HudCastService.instance?.protocolDebug ?: ""
        if (dbg.isNotEmpty()) {
            hudText.text = hudText.text.toString() + "\n最近原始值：$dbg"
        }
    }

    /** 屏幕清单 + 手动指定目标屏：自动挑错屏时，卡片会画到看不见的逻辑屏上。 */
    private fun buildDisplayPicker(parent: LinearLayout) {
        val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val defaultId = wm.defaultDisplay?.displayId ?: 0
        val displays = try {
            dm.displays?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (displays.isEmpty()) return

        parent.addView(hint("车机会暴露多块逻辑屏。若 HUD 上一直没有卡片，多半是自动选错了屏——" +
            "从下面手动指定一块试试，选完立刻生效。"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }

        fun displayName(d: android.view.Display): String {
            val size = android.graphics.Point()
            try { d.getRealSize(size) } catch (_: Throwable) {}
            val marks = buildList {
                if (d.displayId == defaultId) add("主屏")
                if (d.flags and android.view.Display.FLAG_PRESENTATION != 0) add("Presentation")
                if (d.flags and android.view.Display.FLAG_PRIVATE != 0) add("私有")
            }.joinToString(" / ")
            return "Display ${d.displayId} · ${size.x}×${size.y}" +
                (if (marks.isNotEmpty()) "（$marks）" else "") +
                " · 状态${if (d.state == android.view.Display.STATE_ON) "ON" else "OFF"}"
        }

        val auto = RadioButton(this).apply {
            id = View.generateViewId()
            text = "自动选择（推荐）"
            isChecked = config.targetDisplayId == 0
        }
        group.addView(auto)

        val ids = mutableMapOf(auto.id to 0)
        displays.forEach { d ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = displayName(d)
                isChecked = config.targetDisplayId == d.displayId
                if (d.displayId == defaultId) isEnabled = false
            }
            ids[rb.id] = d.displayId
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val id = ids[checkedId] ?: return@setOnCheckedChangeListener
            config.targetDisplayId = id
            refreshHudProbe()
            HudCastService.reprobe(this)
        }
        parent.addView(group)
    }

    private fun renderStatus(status: Status) {
        statusText.text = status.text
        statusText.setTextColor(
            when (status.level) {
                Status.Level.CASTING -> Color.parseColor("#2FD07E")
                Status.Level.ERROR -> Color.parseColor("#FF6B62")
                Status.Level.IDLE -> Color.parseColor("#8A949E")
            }
        )
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
    }

    // ---------------------------------------------------------------- 小工具

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    /**
     * HUD 卡片布局编辑器：预览即 HUD 实际样子，直接拖动摆放、捏合缩放。
     * 各元素（转向 / 车道 / 红绿灯 / 道路名 / 距离）位置与大小全部可调，改动立即持久化。
     */
    private fun buildCardEditor(parent: LinearLayout) {
        parent.addView(section("HUD 卡片布局"))
        val statusHint = hint("布局预览。想拖动 / 缩放元素时，先打开上面的「编辑模式」开关——" +
            "编辑中页面不会跟着滚动；关掉后页面恢复正常滑动。")

        val editor = CardEditorView(this)
        // 1.58：预览缩小到屏宽 55% 并居中——之前接近满屏宽，编辑元素后
        // 手指活动范围太大，松手位置容易落在别的控件上，也挤压说明文字
        val w = (resources.displayMetrics.widthPixels * 0.55f).toInt()
        val h = (w / 2f).toInt()
        editor.layoutParams = LinearLayout.LayoutParams(w, h).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // 1.52：布局方案切换——导航布局（6 元素）与巡航布局（车道 / 红绿灯 /
        // 道路名 3 元素）分开编辑、分开保存；巡航预览里演示的就是巡航画面
        var editCruise = false
        var turnBtn: android.widget.Button? = null
        var distBtn: android.widget.Button? = null
        var etaBtn: android.widget.Button? = null
        val applyMode: (Boolean) -> Unit = { cruise ->
            editCruise = cruise
            editor.demoCruise = cruise
            editor.applyLayout(if (cruise) config.cardLayoutCruise else config.cardLayout)
            editor.selectElement(null)
            // 1.61：编辑中切换布局方案时，HUD 演示画面同步跟随
            if (config.hudEditMode) config.hudEditCruise = cruise
            // 巡航只画三个元素，多余的选择按钮置灰
            turnBtn?.isEnabled = !cruise
            distBtn?.isEnabled = !cruise
            etaBtn?.isEnabled = !cruise
        }
        editor.onLayoutChanged = { layout ->
            if (editCruise) config.cardLayoutCruise = layout else config.cardLayout = layout
        }
        editor.applyLayout(config.cardLayout)

        // 1.52：布局方案切换行
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val navModeBtn = button("编辑导航布局") { applyMode(false) }
        val cruiseModeBtn = button("编辑巡航布局") { applyMode(true) }
        modeRow.addView(navModeBtn)
        modeRow.addView(space(dp(6)))
        modeRow.addView(cruiseModeBtn)
        parent.addView(modeRow)
        parent.addView(hint("巡航布局只有 车道指引 / 红绿灯 / 道路名 三个元素" +
            "（巡航时 HUD 也只显示这三样），切换到「编辑巡航布局」后预览即巡航画面。"))

        // 编辑模式开关：默认关闭，页面滚动不受编辑器影响；
        // 打开后编辑器独占手势（拖动摆放 / 双指缩放），不再与下滑冲突
        val editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val editLabel = TextView(this).apply {
            text = "编辑模式（拖动摆放 / 双指缩放）"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editSwitch = Switch(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                editor.editingEnabled = checked
                // 1.61：编辑中让 HUD 投影显示演示画面（所有元素按当前布局
                // 映射），关掉开关恢复真实高德数据；服务层 0.8 秒同步一次
                config.hudEditMode = checked
                config.hudEditCruise = editCruise
                statusHint.text = if (checked) {
                    "编辑中：单指拖动摆放元素，双指捏合缩放；改动自动保存并实时推到 HUD。" +
                        "HUD 投影此时显示演示画面（全部元素），方便对着实屏摆放。" +
                        "调完记得关掉开关，页面就能正常滑动了。"
                } else {
                    "布局预览。想拖动 / 缩放元素时，先打开上面的「编辑模式」开关——" +
                        "编辑中页面不会跟着滚动；关掉后页面恢复正常滑动。"
                }
            }
        }
        editRow.addView(editLabel)
        editRow.addView(editSwitch)
        parent.addView(editRow)
        parent.addView(statusHint)
        parent.addView(editor)

        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(
            CardElement.TURN to "转向",
            CardElement.LANES to "车道",
            CardElement.LIGHT to "红绿灯",
            CardElement.ROAD to "道路名",
            CardElement.DISTANCE to "距离",
            CardElement.ETA to "到达",
            CardElement.SPEEDLIMIT to "限速"
        )
        for ((el, label) in items) {
            val b = button(label) { editor.selectElement(el) }
            when (el) {
                CardElement.TURN -> turnBtn = b
                CardElement.DISTANCE -> distBtn = b
                CardElement.ETA -> etaBtn = b
                else -> {}
            }
            pickRow.addView(b)
            pickRow.addView(space(dp(6)))
        }
        parent.addView(pickRow)

        val scaleLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9AA5B1"))
            text = "选中元素的缩放：1.00x"
        }
        parent.addView(scaleLabel)

        val scaleBar = SeekBar(this).apply {
            // 1.63：缩放范围扩大到 0.1x ~ 3.0x（progress 0..290 → (p+10)/100）
            max = 290
            progress = 100
        }
        scaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = (progress + 10) / 100f
                scaleLabel.text = "选中元素的缩放：%.2fx".format(value)
                editor.setSelectedScale(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        parent.addView(scaleBar)

        editor.onSelectChanged = { el ->
            val label = when (el) {
                CardElement.TURN -> "转向箭头"
                CardElement.LANES -> "车道指引"
                CardElement.LIGHT -> "红绿灯"
                CardElement.ROAD -> "道路名"
                CardElement.DISTANCE -> "剩余距离"
                CardElement.ETA -> "到达信息"
                CardElement.SPEEDLIMIT -> "限速牌"
                null -> "未选中"
            }
            scaleLabel.text = "选中元素的缩放：%.2fx（%s）".format(editor.currentScale(), label)
            val p = ((editor.currentScale() - 0.1f) * 100).toInt().coerceIn(0, 290)
            if (scaleBar.progress != p) scaleBar.progress = p
        }

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottomRow.addView(button("恢复默认布局") {
            if (editCruise) {
                val def = CardLayout.cruiseDefault()
                editor.applyLayout(def)
                config.cardLayoutCruise = def
            } else {
                val def = CardLayout()
                editor.applyLayout(def)
                config.cardLayout = def
            }
            Toast.makeText(this, "已恢复默认布局", Toast.LENGTH_SHORT).show()
        })
        bottomRow.addView(space(dp(10)))
        bottomRow.addView(button("清空选择") { editor.selectElement(null) })
        parent.addView(bottomRow)
    }

    /**
     * 1.35：红绿灯倒数微调——高德发的倒数与实际灯偶尔不同步时手动加减，
     * 全局生效、持久保存；HUD 上显示的秒数最少钳到 1。
     */
    private fun buildLightOffsetControls(parent: LinearLayout) {
        parent.addView(section("红绿灯倒数微调"))
        val label = TextView(this).apply { textSize = 14f }
        fun refreshLabel() {
            val v = config.lightSecondsOffset
            label.text = if (v == 0) "当前偏移：0 秒（跟随高德）"
            else "当前偏移：%+d 秒".format(v)
        }
        refreshLabel()

        fun adjust(delta: Int) {
            config.lightSecondsOffset = (config.lightSecondsOffset + delta).coerceIn(-120, 120)
            refreshLabel()
            HudCastService.instance?.setLightSecondsOffset(config.lightSecondsOffset)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("−5秒") { adjust(-5) })
        row.addView(space(dp(6)))
        row.addView(button("−1秒") { adjust(-1) })
        row.addView(space(dp(6)))
        row.addView(button("+1秒") { adjust(1) })
        row.addView(space(dp(6)))
        row.addView(button("+5秒") { adjust(5) })
        row.addView(space(dp(6)))
        row.addView(button("归零") {
            config.lightSecondsOffset = 0
            refreshLabel()
        })
        parent.addView(row)
        parent.addView(label)
        parent.addView(hint("微调会加减到高德发的倒数上，HUD 显示最少 1 秒。"))
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#9AA5B1"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun space(width: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATION = 4002
    }
}
