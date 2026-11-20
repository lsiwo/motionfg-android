package dev.lsiwo.motionfg;

import static android.opengl.GLES30.*;

import android.content.res.AssetManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Experimental Qualcomm SGSR2 two-pass GLES 3.2 backend.
 *
 * This MediaProjection adapter has no access to native game depth/camera data.
 * It supplies a far-plane depth fallback and Motion FG optical-flow velocity;
 * this is explicitly an approximation, not engine-native SGSR2 input.
 */
final class Sgsr2Pipeline implements AutoCloseable {
    private static final int GL_R32F = 0x822E;
    private static final int GL_RGBA16F = 0x881A;
    private static final int MAX_PIXELS = 8_294_400;

    private final int convertProgram, upscaleProgram, velocityProgram;
    private final int framebuffer;
    private final int uniformBuffer;
    private final int[] textures = new int[6]; // depth, velocity, motionDepth, history0, history1, spare
    private int renderWidth, renderHeight, outputWidth, outputHeight;
    private int historyRead;
    private boolean historyValid;
    private boolean closed;

    Sgsr2Pipeline(AssetManager assets) throws IOException {
        String version = glGetString(GL_VERSION);
        if (version == null || !version.contains("OpenGL ES 3.2")) {
            throw new IllegalStateException("SGSR2 requires an OpenGL ES 3.2 context");
        }
        convertProgram = link(assets, "sgsr2_convert.frag", "SGSR2 convert");
        upscaleProgram = link(assets, "sgsr2_upscale.frag", "SGSR2 upscale");
        velocityProgram = link(assets, "sgsr2_velocity.frag", "SGSR2 velocity adapter");

        int[] ids = new int[1];
        glGenFramebuffers(1, ids, 0);
        framebuffer = ids[0];
        glGenBuffers(1, ids, 0);
        uniformBuffer = ids[0];
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        glBufferData(GL_UNIFORM_BUFFER, 320, null, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, uniformBuffer);
        bindParams(convertProgram);
        bindParams(upscaleProgram);
        bindSampler(convertProgram, "InputDepth", 0);
        bindSampler(convertProgram, "InputVelocity", 1);
        bindSampler(upscaleProgram, "PrevOutput", 0);
        bindSampler(upscaleProgram, "MotionDepthClipAlphaBuffer", 1);
        bindSampler(upscaleProgram, "InputColor", 2);
        bindSampler(velocityProgram, "motionField", 0);
        bindSampler(velocityProgram, "confidenceField", 1);
    }

    private static int link(AssetManager assets, String fragmentName, String label) throws IOException {
        int vs = GlProgram.compile(GL_VERTEX_SHADER,
                GlProgram.load(assets, "sgsr2_fullscreen.vert"), "sgsr2_fullscreen.vert");
        int fs = GlProgram.compile(GL_FRAGMENT_SHADER,
                GlProgram.load(assets, fragmentName), fragmentName);
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDetachShader(program, vs);
        glDetachShader(program, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
        int[] status = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(label + " link failed: " + log);
        }
        return program;
    }

    private static void bindParams(int program) {
        int block = glGetUniformBlockIndex(program, "Params");
        if (block != 0xFFFFFFFF) glUniformBlockBinding(program, block, 0);
    }

    private static void bindSampler(int program, String name, int unit) {
        glUseProgram(program);
        int location = glGetUniformLocation(program, name);
        if (location >= 0) glUniform1i(location, unit);
    }

    int process(int inputColor, int motionField, int confidenceField,
            int srcW, int srcH, int dstW, int dstH,
            float spanX, float spanY, boolean reset) {
        if (closed || inputColor == 0 || motionField == 0 || confidenceField == 0
                || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0
                || (long) dstW * dstH > MAX_PIXELS) return 0;
        if (!ensure(srcW, srcH, dstW, dstH)) return 0;
        if (reset || !historyValid) clearHistory();
        updateParams(srcW, srcH, dstW, dstH, reset || !historyValid);

        // Build clip-space velocity from Motion FG's bidirectional optical flow.
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        attach(textures[1]);
        glViewport(0, 0, srcW, srcH);
        glUseProgram(velocityProgram);
        bindTexture(0, motionField);
        bindTexture(1, confidenceField);
        int span = glGetUniformLocation(velocityProgram, "motionSpan");
        glUniform2f(span, spanX, spanY);
        draw();

        // SGSR2 Convert pass. A constant far-plane depth is used because
        // MediaProjection cannot expose the game's real depth attachment.
        attach(textures[2]);
        glViewport(0, 0, srcW, srcH);
        glUseProgram(convertProgram);
        bindTexture(0, textures[0]);
        bindTexture(1, textures[1]);
        draw();

        // SGSR2 Upscale pass with display-resolution ping-pong history.
        int write = 1 - historyRead;
        attach(textures[3 + write]);
        glViewport(0, 0, dstW, dstH);
        glUseProgram(upscaleProgram);
        bindTexture(0, textures[3 + historyRead]);
        bindTexture(1, textures[2]);
        bindTexture(2, inputColor);
        draw();

        historyRead = write;
        historyValid = true;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return textures[3 + historyRead];
    }

