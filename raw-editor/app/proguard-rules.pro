# RawDecoder/RawProcessor JNI 메서드와 그 결과 클래스는 이름/생성자 시그니처로
# 네이티브에서 직접 호출/생성되므로 난독화 대상에서 제외한다.
-keep class com.rawlab.editor.raw.RawDecoder { *; }
-keep class com.rawlab.editor.raw.DecodedRaw { *; }
-keep class com.rawlab.editor.raw.RawProcessor { *; }
-keep class com.rawlab.editor.raw.ProcessedImage { *; }
