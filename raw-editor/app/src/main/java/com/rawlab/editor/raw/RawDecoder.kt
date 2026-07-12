package com.rawlab.editor.raw

/** LibRaw(NDK) 기반 RAW 디코더 JNI 래퍼. */
object RawDecoder {
    init {
        System.loadLibrary("rawcore")
    }

    /**
     * [fd]가 가리키는 RAW 파일을 디코드한다. 실패 시 null.
     * fd의 소유권(open/close)은 호출측에 있다 — 네이티브 코드는 fd를 읽기만 하고 닫지 않는다.
     *
     * [maxDimensionPx]가 0보다 크면 LibRaw half-size + 박스 다운샘플로 긴 변을 그 값 이하로
     * 줄인 프록시 이미지를 반환한다(프리뷰용, 대형 센서에서도 빠르고 가벼움).
     * 0이면 원본 해상도 그대로 디코드한다(export용).
     */
    external fun decode(fd: Int, maxDimensionPx: Int): DecodedRaw?
}
