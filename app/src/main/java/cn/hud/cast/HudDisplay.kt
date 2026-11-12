package cn.hud.cast

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

/**
 * HUD 目标屏定位。
 *
 * 定位顺序：
 *  1. 严格模式：优先 displayId == 3 且满足 hud-probe 0.4.8 的整套校验
 *     （STATE_ON / 480×240 / FLAG_PRESENTATION / 非 PRIVATE / 非默认屏）。
 *  2. 兜底模式：Display 3 不满足时，从所有"能放 Presentation 的副屏"里挑一块。
 *     只要不是默认屏、不是私有屏、处于 ON 状态即可，尺寸不再写死。
 *
 * 之所以留兜底：不同车机的 HUD 屏 ID / 分辨率并不统一，
 * 写死会导致换一台车就直接不可用。默认屏永远排除，避免自我镜像递归。
 */
object HudDisplay {

    const val TARGET_DISPLAY_ID = 3
    const val TARGET_WIDTH = 480
    const val TARGET_HEIGHT = 240

    sealed class Probe {
        data class Ready(val display: Display, val strict: Boolean) : Probe()
        data class Blocked(val reason: String) : Probe()

        val displayId: Int? get() = (this as? Ready)?.display?.displayId
    }

    fun inspect(context: Context, overrideId: Int = 0): Probe {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return Probe.Blocked("系统没有 DisplayManager")

        val defaultId = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.displayId

        // 手动指定优先（1.5）：用户从屏幕清单里选的那块，只要有效就用
        if (overrideId > 0 && overrideId != defaultId) {
            val d = try {
                dm.getDisplay(overrideId)
            } catch (_: Throwable) {
                null
            }
            if (d != null && d.isValid && d.state == Display.STATE_ON) {
                return Probe.Ready(d, strict = false)
            }
            // 指定的屏当前不可用，继续走自动逻辑
        }

        strictTarget(context, dm, defaultId)?.let { return it }

        // ---- 兜底：找一块可用的 Presentation 副屏
        val all = try {
            dm.displays
        } catch (t: Throwable) {
            return Probe.Blocked("枚举屏幕失败：${t.javaClass.simpleName}")
        }

        val candidates = all.filter { d ->
            d.isValid && d.state == Display.STATE_ON &&
                d.displayId != defaultId &&
                d.flags and Display.FLAG_PRESENTATION != 0 &&
                d.flags and Display.FLAG_PRIVATE == 0
        }

        val pick = candidates.firstOrNull { it.displayId == TARGET_DISPLAY_ID }
            ?: candidates.minByOrNull { it.displayId }

        return if (pick != null) {
            Probe.Ready(pick, strict = false)
        } else {
            val ids = all.joinToString { "${it.displayId}(${it.name})" }
            Probe.Blocked(
                "找不到可用的 HUD 副屏。当前屏幕：$ids。需存在一块非默认、支持 Presentation 且已点亮的副屏"
            )
        }
    }

    /** Display 3 且完全满足探针原始规则时返回 Ready；任何一条不满足返回 null 交给兜底。 */
    private fun strictTarget(context: Context, dm: DisplayManager, defaultId: Int?): Probe.Ready? {
        val display = try {
            dm.getDisplay(TARGET_DISPLAY_ID)
        } catch (t: Throwable) {
            return null
        } ?: return null

        if (!display.isValid) return null
        if (display.state != Display.STATE_ON) return null

        val size = Point()
        display.getRealSize(size)
        if (size.x != TARGET_WIDTH || size.y != TARGET_HEIGHT) return null

        val flags = display.flags
        if (flags and Display.FLAG_PRESENTATION == 0) return null
        if (flags and Display.FLAG_PRIVATE != 0) return null
        if (defaultId == null || defaultId == TARGET_DISPLAY_ID) return null

        return Probe.Ready(display, strict = true)
    }

    /** 给 UI 用的描述，例如「Display 3 · 480×240」。 */
    fun describe(display: Display): String {
        val size = Point()
        try {
            display.getRealSize(size)
        } catch (_: Throwable) {
        }
        return "Display ${display.displayId} · ${size.x}×${size.y}"
    }

    /** 监听屏幕增删/状态变化，HUD 掉线或重新上线时回调。 */
    fun registerDisplayListener(context: Context, onChange: () -> Unit): DisplayManager.DisplayListener? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChange()
            override fun onDisplayRemoved(displayId: Int) = onChange()
            override fun onDisplayChanged(displayId: Int) = onChange()
        }
        dm.registerDisplayListener(listener, null)
        return listener
    }

    fun unregisterDisplayListener(context: Context, listener: DisplayManager.DisplayListener?) {
        if (listener == null) return
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        dm.unregisterDisplayListener(listener)
    }
}
