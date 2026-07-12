package com.rawlab.editor.raw

/**
 * RawProcessor(네이티브)가 보정/크롭/회전까지 전체 해상도로 적용한 최종 결과.
 * [argb] 크기는 width * height, 각 원소는 0xAARRGGBB (Bitmap.createBitmap(int[], ...) 포맷).
 *
 * JNI(raw_process.cpp)가 리플렉션으로 이 생성자를 직접 호출하므로 시그니처를 바꾸면
 * 네이티브 쪽도 함께 수정해야 한다.
 */
class ProcessedImage(val width: Int, val height: Int, val argb: IntArray)
