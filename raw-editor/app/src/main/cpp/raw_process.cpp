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

// CurveLut.kt의 evaluate()와 동일한 구간별 선형보간. x는 항상 0/0.25/0.5/0.75/1 고정.
inline float evalCurve5(const float points[5], float x) {
    const float xs[5] = {0.f, 0.25f, 0.5f, 0.75f, 1.f};
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
    return y0 + (y1 - y0) * t;
}

// CurveLut.kt의 buildCombined256()과 동일: 마스터 커브를 먼저 적용한 뒤 채널별 커브를
// 적용한 결과를 256단계로 미리 계산해둔다.
struct ChannelCurveLut256 {
    float red[256], green[256], blue[256];

    ChannelCurveLut256(const float master[5], const float redPts[5],
                        const float greenPts[5], const float bluePts[5]) {
        for (int i = 0; i < 256; ++i) {
            float x = i / 255.f;
            float m = evalCurve5(master, x);
            red[i] = evalCurve5(redPts, m);
            green[i] = evalCurve5(greenPts, m);
            blue[i] = evalCurve5(bluePts, m);
        }
    }

    inline void apply(float &r, float &g, float &b) const {
        r = red[static_cast<int>(clamp01(r) * 255.f + 0.5f)];
        g = green[static_cast<int>(clamp01(g) * 255.f + 0.5f)];
        b = blue[static_cast<int>(clamp01(b) * 255.f + 0.5f)];
    }
};

constexpr int kMaxLocalAdjustments = 4;

// 부분 보정(그라디언트/방사형) 레이어 하나. adjust.frag의 uLocal* 유니폼 배열과 대응.
struct LocalAdjustment {
    bool isRadial = false;
    // 그라디언트: (startX,startY)=효과 0% 지점, (endX,endY)=효과 100% 지점.
    // 방사형: (startX,startY)=중심, (endX,endY)=반경(x,y).
    float startX = 0.f, startY = 0.f, endX = 0.f, endY = 0.f;
    bool invert = false;
    float feather = 0.5f;
    float exposure = 0.f, contrast = 0.f, saturation = 0.f;
};

// localAdjustmentsIn은 레이어당 10개 float(type,startX,startY,endX,endY,invert,feather,
// exposure,contrast,saturation)를 이어붙인 배열. EditState.toLocalAdjustmentArray() 참고.
// 최대 kMaxLocalAdjustments개까지만 읽는다(그 이상은 무시).
int readLocalAdjustments(JNIEnv *env, jfloatArray localAdjustmentsIn,
                          LocalAdjustment out[kMaxLocalAdjustments]) {
    jsize n = env->GetArrayLength(localAdjustmentsIn);
    int count = std::min(static_cast<int>(n / 10), kMaxLocalAdjustments);
    if (count <= 0) return 0;
    std::vector<jfloat> buf(static_cast<size_t>(count) * 10);
    env->GetFloatArrayRegion(localAdjustmentsIn, 0, static_cast<jsize>(buf.size()), buf.data());
    for (int i = 0; i < count; ++i) {
        const jfloat *p = &buf[static_cast<size_t>(i) * 10];
        out[i].isRadial = p[0] > 0.5f;
        out[i].startX = p[1];
        out[i].startY = p[2];
        out[i].endX = p[3];
        out[i].endY = p[4];
        out[i].invert = p[5] > 0.5f;
        out[i].feather = p[6];
        out[i].exposure = p[7];
        out[i].contrast = p[8];
        out[i].saturation = p[9];
    }
    return count;
}

// adjust.frag의 localMask()와 동일한 공식. u,v는 크롭 영역 기준 0..1(vUv와 동일 좌표계).
inline float computeLocalMask(const LocalAdjustment &la, float u, float v) {
    float mask;
    if (la.isRadial) {
        float rx = std::max(la.endX, 0.001f);
        float ry = std::max(la.endY, 0.001f);
        float dx = (u - la.startX) / rx;
        float dy = (v - la.startY) / ry;
        float dist = std::sqrt(dx * dx + dy * dy);
        mask = 1.f - smoothstepf(1.f - la.feather, 1.f, dist);
    } else {
        float dirX = la.endX - la.startX;
        float dirY = la.endY - la.startY;
        float lenSq = dirX * dirX + dirY * dirY;
        float t = lenSq > 0.0001f ? ((u - la.startX) * dirX + (v - la.startY) * dirY) / lenSq : 0.f;
        mask = clamp01(t);
    }
    if (la.invert) mask = 1.f - mask;
    return mask;
}

