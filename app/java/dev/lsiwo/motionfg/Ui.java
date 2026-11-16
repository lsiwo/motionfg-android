package dev.lsiwo.motionfg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

final class Ui {
    static final int DARK = 0xff0d121c;
    static final int CARD = 0xff171f2e;
    static final int CONTROL = 0xff222e41;
    static final int INK = 0xfff2f6ff;
    static final int SOFT = 0xffb0bfd4;
    static final int LIME = 0xff83efc4;
    private static final int BORDER = 0xff313e53;
    static String[] factorLabels() {
        return new String[] { AppText.get(R.string.off), "×2", "×3", "×4" };
    }
    static final int[] FACTOR_VALUES = { 1, 2, 3, 4 };
    static String[] profileLabels() {
        return new String[] { AppText.get(R.string.native_profile), AppText.get(R.string.quality), AppText.get(R.string.balanced), AppText.get(R.string.performance) };
    }
    static String[] sdrWhiteLabels() {
        return new String[] { AppText.get(R.string.label_120_nits), AppText.get(R.string.label_160_nits), AppText.get(R.string.label_203_nits), AppText.get(R.string.label_250_nits) };
    }
    static final int[] SDR_WHITE_VALUES = { 120, 160, 203, 250 };
    static String[] filterLabels() {
        return new String[] { AppText.get(R.string.performance_bilinear), AppText.get(R.string.balanced_fast_bicubic),
            AppText.get(R.string.quality_precise_bicubic), AppText.get(R.string.best_quality_edges_and_history) };
    }
    static final int[] FILTER_VALUES = { 0, 1, 2, 3 };
    static String[] captureRateLabels() {
        return new String[] { AppText.get(R.string.auto), "24 FPS", "30 FPS", "45 FPS", "60 FPS" };
    }
    static final int[] INPUT_RATES = { 0, 24, 30, 45, 60 };
    static String[] motionLabels() {
        return new String[] { AppText.get(R.string.fast), AppText.get(R.string.precise) };
    }
    static final int[] MOTION_VALUES = { 0, 1 };
    static final int GAME = 0, VIDEO = 1;

    private Ui() {}

    static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable bg(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static GradientDrawable surface(Context c, int color, int outline, int radius) {
        GradientDrawable shape = bg(c, color, radius);
        shape.setStroke(dp(c, 1), outline);
        return shape;
    }

    static TextView text(Context context, String label, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(context, 2), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView heading(Context c, String label) {
        TextView title = text(c, label, 19, INK, true);
        title.setAccessibilityHeading(true);
        return title;
    }

    static TextView note(Context c, String label) { return text(c, label, 13, SOFT, false); }

    static TextView button(Context context, String label, boolean primary, Runnable action) {
        TextView view = text(context, label, 15, INK, true);
        int pad = dp(context, 14);
        view.setPadding(pad, dp(context, 8), pad, dp(context, 8));
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 48));
        buttonStyle(view, primary);
        click(view, action);
        return view;
    }

