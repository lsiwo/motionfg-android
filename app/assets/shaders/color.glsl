float luminance709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float luminance2020(vec3 c) { return dot(c, vec3(0.2627, 0.6780, 0.0593)); }

vec3 pqLinear(vec3 signal) {
    vec3 t = pow(clamp(signal, 0.0, 1.0), vec3(1.0 / 78.84375));
    return pow(max(t - 0.8359375, 0.0) / max(18.8515625 - 18.6875 * t, 0.0001),
               vec3(1.0 / 0.1593017578125));
}
vec3 pqEncode(vec3 linear) {
    vec3 t = pow(clamp(linear, 0.0, 1.0), vec3(0.1593017578125));
    return pow((0.8359375 + 18.8515625 * t) / (1.0 + 18.6875 * t), vec3(78.84375));
}
vec3 toLinearSrgb(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}
vec3 fromLinearSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}
vec3 toLinearVideo(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 4.5, pow((c + 0.099) / 1.099, vec3(1.0 / 0.45)), step(vec3(0.081), c));
}
vec3 hlgLinear(vec3 e) {
    vec3 scene = mix(e * e / 3.0,
        (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(vec3(0.5), e));
    return scene * pow(max(luminance2020(scene), 0.0), 0.2) * 0.1;
}

const mat3 REC709_TO_2020 = mat3(
    0.6274040, 0.0690970, 0.0163916, 0.3292820, 0.9195400, 0.0880132, 0.0433136, 0.0113612, 0.8955950);
const mat3 BT2020_TO_709 = mat3(
    1.6604910, -0.1245505, -0.0181508, -0.5876411, 1.1328999, -0.1005789, -0.0728499, -0.0083494, 1.1187297);
const mat3 P3_TO_709 = mat3(
    1.224940, -0.042057, -0.019638, -0.224940, 1.042057, -0.078636, 0.0, 0.0, 1.098274);

vec3 mapColorWhite(vec3 rgb, int transfer, int gamut, bool hdrOutput, float whiteNits) {
    if (transfer == 1 && gamut == 1 && hdrOutput) return clamp(rgb, 0.0, 1.0);
    if ((transfer == 0 || transfer == 4) && (gamut == 0 || gamut > 2) && !hdrOutput) {
        return clamp(rgb, 0.0, 1.0);
    }
    bool inputHdr = transfer == 1 || transfer == 2 || transfer == 5;
    if (inputHdr) {
        vec3 linear = transfer == 1 ? pqLinear(rgb)
                    : transfer == 2 ? hlgLinear(clamp(rgb, 0.0, 1.0)) : rgb * 0.008;
        if (gamut == 2) linear = P3_TO_709 * linear;
        if (hdrOutput) return pqEncode(gamut == 1 ? linear : REC709_TO_2020 * linear);
        if (gamut == 1) linear = BT2020_TO_709 * linear;
        linear = max(linear, 0.0) * (10000.0 / 203.0);
        float luma = luminance709(linear);
        float excess = max(luma - 0.72, 0.0);
        float mapped = min(luma, 0.72) + 0.28 * excess / (excess + 0.28);
        return fromLinearSrgb(linear * mapped / max(luma, 0.00001));
    }
    vec3 linear = transfer == 3 ? max(rgb, 0.0)
                : transfer == 4 ? toLinearVideo(rgb) : toLinearSrgb(clamp(rgb, 0.0, 1.0));
    if (gamut == 2) linear = P3_TO_709 * linear;
    if (gamut == 1 && !hdrOutput) linear = BT2020_TO_709 * linear;
    if (!hdrOutput) return fromLinearSrgb(linear);
    if (gamut != 1) linear = REC709_TO_2020 * linear;
    return pqEncode(clamp(linear, 0.0, 1.0) * (whiteNits * 0.0001));
}

vec3 mapColor(vec3 rgb, int transfer, int gamut, bool hdrOutput) {
    return mapColorWhite(rgb, transfer, gamut, hdrOutput, 203.0);
}

vec3 displayColor(vec3 c, int workingHdr, int surfaceType) {
    if (surfaceType < 0) return c;
    if (workingHdr != 0 && surfaceType == 2) {
        return (BT2020_TO_709 * pqLinear(c)) * 125.0;
    }
    if (workingHdr != 0 && surfaceType == 0) return mapColor(c, 1, 1, false);
    return clamp(c, 0.0, 1.0);
}
