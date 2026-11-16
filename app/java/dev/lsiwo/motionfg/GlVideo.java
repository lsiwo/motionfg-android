package dev.lsiwo.motionfg;

import static android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
import static android.opengl.GLES30.*;

import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.view.Surface;

import java.io.IOException;

final class GlVideo implements AutoCloseable {
    static final int NO_RESULT = -1, BLACK = 0, REPEAT = 1, NEW_FRAME = 2;
    private static final int SPAN = 120;
    private static final int SIGNATURE_WIDTH = 64, SIGNATURE_HEIGHT = 36;
    private static final long MAX_HISTORY_PIXELS = 8_294_400L;

    final GlContext egl;
    private final boolean hdr;
    private int sdrWhite = RenderSettings.DEFAULT_WHITE;
    private final int textureLimit;
    private final GlProgram externalProgram, externalYuv, luma, flow, scene, validateMotion;
    private final GlProgram warp, present, probe, signature;
    private final AssetManager assets;
    private final int vertex;
    private final String colorFunctions;
    private final int[] signatures = new int[2];
    private final int quality;
    private final int[] scratch = new int[1], queries = new int[2];
    private final int colorStorage, framebuffer;
    private final int[] colors, fineLuma, coarseLuma;
    private final boolean[] lumaCurrent;
    private final long[] frameIds;
    private final int[] history = new int[2];
    private final long[] reconstructedFrames = new long[2];
    private final long[] renderFences = new long[2];
    private final int[] stage = new int[1];
    private long sequence;
    private long motionA, motionB;
    private int historyWidth, historyHeight, historyIndex;
    private int stageWidth, stageHeight, renderFenceIndex;
    private boolean halfFlow;
    private int signatureIndex;
    private boolean signatureReady;
    private final float[] matrix = new float[16];
    private GlProgram demoProgram;
    private int width, height;
    private CaptureGeometry geometry;
    private int fineW, fineH, coarseW, coarseH, fineGridW, fineGridH, coarseGridW, coarseGridH;
    private int fineVectors, coarseVectors, confidence, cut, tween, queryTexture;
    private int externalTexture;
    private SurfaceTexture stream;
    private Surface input;
    private int lastDataspace = -1;
    private ColorSignal lastSignal = ColorSignal.SRGB;
    private boolean probeHasReference, probePending;

    GlVideo(AssetManager assets, boolean hdrRequested, boolean encoder, int quality, int count,
            boolean external, boolean useProbe) throws IOException {
        this(assets, hdrRequested ? GlContext.PQ : GlContext.SDR, encoder, quality, count, external, useProbe);
    }

