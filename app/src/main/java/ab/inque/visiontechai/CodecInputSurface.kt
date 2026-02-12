package ab.inque.visiontechai

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface

/**
 * EGL surface that feeds the MediaCodec encoder. Draw bitmaps to it with OpenGL
 * so the encoded video matches exactly what we draw (same colors as on-screen).
 */
class CodecInputSurface(private val surface: Surface, private val surfaceWidth: Int, private val surfaceHeight: Int) {

    private var eglDisplayVar: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContextVar: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurfaceVar: EGLSurface = EGL14.EGL_NO_SURFACE

    private var textureId = IntArray(1)
    private var program = 0
    private var texMatrix = FloatArray(16)

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
            }
        """
    }

    init {
        eglDisplayVar = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplayVar == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplayVar, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplayVar, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val contextAttribList = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContextVar = EGL14.eglCreateContext(eglDisplayVar, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribList, 0)
        checkEglError("eglCreateContext")
        eglSurfaceVar = EGL14.eglCreateWindowSurface(eglDisplayVar, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        checkEglError("eglCreateWindowSurface")
        makeCurrent()
        glSetup()
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$msg: EGL error 0x${Integer.toHexString(error)}")
        }
    }

    private fun glSetup() {
        GLES20.glGenTextures(1, textureId, 0)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        Matrix.setIdentityM(texMatrix, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val p = GLES20.glCreateProgram()
        if (p == 0) throw RuntimeException("glCreateProgram failed")
        GLES20.glAttachShader(p, vertexShader)
        GLES20.glAttachShader(p, fragmentShader)
        GLES20.glLinkProgram(p)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
        }
        return p
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw RuntimeException("glCreateShader failed")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    /**
     * Draw the bitmap to the surface (full-screen). Call setPresentationTime() and swapBuffers() after this.
     */
    fun drawBitmap(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES20.glUseProgram(program)
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        // Full-screen quad: two triangles
        val coords = floatArrayOf(
            -1f, -1f, 0f, 1f,  // left bottom
            1f, -1f, 0f, 1f,   // right bottom
            -1f, 1f, 0f, 1f,   // left top
            1f, 1f, 0f, 1f     // right top
        )
        val texCoords = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
        )
        val vertexBuffer = java.nio.ByteBuffer.allocateDirect(coords.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(coords)
        vertexBuffer.position(0)
        val texBuffer = java.nio.ByteBuffer.allocateDirect(texCoords.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords)
        texBuffer.position(0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 4, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplayVar, eglSurfaceVar, eglSurfaceVar, eglContextVar)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
        checkEglError("eglMakeCurrent")
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplayVar, eglSurfaceVar, nsecs)
        checkEglError("eglPresentationTimeANDROID")
    }

    fun swapBuffers(): Boolean {
        val result = EGL14.eglSwapBuffers(eglDisplayVar, eglSurfaceVar)
        checkEglError("eglSwapBuffers")
        return result
    }

    fun release() {
        if (eglDisplayVar != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplayVar, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplayVar, eglSurfaceVar)
            EGL14.eglDestroyContext(eglDisplayVar, eglContextVar)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplayVar)
        }
        surface.release()
        eglDisplayVar = EGL14.EGL_NO_DISPLAY
        eglContextVar = EGL14.EGL_NO_CONTEXT
        eglSurfaceVar = EGL14.EGL_NO_SURFACE
    }
}
