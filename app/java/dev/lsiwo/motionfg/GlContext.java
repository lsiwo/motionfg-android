package dev.lsiwo.motionfg;

import static android.opengl.EGL14.*;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;

final class GlContext implements AutoCloseable {
    static final int SDR = 0, PQ = 1, SCRGB = 2, RAW = -1;
    private static final int ES3 = 0x0040;
    private static final int RECORDABLE = 0x3142;
    private static final int COLORSPACE = 0x309d;
    private static final int BT2020_PQ = 0x3340;
    private static final int SCRGB_LINEAR = 0x3350;
    private static final int COMPONENT_TYPE = 0x3339;
    private static final int COMPONENT_FLOAT = 0x333b;
    private static int references;

    private EGLDisplay display = EGL_NO_DISPLAY;
    private EGLContext context = EGL_NO_CONTEXT;
    private EGLConfig config;
    private int minSwapInterval, maxSwapInterval;
    private EGLSurface offscreen = EGL_NO_SURFACE, target = EGL_NO_SURFACE, current = EGL_NO_SURFACE;
    final int mode;

    GlContext(boolean requestHdr, boolean encoder) {
        this(requestHdr ? PQ : SDR, encoder);
    }

    GlContext(int mode, boolean encoder) {
        this.mode = mode;
        display = acquireDisplay();
        try {
            String ext = eglQueryString(display, EGL_EXTENSIONS);
            if (mode == PQ && (ext == null || !ext.contains("EGL_EXT_gl_colorspace_bt2020_pq"))) {
                throw new IllegalStateException("Цветовое пространство EGL BT.2020 PQ недоступно");
            }
            if (mode == SCRGB && (ext == null || !ext.contains("EGL_EXT_gl_colorspace_scrgb_linear")
                    || !ext.contains("EGL_EXT_pixel_format_float"))) {
                throw new IllegalStateException("Поверхность EGL scRGB FP16 недоступна");
            }
            config = choose(encoder);
            if (config == null) throw error("EGL-конфигурация недоступна");
            minSwapInterval = attribute(config, EGL_MIN_SWAP_INTERVAL);
            maxSwapInterval = attribute(config, EGL_MAX_SWAP_INTERVAL);
            context = eglCreateContext(display, config, EGL_NO_CONTEXT,
                    new int[] { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE }, 0);
            if (context == EGL_NO_CONTEXT) throw error("Создание контекста GLES");
            offscreen = eglCreatePbufferSurface(display, config,
                    new int[] { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE }, 0);
            if (offscreen == EGL_NO_SURFACE) throw error("Создание буфера GLES");
            offscreen();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private static synchronized EGLDisplay acquireDisplay() {
        EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (d == EGL_NO_DISPLAY || (references == 0 && !eglInitialize(d, new int[2], 0, new int[2], 0))) {
            throw new IllegalStateException("Не удалось инициализировать EGL");
        }
        references++;
        return d;
    }

    private static synchronized void releaseDisplay(EGLDisplay d) {
        if (--references == 0) eglTerminate(d);
    }

    private EGLConfig choose(boolean encoder) {
        int bits = mode == SCRGB ? 16 : (mode == PQ ? 10 : 8);
        int[] base = {
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                EGL_RENDERABLE_TYPE, ES3, EGL_RED_SIZE, bits, EGL_GREEN_SIZE, bits,
                EGL_BLUE_SIZE, bits, EGL_ALPHA_SIZE, mode == PQ ? 2 : bits,
                EGL_NONE, EGL_NONE, EGL_NONE
        };
        if (mode == SCRGB) {
            base[12] = COMPONENT_TYPE;
            base[13] = COMPONENT_FLOAT;
        } else if (encoder && mode == SDR) {
            base[12] = RECORDABLE;
            base[13] = EGL_TRUE;
        }
        EGLConfig[] configs = new EGLConfig[40];
        int[] n = new int[1];
        if (!eglChooseConfig(display, base, 0, configs, 0, configs.length, n, 0)) return null;
        EGLConfig fallback = null;
        for (int i = 0; i < n[0]; i++) {
            EGLConfig candidate = configs[i];
            if (attribute(candidate, EGL_RED_SIZE) != bits || attribute(candidate, EGL_GREEN_SIZE) != bits
                    || attribute(candidate, EGL_BLUE_SIZE) != bits) continue;
            if (encoder) return candidate;
            if (fallback == null) fallback = candidate;
            if (attribute(candidate, EGL_MIN_SWAP_INTERVAL) == 0
                    && attribute(candidate, EGL_MAX_SWAP_INTERVAL) >= 1) return candidate;
        }
        return fallback;
    }

    private int attribute(EGLConfig eglConfig, int name) {
        int[] value = new int[1];
        return eglGetConfigAttrib(display, eglConfig, name, value, 0) ? value[0] : -1;
    }

    void attach(Surface surface) {
        detach();
        int color = mode == PQ ? BT2020_PQ : SCRGB_LINEAR;
        int[] attrs = mode == SDR ? new int[] { EGL_NONE } : new int[] { COLORSPACE, color, EGL_NONE };
        target = eglCreateWindowSurface(display, config, surface, attrs, 0);
        if (target == EGL_NO_SURFACE) throw error("Поверхность вывода EGL недоступна");
        if (mode != SDR) {
            int[] actual = new int[1];
            if (eglQuerySurface(display, target, COLORSPACE, actual, 0)) {
                if (actual[0] != color) throw new IllegalStateException("EGL открыл другое цветовое пространство");
            } else {
                eglGetError();
            }
        }
        screen();
    }

    void screen() { makeCurrent(target); }
    void offscreen() { makeCurrent(offscreen); }
    boolean swapInterval(int interval) {
        return eglSwapInterval(display, interval) && interval >= minSwapInterval && interval <= maxSwapInterval;
    }

    boolean swap(long presentationNs) {
        if (presentationNs >= 0 && !android.opengl.EGLExt.eglPresentationTimeANDROID(display, target, presentationNs)) {
            throw error("Метка времени видео");
        }
        return eglSwapBuffers(display, target);
    }

    private void makeCurrent(EGLSurface surface) {
        if (surface == EGL_NO_SURFACE) throw new IllegalStateException("Нет поверхности EGL");
        if (surface != current && !eglMakeCurrent(display, surface, surface, context)) throw error("Переключение EGL");
        current = surface;
    }

    void detach() {
        if (target == EGL_NO_SURFACE) return;
        offscreen();
        eglDestroySurface(display, target);
        target = EGL_NO_SURFACE;
    }

    private static IllegalStateException error(String message) {
        return new IllegalStateException(message + " (EGL 0x" + Integer.toHexString(eglGetError()) + ")");
    }

    @Override public void close() {
        if (display == EGL_NO_DISPLAY) return;
        if (current != EGL_NO_SURFACE) eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (target != EGL_NO_SURFACE) eglDestroySurface(display, target);
        if (offscreen != EGL_NO_SURFACE) eglDestroySurface(display, offscreen);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        releaseDisplay(display);
        display = EGL_NO_DISPLAY;
        context = EGL_NO_CONTEXT;
        target = offscreen = current = EGL_NO_SURFACE;
        eglReleaseThread();
    }
}
