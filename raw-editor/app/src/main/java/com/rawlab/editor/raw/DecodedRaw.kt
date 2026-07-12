package com.rawlab.editor.raw

/**
 * LibRaw로 디코드된 8bit sRGB 비트맵.
 * [pixels] 크기는 width * height * 3 (RGB, row-major, padding 없음).
 *
 * JNI(raw_jni.cpp)가 리플렉션으로 이 생성자를 직접 호출하므로 시그니처를 바꾸면
 * 네이티브 쪽도 함께 수정해야 한다.
 */
class DecodedRaw(val width: Int, val height: Int, val pixels: ByteArray)
