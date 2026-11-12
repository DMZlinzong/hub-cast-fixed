package cn.hud.cast

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.80：巡航红绿灯诊断日志（诊断版专用功能）。
 *
 * - 写入 getExternalFilesDir/logs/cruise_debug.txt（App 卸载即清除，无需任何权限）
 * - 单文件上限 1.5MB，超过自动轮转（保留 3 个：cruise_debug.txt / .1 / .2）
 * - 内容只含红绿灯/车道/限速/广播类型等协议诊断数据；GPS、账号、设备标识
 *   类键值一律只记键名不记值（见 GaodeWatcher.sensitiveKey）
 * - 「导出诊断日志」按钮把日志复制到 /sdcard/Download/，用文件管理器即可取走
 * - 不依赖 Root / ADB
 */
object DebugLog {

    private const val MAX_BYTES = 1_500_000L
    private const val MAX_FILES = 3

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @Volatile
    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        dir = try {
            val base = ctx.getExternalFilesDir(null)
            File(base, "logs").apply { mkdirs() }
        } catch (_: Throwable) {
            null
        }
    }

    /** 追加一行日志。任何异常静默忽略——诊断功能绝不能影响投屏主链路。 */
    @Synchronized
    fun line(tag: String, msg: String) {
        val d = dir ?: return
        try {
            val f = File(d, "cruise_debug.txt")
            if (f.exists() && f.length() > MAX_BYTES) rotate(d)
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
        } catch (_: Throwable) {
        }
    }

    /** 轮转：cruise_debug.txt → .1 → .2（最老丢弃）。 */
    private fun rotate(d: File) {
        try {
            val oldest = File(d, "cruise_debug.txt.${MAX_FILES - 1}")
            if (oldest.exists()) oldest.delete()
            for (i in MAX_FILES - 2 downTo 1) {
                val s = File(d, "cruise_debug.txt.$i")
                if (s.exists()) s.renameTo(File(d, "cruise_debug.txt.${i + 1}"))
            }
            File(d, "cruise_debug.txt").renameTo(File(d, "cruise_debug.txt.1"))
        } catch (_: Throwable) {
        }
    }

    /** 日志文件（不存在返回 null）。 */
    fun currentFile(): File? = dir?.let { d -> File(d, "cruise_debug.txt").takeIf { it.exists() } }

    /**
     * 导出到 /sdcard/Download/HUD_cruise_debug_<时间戳>.txt。
     * 需要 WRITE_EXTERNAL_STORAGE 运行时权限（Android 9 上正常生效）。
     * 返回 Pair(成功?, 提示文本)。
     */
    fun exportToDownload(ctx: Context): Pair<Boolean, String> {
        val src = currentFile() ?: return Pair(false, "暂无日志：先开启巡航，让 HUD 收几条高德广播再导出")
        return try {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dl.mkdirs()
            val ts = SimpleDateFormat("MMdd_HHmm", Locale.CHINA).format(Date())
            val dst = File(dl, "HUD_cruise_debug_$ts.txt")
            src.copyTo(dst, overwrite = true)
            Pair(true, "已导出：/sdcard/Download/${dst.name}\n（文件管理器 → Download 目录）")
        } catch (t: Throwable) {
            Pair(false, "导出失败：${t.message}\n（请先在系统设置里授予本 App「存储」权限）")
        }
    }

    /** 清空当前日志（重新开始记录一轮干净数据）。 */
    @Synchronized
    fun clear() {
        try {
            currentFile()?.delete()
            line("日志", "日志已清空，开始新一轮记录")
        } catch (_: Throwable) {
        }
    }
}
