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
 * - 1.82：内存环形缓冲保留最近 10 分钟 / 上限 6000 行（任务书要求 5~10 分钟），
 *   由「开始/停止记录」按钮控制；「手机扫码下载日志」把缓冲导出为 UTF-8 TXT，
 *   经本机临时 HTTP 服务 + 二维码发到手机（见 LogShareServer），不依赖存储权限
 * - 内容只含红绿灯/车道/限速/广播类型等协议诊断数据；GPS、账号、设备标识
 *   类键值一律只记键名不记值（见 GaodeWatcher.sensitiveKey）
 * - 不依赖 Root / ADB，不上传任何第三方云服务器
 */
object DebugLog {

    private const val MAX_BYTES = 1_500_000L
    private const val MAX_FILES = 3

    /** 1.82：内存缓冲保留窗口（毫秒）——最近 10 分钟 */
    const val MEM_WINDOW_MS = 10 * 60_000L
    /** 1.82：内存缓冲行数上限（防高频广播撑爆内存） */
    private const val MEM_MAX_LINES = 6000

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

    /** 内存环形缓冲条目（时间戳 + 行文本） */
    private class Entry(val time: Long, val text: String)

    private val mem = ArrayDeque<Entry>()

    /** 1.82：记录开关（「开始/停止记录」按钮控制）。控制类消息（tag=日志/分享）
     *  不受开关影响，始终记录，保证开关状态本身可追溯。 */
    @Volatile
    var recordingEnabled: Boolean = true

    /** 追加一行日志。同时写入内存缓冲与文件（文件失败不影响内存）。 */
    @Synchronized
    fun line(tag: String, msg: String) {
        val control = tag == "日志" || tag == "分享" || tag == "记录"
        if (!recordingEnabled && !control) return
        val now = System.currentTimeMillis()
        val entry = Entry(now, "${fmt.format(Date(now))} [$tag] $msg")
        mem.addLast(entry)
        // 行数上限
        while (mem.size > MEM_MAX_LINES) mem.removeFirst()
        // 时间窗口修剪（最近 10 分钟）
        while (mem.isNotEmpty() && now - mem.first().time > MEM_WINDOW_MS) mem.removeFirst()
        val d = dir ?: return
        try {
            val f = File(d, "cruise_debug.txt")
            if (f.exists() && f.length() > MAX_BYTES) rotate(d)
            f.appendText(entry.text + "\n")
        } catch (_: Throwable) {
        }
    }

    /** 屏幕显示用——取最近 maxLines 行。 */
    @Synchronized
    fun recent(maxLines: Int = 150): List<String> =
        mem.takeLast(maxLines).map { it.text }

    /** 清空内存缓冲（文件保留）。 */
    @Synchronized
    fun memClear() {
        mem.clear()
        line("日志", "屏幕日志已清空，等待新数据")
    }

    /** 1.82：内存缓冲当前行数（按钮上显示）。 */
    @Synchronized
    fun memCount(): Int = mem.size

    /** 1.82：把内存缓冲导出为 UTF-8 TXT 文本（扫码下载用）。 */
    @Synchronized
    fun dumpText(): String {
        val sb = StringBuilder()
        sb.appendLine("HUD 投屏巡航诊断日志 v1.82")
        sb.appendLine("导出时间: ${fmt.format(Date())}")
        sb.appendLine("缓冲范围: 最近10分钟 / 当前行数 ${mem.size} / 记录开关: ${if (recordingEnabled) "开启" else "关闭"}")
        sb.appendLine("说明: GPS/坐标/账号/设备标识类键已脱敏；车道 JSON 仅截取红绿灯相关片段")
        sb.appendLine("========================================")
        for (e in mem) sb.appendLine(e.text)
        if (mem.isEmpty()) sb.appendLine("（缓冲为空：请先开启记录，再开高德巡航）")
        return sb.toString()
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
     * 导出到 /sdcard/Download/HUD_cruise_debug_<时间戳>.txt（备用通道，
     * 车机没有文件管理器时用「扫码下载」）。需要存储权限。
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