    private boolean ensure(int sw, int sh, int dw, int dh) {
        if (sw == renderWidth && sh == renderHeight && dw == outputWidth && dh == outputHeight
                && textures[0] != 0) return true;
        releaseTextures();
        renderWidth = sw;
        renderHeight = sh;
        outputWidth = dw;
        outputHeight = dh;
        textures[0] = texture(GL_R32F, sw, sh, GL_RED, GL_FLOAT);
        textures[1] = texture(GL_RGBA8, sw, sh, GL_RGBA, GL_UNSIGNED_BYTE);
        textures[2] = texture(GL_RGBA16F, sw, sh, GL_RGBA, GL_HALF_FLOAT);
        textures[3] = texture(GL_RGBA8, dw, dh, GL_RGBA, GL_UNSIGNED_BYTE);
        textures[4] = texture(GL_RGBA8, dw, dh, GL_RGBA, GL_UNSIGNED_BYTE);
        if (!complete(textures[2], sw, sh) || !complete(textures[3], dw, dh)
                || !complete(textures[4], dw, dh)) {
            releaseTextures();
            return false;
        }
        FloatBuffer farDepth = ByteBuffer.allocateDirect(sw * sh * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < sw * sh; i++) farDepth.put(1.0f);
        farDepth.position(0);
        glBindTexture(GL_TEXTURE_2D, textures[0]);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, sw, sh, GL_RED, GL_FLOAT, farDepth);
        historyRead = 0;
        historyValid = false;
        return glGetError() == GL_NO_ERROR;
    }

    private int texture(int internal, int w, int h, int format, int type) {
        int[] id = new int[1];
        glGenTextures(1, id, 0);
        glBindTexture(GL_TEXTURE_2D, id[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null);
        return id[0];
    }

    private boolean complete(int texture, int w, int h) {
        attach(texture);
        return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    }

    private void attach(int texture) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    }

    private void clearHistory() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, outputWidth, outputHeight);
        glClearColor(0f, 0f, 0f, 0f);
        attach(textures[3]);
        glClear(GL_COLOR_BUFFER_BIT);
        attach(textures[4]);
        glClear(GL_COLOR_BUFFER_BIT);
        historyRead = 0;
        historyValid = false;
    }

    private void updateParams(int sw, int sh, int dw, int dh, boolean reset) {
        ByteBuffer b = ByteBuffer.allocateDirect(320).order(ByteOrder.nativeOrder());
        // clipToPrevClip[4] = identity; engine camera matrices are unavailable.
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) b.putFloat(i * 16 + j * 4, i == j ? 1f : 0f);
        }
        b.position(256);
        b.putFloat(sw).putFloat(sh);
        b.putFloat(dw).putFloat(dh);
        b.putFloat(1f / sw).putFloat(1f / sh);
        b.putFloat(1f / dw).putFloat(1f / dh);
        b.putFloat(0f).putFloat(0f); // no temporal jitter in MediaProjection
        b.putFloat((float) dw / sw).putFloat((float) dh / sh);
        b.putFloat(1.0f); // conservative horizontal FOV fallback
        b.putFloat(0.05f);
        b.putFloat(reset ? 1f : 0f);
        b.putInt(1); // same camera; camera matrices are not exposed by capture
        b.position(0);
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, 320, b);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, uniformBuffer);
    }

    private static void bindTexture(int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    private static void draw() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void releaseTextures() {
        glDeleteTextures(textures.length, textures, 0);
        for (int i = 0; i < textures.length; i++) textures[i] = 0;
        historyValid = false;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        releaseTextures();
        glDeleteProgram(convertProgram);
        glDeleteProgram(upscaleProgram);
        glDeleteProgram(velocityProgram);
        glDeleteFramebuffers(1, new int[] { framebuffer }, 0);
        glDeleteBuffers(1, new int[] { uniformBuffer }, 0);
    }
}
