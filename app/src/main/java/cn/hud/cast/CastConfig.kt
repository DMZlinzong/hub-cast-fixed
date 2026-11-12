package cn.hud.cast

import android.content.Context
import android.content.SharedPreferences

/** 归一化的裁剪框，取值 0f..1f，相对源画面。 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isValid(): Boolean = width > 0.02f && height > 0.02f

    fun normalized(): CropRect {
        var l = left.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (r <= l) r = (l + 0.05f).coerceAtMost(1f)
        if (b <= t) b = (t + 0.05f).coerceAtMost(1f)
        return CropRect(l, t, r, b)
    }

    companion object {
        /** 去掉车机状态栏 / 顶栏，其余全要 */
        val FULL = CropRect(0f, 0f, 1f, 1f)

        /** 导航态默认：主屏左侧地图区 + 顶部留一点，右侧引导卡不要 */
        val NAV_DEFAULT = CropRect(0.00f, 0.06f, 0.72f, 1.00f)

        /** 巡航态默认：全宽地图 + 顶部留一点 */
        val CRUISE_DEFAULT = CropRect(0.00f, 0.06f, 1.00f, 1.00f)
    }
}

/** 缩放方式：HUD 是 2:1，源画面一般 16:9，必须显式决定怎么贴。 */
enum class ScaleMode {
    /** 等比装进去，四周留黑边 */
    FIT,

    /** 等比铺满，超出的部分裁掉 */
    FILL,

    /** 直接拉伸，画面变形但满屏 */
    STRETCH;

    companion object {
        fun fromName(name: String?): ScaleMode =
            entries.firstOrNull { it.name == name } ?: FIT
    }
}

enum class CropProfile { NAV, CRUISE }

/**
 * 投屏参数。存在自己的 SharedPreferences 里，不动任何别人的配置。
 */
class CastConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var navCrop: CropRect
        get() = readCrop(KEY_NAV)
        set(value) = writeCrop(KEY_NAV, value)

    var cruiseCrop: CropRect
        get() = readCrop(KEY_CRUISE)
        set(value) = writeCrop(KEY_CRUISE, value)

    var activeProfile: CropProfile
        get() = if (prefs.getString(KEY_PROFILE, null) == CropProfile.CRUISE.name) {
            CropProfile.CRUISE
        } else {
            CropProfile.NAV
        }
        set(value) = prefs.edit().putString(KEY_PROFILE, value.name).apply()

    var scaleMode: ScaleMode
        get() = ScaleMode.fromName(prefs.getString(KEY_SCALE, null))
        set(value) = prefs.edit().putString(KEY_SCALE, value.name).apply()

    /** 高德离开前台后自动停投 */
    var stopWhenGaodeBackgrounded: Boolean
        get() = prefs.getBoolean(KEY_AUTO_STOP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_STOP, value).apply()

    /** 巡航时自动切换成 cruise 那套裁剪 */
    var switchCropByProfile: Boolean
        get() = prefs.getBoolean(KEY_SWITCH_PROFILE, true)
        set(value) = prefs.edit().putBoolean(KEY_SWITCH_PROFILE, value).apply()

    /** 运行模式：true=后台虚拟屏（免授权，高德拉进隐藏屏） false=前台镜像（抓主屏，需录屏授权） */
    var useBackgroundMode: Boolean
        get() = prefs.getBoolean(KEY_BG_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_BG_MODE, value).apply()

    /** 运行模式：0=前台镜像 1=后台虚拟屏 3=HUD 导航卡片（读车机版高德数据）。默认卡片。 */
    var runMode: Int
        get() = prefs.getInt(KEY_RUN_MODE, 3)
        set(value) = prefs.edit().putInt(KEY_RUN_MODE, value).apply()

    /** HUD 卡片元素布局（车道 / 红绿灯 / 道路名 / 距离 的位置与大小） */
    var cardLayout: CardLayout
        get() = CardLayout.decode(prefs.getString(KEY_CARD_LAYOUT, null))
        set(value) = prefs.edit().putString(KEY_CARD_LAYOUT, value.encode()).apply()

    /** 1.52：巡航布局（只有 车道 / 红绿灯 / 道路名 三个元素生效），独立于导航布局 */
    var cardLayoutCruise: CardLayout
        get() = CardLayout.decode(prefs.getString(KEY_CARD_LAYOUT_CRUISE, null))
            .takeIf { prefs.contains(KEY_CARD_LAYOUT_CRUISE) } ?: CardLayout.cruiseDefault()
        set(value) = prefs.edit().putString(KEY_CARD_LAYOUT_CRUISE, value.encode()).apply()

    /** 后台虚拟屏分辨率：0=1280×720（默认，够用且省资源） 1=1920×1080（地图细节更多，渲染更重） */
    var bgResolution: Int
        get() = prefs.getInt(KEY_BG_RES, 0)
        set(value) = prefs.edit().putInt(KEY_BG_RES, value).apply()

    /** 手动指定的 HUD 目标屏 displayId；0=自动选择。自动选错屏时用它纠正。 */
    var targetDisplayId: Int
        get() = prefs.getInt(KEY_TARGET_DISPLAY, 0)
        set(value) = prefs.edit().putInt(KEY_TARGET_DISPLAY, value).apply()

    /** 渲染心跳（实验）：高德在虚拟屏停止出帧时，定期把它重新拉到前台触发恢复渲染。 */
    var gaodeHeartbeat: Boolean
        get() = prefs.getBoolean(KEY_HEARTBEAT, false)
        set(value) = prefs.edit().putBoolean(KEY_HEARTBEAT, value).apply()

    /**
     * 开机自启动（1.16）：开机后自动拉起常驻服务，一直监听导航数据，
     * 上车/开始导航后 HUD 直接出内容，不用每次手动点「开始投屏」。
     */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BOOT, value).apply()

    /**
     * 打开 App 就自动开始投屏（1.29）：进主界面直接起卡片模式，不用点「开始投屏」。
     * 默认开启——卡片模式免授权，随时可用。
     */
    var autoStartOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LAUNCH, value).apply()

    /** 数据服务断线自动重连（车机版高德重启、被系统回收后自动重新绑定）。 */
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    /**
     * 1.35：红绿灯倒数微调偏移（秒），可正可负。高德发的倒数与实际灯
     * 偶尔不同步时手动加减；显示值最少 1 秒。
     */
    var lightSecondsOffset: Int
        get() = prefs.getInt(KEY_LIGHT_OFFSET, 0)
        set(value) = prefs.edit().putInt(KEY_LIGHT_OFFSET, value).apply()

    /**
     * 1.61：布局编辑进行中——HUD 投影切换为演示画面（所有元素按当前
     * 编辑的布局映射显示），编辑开关关闭后恢复真实高德数据。
     */
    var hudEditMode: Boolean
        get() = prefs.getBoolean(KEY_HUD_EDIT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_HUD_EDIT_MODE, value).apply()

    /** 1.61：编辑的是哪套布局（true=巡航布局），决定 HUD 演示哪套元素 */
    var hudEditCruise: Boolean
        get() = prefs.getBoolean(KEY_HUD_EDIT_CRUISE, false)
        set(value) = prefs.edit().putBoolean(KEY_HUD_EDIT_CRUISE, value).apply()

    val bgWidth: Int get() = if (bgResolution == 1) 1920 else 1280
    val bgHeight: Int get() = if (bgResolution == 1) 1080 else 720
    val bgDpi: Int get() = if (bgResolution == 1) 320 else 240

    fun cropFor(profile: CropProfile): CropRect =
        if (profile == CropProfile.CRUISE) cruiseCrop else navCrop

    fun effectiveCrop(): CropRect {
        if (!switchCropByProfile) return cropFor(CropProfile.NAV)
        return cropFor(activeProfile)
    }

    private fun readCrop(key: String): CropRect {
        val fallback = if (key == KEY_CRUISE) CropRect.CRUISE_DEFAULT else CropRect.NAV_DEFAULT
        val raw = prefs.getString(key, null) ?: return fallback
        return try {
            val p = raw.split(",")
            if (p.size != 4) fallback
            else CropRect(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat()).normalized()
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun writeCrop(key: String, value: CropRect) {
        val c = value.normalized()
        prefs.edit().putString(key, "${c.left},${c.top},${c.right},${c.bottom}").apply()
    }

    companion object {
        private const val PREF_NAME = "hud_cast_v1"
        private const val KEY_NAV = "crop_nav"
        private const val KEY_CRUISE = "crop_cruise"
        private const val KEY_PROFILE = "profile"
        private const val KEY_SCALE = "scale_mode"
        private const val KEY_AUTO_STOP = "auto_stop"
        private const val KEY_SWITCH_PROFILE = "switch_profile"
        private const val KEY_BG_MODE = "bg_mode"
        private const val KEY_RUN_MODE = "run_mode"
        private const val KEY_CARD_LAYOUT = "card_layout"
        private const val KEY_CARD_LAYOUT_CRUISE = "card_layout_cruise"
        private const val KEY_BG_RES = "bg_resolution"
        private const val KEY_TARGET_DISPLAY = "target_display_id"
        private const val KEY_HEARTBEAT = "gaode_heartbeat"
        private const val KEY_AUTO_BOOT = "auto_boot"
        private const val KEY_AUTO_LAUNCH = "auto_launch"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_LIGHT_OFFSET = "light_seconds_offset"
        private const val KEY_HUD_EDIT_MODE = "hud_edit_mode"
        private const val KEY_HUD_EDIT_CRUISE = "hud_edit_cruise"
    }
}
