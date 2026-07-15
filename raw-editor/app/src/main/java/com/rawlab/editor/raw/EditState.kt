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
    /** 텍스처/클래리티 — 샤픈과 같은 언샵마스크 방식이지만 훨씬 넓은 반경의 블러를 기준으로 삼는다. */
    val clarity: Float = 0f,
    val rotationDegrees: Int = 0,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    /**
     * 톤커브 조절점의 출력값(y, 0..1) 4세트 — 마스터(RGB 통합) + 채널별(R/G/B).
     * x는 항상 0, 0.25, 0.5, 0.75, 1.0 고정. 기본값(0,0.25,0.5,0.75,1.0)은 대각선(무보정).
     * 최종 커브는 채널별(masterCurve(x))로, 마스터가 먼저 적용되고 그 결과에 채널별
     * 커브가 적용된다(라이트룸 포인트 커브와 동일한 합성 순서).
     */
    val curveMaster: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    val curveRed: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    val curveGreen: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    val curveBlue: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    /** FilmSimLut.NAMES 중 하나, "none"이면 미적용. */
    val filmSimulation: String = FilmSimLut.NONE,
    /** 필름 시뮬레이션 강도 0..1 (필름 미선택 시 무시됨). */
    val filmSimStrength: Float = 1f,
) {
    /** RawProcessor.process/computeHistogram의 curvePoints 인자 형식(마스터+R+G+B, 20개 float). */
    fun toCurveArray(): FloatArray =
        (curveMaster + curveRed + curveGreen + curveBlue).toFloatArray()
}
