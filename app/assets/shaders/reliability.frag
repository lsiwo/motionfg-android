#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D before;
uniform sampler2D after;
uniform highp sampler2D motion;
uniform sampler2D scene;
uniform vec2 span;
uniform vec2 pixel;
out vec4 color;

vec4 flowAt(vec2 p) {
    return (texture(motion, p) * 255.0 - 128.0) * (span.xyxy / 127.0);
}

float consistent(sampler2D a, sampler2D b, vec2 v, bool reverse) {
    vec2 target = uv + v;
    if (any(lessThan(target, vec2(0.0))) || any(greaterThan(target, vec2(1.0)))) return 0.0;
    vec4 paired = flowAt(target);
    vec2 mismatch = (v + (reverse ? paired.xy : paired.zw)) / pixel;
    float cycle = 1.0 / (1.0 + 0.11 * dot(mismatch, mismatch));
    vec2 ox = vec2(pixel.x, 0.0), oy = vec2(0.0, pixel.y);
    float error = 0.50 * abs(texture(a, uv).r - texture(b, target).r)
        + 0.125 * (abs(texture(a, uv + ox).r - texture(b, target + ox).r)
                 + abs(texture(a, uv - ox).r - texture(b, target - ox).r)
                 + abs(texture(a, uv + oy).r - texture(b, target + oy).r)
                 + abs(texture(a, uv - oy).r - texture(b, target - oy).r));
    return cycle * (1.0 - smoothstep(0.035, 0.19, error));
}

void main() {
    if (texelFetch(scene, ivec2(0), 0).r > 0.5) {
        color = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec4 v = flowAt(uv);
    color = vec4(consistent(before, after, v.xy, false),
                 consistent(after, before, v.zw, true), 0.0, 1.0);
}
