package com.rawlab.editor.raw

/**
 * 전체 해상도 CPU/네이티브 보정 파이프라인.
 * gl/shaders/adjust.frag(프리뷰용 GPU 셰이더)와 동일한 공식을 재현하므로
 * "미리보기에 보이는 대로 저장된다"가 유지된다. GPU 텍스처 크기 제한과 무관하게
 * 항상 원본 해상도로 처리하기 위해 export 시에만 사용한다 (대형 센서/고화소 카메라 대응).
 */
object RawProcessor {
    init {
        System.loadLibrary("rawcore")
    }

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
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        rotationDegrees: Int,
    ): ProcessedImage?
}
