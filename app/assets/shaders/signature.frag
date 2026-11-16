#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 uv;
uniform highp samplerExternalOES source;
uniform mat4 transform;
out vec4 color;
float peak(vec3 c) { return max(max(c.r, c.g), c.b); }

void main() {
    vec2 t = (transform * vec4(uv, 0.0, 1.0)).xy;
    vec2 dx = dFdx(t) * 0.36, dy = dFdy(t) * 0.36;
    vec3 a = texture(source, t - dx - dy).rgb;
    vec3 b = texture(source, t + dx - dy).rgb;
    vec3 c = texture(source, t - dx + dy).rgb;
    vec3 d = texture(source, t + dx + dy).rgb;
    color = vec4((a + b + c + d) * 0.25, max(max(peak(a), peak(b)), max(peak(c), peak(d))));
}
