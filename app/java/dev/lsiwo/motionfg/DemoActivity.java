package dev.lsiwo.motionfg;

import android.app.Activity;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class DemoActivity extends Activity {
    private MotionEngine engine;
    private TextView meter, color;
    private int factor, filter;
    private boolean vsync;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        RenderSettings settings = RenderSettings.read(getSharedPreferences("settings", MODE_PRIVATE));
        factor = settings.factor;
        filter = settings.upscale;
        vsync = settings.vsync;
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsets.Type.systemBars());
        }
        if (settings.hdr == RenderSettings.HDR10) {
            getWindow().setColorMode(android.content.pm.ActivityInfo.COLOR_MODE_HDR);
        }
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.DARK);
        SurfaceView surface = new SurfaceView(this);
        frame.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        int pad = Ui.dp(this, 12);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(pad, pad, pad, 0);
        top.addView(Ui.button(this, "Назад", false, this::finish));
        meter = Ui.text(this, "— / — FPS", 17, Ui.INK, true);
        meter.setGravity(Gravity.CENTER);
        top.addView(meter, new LinearLayout.LayoutParams(0, -2, 1));
        color = Ui.text(this, "", 13, Ui.LIME, true);
        top.addView(color);
        android.widget.Switch sync = Ui.switcher(this, "V‑Sync", vsync, enabled -> {
            vsync = enabled;
            engine.setDemoSettings(factor, filter, vsync);
        });
        sync.setText("V‑Sync");
        sync.setTextColor(Ui.INK);
        sync.setPadding(pad, 0, 0, 0);
        top.addView(sync);
        frame.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(pad, 0, pad, pad);
        LinearLayout multipliers = Ui.pills(this, Ui.FACTORS, Ui.FACTOR_VALUES, factor, value -> {
            factor = value;
            engine.setDemoSettings(factor, filter, vsync);
        });
        controls.addView(multipliers, new LinearLayout.LayoutParams(0, -2, 1));
        TextView scale = Ui.options(this, "Фильтр", Ui.FILTERS, Ui.FILTER_VALUES, filter, value -> {
            filter = value;
            engine.setDemoSettings(factor, filter, vsync);
        });
        LinearLayout.LayoutParams scaleParams = new LinearLayout.LayoutParams(-2, -2);
        scaleParams.leftMargin = pad;
        controls.addView(scale, scaleParams);
        frame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        engine = new MotionEngine(getAssets(), settings, true, ColorSignal.displayHdr10(getDisplay()),
                new MotionEngine.Listener() {
            @Override public void failed(String why) { runOnUiThread(() -> meter.setText(why)); }
            @Override public void rates(int game, int output) {
                runOnUiThread(() -> meter.setText(RenderSettings.meter(game, output)));
            }
            @Override public void colorMode(int mode) { runOnUiThread(() -> color.setText(ColorSignal.outputLabel(mode))); }
        });
        android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        engine.setSourceSize(bounds.width(), bounds.height(), null);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                Display d = surface.getDisplay();
                engine.setOutput(holder.getSurface(), d == null ? 60f : d.getRefreshRate(), rateLimit(d));
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                Display d = surface.getDisplay();
                engine.resizeOutput(holder.getSurface(), w, h, d == null ? 0 : d.getRefreshRate());
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { engine.detachOutput(); }
        });
        setContentView(frame);
    }

    static float rateLimit(Display display) {
        if (display == null) return 60f;
        float rate = display.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) rate = Math.max(rate, mode.getRefreshRate());
        return rate;
    }

    @Override protected void onDestroy() {
        if (engine != null) engine.close();
        super.onDestroy();
    }
}
