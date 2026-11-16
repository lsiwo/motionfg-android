#version 300 es
precision highp float;
in vec2 uv;
uniform float time;
uniform int hdr;
uniform float sdrWhite;
out vec4 color;
/* COLOR_FUNCTIONS */
void main() {
    vec2 p = vec2(uv.x * 1.7777778, uv.y);
    vec2 grid = abs(fract(p * 12.0 + 0.5) - 0.5);
    vec3 ink = vec3(0.036, 0.058, 0.067)
              + vec3(0.055, 0.091, 0.093) * (1.0 - smoothstep(0.01, 0.037, min(grid.x, grid.y)));
    vec2 line = p - vec2(0.89 + 0.62 * sin(time * 2.6), 0.56 + 0.22 * sin(time * 3.9));
    float box = max(abs(line.x), abs(line.y));
    float squares = 0.55 + 0.45 * mod(floor(line.x * 39.0) + floor(line.y * 39.0), 2.0);
    ink = mix(ink, vec3(0.71, 0.98, 0.47) * squares, 1.0 - smoothstep(0.083, 0.087, box));
    vec2 ring = p - vec2(0.89 - 0.64 * sin(time * 1.9), 0.34 + 0.11 * cos(time * 3.2));
    ink = mix(ink, vec3(0.36, 0.71, 1.0), 1.0 - smoothstep(0.010, 0.014, abs(length(ring) - 0.059)));
    color = vec4(mapColorWhite(ink, 0, 0, hdr != 0, sdrWhite > 0.0 ? sdrWhite : 203.0), 1.0);
}
