#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D before;
uniform sampler2D after;
uniform highp sampler2D prior;
uniform vec2 span;
uniform int refine;
uniform int quality;
uniform sampler2D scene;
out vec4 color;

const float zero = 128.0 / 255.0;
const float slope = 127.0 / 255.0;

struct Neighborhood {
    float center;
    vec4 edges;
    vec4 corners;
};
Neighborhood around(sampler2D image, vec2 p, vec2 dx, vec2 dy) {
    return Neighborhood(texture(image, p).r,
        vec4(texture(image, p - dx).r, texture(image, p + dx).r,
             texture(image, p - dy).r, texture(image, p + dy).r),
        quality == 0 && refine != 0 ? vec4(0.0) :
        vec4(texture(image, p - dx - dy).r, texture(image, p + dx - dy).r,
             texture(image, p - dx + dy).r, texture(image, p + dx + dy).r));
}

float errorAt(sampler2D image, vec2 p, vec2 dx, vec2 dy, Neighborhood original) {
    Neighborhood comparison = around(image, p, dx, dy);
    return 0.20 * abs(original.center - comparison.center)
           + dot(abs(original.edges - comparison.edges), vec4(0.11))
           + dot(abs(original.corners - comparison.corners), vec4(0.09))
           + ((any(lessThan(p, vec2(0.0))) || any(greaterThan(p, vec2(1.0)))) ? 0.25 : 0.0);
}

void consider(sampler2D image, vec2 test, vec2 px, vec2 dx, vec2 dy,
              Neighborhood original, inout vec2 best, inout float score) {
    float e = errorAt(image, uv + test, dx, dy, original) + 0.00045 * length(test / px);
    if (e < score) {
        best = test;
        score = e;
    }
}

vec2 decodePrior(ivec2 xy, ivec2 sz, bool reverse) {
    vec4 value = texelFetch(prior, clamp(xy, ivec2(0), sz - 1), 0);
    vec2 v = reverse ? value.zw : value.xy;
    return (v - zero) / slope * span;
}

float subpixelOffset(float left, float center, float right) {
    float curvature = left + right - 2.0 * center;
    if (min(left, right) < center || curvature < 0.002) return 0.0;
    return clamp(0.5 * (left - right) / curvature, -0.5, 0.5);
}

vec2 matchMotion(sampler2D a, sampler2D b, vec2 px, bool reverse) {
    vec2 dx = vec2(px.x * 2.0, 0.0);
    vec2 dy = vec2(0.0, px.y * 2.0);
    Neighborhood original = around(a, uv, dx, dy);
    vec2 best = vec2(0.0);
    float score = errorAt(b, uv, dx, dy, original);
    if (score < 0.003) return best;
    if (refine == 0) {
        int radius = quality == 0 ? 3 : 4;
        for (int y = -radius; y <= radius; ++y)
            for (int x = -radius; x <= radius; ++x)
                if (x != 0 || y != 0)
                    consider(b, vec2(x, y) * px * 2.0, px, dx, dy, original, best, score);
        if (quality == 0 && score > 0.018) {
            for (int y = -1; y <= 1; ++y)
                for (int x = -1; x <= 1; ++x)
                    if (x != 0 || y != 0)
                        consider(b, vec2(x, y) * px * 8.0, px, dx, dy, original, best, score);
        }
    } else {
        ivec2 size = textureSize(prior, 0);
        ivec2 center = ivec2(uv * vec2(size));
        for (int y = -1; y <= 1; ++y)
            for (int x = -1; x <= 1; ++x)
                consider(b, decodePrior(center + ivec2(x, y), size, reverse),
                         px, dx, dy, original, best, score);
        vec2 base = best;
        int radius = quality == 0 ? 1 : 2;
        for (int y = -radius; y <= radius; ++y)
            for (int x = -radius; x <= radius; ++x)
                consider(b, base + vec2(x, y) * px, px, dx, dy, original, best, score);
    }
    vec2 base = best;
    float stepSize = refine == 0 ? 1.0 : 0.5;
    for (int y = -1; y <= 1; ++y)
        for (int x = -1; x <= 1; ++x)
            consider(b, base + vec2(x, y) * px * stepSize, px, dx, dy, original, best, score);
    float detail = abs(original.edges.x - original.edges.y) + abs(original.edges.z - original.edges.w);
    if (refine != 0 && score < 0.095 && detail > 0.024) {
        vec2 at = uv + best;
        vec2 halfX = vec2(px.x * 0.5, 0.0), halfY = vec2(0.0, px.y * 0.5);
        float center = errorAt(b, at, dx, dy, original);
        vec2 shift = vec2(
            subpixelOffset(errorAt(b, at - halfX, dx, dy, original), center,
                           errorAt(b, at + halfX, dx, dy, original)),
            subpixelOffset(errorAt(b, at - halfY, dx, dy, original), center,
                           errorAt(b, at + halfY, dx, dy, original)));
        consider(b, best + shift * px * 0.5, px, dx, dy, original, best, score);
    }
    return clamp(best / span, -1.0, 1.0);
}

void main() {
    if (refine != 0 && texelFetch(scene, ivec2(0), 0).r > 0.5) { color = vec4(zero); return; }
    vec2 px = 1.0 / vec2(textureSize(before, 0));
    color = zero + slope * vec4(matchMotion(before, after, px, false),
                                matchMotion(after, before, px, true));
}
