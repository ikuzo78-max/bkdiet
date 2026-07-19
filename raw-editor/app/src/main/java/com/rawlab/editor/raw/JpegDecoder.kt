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

    /** getPixels() 스트립 하나당 대략 이 정도 바이트 예산으로 IntArray 크기를 정한다. */
    private const val STRIP_BUDGET_BYTES = 8 * 1024 * 1024

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
        val rgb = ByteArray(width * height * 3)

        // bitmap.getPixels()를 이미지 전체 한 번에 부르면 ARGB Bitmap(4바이트/px)에 더해
        // 같은 크기의 IntArray(4바이트/px)까지 동시에 떠 있게 되어, 큰 사진(요즘 폰
        // 카메라는 수십 MP도 흔함)에서 export 시 OOM으로 저장이 실패할 수 있었다.
        // 몇백 줄 단위 스트립으로 나눠 작은 IntArray만 재사용하면서 처리한다.
        val stripRows = (STRIP_BUDGET_BYTES / (width * 4)).coerceIn(1, height)
        val stripBuffer = IntArray(width * stripRows)
        var y = 0
        var outIdx = 0
        while (y < height) {
            val rows = minOf(stripRows, height - y)
            bitmap.getPixels(stripBuffer, 0, width, 0, y, width, rows)
            val count = width * rows
            for (i in 0 until count) {
                val p = stripBuffer[i]
                rgb[outIdx] = ((p shr 16) and 0xFF).toByte()
                rgb[outIdx + 1] = ((p shr 8) and 0xFF).toByte()
                rgb[outIdx + 2] = (p and 0xFF).toByte()
                outIdx += 3
            }
            y += rows
        }
        bitmap.recycle()
        return DecodedRaw(width, height, rgb)
    }
}
