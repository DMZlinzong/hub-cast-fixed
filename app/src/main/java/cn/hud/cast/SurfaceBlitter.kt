package cn.hud.cast

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 把画面按裁剪框重绘到目标 Surface 上（OES 外置纹理 + 裁剪 UV + 等比缩放）。
 *
 * 生命周期拆成两步，这是 1.2 的关键改动：
 *   1. init()：只建 EGL 环境 + shader + 输入纹理（SurfaceTexture），
 *      用一个 16×16 pbuffer 兜住 current context。此时还没有画面输出目标。
 *   2. attachWindow(surface)：HUD 的 SurfaceView 真正就绪后，把 EGL 窗口
 *      surface 挂上来开始画。HUD 掉线重连时可以反复调用，换窗口不动纹理。
 *
 * 这样"建虚拟屏 / 拉起高德"不再依赖 HUD 表面就绪，两者解耦，
 * HUD 什么时候接上画面什么时候开始，链路不会互相堵死。
 */
class SurfaceBlitter {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    /** 兜住 current context 的 pbuffer，attach 后仍然保留，重挂窗口时切回去销旧。 */
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** 当前绘制用的窗口 surface；未 attach 时为 NO_SURFACE。 */
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** 诊断预览用的第二个窗口 surface（控制页显示虚拟屏原始画面，不裁剪）。 */
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWidth = 0
    private var previewHeight = 0

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uCropLoc = 0

    private var oesTexture = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var viewWidth = HudDisplay.TARGET_WIDTH
    private var viewHeight = HudDisplay.TARGET_HEIGHT
    private var sourceWidth = 0
    private var sourceHeight = 0

    var surfaceTexture: SurfaceTexture? = null
        private set

    private val texMatrix = FloatArray(16)

