#version 300 es
precision highp float;
in vec2 uv;
uniform highp sampler2D image;
uniform highp sampler2D previousImage;
uniform highp sampler2D historyImage;
uniform highp sampler2D field;
uniform sampler2D reliability;
uniform sampler2D scene;
uniform vec2 span;
uniform int filterMode;
uniform int historyEnabled;
uniform int hdr;
uniform int hdrWindow;
out vec4 color;
/* COLOR_FUNCTIONS */

float luma(vec3 c) {
    return hdr != 0 ? luminance2020(c) : luminance709(c);
}
float largest(vec3 c) { return max(c.x, max(c.y, c.z)); }
vec3 pixels(vec2 at) { return texture(image, at).rgb; }
vec3 samplePixel(ivec2 at, ivec2 size) {
    return texelFetch(image, clamp(at, ivec2(0), size - 1), 0).rgb;
}

vec3 cubic(vec2 size, bool compact) {
    vec2 pos = uv * size - 0.5;
    vec2 base = floor(pos) + 0.5;
    vec2 f = fract(pos), f2 = f * f, f3 = f2 * f;
    vec2 w0 = -0.5 * f + f2 - 0.5 * f3;
    vec2 w1 = 1.0 - 2.5 * f2 + 1.5 * f3;
    vec2 w2 = 0.5 * f + 2.0 * f2 - 1.5 * f3;
    vec2 w3 = -0.5 * f2 + 0.5 * f3;
    vec2 middleWeight = w1 + w2;
    vec2 mid = (base + w2 / middleWeight) / size;
    vec2 lo = (base - 1.0) / size, hi = (base + 2.0) / size;
    vec3 center = pixels(mid);
    vec3 n = pixels(vec2(mid.x, lo.y)), s = pixels(vec2(mid.x, hi.y));
    vec3 w = pixels(vec2(lo.x, mid.y)), e = pixels(vec2(hi.x, mid.y));
    vec3 cross = n * middleWeight.x * w0.y + s * middleWeight.x * w3.y
               + w * w0.x * middleWeight.y + e * w3.x * middleWeight.y
               + center * middleWeight.x * middleWeight.y;
    if (compact) {
        float weight = middleWeight.x * middleWeight.y
                     + middleWeight.x * (w0.y + w3.y) + middleWeight.y * (w0.x + w3.x);
        return clamp(cross / weight, min(center, min(min(n, s), min(w, e))),
                                     max(center, max(max(n, s), max(w, e))));
    }
    return cross + pixels(lo) * w0.x * w0.y
                 + pixels(vec2(hi.x, lo.y)) * w3.x * w0.y
                 + pixels(vec2(lo.x, hi.y)) * w0.x * w3.y
                 + pixels(hi) * w3.x * w3.y;
}

const ivec2 offsets[12] = ivec2[12](ivec2(0, -1), ivec2(1, -1),
    ivec2(-1, 0), ivec2(0, 0), ivec2(1, 0), ivec2(2, 0),
    ivec2(-1, 1), ivec2(0, 1), ivec2(1, 1), ivec2(2, 1),
    ivec2(0, 2), ivec2(1, 2));

vec4 cubicWeights(float t) {
    float t2 = t * t, t3 = t2 * t;
    return vec4(-0.5 * t + t2 - 0.5 * t3, 1.0 - 2.5 * t2 + 1.5 * t3,
                0.5 * t + 2.0 * t2 - 1.5 * t3, -0.5 * t2 + 0.5 * t3);
}

