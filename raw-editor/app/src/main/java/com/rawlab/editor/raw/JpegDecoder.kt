package com.rawlab.editor.raw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * JPEG처럼 이미 데모자이킹된 8bit 이미지를 LibRaw 없이 안드로이드 표준 BitmapFactory로
 * 디코드해 DecodedRaw와 동일한 RGB8 포맷으로 변환한다. EXIF Orientation을 반영해 항상
 * 올바른 방향으로 보이도록 회전/반전한다(RAW와 달리 JPEG은 회전이 파일 방향이 아니라
 * 태그로만 기록되는 경우가 흔함).
 */
object JpegDecoder {

    /**
     * [bytes]는 JPEG 파일 전체 내용. [maxDimensionPx] > 0이면 BitmapFactory의
     * inSampleSize로 디코드 단계에서부터 그 값 이하로 축소한 프록시를 반환한다(프리뷰용,
     * 큰 원본도 전체 해상도 비트맵을 만들지 않고 안전하게 처리). 0이면 원본 해상도
     * 그대로 디코드한다(export용).
     */
    fun decode(bytes: ByteArray, maxDimensionPx: Int): DecodedRaw? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val sampleSize = if (maxDimensionPx > 0) {
            val longestSide = maxOf(rawWidth, rawHeight)
            var sample = 1
            while (longestSide / (sample * 2) >= maxDimensionPx) sample *= 2
            sample
        } else {
            1
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null

        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }

        // inSampleSize는 2의 거듭제곱 단위만 지원해 목표보다 살짝 크게 나올 수 있으므로,
        // 필요하면 한 번 더 정확히 축소한다.
        if (maxDimensionPx > 0) {
            val longestSide = maxOf(bitmap.width, bitmap.height)
            if (longestSide > maxDimensionPx) {
                val scale = maxDimensionPx.toFloat() / longestSide
                val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaled != bitmap) bitmap.recycle()
                bitmap = scaled
            }
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val rgb = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val o = i * 3
            rgb[o] = ((p shr 16) and 0xFF).toByte()
            rgb[o + 1] = ((p shr 8) and 0xFF).toByte()
            rgb[o + 2] = (p and 0xFF).toByte()
        }
        return DecodedRaw(width, height, rgb)
    }
}
