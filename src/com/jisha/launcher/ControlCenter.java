package com.jisha.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Control Center: glass tiles over the home screen for Power Off, Day/Night, Standby and RAM.
 */
final class ControlCenter extends FrameLayout {

    /** What the home screen does for the tiles. */
    interface Actions {
        void powerOff();

        void toggleNight();

        void standby();

        void closed();
    }

    /** Small tiles are wide pills (W x H); Power Off spans two rows. */
    private static final int W = 106, H = 54, GAP = 10; // dp

    private final Actions actions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView blur;
    private final FrameLayout copies;
    private final GridLayout grid;
    private TextView ramText, nightTitle, nightSub;
    private ImageView nightIcon;
    private View ramFill, clearButton, firstTile, nightTile;
    private boolean open, clearing;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!open) return;
            updateRam();
            main.postDelayed(this, 2000);
        }
    };

    ControlCenter(Context ctx, Actions actions) {
        super(ctx);
        this.actions = actions;
        setVisibility(GONE);

        blur = new ImageView(ctx);
        blur.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(blur, new LayoutParams(-1, -1));
        View dim = new View(ctx);
        dim.setTag("dim");
        addView(dim, new LayoutParams(-1, -1));

        copies = new FrameLayout(ctx);
        addView(copies, new LayoutParams(-1, -1));

        grid = new GridLayout(ctx);
        grid.setColumnCount(2);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        addView(grid, new LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
    }

    boolean isOpen() {
        return open;
    }

    /**
     * Opens over {@code home}, keeping sharp copies of {@code keep} (the top-right buttons).
     */
    void show(View home, View[] keep, View anchor, boolean focusNight) {
        if (open) return;
        open = true;

        // No blur or dimming: the home screen stays visible as it is, and the top
        // buttons are the real ones underneath.
        blur.setImageDrawable(null);
        findViewWithTag("dim").setBackgroundColor(0);
        setBackgroundColor(0);
        copies.removeAllViews();
        int[] me = new int[2];
        home.getLocationInWindow(me);

        // Tiles sit under the Control Center button, right-aligned with it.
        buildTiles();
        int[] a = new int[2];
        anchor.getLocationInWindow(a);
        LayoutParams glp = (LayoutParams) grid.getLayoutParams();
        glp.topMargin = a[1] - me[1] + anchor.getHeight() + dp(24);
        glp.rightMargin = Math.max(dp(16), home.getWidth() - (a[0] - me[0] + anchor.getWidth()));
        grid.setLayoutParams(glp);

        // The buttons appear instantly (they match what's underneath); only the blur and tiles fade in.
        setVisibility(VISIBLE);
        setAlpha(1f);
        for (View v : new View[]{grid}) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(220).start();
        }
        grid.setTranslationY(-dp(12));
        grid.animate().translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        updateRam();
        main.postDelayed(ticker, 2000);
        (focusNight ? nightTile : firstTile).requestFocus();
    }

    /** Re-draws the tiles in the current Day/Night colours, keeping the panel open. */
    void restyle() {
        if (!open) return;
        buildTiles();
        updateRam();
        grid.setAlpha(1f);
        grid.post(() -> {
            if (open && nightTile != null) nightTile.requestFocus();
        });
    }

    void hide(final Runnable after) {
        if (!open) return;
        open = false;
        main.removeCallbacks(ticker);
        animate().alpha(0f).setDuration(180).withEndAction(() -> {
            setVisibility(GONE);
            blur.setImageDrawable(null);
            copies.removeAllViews();
            if (after != null) {
                after.run();
            } else {
                actions.closed();
            }
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (open && (ev.getKeyCode() == KeyEvent.KEYCODE_BACK || ev.getKeyCode() == KeyEvent.KEYCODE_MENU)) {
            if (ev.getAction() == KeyEvent.ACTION_UP) hide(null);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** Keeps focus on the tiles; Up from the top row closes (back to the button). */
    @Override
    public View focusSearch(View focused, int direction) {
        View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
        if (next == null && direction == View.FOCUS_UP) hide(null);
        return next != null ? next : focused;
    }

    // ---------------------------------------------------------------- tiles

    private void buildTiles() {
        grid.removeAllViews();
        int w = dp(W), h = dp(H), g = dp(GAP);

        // Power Off: big square tile.
        LinearLayout power = tile(true);
        power.setOrientation(LinearLayout.VERTICAL);
        power.setGravity(Gravity.CENTER);
        ImageView pi = icon(R.drawable.ic_power, dp(40));
        power.addView(pi, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView pt = text("Power Off", 13);
        LinearLayout.LayoutParams ptl = new LinearLayout.LayoutParams(-2, -2);
        ptl.topMargin = dp(8);
        power.addView(pt, ptl);
        power.setOnClickListener(v -> hide(actions::powerOff));
        add(power, 0, 0, 1, w, h * 2 + g);
        firstTile = power;

        // Day / Night.
        LinearLayout night = tile(false);
        nightIcon = icon(Theme.night ? R.drawable.ic_moon : R.drawable.ic_sun, dp(26));
        night.addView(bubble(nightIcon));
        LinearLayout nw = words();
        nightTitle = text(Theme.night ? "Night" : "Day", 13);
        nw.addView(nightTitle);
        night.addView(nw, wordsLp());
        night.setOnClickListener(v -> actions.toggleNight());
        add(night, 0, 1, 1, w, h);
        nightTile = night;

        // Standby.
        LinearLayout standby = tile(false);
        standby.addView(bubble(icon(R.drawable.ic_standby, dp(26))));
        LinearLayout sw = words();
        sw.addView(text("Standby", 13));
        standby.addView(sw, wordsLp());
        standby.setOnClickListener(v -> hide(actions::standby));
        add(standby, 1, 1, 1, w, h);

        // RAM: bar with live usage and a round Clear RAM button.
        LinearLayout ram = tile(false);
        ram.setFocusable(false);
        ram.setFocusableInTouchMode(false);
        ram.setPadding(dp(12), 0, dp(6), 0);
        // Let the Clear RAM button grow when focused without being cut off.
        ram.setClipToOutline(false);
        ram.setClipChildren(false);
        ram.setClipToPadding(false);
        ram.addView(bubble(icon(R.drawable.ic_memory, dp(24))));
        LinearLayout rw = new LinearLayout(getContext());
        rw.setOrientation(LinearLayout.VERTICAL);
        FrameLayout head = new FrameLayout(getContext());
        head.addView(text("RAM", 12), new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        ramText = small("");
        head.addView(ramText, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        rw.addView(head, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout bar = new FrameLayout(getContext());
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(4));
        track.setColor(Theme.fg(0x33));
        bar.setBackground(track);
        ramFill = new View(getContext());
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(4));
        fill.setColor(Theme.fg());
        ramFill.setBackground(fill);
        ramFill.setPivotX(0);
        ramFill.setScaleX(0f);
        bar.addView(ramFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(5));
        blp.topMargin = dp(6);
        rw.addView(bar, blp);
        LinearLayout.LayoutParams rwl = new LinearLayout.LayoutParams(0, -2, 1f);
        rwl.leftMargin = dp(8);
        rwl.rightMargin = dp(8);
        ram.addView(rw, rwl);
        clearButton = roundButton();
        ram.addView(clearButton, new LinearLayout.LayoutParams(dp(34), dp(34)));
        add(ram, 2, 0, 2, w * 2 + g, dp(54));
    }

    private void add(View v, int row, int col, int colSpan, int w, int h) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(row, row == 0 && col == 0 ? 2 : 1), GridLayout.spec(col, colSpan));
        lp.width = w;
        lp.height = h;
        int g = dp(GAP) / 2;
        lp.setMargins(g, g, g, g);
        grid.addView(v, lp);
    }

    /** A glass tile; when focused it turns solid like tvOS. */
    private LinearLayout tile(boolean big) {
        final LinearLayout t = new LinearLayout(getContext());
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(8), 0);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClipToOutline(true);
        final int radius = dp(big ? 22 : 27);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        t.setBackground(glass(radius, false));
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(glass(radius, has));
            tint(t, has ? Theme.focusText() : Theme.fg());
            t.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(160).start();
        });
        return t;
    }

    private GradientDrawable glass(float radius, boolean focused) {
        GradientDrawable g;
        if (focused) {
            g = new GradientDrawable();
            g.setColor(Theme.focusFill());
        } else {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Theme.night
                    ? new int[]{0x4DFFFFFF, 0x1FFFFFFF} : new int[]{0xB3FFFFFF, 0x80FFFFFF});
            g.setStroke(Math.max(1, dp(1)), Theme.night ? 0x40FFFFFF : 0x99FFFFFF);
        }
        g.setCornerRadius(radius);
        return g;
    }

    /** Recolours every icon and text inside a tile (focus flips white/dark). */
    private static void tint(View v, int color) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextColor(tv.getTag() != null ? (color & 0x00FFFFFF) | 0xA6000000 : color);
        } else if (v instanceof ImageView) {
            ((ImageView) v).setColorFilter(color);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tint(g.getChildAt(i), color);
        }
    }

    private View roundButton() {
        final FrameLayout b = new FrameLayout(getContext());
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setContentDescription("Clear RAM");
        final ImageView iv = icon(R.drawable.ic_clean, dp(18));
        b.addView(iv, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        final Runnable paint = () -> {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(b.isFocused() ? Theme.focusFill() : Theme.soft());
            b.setBackground(g);
            iv.setColorFilter(b.isFocused() ? Theme.focusText() : Theme.fg());
        };
        paint.run();
        b.setOnFocusChangeListener((v, has) -> {
            paint.run();
            b.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        b.setOnClickListener(v -> clearRam());
        return b;
    }

    private void clearRam() {
        if (clearing) return;
        clearing = true;
        ramText.setText("Clearing…");
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            final long freed = RamTools.clear(app);
            main.post(() -> {
                clearing = false;
                if (!open) return;
                ramText.setText("Freed " + RamTools.mb(freed));
                main.postDelayed(this::updateRam, 1500);
            });
        }, "jisha-cc-clear").start();
    }

    private void updateRam() {
        if (clearing || ramText == null) return;
        long total = RamTools.total(getContext()), used = RamTools.used(getContext());
        if (!ramText.getText().toString().startsWith("Freed")) {
            ramText.setText(RamTools.mb(used) + " of " + RamTools.mb(total));
        } else {
            ramText.setText(RamTools.mb(used) + " of " + RamTools.mb(total));
        }
        float f = total == 0 ? 0f : used / (float) total;
        ramFill.animate().scaleX(Math.max(0.02f, f)).setDuration(400).start();
    }

    // ---------------------------------------------------------------- small parts

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Theme.fg());
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView small(String s) {
        TextView t = text(s, 10);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setTag("small");
        t.setTextColor(Theme.fg(0xA6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(3);
        t.setLayoutParams(lp);
        return t;
    }

    private ImageView icon(int res, int size) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setColorFilter(Theme.fg());
        return iv;
    }

    private View bubble(ImageView iv) {
        FrameLayout f = new FrameLayout(getContext());
        f.addView(iv, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        return f;
    }

    private LinearLayout words() {
        LinearLayout w = new LinearLayout(getContext());
        w.setOrientation(LinearLayout.VERTICAL);
        return w;
    }

    private LinearLayout.LayoutParams wordsLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(8);
        return lp;
    }
}
