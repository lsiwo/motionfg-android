#version 300 es
precision highp float;
in vec2 uv;
uniform highp sampler2D before;
uniform highp sampler2D after;
uniform highp sampler2D motion;
uniform sampler2D reliability;
uniform sampler2D scene;
uniform vec2 span;
uniform vec2 sourceSize;
uniform float phase;
uniform int quality;
uniform int hdr;
uniform int hdrWindow;
uniform int predictive;
out vec4 color;
/* COLOR_FUNCTIONS */

vec4 flowAt(vec2 at) {
    return (texture(motion, at) * 255.0 - 128.0) * (span.xyxy / 127.0);
}

bool valid(vec2 point) {
    return all(greaterThanEqual(point, vec2(0.0))) && all(lessThanEqual(point, vec2(1.0)));
}

vec3 blend(vec3 a, vec3 b, float t) {
    if (hdr == 0) return mix(a, b, t);
    return pqEncode(mix(pqLinear(a), pqLinear(b), t));
}

vec4 nearestFrame() { return phase < 0.5 ? texture(before, uv) : texture(after, uv); }

vec4 outputColor(vec3 c) {
    return vec4(displayColor(c, hdr, hdrWindow), 1.0);
}

float difference(vec3 a, vec3 b) {
    vec3 d = abs(a - b);
    return max(d.r, max(d.g, d.b));
}

bool stableEdge(vec2 at, vec2 offset, vec3 center) {
    if (difference(texture(before, at).rgb, center) >= 0.018) return false;
    vec3 a = texture(after, at - offset).rgb, b = texture(after, at + offset).rgb;
    return max(difference(a, center), difference(b, center)) > 0.09
        && difference(a, texture(before, at - offset).rgb) < 0.032
        && difference(b, texture(before, at + offset).rgb) < 0.032;
}

vec3 forecast() {
    vec3 latest = texture(after, uv).rgb;
    vec2 initial = flowAt(uv).zw;
    vec2 point = uv + phase * initial;
    if (!valid(point)) return latest;

    vec2 refined = flowAt(point).zw;
    vec2 dimensions = max(sourceSize, vec2(1.0));
    float motion = length(refined * dimensions);
    vec2 jump = (refined - initial) * dimensions;
    // A vector from the other side of an object boundary must not drive this pixel.
    vec2 limits = vec2(1.5, 4.0) + vec2(0.08, 0.14) * motion;
    float agreement = 1.0 - smoothstep(limits.x * limits.x, limits.y * limits.y, dot(jump, jump));
    if (agreement <= 0.0) return latest;
    point = uv + phase * refined;
    if (!valid(point)) return latest;

    float trust = min(texture(reliability, point).g, texture(reliability, uv).g + 0.12);
    float coverage = agreement * smoothstep(0.52, 0.88, trust);
    if (coverage <= 0.02) return latest;
    vec3 moved = texture(after, point).rgb;
    float delta = difference(latest, moved);
    if (delta > 0.009) {
        vec2 offset = refined * (min(3.0, phase * motion) / max(motion, 0.001));
        if (stableEdge(uv, offset, latest) || stableEdge(point, offset, moved)) return latest;
    }
    // Choose a position in the image rather than overlaying displaced silhouettes.
    if (delta > 0.13) return coverage >= 0.84 ? moved : latest;
    return coverage >= 0.99 ? moved : texture(after, mix(uv, point, coverage)).rgb;
}

void main() {
    if (texelFetch(scene, ivec2(0), 0).r > 0.5) {
        color = outputColor(predictive != 0 ? texture(after, uv).rgb : nearestFrame().rgb);
        return;
    }
    if (predictive != 0) {
        color = outputColor(forecast());
        return;
    }
    vec4 field = flowAt(uv);
    vec2 pA = uv - phase * field.xy;
    vec2 pB = uv - (1.0 - phase) * field.zw;
    if (quality == 1) {
        pA = uv - phase * flowAt(pA).xy;
        pB = uv - (1.0 - phase) * flowAt(pB).zw;
    }
    float confA = valid(pA) ? texture(reliability, pA).r : 0.0;
    float confB = valid(pB) ? texture(reliability, pB).g : 0.0;
    float wa = (1.0 - phase) * confA, wb = phase * confB;
    float support = wa + wb;
    if (support < 0.10) {
        color = outputColor(nearestFrame().rgb);
        return;
    }

    vec3 a = texture(before, pA).rgb;
    vec3 b = texture(after, pB).rgb;
    float mismatch = difference(a, b);
    if (mismatch > 0.29 || (mismatch > 0.17 && min(confA, confB) < 0.20 && max(confA, confB) > 0.75)) {
        // Occlusions can make only one of the source samples valid.
        bool validA = confA >= 0.56, validB = confB >= 0.56;
        vec3 chosen;
        if (validA && validB) chosen = wb >= wa ? b : a;
        else if (validA || validB) chosen = validB ? b : a;
        else chosen = nearestFrame().rgb;
        color = outputColor(chosen);
        return;
    }
    float weightB = wb / support;
    float bias = smoothstep(0.095, 0.28, mismatch) * smoothstep(0.025, 0.17, abs(wa - wb));
    weightB = mix(weightB, step(wa, wb), bias);
    vec3 result = blend(a, b, weightB);
    float certainty = smoothstep(0.12, 0.43, support);
    if (certainty < 1.0) {
        vec3 nearest = nearestFrame().rgb;
        result = mix(nearest, result, certainty);
    }
    color = outputColor(result);
}
