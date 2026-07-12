#include <jni.h>
#include <algorithm>
#include <cmath>
#include <utility>
#include <vector>
#include <android/log.h>

#define LOG_TAG "rawcore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// adjust.vert/adjust.frag(프리뷰 GPU 셰이더)와 동일한 보정 공식을 CPU에서 재현한다.
// export는 기기의 GPU 텍스처 크기 제한과 무관하게 항상 원본 해상도를 보장해야 하므로
// (GFX100RF 등 1억 화소급 카메라) GPU 대신 여기서 전체 해상도를 처리한다.
namespace {

inline float clamp01(float v) {
    return v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
}

inline float smoothstepf(float edge0, float edge1, float x) {
    float t = clamp01((x - edge0) / (edge1 - edge0));
    return t * t * (3.f - 2.f * t);
}

inline float luminance(float r, float g, float b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

// CurveLut.kt와 동일한 구간별 선형보간. x는 항상 0/0.25/0.5/0.75/1 고정.
struct CurveLut256 {
    float values[256];

    explicit CurveLut256(const float points[5]) {
        const float xs[5] = {0.f, 0.25f, 0.5f, 0.75f, 1.f};
        for (int i = 0; i < 256; ++i) {
            float x = i / 255.f;
            int seg = 3;
            for (int s = 0; s < 4; ++s) {
                if (x <= xs[s + 1]) {
                    seg = s;
                    break;
                }
            }
            float x0 = xs[seg], x1 = xs[seg + 1];
            float y0 = points[seg], y1 = points[seg + 1];
            float t = (x1 > x0) ? (x - x0) / (x1 - x0) : 0.f;
            values[i] = y0 + (y1 - y0) * t;
        }
    }

    inline float apply(float v) const {
        int idx = static_cast<int>(clamp01(v) * 255.f + 0.5f);
        return values[idx];
    }
};

// 셰이더의 1~6단계(화이트밸런스/노출/하이라이트-섀도우/대비/채도-생동감/톤커브)를 픽셀 하나에 적용.
void adjustPixel(float &r, float &g, float &b,
                  float tempShift, float tintShift, float evScale,
                  float highlights, float shadows, float contrast,
                  float saturation, float vibrance, const CurveLut256 &curve) {
    // 1) 화이트 밸런스
    r *= (1.f + tempShift);
    b *= (1.f - tempShift);
    g *= (1.f + tintShift * 0.5f);

    // 2) 노출 (선형광 공간)
    float lr = powf(std::max(r, 0.f), 2.2f) * evScale;
    float lg = powf(std::max(g, 0.f), 2.2f) * evScale;
    float lb = powf(std::max(b, 0.f), 2.2f) * evScale;
    r = lr > 0.f ? powf(lr, 1.f / 2.2f) : 0.f;
    g = lg > 0.f ? powf(lg, 1.f / 2.2f) : 0.f;
    b = lb > 0.f ? powf(lb, 1.f / 2.2f) : 0.f;

    // 3) 하이라이트 / 섀도우
    float lum = luminance(r, g, b);
    float highlightMask = smoothstepf(0.5f, 1.0f, lum);
    float shadowMask = 1.f - smoothstepf(0.f, 0.5f, lum);
    r += highlights * highlightMask * 0.5f * (1.f - r);
    g += highlights * highlightMask * 0.5f * (1.f - g);
    b += highlights * highlightMask * 0.5f * (1.f - b);
    r += shadows * shadowMask * 0.5f * r;
    g += shadows * shadowMask * 0.5f * g;
    b += shadows * shadowMask * 0.5f * b;

    // 4) 대비
    r = (r - 0.5f) * (1.f + contrast) + 0.5f;
    g = (g - 0.5f) * (1.f + contrast) + 0.5f;
    b = (b - 0.5f) * (1.f + contrast) + 0.5f;

    // 5) 채도 / 생동감
    float gray = luminance(r, g, b);
    r = gray + (r - gray) * (1.f + saturation);
    g = gray + (g - gray) * (1.f + saturation);
    b = gray + (b - gray) * (1.f + saturation);

    float maxC = std::max(r, std::max(g, b));
    float minC = std::min(r, std::min(g, b));
    float existingSat = maxC - minC;
    float gray2 = luminance(r, g, b);
    float vr = gray2 + (r - gray2) * (1.f + vibrance);
    float vg = gray2 + (g - gray2) * (1.f + vibrance);
    float vb = gray2 + (b - gray2) * (1.f + vibrance);
    r = r + (vr - r) * (1.f - existingSat);
    g = g + (vg - g) * (1.f - existingSat);
    b = b + (vb - b) * (1.f - existingSat);

    // 6) 톤커브
    r = curve.apply(r);
    g = curve.apply(g);
    b = curve.apply(b);
}

void readCurvePoints(JNIEnv *env, jfloatArray curvePointsIn, float out[5]) {
    jsize n = env->GetArrayLength(curvePointsIn);
    jfloat buf[5] = {0.f, 0.25f, 0.5f, 0.75f, 1.f};
    env->GetFloatArrayRegion(curvePointsIn, 0, std::min(n, static_cast<jsize>(5)), buf);
    for (int i = 0; i < 5; ++i) out[i] = buf[i];
}

} // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_rawlab_editor_raw_RawProcessor_process(
    JNIEnv *env, jobject /*thiz*/,
    jbyteArray pixelsIn, jint width, jint height,
    jfloat exposure, jfloat contrast, jfloat temperature, jfloat tint,
    jfloat highlights, jfloat shadows, jfloat saturation, jfloat vibrance, jfloat sharpen,
    jfloatArray curvePointsIn,
    jfloat cropLeft, jfloat cropTop, jfloat cropRight, jfloat cropBottom,
    jint rotationDegrees,
    jfloat patchCenterX, jfloat patchCenterY, jint patchSize) {

    jsize len = env->GetArrayLength(pixelsIn);
    std::vector<uint8_t> src(static_cast<size_t>(len));
    env->GetByteArrayRegion(pixelsIn, 0, len, reinterpret_cast<jbyte *>(src.data()));

    float curvePts[5];
    readCurvePoints(env, curvePointsIn, curvePts);
    CurveLut256 curve(curvePts);

    const float tempShift = temperature * 0.30f;
    const float tintShift = tint * 0.30f;
    const float evScale = powf(2.f, exposure * 3.f);

    const size_t pixelCount = static_cast<size_t>(width) * height;
    std::vector<float> adjusted(pixelCount * 3);

    for (size_t i = 0; i < pixelCount; ++i) {
        size_t idx = i * 3;
        float r = src[idx] / 255.f;
        float g = src[idx + 1] / 255.f;
        float b = src[idx + 2] / 255.f;
        adjustPixel(r, g, b, tempShift, tintShift, evScale, highlights, shadows,
                    contrast, saturation, vibrance, curve);
        adjusted[idx] = r;
        adjusted[idx + 1] = g;
        adjusted[idx + 2] = b;
    }

    // 7) 샤픈: 셰이더와 동일하게 "원본(src)" 이웃 4픽셀 평균 대비 언샵마스크를
    // 보정된(adjusted) 픽셀에 더한다.
    std::vector<uint8_t> finalPixels(pixelCount * 3);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            size_t idx = (static_cast<size_t>(y) * width + x) * 3;
            float r = adjusted[idx], g = adjusted[idx + 1], b = adjusted[idx + 2];

            if (sharpen > 0.f) {
                int xm = std::max(x - 1, 0);
                int xp = std::min(x + 1, width - 1);
                int ym = std::max(y - 1, 0);
                int yp = std::min(y + 1, height - 1);
                size_t iL = (static_cast<size_t>(y) * width + xm) * 3;
                size_t iR = (static_cast<size_t>(y) * width + xp) * 3;
                size_t iT = (static_cast<size_t>(ym) * width + x) * 3;
                size_t iB = (static_cast<size_t>(yp) * width + x) * 3;
                float br = (src[iL] + src[iR] + src[iT] + src[iB]) / (255.f * 4.f);
                float bg = (src[iL + 1] + src[iR + 1] + src[iT + 1] + src[iB + 1]) / (255.f * 4.f);
                float bb = (src[iL + 2] + src[iR + 2] + src[iT + 2] + src[iB + 2]) / (255.f * 4.f);
                r += (r - br) * sharpen * 1.5f;
                g += (g - bg) * sharpen * 1.5f;
                b += (b - bb) * sharpen * 1.5f;
            }

            finalPixels[idx] = static_cast<uint8_t>(clamp01(r) * 255.f + 0.5f);
            finalPixels[idx + 1] = static_cast<uint8_t>(clamp01(g) * 255.f + 0.5f);
            finalPixels[idx + 2] = static_cast<uint8_t>(clamp01(b) * 255.f + 0.5f);
        }
    }

    // 8) 크롭
    int cropLeftPx = static_cast<int>(cropLeft * width + 0.5f);
    int cropTopPx = static_cast<int>(cropTop * height + 0.5f);
    int cropRightPx = static_cast<int>(cropRight * width + 0.5f);
    int cropBottomPx = static_cast<int>(cropBottom * height + 0.5f);
    cropLeftPx = std::max(0, std::min(cropLeftPx, width - 1));
    cropTopPx = std::max(0, std::min(cropTopPx, height - 1));
    cropRightPx = std::max(cropLeftPx + 1, std::min(cropRightPx, width));
    cropBottomPx = std::max(cropTopPx + 1, std::min(cropBottomPx, height));
    int cropW = cropRightPx - cropLeftPx;
    int cropH = cropBottomPx - cropTopPx;

    std::vector<uint8_t> cropped(static_cast<size_t>(cropW) * cropH * 3);
    for (int y = 0; y < cropH; ++y) {
        size_t srcRow = (static_cast<size_t>(cropTopPx + y) * width + cropLeftPx) * 3;
        size_t dstRow = static_cast<size_t>(y) * cropW * 3;
        std::copy(finalPixels.begin() + srcRow, finalPixels.begin() + srcRow + cropW * 3,
                  cropped.begin() + dstRow);
    }

    // 9) 회전 (90도 단위만 지원)
    int rot = ((rotationDegrees % 360) + 360) % 360;
    std::vector<uint8_t> rotated;
    int outW, outH;
    if (rot == 90 || rot == 270) {
        outW = cropH;
        outH = cropW;
        rotated.resize(static_cast<size_t>(outW) * outH * 3);
        for (int y = 0; y < cropH; ++y) {
            for (int x = 0; x < cropW; ++x) {
                size_t srcIdx = (static_cast<size_t>(y) * cropW + x) * 3;
                // GL 프리뷰(Matrix.rotateM, +Z축 기준 양의 각도 = 반시계 방향 회전)와
                // 방향을 맞춰야 한다: rot=90은 반시계, rot=270(=-90)은 시계 방향.
                int dx, dy;
                if (rot == 90) {
                    dx = y;
                    dy = cropW - 1 - x;
                } else { // 270
                    dx = cropH - 1 - y;
                    dy = x;
                }
                size_t dstIdx = (static_cast<size_t>(dy) * outW + dx) * 3;
                rotated[dstIdx] = cropped[srcIdx];
                rotated[dstIdx + 1] = cropped[srcIdx + 1];
                rotated[dstIdx + 2] = cropped[srcIdx + 2];
            }
        }
    } else if (rot == 180) {
        outW = cropW;
        outH = cropH;
        rotated.resize(static_cast<size_t>(outW) * outH * 3);
        for (int y = 0; y < cropH; ++y) {
            for (int x = 0; x < cropW; ++x) {
                size_t srcIdx = (static_cast<size_t>(y) * cropW + x) * 3;
                size_t dstIdx = (static_cast<size_t>(cropH - 1 - y) * outW + (cropW - 1 - x)) * 3;
                rotated[dstIdx] = cropped[srcIdx];
                rotated[dstIdx + 1] = cropped[srcIdx + 1];
                rotated[dstIdx + 2] = cropped[srcIdx + 2];
            }
        }
    } else {
        outW = cropW;
        outH = cropH;
        rotated = std::move(cropped);
    }

    // 10) 패치 추출 (patchSize > 0일 때만) — "100% 확인" 기능용. 전체 해상도 이미지를
    // 그대로 Bitmap/GL 텍스처로 만들면 기기 텍스처 크기 한계에 걸릴 수 있으므로,
    // 중심점 주변의 작은 영역만 잘라 반환한다. export(patchSize<=0)는 전체를 반환.
    const uint8_t *finalBuf = rotated.data();
    int finalW = outW, finalH = outH;
    int outX = 0, outY = 0, outWidth = outW, outHeight = outH;
    if (patchSize > 0) {
        int cx = static_cast<int>(patchCenterX * outW);
        int cy = static_cast<int>(patchCenterY * outH);
        outWidth = std::min(patchSize, outW);
        outHeight = std::min(patchSize, outH);
        outX = std::max(0, std::min(cx - outWidth / 2, outW - outWidth));
        outY = std::max(0, std::min(cy - outHeight / 2, outH - outHeight));
    }

    const size_t outCount = static_cast<size_t>(outWidth) * outHeight;
    std::vector<jint> argb(outCount);
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            size_t srcIdx = (static_cast<size_t>(outY + y) * finalW + (outX + x)) * 3;
            size_t dstIdx = static_cast<size_t>(y) * outWidth + x;
            argb[dstIdx] = static_cast<jint>(0xFF000000u |
                                              (static_cast<uint32_t>(finalBuf[srcIdx]) << 16) |
                                              (static_cast<uint32_t>(finalBuf[srcIdx + 1]) << 8) |
                                              static_cast<uint32_t>(finalBuf[srcIdx + 2]));
        }
    }
    (void) finalH;

    jintArray argbArray = env->NewIntArray(static_cast<jsize>(outCount));
    env->SetIntArrayRegion(argbArray, 0, static_cast<jsize>(outCount), argb.data());

    jclass processedImageClass = env->FindClass("com/rawlab/editor/raw/ProcessedImage");
    jmethodID ctor = env->GetMethodID(processedImageClass, "<init>", "(II[I)V");
    return env->NewObject(processedImageClass, ctor, outWidth, outHeight, argbArray);
}