vec3 reconstruct(ivec2 size, ivec2 base, vec2 f, out vec3 low, out vec3 high) {
    vec3 sampled[12];
    for (int i = 0; i < 12; ++i) sampled[i] = samplePixel(base + offsets[i], size);
    low = min(min(sampled[3], sampled[4]), min(sampled[7], sampled[8]));
    high = max(max(sampled[3], sampled[4]), max(sampled[7], sampled[8]));
    vec2 gradA = vec2(luma(sampled[4] - sampled[2]), luma(sampled[7] - sampled[0]));
    vec2 gradB = vec2(luma(sampled[5] - sampled[3]), luma(sampled[8] - sampled[1]));
    vec2 gradC = vec2(luma(sampled[8] - sampled[6]), luma(sampled[10] - sampled[3]));
    vec2 gradD = vec2(luma(sampled[9] - sampled[7]), luma(sampled[11] - sampled[4]));
    vec2 gradient = mix(mix(gradA, gradB, f.x), mix(gradC, gradD, f.x), f.y);
    float magnitude = length(gradient);
    float activity = mix(mix(length(gradA), length(gradB), f.x),
                         mix(length(gradC), length(gradD), f.x), f.y);
    float align = magnitude / max(activity, 0.0001);
    float edge = smoothstep(0.026, 0.20, magnitude) * smoothstep(0.42, 0.94, align);
    vec2 normal = gradient / max(magnitude, 0.0001);
    vec4 wx = cubicWeights(f.x), wy = cubicWeights(f.y);
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 12; ++i) {
        ivec2 offset = offsets[i];
        vec2 delta = vec2(offset) - f;
        float crossEdge = dot(delta, normal);
        float alongEdge = max(0.0, dot(delta, delta) - crossEdge * crossEdge);
        float d2 = min(4.0, alongEdge * 0.74 + crossEdge * crossEdge * 1.62);
        float taper = 1.0 - 0.25 * d2;
        float directionWeight = taper * taper * (1.0 - 0.72 * d2);
        float weight = wx[offset.x + 1] * wy[offset.y + 1] + 0.23 * edge * directionWeight;
        sum += sampled[i] * weight;
        total += weight;
    }
    return total > 0.01 ? clamp(sum / total, low, high) : pixels(uv);
}

vec3 temporal(vec3 center, vec3 low, vec3 high) {
    if (historyEnabled == 0) return center;
    bool alignMotion = historyEnabled == 1;
    if (alignMotion && texelFetch(scene, ivec2(0), 0).r > 0.5) return center;
    vec2 back = alignMotion ? (texture(field, uv).zw * 255.0 - 128.0) * (span / 127.0) : vec2(0.0);
    vec2 oldPoint = uv + back;
    float confidence = alignMotion ? texture(reliability, uv).g : 1.0;
    if (confidence < 0.38 || any(lessThan(oldPoint, vec2(0.0))) || any(greaterThan(oldPoint, vec2(1.0)))) {
        return center;
    }
    vec3 raw = pixels(uv), before = texture(previousImage, oldPoint).rgb;
    vec3 delta = raw - before;
    float disagreement = largest(abs(delta));
    float contrast = largest(high - low);
    if (contrast > 0.16 && disagreement > 0.025) return center;
    float react = alignMotion ? 1.0 - smoothstep(0.018, 0.081, disagreement)
                             : 1.0 - smoothstep(0.004, 0.039, disagreement);
    float weight = (alignMotion ? (hdr != 0 ? 0.38 : 0.56) : 0.24)
                 * react * smoothstep(0.38, 0.95, confidence);
    weight *= 1.0 - 0.72 * smoothstep(0.12, 0.52, contrast);
    // Align low-frequency color to the current frame before reusing history.
    // This carries subpixel detail without carrying the old exposure or color.
    vec3 reprojected = clamp(texture(historyImage, oldPoint).rgb + delta, low, high);
    float detailLimit = 0.006 + 0.14 * contrast;
    return clamp(center + weight * clamp(reprojected - center, -detailLimit, detailLimit), low, high);
}

void main() {
    ivec2 sz = textureSize(image, 0);
    vec2 size = vec2(sz);
    vec3 c;
    if (filterMode == 0) {
        c = pixels(uv);
    } else if (filterMode == 1) {
        c = cubic(size, true);
    } else {
        vec2 p = uv * size - 0.5;
        ivec2 origin = ivec2(floor(p));
        vec3 low, high;
        if (filterMode == 2) {
            vec3 a = samplePixel(origin, sz), b = samplePixel(origin + ivec2(1, 0), sz);
            vec3 d = samplePixel(origin + ivec2(0, 1), sz), e = samplePixel(origin + ivec2(1), sz);
            low = min(min(a, b), min(d, e));
            high = max(max(a, b), max(d, e));
            c = clamp(cubic(size, false), low, high);
        } else {
            c = reconstruct(sz, origin, fract(p), low, high);
            float contrast = largest(high - low);
            float detail = 0.11 * smoothstep(0.014, 0.08, contrast) * (1.0 - smoothstep(0.20, 0.55, contrast));
            c = clamp(c + detail * (c - pixels(uv)), low, high);
            c = temporal(c, low, high);
        }
    }
    color = vec4(displayColor(c, hdr, hdrWindow), 1.0);
}
