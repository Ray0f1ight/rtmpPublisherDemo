package com.example.rtc_demo.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 把相机输出的 OES 外部纹理铺满整个 GLSurfaceView。
 *
 * @param onSurfaceTextureAvailable 在 GL 线程回调，参数就是可以交给相机的 SurfaceTexture
 * @param onFrameAvailable 相机送来新帧时回调，用来触发 requestRender
 */
class CameraPreviewRenderer(
    private val onSurfaceTextureAvailable: (SurfaceTexture) -> Unit,
    private val onFrameAvailable: () -> Unit
) : GLSurfaceView.Renderer {

    private val textureMatrix = FloatArray(16)

    private val vertexBuffer = createFloatBuffer(VERTEX_COORDS)

    private val texCoordBuffer = createFloatBuffer(TEXTURE_COORDS)

    private var program = 0

    private var positionHandle = 0

    private var texCoordHandle = 0

    private var textureMatrixHandle = 0

    private var textureHandle = 0

    private var oesTextureId = 0

    private var surfaceTexture: SurfaceTexture? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // EGL context 重建时上一个 SurfaceTexture 已经连着旧纹理失效了
        releaseSurfaceTexture()

        program = createProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        oesTextureId = createOesTexture()
        val texture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener { onFrameAvailable() }
        }
        surfaceTexture = texture
        onSurfaceTextureAvailable(texture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture ?: return
        // 把相机最新那一帧绑到 OES 纹理上，并取出配套的坐标变换矩阵
        texture.updateTexImage()
        texture.getTransformMatrix(textureMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /** SurfaceTexture 可以在任意线程释放；纹理和 program 随 EGL context 销毁自动回收 */
    fun releaseSurfaceTexture() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textures[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertexShader)
        GLES20.glAttachShader(id, fragmentShader)
        GLES20.glLinkProgram(id)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) {
            "link program failed: ${GLES20.glGetProgramInfoLog(id)}"
        }

        // 链接完成后 shader 对象就没用了
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return id
    }

    private fun compileShader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            "compile shader failed: ${GLES20.glGetShaderInfoLog(id)}"
        }
        return id
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    private companion object {
        const val VERTEX_COUNT = 4

        /** 铺满整个视口的矩形，NDC 坐标 */
        val VERTEX_COORDS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        /** 纹理坐标，真实朝向交给 uTextureMatrix 校正 */
        val TEXTURE_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTextureMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }
        """.trimIndent()
    }
}
