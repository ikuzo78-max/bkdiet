cmake_minimum_required(VERSION 3.22.1)
project(rawcore)

include(FetchContent)

# LibRaw는 CMake를 공식 제공하지 않으므로(autotools 기반) 소스만 받아서
# 우리가 필요한 파일들로 직접 static lib 타겟을 구성한다.
FetchContent_Declare(
    libraw_src
    GIT_REPOSITORY https://github.com/LibRaw/LibRaw.git
    GIT_TAG        0.21.2
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(libraw_src)

file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS
    "${libraw_src_SOURCE_DIR}/src/*.cpp"
)

add_library(libraw STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw PUBLIC
    "${libraw_src_SOURCE_DIR}"
)
target_compile_definitions(libraw PUBLIC
    LIBRAW_NOTHREADS
    NO_JASPER   # JPEG2000(Red/Kodak) 미지원 - 소수 포맷만 영향
    NO_JPEG     # 손실 JPEG 압축 RAW(일부 CR2/CR3) 미지원 - v0.2에서 libjpeg-turbo 추가 검토
    NO_LCMS     # ICC 프로파일 변환 미지원 - sRGB 매트릭스 변환으로 충분한 v0.1 범위
    USE_ZLIB    # DNG deflate 압축 지원 (NDK 내장 libz 사용)
)
target_link_libraries(libraw PUBLIC z)

add_library(rawcore SHARED raw_jni.cpp raw_process.cpp)
target_link_libraries(rawcore PRIVATE libraw log)