    /** 第一步：建 EGL 环境 + shader + 输入纹理。不依赖任何画面输出目标。 */
    fun init(): SurfaceTexture {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay 失败")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize 失败")
        }

        // config 必须同时支持 window 和 pbuffer：pbuffer 用来在还没有窗口时兜住 context
        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0 || configs[0] == null
        ) {
            throw RuntimeException("eglChooseConfig 没有可用配置")
        }
        eglConfig = configs[0]

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext 失败")

        val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttrs, 0)
        if (pbufferSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface 失败")
        if (!EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent(pbuffer) 失败")
        }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCropLoc = GLES20.glGetUniformLocation(program, "uCrop")

        vertexBuffer = floatBufferOf(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )
        texCoordBuffer = floatBufferOf(
            floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
            )
        )

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val st = SurfaceTexture(oesTexture)
        surfaceTexture = st
        return st
    }

    /**
     * 第二步：挂上画面输出目标。HUD 表面就绪 / 掉线重连时反复调用，
     * 旧的窗口 surface 自动销毁重建，纹理和虚拟屏都不受影响。
     */
    fun attachWindow(surface: Surface, viewWidth: Int, viewHeight: Int) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("EGL 尚未 init，先调 init()")
        }
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            // 先切回 pbuffer 再销毁旧窗口，避免 current 绑着正在销毁的 surface
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            EGL14.eglDestroySurface(eglDisplay, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
        val attrs = intArrayOf(EGL14.EGL_NONE)
        windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attrs, 0)
        if (windowSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface 失败")
        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent(window) 失败")
        }
        setViewSize(viewWidth, viewHeight)
    }

    /**
     * 诊断预览：把虚拟屏的原始画面（不裁剪）也画到控制页的 SurfaceView 上。
     * 预览挂不上不影响主链路——静默失败即可。
     */
    fun attachPreview(surface: Surface, width: Int, height: Int) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return
        detachPreview()
        try {
            val attrs = intArrayOf(EGL14.EGL_NONE)
            val ps = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attrs, 0)
            if (ps == EGL14.EGL_NO_SURFACE) return
            previewSurface = ps
            previewWidth = if (width > 0) width else 320
            previewHeight = if (height > 0) height else 180
        } catch (_: Throwable) {
        }
    }

    fun detachPreview() {
        if (previewSurface == EGL14.EGL_NO_SURFACE) return
        try {
            val target = if (windowSurface != EGL14.EGL_NO_SURFACE) windowSurface else pbufferSurface
            EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)
            EGL14.eglDestroySurface(eglDisplay, previewSurface)
        } catch (_: Throwable) {
        }
        previewSurface = EGL14.EGL_NO_SURFACE
    }

    fun hasPreview(): Boolean = previewSurface != EGL14.EGL_NO_SURFACE

    /**
     * 输出链路测试彩条（1.4）：不依赖任何输入帧，直接往目标 surface 画移动彩条。
     * HUD 能看到彩条 = EGL → HUD 输出链路完全畅通；看不到 = 链路本身有问题。
     * 和 draw() 一样，预览窗口存在时也画一份。
     */
    fun drawTestPattern(elapsedMs: Long) {
        if (windowSurface == EGL14.EGL_NO_SURFACE && previewSurface == EGL14.EGL_NO_SURFACE) return
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            drawTestTo(windowSurface, viewWidth, viewHeight, elapsedMs)
        }
        val ps = previewSurface
        if (ps != EGL14.EGL_NO_SURFACE) {
            val ok = try {
                EGL14.eglMakeCurrent(eglDisplay, ps, ps, eglContext)
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                drawTestTo(ps, previewWidth, previewHeight, elapsedMs)
                val back = if (windowSurface != EGL14.EGL_NO_SURFACE) windowSurface else pbufferSurface
                EGL14.eglMakeCurrent(eglDisplay, back, back, eglContext)
            }
        }
    }

    private fun drawTestTo(target: EGLSurface, width: Int, height: Int, elapsedMs: Long) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val colors = arrayOf(
            floatArrayOf(0.85f, 0.10f, 0.10f),
            floatArrayOf(0.10f, 0.72f, 0.20f),
            floatArrayOf(0.10f, 0.32f, 0.90f),
            floatArrayOf(0.92f, 0.80f, 0.10f),
            floatArrayOf(0.10f, 0.80f, 0.80f),
            floatArrayOf(0.90f, 0.20f, 0.80f),
            floatArrayOf(1.00f, 1.00f, 1.00f),
            floatArrayOf(0.40f, 0.40f, 0.40f)
        )
        val bands = colors.size
        val shift = ((elapsedMs / 400L) % bands).toInt()
        val bw = (width / bands).coerceAtLeast(1)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (i in 0 until bands) {
            val c = colors[(i + shift) % bands]
            GLES20.glScissor(i * bw, 0, bw, height)
            GLES20.glClearColor(c[0], c[1], c[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        // 一条上下往返的白条，验证方向和动态刷新
        val t = (elapsedMs % 2400L) / 2400f
        val y = (if (t < 0.5f) t * 2f else (1f - t) * 2f) * (height - height / 8)
        GLES20.glScissor(0, y.toInt(), width, height / 8)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        EGL14.eglSwapBuffers(eglDisplay, target)
    }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = if (width > 0) width else HudDisplay.TARGET_WIDTH
        viewHeight = if (height > 0) height else HudDisplay.TARGET_HEIGHT
    }

    fun setSourceSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
    }

    /** 画出当前一帧。调用前必须先 surfaceTexture.updateTexImage() 且已 attachWindow。 */
    fun draw(crop: CropRect, mode: ScaleMode) {
        val st = surfaceTexture ?: return
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        if (windowSurface == EGL14.EGL_NO_SURFACE && previewSurface == EGL14.EGL_NO_SURFACE) return
        st.getTransformMatrix(texMatrix)

        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            drawTo(windowSurface, viewWidth, viewHeight, crop, mode)
        }

        // 预览窗口存在时，把原始画面（全幅、拉伸铺满）也画一份，方便在控制页直接看到虚拟屏内容
        val ps = previewSurface
        if (ps != EGL14.EGL_NO_SURFACE) {
            val ok = try {
                EGL14.eglMakeCurrent(eglDisplay, ps, ps, eglContext)
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                drawTo(ps, previewWidth, previewHeight, CropRect.FULL, ScaleMode.STRETCH)
                val back = if (windowSurface != EGL14.EGL_NO_SURFACE) windowSurface else pbufferSurface
                EGL14.eglMakeCurrent(eglDisplay, back, back, eglContext)
            }
        }
    }

    private fun drawTo(
        target: EGLSurface,
        width: Int,
        height: Int,
        crop: CropRect,
        mode: ScaleMode,
        swap: Boolean = true
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val vp = viewportFor(crop.normalized(), mode, width, height)
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        val c = crop.normalized()
        GLES20.glUniform4f(uCropLoc, c.left, c.top, c.right, c.bottom)

        vertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        texCoordBuffer?.position(0)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        if (swap) EGL14.eglSwapBuffers(eglDisplay, target)
    }

    /**
     * 黑帧采样（1.8）：往窗口 surface 画一帧「全幅、不裁剪」的画面，
     * 读中心 8×8 像素的平均亮度后照常上屏。供镜像模式判断录屏是否返回黑屏。
     * 调用前提：draw() 之后（windowSurface 处于 current），且窗口已挂载。
     * 返回 0~1；未挂窗口返回 -1。
     */
    fun sampleFullBrightness(): Float {
        if (windowSurface == EGL14.EGL_NO_SURFACE) return -1f
        drawTo(windowSurface, viewWidth, viewHeight, CropRect.FULL, ScaleMode.STRETCH, swap = false)
        val buf = ByteBuffer.allocateDirect(8 * 8 * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(
            viewWidth / 2 - 4, viewHeight / 2 - 4, 8, 8,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        EGL14.eglSwapBuffers(eglDisplay, windowSurface)
        var sum = 0L
        for (i in 0 until 64) {
            sum += (buf.get(i * 4).toInt() and 0xFF) + (buf.get(i * 4 + 1).toInt() and 0xFF) +
                (buf.get(i * 4 + 2).toInt() and 0xFF)
        }
        return sum / (64f * 3f * 255f)
    }

    /** 计算真正的目标视口（像素，原点在左下），FILL 时视口会大于画面由 GL 裁掉。 */
    private fun viewportFor(crop: CropRect, mode: ScaleMode, viewWidth: Int, viewHeight: Int): IntArray {
        if (mode == ScaleMode.STRETCH || sourceWidth <= 0 || sourceHeight <= 0) {
            return intArrayOf(0, 0, viewWidth, viewHeight)
        }
        val srcAspect = (sourceWidth * crop.width) / (sourceHeight * crop.height)
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        var w: Float
        var h: Float
        when (mode) {
            ScaleMode.FIT ->
                if (srcAspect > viewAspect) {
                    w = viewWidth.toFloat()
                    h = w / srcAspect
                } else {
                    h = viewHeight.toFloat()
                    w = h * srcAspect
                }

            ScaleMode.FILL ->
                if (srcAspect > viewAspect) {
                    h = viewHeight.toFloat()
                    w = h * srcAspect
                } else {
                    w = viewWidth.toFloat()
                    h = w / srcAspect
                }

            ScaleMode.STRETCH -> {
                w = viewWidth.toFloat()
                h = viewHeight.toFloat()
            }
        }

        val x = ((viewWidth - w) / 2f).toInt()
        val y = ((viewHeight - h) / 2f).toInt()
        return intArrayOf(x, y, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
    }

    fun release() {
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
        } catch (_: Throwable) {
        }
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, windowSurface)
            }
            if (previewSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewSurface)
            }
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        windowSurface = EGL14.EGL_NO_SURFACE
        previewSurface = EGL14.EGL_NO_SURFACE
        pbufferSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        program = 0
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("链接 shader 失败：$log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("编译 shader 失败：$log")
        }
        return shader
    }

    private fun floatBufferOf(data: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data)
        buf.position(0)
        return buf
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform vec4 uCrop;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vec2 cropped = uCrop.xy + aTexCoord * (uCrop.zw - uCrop.xy);
                vTexCoord = (uTexMatrix * vec4(cropped, 0.0, 1.0)).xy;
                gl_Position = aPosition;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTexCoord);
            }
        """
    }
}
