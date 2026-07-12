package com.rawlab.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/** RawProcessor.computeHistogram() 결과(R[0..255], G[256..511], B[512..767])를 그린다. */
@Composable
fun HistogramView(bins: IntArray?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color.Black.copy(alpha = 0.35f))) {
        if (bins == null || bins.size < 768) return@Canvas
        val maxCount = (0 until 768).maxOf { bins[it] }.coerceAtLeast(1)
        drawChannel(bins, 0, maxCount, Color(0xFFFF5252))
        drawChannel(bins, 256, maxCount, Color(0xFF4CAF50))
        drawChannel(bins, 512, maxCount, Color(0xFF448AFF))
    }
}

private fun DrawScope.drawChannel(bins: IntArray, offset: Int, maxCount: Int, color: Color) {
    val path = Path()
    val stepX = size.width / 255f
    path.moveTo(0f, size.height)
    for (i in 0 until 256) {
        val ratio = bins[offset + i].toFloat() / maxCount
        val y = size.height - (ratio * size.height)
        path.lineTo(i * stepX, y)
    }
    path.lineTo(size.width, size.height)
    path.close()
    drawPath(path, color = color.copy(alpha = 0.55f))
}
