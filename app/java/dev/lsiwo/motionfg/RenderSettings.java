package dev.lsiwo.motionfg;

import android.content.Intent;
import android.content.SharedPreferences;

final class RenderSettings {
    static final String SCALE = "upscale";
    static final String FACTOR = "factor";
    static final String MOTION = "motion_quality";
    static final String HDR = "hdr_output";
    static final String VSYNC = "vsync";
    static final String PROFILE = "capture_profile";
    static final String CANVAS_WIDTH = "capture_width";
    static final String CANVAS_HEIGHT = "capture_height";
    static final String LOW_LATENCY = "low_latency";
    static final String SDR_WHITE = "sdr_white";
    static final int DEFAULT_WHITE = 203;
    static final int MIN_CANVAS = 160, MAX_CANVAS = 4096;
    static final int LINEAR = 0, SHARP = 1, BICUBIC = 2, TEMPORAL = 3;
    static final int FAST = 0, QUALITY = 1;
    static final int SDR = 0, HDR10 = 1;

    final int fps, factor, upscale, motion, hdr, sdrWhite, canvasWidth, canvasHeight;
    final boolean counter, vsync, lowLatency;
    final CaptureProfile profile;

    RenderSettings(int fps, int factor, int upscale, int motion, int hdr,
            boolean counter, boolean vsync, CaptureProfile profile,
            int canvasWidth, int canvasHeight, boolean lowLatency, int sdrWhite) {
        this.fps = fps == 0 || fps == 24 || fps == 45 || fps == 60 ? fps : 30;
        this.factor = Math.max(1, Math.min(4, factor));
        this.upscale = Math.max(LINEAR, Math.min(TEMPORAL, upscale));
        this.motion = motion == FAST ? FAST : QUALITY;
        this.hdr = hdr == HDR10 ? HDR10 : SDR;
        this.counter = counter;
        this.vsync = vsync;
        this.profile = profile;
        boolean custom = legalDimension(canvasWidth) && legalDimension(canvasHeight);
        this.canvasWidth = custom ? canvasWidth : 0;
        this.canvasHeight = custom ? canvasHeight : 0;
        this.lowLatency = lowLatency;
        this.sdrWhite = Math.max(100, Math.min(300, sdrWhite));
    }

    static boolean legalDimension(int value) { return value >= MIN_CANVAS && value <= MAX_CANVAS; }

    static RenderSettings read(SharedPreferences p) {
        int fallback = p.getInt("short_edge", -1) == 0
                ? CaptureProfile.NATIVE.ordinal() : CaptureProfile.QUALITY.ordinal();
        CaptureProfile profile = CaptureProfile.at(p.getInt(PROFILE, fallback));
        return new RenderSettings(p.getInt(CaptureService.FPS, 0), p.getInt(FACTOR, 2), profile.filter,
                p.getInt(MOTION, FAST), p.getInt(HDR, SDR), p.getBoolean(CaptureService.COUNTER, true),
                p.getBoolean(VSYNC, false), profile, p.getInt(CANVAS_WIDTH, 0),
                p.getInt(CANVAS_HEIGHT, 0), p.getBoolean(LOW_LATENCY, true), p.getInt(SDR_WHITE, DEFAULT_WHITE));
    }

    static RenderSettings fromIntent(Intent i) {
        CaptureProfile profile = CaptureProfile.at(i.getIntExtra(PROFILE, CaptureProfile.QUALITY.ordinal()));
        return new RenderSettings(i.getIntExtra(CaptureService.FPS, 0), i.getIntExtra(FACTOR, 2), profile.filter,
                i.getIntExtra(MOTION, FAST), i.getIntExtra(HDR, SDR), i.getBooleanExtra(CaptureService.COUNTER, true),
                i.getBooleanExtra(VSYNC, false), profile,
                i.getIntExtra(CANVAS_WIDTH, 0), i.getIntExtra(CANVAS_HEIGHT, 0), i.getBooleanExtra(LOW_LATENCY, true),
                i.getIntExtra(SDR_WHITE, DEFAULT_WHITE));
    }

    Intent put(Intent i) {
        return i.putExtra(CaptureService.FPS, fps).putExtra(FACTOR, factor).putExtra(MOTION, motion)
                .putExtra(HDR, hdr).putExtra(CaptureService.COUNTER, counter)
                .putExtra(VSYNC, vsync).putExtra(PROFILE, profile.ordinal()).putExtra(CANVAS_WIDTH, canvasWidth)
                .putExtra(CANVAS_HEIGHT, canvasHeight).putExtra(LOW_LATENCY, lowLatency).putExtra(SDR_WHITE, sdrWhite);
    }

    static String meter(int game, int output) {
        return game + " / " + output + " FPS";
    }
}
