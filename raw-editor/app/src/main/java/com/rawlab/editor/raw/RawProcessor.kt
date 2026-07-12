package com.rawlab.editor.raw

/**
 * 전체 해상도 CPU/네이티브 보정 파이프라인.
 * gl/shaders/adjust.frag(프리뷰용 GPU 셰이더)와 동일한 공식을 재현하므로
 * "미리보기에 보이는 대로 저장된다"가 유지된다. GPU 텍스처 크기 제한과 무관하게
 * 항상 원본 해상도로 처리하기 위해 export/100%확인 시에만 사용한다.
 */
object RawProcessor {
    init {
        System.loadLibrary("rawcore")
    }

    /**
     * [patchSize]가 0 이하이면 크롭/회전이 반영된 전체 이미지를 반환한다(export용).
     * [patchSize] > 0이면 (patchCenterX, patchCenterY)(0..1, 크롭+회전 이후 좌표 기준)를
     * 중심으로 한 patchSize x patchSize 영역만 잘라 반환한다("100% 확인"용 — 기기 텍스처
     * 크기 한계와 무관하게 원본 화소를 그대로 볼 수 있음).
     */
    external fun process(
        pixels: ByteArray,
        width: Int,
        height: Int,
        exposure: Float,
        contrast: Float,
        temperature: Float,
        tint: Float,
        highlights: Float,
        shadows: Float,
        saturation: Float,
        vibrance: Float,
        sharpen: Float,
        curvePoints: FloatArray,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        rotationDegrees: Int,
        patchCenterX: Float = 0.5f,
        patchCenterY: Float = 0.5f,
        patchSize: Int = 0,
    ): ProcessedImage?

    /** 크롭 영역 기준 RGB 히스토그램. 반환값은 크기 768(IntArray): R[0..255], G[256..511], B[512..767]. */
    external fun computeHistogram(
        pixels: ByteArray,
        width: Int,
        height: Int,
        exposure: Float,
        contrast: Float,
        temperature: Float,
        tint: Float,
        highlights: Float,
        shadows: Float,
        saturation: Float,
        vibrance: Float,
        curvePoints: FloatArray,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
    ): IntArray?
}
