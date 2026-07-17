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
    /** 부분 보정(그라디언트/방사형 마스크) 레이어. 최대 MAX_LOCAL_ADJUSTMENTS개. */
    val localAdjustments: List<LocalAdjustment> = emptyList(),
) {
    /** RawProcessor.process/computeHistogram의 curvePoints 인자 형식(마스터+R+G+B, 20개 float). */
    fun toCurveArray(): FloatArray =
        (curveMaster + curveRed + curveGreen + curveBlue).toFloatArray()

    /**
     * RawProcessor.process/computeHistogram의 localAdjustments 인자 형식.
     * 레이어마다 10개 float를 이어붙인다: [type(0=그라디언트,1=방사형), startX, startY,
     * endX, endY, invert(0/1), feather, exposure, contrast, saturation].
     * MAX_LOCAL_ADJUSTMENTS개까지만 반영된다(그 이상은 GPU/CPU 양쪽에서 무시).
     */
    fun toLocalAdjustmentArray(): FloatArray =
        localAdjustments.take(MAX_LOCAL_ADJUSTMENTS).flatMap { la ->
            listOf(
                if (la.type == LocalMaskType.RADIAL) 1f else 0f,
                la.startX, la.startY, la.endX, la.endY,
                if (la.invert) 1f else 0f,
                la.feather, la.exposure, la.contrast, la.saturation,
            )
        }.toFloatArray()

    companion object {
        /** GPU 셰이더(uLocalType 등 고정 크기 배열)와 CPU 네이티브 코드 양쪽의 상한과 일치해야 한다. */
        const val MAX_LOCAL_ADJUSTMENTS = 4
    }
}

enum class LocalMaskType { GRADIENT, RADIAL }

/**
 * 화면의 일부 영역에만 노출/대비/채도를 적용하는 부분 보정 레이어("range mask"의 실용적
 * 구현). 좌표는 크롭 영역 기준 0..1 정규화 값이며, 회전 전(=GPU의 vUv, CPU의 크롭 좌표계)
 * 기준이라 이미지를 회전해도 마스크가 사진 내용과 함께 회전한다.
 *
 * - GRADIENT: (startX,startY)=효과 0%인 지점, (endX,endY)=효과 100%인 지점 (그 사이는 선형 보간).
 * - RADIAL: (startX,startY)=타원 중심, (endX,endY)=타원의 반경(X,Y)을 각각 0..1로 표현.
 */
@Serializable
data class LocalAdjustment(
    val type: LocalMaskType,
    val startX: Float = 0.3f,
    val startY: Float = 0.3f,
    val endX: Float = 0.7f,
    val endY: Float = 0.7f,
    /** 마스크 안/밖을 반전할지 (방사형: 안쪽↔바깥쪽, 그라디언트: 시작↔끝). */
    val invert: Boolean = false,
    /** 마스크 경계의 부드러움. 방사형에만 적용되고 그라디언트는 시작~끝 거리 자체가 전환 폭이라 무시된다. */
    val feather: Float = 0.5f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
)
