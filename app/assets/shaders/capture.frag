#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 uv;
uniform highp samplerExternalOES source;
uniform mat4 transform;
uniform int transfer;
uniform int gamut;
uniform int hdr;
uniform float sdrWhite;
out vec4 color;
/* COLOR_FUNCTIONS */
void main() {
    vec3 c = texture(source, (transform * vec4(uv, 0.0, 1.0)).xy).rgb;
    color = vec4(mapColorWhite(c, transfer, gamut, hdr != 0, sdrWhite), 1.0);
}
