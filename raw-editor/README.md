# RawLab

안드로이드 네이티브 RAW 이미지 편집 앱 (v0.1, MVP).

Kotlin + Jetpack Compose UI, LibRaw(NDK/JNI)로 RAW 디코딩(+ 안드로이드 표준
BitmapFactory로 JPEG도 디코딩), OpenGL ES 3.0 셰이더로 실시간 프리뷰 편집(노출/대비/
화이트밸런스/하이라이트-섀도우/채도-생동감/샤픈/텍스처-클래리티/부분 보정)과 크롭/회전,
JPEG export를 지원합니다.

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
    raw/RawDecoder.kt     # JNI 디코더 래퍼 (LibRaw, RAW 전용)
    raw/JpegDecoder.kt    # BitmapFactory 기반 JPEG 디코더 (EXIF Orientation 보정)
    raw/SourceImageDecoder.kt # 확장자로 RAW/JPEG 구분해 RawDecoder/JpegDecoder로 위임
    raw/DecodedRaw.kt     # 디코드 결과 (width, height, RGB8 pixels) — 출처가 RAW든
                          # JPEG이든 이 형식으로 통일되어 이후 파이프라인은 구분하지 않음
    raw/RawProcessor.kt   # JNI 전체해상도 보정/히스토그램 래퍼
    raw/ProcessedImage.kt # 보정 결과 (width, height, ARGB8888 pixels)
    raw/EditState.kt      # 비파괴 편집 파라미터 (톤커브/필름시뮬레이션 포함)
    raw/CurveLut.kt       # 톤커브 5점 -> 256단계 LUT (마스터+R/G/B 채널별, 구간별 선형보간, GL 텍스처용)
    raw/FilmSimLut.kt     # .cube 파싱(+캐시), 필름 시뮬레이션 3D LUT
    gl/RawGLRenderer.kt   # GLSurfaceView.Renderer, 실시간 프리뷰(프록시 해상도)
    gl/ShaderUtils.kt     # 셰이더 컴파일/링크 공용 헬퍼
    ui/HomeScreen.kt       # SAF로 RAW 파일 선택
    ui/EditorScreen.kt     # 프리뷰(핀치줌/드래그) + 슬라이더 + 필름시뮬레이션 + 톤커브 +
                           # 히스토그램 + 회전/저장설정/export
    ui/CurveEditor.kt      # 5점 드래그 톤커브 에디터 (Canvas, 채널별 색상 표시)
    ui/HistogramView.kt    # RGB 히스토그램 오버레이 (Canvas)
    ui/EditorViewModel.kt  # 원본 Uri 보관, 프리뷰/export 트리거
    export/Exporter.kt     # 원본 Uri 재디코드(전체 해상도) -> RawProcessor -> 지정 포맷/폴더로 저장
    export/ExportFormat.kt # JPEG/PNG 포맷 정의
    export/ExportPrefs.kt  # 저장 포맷/폴더 선택 기억 (SharedPreferences)
  assets/luts/            # 필름 시뮬레이션 .cube 11종 + LICENSE-LUTS.md
  assets/shaders/
    adjust.vert / adjust.frag  # 프리뷰 GPU 셰이더 (raw_process.cpp가 같은 공식을 CPU로 재현,
                                # 톤커브는 256x1 LUT 텍스처로 샘플링)
