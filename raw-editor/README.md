# RawLab

안드로이드 네이티브 RAW 이미지 편집 앱 (v0.1, MVP).

Kotlin + Jetpack Compose UI, LibRaw(NDK/JNI)로 RAW 디코딩, OpenGL ES 3.0 셰이더로
실시간 프리뷰 편집(노출/대비/화이트밸런스/하이라이트-섀도우/채도-생동감/샤픈)과
크롭/회전, JPEG export를 지원합니다.

후지필름 GFX100RF(1억 화소 medium format, RAF)처럼 PC 없이 모바일에서만
RAW를 촬영/보정하려는 워크플로를 염두에 두고 설계했습니다 — 그래서 프리뷰와
export의 처리 경로를 의도적으로 분리했습니다 (아래 아키텍처 참고).

## 폰에서 바로 설치하기 (PC 없이)

이 저장소에는 GitHub Actions 워크플로(`.github/workflows/build-rawlab-apk.yml`)가 있어서
`claude/mobile-raw-image-editor-mjizrj` 브랜치에 코드가 푸시될 때마다 클라우드에서
자동으로 APK를 빌드하고, GitHub Releases의 `rawlab-debug` 태그에 최신 APK를 올려줍니다.

1. 폰 브라우저로 GitHub 저장소의 **Releases** 탭으로 이동 (`https://github.com/ikuzo78-max/bkdiet/releases`)
2. `RawLab 자동 빌드 (debug)` 릴리스에서 `app-debug.apk` 다운로드
3. 처음 설치할 때는 안드로이드가 "출처를 알 수 없는 앱" 설치를 막을 수 있습니다 —
   설정에서 해당 브라우저(또는 파일 관리자)에 "알 수 없는 앱 설치" 권한을 한 번
   허용해주면 됩니다
4. 다운로드한 APK를 열어 설치

빌드가 실패하면 저장소의 **Actions** 탭에서 로그를 확인할 수 있습니다. 코드가 바뀔
때마다 자동으로 다시 빌드되고, Actions 탭에서 `workflow_dispatch`로 수동 재실행도
가능합니다.

이 방식은 디버그 서명(자동 생성되는 디버그 키)만 사용하는 개인 테스트용 APK입니다 —
Play 스토어 배포용이 아닙니다.

## 빌드 요구사항 (직접 Android Studio로 빌드할 경우)

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
    raw_jni.cpp         # JNI: RAW 파일(fd) -> 8bit sRGB RGB 버퍼 (프록시/원본 해상도)
    raw_process.cpp     # JNI: 보정+톤커브+크롭+회전을 CPU에서 전체 해상도로 처리 (export/100%확인),
                        # 히스토그램 계산도 여기서 담당
  java/com/rawlab/editor/
    MainActivity.kt
    raw/RawDecoder.kt     # JNI 디코더 래퍼
    raw/DecodedRaw.kt     # 디코드 결과 (width, height, RGB8 pixels)
    raw/RawProcessor.kt   # JNI 전체해상도 보정/히스토그램 래퍼
    raw/ProcessedImage.kt # 보정 결과 (width, height, ARGB8888 pixels)
    raw/EditState.kt      # 비파괴 편집 파라미터 (톤커브/필름시뮬레이션 포함)
    raw/CurveLut.kt       # 톤커브 5점 -> 256단계 LUT (구간별 선형보간, GL 텍스처용)
    raw/FilmSimLut.kt     # .cube 파싱(+캐시), 필름 시뮬레이션 3D LUT
    gl/RawGLRenderer.kt   # GLSurfaceView.Renderer, 실시간 프리뷰(프록시 해상도)
    gl/ShaderUtils.kt     # 셰이더 컴파일/링크 공용 헬퍼
    ui/HomeScreen.kt       # SAF로 RAW 파일 선택
    ui/EditorScreen.kt     # 프리뷰 + 슬라이더 + 필름시뮬레이션 + 톤커브 + 히스토그램 +
                           # 100%확인 + 회전/저장설정/export
    ui/CurveEditor.kt      # 5점 드래그 톤커브 에디터 (Canvas)
    ui/HistogramView.kt    # RGB 히스토그램 오버레이 (Canvas)
    ui/EditorViewModel.kt  # 원본 Uri 보관, 프리뷰/export/100%확인 트리거
    export/Exporter.kt     # 원본 Uri 재디코드(전체 해상도) -> RawProcessor -> 지정 포맷/폴더로 저장
    export/ExportFormat.kt # JPEG/PNG 포맷 정의
    export/ExportPrefs.kt  # 저장 포맷/폴더 선택 기억 (SharedPreferences)
  assets/luts/            # 필름 시뮬레이션 .cube 11종 + LICENSE-LUTS.md
  assets/shaders/
    adjust.vert / adjust.frag  # 프리뷰 GPU 셰이더 (raw_process.cpp가 같은 공식을 CPU로 재현,
                                # 톤커브는 256x1 LUT 텍스처로 샘플링)
