package com.rawlab.editor.raw

import android.content.Context
import android.net.Uri

/**
 * 파일 확장자로 RAW/JPEG을 구분해 적절한 디코더(LibRaw 또는 표준 BitmapFactory)로
 * 위임한다. RawDecoder(LibRaw)와 JpegDecoder 둘 다 DecodedRaw로 통일된 형식(RGB8)을
 * 반환하므로, 그 이후의 GPU 프리뷰/CPU export 파이프라인은 원본이 RAW든 JPEG이든
 * 신경 쓸 필요가 없다.
 */
object SourceImageDecoder {
    private val JPEG_EXTENSIONS = setOf("jpg", "jpeg")

    fun isJpeg(displayName: String): Boolean =
        displayName.substringAfterLast('.', "").lowercase() in JPEG_EXTENSIONS

    /**
     * [maxDimensionPx] > 0이면 그 값 이하로 축소한 프록시(프리뷰용), 0이면 원본 해상도
     * (export용)를 반환한다. 실패 시 null.
     */
    fun decode(context: Context, uri: Uri, displayName: String, maxDimensionPx: Int): DecodedRaw? {
        return if (isJpeg(displayName)) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            JpegDecoder.decode(bytes, maxDimensionPx)
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                RawDecoder.decode(pfd.fd, maxDimensionPx)
            }
        }
    }
}
