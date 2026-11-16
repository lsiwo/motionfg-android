#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
in vec2 uv;
uniform highp __samplerExternal2DY2YEXT source;
uniform mat4 transform;
uniform int transfer;
uniform int gamut;
uniform int hdr;
uniform float sdrWhite;
uniform int limited;
out vec4 color;
/* COLOR_FUNCTIONS */
void main() {
    vec3 yuv = texture(source, (transform * vec4(uv, 0.0, 1.0)).xy).rgb;
    if (limited != 0) {
        yuv = (yuv - vec3(64.0, 512.0, 512.0) / 1023.0)
                * (1023.0 / vec3(876.0, 896.0, 896.0));
    } else {
        yuv.gb -= vec2(0.5);
    }
    vec3 rgb = yuv.r + mat2x3(0.0, -0.164553, 1.8814, 1.4746, -0.571353, 0.0) * yuv.gb;
    color = vec4(mapColorWhite(max(rgb, 0.0), transfer, gamut, hdr != 0, sdrWhite), 1.0);
}
