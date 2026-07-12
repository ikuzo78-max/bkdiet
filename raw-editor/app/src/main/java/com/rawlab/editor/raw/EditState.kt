package com.rawlab.editor.raw

import kotlinx.serialization.Serializable

/**
 * 비파괴 편집 파라미터. 원본 RAW 파일 옆에 "<파일명>.rawlab.json" 사이드카로 저장한다.
 * 모든 슬라이더 값은 -1.0..1.0 범위 (0 = 무보정), rotationDegrees만 예외.
 */
@Serializable
data class EditState(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val sharpen: Float = 0f,
    val rotationDegrees: Int = 0,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    /**
     * 톤커브 조절점의 출력값(y, 0..1). x는 항상 0, 0.25, 0.5, 0.75, 1.0 고정.
     * 기본값(0,0.25,0.5,0.75,1.0)은 대각선(무보정)을 의미한다.
     */
    val curvePoints: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
)
