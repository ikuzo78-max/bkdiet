package com.rawlab.editor.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.rawlab.editor.raw.EditState
import com.rawlab.editor.raw.FilmSimLut
import com.rawlab.editor.raw.RawDecoder
import com.rawlab.editor.raw.RawProcessor
import java.io.File

/**
 * [uri]의 RAW 파일을 원본 해상도로 다시 디코드하고, RawProcessor(네이티브 CPU)로
 * 프리뷰와 동일한 보정/크롭/회전을 전체 해상도에 적용한 뒤 지정 포맷으로 저장한다.
 *
 * GPU 셰이더가 아니라 CPU/네이티브로 처리하는 이유: GFX100RF(1억 화소)처럼 기기의
 * GL_MAX_TEXTURE_SIZE를 넘어서는 대형 센서 RAW도 항상 원본 해상도 그대로 저장을
 * 보장하기 위함이다.
 *
 * [destinationTree]가 null이면 MediaStore(Pictures/RawLab)에 저장하고, 아니면
 * 사용자가 SAF로 고른 폴더(destinationTree)에 저장한다. 반환값은 저장 위치를
 * 사용자에게 보여주기 위한 짧은 설명 문자열.
 */
object Exporter {

    fun export(
        context: Context,
        uri: Uri,
        editState: EditState,
        sourceDisplayName: String,
        format: ExportFormat,
        destinationTree: Uri?,
    ): String {
        val decoded = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            RawDecoder.decode(pfd.fd, 0)
        } ?: error("전체 해상도 RAW 디코딩 실패")

        val filmLut = FilmSimLut.load(context, editState.filmSimulation) ?: FloatArray(0)
        val processed = RawProcessor.process(
            decoded.pixels, decoded.width, decoded.height,
            editState.exposure, editState.contrast, editState.temperature, editState.tint,
            editState.highlights, editState.shadows, editState.saturation, editState.vibrance,
            editState.sharpen,
            editState.curvePoints.toFloatArray(),
            filmLut, FilmSimLut.LUT_SIZE, editState.filmSimStrength,
            editState.cropLeft, editState.cropTop, editState.cropRight, editState.cropBottom,
            editState.rotationDegrees,
        ) ?: error("이미지 보정 처리 실패")

        val bitmap = Bitmap.createBitmap(processed.argb, processed.width, processed.height, Bitmap.Config.ARGB_8888)
        val baseName = sourceDisplayName.substringBeforeLast('.').ifBlank { "rawlab" }
        val fileName = "${baseName}_edit_${System.currentTimeMillis()}.${format.extension}"

        return if (destinationTree != null) {
            saveToTree(context, bitmap, destinationTree, fileName, format)
        } else {
            saveToMediaStore(context, bitmap, fileName, format)
        }
    }

    private fun saveToTree(
        context: Context,
        bitmap: Bitmap,
        treeUri: Uri,
        fileName: String,
        format: ExportFormat,
    ): String {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: error("폴더 접근 실패")
        val newFile = treeDoc.createFile(format.mimeType, fileName) ?: error("파일 생성 실패")
        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
            bitmap.compress(format.compressFormat, format.quality, out)
        } ?: error("출력 스트림 열기 실패")
        return treeDoc.name ?: "선택한 폴더"
    }

    private fun saveToMediaStore(context: Context, bitmap: Bitmap, fileName: String, format: ExportFormat): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
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
            bitmap.compress(format.compressFormat, format.quality, out)
        } ?: error("출력 스트림 열기 실패")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return "Pictures/RawLab"
    }
}
