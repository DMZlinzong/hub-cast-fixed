package cn.hud.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 开机自启动（1.16）。
 *
 * 收到 BOOT_COMPLETED 后若「开机自启动」开关是开的，就直接拉起常驻服务：
 * 服务起来后立刻绑定车机版高德的数据服务、建 HUD 卡片窗口，
 * 之后无论什么时候开始导航，HUD 都会自动出内容——不需要再点一次「开始投屏」。
 *
 * 注意：开机时不存在任何录屏授权，所以镜像 / 后台虚拟屏模式一律降级成卡片模式，
 * 否则系统会以「后台启动 mediaProjection 类型前台服务」为由直接拒绝。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HudCast"
        private const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val LOCKED_BOOT = "android.intent.action.LOCKED_BOOT_COMPLETED"

        private val TRIGGERS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            QUICKBOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            LOCKED_BOOT
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in TRIGGERS) return

        val app = context.applicationContext
        val config = CastConfig(app)
        if (!config.autoStartOnBoot) {
            Log.i(TAG, "开机广播 $action 到达，但「开机自启动」未开启，不做任何事")
            return
        }

        // 开机时不存在任何录屏授权，常驻一律用卡片模式（免授权、免抓屏）
        val mode = HudCastService.MODE_CARD
        Log.i(TAG, "开机自启动触发：action=$action → 启动模式=卡片常驻")

        // 开机瞬间系统繁忙，启动可能被拒，短暂重试；goAsync 让广播进程活到重试结束
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                attempt++
                val ok = HudCastService.startAuto(app, mode)
                Log.i(TAG, "开机自启动第 $attempt 次请求：${if (ok) "已提交" else "被拒"}")
                if (!ok && attempt < 3) {
                    handler.postDelayed(this, 2_000L)
                } else {
                    try {
                        pending.finish()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        handler.post(task)
    }
}
