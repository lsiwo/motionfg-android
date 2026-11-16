#version 300 es
precision highp float;
uniform sampler2D before;
uniform sampler2D after;
uniform highp sampler2D coarseMotion;
uniform vec2 span;
out vec4 color;
void main() {
    float stationary = 0.0;
    float residual = 0.0;
    float bias = 0.0;
    float widespread = 0.0;
    for (int y = 0; y < 6; ++y) {
        for (int x = 0; x < 10; ++x) {
            vec2 at = (vec2(x, y) + 0.5) / vec2(10, 6);
            float a = texture(before, at).r;
            float b = texture(after, at).r;
            vec2 v = texture(coarseMotion, at).xy;
            vec2 d = (v * 255.0 - 128.0) / 127.0 * span;
            stationary += abs(a - b);
            widespread += step(0.075, abs(a - b));
            residual += abs(a - texture(after, at + d).r);
            bias += a - b;
        }
    }
    bool cut = abs(bias) > 8.7 || (widespread > 35.0 && stationary > 6.6
            && residual > 5.4 && stationary - residual < 2.0);
    color = vec4(cut ? 1.0 : 0.0, 0.0, 0.0, 1.0);
}