    private static void click(View view, Runnable action) {
        view.setOnClickListener(v -> action.run());
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
                info.setSelected(host.isSelected());
            }
        });
    }

    private static void buttonStyle(TextView view, boolean selected) {
        view.setTextColor(selected ? DARK : INK);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x298ff4d4),
                bg(view.getContext(), selected ? LIME : CONTROL, 12), null));
    }

    private static RippleDrawable touch(Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x2372e6c7), null, bg(c, -1, 8));
    }

    static LinearLayout page(Activity activity, int tab) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        LinearLayout root = vertical(activity);
        root.setBackgroundColor(DARK);
        FrameLayout box = new FrameLayout(activity);
        LinearLayout full = vertical(activity);
        FrameLayout.LayoutParams width = new FrameLayout.LayoutParams(dp(activity, 620), -1, Gravity.CENTER_HORIZONTAL);
        box.addView(full, width);
        root.addView(box, new LinearLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = Math.min(r - l, dp(activity, 620));
            if (want > 0 && full.getLayoutParams().width != want) {
                width.width = want;
                full.setLayoutParams(width);
            }
        });
        int available = activity.getResources().getDisplayMetrics().widthPixels;
        width.width = Math.min(available, dp(activity, 620));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(activity, 21);
        top.setPadding(pad, dp(activity, 13), pad, dp(activity, 15));
        android.widget.ImageView brand = new android.widget.ImageView(activity);
        brand.setImageResource(R.drawable.ic_motion);
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(brand, new LinearLayout.LayoutParams(dp(activity, 33), dp(activity, 33)));
        TextView name = text(activity, "Motion FG", 24, INK, true);
        LinearLayout.LayoutParams wordmark = new LinearLayout.LayoutParams(0, -2, 1);
        wordmark.leftMargin = dp(activity, 9);
        top.addView(name, wordmark);
        full.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = vertical(activity);
        body.setPadding(pad, dp(activity, 4), pad, dp(activity, 16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        full.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        divider(full, 0);
        full.addView(nav(activity, tab), new LinearLayout.LayoutParams(-1, -2));
        activity.setContentView(root);
        return body;
    }

    private static LinearLayout nav(Activity c, int tab) {
        LinearLayout row = new LinearLayout(c);
        row.setBackgroundColor(DARK);
        row.setPadding(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 5));
        String[] labels = { AppText.get(R.string.game), AppText.get(R.string.video) };
        Class<?>[] activities = { MainActivity.class, VideoActivity.class };
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            boolean selected = index == tab;
            LinearLayout item = vertical(c);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(dp(c, 54));
            item.setSelected(selected);
            item.setContentDescription(labels[i]);
            item.setBackground(selected ? surface(c, 0xff1b3439, 0xff335454, 16) : touch(c));
            Icon icon = new Icon(c, i, selected ? LIME : SOFT);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(dp(c, 23), dp(c, 23)));
            TextView title = text(c, labels[i], 12, selected ? LIME : SOFT, true);
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(-2, -2);
            caption.topMargin = dp(c, 4);
            item.addView(title, caption);
            click(item, () -> {
                if (selected) return;
                if (CaptureService.running && index != GAME) {
                    c.stopService(new Intent(c, CaptureService.class));
                }
                c.startActivity(new Intent(c, activities[index]).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            });
            LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(0, -2, 1f);
            area.setMargins(dp(c, 3), 0, dp(c, 3), 0);
            row.addView(item, area);
        }
        return row;
    }

    static LinearLayout card(Context c) { return card(c, false); }
    static LinearLayout hero(Context c) { return card(c, true); }

    private static LinearLayout card(Context c, boolean highlight) {
        LinearLayout panel = vertical(c);
        GradientDrawable backdrop = highlight
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[] { 0xff214144, 0xff202e45, 0xff1a2336 })
                : bg(c, CARD, 23);
        backdrop.setCornerRadius(dp(c, 23));
        backdrop.setStroke(dp(c, 1), highlight ? 0xff436269 : BORDER);
        panel.setBackground(backdrop);
        int pad = dp(c, 16);
        panel.setPadding(pad, pad, pad, pad);
        return panel;
    }

    static LinearLayout vertical(Context c) {
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        return panel;
    }

    static void add(LinearLayout container, View child, int space) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(container.getContext(), space);
        container.addView(child, params);
    }

    static void divider(LinearLayout container, int top) {
        View line = new View(container.getContext());
        line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(container.getContext(), 1));
        p.topMargin = dp(container.getContext(), top);
        container.addView(line, p);
    }

    interface Choose { void selected(int value); }
    interface Toggle { void selected(boolean on); }

    static LinearLayout profiles(Context c, CaptureProfile initial, Choose choice) {
        LinearLayout grid = vertical(c);
        String[] names = profileLabels();
        LinearLayout[] tiles = new LinearLayout[names.length];
        for (int row = 0; row < 2; row++) {
            LinearLayout group = new LinearLayout(c);
            for (int col = 0; col < 2; col++) {
                int index = row * 2 + col;
                LinearLayout tile = vertical(c);
                tiles[index] = tile;
                tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.setPadding(dp(c, 11), dp(c, 10), dp(c, 9), dp(c, 10));
                tile.setMinimumHeight(dp(c, 68));
                String percent = CaptureProfile.at(index).percent + AppText.get(R.string.per_axis);
                tile.setContentDescription(names[index] + ", " + percent);
                TextView title = text(c, names[index], 13, INK, true);
                TextView subtitle = text(c, percent, 11, SOFT, false);
                title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                add(tile, title, 0);
                add(tile, subtitle, 4);
                filterStyle(tile, index == initial.ordinal());
                click(tile, () -> {
                    for (int i = 0; i < tiles.length; i++) filterStyle(tiles[i], i == index);
                    choice.selected(index);
                });
                LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(0, -1, 1);
                if (col != 0) area.leftMargin = dp(c, 8);
                group.addView(tile, area);
            }
            add(grid, group, row == 0 ? 0 : 8);
        }
        return grid;
    }

    private static void filterStyle(LinearLayout tile, boolean active) {
        tile.setSelected(active);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2383efc4),
                surface(tile.getContext(), active ? 0xff204744 : CONTROL, active ? 0xff619b8a : BORDER, 14), null));
        ((TextView) tile.getChildAt(0)).setTextColor(active ? LIME : INK);
    }

    static LinearLayout pills(Context c, String[] labels, int[] values, int initial, Choose action) {
        LinearLayout row = new LinearLayout(c);
        int p = dp(c, 3);
        row.setPadding(p, p, p, p);
        row.setBackground(bg(c, DARK, 13));
        TextView[] segments = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView view = button(c, labels[i], initial == values[i], () -> {
                for (int j = 0; j < segments.length; j++) {
                    segments[j].setSelected(j == index);
                    buttonStyle(segments[j], j == index);
                }
                action.selected(values[index]);
            });
            segments[i] = view;
            view.setSelected(initial == values[i]);
            view.setPadding(p, p, p, p);
            view.setTextSize(14);
            view.setMinHeight(dp(c, 44));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) space.leftMargin = p;
            row.addView(view, space);
        }
        return row;
    }

    static LinearLayout choice(Context c, String label, String[] labels, int[] values, int initial, Choose action) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 48));
        row.setBackground(touch(c));
        TextView title = text(c, label, 15, INK, true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        int[] current = { index(values, initial) };
        TextView value = text(c, labels[current[0]], 14, LIME, false);
        value.setGravity(Gravity.END);
        value.setMaxWidth(dp(c, 142));
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        TextView arrow = text(c, "›", 24, SOFT, false);
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(-2, -2);
        icon.leftMargin = dp(c, 8);
        row.addView(arrow, icon);
        row.setContentDescription(label + ", " + labels[current[0]]);
        click(row, () -> picker(c, label, labels, current[0], selected -> {
            current[0] = selected;
            value.setText(labels[selected]);
            row.setContentDescription(label + ", " + labels[selected]);
            action.selected(values[selected]);
        }));
        return row;
    }

    static TextView options(Context c, String label, String[] labels, int[] values, int initial, Choose action) {
        TextView[] button = new TextView[1];
        int[] current = { index(values, initial) };
        button[0] = button(c, label + "  ·  " + labels[current[0]], false, () ->
                picker(c, label, labels, current[0], selection -> {
                    current[0] = selection;
                    button[0].setText(label + "  ·  " + labels[selection]);
                    action.selected(values[selection]);
                }));
        return button[0];
    }

    private static void picker(Context c, String title, String[] choices, int current, Choose select) {
        new AlertDialog.Builder(c).setTitle(title)
                .setSingleChoiceItems(choices, current, (dialog, position) -> {
                    select.selected(position);
                    dialog.dismiss();
                }).setNegativeButton(AppText.get(R.string.cancel), null).show();
    }

    static Switch switcher(Context c, String title, boolean initial, Toggle change) {
        Switch s = new Switch(c);
        s.setShowText(false);
        s.setContentDescription(title);
        s.setMinHeight(dp(c, 48));
        s.setThumbTintList(new ColorStateList(new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { LIME, SOFT }));
        s.setTrackTintList(new ColorStateList(new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { 0xff32685e, 0xff3b4657 }));
        s.setChecked(initial);
        s.setOnCheckedChangeListener((button, checked) -> change.selected(checked));
        return s;
    }

    static LinearLayout switchRow(Context c, String title, String hint, Switch control) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(touch(c));
        LinearLayout wording = vertical(c);
        wording.setPadding(0, dp(c, 8), dp(c, 10), dp(c, 8));
        wording.addView(text(c, title, 15, INK, true));
        if (hint != null) add(wording, note(c, hint), 5);
        row.addView(wording, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(control, new LinearLayout.LayoutParams(-2, -2));
        wording.setOnClickListener(v -> {
            if (control.isEnabled()) control.toggle();
        });
        return row;
    }

    private static int index(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return 0;
    }

    private static final class Icon extends View {
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;

        Icon(Context context, int kind, int color) {
            super(context);
            this.kind = kind;
            outline.setColor(color);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(1.65f);
            outline.setStrokeJoin(Paint.Join.ROUND);
            outline.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            c.save();
            c.scale(getWidth() / 24f, getHeight() / 24f);
            switch (kind) {
                case GAME:
                    c.drawRoundRect(3, 7, 21, 17, 4, 4, outline);
                    c.drawLine(6, 12, 11, 12, outline);
                    c.drawLine(8.5f, 9.5f, 8.5f, 14.5f, outline);
                    c.drawCircle(16.6f, 11, 0.6f, outline);
                    c.drawCircle(18.2f, 13.2f, 0.6f, outline);
                    break;
                case VIDEO:
                    c.drawRoundRect(3, 5, 21, 19, 3, 3, outline);
                    c.drawLine(10, 9, 15, 12, outline);
                    c.drawLine(15, 12, 10, 15, outline);
                    c.drawLine(10, 15, 10, 9, outline);
                    break;
            }
            c.restore();
        }
    }
}
