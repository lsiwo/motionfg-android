package dev.lsiwo.motionfg;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class MotionOverlay extends AccessibilityService {
    interface ProfileChange { void apply(RenderSettings settings); }

    static MotionOverlay active;
    private static final int PASS_THROUGH = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_SECURE;
    private static final int CORNER = Gravity.TOP | Gravity.LEFT;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable inspectWindows = this::inspectSystemWindows;
    private WindowManager windows;
    private DisplayManager displays;
    private MotionEngine engine;
    private RenderSettings profile;
    private ProfileChange change;
    private FrameLayout screen;
    private SurfaceView surface;
    private TextView meter, bubble, quickStats, quickColor;
    private String colorStatus = "";
    private ScrollView panel;
    private WindowManager.LayoutParams screenLayout, bubbleLayout, panelLayout;
    private boolean comparing, systemCovered, shadeOpening, outputReady;
    private int gameFps, outputFps, edgeTop, edgeBottom;
    private CaptureGeometry captureGeometry;
    private int bubbleX = -1, bubbleY = -1;
    private Insets safe = Insets.NONE;

    private final DisplayManager.DisplayListener displayChanges = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY || screen == null) return;
            applyInsets();
            publishOutput();
            requestDisplayMode();
            updateMeter();
        }
    };

    @Override protected void onServiceConnected() { active = this; }

    void show(MotionEngine render, RenderSettings settings, ProfileChange changes) {
        if (screen != null) return;
        engine = render;
        profile = settings;
        change = changes;
        windows = getSystemService(WindowManager.class);
        displays = getSystemService(DisplayManager.class);
        Context theme = new android.view.ContextThemeWrapper(this, R.style.MotionTheme);
        screen = new FrameLayout(theme);
        surface = new SurfaceView(theme);
        surface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        surface.setSecure(true);
        surface.setVisibility(View.INVISIBLE);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                Display d = display();
                render.setOutput(holder.getSurface(), d == null ? 60f : d.getRefreshRate(), DemoActivity.rateLimit(d));
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                publishOutput();
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                render.detachOutput();
            }
        });
        screen.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        meter = Ui.text(theme, "— / — FPS", 14, Ui.LIME, true);
        meter.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        meter.setBackground(Ui.bg(theme, 0xe6101723, 9));
        int pad = Ui.dp(theme, 8);
        meter.setPadding(pad, pad / 2, pad, pad / 2);
        meter.setVisibility(View.GONE);
        screen.addView(meter, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            screen.post(this::applyInsets);
            return insets;
        });
        screen.setBackgroundColor(Color.TRANSPARENT);
        screenLayout = layout(-1, -1, false, "Motion FG presentation");
        if (profile.hdr == RenderSettings.HDR10) {
            screenLayout.setColorMode(android.content.pm.ActivityInfo.COLOR_MODE_HDR);
        }
        applyInsets();
        windows.addView(screen, screenLayout);

        bubble = Ui.button(theme, "FG", true, this::togglePanel);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(0, 0, 0, 0);
        bubble.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x550d121c),
                Ui.surface(theme, Ui.LIME, 0xffd1ffe9, 16), null));
        bubble.setElevation(Ui.dp(this, 5));
        bubble.setContentDescription(AppText.get(R.string.motion_fg_quick_settings));
        bubble.setOnTouchListener(new DragButton());
        int bubbleSize = Ui.dp(this, 48);
        bubbleLayout = layout(bubbleSize, bubbleSize, true, "Motion FG controls");
        placeBubble();
        windows.addView(bubble, bubbleLayout);
        displays.registerDisplayListener(displayChanges, main);
        requestDisplayMode();
        inspectSystemWindows();
    }

    private WindowManager.LayoutParams layout(int width, int height, boolean touchable, String title) {
        int flags = PASS_THROUGH | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = CORNER;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle(title);
        return params;
    }

    private Display display() {
        return displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
    }

    private void applyInsets() {
        if (screen == null) return;
        safe = windows.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        edgeTop = Math.max(Ui.dp(this, 24), safe.top);
        edgeBottom = Math.max(Ui.dp(this, 20), safe.bottom);
        if (windows.getCurrentWindowMetrics().getBounds().height() - edgeTop - edgeBottom < Ui.dp(this, 90)) {
            edgeTop = edgeBottom = 0;
        }
        FrameLayout.LayoutParams stats = (FrameLayout.LayoutParams) meter.getLayoutParams();
        int left = Math.max(safe.left, Ui.dp(this, 12));
        int top = edgeTop + Ui.dp(this, 8);
        if (stats.leftMargin != left || stats.topMargin != top) {
            stats.leftMargin = left;
            stats.topMargin = top;
            meter.setLayoutParams(stats);
        }
        if (bubbleLayout != null) {
            placeBubble();
            if (bubble.isAttachedToWindow()) windows.updateViewLayout(bubble, bubbleLayout);
        }
        if (panel != null) placePanel();
    }

    private void publishOutput() {
        if (surface == null || engine == null) return;
        Display d = display();
        Rect frame = surface.getHolder().getSurfaceFrame();
        if (frame.width() > 0 && frame.height() > 0) {
            engine.resizeOutput(surface.getHolder().getSurface(), frame.width(), frame.height(),
                    d == null ? 0 : d.getRefreshRate());
        }
    }

    private void requestDisplayMode() {
        Display d = display();
        if (d == null || screen == null || !screen.isAttachedToWindow()) return;
        Display.Mode current = d.getMode(), best = current;
        int target = (profile.fps == 0 ? (gameFps > 0 ? gameFps : 30) : profile.fps) * profile.factor;
        int requestedId = 0;
        if (profile.vsync && !comparing && !systemCovered && !shadeOpening && profile.factor >= 3) {
            for (Display.Mode mode : d.getSupportedModes()) {
                if (mode.getPhysicalWidth() != current.getPhysicalWidth()
                        || mode.getPhysicalHeight() != current.getPhysicalHeight()) continue;
                float wanted = mode.getRefreshRate(), selected = best.getRefreshRate();
                if (selected < target - 1f ? wanted > selected : wanted >= target - 1f && wanted < selected) {
                    best = mode;
                }
            }
            requestedId = best.getModeId();
        }
        if (screenLayout.preferredDisplayModeId != requestedId) {
            screenLayout.preferredDisplayModeId = requestedId;
            windows.updateViewLayout(screen, screenLayout);
        }
    }

    void updateCaptureSize(CaptureGeometry geometry) {
        captureGeometry = geometry;
        updateMeter();
    }

    void updateRates(int game, int output) {
        gameFps = game;
        outputFps = output;
        updateMeter();
        if (profile != null && profile.vsync && profile.fps == 0 && profile.factor >= 3 && game > 0) {
            requestDisplayMode();
        }
    }

    void updateColorStatus(String line) {
        colorStatus = line;
        updateMeter();
    }

    private void updateMeter() {
        if (meter == null) return;
        if (profile.counter && !comparing && !systemCovered) {
            String fps = RenderSettings.meter(gameFps, outputFps);
            if (!fps.contentEquals(meter.getText())) meter.setText(fps);
        }
        if (quickStats != null) {
            Display d = display();
            String line = comparing || !outputReady ? AppText.get(R.string.original_game)
                    : profile.counter ? RenderSettings.meter(gameFps, outputFps) : AppText.get(R.string.fps_counter_is_off);
            if (d != null) line += AppText.get(R.string.display) + Math.round(d.getRefreshRate()) + AppText.get(R.string.hz);
            if (!line.contentEquals(quickStats.getText())) quickStats.setText(line);
        }
        if (quickColor != null) {
            String signal = "";
            if (captureGeometry != null) {
                CaptureGeometry size = captureGeometry;
                signal = size.bufferWidth + " × " + size.bufferHeight;
                if (size.bufferWidth - size.width > 2 || size.bufferHeight - size.height > 2) {
                    signal += AppText.get(R.string.frame) + size.width + " × " + size.height;
                }
            }
            if (!colorStatus.isEmpty()) signal += (signal.isEmpty() ? "" : "  ·  ") + colorStatus;
            if (!signal.contentEquals(quickColor.getText())) quickColor.setText(signal);
        }
    }

    private void placeBubble() {
        Rect b = windows.getCurrentWindowMetrics().getBounds();
        int margin = Ui.dp(this, 12);
        int left = Math.max(margin, safe.left + margin);
        int right = b.width() - Math.max(margin, safe.right + margin) - bubbleLayout.width;
        int top = edgeTop + margin;
        int bottom = b.height() - edgeBottom - margin - bubbleLayout.height;
        bubbleLayout.x = clamp(bubbleX < 0 ? right : bubbleX, left, right);
        bubbleLayout.y = clamp(bubbleY < 0 ? top + Ui.dp(this, 54) : bubbleY, top, bottom);
    }

    private void togglePanel() {
        if (systemCovered || shadeOpening) return;
        if (panel != null) closePanel(); else openPanel();
    }

    private void openPanel() {
        Context c = bubble.getContext();
        panel = new ScrollView(c);
        panel.setClipToOutline(true);
        panel.setBackground(Ui.surface(c, 0xfa171f2e, 0xff4c646d, 22));
        panel.setElevation(Ui.dp(c, 8));
        panel.setFillViewport(false);
        LinearLayout content = Ui.vertical(c);
        int pad = Ui.dp(c, 13);
        content.setPadding(pad, pad, pad, pad);
        panel.addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.heading(c, AppText.get(R.string.in_game_settings)), new LinearLayout.LayoutParams(0, -2, 1));
        TextView shade = Ui.button(c, "↓", false, this::openShade);
        shade.setContentDescription(AppText.get(R.string.open_notification_shade));
        LinearLayout.LayoutParams shadeSize = new LinearLayout.LayoutParams(Ui.dp(c, 48), -2);
        shadeSize.rightMargin = Ui.dp(c, 6);
        top.addView(shade, shadeSize);
        TextView close = Ui.button(c, "×", false, this::closePanel);
        close.setContentDescription(AppText.get(R.string.close_settings));
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(c, 48), -2));
        Ui.add(content, top, 0);
        LinearLayout readout = Ui.vertical(c);
        readout.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        readout.setBackground(Ui.surface(c, 0xff142e32, 0xff31534e, 13));
        quickStats = Ui.text(c, "", 14, Ui.LIME, true);
        quickColor = Ui.note(c, "");
        Ui.add(readout, quickStats, 0);
        Ui.add(readout, quickColor, 4);
        Ui.add(content, readout, 6);
        updateMeter();
        Ui.add(content, Ui.switchRow(c, AppText.get(R.string.show_original), AppText.get(R.string.compare_with_the_unprocessed_game),
                Ui.switcher(c, AppText.get(R.string.show_original), comparing, original -> {
                    comparing = original;
                    applyVisibility();
                })), 2);
        Ui.divider(content, 0);

        Ui.add(content, Ui.text(c, AppText.get(R.string.frame_generation), 14, Ui.INK, true), 10);
        Ui.add(content, Ui.pills(c, Ui.factorLabels(), Ui.FACTOR_VALUES, profile.factor, this::selectFactor), 4);
        Ui.add(content, Ui.switchRow(c, "V‑Sync", AppText.get(R.string.sync_frame_generation_to_the_display),
                Ui.switcher(c, "V‑Sync", profile.vsync,
                        sync -> apply(profile.profile, profile.fps, profile.factor, sync,
                                profile.lowLatency, profile.sdrWhite))), 3);
        Ui.add(content, Ui.switchRow(c, AppText.get(R.string.low_latency), null,
                Ui.switcher(c, AppText.get(R.string.low_latency), profile.lowLatency,
                        enabled -> apply(profile.profile, profile.fps, profile.factor,
                                profile.vsync, enabled, profile.sdrWhite))), 1);
        Ui.add(content, Ui.text(c, AppText.get(R.string.upscaling), 14, Ui.INK, true), 9);
        Ui.add(content, Ui.profiles(c, profile.profile, value -> apply(CaptureProfile.at(value),
                profile.fps, profile.factor, profile.vsync, profile.lowLatency, profile.sdrWhite)), 4);
        if (profile.canvasWidth > 0) {
            Ui.add(content, Ui.note(c, AppText.get(R.string.custom_canvas) + profile.canvasWidth + " × " + profile.canvasHeight), 5);
        }
        Ui.add(content, Ui.text(c, AppText.get(R.string.capture_fps_limit), 14, Ui.INK, true), 9);
        Ui.add(content, Ui.pills(c, new String[] { AppText.get(R.string.auto), "24", "30", "45", "60" }, Ui.INPUT_RATES, profile.fps,
                value -> apply(profile.profile, value, profile.factor, profile.vsync,
                        profile.lowLatency, profile.sdrWhite)), 4);
        if (profile.hdr == RenderSettings.HDR10) {
            Ui.add(content, Ui.text(c, AppText.get(R.string.sdr_white_in_hdr), 14, Ui.INK, true), 9);
            Ui.add(content, Ui.pills(c, Ui.sdrWhiteLabels(), Ui.SDR_WHITE_VALUES, profile.sdrWhite,
                    white -> apply(profile.profile, profile.fps, profile.factor, profile.vsync,
                            profile.lowLatency, white)), 4);
        }

        panelLayout = layout(0, 0, true, "Motion FG quick settings");
        placePanel();
        windows.addView(panel, panelLayout);
    }

    private void selectFactor(int multiplier) {
        apply(profile.profile, profile.fps, multiplier, profile.vsync,
                profile.lowLatency, profile.sdrWhite);
    }

    private void apply(CaptureProfile preset, int fps, int factor, boolean sync,
            boolean lowLatency, int white) {
        profile = new RenderSettings(fps, factor, preset.filter, profile.motion, profile.hdr, profile.counter,
                sync, preset, profile.canvasWidth, profile.canvasHeight, lowLatency, white);
        change.apply(profile);
        requestDisplayMode();
    }

    private void placePanel() {
        Rect b = windows.getCurrentWindowMetrics().getBounds();
        int blank = Ui.dp(this, 10);
        int left = safe.left + blank, right = b.width() - safe.right - blank;
        int top = edgeTop + blank, bottom = b.height() - edgeBottom - blank;
        panelLayout.width = Math.min(Ui.dp(this, 356), Math.max(1, right - left));
        int maxHeight = Math.min(Ui.dp(this, 562), Math.max(1, bottom - top));
        panel.measure(View.MeasureSpec.makeMeasureSpec(panelLayout.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        panelLayout.height = Math.min(maxHeight, panel.getMeasuredHeight());
        panelLayout.x = bubbleLayout.x < b.width() / 2 ? left : right - panelLayout.width;
        panelLayout.y = clamp(bubbleLayout.y, top, bottom - panelLayout.height);
        if (panel.isAttachedToWindow()) windows.updateViewLayout(panel, panelLayout);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private void closePanel() {
        if (panel != null) {
            if (panel.isAttachedToWindow()) windows.removeViewImmediate(panel);
            panel = null;
            panelLayout = null;
            quickStats = quickColor = null;
        }
    }

    private void openShade() {
        closePanel();
        shadeOpening = true;
        applyVisibility();
        if (!performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) {
            shadeOpening = false;
            applyVisibility();
            return;
        }
        main.postDelayed(() -> {
            shadeOpening = false;
            inspectSystemWindows();
            applyVisibility();
        }, 650);
    }

    private void applyVisibility() {
        if (screen == null) return;
        boolean hidden = comparing || systemCovered || shadeOpening;
        applyOutputVisibility();
        bubble.setVisibility(systemCovered || shadeOpening ? View.GONE : View.VISIBLE);
        if (systemCovered) closePanel();
        engine.setSuspended(hidden);
        requestDisplayMode();
        updateMeter();
    }

    void setOutputReady(boolean ready) {
        if (screen == null || outputReady == ready) return;
        outputReady = ready;
        applyOutputVisibility();
        updateMeter();
    }

    private void applyOutputVisibility() {
        boolean show = outputReady && !comparing && !systemCovered && !shadeOpening;
        surface.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        meter.setVisibility(show && profile.counter ? View.VISIBLE : View.GONE);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (screen == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        main.removeCallbacks(inspectWindows);
        main.post(inspectWindows);
    }

    private void inspectSystemWindows() {
        if (screen == null) return;
        // Window metadata alone is sufficient. Never read the app's accessibility tree.
        List<AccessibilityWindowInfo> list = getWindows();
        Rect bounds = windows.getCurrentWindowMetrics().getBounds(), rect = new Rect();
        int appLayer = -1;
        for (AccessibilityWindowInfo item : list) {
            if (item.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) appLayer = Math.max(appLayer, item.getLayer());
        }
        boolean covered = false;
        for (AccessibilityWindowInfo item : list) {
            if (item.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && item.getLayer() > appLayer) {
                item.getBoundsInScreen(rect);
                if (rect.width() >= bounds.width() * 0.58f && rect.height() >= bounds.height() * 0.23f) {
                    covered = true;
                }
            }
            item.recycle();
        }
        if (systemCovered != covered) {
            systemCovered = covered;
            applyVisibility();
        }
    }

    void hide() {
        if (screen == null) return;
        closePanel();
        main.removeCallbacksAndMessages(null);
        displays.unregisterDisplayListener(displayChanges);
        if (bubble != null && bubble.isAttachedToWindow()) windows.removeViewImmediate(bubble);
        if (screen.isAttachedToWindow()) windows.removeViewImmediate(screen);
        screen = null;
        bubble = meter = null;
        surface = null;
        bubbleLayout = null;
        engine = null;
        comparing = systemCovered = shadeOpening = outputReady = false;
        gameFps = outputFps = 0;
        captureGeometry = null;
        colorStatus = "";
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyInsets();
        publishOutput();
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        disconnected();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnected();
        super.onDestroy();
    }

    private void disconnected() {
        if (active != this) return;
        active = null;
        hide();
        stopService(new Intent(this, CaptureService.class));
    }

    private final class DragButton implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(MotionOverlay.this).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean moving;

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = bubbleLayout.x;
                    startY = bubbleLayout.y;
                    moving = false;
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downX);
                    int dy = Math.round(event.getRawY() - downY);
                    if (!moving && Math.max(Math.abs(dx), Math.abs(dy)) > slop) {
                        moving = true;
                        closePanel();
                    }
                    if (moving) {
                        bubbleX = startX + dx;
                        bubbleY = startY + dy;
                        placeBubble();
                        windows.updateViewLayout(view, bubbleLayout);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    if (!moving) view.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    return true;
                default:
                    return false;
            }
        }
    }
}
