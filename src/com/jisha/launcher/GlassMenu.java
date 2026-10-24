package com.jisha.launcher;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * A glass options list that slides in from the right, styled like {@link SettingsPanel}.
 * Used for an app tile's options (move, hide, app info…) and the hidden-apps picker.
 */
final class GlassMenu extends FrameLayout {

    /** One row in the menu. */
    static final class Item {
        final int icon;
        final String label;
        final Runnable action;

        Item(int icon, String label, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.action = action;
        }
    }

    private static final int WIDTH_DP = 230;

    private final View scrim;
    private final LinearLayout panel;
    private Runnable onClosed;
    private boolean open;

    GlassMenu(Context ctx) {
        super(ctx);
        setVisibility(GONE);

        scrim = new View(ctx);
        scrim.setBackgroundColor(0x40000000);
        addView(scrim, new LayoutParams(-1, -1));

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
        panel.setClipToOutline(true);
        panel.setElevation(dp(24));
        LayoutParams plp = new LayoutParams(dp(WIDTH_DP), -1, Gravity.END);
        plp.setMargins(0, dp(20), dp(40), dp(20));
        addView(panel, plp);
    }

    boolean isOpen() {
        return open;
    }

    /**
     * Shows the menu.
     *
     * @param icon     optional artwork shown in the header card (e.g. the app's banner)
     * @param onClosed run after the menu closes without an action (Back / Left)
     */
    void show(String title, Drawable icon, List<Item> items, Runnable onClosed) {
        this.onClosed = onClosed;
        panel.removeAllViews();
        panel.addView(header(title, icon), new LinearLayout.LayoutParams(-1, -2));

        View rule = new View(getContext());
        rule.setBackgroundColor(Theme.rule());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        rlp.setMargins(dp(4), dp(14), dp(4), dp(6));
        panel.addView(rule, rlp);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        View first = null;
        for (int i = 0; i < items.size(); i++) {
            View row = row(list, items.get(i), i == items.size() - 1);
            if (first == null) first = row;
        }

        boolean wasOpen = open;
        open = true;
        panel.setBackground(frosted());
        setVisibility(VISIBLE);
        if (!wasOpen) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(220).start();
            panel.setTranslationX(dp(WIDTH_DP + 40));
            panel.animate().translationX(0).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (first != null) first.requestFocus();
    }

    /** Closes the menu, then runs {@code after} (or the close callback when null). */
    void hide(final Runnable after) {
        if (!open) return;
        open = false;
        final Runnable closed = onClosed;
        scrim.animate().alpha(0f).setDuration(180).start();
        panel.animate().translationX(dp(WIDTH_DP + 40)).setDuration(200)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    if (after != null) {
                        after.run();
                    } else if (closed != null) {
                        closed.run();
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

    /** Keeps focus inside the menu; Left closes it (like Back). */
    @Override
    public View focusSearch(View focused, int direction) {
        View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
        if (next == null && direction == View.FOCUS_LEFT) hide(null);
        return next != null ? next : focused;
    }

    // ---------------------------------------------------------------- parts

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp, int color, String family) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View header(String title, Drawable icon) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        if (icon != null) {
            FrameLayout art = new FrameLayout(ctx);
            art.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                }
            });
            art.setClipToOutline(true);
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(icon);
            art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(80), dp(45));
            alp.rightMargin = dp(12);
            card.addView(art, alp);
        }
        TextView t = text(title, 17, Theme.fg(), "sans-serif-medium");
        card.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    private View row(LinearLayout list, final Item item, boolean last) {
        Context ctx = getContext();
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setPadding(dp(8), 0, dp(10), 0);

        final GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(Theme.soft());
        FrameLayout bubble = new FrameLayout(ctx);
        bubble.setBackground(bubbleBg);
        final ImageView iv = new ImageView(ctx);
        iv.setImageResource(item.icon);
        iv.setColorFilter(Theme.fg());
        bubble.addView(iv, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        row.addView(bubble, new LinearLayout.LayoutParams(dp(30), dp(30)));

        final TextView t = text(item.label, 15, Theme.fg(), "sans-serif");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        row.addView(t, tlp);

        row.setOnFocusChangeListener((v, has) -> {
            if (has) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(Theme.focusFill());
                pill.setCornerRadius(dp(22)); // fully rounded, like tvOS
                row.setBackground(pill);
            } else {
                row.setBackground(null);
            }
            t.setTextColor(has ? Theme.focusText() : Theme.fg());
            t.setTypeface(Typeface.create(has ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            bubbleBg.setColor(has ? Theme.focusText() : Theme.soft());
            iv.setColorFilter(has ? Theme.focusFill() : Theme.fg());
        });
        row.setOnClickListener(v -> hide(item.action));
        list.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));

        if (!last) {
            View r = new View(ctx);
            r.setBackgroundColor(Theme.rule());
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            rlp.setMargins(dp(50), dp(1), dp(10), dp(1));
            list.addView(r, rlp);
        }
        return row;
    }

    // ---------------------------------------------------------------- glass

    private Drawable tint() {
        return Glass.tint();
    }

    /** Blurred snapshot of the screen behind the panel with the glass tint on top. */
    private Drawable frosted() {
        View behind = (View) getParent();
        if (behind == null) return tint();
        int w = dp(WIDTH_DP), h = behind.getHeight() - dp(40);
        return Glass.frost(behind, this, behind.getWidth() - dp(40) - w, dp(20), w, h);
    }
}