```

### 확대해서 디테일/포커스 확인하기

프리뷰 화면에서 핀치줌(최대 8배)과 드래그로 이동, 더블탭으로 초기화할 수 있다.
이미 화면에 올라간 프록시 텍스처(긴 변 최대 2048px)를 화면에서만 확대하는
것이라 원본을 다시 디코드하지 않는다 — GFX100RF(1억 화소) RAF를 열어도 안전하다.

초기 버전에는 원본 해상도를 다시 디코드해 중앙 패치만 잘라 보여주는 "100% 확인"
버튼이 따로 있었는데, 실기기(GFX100RF RAF)에서 재디코드 자체가 메모리를 많이
써서 OOM으로 죽는 문제가 있었다. 패치 영역만 처리하도록 최적화해봤지만 여전히
죽는 걸 확인했고(재디코드 시점 자체, 혹은 LibRaw 데모자이킹 내부 버퍼가 원인일
가능성이 큼), 사용자 피드백에 따라 이 기능은 제거하고 프리뷰 자체를 확대하는
방식으로 대체했다. 프록시 해상도(2048px) 한계 내에서만 확인 가능하다는 제약은
있지만 크래시 위험이 없다.

### 왜 프리뷰와 export 경로가 다른가

GFX100RF는 약 1억 화소(11648×8736)입니다. 이 해상도를 그대로 GPU 텍스처로 올리면
RGB8 버퍼만 ~300MB고, 보급형/구형 기기의 `GL_MAX_TEXTURE_SIZE`(보통 4096~8192)를
넘어서 프리뷰 자체가 실패하거나 매우 느려질 수 있습니다. 그래서:

- **프리뷰**: `SourceImageDecoder.decode(..., maxDimensionPx=2048)` — RAW는 LibRaw
  `half_size` + 박스 다운샘플로, JPEG은 `BitmapFactory`의 `inSampleSize`로 각각
  디코드 단계에서부터 긴 변을 2048px 이하로 줄인 프록시를 만들어 GPU 셰이더로
  실시간 편집.
- **export**: `SourceImageDecoder.decode(..., maxDimensionPx=0)`으로 원본 파일을
  다시 전체 해상도로 디코드하고, `RawProcessor.process(...)`가 **adjust.frag와 동일한 보정 공식을
  CPU/네이티브 코드로 재현**해서 크롭/회전까지 적용합니다. GPU를 거치지 않으므로
  기기의 텍스처 크기 제한과 무관하게 **항상 원본 해상도로 저장**됩니다(사용자가
  명시적으로 선택한 방식).
- 대가: 1억 화소급 export는 CPU 연산량과 메모리 사용량이 큽니다(중간 버퍼까지 합치면
  기기 메모리를 GB 단위로 사용할 수 있음). 저장 버튼을 누른 뒤 처리에 시간이 걸릴 수
  있고(수 초~수십 초, 기기 성능에 따라 다름), 저장 중에는 버튼이 "저장 중…"으로
  바뀝니다. 메모리가 부족한 저사양 기기에서는 export가 실패할 수 있습니다.

### JPEG도 편집할 수 있다

RAW 외에 이미 데모자이킹된 JPEG(.jpg/.jpeg) 파일도 열어서 똑같이 보정/저장할 수
있다. `SourceImageDecoder`가 파일 확장자로 RAW/JPEG을 구분해, RAW는 기존대로
LibRaw(JNI)로, JPEG은 안드로이드 표준 `BitmapFactory`로 디코드한 뒤 둘 다 동일한
`DecodedRaw`(RGB8) 형식으로 통일해서 돌려준다 — 그 다음 GPU 프리뷰/CPU export
파이프라인은 원본이 RAW든 JPEG이든 전혀 신경 쓰지 않는다.

- JPEG은 파일에 EXIF Orientation 태그로만 회전 정보가 기록되고 픽셀 자체는
  옆으로 누운 채 저장된 경우가 흔해서, `JpegDecoder`가 그 태그를 읽어 항상 올바른
  방향으로 보이도록 회전/반전을 적용한 뒤 돌려준다.
- 프리뷰는 `BitmapFactory`의 `inSampleSize`로 디코드 단계에서부터 축소해서 큰
  원본도 전체 해상도 비트맵을 만들지 않고 안전하게 처리한다. export(전체 해상도)는
  RAW export와 동일한 트레이드오프를 그대로 적용받는다 — 아주 큰 JPEG(예: 1억 화소급
  스캔/합성 이미지)이라면 마찬가지로 CPU/메모리 사용량이 커질 수 있다.
- `Bitmap.getPixels()`를 이미지 전체 한 번에 부르면 ARGB Bitmap(4바이트/px)에 더해
  같은 크기의 IntArray(4바이트/px)까지 동시에 떠 있게 되어, 요즘 폰 카메라가 흔히
  찍는 수십 MP급 JPEG만으로도 export가 OOM으로 실패하는 문제가 있었다. 지금은
  `JpegDecoder`가 몇백 줄 단위 스트립으로 나눠 작은 IntArray만 재사용하며 처리해
  이 문제를 없앴고, `AndroidManifest.xml`에 `android:largeHeap="true"`도 추가해
  여유를 더 뒀다.

### 텍스처/클래리티는 왜 넓은 반경 블러를 매 픽셀 계산하지 않는가

텍스처/클래리티는 샤픈과 같은 언샵마스크 방식이지만 훨씬 넓은 반경의 블러가
기준이 됩니다. 이 넓은 커널을 픽셀마다 직접 순회하면 GFX100RF의 1억 화소
export에서 노이즈 리덕션(bilateral 등)과 똑같은 시간/메모리 문제가 재발할
수 있어, 큰 커널을 흉내 내는 저비용 방식을 택했습니다.

- **프리뷰(GPU)**: 사진 텍스처에 밉맵(mipmap)을 미리 만들어두고, 셰이더에서
  `textureLod(uTexture, uv, 4.0)`로 레벨4(원본의 1/16 크기로 뭉친) 밉맵을 한 번
  샘플링합니다. 하드웨어가 만들어둔 밉체인을 조회만 하므로 큰 커널을 직접
  순회하는 것보다 훨씬 빠릅니다.
- **export(CPU)**: 원본을 1/16 크기로 박스다운샘플한 작은 버퍼(102MP 기준
  ~5MB)를 한 번 만들어두고, 본 픽셀 루프에서는 이 버퍼를 바일리니어로
  조회만 합니다 — 밉맵 샘플링과 같은 발상을 CPU에서 재현한 것입니다.
- 그 결과 텍스처/클래리티는 노이즈 리덕션과 달리 102MP에서도 기존 export
  파이프라인과 비슷한 시간/메모리로 처리됩니다(별도의 큰 중간 버퍼나 픽셀당
  큰 커널 순회가 없음).

### 부분 보정(그라디언트/방사형) — "range mask"의 실용적 구현

라이트룸의 Range Mask는 원래 브러시/그라디언트/방사형 같은 로컬 마스크를
밝기·색상 유사도로 한 번 더 좁히는 "정제" 기능이라, 그 자체만 따로 구현할 수는
없고 먼저 로컬 마스크가 있어야 의미가 있다. 그래서 이번에는 실제로 화면 일부만
골라 보정하는 기능인 **그라디언트(선형)/방사형 로컬 마스크**를 최대 4개 레이어까지
지원하는 형태로 구현했다(브러시로 자유롭게 칠하는 방식과, 마스크를 색/밝기
유사도로 정제하는 기능은 아직 없음 — 아래 로드맵 참고).

- 각 레이어는 노출/대비/채도 3가지만 조절 가능하고, 마스크 영역(그라디언트는
  시작~끝 지점, 방사형은 중심+반경+페더)은 프리뷰를 보면서 슬라이더로 조절한다.
  화면을 손가락으로 직접 드래그해서 위치/크기를 잡는 제스처는 아직 없다 — 이미
  프리뷰 확대/이동에 핀치줌·드래그 제스처를 쓰고 있어서, 제스처가 서로 충돌하지
  않도록 이번에는 슬라이더 방식으로 범위를 좁혀 구현했다.
- 마스크 계산이 좌표 하나당 거리/방향 공식 한 번(O(1))이라 별도의 큰 버퍼나
  무거운 순회가 없어, 텍스처/클래리티와 같은 이유로 102MP export에서도 안전하다.
  레이어를 여러 개 겹쳐도 그 계산이 레이어 수만큼 반복될 뿐 폭발적으로 늘지 않는다.
- GPU 프리뷰(`adjust.frag`)와 CPU export/히스토그램(`raw_process.cpp`) 양쪽에
  동일한 마스크 공식과 노출/대비/채도 공식을 재현해 "프리뷰에 보이는 대로
  저장된다"를 유지한다. 마스크 좌표는 크롭 영역 기준 0..1 정규화 값이라
  이미지를 회전해도 마스크가 사진 내용과 함께 회전한다.

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
  필요해지면 libjpeg-turbo를 NDK로 추가 빌드해 `NO_JPEG`를 해제하면 됩니다(이 제약은
  LibRaw의 RAW 디코딩 경로에만 해당하고, `.jpg`/`.jpeg` 파일 자체는 안드로이드 표준
  `BitmapFactory`로 디코드하므로 영향받지 않습니다).
- 크롭은 데이터 모델(`EditState`)과 렌더링(프리뷰 셰이더 UV, export 캔버스 크기)까지는
  구현되어 있지만, 드래그로 크롭 영역을 지정하는 UI는 아직 없습니다(회전 버튼만 제공).
- 톤커브는 마스터(RGB 통합) + R/G/B 개별 채널까지 4세트(각 5점, 구간별 선형보간) 지원.
  합성 순서는 마스터 커브 결과값에 채널별 커브를 적용하는 방식(라이트룸 포인트 커브와 동일).
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
- 데이트 스탬프, 즐겨찾기/필터, 배치 처리, 16bit 선형 파이프라인, 자동 테스트/CI는
  아직 없음.
- 디헤이즈, 노이즈 리덕션, EV 브라케팅 HDR 합성은 아직 없음 — 특히 노이즈
  리덕션(bilateral 등 엣지 보존 스무딩)은 102MP 원본에서 텍스처/클래리티처럼
  저비용 근사로 처리할지, 별도의 타일 기반 처리가 필요한지 검토가 더 필요해
  이번 범위에서 제외했다.
- 부분 보정은 그라디언트/방사형(최대 4레이어, 노출/대비/채도)만 지원한다.
  브러시(자유 드로잉) 마스크와, 마스크를 색상/밝기 유사도로 한 번 더 좁히는
  라이트룸 스타일의 Range Mask 정제 기능, 화면을 직접 드래그해 마스크 위치/
  크기를 잡는 제스처는 아직 없음.

## 참고한 레퍼런스

- [lim8701/FilmRawstery](https://github.com/lim8701/FilmRawstery) (데스크톱 후지필름
  RAF 편집기)의 rawpy/LibRaw 디코딩 선택, 셰이더 보정 순서, "프리뷰=export 동일 수식",
  JSON 사이드카 비파괴 편집 방식을 참고해 이 프로젝트 구조에 반영했습니다.
- [abpy/FujifilmCameraProfiles](https://github.com/abpy/FujifilmCameraProfiles) —
  필름 시뮬레이션 3D LUT 출처 (CC BY-NC-SA 4.0, `assets/luts/LICENSE-LUTS.md` 참고).
