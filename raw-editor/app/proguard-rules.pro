# RawDecoder JNI 메서드는 이름으로 네이티브에서 호출되므로 난독화 대상에서 제외한다.
-keep class com.rawlab.editor.raw.RawDecoder { *; }
-keep class com.rawlab.editor.raw.DecodedRaw { *; }
