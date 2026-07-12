package com.rawlab.editor.raw

/**
 * EditState.curvePoints(5개, x=0/0.25/0.5/0.75/1.0 고정, y=0..1)를 256단계 LUT로 변환한다.
 * 구간별 선형보간(piecewise-linear)만 사용 — 스플라인 오버슈트 없이 항상 예측 가능한 결과.
 * raw_process.cpp(export)도 동일한 보간 방식을 C++로 재현하므로 두 결과가 일치한다.
 */
object CurveLut {
    private val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    /** 0..255 각 입력값에 대응하는 출력값(0..255)을 담은 256바이트 LUT. */
    fun build256(points: List<Float>): ByteArray {
        val lut = ByteArray(256)
        for (i in 0 until 256) {
            val x = i / 255f
            val y = evaluate(points, x)
            lut[i] = (y.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return lut
    }

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