// adjust.frag의 5.5단계와 동일: 레이어를 순서대로 마스크 가중치만큼 섞어 적용한다.
inline void applyLocalAdjustments(float &r, float &g, float &b, float u, float v,
                                   const LocalAdjustment *layers, int count) {
    for (int i = 0; i < count; ++i) {
        float mask = computeLocalMask(layers[i], u, v);
        if (mask <= 0.f) continue;
        float evScale = powf(2.f, layers[i].exposure * 3.f);
        float lr = powf(std::max(r, 0.f), 2.2f) * evScale;
        float lg = powf(std::max(g, 0.f), 2.2f) * evScale;
        float lb = powf(std::max(b, 0.f), 2.2f) * evScale;
        float er = lr > 0.f ? powf(lr, 1.f / 2.2f) : 0.f;
        float eg = lg > 0.f ? powf(lg, 1.f / 2.2f) : 0.f;
        float eb = lb > 0.f ? powf(lb, 1.f / 2.2f) : 0.f;
        er = (er - 0.5f) * (1.f + layers[i].contrast) + 0.5f;
        eg = (eg - 0.5f) * (1.f + layers[i].contrast) + 0.5f;
        eb = (eb - 0.5f) * (1.f + layers[i].contrast) + 0.5f;
        float lgray = luminance(er, eg, eb);
        er = lgray + (er - lgray) * (1.f + layers[i].saturation);
        eg = lgray + (eg - lgray) * (1.f + layers[i].saturation);
        eb = lgray + (eb - lgray) * (1.f + layers[i].saturation);
        r = r + (er - r) * mask;
        g = g + (eg - g) * mask;
        b = b + (eb - b) * mask;
    }
}

// 셰이더의 1~6단계(화이트밸런스/노출/하이라이트-섀도우/대비/채도-생동감/부분보정/톤커브)를
// 픽셀 하나에 적용. u,v는 크롭 영역 기준 0..1 정규화 좌표(부분 보정 마스크 계산용).
void adjustPixel(float &r, float &g, float &b,
                  float tempShift, float tintShift, float evScale,
                  float highlights, float shadows, float contrast,
                  float saturation, float vibrance,
                  float u, float v, const LocalAdjustment *localLayers, int localCount,
                  const ChannelCurveLut256 &curve) {
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

    // 5.5) 부분 보정 (그라디언트/방사형 마스크)
    applyLocalAdjustments(r, g, b, u, v, localLayers, localCount);

    // 6) 톤커브 (마스터 -> 채널별)
    curve.apply(r, g, b);
}

// curvePointsIn은 마스터+빨강+초록+파랑 4세트(5점씩)를 이어붙인 20개 float.
void readCurvePoints(JNIEnv *env, jfloatArray curvePointsIn,
                      float master[5], float red[5], float green[5], float blue[5]) {
    jfloat buf[20];
    float defaults[5] = {0.f, 0.25f, 0.5f, 0.75f, 1.f};
    for (int set = 0; set < 4; ++set) {
        for (int i = 0; i < 5; ++i) buf[set * 5 + i] = defaults[i];
    }
    jsize n = env->GetArrayLength(curvePointsIn);
    env->GetFloatArrayRegion(curvePointsIn, 0, std::min(n, static_cast<jsize>(20)), buf);
    for (int i = 0; i < 5; ++i) {
        master[i] = buf[i];
        red[i] = buf[5 + i];
        green[i] = buf[10 + i];
        blue[i] = buf[15 + i];
    }
}

// (rot==90/270일 때 cropW/cropH가 필요하므로 인자로 받는다) 회전 후(output) 좌표를
// 회전 전(cropped) 좌표로 역매핑한다. 아래 rotateBuffer()의 정방향 매핑과 반드시 짝을
// 맞춰 유지해야 한다.
void inverseRotatePoint(int rot, int cropW, int cropH, int outX, int outY, int &cropX, int &cropY) {
    switch (rot) {
        case 90:
            // 정방향: dx=y, dy=cropW-1-x  =>  역방향: y=dx, x=cropW-1-dy
            cropX = cropW - 1 - outY;
            cropY = outX;
            break;
        case 270:
            // 정방향: dx=cropH-1-y, dy=x  =>  역방향: y=cropH-1-dx, x=dy
            cropX = outY;
            cropY = cropH - 1 - outX;
            break;
        case 180:
            cropX = cropW - 1 - outX;
            cropY = cropH - 1 - outY;
            break;
        default:
            cropX = outX;
            cropY = outY;
            break;
    }
}

