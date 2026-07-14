package com.rawlab.editor.raw

/**
 * EditState의 톤커브 4세트(마스터 + R/G/B, 각 5점 x=0/0.25/0.5/0.75/1.0 고정, y=0..1)를
 * 256단계 LUT로 변환한다. 구간별 선형보간(piecewise-linear)만 사용 — 스플라인 오버슈트
 * 없이 항상 예측 가능한 결과. raw_process.cpp(export)도 동일한 보간/합성 방식을 C++로
 * 재현하므로 두 결과가 일치한다.
 */
object CurveLut {
    private val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    /** 단일 커브(5점)를 0..255 -> 0..255 256바이트 LUT로 변환. */
    fun build256(points: List<Float>): ByteArray {
        val lut = ByteArray(256)
        for (i in 0 until 256) {
            lut[i] = toByte(evaluate(points, i / 255f))
        }
        return lut
    }

    /**
     * 마스터 커브를 먼저 적용한 뒤 채널별 커브를 적용한 결과를 RGB8 256x1 텍스처
     * 바이트(R=red채널결과, G=green채널결과, B=blue채널결과)로 합성한다.
     */
    fun buildCombined256(
        master: List<Float>,
        red: List<Float>,
        green: List<Float>,
        blue: List<Float>,
    ): ByteArray {
        val bytes = ByteArray(256 * 3)
        for (i in 0 until 256) {
            val x = i / 255f
            val m = evaluate(master, x)
            bytes[i * 3] = toByte(evaluate(red, m))
            bytes[i * 3 + 1] = toByte(evaluate(green, m))
            bytes[i * 3 + 2] = toByte(evaluate(blue, m))
        }
        return bytes
    }

    private fun toByte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    private fun evaluate(points: List<Float>, x: Float): Float {
        var seg = xs.size - 2
        for (i in 0 until xs.size - 1) {
            if (x <= xs[i + 1]) {
                seg = i
                break
            }
        }
        val x0 = xs[seg]
        val x1 = xs[seg + 1]
        val y0 = points[seg]
        val y1 = points[seg + 1]
        val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0f
        return y0 + (y1 - y0) * t
    }
}
