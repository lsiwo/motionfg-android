package dev.lsiwo.motionfg;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoActivity extends Activity {
    private static final int PICK_VIDEO = 100, SAVE_VIDEO = 101;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<View> controls = new ArrayList<>();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private SharedPreferences p;
    private Uri input;
    private String inputName;
    private TextView file, status, start, cancelButton;
    private ProgressBar progress;
    private boolean running;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        p = getSharedPreferences("video", MODE_PRIVATE);
        input = state == null ? null : state.getParcelable("input", Uri.class);
        inputName = state == null ? null : state.getString("inputName");
        LinearLayout page = Ui.page(this, Ui.VIDEO);
        LinearLayout source = Ui.hero(this);
        Ui.add(source, Ui.heading(this, AppText.get(R.string.video_processing)), 0);
        Ui.add(source, Ui.note(this, AppText.get(R.string.increase_the_frame_rate_or_resolution_and_export_to_mp4)), 7);
        file = Ui.button(this, inputName == null ? AppText.get(R.string.select_video) : inputName, false, this::pickVideo);
        file.setMaxLines(2);
        file.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.add(file);
        Ui.add(source, file, 12);
        Ui.add(page, source, 2);

        LinearLayout motion = Ui.card(this);
        Ui.add(motion, Ui.heading(this, AppText.get(R.string.frame_rate)), 0);
        LinearLayout factors = Ui.pills(this, new String[] { "×1", "×2", "×3", "×4" }, Ui.FACTOR_VALUES,
                p.getInt(RenderSettings.FACTOR, 2), v -> save(RenderSettings.FACTOR, v));
        controls.add(factors);
        Ui.add(motion, factors, 10);
        Ui.divider(motion, 11);
        addOption(motion, AppText.get(R.string.motion_profile), Ui.motionLabels(), Ui.MOTION_VALUES,
                RenderSettings.MOTION, RenderSettings.QUALITY);
        Ui.add(page, motion, 12);

        LinearLayout card = Ui.card(this);
        Ui.add(card, Ui.heading(this, AppText.get(R.string.mp4_settings)), 0);
        addOption(card, AppText.get(R.string.resolution), new String[] { AppText.get(R.string.original), "720p", "1080p", "1440p", "2160p" },
                new int[] { 0, 720, 1080, 1440, 2160 }, "size", 0);
        Ui.divider(card, 0);
        addOption(card, AppText.get(R.string.upscaling), Ui.filterLabels(), Ui.FILTER_VALUES, RenderSettings.SCALE, RenderSettings.TEMPORAL);
        Ui.divider(card, 0);
        addOption(card, AppText.get(R.string.color), new String[] { AppText.get(R.string.automatic), "SDR", "HDR10" },
                new int[] { VideoExport.AUTO_COLOR, RenderSettings.SDR, RenderSettings.HDR10 },
                RenderSettings.HDR, VideoExport.AUTO_COLOR);
        Ui.divider(card, 0);
        Switch audio = Ui.switcher(this, AppText.get(R.string.keep_audio), p.getBoolean("audio", true),
                b -> p.edit().putBoolean("audio", b).apply());
        controls.add(audio);
        Ui.add(card, Ui.switchRow(this, AppText.get(R.string.keep_audio), null, audio), 2);
        Ui.add(page, card, 12);
        start = Ui.button(this, AppText.get(R.string.export_mp4), true, this::chooseOutput);
        Ui.add(page, start, 13);
        cancelButton = Ui.button(this, AppText.get(R.string.cancel_export), false, () -> {
            cancel.set(true);
            status.setText(AppText.get(R.string.canceling));
        });
        cancelButton.setVisibility(View.GONE);
        Ui.add(page, cancelButton, 8);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        Ui.add(page, progress, 8);
        status = Ui.text(this, "", 14, Ui.SOFT, false);
        Ui.add(page, status, 7);
    }

    private void addOption(LinearLayout card, String label, String[] names, int[] values, String key, int fallback) {
        LinearLayout control = Ui.choice(this, label, names, values, p.getInt(key, fallback), v -> save(key, v));
        controls.add(control);
        Ui.add(card, control, 2);
    }

    private void save(String name, int value) { p.edit().putInt(name, value).apply(); }

    private void pickVideo() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), PICK_VIDEO);
    }

    private void chooseOutput() {
        if (input == null) {
            pickVideo();
            return;
        }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4")
                .putExtra(Intent.EXTRA_TITLE, "MotionFG.mp4"), SAVE_VIDEO);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_VIDEO) {
            input = data.getData();
            inputName = AppText.get(R.string.video_selected);
            try (Cursor names = getContentResolver().query(input, new String[] { OpenableColumns.DISPLAY_NAME },
                    null, null, null)) {
                if (names != null && names.moveToFirst() && names.getString(0) != null) inputName = names.getString(0);
            } catch (RuntimeException ignored) { }
            file.setText(inputName);
            status.setText("");
        } else if (request == SAVE_VIDEO && input != null && !running) {
            Uri destination = data.getData();
            if (sameDocument(input, destination)) {
                status.setText(AppText.get(R.string.choose_a_different_output_file));
                return;
            }
            export(destination);
        }
    }

    private boolean sameDocument(Uri source, Uri destination) {
        if (source.equals(destination)) return true;
        return Objects.equals(source.getAuthority(), destination.getAuthority())
                && DocumentsContract.isDocumentUri(this, source) && DocumentsContract.isDocumentUri(this, destination)
                && DocumentsContract.getDocumentId(source).equals(DocumentsContract.getDocumentId(destination));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelable("input", input);
        state.putString("inputName", inputName);
        super.onSaveInstanceState(state);
    }

    private void export(Uri destination) {
        running = true;
        cancel.set(false);
        for (View v : controls) enable(v, false);
        start.setVisibility(View.GONE);
        cancelButton.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        status.setText(AppText.get(R.string.preparing_gpu_and_codecs));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Uri source = input;
        final int target = p.getInt("size", 0), color = p.getInt(RenderSettings.HDR, VideoExport.AUTO_COLOR);
        final boolean audio = p.getBoolean("audio", true);
        final RenderSettings options = new RenderSettings(0, p.getInt(RenderSettings.FACTOR, 2),
                p.getInt(RenderSettings.SCALE, RenderSettings.TEMPORAL),
                p.getInt(RenderSettings.MOTION, RenderSettings.QUALITY), color, false, false,
                CaptureProfile.NATIVE, 0, 0, false, RenderSettings.DEFAULT_WHITE);
        worker.execute(() -> {
            File pending = null;
            boolean saved = false;
            String line;
            try {
                pending = File.createTempFile("motionfg-", ".mp4", getCacheDir());
                String result;
                try (VideoExport job = new VideoExport(this, cancel, this::progressChanged)) {
                    result = job.run(source, pending, options, target, color, audio);
                }
                progressChanged(99, AppText.get(R.string.saving_mp4));
                copy(pending, destination);
                saved = true;
                line = AppText.get(R.string.done) + result;
            } catch (CancellationException | InterruptedException e) {
                line = AppText.get(R.string.export_canceled);
            } catch (RuntimeException | IOException e) {
                Log.e("MotionFG", "Video export failed", e);
                line = e.getMessage() == null ? AppText.get(R.string.could_not_export_the_video) : e.getMessage();
            } finally {
                if (pending != null) pending.delete();
                if (!saved) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), destination); }
                    catch (Exception ignored) { }
                }
            }
            final String end = line;
            runOnUiThread(() -> complete(end));
        });
    }

    private void copy(File pending, Uri destination) throws IOException {
        if (cancel.get()) throw new CancellationException();
        byte[] block = new byte[262_144];
        try (FileInputStream in = new FileInputStream(pending);
             OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new IOException(AppText.get(R.string.the_selected_file_is_not_writable));
            int n;
            while ((n = in.read(block)) != -1) {
                if (cancel.get()) throw new CancellationException();
                out.write(block, 0, n);
            }
        }
    }

    private void progressChanged(int percent, String text) {
        runOnUiThread(() -> {
            if (!running || isDestroyed() || cancel.get()) return;
            progress.setIndeterminate(percent < 0);
            if (percent >= 0) progress.setProgress(percent);
            status.setText(text);
        });
    }

    private void complete(String result) {
        if (isDestroyed()) return;
        running = false;
        for (View v : controls) enable(v, true);
        start.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        status.setText(result);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static void enable(View view, boolean enabled) {
        view.setAlpha(enabled ? 1f : 0.6f);
        setEnabledTree(view, enabled);
    }

    private static void setEnabledTree(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledTree(group.getChildAt(i), enabled);
        }
    }

    @Override protected void onDestroy() {
        cancel.set(true);
        worker.shutdownNow();
        super.onDestroy();
    }
}