// srcBuf(폭 srcW x 높이 srcH, RGB8)를 rot도만큼 회전해 dst에 담는다. 회전 방향은
// GL 프리뷰(Matrix.rotateM, +Z축 기준 양의 각도 = 반시계 방향 회전)와 일치시켰다:
// rot=90은 반시계, rot=270(=-90)은 시계 방향.
void rotateBuffer(const std::vector<uint8_t> &srcBuf, int srcW, int srcH, int rot,
                   std::vector<uint8_t> &dst, int &dstW, int &dstH) {
    if (rot == 90 || rot == 270) {
        dstW = srcH;
        dstH = srcW;
        dst.resize(static_cast<size_t>(dstW) * dstH * 3);
        for (int y = 0; y < srcH; ++y) {
            for (int x = 0; x < srcW; ++x) {
                size_t srcIdx = (static_cast<size_t>(y) * srcW + x) * 3;
                int dx, dy;
                if (rot == 90) {
                    dx = y;
                    dy = srcW - 1 - x;
                } else {
                    dx = srcH - 1 - y;
                    dy = x;
                }
                size_t dstIdx = (static_cast<size_t>(dy) * dstW + dx) * 3;
                dst[dstIdx] = srcBuf[srcIdx];
                dst[dstIdx + 1] = srcBuf[srcIdx + 1];
                dst[dstIdx + 2] = srcBuf[srcIdx + 2];
            }
        }
    } else if (rot == 180) {
        dstW = srcW;
        dstH = srcH;
        dst.resize(static_cast<size_t>(dstW) * dstH * 3);
        for (int y = 0; y < srcH; ++y) {
            for (int x = 0; x < srcW; ++x) {
                size_t srcIdx = (static_cast<size_t>(y) * srcW + x) * 3;
                size_t dstIdx = (static_cast<size_t>(srcH - 1 - y) * dstW + (srcW - 1 - x)) * 3;
                dst[dstIdx] = srcBuf[srcIdx];
                dst[dstIdx + 1] = srcBuf[srcIdx + 1];
                dst[dstIdx + 2] = srcBuf[srcIdx + 2];
            }
        }
    } else {
        dstW = srcW;
        dstH = srcH;
        dst = srcBuf;
    }
}

// 필름 시뮬레이션 3D LUT (adjust.frag의 uFilmLut 트라이리니어 샘플링을 CPU로 재현).
// lut는 FilmSimLut.kt와 동일한 순서(.cube 표준: R 최우선, G, B 순서로 바깥쪽)의
// size*size*size*3 RGB(0..1) 배열. lut가 비어있거나 strength<=0이면 무보정.
void applyFilmLut(float &r, float &g, float &b, const float *lut, int size, float strength) {
    if (lut == nullptr || size <= 1 || strength <= 0.f) return;

    float rf = clamp01(r) * (size - 1);
    float gf = clamp01(g) * (size - 1);
    float bf = clamp01(b) * (size - 1);
    int r0 = static_cast<int>(rf), g0 = static_cast<int>(gf), b0 = static_cast<int>(bf);
    int r1 = std::min(r0 + 1, size - 1);
    int g1 = std::min(g0 + 1, size - 1);
    int b1 = std::min(b0 + 1, size - 1);
    float rt = rf - r0, gt = gf - g0, bt = bf - b0;

    auto at = [&](int ri, int gi, int bi, int ch) -> float {
        size_t idx = (static_cast<size_t>(bi) * size * size +
                      static_cast<size_t>(gi) * size +
                      static_cast<size_t>(ri)) * 3 + ch;
        return lut[idx];
    };

    float outCh[3];
    for (int ch = 0; ch < 3; ++ch) {
        float c000 = at(r0, g0, b0, ch), c100 = at(r1, g0, b0, ch);
        float c010 = at(r0, g1, b0, ch), c110 = at(r1, g1, b0, ch);
        float c001 = at(r0, g0, b1, ch), c101 = at(r1, g0, b1, ch);
        float c011 = at(r0, g1, b1, ch), c111 = at(r1, g1, b1, ch);
        float c00 = c000 + (c100 - c000) * rt;
        float c10 = c010 + (c110 - c010) * rt;
        float c01 = c001 + (c101 - c001) * rt;
        float c11 = c011 + (c111 - c011) * rt;
        float c0 = c00 + (c10 - c00) * gt;
        float c1 = c01 + (c11 - c01) * gt;
        outCh[ch] = c0 + (c1 - c0) * bt;
    }
    r = r + (outCh[0] - r) * strength;
    g = g + (outCh[1] - g) * strength;
    b = b + (outCh[2] - b) * strength;
}

