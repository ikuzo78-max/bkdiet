package com.rawlab.editor.raw

import android.content.Context

/**
 * assets/luts/ 안의 .cube 파일(후지필름 필름 시뮬레이션, CC BY-NC-SA 4.0 —
 * assets/luts/LICENSE-LUTS.md 참고)을 파싱해 32x32x32 RGB LUT로 제공한다.
 *
 * .cube 파일의 데이터 순서(R이 가장 안쪽, G, B 순서로 바깥쪽)는 OpenGL의
 * GL_TEXTURE_3D가 기대하는 메모리 레이아웃(x=R 최우선, y=G, z=B)과 그대로
 * 일치하므로 별도 재배열 없이 바로 업로드/사용할 수 있다.
 */
object FilmSimLut {
    const val NONE = "none"
    const val LUT_SIZE = 32

    val NAMES = listOf(
        NONE, "provia", "velvia", "astia", "classic_chrome", "classic_neg",
        "nostalgic_neg", "pro_neg_std", "pro_neg_hi", "eterna", "reala_ace", "bleach_bypass",
    )

    fun displayName(name: String): String = when (name) {
        NONE -> "없음"
        "provia" -> "Provia"
        "velvia" -> "Velvia"
        "astia" -> "Astia"
        "classic_chrome" -> "Classic Chrome"
        "classic_neg" -> "Classic Neg"
        "nostalgic_neg" -> "Nostalgic Neg"
        "pro_neg_std" -> "Pro Neg Std"
        "pro_neg_hi" -> "Pro Neg Hi"
        "eterna" -> "Eterna"
        "reala_ace" -> "Reala Ace"
        "bleach_bypass" -> "Bleach Bypass"
        else -> name
    }

    private val cache = mutableMapOf<String, FloatArray>()

    /** [name]이 [NONE]이면 null. 그 외에는 32*32*32*3 크기 RGB(0..1) float 배열(캐시됨). */
    fun load(context: Context, name: String): FloatArray? {
        if (name == NONE) return null
        cache[name]?.let { return it }

        val data = FloatArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3)
        var index = 0
        context.assets.open("luts/$name.cube").bufferedReader().use { reader ->
            reader.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("LUT_3D_SIZE")) return@forEachLine
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3 || index + 3 > data.size) return@forEachLine
                data[index] = parts[0].toFloatOrNull() ?: 0f
                data[index + 1] = parts[1].toFloatOrNull() ?: 0f
                data[index + 2] = parts[2].toFloatOrNull() ?: 0f
                index += 3
            }
        }
        cache[name] = data
        return data
    }
}
