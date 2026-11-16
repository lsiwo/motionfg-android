package dev.lsiwo.motionfg;

enum CaptureProfile {
    NATIVE(100, RenderSettings.LINEAR),
    QUALITY(67, RenderSettings.TEMPORAL),
    BALANCED(58, RenderSettings.BICUBIC),
    PERFORMANCE(50, RenderSettings.SHARP);

    private static final CaptureProfile[] OPTIONS = values();
    final int percent, filter;

    CaptureProfile(int percent, int filter) {
        this.percent = percent;
        this.filter = filter;
    }

    static CaptureProfile at(int index) {
        return index >= 0 && index < OPTIONS.length ? OPTIONS[index] : QUALITY;
    }
}