// buf(bufW x bufH, RGB 인접)에서 (fx, fy) 위치를 바일리니어로 샘플링한다.
void sampleBilinear(const std::vector<float> &buf, int bufW, int bufH,
                     float fx, float fy, float &r, float &g, float &b) {
    fx = std::max(0.f, std::min(fx, bufW - 1.f));
    fy = std::max(0.f, std::min(fy, bufH - 1.f));
    int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
    int x1 = std::min(x0 + 1, bufW - 1);
    int y1 = std::min(y0 + 1, bufH - 1);
    float tx = fx - x0, ty = fy - y0;
    auto at = [&](int xx, int yy, int ch) {
        return buf[(static_cast<size_t>(yy) * bufW + xx) * 3 + ch];
    };
    float outCh[3];
    for (int ch = 0; ch < 3; ++ch) {
        float top = at(x0, y0, ch) + (at(x1, y0, ch) - at(x0, y0, ch)) * tx;
        float bot = at(x0, y1, ch) + (at(x1, y1, ch) - at(x0, y1, ch)) * tx;
        outCh[ch] = top + (bot - top) * ty;
    }
    r = outCh[0];
    g = outCh[1];
    b = outCh[2];
}

// 텍스처/클래리티(넓은 반경 언샵마스크)에 쓸 블러 버퍼를 만든다. 102MP 원본에 큰
// 커널을 픽셀마다 직접 적용하면 시간이 지나치게 오래 걸리므로, 먼저 원본을 1/16
// 크기로 박스다운샘플한 작은 버퍼를 만들고(GPU 프리뷰의 밉맵 레벨4 샘플링과 동일한
// 발상) 본 픽셀 루프에서는 이를 바일리니어로 조회만 한다.
void buildClarityBlur(const std::vector<uint8_t> &src, int width,
                       int regionOrigX, int regionOrigY, int regionW, int regionH,
                       std::vector<float> &blurBuf, int &blurW, int &blurH) {
    blurW = std::max(1, regionW / 16);
    blurH = std::max(1, regionH / 16);
    blurBuf.assign(static_cast<size_t>(blurW) * blurH * 3, 0.f);
    std::vector<int> count(static_cast<size_t>(blurW) * blurH, 0);
    for (int ry = 0; ry < regionH; ++ry) {
        int y = regionOrigY + ry;
        int by = std::min(ry * blurH / regionH, blurH - 1);
        for (int rx = 0; rx < regionW; ++rx) {
            int x = regionOrigX + rx;
            int bx = std::min(rx * blurW / regionW, blurW - 1);
            size_t idx = (static_cast<size_t>(y) * width + x) * 3;
            size_t bidx = (static_cast<size_t>(by) * blurW + bx) * 3;
            blurBuf[bidx] += src[idx] / 255.f;
            blurBuf[bidx + 1] += src[idx + 1] / 255.f;
            blurBuf[bidx + 2] += src[idx + 2] / 255.f;
            count[static_cast<size_t>(by) * blurW + bx] += 1;
        }
    }
    for (size_t i = 0; i < count.size(); ++i) {
        int c = std::max(1, count[i]);
        blurBuf[i * 3] /= c;
        blurBuf[i * 3 + 1] /= c;
        blurBuf[i * 3 + 2] /= c;
    }
}

} // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_rawlab_editor_raw_RawProcessor_process(
    JNIEnv *env, jobject /*thiz*/,
    jbyteArray pixelsIn, jint width, jint height,
    jfloat exposure, jfloat contrast, jfloat temperature, jfloat tint,
    jfloat highlights, jfloat shadows, jfloat saturation, jfloat vibrance, jfloat sharpen,
    jfloat clarity,
    jfloatArray curvePointsIn,
    jfloatArray localAdjustmentsIn,
    jfloatArray filmLutIn, jint filmLutSize, jfloat filmLutStrength,
    jfloat cropLeft, jfloat cropTop, jfloat cropRight, jfloat cropBottom,
    jint rotationDegrees,
    jfloat patchCenterX, jfloat patchCenterY, jint patchSize) {

    jsize len = env->GetArrayLength(pixelsIn);
    std::vector<uint8_t> src(static_cast<size_t>(len));
    env->GetByteArrayRegion(pixelsIn, 0, len, reinterpret_cast<jbyte *>(src.data()));

    float curveMaster[5], curveRed[5], curveGreen[5], curveBlue[5];
    readCurvePoints(env, curvePointsIn, curveMaster, curveRed, curveGreen, curveBlue);
    ChannelCurveLut256 curve(curveMaster, curveRed, curveGreen, curveBlue);

    LocalAdjustment localLayers[kMaxLocalAdjustments];
    int localCount = readLocalAdjustments(env, localAdjustmentsIn, localLayers);

    jsize filmLutLen = env->GetArrayLength(filmLutIn);
    std::vector<float> filmLut(static_cast<size_t>(filmLutLen));
    if (filmLutLen > 0) {
        env->GetFloatArrayRegion(filmLutIn, 0, filmLutLen, filmLut.data());
    }
    const float *filmLutPtr = filmLutLen > 0 ? filmLut.data() : nullptr;

    const float tempShift = temperature * 0.30f;
    const float tintShift = tint * 0.30f;
    const float evScale = powf(2.f, exposure * 3.f);

    // 크롭 영역 (원본 이미지 좌표계)
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

    int rot = ((rotationDegrees % 360) + 360) % 360;
    int outW = (rot == 90 || rot == 270) ? cropH : cropW;
    int outH = (rot == 90 || rot == 270) ? cropW : cropH;

    // 실제로 픽셀 연산이 필요한 영역만 계산한다. patchSize<=0(export)는 크롭 전체가
    // 곧 그 영역이지만, patchSize>0("100% 확인")는 요청한 작은 패치에 대응하는
    // 원본 좌표계 사각형만 역산해서 그만큼만 처리한다 — GFX100RF 같은 1억 화소
    // 이미지를 통째로 보정하면 GB 단위 메모리가 필요해 저사양 기기에서 OOM으로
    // 죽을 수 있는데, 패치는 수 MB만으로 충분하다.
    int regionInCropX, regionInCropY, regionW, regionH;
    if (patchSize > 0) {
        int cx = static_cast<int>(patchCenterX * outW);
        int cy = static_cast<int>(patchCenterY * outH);
        int patchOutW = std::min(patchSize, outW);
        int patchOutH = std::min(patchSize, outH);
        int patchOutX = std::max(0, std::min(cx - patchOutW / 2, outW - patchOutW));
        int patchOutY = std::max(0, std::min(cy - patchOutH / 2, outH - patchOutH));

        // 출력(회전 후) 좌표계의 패치 사각형 네 모서리를 크롭(회전 전) 좌표계로
        // 역매핑해서 그 바운딩 박스를 구한다 — 90도 단위 회전은 등거리 변환이라
        // 사각형이 그대로 사각형으로 대응되므로 바운딩 박스가 곧 정확한 사각형이다.
        int cx0, cy0, cx1, cy1, cx2, cy2, cx3, cy3;
        inverseRotatePoint(rot, cropW, cropH, patchOutX, patchOutY, cx0, cy0);
        inverseRotatePoint(rot, cropW, cropH, patchOutX + patchOutW - 1, patchOutY, cx1, cy1);
        inverseRotatePoint(rot, cropW, cropH, patchOutX, patchOutY + patchOutH - 1, cx2, cy2);
        inverseRotatePoint(rot, cropW, cropH, patchOutX + patchOutW - 1, patchOutY + patchOutH - 1, cx3, cy3);
        int minX = std::min(std::min(cx0, cx1), std::min(cx2, cx3));
        int maxX = std::max(std::max(cx0, cx1), std::max(cx2, cx3));
        int minY = std::min(std::min(cy0, cy1), std::min(cy2, cy3));
        int maxY = std::max(std::max(cy0, cy1), std::max(cy2, cy3));
        regionInCropX = minX;
        regionInCropY = minY;
        regionW = maxX - minX + 1;
        regionH = maxY - minY + 1;

        // 방어적 클램프 (좌표 계산이 어긋나도 버퍼 밖을 읽지 않도록).
        regionInCropX = std::max(0, std::min(regionInCropX, cropW - 1));
        regionInCropY = std::max(0, std::min(regionInCropY, cropH - 1));
        regionW = std::max(1, std::min(regionW, cropW - regionInCropX));
        regionH = std::max(1, std::min(regionH, cropH - regionInCropY));
    } else {
        regionInCropX = 0;
        regionInCropY = 0;
        regionW = cropW;
        regionH = cropH;
    }
    int regionOrigX = cropLeftPx + regionInCropX;
    int regionOrigY = cropTopPx + regionInCropY;

    std::vector<float> clarityBlur;
    int clarityBlurW = 0, clarityBlurH = 0;
    if (clarity != 0.f) {
        buildClarityBlur(src, width, regionOrigX, regionOrigY, regionW, regionH,
                          clarityBlur, clarityBlurW, clarityBlurH);
    }

    // 1~8단계(화이트밸런스~톤커브~텍스처/클래리티~샤픈~필름시뮬레이션)를 필요한 영역에
    // 대해서만 한 번의 패스로 계산한다. 샤픈의 이웃 픽셀은 항상 원본 전체(src,
    // width/height) 기준으로 클램프해서 읽으므로 영역 경계에서도 전체 이미지를
    // 처리했을 때와 동일한 결과가 나온다.
    std::vector<uint8_t> regionOut(static_cast<size_t>(regionW) * regionH * 3);
    for (int ry = 0; ry < regionH; ++ry) {
        int y = regionOrigY + ry;
        for (int rx = 0; rx < regionW; ++rx) {
            int x = regionOrigX + rx;
            size_t idx = (static_cast<size_t>(y) * width + x) * 3;
            float r = src[idx] / 255.f;
            float g = src[idx + 1] / 255.f;
            float b = src[idx + 2] / 255.f;
            float u = cropW > 1 ? static_cast<float>(x - cropLeftPx) / (cropW - 1) : 0.f;
            float v = cropH > 1 ? static_cast<float>(y - cropTopPx) / (cropH - 1) : 0.f;
            adjustPixel(r, g, b, tempShift, tintShift, evScale, highlights, shadows,
                        contrast, saturation, vibrance, u, v, localLayers, localCount, curve);

            if (clarity != 0.f) {
                float fx = (regionW > 1) ? static_cast<float>(rx) / (regionW - 1) * (clarityBlurW - 1) : 0.f;
                float fy = (regionH > 1) ? static_cast<float>(ry) / (regionH - 1) * (clarityBlurH - 1) : 0.f;
                float br, bg, bb;
                sampleBilinear(clarityBlur, clarityBlurW, clarityBlurH, fx, fy, br, bg, bb);
                r += (r - br) * clarity * 0.6f;
                g += (g - bg) * clarity * 0.6f;
                b += (b - bb) * clarity * 0.6f;
            }

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
            r = clamp01(r);
            g = clamp01(g);
            b = clamp01(b);

            applyFilmLut(r, g, b, filmLutPtr, filmLutSize, filmLutStrength);

            size_t outIdx = (static_cast<size_t>(ry) * regionW + rx) * 3;
            regionOut[outIdx] = static_cast<uint8_t>(clamp01(r) * 255.f + 0.5f);
            regionOut[outIdx + 1] = static_cast<uint8_t>(clamp01(g) * 255.f + 0.5f);
            regionOut[outIdx + 2] = static_cast<uint8_t>(clamp01(b) * 255.f + 0.5f);
        }
    }

    // 9) 회전 — 이미 필요한 영역만 남겨뒀으므로 회전 후 크기가 곧 최종 출력 크기다
    // (export는 크롭 전체 회전 결과, "100% 확인"은 요청한 패치 크기와 정확히 일치).
    std::vector<uint8_t> finalBuf;
    int finalW, finalH;
    rotateBuffer(regionOut, regionW, regionH, rot, finalBuf, finalW, finalH);

    const size_t outCount = static_cast<size_t>(finalW) * finalH;
    std::vector<jint> argb(outCount);
    for (size_t i = 0; i < outCount; ++i) {
        size_t idx = i * 3;
        argb[i] = static_cast<jint>(0xFF000000u |
                                     (static_cast<uint32_t>(finalBuf[idx]) << 16) |
                                     (static_cast<uint32_t>(finalBuf[idx + 1]) << 8) |
                                     static_cast<uint32_t>(finalBuf[idx + 2]));
    }

    jintArray argbArray = env->NewIntArray(static_cast<jsize>(outCount));
    env->SetIntArrayRegion(argbArray, 0, static_cast<jsize>(outCount), argb.data());

    jclass processedImageClass = env->FindClass("com/rawlab/editor/raw/ProcessedImage");
    jmethodID ctor = env->GetMethodID(processedImageClass, "<init>", "(II[I)V");
    return env->NewObject(processedImageClass, ctor, finalW, finalH, argbArray);
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
    jfloatArray localAdjustmentsIn,
    jfloatArray filmLutIn, jint filmLutSize, jfloat filmLutStrength,
    jfloat cropLeft, jfloat cropTop, jfloat cropRight, jfloat cropBottom) {

    jsize len = env->GetArrayLength(pixelsIn);
    std::vector<uint8_t> src(static_cast<size_t>(len));
    env->GetByteArrayRegion(pixelsIn, 0, len, reinterpret_cast<jbyte *>(src.data()));

    float curveMaster[5], curveRed[5], curveGreen[5], curveBlue[5];
    readCurvePoints(env, curvePointsIn, curveMaster, curveRed, curveGreen, curveBlue);
    ChannelCurveLut256 curve(curveMaster, curveRed, curveGreen, curveBlue);

    LocalAdjustment localLayers[kMaxLocalAdjustments];
    int localCount = readLocalAdjustments(env, localAdjustmentsIn, localLayers);

    jsize filmLutLen = env->GetArrayLength(filmLutIn);
    std::vector<float> filmLut(static_cast<size_t>(filmLutLen));
    if (filmLutLen > 0) {
        env->GetFloatArrayRegion(filmLutIn, 0, filmLutLen, filmLut.data());
    }
    const float *filmLutPtr = filmLutLen > 0 ? filmLut.data() : nullptr;

    const float tempShift = temperature * 0.30f;
    const float tintShift = tint * 0.30f;
    const float evScale = powf(2.f, exposure * 3.f);

    int cropLeftPx = std::max(0, std::min(static_cast<int>(cropLeft * width + 0.5f), width - 1));
    int cropTopPx = std::max(0, std::min(static_cast<int>(cropTop * height + 0.5f), height - 1));
    int cropRightPx = std::max(cropLeftPx + 1, std::min(static_cast<int>(cropRight * width + 0.5f), width));
    int cropBottomPx = std::max(cropTopPx + 1, std::min(static_cast<int>(cropBottom * height + 0.5f), height));
    int cropW = cropRightPx - cropLeftPx;
    int cropH = cropBottomPx - cropTopPx;

    std::vector<jint> bins(768, 0); // [0..255]=R, [256..511]=G, [512..767]=B

    for (int y = cropTopPx; y < cropBottomPx; ++y) {
        for (int x = cropLeftPx; x < cropRightPx; ++x) {
            size_t idx = (static_cast<size_t>(y) * width + x) * 3;
            float r = src[idx] / 255.f;
            float g = src[idx + 1] / 255.f;
            float b = src[idx + 2] / 255.f;
            float u = cropW > 1 ? static_cast<float>(x - cropLeftPx) / (cropW - 1) : 0.f;
            float v = cropH > 1 ? static_cast<float>(y - cropTopPx) / (cropH - 1) : 0.f;
            adjustPixel(r, g, b, tempShift, tintShift, evScale, highlights, shadows,
                        contrast, saturation, vibrance, u, v, localLayers, localCount, curve);
            applyFilmLut(r, g, b, filmLutPtr, filmLutSize, filmLutStrength);
            bins[static_cast<int>(clamp01(r) * 255.f + 0.5f)] += 1;
            bins[256 + static_cast<int>(clamp01(g) * 255.f + 0.5f)] += 1;
            bins[512 + static_cast<int>(clamp01(b) * 255.f + 0.5f)] += 1;
        }
    }

    jintArray result = env->NewIntArray(768);
    env->SetIntArrayRegion(result, 0, 768, bins.data());
    return result;
}
