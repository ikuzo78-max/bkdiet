package com.rawlab.editor.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.rawlab.editor.raw.CurveLut
import com.rawlab.editor.raw.DecodedRaw
import com.rawlab.editor.raw.EditState
import com.rawlab.editor.raw.FilmSimLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * DecodedRaw 비트맵을 텍스처로 올리고, adjust.vert/adjust.frag 셰이더로
 * 노출/대비/화이트밸런스/하이라이트-섀도우/채도-생동감/샤픈 + 크롭/회전을 실시간 렌더링한다.
 */
class RawGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile
    var editState: EditState = EditState()

    // 화면 확대/이동(핀치줌/드래그). 별도로 원본을 다시 디코드하지 않고 이미 올라간
    // 프록시 텍스처를 화면에서만 확대해서 보여준다 — 재디코드가 필요 없어 안전하다.
    @Volatile
    var viewZoom: Float = 1f
    @Volatile
    var viewPanX: Float = 0f
    @Volatile
    var viewPanY: Float = 0f

    private var program = 0
    private var vbo = 0
    private var textureId = 0
    private var curveLutTextureId = 0
    private var lastCurveKey: List<List<Float>>? = null
    private var filmLutTextureId = 0
    private var lastFilmSimulation: String? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var pendingImage: DecodedRaw? = null
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val vertexBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val mvpMatrix = FloatArray(16)

    /** 디코드된 RAW 이미지를 다음 프레임에 텍스처로 업로드하도록 예약한다. GL 스레드 밖에서 호출 가능. */
    fun submitImage(image: DecodedRaw) {
        synchronized(this) { pendingImage = image }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.07f, 0.07f, 0.07f, 1f)

        val vertSrc = context.assets.open("shaders/adjust.vert").bufferedReader().use { it.readText() }
        val fragSrc = context.assets.open("shaders/adjust.frag").bufferedReader().use { it.readText() }
        val vertShader = ShaderUtils.compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fragShader = ShaderUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        program = ShaderUtils.linkProgram(vertShader, fragShader)
        GLES30.glDeleteShader(vertShader)
        GLES30.glDeleteShader(fragShader)

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        vbo = vboArr[0]

        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        textureId = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val curveTexArr = IntArray(1)
        GLES30.glGenTextures(1, curveTexArr, 0)
        curveLutTextureId = curveTexArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveLutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val filmLutTexArr = IntArray(1)
        GLES30.glGenTextures(1, filmLutTexArr, 0)
        filmLutTextureId = filmLutTexArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, filmLutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        // strength=0일 때는 내용이 무의미하므로(mix로 걸러짐) 1x1x1 더미로 초기화해둔다.
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB, 1, 1, 1, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(byteArrayOf(0, 0, 0))
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadPendingImageIfAny()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (imageWidth == 0 || imageHeight == 0) return

        GLES30.glUseProgram(program)
        updateVertexData()
        updateMvpMatrix()

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(POSITION_LOC)
        GLES30.glVertexAttribPointer(POSITION_LOC, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(UV_LOC)
        GLES30.glVertexAttribPointer(UV_LOC, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, 8)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uTexelSize"),
            1f / imageWidth, 1f / imageHeight
        )
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvpMatrix, 0)

        updateCurveLutIfNeeded()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveLutTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCurveLut"), 1)

        updateFilmLutIfNeeded()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, filmLutTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFilmLut"), 2)
        val filmStrength = if (editState.filmSimulation == FilmSimLut.NONE) 0f else editState.filmSimStrength
        setFloat("uFilmLutStrength", filmStrength)

        val state = editState
        setFloat("uExposure", state.exposure)
        setFloat("uContrast", state.contrast)
        setFloat("uTemperature", state.temperature)
        setFloat("uTint", state.tint)
        setFloat("uHighlights", state.highlights)
        setFloat("uShadows", state.shadows)
        setFloat("uSaturation", state.saturation)
        setFloat("uVibrance", state.vibrance)
        setFloat("uSharpen", state.sharpen)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setFloat(name: String, value: Float) {
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), value)
    }

    private fun updateCurveLutIfNeeded() {
        val state = editState
        val key = listOf(state.curveMaster, state.curveRed, state.curveGreen, state.curveBlue)
        if (key == lastCurveKey) return
        lastCurveKey = key
        val lut = CurveLut.buildCombined256(state.curveMaster, state.curveRed, state.curveGreen, state.curveBlue)
        // 반드시 유닛1을 활성화한 뒤 바인드해야 한다 — 이 시점에 유닛0이 활성 상태이면
        // glBindTexture가 유닛0의 GL_TEXTURE_2D 바인딩(사진 텍스처)을 덮어써서
        // 화면에 사진 대신 이 LUT 텍스처가 그려지는 버그가 생긴다.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveLutTextureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB,
            256, 1, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(lut)
        )
    }

    private fun updateFilmLutIfNeeded() {
        val name = editState.filmSimulation
        if (name == lastFilmSimulation) return
        lastFilmSimulation = name
        val lutFloats = FilmSimLut.load(context, name) ?: return

        val size = FilmSimLut.LUT_SIZE
        val bytes = ByteArray(lutFloats.size)
        for (i in lutFloats.indices) {
            bytes[i] = (lutFloats[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        // GL_TEXTURE_3D는 GL_TEXTURE_2D와 별개 타겟이라 사진 텍스처(유닛0, 2D)를 직접
        // 덮어쓰진 않지만, 항상 명시적으로 유닛을 지정해 어느 유닛이 활성 상태든 안전하게 만든다.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, filmLutTextureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB, size, size, size, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes)
        )
    }

    private fun uploadPendingImageIfAny() {
        val image = synchronized(this) { pendingImage.also { pendingImage = null } } ?: return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        // RGB8 각 행은 width*3바이트라 4의 배수가 아닐 수 있음 - 기본 UNPACK_ALIGNMENT(4)를 쓰면
        // 행 경계가 어긋나 이미지가 깨진다.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val buffer = ByteBuffer.wrap(image.pixels)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB,
            image.width, image.height, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        imageWidth = image.width
        imageHeight = image.height
    }

    private fun updateVertexData() {
        val state = editState
        // top-left, top-right, bottom-left, bottom-right (position xy, uv xy)
        val verts = floatArrayOf(
            -1f, 1f, state.cropLeft, state.cropTop,
            1f, 1f, state.cropRight, state.cropTop,
            -1f, -1f, state.cropLeft, state.cropBottom,
            1f, -1f, state.cropRight, state.cropBottom,
        )
        vertexBuffer.clear()
        vertexBuffer.put(verts)
        vertexBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vertexBuffer, GLES30.GL_DYNAMIC_DRAW)
    }

    private fun updateMvpMatrix() {
        val state = editState
        Matrix.setIdentityM(mvpMatrix, 0)
        if (viewportWidth == 0 || viewportHeight == 0) return

        // 핀치줌/드래그를 최종 화면 공간에서 가장 바깥쪽에 적용한다(v' = Pan * Zoom * ...*v)
        // — 그래야 팬 이동량이 확대 배율과 무관하게 화면 픽셀 이동량과 일치한다.
        Matrix.translateM(mvpMatrix, 0, viewPanX, viewPanY, 0f)
        Matrix.scaleM(mvpMatrix, 0, viewZoom, viewZoom, 1f)

        val cropWidth = (state.cropRight - state.cropLeft).coerceAtLeast(0.01f) * imageWidth
        val cropHeight = (state.cropBottom - state.cropTop).coerceAtLeast(0.01f) * imageHeight
        val rotated90 = state.rotationDegrees % 180 != 0
        val contentWidth = if (rotated90) cropHeight else cropWidth
        val contentHeight = if (rotated90) cropWidth else cropHeight

        val viewAspect = viewportWidth.toFloat() / viewportHeight
        val contentAspect = if (contentHeight == 0f) 1f else contentWidth / contentHeight

        val scaleX: Float
        val scaleY: Float
        if (contentAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / contentAspect
        } else {
            scaleX = contentAspect / viewAspect
            scaleY = 1f
        }

        // 뷰포트에 맞춰 스케일한 뒤 회전 (v' = R * S * v)
        Matrix.rotateM(mvpMatrix, 0, state.rotationDegrees.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
    }

    private companion object {
        const val POSITION_LOC = 0
        const val UV_LOC = 1
        const val STRIDE_BYTES = 16
    }
}
