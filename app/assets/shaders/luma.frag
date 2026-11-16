#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D source;
uniform vec2 tap;
uniform int monochrome;
out vec4 color;
void main() {
    vec3 sum = texture(source, uv + vec2( tap.x,  tap.y)).rgb
             + texture(source, uv + vec2(-tap.x,  tap.y)).rgb
             + texture(source, uv + vec2( tap.x, -tap.y)).rgb
             + texture(source, uv + vec2(-tap.x, -tap.y)).rgb;
    float luminance = monochrome == 1 ? sum.r * 0.25 : dot(sum * 0.25, vec3(0.299, 0.587, 0.114));
    color = vec4(luminance, 0.0, 0.0, 1.0);
}
