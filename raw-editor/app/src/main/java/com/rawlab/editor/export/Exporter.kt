package com.rawlab.editor.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rawlab.editor.raw.EditState
import com.rawlab.editor.raw.RawDecoder
import com.rawlab.editor.raw.RawProcessor
import java.io.File

/**
 * [uri]의 RAW 파일을 원본 해상도로 다시 디코드하고, RawProcessor(네이티브 CPU)로
 * 프리뷰와 동일한 보정/크롭/회전을 전체 해상도에 적용한 뒤 JPEG으로 MediaStore에 저장한다.
 *
 * GPU 셰이더가 아니라 CPU/네이티브로 처리하는 이유: GFX100RF(1억 화소)처럼 기기의
 * GL_MAX_TEXTURE_SIZE를 넘어서는 대형 센서 RAW도 항상 원본 해상도 그대로 저장을
 * 보장하기 위함이다.
 */
object Exporter {

    fun export(context: Context, uri: Uri, editState: EditState, sourceDisplayName: String) {
        val decoded = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            RawDecoder.decode(pfd.fd, 0)
        } ?: error("전체 해상도 RAW 디코딩 실패")

        val processed = RawProcessor.process(
            decoded.pixels, decoded.width, decoded.height,
            editState.exposure, editState.contrast, editState.temperature, editState.tint,
            editState.highlights, editState.shadows, editState.saturation, editState.vibrance,
            editState.sharpen,
            editState.cropLeft, editState.cropTop, editState.cropRight, editState.cropBottom,
            editState.rotationDegrees,
        ) ?: error("이미지 보정 처리 실패")

        val bitmap = Bitmap.createBitmap(processed.argb, processed.width, processed.height, Bitmap.Config.ARGB_8888)
        saveToMediaStore(context, bitmap, sourceDisplayName)
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