    GlVideo(AssetManager assets, int outputMode, boolean encoder, int quality, int count,
            boolean external, boolean useProbe) throws IOException {
        this.quality = quality;
        this.assets = assets;
        colors = new int[count];
        fineLuma = new int[count];
        coarseLuma = new int[count];
        lumaCurrent = new boolean[count];
        frameIds = new long[count];
        egl = new GlContext(outputMode, encoder);
        hdr = outputMode != GlContext.SDR;
        colorStorage = hdr ? GL_RGB10_A2 : GL_RGBA8;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, scratch, 0);
        textureLimit = scratch[0];
        try {
            vertex = GlProgram.compile(GL_VERTEX_SHADER, GlProgram.load(assets, "triangle.vert"), "triangle.vert");
            colorFunctions = GlProgram.load(assets, "color.glsl");
            String glExtensions = glGetString(GL_EXTENSIONS);
            halfFlow = glExtensions != null && (glExtensions.contains("GL_EXT_color_buffer_half_float")
                    || glExtensions.contains("GL_EXT_color_buffer_float"));
            if (external) {
                externalProgram = program("capture.frag");
                GlProgram yuv = null;
                if (encoder && glExtensions != null && glExtensions.contains("GL_EXT_YUV_target")) {
                    try {
                        yuv = program("capture_yuv.frag");
                    } catch (IllegalStateException e) {
                        android.util.Log.w("MotionFG", "HDR YUV shader unavailable", e);
                    }
                }
                externalYuv = yuv;
            } else {
                externalProgram = externalYuv = null;
            }
            luma = program("luma.frag");
            flow = program("flow.frag");
            scene = program("scene.frag");
            validateMotion = program("reliability.frag");
            warp = program("interpolate.frag");
            present = program("present.frag");
            probe = useProbe ? program("probe.frag") : null;
            signature = useProbe ? program("signature.frag") : null;
            glGenFramebuffers(1, scratch, 0);
            framebuffer = scratch[0];
            if (useProbe) {
                glGenQueries(2, queries, 0);
                signatures[0] = texture(GL_RGBA8, SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
                signatures[1] = texture(GL_RGBA8, SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
                queryTexture = texture(GL_R8, SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
            }
            if (external) {
                openInput();
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private GlProgram program(String fragment) throws IOException {
        return new GlProgram(assets, vertex, colorFunctions, fragment);
    }

    int width() { return width; }
    int height() { return height; }
    int maxTextureSize() { return textureLimit; }
    ColorSignal captureSignal() { return lastSignal; }

    void setSdrWhite(int nits) { sdrWhite = nits; }

    boolean setGeometry(CaptureGeometry next) {
        if (next.matches(geometry)) return false;
        if (geometry != null && stream != null) {
            // Use a fresh queue so a buffer from the old rotation cannot use the new crop.
            releaseInput();
            openInput();
        }
        geometry = next;
        size(next.width, next.height);
        if (stream != null) stream.setDefaultBufferSize(next.bufferWidth, next.bufferHeight);
        return true;
    }

    Surface input() { return input; }
    boolean supportsHdrVideoInput() { return externalYuv != null; }
    void onInput(SurfaceTexture.OnFrameAvailableListener listener, Handler handler) {
        SurfaceTexture binding = stream;
        binding.setOnFrameAvailableListener(t -> {
            if (stream == binding) listener.onFrameAvailable(t);
        }, handler);
    }

    private void openInput() {
        glGenTextures(1, scratch, 0);
        externalTexture = scratch[0];
        parameters(GL_TEXTURE_EXTERNAL_OES, externalTexture);
        stream = new SurfaceTexture(externalTexture);
        input = new Surface(stream);
        lastDataspace = -1;
        lastSignal = ColorSignal.SRGB;
    }

    private void releaseInput() {
        if (stream != null) stream.setOnFrameAvailableListener(null);
        if (input != null) input.release();
        if (stream != null) stream.release();
        if (externalTexture != 0) deleteTextures(new int[] { externalTexture });
        input = null;
        stream = null;
        externalTexture = 0;
    }

    void size(int w, int h) {
        if (w == width && h == height) return;
        if (w < 2 || h < 2) throw new IllegalArgumentException(AppText.get(R.string.invalid_resolution));
        if (w > textureLimit || h > textureLimit) throw new IllegalArgumentException(AppText.get(R.string.resolution_exceeds_the_gpu_limit));

        deleteTextures(colors);
        deleteTextures(fineLuma);
        deleteTextures(coarseLuma);
        deleteTextures(new int[] { fineVectors, coarseVectors, confidence, cut, tween });
        discardHistory();
        motionA = motionB = 0;
        width = w;
        height = h;
        float analysisScale = Math.min(1f, 720f / Math.min(w, h));
        int motionW = Math.max(2, Math.round(w * analysisScale));
        int motionH = Math.max(2, Math.round(h * analysisScale));
        fineW = ceil(motionW, 4);
        fineH = ceil(motionH, 4);
        coarseW = ceil(motionW, 8);
        coarseH = ceil(motionH, 8);
        int grid = quality == RenderSettings.FAST ? 42 : 40;
        coarseGridW = ceil(motionW, grid);
        coarseGridH = ceil(motionH, grid);
        int fineGrid = quality == RenderSettings.FAST ? 27 : 16;
        fineGridW = ceil(motionW, fineGrid);
        fineGridH = ceil(motionH, fineGrid);
        for (int i = 0; i < colors.length; i++) {
            colors[i] = texture(colorStorage, w, h);
            fineLuma[i] = coarseLuma[i] = 0;
            lumaCurrent[i] = false;
        }
        coarseVectors = fineVectors = confidence = cut = tween = 0;
        target(colors[0], w, h);
        if (glGetError() != GL_NO_ERROR || glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(AppText.get(R.string.gpu_could_not_allocate_buffers_for) + w + " × " + h);
        }
        if (stream != null && geometry == null) stream.setDefaultBufferSize(w, h);
    }

    long latch() {
        stream.updateTexImage();
        stream.getTransformMatrix(matrix);
        if (geometry != null) geometry.cropTransform(matrix);
        return stream.getTimestamp();
    }

    int capture(int slot, ColorSignal supplied, boolean decoder) {
        int space = stream.getDataSpace();
        if (space != lastDataspace) {
            lastDataspace = space;
            lastSignal = ColorSignal.capture(space);
        }
        ColorSignal signal = supplied == null ? lastSignal : supplied;
        GlProgram p = decoder && signal.hdr() && externalYuv != null ? externalYuv : externalProgram;
        target(colors[slot], width, height);
        p.use();
        p.texture("source", 0, GL_TEXTURE_EXTERNAL_OES, externalTexture);
        glUniformMatrix4fv(p.location("transform"), 1, false, matrix, 0);
        p.integer("transfer", signal.transfer);
        p.integer("gamut", signal.gamut);
        p.integer("hdr", hdr ? 1 : 0);
        p.scalar("sdrWhite", sdrWhite);
        p.integer("limited", signal.limited ? 1 : 0);
        draw();
        lumaCurrent[slot] = false;
        frameIds[slot] = ++sequence;
        return colors[slot];
    }

    void demo(int slot, float time) throws IOException {
        if (demoProgram == null) demoProgram = program("demo.frag");
        target(colors[slot], width, height);
        demoProgram.use();
        demoProgram.scalar("time", time);
        demoProgram.integer("hdr", hdr ? 1 : 0);
        demoProgram.scalar("sdrWhite", sdrWhite);
        draw();
        lumaCurrent[slot] = false;
        frameIds[slot] = ++sequence;
    }

    void submitProbe() {
        int current = signatures[signatureIndex];
        target(current, SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
        signature.use();
        signature.texture("source", 0, GL_TEXTURE_EXTERNAL_OES, externalTexture);
        glUniformMatrix4fv(signature.location("transform"), 1, false, matrix, 0);
        draw();

        target(queryTexture, SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
        probe.use();
        probe.texture("currentFrame", 0, GL_TEXTURE_2D, current);
        probe.texture("previousFrame", 1, GL_TEXTURE_2D, signatures[1 - signatureIndex]);
        glColorMask(false, false, false, false);
        probe.integer("testBlack", 1);
        probe.scalar("level", 0.0079f);
        glBeginQuery(GL_ANY_SAMPLES_PASSED, queries[0]);
        draw();
        glEndQuery(GL_ANY_SAMPLES_PASSED);
        probeHasReference = signatureReady;
        if (probeHasReference) {
            probe.integer("testBlack", 0);
            probe.scalar("level", 0.0039f);
            glBeginQuery(GL_ANY_SAMPLES_PASSED, queries[1]);
            draw();
            glEndQuery(GL_ANY_SAMPLES_PASSED);
        }
        glColorMask(true, true, true, true);
        probePending = true;
        glFlush();
    }

    int pollProbe() {
        if (!probePending) return NO_RESULT;
        int last = probeHasReference ? 1 : 0;
        glGetQueryObjectuiv(queries[last], GL_QUERY_RESULT_AVAILABLE, scratch, 0);
        if (scratch[0] == 0) return NO_RESULT;
        probePending = false;
        glGetQueryObjectuiv(queries[0], GL_QUERY_RESULT, scratch, 0);
        if (scratch[0] == 0) return BLACK;
        if (!probeHasReference) return NEW_FRAME;
        glGetQueryObjectuiv(queries[1], GL_QUERY_RESULT, scratch, 0);
        return scratch[0] == 0 ? REPEAT : NEW_FRAME;
    }

    void acceptProbe() {
        signatureReady = true;
        signatureIndex = 1 - signatureIndex;
    }

    void discardProbe() {
        probePending = signatureReady = false;
    }

    private void storeLuma(int slot) {
        if (lumaCurrent[slot]) return;
        if (fineLuma[slot] == 0) {
            fineLuma[slot] = texture(GL_R8, fineW, fineH);
            coarseLuma[slot] = texture(GL_R8, coarseW, coarseH);
        }
        downsample(colors[slot], fineLuma[slot], fineW, fineH, false);
        downsample(fineLuma[slot], coarseLuma[slot], coarseW, coarseH, true);
        lumaCurrent[slot] = true;
    }

    private void downsample(int source, int output, int w, int h, boolean monochrome) {
        target(output, w, h);
        luma.use();
        luma.texture("source", 0, GL_TEXTURE_2D, source);
        luma.vector("tap", 0.23f / w, 0.23f / h);
        luma.integer("monochrome", monochrome ? 1 : 0);
        draw();
    }

    void estimate(int a, int b) {
        if (motionA != 0 && motionA == frameIds[a] && motionB == frameIds[b]) return;
        if (fineVectors == 0) {
            coarseVectors = motionTexture(coarseGridW, coarseGridH);
            fineVectors = motionTexture(fineGridW, fineGridH);
            cut = texture(GL_R8, 1, 1);
            confidence = texture(GL_RG8, fineGridW * 2, fineGridH * 2);
        }
        storeLuma(a);
        storeLuma(b);
        float motionRange = motionRange();
        target(coarseVectors, coarseGridW, coarseGridH);
        flow.use();
        flow.vector("span", motionRange / width, motionRange / height);
        flow.integer("quality", quality);
        bindFlow(coarseLuma[a], coarseLuma[b], colors[a], colors[a], 0);
        draw();

        target(cut, 1, 1);
        scene.use();
        scene.texture("before", 0, GL_TEXTURE_2D, coarseLuma[a]);
        scene.texture("after", 1, GL_TEXTURE_2D, coarseLuma[b]);
        scene.texture("coarseMotion", 2, GL_TEXTURE_2D, coarseVectors);
        scene.vector("span", motionRange / width, motionRange / height);
        draw();

        target(fineVectors, fineGridW, fineGridH);
        flow.use();
        bindFlow(fineLuma[a], fineLuma[b], coarseVectors, cut, 1);
        draw();

        target(confidence, fineGridW * 2, fineGridH * 2);
        validateMotion.use();
        validateMotion.texture("before", 0, GL_TEXTURE_2D, fineLuma[a]);
        validateMotion.texture("after", 1, GL_TEXTURE_2D, fineLuma[b]);
        validateMotion.texture("motion", 2, GL_TEXTURE_2D, fineVectors);
        validateMotion.texture("scene", 3, GL_TEXTURE_2D, cut);
        validateMotion.vector("span", motionRange / width, motionRange / height);
        validateMotion.vector("pixel", 1f / fineW, 1f / fineH);
        draw();
        motionA = frameIds[a];
        motionB = frameIds[b];
    }

    private int motionTexture(int w, int h) {
        if (halfFlow) {
            int candidate = texture(GL_RGBA16F, w, h);
            target(candidate, w, h);
            if (glGetError() == GL_NO_ERROR && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                return candidate;
            }
            deleteTextures(new int[] { candidate });
            halfFlow = false;
        }
        return texture(GL_RGBA8, w, h);
    }

    private float motionRange() {
        return SPAN * Math.max(1f, Math.min(width, height) / 720f);
    }

    private void bindFlow(int a, int b, int previous, int cutMap, int refine) {
        flow.texture("before", 0, GL_TEXTURE_2D, a);
        flow.texture("after", 1, GL_TEXTURE_2D, b);
        flow.texture("prior", 2, GL_TEXTURE_2D, previous);
        flow.texture("scene", 3, GL_TEXTURE_2D, cutMap);
        flow.integer("refine", refine);
    }

    private int interpolate(int a, int b, float t) {
        if (tween == 0) tween = texture(colorStorage, width, height);
        target(tween, width, height);
        drawWarp(colors[a], colors[b], t, hdr ? GlContext.PQ : GlContext.SDR, false);
        return tween;
    }

    private void drawWarp(int first, int second, float t, int outputMode, boolean prediction) {
        warp.use();
        warp.texture("before", 0, GL_TEXTURE_2D, first);
        warp.texture("after", 1, GL_TEXTURE_2D, second);
        warp.texture("motion", 2, GL_TEXTURE_2D, fineVectors);
        warp.texture("scene", 3, GL_TEXTURE_2D, cut);
        warp.texture("reliability", 4, GL_TEXTURE_2D, confidence);
        float motionRange = motionRange();
        warp.vector("span", motionRange / width, motionRange / height);
        warp.vector("sourceSize", width, height);
        warp.scalar("phase", t);
        warp.integer("quality", quality);
        warp.integer("hdr", hdr ? 1 : 0);
        warp.integer("hdrWindow", outputMode);
        warp.integer("predictive", prediction ? 1 : 0);
        draw();
    }

    void output(int prior, int slot, float phase, int w, int h, int filter, boolean staged,
            boolean immediate, boolean reuseScale) {
        boolean enlarging = w > width || h > height;
        if (!enlarging) filter = RenderSettings.LINEAR;
        boolean generated = phase < 1f;
        int first = 0, second;
        if (enlarging && (filter == RenderSettings.TEMPORAL || reuseScale && filter != RenderSettings.LINEAR)
                && ensureHistory(w, h)) {
            if (generated && !immediate) {
                first = reconstructSource(w, h, prior, FramePacer.NONE, filter, true);
            }
            second = reconstructSource(w, h, slot, prior, filter, !immediate);
            filter = RenderSettings.LINEAR;
        } else {
            if (generated && !immediate && filter == RenderSettings.LINEAR) first = colors[prior];
            second = generated && !immediate && first == 0 ? interpolate(prior, slot, phase) : colors[slot];
        }
        if (staged) {
            target(stage[0], w, h);
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, w, h);
        }
        if (generated && immediate) {
            drawWarp(colors[prior], second, phase, egl.mode, true);
        } else if (first != 0) {
            drawWarp(first, second, phase, egl.mode, false);
        } else {
            prepareImage(second, filter, egl.mode);
            present.integer("historyEnabled", 0);
            draw();
        }
    }

    boolean ensureStage(int w, int h) {
        if (stageWidth == w && stageHeight == h) return stage[0] != 0;
        discardStage();
        stageWidth = w;
        stageHeight = h;
        int format = egl.mode == GlContext.SCRGB ? GL_RGBA16F : colorStorage;
        stage[0] = texture(format, w, h);
        target(stage[0], w, h);
        if (glGetError() == GL_NO_ERROR && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            return true;
        }
        deleteTextures(stage);
        stage[0] = 0;
        return false;
    }

    boolean renderAvailable() {
        long fence = renderFences[renderFenceIndex];
        if (fence == 0) return true;
        if (glClientWaitSync(fence, 0, 0L) == GL_TIMEOUT_EXPIRED) return false;
        glDeleteSync(fence);
        renderFences[renderFenceIndex] = 0;
        return true;
    }

    void frameDrawn(boolean staged) {
        renderFences[renderFenceIndex] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        renderFenceIndex ^= 1;
        if (staged) glFlush();
    }

    void copyStage() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, stageWidth, stageHeight);
        prepareImage(stage[0], RenderSettings.LINEAR, GlContext.RAW);
        present.integer("historyEnabled", 0);
        draw();
    }

    void discardStage() {
        if (stage[0] != 0) deleteTextures(stage);
        stage[0] = stageWidth = stageHeight = 0;
    }

    private boolean ensureHistory(int w, int h) {
        if (w > textureLimit || h > textureLimit || (long) w * h > MAX_HISTORY_PIXELS) return false;
        if (historyWidth == w && historyHeight == h) return history[0] != 0;
        discardHistory();
        historyWidth = w;
        historyHeight = h;
        history[0] = texture(colorStorage, w, h);
        history[1] = texture(colorStorage, w, h);
        target(history[1], w, h);
        if (glGetError() != GL_NO_ERROR || glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            deleteTextures(history);
            history[0] = history[1] = 0;
            return false;
        }
        return true;
    }

    void resetHistory() { reconstructedFrames[0] = reconstructedFrames[1] = 0; }

    void discardHistory() {
        deleteTextures(history);
        history[0] = history[1] = 0;
        historyWidth = historyHeight = 0;
        resetHistory();
    }

    private int reconstructSource(int w, int h, int slot, int priorSlot, int filter, boolean temporal) {
        long id = frameIds[slot];
        int cached = historySlot(id);
        if (cached >= 0) return history[cached];
        long previousId = priorSlot < 0 ? 0 : frameIds[priorSlot];
        int previousBuffer = historySlot(previousId);
        boolean reusable = filter == RenderSettings.TEMPORAL && previousBuffer >= 0;
        if (reusable && temporal) estimate(priorSlot, slot);
        int next = 1 - (previousBuffer >= 0 ? previousBuffer : historyIndex);
        target(history[next], w, h);
        prepareImage(colors[slot], filter, hdr ? GlContext.PQ : GlContext.SDR);
        present.integer("historyEnabled", reusable ? (temporal ? 1 : 2) : 0);
        present.texture("historyImage", 1, GL_TEXTURE_2D, reusable ? history[previousBuffer] : colors[slot]);
        present.texture("previousImage", 2, GL_TEXTURE_2D, reusable ? colors[priorSlot] : colors[slot]);
        present.texture("field", 3, GL_TEXTURE_2D, reusable && temporal ? fineVectors : colors[slot]);
        present.texture("reliability", 4, GL_TEXTURE_2D, reusable && temporal ? confidence : colors[slot]);
        present.texture("scene", 5, GL_TEXTURE_2D, reusable && temporal ? cut : colors[slot]);
        if (reusable && temporal) {
            float range = motionRange();
            present.vector("span", range / width, range / height);
        }
        draw();
        historyIndex = next;
        reconstructedFrames[next] = id;
        return history[next];
    }

    private int historySlot(long id) {
        if (id == 0) return FramePacer.NONE;
        if (reconstructedFrames[0] == id) return 0;
        return reconstructedFrames[1] == id ? 1 : FramePacer.NONE;
    }

    private void prepareImage(int tex, int filter, int outputMode) {
        present.use();
        present.texture("image", 0, GL_TEXTURE_2D, tex);
        present.integer("filterMode", filter);
        present.integer("hdr", hdr ? 1 : 0);
        present.integer("hdrWindow", outputMode);
    }

    void target(int texture, int w, int h) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glViewport(0, 0, w, h);
    }

    int texture(int format, int w, int h) {
        glGenTextures(1, scratch, 0);
        int id = scratch[0];
        parameters(GL_TEXTURE_2D, id);
        glTexStorage2D(GL_TEXTURE_2D, 1, format, w, h);
        return id;
    }

    private static void parameters(int type, int id) {
        glBindTexture(type, id);
        glTexParameteri(type, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(type, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(type, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(type, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private static void deleteTextures(int[] ids) {
        glDeleteTextures(ids.length, ids, 0);
    }

    private static int ceil(int value, int grid) { return (value + grid - 1) / grid; }
    static void draw() { glDrawArrays(GL_TRIANGLES, 0, 3); }

    @Override public void close() {
        releaseInput();
        discardStage();
        egl.close();
    }
}
