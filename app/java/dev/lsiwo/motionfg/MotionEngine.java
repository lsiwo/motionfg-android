package dev.lsiwo.motionfg;

import android.content.res.AssetManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class MotionEngine {
    interface Listener {
        void failed(String message);
        void rates(int game, int output);
        void colorMode(int mode);
        default void outputChanged() { }
        default void inputColor(ColorSignal signal) { }
    }

    interface InputReady {
        void accept(Surface surface, CaptureGeometry geometry, Runnable resizeComplete);
    }

    private static final int SLOT_COUNT = 3;
    private static final long STALL_MILLIS = 220;
    private static final long SECOND = 1_000_000_000L, DEMO_INTERVAL = SECOND / 30;
    private static final String TAG = "MotionFG";
    private final HandlerThread worker = new HandlerThread("MotionFG GPU");
    private final Handler handler;
    private final AssetManager assets;
    private final Listener listener;
    private final int motionQuality;
    private final FramePacer pacer;
    private final boolean demo;
    private final boolean showFps;
    private final Choreographer.FrameCallback tick = this::drawTick;
    private final Choreographer.FrameCallback displayFrame = ignored -> presentLatest();
    private final Runnable freeTick = () -> drawTick(System.nanoTime());
    private final Runnable probePoll = this::checkProbe;
    private final Runnable meter = this::reportRates;
    private Choreographer choreographer;
    private GlVideo gpu;
    private Surface window;
    private int outputWidth, outputHeight, filter, factor, captureFps;
    private int sourceWidth, sourceHeight, canvasWidth, canvasHeight, sdrWhite;
    private int outputMode;
    private CaptureProfile profile;
    private InputReady sourceReady;
    private int inputFrames, outputFrames;
    private long candidateTime, statsStart;
    private long lastVsync, displayPeriod = SECOND / 60, reportedPeriod = SECOND / 60;
    private int slowTicks;
    private long demoStart, demoNext, lastLatched;
    private long timerDue, renderRetryAt;
    private float capacity;
    private boolean requested, blackInput, pendingPoll, inputQueued, probing, suspended, repaint;
    private boolean reportFps;
    private boolean vsync, lowLatency, stageFree, displayQueued, stageDirty, inputResizing;
    private int captureRevision;
    private ColorSignal lastColor;
    private volatile boolean closed, outputReady;
    private volatile boolean surfaceActive;
    private volatile long gpuBusySince, probeSince;

    MotionEngine(AssetManager assets, RenderSettings settings, boolean demo, boolean hdrSupported,
            Listener listener) {
        this.assets = assets;
        motionQuality = settings.motion;
        this.demo = demo;
        showFps = demo || settings.counter;
        outputMode = settings.hdr == RenderSettings.HDR10 && hdrSupported ? GlContext.PQ : GlContext.SDR;
        this.listener = listener;
        factor = settings.factor;
        filter = settings.upscale;
        profile = settings.profile;
        canvasWidth = settings.canvasWidth;
        canvasHeight = settings.canvasHeight;
        sdrWhite = settings.sdrWhite;
        captureFps = settings.fps;
        vsync = settings.vsync;
        lowLatency = settings.lowLatency;
        pacer = new FramePacer(demo ? 30 : settings.fps);
        pacer.setLowLatency(lowLatency);
        worker.start();
        handler = new Handler(worker.getLooper());
        configureMeter();
    }

    void setSourceSize(int sourceWidth, int sourceHeight, InputReady ready) {
        enqueue(() -> {
            ensure();
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            sourceReady = ready;
            configureSource();
            requestDraw();
        });
    }

    private void configureSource() {
        if (sourceWidth <= 0 || sourceHeight <= 0) return;
        CaptureGeometry size = CaptureGeometry.source(sourceWidth, sourceHeight, profile.percent,
                canvasWidth, canvasHeight, gpu.maxTextureSize());
        boolean changed = gpu.setGeometry(size);
        if (changed) {
            restartStream();
            if (!demo) listenToInput();
        }
        if (sourceReady != null) {
            int revision = ++captureRevision;
            inputResizing |= changed;
            sourceReady.accept(gpu.input(), size, () -> enqueue(() -> {
                if (revision == captureRevision) inputResizing = false;
            }));
        }
    }

    void setOutput(Surface surface, float currentRate, float maxRate) {
        surfaceActive = surface != null;
        enqueue(() -> {
            ensure();
            if (window == surface) return;
            detach();
            if (surface == null || !surface.isValid()) return;
            if (!attachOutput(surface)) return;
            window = surface;
            capacity = maxRate > 1f ? maxRate : 120f;
            updateDisplayPeriod(currentRate);
            updateSwapMode();
            refreshHint();
            listener.colorMode(outputMode);
            requestDraw();
        });
    }

    void resizeOutput(Surface surface, int w, int h, float refreshRate) {
        enqueue(() -> {
            if (surface != window) return;
            updateDisplayPeriod(refreshRate);
            if (w == outputWidth && h == outputHeight) return;
            cancelDisplayFrame();
            outputWidth = w;
            outputHeight = h;
            repaint = true;
            requestDraw();
        });
    }

    private void updateDisplayPeriod(float rate) {
        if (rate <= 1f) return;
        long period = Math.round((double) SECOND / rate);
        if (period == reportedPeriod) return;
        displayPeriod = reportedPeriod = period;
        lastVsync = 0;
        slowTicks = 0;
        if (vsync) pacer.reschedule();
    }

    void setDemoSettings(int multiplier, int upscale, boolean sync) {
        enqueue(() -> updatePlayback(multiplier, upscale, sync));
    }

    void setLiveSettings(RenderSettings changed) {
        enqueue(() -> {
            profile = changed.profile;
            canvasWidth = changed.canvasWidth;
            canvasHeight = changed.canvasHeight;
            if (sdrWhite != changed.sdrWhite) {
                sdrWhite = changed.sdrWhite;
                if (gpu != null) {
                    gpu.setSdrWhite(sdrWhite);
                    if (outputMode != GlContext.SDR) restartStream();
                }
            }
            if (lowLatency != changed.lowLatency && gpu != null) gpu.discardHistory();
            lowLatency = changed.lowLatency;
            pacer.setLowLatency(lowLatency);
            if (captureFps != changed.fps) {
                captureFps = changed.fps;
                pacer.setLimit(captureFps);
            }
            updatePlayback(changed.factor, changed.upscale, changed.vsync);
        });
    }

    private void updatePlayback(int multiplier, int upscale, boolean sync) {
        if (factor != multiplier || vsync != sync) pacer.reschedule();
        if (vsync != sync) {
            cancelDraw();
            vsync = sync;
            lastVsync = 0;
            displayPeriod = reportedPeriod;
            slowTicks = 0;
            updateSwapMode();
        }
        if (filter != upscale && gpu != null) {
            gpu.discardHistory();
        }
        factor = multiplier;
        filter = upscale;
        repaint = true;
        configureMeter();
        refreshHint();
        requestDraw();
    }

    void setSuspended(boolean pause) {
        enqueue(() -> {
            if (suspended == pause) return;
            boolean hasLatched = lastLatched != 0;
            suspended = pause;
            restartStream();
            refreshHint();
            if (!pause) {
                if (hasLatched && !demo && gpu != null) {
                    inputQueued = true;
                    consumeInput();
                }
                requestDraw();
            }
        });
    }

    private void updateSwapMode() {
        cancelDisplayFrame();
        if (gpu == null || window == null) return;
        gpu.discardStage();
        boolean applied = gpu.egl.swapInterval(vsync ? 1 : 0);
        stageFree = !vsync && !applied;
        renderRetryAt = 0;
    }

    private void refreshHint() {
        if (window == null) return;
        try {
            int expected = demo || captureFps == 0 ? 30 : captureFps;
            float rate = !vsync || suspended || factor == 1 ? 0f : Math.min(capacity, expected * factor);
            window.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // A rotation can invalidate the window before the display hint is applied.
        }
    }

    void detachOutput() {
        if (closed) return;
        surfaceActive = false;
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            try {
                if (!closed) detach();
            } catch (RuntimeException e) {
                fail(e);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(280, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensure() throws IOException {
        if (gpu != null) return;
        while (gpu == null) {
            try {
                gpu = new GlVideo(assets, outputMode, false, motionQuality, SLOT_COUNT, !demo, !demo);
            } catch (IllegalStateException e) {
                useFallback(e);
            }
        }
        gpu.setSdrWhite(sdrWhite);
        if (!demo) listenToInput();
        if (choreographer == null) choreographer = Choreographer.getInstance();
        Log.i(TAG, "OpenGL renderer: " + android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_RENDERER));
    }

    private void listenToInput() {
        GlVideo binding = gpu;
        binding.onInput(t -> { if (gpu == binding) onFrame(); }, handler);
    }

    private boolean attachOutput(Surface surface) throws IOException {
        while (surface.isValid()) {
            try {
                gpu.egl.attach(surface);
                return true;
            } catch (IllegalStateException e) {
                if (!surface.isValid()) {
                    gpu.egl.detach();
                    return false;
                }
                useFallback(e);
                gpu.close();
                gpu = null;
                ensure();
                configureSource();
            }
        }
        return false;
    }

    private void useFallback(IllegalStateException cause) {
        if (outputMode == GlContext.SDR) throw cause;
        Log.w(TAG, "HDR surface mode " + outputMode + " unavailable", cause);
        outputMode = outputMode == GlContext.PQ ? GlContext.SCRGB : GlContext.SDR;
    }

    private void requestDraw() {
        if (!surfaceActive || suspended || window == null || outputWidth <= 0 || outputHeight <= 0 || gpu.width() == 0) return;
        if (vsync) {
            if (!requested) {
                requested = true;
                choreographer.postFrameCallback(tick);
            }
            return;
        }
        long now = System.nanoTime();
        long due = pacer.nextFreePhase(now);
        if (demo) due = Math.min(due, demoNext == 0 ? now : demoNext);
        if (repaint && pacer.front != FramePacer.NONE && !pacer.hasWork()) due = now;
        if (due == Long.MAX_VALUE) return;
        due = Math.max(due, renderRetryAt);
        if (requested && due >= timerDue) return;
        if (requested) handler.removeCallbacks(freeTick);
        requested = true;
        timerDue = due;
        long delay = Math.max(0L, (due - now + 500_000L) / 1_000_000L);
        handler.postDelayed(freeTick, delay);
    }

    private void cancelDraw() {
        if (requested) {
            handler.removeCallbacks(freeTick);
            if (choreographer != null) choreographer.removeFrameCallback(tick);
        }
        requested = false;
    }

    private void cancelDisplayFrame() {
        if (displayQueued) choreographer.removeFrameCallback(displayFrame);
        displayQueued = stageDirty = false;
    }

    private void onFrame() {
        if (closed) return;
        inputQueued = true;
        checkInput();
    }

    private void consumeInput() {
        if (probing) {
            int outcome = gpu.pollProbe();
            if (outcome == GlVideo.NO_RESULT) return;
            probing = false;
            probeSince = 0;
            long now = System.nanoTime();
            if (outcome == GlVideo.BLACK) {
                if (!blackInput) {
                    boolean next = inputQueued;
                    restartStream();
                    inputQueued = next;
                    blackInput = true;
                }
            } else if (outcome == GlVideo.NEW_FRAME) {
                blackInput = false;
                int slot = pacer.captureSlot(SLOT_COUNT);
                if (slot != FramePacer.NONE) {
                    gpu.capture(slot, null, false);
                    ColorSignal signal = gpu.captureSignal();
                    if (signal != lastColor) {
                        lastColor = signal;
                        listener.inputColor(signal);
                    }
                    gpu.acceptProbe();
                    accept(slot, candidateTime, now);
                }
            }
        }

        if (inputQueued) {
            inputQueued = false;
            long timestamp = gpu.latch();
            if (timestamp <= 0) timestamp = System.nanoTime();
            if (!suspended && !inputResizing && window != null && gpu.width() > 0
                    && timestamp != lastLatched && pacer.accepts(timestamp)) {
                candidateTime = timestamp;
                gpu.submitProbe();
                probing = true;
                probeSince = SystemClock.uptimeMillis();
                scheduleProbePoll(lowLatency);
            }
            lastLatched = timestamp;
        }
    }

    private void scheduleProbePoll(boolean immediate) {
        if (pendingPoll || !probing) return;
        pendingPoll = true;
        long wait = SystemClock.uptimeMillis() - probeSince;
        handler.postDelayed(probePoll, immediate ? 0 : wait < 8 ? 1 : 4);
    }

    private void checkProbe() {
        pendingPoll = false;
        if (closed || !probing) return;
        checkInput();
        if (!closed && !vsync) scheduleProbePoll(false);
    }

    private void checkInput() {
        boolean wasBusy = markGpuBusy();
        try {
            consumeInput();
            if (pacer.hasWork() || (vsync && probing)) requestDraw();
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            if (!wasBusy) gpuBusySince = 0;
        }
    }

    private void accept(int slot, long sourceNs, long now) {
        pacer.submit(slot, sourceNs);
        if (reportFps) {
            beginMeter(now);
            inputFrames++;
        }
    }

    private void beginMeter(long now) {
        if (statsStart != 0) return;
        statsStart = now;
        handler.postDelayed(meter, 1000);
    }

    private void configureMeter() {
        reportFps = showFps || vsync && captureFps == 0 && factor >= 3;
        if (!reportFps && statsStart != 0) {
            handler.removeCallbacks(meter);
            statsStart = 0;
            inputFrames = outputFrames = 0;
        }
    }

    private void reportRates() {
        if (closed || suspended || window == null || statsStart == 0) return;
        long now = System.nanoTime();
        long elapsed = Math.max(1, now - statsStart);
        listener.rates(Math.round(inputFrames * (float) SECOND / elapsed),
                Math.round(outputFrames * (float) SECOND / elapsed));
        boolean active = inputFrames != 0 || outputFrames != 0;
        inputFrames = outputFrames = 0;
        statsStart = active ? now : 0;
        if (active) handler.postDelayed(meter, 1000);
    }

    private void drawTick(long now) {
        requested = false;
        if (closed || !surfaceActive || suspended || window == null || gpu.width() == 0
                || outputWidth <= 0 || outputHeight <= 0) return;
        markGpuBusy();
        boolean waiting = false;
        try {
            if (vsync) measureVsync(now);
            if (demo) offerDemo(now); else consumeInput();
            boolean redraw = repaint && pacer.front != FramePacer.NONE;
            if ((pacer.hasWork() || redraw) && !gpu.renderAvailable()) {
                waiting = true;
                renderRetryAt = System.nanoTime() + (pacer.hasFreshSource() ? 1_000_000L : 2_000_000L);
                requestDraw();
                return;
            }
            renderRetryAt = 0;
            boolean draw = vsync ? pacer.advance(now, displayPeriod, factor) : pacer.advanceFree(System.nanoTime(), factor);
            if (draw || redraw && !pacer.hasWork()) {
                if (!vsync && !stageFree && pacer.outrunsDisplay(reportedPeriod, factor)) stageFree = true;
                boolean staged = !vsync && stageFree && gpu.ensureStage(outputWidth, outputHeight);
                if (pacer.startPair) gpu.estimate(pacer.front, pacer.target);
                if (draw) {
                    if (!present(pacer.front, pacer.target, pacer.time, staged)) return;
                    pacer.presented();
                    if (reportFps) {
                        beginMeter(System.nanoTime());
                        outputFrames++;
                    }
                } else if (!present(FramePacer.NONE, pacer.front, 1f, staged)) return;
                repaint = false;
            }
            if (demo || pacer.hasWork() || (vsync && probing)) requestDraw();
            else lastVsync = 0;
        } catch (IOException | RuntimeException e) {
            fail(e);
        } finally {
            if (!waiting) gpuBusySince = 0;
        }
    }

    private boolean present(int previous, int current, float phase, boolean staged) {
        gpu.output(previous, current, phase, outputWidth, outputHeight, filter, staged,
                lowLatency, lowLatency && factor > 1);
        gpu.frameDrawn(staged);
        if (!staged) return swapWindow();
        stageDirty = true;
        if (!displayQueued) {
            displayQueued = true;
            choreographer.postFrameCallback(displayFrame);
        }
        return true;
    }

    private void presentLatest() {
        displayQueued = false;
        if (closed || !surfaceActive || suspended || window == null || !stageDirty) return;
        stageDirty = false;
        markGpuBusy();
        try {
            gpu.copyStage();
            swapWindow();
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            gpuBusySince = 0;
        }
    }

    private boolean markGpuBusy() {
        if (gpuBusySince != 0) return true;
        gpuBusySince = SystemClock.uptimeMillis();
        return false;
    }

    boolean outputUsable() {
        if (!outputReady || !surfaceActive) return false;
        long now = SystemClock.uptimeMillis(), busy = gpuBusySince, probe = probeSince;
        return (busy == 0 || now - busy < STALL_MILLIS) && (probe == 0 || now - probe < STALL_MILLIS);
    }

    private void setOutputReady(boolean ready) {
        if (outputReady == ready) return;
        outputReady = ready;
        listener.outputChanged();
    }

    private boolean swapWindow() {
        if (!surfaceActive) return false;
        if (gpu.egl.swap(-1)) {
            setOutputReady(true);
            return true;
        }
        if (window.isValid()) throw new IllegalStateException("Не удалось вывести кадр через EGL");
        detach();
        return false;
    }

    private void measureVsync(long now) {
        long delta = now - lastVsync;
        lastVsync = now;
        if (delta < 4_000_000L || delta > 27_000_000L) {
            slowTicks = 0;
            return;
        }
        if (delta * 5 < displayPeriod * 7) {
            displayPeriod = (displayPeriod * 3 + delta) / 4;
            slowTicks = 0;
        } else if (++slowTicks >= 6) {
            // A system rate limit or missed vsyncs may persist even at 120 Hz.
            displayPeriod = delta;
            slowTicks = 0;
            pacer.reschedule();
        }
    }

    private void offerDemo(long now) throws IOException {
        if (demoStart == 0) demoStart = demoNext = now;
        if (now + 1_000_000L < demoNext) return;
        int slot = pacer.captureSlot(SLOT_COUNT);
        if (slot == FramePacer.NONE) return;
        gpu.demo(slot, (demoNext - demoStart) / (float) SECOND);
        accept(slot, demoNext, System.nanoTime());
        demoNext += DEMO_INTERVAL;
        if (demoNext < now - DEMO_INTERVAL) demoNext = now + DEMO_INTERVAL;
    }

    private void restartStream() {
        cancelDraw();
        cancelDisplayFrame();
        pacer.reset();
        probing = inputQueued = pendingPoll = false;
        probeSince = 0;
        lastColor = null;
        if (gpu != null) {
            gpu.discardProbe();
            gpu.resetHistory();
        }
        handler.removeCallbacks(meter);
        handler.removeCallbacks(probePoll);
        candidateTime = lastLatched = 0;
        demoStart = demoNext = statsStart = lastVsync = 0;
        inputFrames = outputFrames = 0;
        renderRetryAt = 0;
        blackInput = false;
        setOutputReady(false);
        repaint = false;
    }

    private void detach() {
        if (gpu != null) {
            gpu.discardStage();
            gpu.egl.detach();
        }
        window = null;
        outputWidth = outputHeight = 0;
        restartStream();
    }

    private interface Task {
        void run() throws IOException;
    }

    private void enqueue(Task task) {
        if (closed) return;
        handler.post(() -> {
            if (closed) return;
            try {
                task.run();
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    private void fail(Exception e) {
        Log.e(TAG, "GPU pipeline failed", e);
        listener.failed(e.getMessage() == null ? "Ошибка GPU" : e.getMessage());
        close();
    }

    void close() {
        if (closed) return;
        closed = true;
        handler.post(() -> {
            handler.removeCallbacksAndMessages(null);
            cancelDraw();
            cancelDisplayFrame();
            if (gpu != null) gpu.close();
            worker.quitSafely();
        });
    }
}
