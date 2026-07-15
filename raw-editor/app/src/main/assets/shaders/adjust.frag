#version 300 es
precision mediump float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uTexelSize;   // 1.0/width, 1.0/height (샤픈 샘플링용)

// 모든 슬라이더는 -1.0..1.0 (0 = 무보정), uSharpen만 0.0..1.0.
uniform float uExposure;
uniform float uContrast;
uniform float uTemperature;
uniform float uTint;
uniform float uHighlights;
uniform float uShadows;
uniform float uSaturation;
uniform float uVibrance;
uniform float uSharpen;
uniform float uClarity;
uniform sampler2D uCurveLut; // 256x1 RGB LUT: R=red채널결과, G=green채널결과, B=blue채널결과
                              // (마스터 커브 -> 채널별 커브 순으로 이미 합성되어 있음)
uniform mediump sampler3D uFilmLut; // 32x32x32 필름 시뮬레이션 3D LUT
uniform float uFilmLutStrength;     // 0 = 미적용

vec3 srgbToLinear(vec3 c) {
    return pow(max(c, 0.0), vec3(2.2));
}

vec3 linearToSrgb(vec3 c) {
    return pow(max(c, 0.0), vec3(1.0 / 2.2));
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 color = texture(uTexture, vUv).rgb;

    // 1) 화이트 밸런스 미세조정 (채널 게인 근사)
    float tempShift = uTemperature * 0.30;
    float tintShift = uTint * 0.30;
    color.r *= (1.0 + tempShift);
    color.b *= (1.0 - tempShift);
    color.g *= (1.0 + tintShift * 0.5);

    // 2) 노출: 선형광 공간에서 스탑(EV) 단위로 배율 적용
    vec3 linearColor = srgbToLinear(color);
    linearColor *= pow(2.0, uExposure * 3.0);
    color = linearToSrgb(linearColor);

    // 3) 하이라이트 / 섀도우: 루미넌스 마스크 기반 톤 조정
    float lum = luminance(color);
    float highlightMask = smoothstep(0.5, 1.0, lum);
    float shadowMask = 1.0 - smoothstep(0.0, 0.5, lum);
    color += uHighlights * highlightMask * 0.5 * (1.0 - color);
    color += uShadows * shadowMask * 0.5 * color;

    // 4) 대비
    color = (color - 0.5) * (1.0 + uContrast) + 0.5;

    // 5) 채도 / 생동감 (생동감은 이미 채도가 높은 픽셀일수록 영향을 덜 받음)
    float gray = luminance(color);
    color = mix(vec3(gray), color, 1.0 + uSaturation);
    float maxC = max(color.r, max(color.g, color.b));
    float minC = min(color.r, min(color.g, color.b));
    float existingSat = maxC - minC;
    color = mix(color, mix(vec3(gray), color, 1.0 + uVibrance), 1.0 - existingSat);

    // 6) 톤커브 (마스터 -> 채널별, 각 채널을 자기 값으로 조회)
    color.r = texture(uCurveLut, vec2(color.r, 0.5)).r;
    color.g = texture(uCurveLut, vec2(color.g, 0.5)).g;
    color.b = texture(uCurveLut, vec2(color.b, 0.5)).b;

    // 6.5) 텍스처/클래리티: 샤픈과 같은 언샵마스크 방식이지만, 인접 픽셀 대신 밉맵의
    // 넓은 반경(레벨 4 = 원본 텍스처의 1/16 크기로 뭉친) 블러를 기준으로 삼는다.
    // 매 프래그먼트마다 큰 커널을 직접 순회하지 않고 밉맵 한 번 샘플링으로 대체해
    // 실시간 프리뷰 성능을 유지한다.
    if (uClarity != 0.0) {
        vec3 wideBlur = textureLod(uTexture, vUv, 4.0).rgb;
        color += (color - wideBlur) * uClarity * 0.6;
    }

    // 7) 샤픈: 인접 4픽셀 평균 대비 언샵 마스크
    if (uSharpen > 0.0) {
        vec3 sum = vec3(0.0);
        sum += texture(uTexture, vUv + vec2(-uTexelSize.x, 0.0)).rgb;
        sum += texture(uTexture, vUv + vec2(uTexelSize.x, 0.0)).rgb;
        sum += texture(uTexture, vUv + vec2(0.0, -uTexelSize.y)).rgb;
        sum += texture(uTexture, vUv + vec2(0.0, uTexelSize.y)).rgb;
        vec3 blurred = sum * 0.25;
        color += (color - blurred) * uSharpen * 1.5;
    }
    color = clamp(color, 0.0, 1.0);

    // 8) 필름 시뮬레이션 (3D LUT, 마지막에 "룩"으로 적용)
    if (uFilmLutStrength > 0.0) {
        vec3 graded = texture(uFilmLut, color).rgb;
        color = mix(color, graded, uFilmLutStrength);
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
