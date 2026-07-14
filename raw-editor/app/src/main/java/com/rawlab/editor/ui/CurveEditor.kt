package com.rawlab.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

/**
 * 5개 조절점(x=0/0.25/0.5/0.75/1 고정, y만 드래그로 조절)짜리 톤커브 에디터.
 * [points]는 y값(0..1) 5개, 대각선(0,0.25,0.5,0.75,1)이 무보정 상태.
 */
@Composable
fun CurveEditor(
    points: List<Float>,
    onPointsChange: (List<Float>) -> Unit,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFFFFC107),
) {
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    // pointerInput(Unit)의 제스처 코루틴은 최초 1회만 시작되고 리컴포지션마다 재시작되지
    // 않으므로, points/onPointsChange를 직접 참조하면 첫 드래그 이후에는 항상 "처음
    // 마운트됐을 때의" 값으로 고정되어(stale closure) 이전 드래그로 바뀐 값이 다음
    // 드래그에서 덮어써져 리셋되는 문제가 있었다. rememberUpdatedState로 항상 최신 값을
    // 참조하도록 고친다.
    val currentPoints by rememberUpdatedState(points)
    val currentOnPointsChange by rememberUpdatedState(onPointsChange)

    Canvas(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val xNorm = (offset.x / size.width).coerceIn(0f, 1f)
                        activeIndex = (xNorm * 4f).roundToInt().coerceIn(0, 4)
                    },
                    onDragEnd = { activeIndex = null },
                    onDragCancel = { activeIndex = null },
                ) { change, _ ->
                    change.consume()
                    val index = activeIndex ?: return@detectDragGestures
                    val yNorm = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    val updated = currentPoints.toMutableList()
                    updated[index] = yNorm.coerceIn(0f, 1f)
                    currentOnPointsChange(updated)
                }
            }
    ) {
        val w = size.width
        val h = size.height

        // 대각선 기준선 (무보정)
        drawLine(
            color = Color.White.copy(alpha = 0.25f),
            start = Offset(0f, h),
            end = Offset(w, 0f),
            strokeWidth = 2f,
        )

        // 커브 (구간별 선형)
        val curvePath = Path()
        points.forEachIndexed { i, y ->
            val x = w * (i / 4f)
            val py = h * (1f - y)
            if (i == 0) curvePath.moveTo(x, py) else curvePath.lineTo(x, py)
        }
        drawPath(
            curvePath,
            color = lineColor,
            style = Stroke(width = 5f, cap = StrokeCap.Round),
        )

        // 조절점
        points.forEachIndexed { i, y ->
            val x = w * (i / 4f)
            val py = h * (1f - y)
            drawCircle(color = lineColor, radius = 12f, center = Offset(x, py))
            drawCircle(color = Color.Black, radius = 5f, center = Offset(x, py))
        }
    }
}
