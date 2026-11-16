package dev.lsiwo.motionfg;

import android.Manifest;
import android.app.Activity;
import android.app.LocaleManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.LocaleList;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int CHOOSE_APP = 70, NOTIFICATIONS = 71;
    private TextView overlayState, launch, lastStatus, goal, goalNote, syncBadge, advanced, canvas;
    private SharedPreferences prefs;
    private RenderSettings rendered;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        RenderSettings active = rendered = RenderSettings.read(prefs);
        LinearLayout body = Ui.page(this, Ui.GAME);

        LinearLayout hero = Ui.hero(this);
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heroTop.addView(Ui.heading(this, AppText.get(R.string.game_profile)), new LinearLayout.LayoutParams(0, -2, 1));
        syncBadge = Ui.text(this, "", 11, Ui.LIME, true);
        syncBadge.setBackground(Ui.surface(this, 0xff1a3538, 0xff41656a, 12));
        syncBadge.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        heroTop.addView(syncBadge, new LinearLayout.LayoutParams(-2, -2));
        Ui.add(hero, heroTop, 0);
        goal = Ui.text(this, "", 36, Ui.INK, true);
        goal.setFontFeatureSettings("tnum");
        Ui.add(hero, goal, 12);
        goalNote = Ui.note(this, "");
        Ui.add(hero, goalNote, 5);
        overlayState = Ui.note(this, "");
        Ui.add(hero, overlayState, 13);
        launch = Ui.button(this, AppText.get(R.string.select_game), true, this::launchOrStop);
        Ui.add(hero, launch, 11);
        lastStatus = Ui.note(this, "");
        Ui.add(hero, lastStatus, 9);
        Ui.add(body, hero, 2);

        LinearLayout generation = Ui.card(this);
        Ui.add(generation, Ui.heading(this, AppText.get(R.string.frame_generation)), 0);
        Ui.add(generation, Ui.pills(this, Ui.factorLabels(), Ui.FACTOR_VALUES, active.factor,
                value -> save(RenderSettings.FACTOR, value)), 11);
        Ui.divider(generation, 11);
        Ui.add(generation, Ui.switchRow(this, AppText.get(R.string.low_latency), AppText.get(R.string.shows_original_frames_immediately),
                Ui.switcher(this, AppText.get(R.string.low_latency), active.lowLatency,
                        on -> {
                            prefs.edit().putBoolean(RenderSettings.LOW_LATENCY, on).apply();
                            updateGoal();
                        })), 2);
        Ui.divider(generation, 2);
        Switch sync = Ui.switcher(this, "V‑Sync", active.vsync, enabled -> {
            prefs.edit().putBoolean(RenderSettings.VSYNC, enabled).apply();
            updateGoal();
        });
        Ui.add(generation, Ui.switchRow(this, "V‑Sync", AppText.get(R.string.sync_rendering_to_the_display), sync), 2);
        Ui.add(body, generation, 12);

        LinearLayout picture = Ui.card(this);
        Ui.add(picture, Ui.heading(this, AppText.get(R.string.upscaling)), 0);
        Ui.add(picture, Ui.profiles(this, active.profile, v -> save(RenderSettings.PROFILE, v)), 11);
        canvas = Ui.button(this, "", false, this::editCanvas);
        Ui.add(picture, canvas, 10);
        Ui.divider(picture, 11);
        LinearLayout white = Ui.choice(this, AppText.get(R.string.sdr_white_in_hdr), Ui.sdrWhiteLabels(), Ui.SDR_WHITE_VALUES,
                active.sdrWhite, v -> save(RenderSettings.SDR_WHITE, v));
        white.setVisibility(active.hdr == RenderSettings.HDR10 ? View.VISIBLE : View.GONE);
        Switch hdr = Ui.switcher(this, "HDR", active.hdr == RenderSettings.HDR10, on -> {
            save(RenderSettings.HDR, on ? RenderSettings.HDR10 : RenderSettings.SDR);
            white.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        Ui.add(picture, Ui.switchRow(this, "HDR", AppText.get(R.string.hdr10_or_scrgb_output), hdr), 2);
        Ui.add(picture, white, 1);
        Ui.add(body, picture, 12);

        LinearLayout performance = Ui.card(this);
        LinearLayout detail = Ui.vertical(this);
        boolean expanded = state != null && state.getBoolean("expanded");
        detail.setVisibility(expanded ? View.VISIBLE : View.GONE);
        advanced = Ui.button(this, "", false, () -> {
            boolean expand = detail.getVisibility() != View.VISIBLE;
            detail.setVisibility(expand ? View.VISIBLE : View.GONE);
            advanced.setSelected(expand);
            updateGoal();
        });
        advanced.setSelected(expanded);
        Ui.add(performance, advanced, 0);
        Ui.add(detail, Ui.choice(this, AppText.get(R.string.input_fps_limit), Ui.captureRateLabels(), Ui.INPUT_RATES,
                active.fps, v -> save(CaptureService.FPS, v)), 6);
        Ui.divider(detail, 0);
        Ui.add(detail, Ui.choice(this, AppText.get(R.string.motion_profile), Ui.motionLabels(), Ui.MOTION_VALUES, active.motion,
                v -> save(RenderSettings.MOTION, v)), 0);
        Ui.divider(detail, 0);
        Switch counter = Ui.switcher(this, AppText.get(R.string.fps_counter), active.counter, on ->
                prefs.edit().putBoolean(CaptureService.COUNTER, on).apply());
        Ui.add(detail, Ui.switchRow(this, AppText.get(R.string.fps_counter), AppText.get(R.string.game_output_frames), counter), 2);
        Ui.add(performance, detail, 0);
        Ui.add(body, performance, 12);

        Ui.add(body, Ui.button(this, AppText.get(R.string.open_test_scene), false, () -> {
            stopService(new Intent(this, CaptureService.class));
            startActivity(new Intent(this, DemoActivity.class));
        }), 12);
        LinearLayout app = Ui.card(this);
        Ui.add(app, Ui.button(this, getString(R.string.language), false, this::chooseLanguage), 0);
        Ui.add(app, Ui.button(this, getString(R.string.about), false, this::showAbout), 8);
        Ui.add(body, app, 12);
        updateGoal();
    }

    private static final String PROJECT_URL = "https://github.com/lsiwo/motionfg-android/tree/main";

    private void chooseLanguage() {
        LocaleManager manager = getSystemService(LocaleManager.class);
        String[] tags = { "", "en", "ru" };
        LocaleList current = manager.getApplicationLocales();
        int selected = current.isEmpty() ? 0 : "ru".equals(current.get(0).getLanguage()) ? 2 : 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(new String[] { getString(R.string.system_default), "English", "Русский" },
                        selected, (dialog, index) -> {
                            dialog.dismiss();
                            if (index != selected) {
                                manager.setApplicationLocales(LocaleList.forLanguageTags(tags[index]));
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setMessage(AppText.get(R.string.about_description) + "\n\n" + PROJECT_URL)
                .setPositiveButton(R.string.open_github, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(this, R.string.no_app_can_open_this_link, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void save(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        updateGoal();
    }

    private void editCanvas() {
        RenderSettings settings = RenderSettings.read(prefs);
        LinearLayout form = Ui.vertical(this);
        form.setPadding(Ui.dp(this, 23), Ui.dp(this, 6), Ui.dp(this, 23), 0);
        Ui.add(form, Ui.note(this, AppText.get(R.string.set_the_capture_buffer_width_and_height_in_pixels)), 0);
        LinearLayout fields = new LinearLayout(this);
        EditText w = dimension(AppText.get(R.string.width), settings.canvasWidth), h = dimension(AppText.get(R.string.height), settings.canvasHeight);
        fields.addView(w, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = Ui.dp(this, 10);
        fields.addView(h, second);
        Ui.add(form, fields, 10);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(AppText.get(R.string.capture_canvas)).setView(form)
                .setNegativeButton(AppText.get(R.string.cancel), null)
                .setNeutralButton(AppText.get(R.string.use_profile), (d, button) -> saveCanvas(0, 0))
                .setPositiveButton(AppText.get(R.string.apply), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int width = number(w), height = number(h);
            boolean validWidth = RenderSettings.legalDimension(width), validHeight = RenderSettings.legalDimension(height);
            if (!validWidth) w.setError(AppText.get(R.string.label_160_to_4096_pixels));
            if (!validHeight) h.setError(AppText.get(R.string.label_160_to_4096_pixels));
            if (validWidth && validHeight) {
                saveCanvas(width, height);
                dialog.dismiss();
            }
        }));
        dialog.show();
    }

    private EditText dimension(String label, int value) {
        EditText field = new EditText(this);
        field.setHint(label);
        field.setContentDescription(label + AppText.get(R.string.in_pixels));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(4) });
        if (value > 0) field.setText(Integer.toString(value));
        return field;
    }

    private static int number(EditText field) {
        String value = field.getText().toString().trim();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return 0; }
    }

    private void saveCanvas(int width, int height) {
        prefs.edit().putInt(RenderSettings.CANVAS_WIDTH, width).putInt(RenderSettings.CANVAS_HEIGHT, height).apply();
        updateGoal();
    }

    private void updateGoal() {
        RenderSettings s = rendered = RenderSettings.read(prefs);
        int displayLimit = Math.round(DemoActivity.rateLimit(getDisplay()));
        int targetFps = s.vsync ? Math.min(s.fps * s.factor, displayLimit) : s.fps * s.factor;
        goal.setText(s.fps == 0 ? (s.factor == 1 ? AppText.get(R.string.original_fps) : AppText.get(R.string.up_to_suffix) + s.factor + " FPS")
                : s.fps + " → " + targetFps + " FPS");
        syncBadge.setText(s.vsync ? AppText.get(R.string.v_sync_on) : AppText.get(R.string.v_sync_off));
        boolean fixed = s.canvasWidth > 0;
        String input = fixed ? s.canvasWidth + " × " + s.canvasHeight : s.profile.percent + AppText.get(R.string.of_app_size);
        canvas.setText(fixed ? AppText.get(R.string.canvas) + s.canvasWidth + " × " + s.canvasHeight : AppText.get(R.string.canvas_use_profile));
        goalNote.setText(AppText.get(R.string.capture) + input
                + (s.vsync ? AppText.get(R.string.up_to) + displayLimit + AppText.get(R.string.hz) : ""));
        advanced.setText(advanced.isSelected() ? AppText.get(R.string.performance_settings)
                : AppText.get(R.string.performance_settings_suffix));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("expanded", advanced.isSelected());
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        RenderSettings current = RenderSettings.read(prefs);
        if (current.profile != rendered.profile || current.fps != rendered.fps || current.factor != rendered.factor
                || current.vsync != rendered.vsync
                || current.lowLatency != rendered.lowLatency || current.sdrWhite != rendered.sdrWhite) {
            recreate();
            return;
        }
        updateStatus();
    }

    private void updateStatus() {
        boolean ready = MotionOverlay.active != null;
        boolean running = CaptureService.running;
        overlayState.setText(running ? AppText.get(R.string.capture_is_active_tap_fg_in_game_for_quick_settings)
                : ready ? AppText.get(R.string.in_android_s_prompt_select_a_single_app_and_choose_the_game)
                        : AppText.get(R.string.enable_motion_fg_in_accessibility_to_display_processed_frames_ove));
        launch.setText(running ? AppText.get(R.string.stop_processing)
                : ready ? AppText.get(R.string.select_game) : AppText.get(R.string.enable_overlay));
        SharedPreferences session = getSharedPreferences("session", MODE_PRIVATE);
        String line = session.getString("status", "");
        lastStatus.setText(line);
        lastStatus.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void launchOrStop() {
        if (CaptureService.running) {
            stopService(new Intent(this, CaptureService.class));
            launch.postDelayed(this::updateStatus, 180);
        } else if (MotionOverlay.active == null) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATIONS);
        } else {
            askForGame();
        }
    }

    private void askForGame() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()), CHOOSE_APP);
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request == NOTIFICATIONS) askForGame();
    }

    @Override protected void onActivityResult(int request, int result, Intent consent) {
        super.onActivityResult(request, result, consent);
        if (request != CHOOSE_APP || result != RESULT_OK || consent == null) return;
        try {
            Intent session = new Intent(this, CaptureService.class).putExtra(CaptureService.CONSENT, consent);
            startForegroundService(RenderSettings.read(prefs).put(session));
            moveTaskToBack(true);
        } catch (RuntimeException error) {
            Toast.makeText(this, AppText.get(R.string.capture_failed_to_start_select_the_game_again), Toast.LENGTH_LONG).show();
        }
    }
}