```

### 100% 확인(loupe)은 왜 전체 이미지를 안 보여주나

GFX100RF급 해상도를 통째로 Bitmap/이미지 뷰로 띄우면 export와 똑같이 기기 텍스처
크기 한계에 걸릴 수 있다. 그래서 "100%" 버튼은 전체 이미지를 원본 해상도로
보정까지 마친 뒤, 화면 중앙 1200x1200px 영역만 잘라서 보여준다(핀치 줌/팬 가능).
탭해서 보고 싶은 지점을 고르는 기능은 아직 없음 — 항상 중앙 기준.

### 왜 프리뷰와 export 경로가 다른가

GFX100RF는 약 1억 화소(11648×8736)입니다. 이 해상도를 그대로 GPU 텍스처로 올리면
RGB8 버퍼만 ~300MB고, 보급형/구형 기기의 `GL_MAX_TEXTURE_SIZE`(보통 4096~8192)를
넘어서 프리뷰 자체가 실패하거나 매우 느려질 수 있습니다. 그래서:

- **프리뷰**: `RawDecoder.decode(fd, maxDimensionPx=2048)` — LibRaw `half_size` +
  박스 다운샘플로 긴 변을 2048px 이하로 줄인 프록시를 GPU 셰이더로 실시간 편집.
- **export**: `RawDecoder.decode(fd, 0)`으로 원본 파일을 다시 전체 해상도로
  디코드하고, `RawProcessor.process(...)`가 **adjust.frag와 동일한 보정 공식을
  CPU/네이티브 코드로 재현**해서 크롭/회전까지 적용합니다. GPU를 거치지 않으므로
  기기의 텍스처 크기 제한과 무관하게 **항상 원본 해상도로 저장**됩니다(사용자가
  명시적으로 선택한 방식).
- 대가: 1억 화소급 export는 CPU 연산량과 메모리 사용량이 큽니다(중간 버퍼까지 합치면
  기기 메모리를 GB 단위로 사용할 수 있음). 저장 버튼을 누른 뒤 처리에 시간이 걸릴 수
  있고(수 초~수십 초, 기기 성능에 따라 다름), 저장 중에는 버튼이 "저장 중…"으로
  바뀝니다. 메모리가 부족한 저사양 기기에서는 export가 실패할 수 있습니다.

## 알려진 제약 / v0.1 범위 밖 (로드맵)

- **이 환경(샌드박스)에서는 실제 빌드/실행 검증을 하지 못했습니다.** Android
  SDK/NDK와 Google Maven 접근이 막혀 있어 컴파일 자체가 불가능했습니다. 코드는
  꼼꼼히 리뷰했지만, 실제 확인은 Android Studio + 실기기/에뮬레이터에서
  RAW 파일(가능하면 GFX100RF RAF로)을 열어보는 과정이 필요합니다. 특히
  raw_process.cpp의 전체 해상도 CPU 처리는 실기기에서 처리 시간/메모리를
  꼭 확인해봐야 합니다.
- LibRaw는 `NO_JPEG`(손실 JPEG 압축 RAW 미지원), `NO_LCMS`(ICC 프로파일 미지원),
  `NO_JASPER`(JPEG2000 미지원) 옵션으로 빌드됩니다. 즉 **비압축/무손실압축 RAF/DNG**
  는 잘 동작하지만, 손실 JPEG 압축을 쓰는 일부 RAW는 디코딩되지 않습니다.
  필요해지면 libjpeg-turbo를 NDK로 추가 빌드해 `NO_JPEG`를 해제하면 됩니다.
- 크롭은 데이터 모델(`EditState`)과 렌더링(프리뷰 셰이더 UV, export 캔버스 크기)까지는
  구현되어 있지만, 드래그로 크롭 영역을 지정하는 UI는 아직 없습니다(회전 버튼만 제공).
- 톤커브는 RGB 통합 커브(5점, 구간별 선형보간)만 지원 — R/G/B 개별 채널 커브는 아직 없음.
- 히스토그램은 프록시(축소) 버퍼 기준으로 계산 — 통계적으로는 충분히 대표성 있지만
  1px 단위 정밀도는 아님.
- 필름 시뮬레이션은 11종(Provia/Velvia/Astia/Classic Chrome/Classic Neg/Nostalgic Neg/
  Pro Neg Std·Hi/Eterna/Reala Ace/Bleach Bypass) 3D LUT를 지원한다. LUT 파일
  (`assets/luts/*.cube`)은 [abpy/FujifilmCameraProfiles](https://github.com/abpy/FujifilmCameraProfiles)
  에서 가져왔으며 **CC BY-NC-SA 4.0**(비영리 한정, 출처 표시 필수)이다 —
  자세한 조건은 `assets/luts/LICENSE-LUTS.md` 참고. RawLab을 상업적으로 배포할
  계획이 있다면 이 LUT들은 제외하거나 별도 라이선스를 확인해야 한다.
- AI 마스킹(하늘/인물 등 영역별 보정)은 사용자 요청으로 범위에서 제외했다.
- 저장 시 포맷(JPEG/PNG)과 저장 폴더(SAF `ACTION_OPEN_DOCUMENT_TREE`, 미선택 시
  기본값 `Pictures/RawLab`)를 고를 수 있다. 마지막 선택은 앱 재실행 후에도 유지된다
  (`ExportPrefs`, `SharedPreferences` 기반).
- 데이트 스탬프, 즐겨찾기/필터, 배치 처리, 16bit 선형 파이프라인,
  R/G/B 개별 채널 커브, 자동 테스트/CI는 아직 없음.

## 참고한 레퍼런스

- [lim8701/FilmRawstery](https://github.com/lim8701/FilmRawstery) (데스크톱 후지필름
  RAF 편집기)의 rawpy/LibRaw 디코딩 선택, 셰이더 보정 순서, "프리뷰=export 동일 수식",
  JSON 사이드카 비파괴 편집 방식을 참고해 이 프로젝트 구조에 반영했습니다.
- [abpy/FujifilmCameraProfiles](https://github.com/abpy/FujifilmCameraProfiles) —
  필름 시뮬레이션 3D LUT 출처 (CC BY-NC-SA 4.0, `assets/luts/LICENSE-LUTS.md` 참고).
