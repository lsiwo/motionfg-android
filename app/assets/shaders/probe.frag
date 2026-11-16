#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D currentFrame;
uniform sampler2D previousFrame;
uniform int testBlack;
uniform float level;
out vec4 color;
void main() {
    vec4 a = texture(currentFrame, uv);
    vec4 delta = testBlack != 0 ? a : abs(a - texture(previousFrame, uv));
    if (max(max(delta.r, delta.g), max(delta.b, delta.a)) <= level) discard;
    color = vec4(0.0);
}
