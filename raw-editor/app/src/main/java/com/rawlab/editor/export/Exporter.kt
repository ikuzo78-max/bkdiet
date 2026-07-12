package com.rawlab.editor.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rawlab.editor.gl.ShaderUtils
import com.rawlab.editor.raw.DecodedRaw
import com.rawlab.editor.raw.EditState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 프리뷰(RawGLRenderer)와 동일한 셰이더/파라미터로 전체 해상도를 오프스크린(EGL Pbuffer)에
 * 렌더링한 뒤 JPEG으로 MediaStore에 저장한다. "보이는 대로 저장된다"를 보장하기 위해
 * adjust.vert/adjust.frag를 그대로 재사용한다.
 */
object Exporter {

    fun export(context: Context, decoded: DecodedRaw, editState: EditState, sourceDisplayName: String) {
        val cropLeftPx = (editState.cropLeft * decoded.width).roundToInt()
        val cropTopPx = (editState.cropTop * decoded.height).roundToInt()
        val cropRightPx = (editState.cropRight * decoded.width).roundToInt()
        val cropBottomPx = (editState.cropBottom * decoded.height).roundToInt()
        val cropWidth = (cropRightPx - cropLeftPx).coerceAtLeast(1)
        val cropHeight = (cropBottomPx - cropTopPx).coerceAtLeast(1)
        val rotated90 = editState.rotationDegrees % 180 != 0
        val outputWidth = if (rotated90) cropHeight else cropWidth
        val outputHeight = if (rotated90) cropWidth else cropHeight

        val pixels = renderOffscreen(context, decoded, editState, outputWidth, outputHeight)
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixels)

        saveToMediaStore(context, bitmap, sourceDisplayName)
    }

    private fun renderOffscreen(
        context: Context,
        decoded: DecodedRaw,
        editState: EditState,
        outputWidth: Int,
        outputHeight: Int,
    ): ByteBuffer {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL 초기화 실패" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: error("적합한 EGL config를 찾지 못함")

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context 생성 실패" }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, outputWidth,
            EGL14.EGL_HEIGHT, outputHeight,
            EGL14.EGL_NONE,
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer surface 생성 실패" }

        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
        try {
            return drawAndReadPixels(context, decoded, editState, outputWidth, outputHeight)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, eglContext)
            // eglGetDisplay(EGL_DEFAULT_DISPLAY)는 프로세스 공용 핸들이라 eglTerminate는 호출하지 않는다
            // (에디터 화면의 GLSurfaceView가 같은 디스플레이를 계속 쓰고 있을 수 있음).
        }
    }

    private fun drawAndReadPixels(
        context: Context,
        decoded: DecodedRaw,
        editState: EditState,
        outputWidth: Int,
        outputHeight: Int,
    ): ByteBuffer {
        val vertSrc = context.assets.open("shaders/adjust.vert").bufferedReader().use { it.readText() }
        val fragSrc = context.assets.open("shaders/adjust.frag").bufferedReader().use { it.readText() }
        val vertShader = ShaderUtils.compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fragShader = ShaderUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val program = ShaderUtils.linkProgram(vertShader, fragShader)
        GLES30.glDeleteShader(vertShader)
        GLES30.glDeleteShader(fragShader)

        val textureId = IntArray(1)
        GLES30.glGenTextures(1, textureId, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB,
            decoded.width, decoded.height, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(decoded.pixels)
        )

        val verts = floatArrayOf(
            -1f, 1f, editState.cropLeft, editState.cropTop,
            1f, 1f, editState.cropRight, editState.cropTop,
            -1f, -1f, editState.cropLeft, editState.cropBottom,
            1f, -1f, editState.cropRight, editState.cropBottom,
        )
        val vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }

        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

        // 출력 버퍼 크기를 회전 후 콘텐츠 크기에 정확히 맞춰 만들었으므로 별도 스케일 보정 없이
        // 회전만 반영하면 된다 (프리뷰의 letterbox 피팅과 달리 export는 크롭 결과를 꽉 채운다).
        val mvpMatrix = FloatArray(16)
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.rotateM(mvpMatrix, 0, editState.rotationDegrees.toFloat(), 0f, 0f, 1f)

        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uTexelSize"),
            1f / decoded.width, 1f / decoded.height
        )
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uExposure"), editState.exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uContrast"), editState.contrast)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uTemperature"), editState.temperature)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uTint"), editState.tint)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uHighlights"), editState.highlights)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uShadows"), editState.shadows)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSaturation"), editState.saturation)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uVibrance"), editState.vibrance)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSharpen"), editState.sharpen)

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val rawPixels = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
            .order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(
            0, 0, outputWidth, outputHeight,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rawPixels
        )

        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteTextures(1, textureId, 0)
        GLES30.glDeleteProgram(program)

        // glReadPixels는 원점이 좌하단이라 위아래가 뒤집혀 있다 - Bitmap이 기대하는
        // 위->아래 행 순서로 재배열한다.
        return flipRowsVertically(rawPixels, outputWidth, outputHeight)
    }

    private fun flipRowsVertically(src: ByteBuffer, width: Int, height: Int): ByteBuffer {
        val rowBytes = width * 4
        val dst = ByteBuffer.allocateDirect(src.capacity()).order(ByteOrder.nativeOrder())
        val srcArray = ByteArray(src.capacity())
        src.rewind()
        src.get(srcArray)
        for (row in 0 until height) {
            val srcOffset = (height - 1 - row) * rowBytes
            dst.put(srcArray, srcOffset, rowBytes)
        }
        dst.rewind()
        return dst
    }

    private fun saveToMediaStore(context: Context, bitmap: Bitmap, sourceDisplayName: String) {
        val baseName = sourceDisplayName.substringBeforeLast('.').ifBlank { "rawlab" }
        val fileName = "${baseName}_edit_${System.currentTimeMillis()}.jpg"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RawLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "RawLab"
                ).apply { mkdirs() }
                put(MediaStore.Images.Media.DATA, File(dir, fileName).absolutePath)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert 실패")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: error("출력 스트림 열기 실패")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }
}
