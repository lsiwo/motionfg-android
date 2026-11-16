package dev.lsiwo.motionfg;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class VideoExport implements AutoCloseable {
    interface Progress {
        void update(int percent, String line);
    }

    static final int AUTO_COLOR = 2;
    static final String H264 = MediaFormat.MIMETYPE_VIDEO_AVC;
    static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;

    private final Context context;
    private final AtomicBoolean cancelled;
    private final Progress progress;
    private final MediaExtractor source = new MediaExtractor();
    private final MediaCodec.BufferInfo frame = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo encoded = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo audioSample = new MediaCodec.BufferInfo();
    private final Semaphore decoderFrame = new Semaphore(0);
    private MediaExtractor audio;
    private MediaCodec decoder, encoder;
    private MediaMuxer muxer;
    private Surface encoderSurface;
    private GlVideo gpu;
    private HandlerThread decoderEvents;
    private boolean muxerStarted, encoderEnded;
    private int videoTrack = -1, audioTrack = -1;
    private int outputWidth, outputHeight, count;
    private long firstPts, lastSource, lastSpan, lastWritten = -1, progressAt;
    private int previous = -1;
    private boolean exportingHdr;
    private ByteBuffer audioBuffer = ByteBuffer.allocateDirect(65_536);

    VideoExport(Context context, AtomicBoolean cancelled, Progress progress) {
        this.context = context;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    String run(Uri input, File destination, RenderSettings settings, int exportEdge, int color, boolean keepAudio)
            throws IOException, InterruptedException {
        source.setDataSource(context, input, null);
        int track = trackOf(source, "video/");
        if (track < 0) throw new IOException(AppText.get(R.string.the_file_has_no_video_track));
        MediaFormat format = source.getTrackFormat(track);
        source.selectTrack(track);
        String inputMime = format.getString(MediaFormat.KEY_MIME);
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        int rotation = format.getInteger(MediaFormat.KEY_ROTATION, 0);
        long duration = format.getLong(MediaFormat.KEY_DURATION, 0L);
        ColorSignal localColor = ColorSignal.video(format);
        boolean hdr = color == RenderSettings.HDR10 || (color == AUTO_COLOR && localColor.hdr());
        exportingHdr = hdr;
        int sourceRate = sourceRate(format, source);
        int factor = settings.factor;
        int outputRate = sourceRate * factor;
        String outputMime = hdr ? HEVC : H264;
        double scale = exportEdge == 0 ? 1.0 : (double) exportEdge / Math.min(width, height);
        outputWidth = alignEven(width * scale);
        outputHeight = alignEven(height * scale);

        MediaFormat toEncode = MediaFormat.createVideoFormat(outputMime, outputWidth, outputHeight);
        toEncode.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        toEncode.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                hdr ? MediaFormat.COLOR_STANDARD_BT2020 : MediaFormat.COLOR_STANDARD_BT709);
        toEncode.setInteger(MediaFormat.KEY_COLOR_TRANSFER,
                hdr ? MediaFormat.COLOR_TRANSFER_ST2084 : MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        toEncode.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        toEncode.setInteger(MediaFormat.KEY_FRAME_RATE, outputRate);
        toEncode.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        toEncode.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        if (hdr) {
            toEncode.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrMetadata(format, localColor));
        }
        MediaCodecInfo encodeInfo = findEncoder(outputMime, outputWidth, outputHeight, outputRate, hdr);
        if (encodeInfo == null) {
            String type = hdr ? "HEVC Main10 HDR10" : "H.264";
            throw new IOException(AppText.get(R.string.codec) + type + AppText.get(R.string.does_not_support) + outputWidth + " × "
                    + outputHeight + AppText.get(R.string.at) + outputRate + AppText.get(R.string.fps_reduce_the_resolution_or_frame_multiplier));
        }
        MediaCodecInfo.VideoCapabilities capabilities = encodeInfo.getCapabilitiesForType(outputMime)
                .getVideoCapabilities();
        if (hdr) toEncode.setInteger(MediaFormat.KEY_PROFILE, hdrProfile(encodeInfo));
        int requestedBitrate = (int) Math.max(900_000, Math.min(90_000_000L,
                Math.round(outputWidth * (long) outputHeight * outputRate * (hdr ? 0.107 : 0.130))));
        toEncode.setInteger(MediaFormat.KEY_BIT_RATE, capabilities.getBitrateRange().clamp(requestedBitrate));

        String type = hdr ? "HDR10" : "SDR";
        progress.update(-1, outputWidth + " × " + outputHeight + " · " + outputRate + " FPS · " + type);
        check();
        gpu = new GlVideo(context.getAssets(), hdr, true, settings.motion, 2, true, false);
        if (localColor.hdr() && !gpu.supportsHdrVideoInput()) {
            if (hdr) throw new IOException(AppText.get(R.string.the_gpu_driver_could_not_open_a_10_bit_yuv_surface_for_hdr));
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        }
        gpu.size(width, height);
        decoderEvents = new HandlerThread(AppText.get(R.string.motionfg_decoder));
        decoderEvents.start();
        gpu.onInput(t -> decoderFrame.release(), new Handler(decoderEvents.getLooper()));

        muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation);
        if (keepAudio) configureAudio(input);
        encoder = MediaCodec.createByCodecName(encodeInfo.getName());
        encoder.configure(toEncode, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        gpu.egl.attach(encoderSurface);
        gpu.egl.swapInterval(0);
        encoder.start();

        format.setInteger(MediaFormat.KEY_ROTATION, 0);
        String decoderName = findDecoder(inputMime, format);
        if (decoderName == null) throw new IOException(AppText.get(R.string.hardware_decoding_is_unavailable_for) + inputMime);
        decoder = MediaCodec.createByCodecName(decoderName);
        decoder.configure(format, gpu.input(), null, 0);
        boolean codecToneMaps = localColor.hdr() && !gpu.supportsHdrVideoInput()
                && decoder.getInputFormat().getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, 0)
                == MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
        if (localColor.hdr() && !gpu.supportsHdrVideoInput() && !codecToneMaps) {
            throw new IOException(AppText.get(R.string.the_decoder_does_not_support_hdr_to_sdr_conversion));
        }
        decoder.start();

        boolean inputEnded = false;
        boolean decodedEnded = false;
        ColorSignal signal = codecToneMaps ? ColorSignal.SDR_VIDEO : localColor;
        long stalled = SystemClock.elapsedRealtime() + 18_000;
        while (!decodedEnded) {
            check();
            if (!inputEnded) inputEnded = feedDecoder();
            int index = decoder.dequeueOutputBuffer(frame, 10_000);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out = decoder.getOutputFormat();
                if (out.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    ColorSignal actual = ColorSignal.video(out);
                    if (!codecToneMaps || !actual.hdr()) signal = actual;
                }
            } else if (index >= 0) {
                long pts = frame.presentationTimeUs;
                decodedEnded = (frame.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean render = (frame.size > 0 || !decodedEnded)
                        && (frame.flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG | MediaCodec.BUFFER_FLAG_DECODE_ONLY)) == 0
                        && pts >= 0 && (previous < 0 || pts > lastSource);
                decoder.releaseOutputBuffer(index, render);
                if (render) {
                    awaitDecodedFrame();
                    gpu.latch();
                    emitDecoded(pts, settings, signal, duration);
                    stalled = SystemClock.elapsedRealtime() + 18_000;
                }
            }
            drain(false);
            if (SystemClock.elapsedRealtime() > stalled) throw new IOException(AppText.get(R.string.the_decoder_stopped_producing_frames));
        }
        if (previous < 0) throw new IOException(AppText.get(R.string.the_decoder_did_not_produce_any_frames));
        long clipEnd = finishTimeline(duration, sourceRate, settings);
        encoder.signalEndOfInputStream();
        drain(true);
        if (!muxerStarted || lastWritten < 0) throw new IOException(AppText.get(R.string.the_encoder_did_not_write_a_video_track));
        check();
        MediaCodec.BufferInfo end = new MediaCodec.BufferInfo();
        end.set(0, 0, Math.max(lastWritten + 1, clipEnd), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(videoTrack, ByteBuffer.allocate(0), end);
        copyAudio(clipEnd - 1);
        muxer.stop();
        muxerStarted = false;
        return outputWidth + " × " + outputHeight + " · " + count + AppText.get(R.string.frames) + type;
    }

    private static int sourceRate(MediaFormat input, MediaExtractor samples) {
        if (input.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            float rate;
            try { rate = input.getFloat(MediaFormat.KEY_FRAME_RATE); }
            catch (ClassCastException e) { rate = input.getInteger(MediaFormat.KEY_FRAME_RATE); }
            if (rate >= 1 && rate <= 1000) return Math.round(rate);
        }
        long[] times = new long[48];
        int count = 0;
        for (; count < times.length && samples.getSampleTime() >= 0; count++) {
            times[count] = samples.getSampleTime();
            samples.advance();
        }
        samples.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        if (count < 2) return 30;
        Arrays.sort(times, 0, count);
        long[] differences = new long[count - 1];
        int occupied = 0;
        for (int i = 1; i < count; i++) if (times[i] > times[i - 1]) {
            differences[occupied++] = times[i] - times[i - 1];
        }
        if (occupied == 0) return 30;
        Arrays.sort(differences, 0, occupied);
        return (int) Math.max(1, Math.min(480, Math.round(1_000_000.0 / differences[occupied / 2])));
    }

    private static int alignEven(double n) {
        return Math.max(2, ((int) Math.round(n)) & ~1);
    }

    private static int trackOf(MediaExtractor e, String prefix) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String mime = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static MediaCodecInfo findEncoder(String mime, int width, int height, int fps, boolean hdr) {
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!codec.isEncoder() || !codec.isHardwareAccelerated()) continue;
            try {
                MediaCodecInfo.CodecCapabilities cap = codec.getCapabilitiesForType(mime);
                if (!cap.getVideoCapabilities().areSizeAndRateSupported(width, height, fps)) continue;
                if (hdr && !cap.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)) continue;
                boolean surface = false;
                for (int f : cap.colorFormats) surface |= f == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                if (!surface) continue;
                if (hdr && hdrProfile(codec) == 0) continue;
                return codec;
            } catch (IllegalArgumentException ignored) {
                // This component does not advertise the requested MIME type.
            }
        }
        return null;
    }

    private static int hdrProfile(MediaCodecInfo codec) {
        int main10 = 0;
        for (MediaCodecInfo.CodecProfileLevel p : codec.getCapabilitiesForType(HEVC).profileLevels) {
            if (p.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) return p.profile;
            if (p.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = p.profile;
        }
        return main10;
    }

    private static String findDecoder(String mime, MediaFormat input) {
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (codec.isEncoder() || !codec.isHardwareAccelerated()) continue;
            try {
                if (codec.getCapabilitiesForType(mime).isFormatSupported(input)) return codec.getName();
            } catch (IllegalArgumentException ignored) {
                // This component does not advertise the requested MIME type.
            }
        }
        return null;
    }

    private static ByteBuffer hdrMetadata(MediaFormat source, ColorSignal color) {
        ByteBuffer given = source.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO);
        if (color.transfer == ColorSignal.PQ && given != null && given.remaining() == 25) return given.duplicate();
        int peak = color.hdr() ? 1000 : 203;
        ByteBuffer values = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
        values.put((byte) 0);
        for (int value : new int[] { 35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450,
                peak, 50, peak, color.hdr() ? 400 : 203 }) values.putShort((short) value);
        values.flip();
        return values;
    }

    private void configureAudio(Uri input) throws IOException {
        audio = new MediaExtractor();
        audio.setDataSource(context, input, null);
        int index = trackOf(audio, "audio/");
        if (index < 0) return;
        MediaFormat a = audio.getTrackFormat(index);
        a.removeKey(MediaFormat.KEY_DURATION);
        try {
            audioTrack = muxer.addTrack(a);
        } catch (IllegalArgumentException e) {
            throw new IOException(AppText.get(R.string.mp4_does_not_support_the_audio_track) + a.getString(MediaFormat.KEY_MIME)
                    + AppText.get(R.string.you_can_turn_off_keep_audio_for_this_file), e);
        }
        audio.selectTrack(index);
    }

    private boolean feedDecoder() {
        int index = decoder.dequeueInputBuffer(0);
        if (index < 0) return false;
        ByteBuffer buffer = decoder.getInputBuffer(index);
        if (buffer == null) throw new IllegalStateException(AppText.get(R.string.no_decoder_input_buffer));
        buffer.clear();
        int size = source.readSampleData(buffer, 0);
        if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        decoder.queueInputBuffer(index, 0, size, source.getSampleTime(), 0);
        source.advance();
        return false;
    }

    private void awaitDecodedFrame() throws InterruptedException, IOException {
        long end = SystemClock.elapsedRealtime() + 8_000;
        while (!decoderFrame.tryAcquire(60, TimeUnit.MILLISECONDS)) {
            check();
            if (SystemClock.elapsedRealtime() >= end) throw new IOException(AppText.get(R.string.the_decoder_did_not_send_a_frame_to_the_gpu));
        }
    }

    private void emitDecoded(long pts, RenderSettings settings, ColorSignal signal, long duration) throws IOException {
        int slot = previous == 0 ? 1 : 0;
        gpu.capture(slot, signal, true);
        if (previous < 0) {
            firstPts = pts;
        } else {
            long span = pts - lastSource;
            lastSpan = span;
            int factor = (int) Math.min(settings.factor, span);
            if (factor > 1) {
                gpu.estimate(previous, slot);
                for (int step = 1; step < factor; step++) {
                    check();
                    long t = lastSource + span * step / factor;
                    emit(previous, slot, (float) step / factor, t - firstPts, settings.upscale);
                }
            }
        }
        emit(previous, slot, 1f, pts - firstPts, settings.upscale);
        previous = slot;
        lastSource = pts;
        long now = SystemClock.elapsedRealtime();
        if (now - progressAt > 250) {
            progressAt = now;
            int percent = duration > 0 ? (int) Math.min(99, (pts - firstPts) * 100 / duration) : -1;
            progress.update(percent, AppText.get(R.string.frames_processed) + count);
        }
    }

    private long finishTimeline(long duration, int fps, RenderSettings settings) throws IOException {
        long tail = lastSpan > 0 ? lastSpan : Math.max(1, 1_000_000 / fps);
        long relative = lastSource - firstPts;
        long explicit = duration - relative;
        if (explicit > 0 && explicit <= tail * 3) tail = explicit;
        int steps = (int) Math.min(settings.factor, tail);
        for (int i = 1; i < steps; i++) {
            check();
            emit(FramePacer.NONE, previous, 1f, relative + tail * i / steps, settings.upscale);
        }
        return relative + tail;
    }

    private void emit(int previous, int current, float phase, long pts, int mode) throws IOException {
        gpu.output(previous, current, phase, outputWidth, outputHeight, mode, false, false, false);
        if (!gpu.egl.swap(pts * 1000)) throw new IOException(AppText.get(R.string.gpu_could_not_send_the_frame_to_the_encoder));
        count++;
        drain(false);
    }

    private void drain(boolean end) throws IOException {
        long timeout = SystemClock.elapsedRealtime() + 18_000;
        while (!encoderEnded) {
            check();
            int index = encoder.dequeueOutputBuffer(encoded, end ? 10_000 : 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IOException(AppText.get(R.string.the_encoder_changed_format_after_writing_started));
                MediaFormat out = encoder.getOutputFormat();
                if (exportingHdr && out.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
                        && out.getInteger(MediaFormat.KEY_COLOR_TRANSFER) != MediaFormat.COLOR_TRANSFER_ST2084) {
                    throw new IOException(AppText.get(R.string.the_encoder_did_not_confirm_hdr10_output));
                }
                videoTrack = muxer.addTrack(out);
                muxer.start();
                muxerStarted = true;
            } else if (index >= 0) {
                try {
                    if (encoded.size > 0 && (encoded.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!muxerStarted) throw new IOException(AppText.get(R.string.the_encoder_did_not_report_an_output_format));
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        if (data == null) throw new IOException(AppText.get(R.string.the_encoder_did_not_return_mp4_data));
                        data.position(encoded.offset);
                        data.limit(encoded.offset + encoded.size);
                        copyAudio(encoded.presentationTimeUs);
                        muxer.writeSampleData(videoTrack, data, encoded);
                        lastWritten = encoded.presentationTimeUs;
                    }
                    encoderEnded = (encoded.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
                timeout = SystemClock.elapsedRealtime() + 18_000;
            } else if (!end) {
                return;
            } else if (SystemClock.elapsedRealtime() > timeout) {
                throw new IOException(AppText.get(R.string.the_encoder_did_not_finish_writing_the_video));
            }
        }
    }

    private void copyAudio(long until) throws IOException {
        if (audioTrack < 0) return;
        long time;
        while ((time = audio.getSampleTime()) >= 0) {
            long pts = time - firstPts;
            if (pts > until) return;
            if (pts >= 0) {
                check();
                long size = audio.getSampleSize();
                if (size > 16 * 1024 * 1024L) throw new IOException(AppText.get(R.string.audio_packet_is_too_large));
                if (size > audioBuffer.capacity()) audioBuffer = ByteBuffer.allocateDirect((int) size);
                audioBuffer.clear();
                int bytes = audio.readSampleData(audioBuffer, 0);
                if (bytes < 0) return;
                audioBuffer.limit(bytes);
                audioSample.set(0, bytes, pts, (audio.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(audioTrack, audioBuffer, audioSample);
            }
            audio.advance();
        }
    }

    private void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    @Override public void close() {
        if (decoder != null) decoder.release();
        if (gpu != null) gpu.close();
        if (encoderSurface != null) encoderSurface.release();
        if (encoder != null) encoder.release();
        if (decoderEvents != null) decoderEvents.quitSafely();
        if (audio != null) audio.release();
        source.release();
        if (muxer != null) {
            if (muxerStarted) {
                try { muxer.stop(); } catch (IllegalStateException ignored) { }
            }
            muxer.release();
        }
    }
}
