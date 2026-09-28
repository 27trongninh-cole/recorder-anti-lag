package com.hoa.overlaytest

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Android 14+ chỉ cho phép gọi MediaProjection.createVirtualDisplay() ĐÚNG 1 LẦN
 * trên mỗi MediaProjection. Vì vậy chỉ có 1 VirtualDisplay, đổ frame vào class
 * này (qua Surface do start() trả về). Mỗi frame mới class này:
 *  1. Vẽ ra Surface của encoder (giới hạn ~35fps để nhẹ GPU/encoder)
 *  2. Định kỳ (analysisIntervalMs) vẽ thêm 1 bản thu nhỏ vào FBO, đọc pixel về
 *     thành Bitmap và gửi cho onAnalysisFrame (để OCR/avatar matching)
 *
 * Toàn bộ chạy trên 1 thread GL riêng, ưu tiên thấp.
 */
class ScreenFrameRouter(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val analysisWidth: Int,
    private val analysisHeight: Int,
    private val analysisIntervalMs: Long,
    private val onAnalysisFrame: (Bitmap) -> Unit
) {
    private val MIN_ENCODE_INTERVAL_NS = 1_000_000_000L / 35

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0

    private var fbo = 0
    private var fboTex = 0
    private val readBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(analysisWidth * analysisHeight * 4).order(ByteOrder.nativeOrder())

    private val texMatrix = FloatArray(16)
    private var lastEncodeTs = 0L
    private var lastAnalysisTimeMs = 0L
    @Volatile private var released = false

    private val vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val texCoordBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }

    /** Khởi tạo EGL trên thread GL riêng, trả về Surface để đưa cho VirtualDisplay */
    fun start(): Surface {
        val t = HandlerThread("ScreenFrameRouter", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        thread = t
        handler = Handler(t.looper)

        val latch = CountDownLatch(1)
        var initError: Exception? = null
        handler!!.post {
            try {
                initGl()
            } catch (e: Exception) {
                initError = e
            }
            latch.countDown()
        }
        latch.await()
        initError?.let { throw it }
        return inputSurface!!
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: throw IllegalStateException("Không chọn được EGLConfig")

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // FBO dùng cho bản thu nhỏ phục vụ phân tích
        val ft = IntArray(1)
        GLES20.glGenTextures(1, ft, 0)
        fboTex = ft[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, analysisWidth, analysisHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        fbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun drawFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val ts = st.timestamp

            // 1) Vẽ ra encoder (bỏ bớt frame nếu game chạy > ~35fps)
            if (ts - lastEncodeTs >= MIN_ENCODE_INTERVAL_NS) {
                lastEncodeTs = ts
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, width, height)
                drawQuad()
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ts)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }

            // 2) Định kỳ vẽ bản thu nhỏ vào FBO và đọc về Bitmap để phân tích
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastAnalysisTimeMs >= analysisIntervalMs) {
                lastAnalysisTimeMs = nowMs
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glViewport(0, 0, analysisWidth, analysisHeight)
                drawQuad()
                readBuffer.rewind()
                GLES20.glReadPixels(
                    0, 0, analysisWidth, analysisHeight,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                readBuffer.rewind()
                val raw = Bitmap.createBitmap(analysisWidth, analysisHeight, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(readBuffer)
                // glReadPixels đọc từ dưới lên -> lật dọc lại cho đúng chiều
                val flip = Matrix().apply { postScale(1f, -1f, analysisWidth / 2f, analysisHeight / 2f) }
                val upright = Bitmap.createBitmap(raw, 0, 0, analysisWidth, analysisHeight, flip, false)
                raw.recycle()
                onAnalysisFrame(upright)
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenFrameRouter", "Lỗi drawFrame", e)
        }
    }

    private fun drawQuad() {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    fun release() {
        released = true
        val h = handler
        val t = thread
        if (h == null || t == null) return
        val latch = CountDownLatch(1)
        h.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                inputSurface?.release()
                surfaceTexture?.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await()
        t.quitSafely()
        thread = null
        handler = null
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
