#version 320 es
precision highp float;
in vec2 uv;
uniform sampler2D motionField;
uniform sampler2D confidenceField;
uniform vec2 motionSpan;
layout(location = 0) out vec4 EncodedVelocity;

// Motion FG stores forward/backward flow in RGBA8 around 128/255.
// SGSR2 expects clip-space velocity encoded with v * 0.2495 + 32767/65535.
// Optical-flow confidence is used conservatively: uncertain vectors become
// zero velocity, causing SGSR2's matrix fallback rather than false motion.
void main() {
    vec2 packed = texture(motionField, uv).xy;
    vec2 flowUv = (packed - vec2(128.0 / 255.0)) * (255.0 / 127.0) * motionSpan;
    float confidence = texture(confidenceField, uv).r;
    vec2 encoded = flowUv * 0.499 + vec2(32767.0 / 65535.0);
    if (confidence < 0.38) encoded.x = 0.0;
    EncodedVelocity = vec4(encoded, 0.0, 1.0);
}