// RGB 히스토그램(256구간 x 3채널)을 크롭 영역 기준, 현재 보정치를 반영해 계산한다.
// 프록시(축소본) 버퍼에서 호출되는 것을 전제로 하며(실시간성 확보), 회전/샤픈은 통계에
// 영향이 없거나 미미해 계산에서 제외한다.
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_rawlab_editor_raw_RawProcessor_computeHistogram(
    JNIEnv *env, jobject /*thiz*/,
    jbyteArray pixelsIn, jint width, jint height,
    jfloat exposure, jfloat contrast, jfloat temperature, jfloat tint,
    jfloat highlights, jfloat shadows, jfloat saturation, jfloat vibrance,
    jfloatArray curvePointsIn,
    jfloat cropLeft, jfloat cropTop, jfloat cropRight, jfloat cropBottom) {

    jsize len = env->GetArrayLength(pixelsIn);
    std::vector<uint8_t> src(static_cast<size_t>(len));
    env->GetByteArrayRegion(pixelsIn, 0, len, reinterpret_cast<jbyte *>(src.data()));

    float curvePts[5];
    readCurvePoints(env, curvePointsIn, curvePts);
    CurveLut256 curve(curvePts);

    const float tempShift = temperature * 0.30f;
    const float tintShift = tint * 0.30f;
    const float evScale = powf(2.f, exposure * 3.f);

    int cropLeftPx = std::max(0, std::min(static_cast<int>(cropLeft * width + 0.5f), width - 1));
    int cropTopPx = std::max(0, std::min(static_cast<int>(cropTop * height + 0.5f), height - 1));
    int cropRightPx = std::max(cropLeftPx + 1, std::min(static_cast<int>(cropRight * width + 0.5f), width));
    int cropBottomPx = std::max(cropTopPx + 1, std::min(static_cast<int>(cropBottom * height + 0.5f), height));

    std::vector<jint> bins(768, 0); // [0..255]=R, [256..511]=G, [512..767]=B

    for (int y = cropTopPx; y < cropBottomPx; ++y) {
        for (int x = cropLeftPx; x < cropRightPx; ++x) {
            size_t idx = (static_cast<size_t>(y) * width + x) * 3;
            float r = src[idx] / 255.f;
            float g = src[idx + 1] / 255.f;
            float b = src[idx + 2] / 255.f;
            adjustPixel(r, g, b, tempShift, tintShift, evScale, highlights, shadows,
                        contrast, saturation, vibrance, curve);
            bins[static_cast<int>(clamp01(r) * 255.f + 0.5f)] += 1;
            bins[256 + static_cast<int>(clamp01(g) * 255.f + 0.5f)] += 1;
            bins[512 + static_cast<int>(clamp01(b) * 255.f + 0.5f)] += 1;
        }
    }

    jintArray result = env->NewIntArray(768);
    env->SetIntArrayRegion(result, 0, 768, bins.data());
    return result;
}
