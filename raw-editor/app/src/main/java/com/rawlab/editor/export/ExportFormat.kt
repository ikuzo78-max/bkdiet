package com.rawlab.editor.export

import android.graphics.Bitmap

enum class ExportFormat(val mimeType: String, val extension: String, val quality: Int) {
    JPEG("image/jpeg", "jpg", 95),
    PNG("image/png", "png", 100);

    val compressFormat: Bitmap.CompressFormat
        get() = when (this) {
            JPEG -> Bitmap.CompressFormat.JPEG
            PNG -> Bitmap.CompressFormat.PNG
        }
}
