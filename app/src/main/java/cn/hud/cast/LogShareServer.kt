package cn.hud.cast

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.82：诊断日志扫码下载——应用内临时 HTTP 服务 + 二维码。
 *
 * 安全边界（任务书第 6 条）：
 * - 只暴露一个固定路径 GET /log/<随机令牌>.txt，其余一律 404
 * - 不提供目录列表、上传、任何文件系统访问或远程执行
 * - 内容是内存日志的 UTF-8 文本（已脱敏），不落盘、不读外部文件
 * - 一次完整下载成功 或 5 分钟无人下载 → 自动关服务
 * - 全程局域网直连，不经任何第三方云服务器
 */
object LogShareServer {

    private const val IDLE_TIMEOUT_MS = 5 * 60_000L

    @Volatile
    var running: Boolean = false
        private set

    private var server: ServerSocket? = null
    @Volatile
    private var token: String = ""
    @Volatile
    private var content: ByteArray = ByteArray(0)
    private val alive = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var onEvent: ((String) -> Unit)? = null

    /**
     * 启动服务。网络操作在后台线程执行，结果回调到主线程。
     * @param onReady url 非空 = 成功；null = 失败（errMsg 给原因）
     */
    fun start(payload: String, onReady: (url: String?, errMsg: String?) -> Unit, event: (String) -> Unit) {
        stop()
        onEvent = event
        content = payload.toByteArray(Charsets.UTF_8)
        token = randomToken()
        Thread {
            var url: String? = null
            var err: String? = null
            try {
                val ip = lanIp()
                    ?: run {
                        err = "车机未连接局域网（Wi-Fi / 热点均未连上），找不到本机 IP"
                        return@Thread
                    }
                val ss = ServerSocket(0, 64, InetAddress.getByName("0.0.0.0"))
                server = ss
                running = true
                alive.set(true)
                url = "http://$ip:${ss.localPort}/log/$token.txt"
                Thread { acceptLoop() }.apply { isDaemon = true; name = "hud-log-http"; start() }
                main.postDelayed({
                    if (running) {
                        event("5 分钟无人下载，服务已自动关闭")
                        stop()
                    }
                }, IDLE_TIMEOUT_MS)
            } catch (t: Throwable) {
                err = "HTTP 服务启动失败：${t.message}"
            } finally {
                val u = url
                main.post { onReady(u, err) }
            }
        }.apply { isDaemon = true; name = "hud-log-http-start"; start() }
    }

    private fun acceptLoop() {
        while (running) {
            val s = try {
                server?.accept() ?: break
            } catch (_: Throwable) {
                break
            }
            Thread { handle(s) }.apply { isDaemon = true; start() }
        }
    }

    /** 处理单个连接：只认 GET /log/<token>.txt，其余 404。 */
    private fun handle(s: Socket) {
        var success = false
        try {
            s.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val reqLine = reader.readLine() ?: return
            // 排空请求头（防长连接卡死，读完到空行为止）
            while (reader.readLine()?.isNotEmpty() == true) { /* drain */ }
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: ""
            if (method == "GET" && path == "/log/$token.txt") {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n")
                    append("Content-Length: ${content.size}\r\n")
                    append("Content-Disposition: attachment; filename=\"HUD_cruise_debug_$ts.txt\"\r\n")
                    append("Connection: close\r\n\r\n")
                }
                s.getOutputStream().use { out ->
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(content)
                    out.flush()
                }
                success = true
            } else {
                val body = "404 Not Found — 本服务只提供本次诊断日志下载，无其它内容".toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().use { out ->
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(body)
                    out.flush()
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { s.close() } catch (_: Throwable) {}
        }
        if (success) {
            main.post {
                onEvent?.invoke("日志下载完成，服务已自动关闭")
                stop()
            }
        }
    }

    /** 停止服务（幂等）。 */
    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        main.removeCallbacksAndMessages(null)
    }

    /** 枚举网卡取局域网 IPv4（优先 wlan / 常规站点内地址）。 */
    private fun lanIp(): String? {
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val ia = addrs.nextElement()
                    if (ia is Inet4Address && !ia.isLoopbackAddress) {
                        if (ia.isSiteLocalAddress) {
                            // wlan 优先
                            if (ni.name?.startsWith("wlan") == true || ni.displayName?.startsWith("wlan") == true) {
                                return ia.hostAddress
                            }
                            if (fallback == null) fallback = ia.hostAddress
                        }
                    }
                }
            }
            return fallback
        } catch (_: Throwable) {
            return null
        }
    }

    private fun randomToken(): String {
        val b = ByteArray(9)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 生成二维码位图（zxing core，纯本地计算，无网络）。 */
    fun qrBitmap(text: String, size: Int = 480): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Throwable) {
        null
    }
}
