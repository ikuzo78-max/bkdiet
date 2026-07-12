package com.rawlab.editor.raw

/** LibRaw(NDK) 기반 RAW 디코더 JNI 래퍼. */
object RawDecoder {
    init {
        System.loadLibrary("rawcore")
    }

    /**
     * [fd]가 가리키는 RAW 파일을 디코드한다. 실패 시 null.
     * fd의 소유권(open/close)은 호출측에 있다 — 네이티브 코드는 fd를 읽기만 하고 닫지 않는다.
     */
    external fun decode(fd: Int): DecodedRaw?
}
