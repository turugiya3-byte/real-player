package com.example.xrealdepthplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DepthFilterRenderer(
    private val onSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var edgeStrength: Float = 1.3f
    @Volatile var depthPop: Float = 0.45f
    @Volatile var vignetteStrength: Float = 0.35f
    @Volatile var chromaticShift: Float = 0.002f
    @Volatile var videoAspectRatio: Float = 16f / 9f

    private var programId = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)
    private var viewWidth = 1920
    private var viewHeight = 1080

    private val vertexData = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(vertexData).apply { position(0) }

    private val vertexShaderCode = """
        #version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform mat4 uSTMatrix;
        uniform vec2 uScale;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);
            vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;

        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform samplerExternalOES uTexture;
        uniform float uEdgeStrength;
        uniform float uDepthPop;
        uniform float uVignette;
        uniform float uChromatic;
        uniform vec2 uTexelSize;

        void main() {
            vec2 uv = vTexCoord;

            // 1. 微細色収差（擬似視差ブースト）
            float r = texture(uTexture, uv + vec2(uChromatic, 0.0)).r;
            float g = texture(uTexture, uv).g;
            float b = texture(uTexture, uv - vec2(uChromatic, 0.0)).b;
            vec3 col = vec3(r, g, b);

            // 2. マイクロコントラスト（被写体と背景の境界エッジ強調）
            vec3 blur = (
                texture(uTexture, uv + vec2(-uTexelSize.x, -uTexelSize.y)).rgb +
                texture(uTexture, uv + vec2( uTexelSize.x, -uTexelSize.y)).rgb +
                texture(uTexture, uv + vec2(-uTexelSize.x,  uTexelSize.y)).rgb +
                texture(uTexture, uv + vec2( uTexelSize.x,  uTexelSize.y)).rgb
            ) * 0.25;
            col = col + (col - blur) * uEdgeStrength;

            // 3. 中央手前・周辺奥のコントラスト分離 & 周辺減光
            vec2 centered = (uv - 0.5) * 2.0;
            float dist = clamp(length(centered) / 1.414, 0.0, 1.0);
            float radial = 1.0 - dist;

            float pop = 1.0 + (radial - 0.4) * uDepthPop;
            col = (col - 0.5) * pop + 0.5;

            float vig = 1.0 - pow(dist, 2.2) * uVignette;
            col *= vig;

            fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        surfaceTexture = SurfaceTexture(textureId)
        onSurfaceReady(Surface(surfaceTexture))

        programId = createProgram(vertexShaderCode, fragmentShaderCode)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(stMatrix)

        GLES30.glUseProgram(programId)

        // アスペクト比補正（黒帯レターボックス/ピラーボックス維持）
        val screenAspect = viewWidth.toFloat() / viewHeight.coerceAtLeast(1).toFloat()
        var scaleX = 1f
        var scaleY = 1f
        if (videoAspectRatio > screenAspect) {
            scaleY = screenAspect / videoAspectRatio
        } else {
            scaleX = videoAspectRatio / screenAspect
        }

        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programId, "uSTMatrix"), 1, false, stMatrix, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uScale"), scaleX, scaleY)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEdgeStrength"), edgeStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDepthPop"), depthPop)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uVignette"), vignetteStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uChromatic"), chromaticShift)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uTexelSize"), 1f / viewWidth, 1f / viewHeight)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fs)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vShader)
            GLES30.glAttachShader(it, fShader)
            GLES30.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        return GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src)
            GLES30.glCompileShader(it)
        }
    }
}
