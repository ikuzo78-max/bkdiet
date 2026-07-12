#include <jni.h>
#include <unistd.h>
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

} // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_rawlab_editor_raw_RawDecoder_decode(JNIEnv *env, jobject /*thiz*/, jint fd) {
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

    jbyteArray pixels = env->NewByteArray(static_cast<jsize>(image->data_size));
    env->SetByteArrayRegion(pixels, 0, static_cast<jsize>(image->data_size),
                            reinterpret_cast<jbyte *>(image->data));

    jint width = image->width;
    jint height = image->height;
    LibRaw::dcraw_clear_mem(image);

    jclass decodedRawClass = env->FindClass("com/rawlab/editor/raw/DecodedRaw");
    jmethodID ctor = env->GetMethodID(decodedRawClass, "<init>", "(II[B)V");
    return env->NewObject(decodedRawClass, ctor, width, height, pixels);
}
