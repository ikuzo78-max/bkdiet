# RawLab

안드로이드 네이티브 RAW 이미지 편집 앱 (v0.1, MVP).

Kotlin + Jetpack Compose UI, LibRaw(NDK/JNI)로 RAW 디코딩, OpenGL ES 3.0 셰이더로
실시간 프리뷰 편집(노출/대비/화이트밸런스/하이라이트-섀도우/채도-생동감/샤픈)과
크롭/회전, JPEG export를 지원합니다.

## 빌드 요구사항

- Android Studio (최신 안정 버전 권장, Ladybug 이상)
- JDK 17
- Android SDK: compileSdk/targetSdk 35, minSdk 26
- NDK 26 이상 + CMake 3.22.1 (Android Studio에서 `SDK Manager > SDK Tools`로 설치,
  또는 첫 Gradle sync 시 자동 설치 프롬프트가 뜹니다)
- 인터넷 연결 (최초 빌드 시 CMake가 LibRaw 0.21.2 소스를 GitHub에서 `FetchContent`로
  받아옵니다 — `app/src/main/cpp/CMakeLists.txt` 참고)

## 여는 법

`raw-editor/` 폴더를 Android Studio에서 "Open"으로 열면 됩니다. Gradle sync가
끝나면 NDK/CMake 빌드가 함께 진행되어 `libraw`(정적 라이브러리)와
`rawcore.so`(JNI 브릿지)가 생성됩니다.

## 아키텍처

```
app/src/main/
  cpp/
    CMakeLists.txt      # LibRaw FetchContent + rawcore.so 빌드
    raw_jni.cpp         # JNI: RAW 파일(fd) -> 8bit sRGB RGB 버퍼
  java/com/rawlab/editor/
    MainActivity.kt
    raw/RawDecoder.kt   # JNI 래퍼
    raw/DecodedRaw.kt   # 디코드 결과 (width, height, RGB8 pixels)
    raw/EditState.kt    # 비파괴 편집 파라미터
    gl/RawGLRenderer.kt # GLSurfaceView.Renderer, 실시간 프리뷰
    gl/ShaderUtils.kt   # 셰이더 컴파일/링크 공용 헬퍼
    ui/HomeScreen.kt    # SAF로 RAW 파일 선택
    ui/EditorScreen.kt  # 프리뷰 + 슬라이더 + 크롭(회전)/export
    ui/EditorViewModel.kt
    export/Exporter.kt  # 오프스크린(EGL Pbuffer) 풀해상도 렌더 -> JPEG -> MediaStore
  assets/shaders/
    adjust.vert / adjust.frag  # 프리뷰와 export가 공유하는 보정 파이프라인
```

셰이더 보정 순서: 화이트밸런스 → 노출(선형광 공간) → 하이라이트/섀도우 →
대비 → 채도/생동감 → 샤픈. 프리뷰와 export가 동일한 셰이더를 쓰므로
"화면에 보이는 대로 저장된다"가 보장됩니다.

## 알려진 제약 / v0.1 범위 밖 (로드맵)

- **이 환경(샌드박스)에서는 실제 빌드/실행 검증을 하지 못했습니다.** Android
  SDK/NDK와 Google Maven 접근이 막혀 있어 컴파일 자체가 불가능했습니다. 코드는
  꼼꼼히 리뷰했지만, 실제 확인은 Android Studio + 실기기/에뮬레이터에서
  RAW 파일을 열어보는 과정이 필요합니다.
- LibRaw는 `NO_JPEG`(손실 JPEG 압축 RAW 미지원), `NO_LCMS`(ICC 프로파일 미지원),
  `NO_JASPER`(JPEG2000 미지원) 옵션으로 빌드됩니다. 즉 **비압축/무손실압축 DNG**
  (안드로이드 `DngCreator`가 만드는 파일 포함) 및 대부분의 무손실 압축 CR2/NEF/ARW는
  잘 동작하지만, 손실 JPEG 압축을 쓰는 일부 CR2/CR3 등은 디코딩되지 않습니다.
  필요해지면 libjpeg-turbo를 NDK로 추가 빌드해 `NO_JPEG`를 해제하면 됩니다.
- 크롭은 데이터 모델(`EditState`)과 렌더링(셰이더 UV/export 캔버스 크기)까지는
  구현되어 있지만, 드래그로 크롭 영역을 지정하는 UI는 아직 없습니다(회전 버튼만 제공).
- 히스토그램, 커브 툴, 배치 처리, 16bit 선형 파이프라인, 필름 시뮬레이션 3D LUT,
  자동 테스트/CI는 v0.1 범위 밖입니다.

## 참고한 레퍼런스

[lim8701/FilmRawstery](https://github.com/lim8701/FilmRawstery) (데스크톱 후지필름
RAF 편집기)의 rawpy/LibRaw 디코딩 선택, 셰이더 보정 순서, "프리뷰=export 동일 수식",
JSON 사이드카 비파괴 편집 방식을 참고해 이 프로젝트 구조에 반영했습니다.
