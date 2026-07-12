#include <jni.h>
#include <unistd.h>
#include <algorithm>
#include <utility>
#include <vector>
#include <android/log.h>
#include <libraw/libraw.h>

#define LOG_TAG "rawcore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// fd 전체를 메모리로 읽어들인다. fd는 호출측(Kotlin)이 열고 닫는다.
bool readAll(int fd, std::vector<uint8_t> &out) {
    off_t size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        LOGE("invalid file size");
        return false;
    }
    lseek(fd, 0, SEEK_SET);

    out.resize(static_cast<size_t>(size));
    size_t total = 0;
    while (total < out.size()) {
        ssize_t n = read(fd, out.data() + total, out.size() - total);
        if (n <= 0) {
            LOGE("read() failed at offset %zu", total);
            return false;
        }
        total += static_cast<size_t>(n);
    }
    return true;
}

// 정수 배율(factor)만큼 박스 평균으로 축소한다. GFX100RF(1억 화소) 같은 대형 센서를
// 프리뷰용으로 안전한 크기까지 줄이기 위함 (GPU 텍스처 최대 크기, 메모리 제한 회피).
void downsampleBox(const std::vector<uint8_t> &src, int srcW, int srcH,
                    std::vector<uint8_t> &dst, int &dstW, int &dstH, int factor) {
    dstW = srcW / factor;
    dstH = srcH / factor;
    dst.resize(static_cast<size_t>(dstW) * dstH * 3);

    for (int y = 0; y < dstH; ++y) {
        for (int x = 0; x < dstW; ++x) {
            int rSum = 0, gSum = 0, bSum = 0;
            for (int dy = 0; dy < factor; ++dy) {
                for (int dx = 0; dx < factor; ++dx) {
                    size_t srcIdx = (static_cast<size_t>(y * factor + dy) * srcW +
                                      (x * factor + dx)) * 3;
                    rSum += src[srcIdx];
                    gSum += src[srcIdx + 1];
                    bSum += src[srcIdx + 2];
                }
            }
            int count = factor * factor;
            size_t dstIdx = (static_cast<size_t>(y) * dstW + x) * 3;
            dst[dstIdx] = static_cast<uint8_t>(rSum / count);
            dst[dstIdx + 1] = static_cast<uint8_t>(gSum / count);
            dst[dstIdx + 2] = static_cast<uint8_t>(bSum / count);
        }
    }
}

} // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_rawlab_editor_raw_RawDecoder_decode(JNIEnv *env, jobject /*thiz*/, jint fd,
                                              jint maxDimension) {
    std::vector<uint8_t> buffer;
    if (!readAll(fd, buffer)) {
        return nullptr;
    }

    LibRaw processor;
    processor.imgdata.params.use_camera_wb = 1;
    processor.imgdata.params.no_auto_bright = 1;
    processor.imgdata.params.output_color = 1;   // sRGB
    processor.imgdata.params.output_bps = 8;      // 8bit/채널 (v0.1 범위)
    processor.imgdata.params.user_qual = 3;       // AHD 디모자이킹
    // 프리뷰 요청(maxDimension > 0)이면 LibRaw 자체 half-size 디코드로 4배 더 빠르고
    // 가볍게 뽑는다. 전체 해상도가 필요한 export는 maxDimension == 0으로 호출한다.
    processor.imgdata.params.half_size = (maxDimension > 0) ? 1 : 0;

    int err = processor.open_buffer(buffer.data(), buffer.size());
    if (err != LIBRAW_SUCCESS) {
        LOGE("open_buffer failed: %s", libraw_strerror(err));
        return nullptr;
    }
    if ((err = processor.unpack()) != LIBRAW_SUCCESS) {
        LOGE("unpack failed: %s", libraw_strerror(err));
        return nullptr;
    }
    if ((err = processor.dcraw_process()) != LIBRAW_SUCCESS) {
        LOGE("dcraw_process failed: %s", libraw_strerror(err));
        return nullptr;
    }

    libraw_processed_image_t *image = processor.dcraw_make_mem_image(&err);
    if (image == nullptr) {
        LOGE("dcraw_make_mem_image failed: %s", libraw_strerror(err));
        return nullptr;
    }
    if (image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3 || image->bits != 8) {
        LOGE("unexpected image format: type=%d colors=%d bits=%d",
             image->type, image->colors, image->bits);
        LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }

    std::vector<uint8_t> pixels(image->data, image->data + image->data_size);
    int width = image->width;
    int height = image->height;
    LibRaw::dcraw_clear_mem(image);
    processor.recycle();

    if (maxDimension > 0) {
        int longSide = std::max(width, height);
        int factor = (longSide + maxDimension - 1) / maxDimension; // ceil
        if (factor > 1) {
            std::vector<uint8_t> downsampled;
            int newW, newH;
            downsampleBox(pixels, width, height, downsampled, newW, newH, factor);
            pixels = std::move(downsampled);
            width = newW;
            height = newH;
        }
    }

    jbyteArray pixelArray = env->NewByteArray(static_cast<jsize>(pixels.size()));
    env->SetByteArrayRegion(pixelArray, 0, static_cast<jsize>(pixels.size()),
                            reinterpret_cast<jbyte *>(pixels.data()));

    jclass decodedRawClass = env->FindClass("com/rawlab/editor/raw/DecodedRaw");
    jmethodID ctor = env->GetMethodID(decodedRawClass, "<init>", "(II[B)V");
    return env->NewObject(decodedRawClass, ctor, width, height, pixelArray);
}
