package com.jisha.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
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

/**
 * Glass settings menu that slides in from the right: a profile card, an Appearance page
 * (Day/Night and glass opacity), the Fire TV settings pages that can be opened directly,
 * and Settings/Help buttons.
 */
final class SettingsPanel extends FrameLayout {

    private static final int WIDTH_DP = 230;
    private static final String PAGE_MENU = "menu";
    private static final String PAGE_APPEARANCE = "appearance";

    private final HomeScreen.Host host;
    private final Runnable onThemeChanged;
    private final Runnable onClosed;
    private final Prefs prefs;
    private final View scrim;
    private final LinearLayout panel;
    private String page = PAGE_MENU;
    /** Tag of the control to focus after the panel is rebuilt. */
    private String focusTag;
    private boolean open;

    SettingsPanel(Context ctx, HomeScreen.Host host, Runnable onThemeChanged, Runnable onClosed) {
        super(ctx);
        this.host = host;
        this.onThemeChanged = onThemeChanged;
        this.onClosed = onClosed;
        this.prefs = new Prefs(ctx);
        setVisibility(GONE);
        setClipChildren(false);

        scrim = new View(ctx);
        scrim.setBackgroundColor(0x40000000);
        addView(scrim, new LayoutParams(-1, -1));

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(10));
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
        build();
    }

    // ---------------------------------------------------------------- public

    boolean isOpen() {
        return open;
    }

    void show() {
        if (open) return;
        open = true;
        page = PAGE_MENU;
        focusTag = "Appearance";
        build();
        panel.setBackground(frosted());
        setVisibility(VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();
        panel.setTranslationX(dp(WIDTH_DP + 40));
        panel.animate().translationX(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
        restoreFocus();
    }

    void hide(final Runnable after) {
        if (!open) return;
        open = false;
        scrim.animate().alpha(0f).setDuration(180).start();
        panel.animate().translationX(dp(WIDTH_DP + 40)).setDuration(200)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    if (after != null) {
                        after.run();
                    } else if (onClosed != null) {
                        onClosed.run();
                    }
                }).start();
    }

    /** Re-applies the theme after the home screen behind was restyled. */
    void refresh() {
        build();
        if (!open) return;
        panel.setBackground(tint());
        // Take the frosted snapshot once the restyled home screen has been laid out.
        restoreFocus();
        // The restyled home screen may grab focus while it settles; take it back.
        postDelayed(() -> {
            if (!open) return;
            panel.setBackground(frosted());
            restoreFocus();
        }, 150);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (open && ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                if (PAGE_APPEARANCE.equals(page)) {
                    page = PAGE_MENU;
                    focusTag = "Appearance";
                    build();
                    restoreFocus();
                } else {
                    hide(null);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** Keeps focus inside the menu; Left with nowhere to go closes it (like Back). */
    @Override
    public View focusSearch(View focused, int direction) {
        View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
        if (next == null && direction == View.FOCUS_LEFT && PAGE_MENU.equals(page)) {
            hide(null);
        }
        return next != null ? next : focused;
    }

    // ---------------------------------------------------------------- building

    private void build() {
        panel.removeAllViews();
        if (PAGE_APPEARANCE.equals(page)) {
            buildAppearance();
        } else {
            buildMenu();
        }
    }

    private void restoreFocus() {
        View v = focusTag == null ? null : panel.findViewWithTag(focusTag);
        if (v != null) v.requestFocus();
    }

    private void buildMenu() {
        Context ctx = getContext();
        panel.addView(profileCard(), new LinearLayout.LayoutParams(-1, -2));
        panel.addView(rule(), ruleParams(dp(14), dp(6)));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        item(list, R.drawable.ic_appearance, "Appearance", false, () -> {
            page = PAGE_APPEARANCE;
            focusTag = Theme.night ? "Night" : "Day";
            build();
            restoreFocus();
        });
        item(list, R.drawable.ic_bell, "Notifications", false,
                launcher(new Intent("com.amazon.tv.action.NOTIFICATION_CENTER"), "Notifications"));
        item(list, R.drawable.ic_wifi, "Network", false,
                launcher(new Intent(Settings.ACTION_WIFI_SETTINGS), "Network"));
        item(list, R.drawable.ic_apps, "Applications", false,
                launcher(new Intent("com.amazon.device.settings.action.APPLICATIONS"), "Applications"));
        item(list, R.drawable.ic_equipment, "Equipment Control", false, launcher(new Intent().setComponent(
                new ComponentName("com.amazon.tv.devicecontrolsettings",
                        "com.amazon.tv.devicecontrolsettings.screens.main_menu.OzOobeSettingsActivity")),
                "Equipment Control"));
        item(list, R.drawable.ic_remote, "Remote & Bluetooth", false,
                launcher(new Intent("android.settings.CONTROLLERS_SETTINGS"), "Remote & Bluetooth"));
        item(list, R.drawable.ic_tv, "My Fire TV", true,
                launcher(new Intent("com.amazon.device.settings.action.DEVICE"), "My Fire TV"));

        // Bottom-right: Settings (full Fire TV settings) and Help, with room to grow when focused.
        Intent grid = new Intent(Settings.ACTION_SETTINGS);
        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        bottom.setPadding(dp(6), dp(6), dp(6), dp(6));
        bottom.addView(roundButton(R.drawable.ic_settings, "Settings", dp(40), launcher(grid, "Settings")),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        hlp.leftMargin = dp(12);
        bottom.addView(roundButton(R.drawable.ic_help, "Help", dp(40), launcher(grid, "Help")), hlp);
        panel.setClipChildren(false);
        panel.addView(bottom, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void buildAppearance() {
        Context ctx = getContext();

        // Header: back arrow + title (Back on the remote also returns to the menu).
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // Back arrow: focusable (Up from Day/Night reaches it); OK returns to the menu.
        header.setClipChildren(false);
        header.addView(roundButton(R.drawable.ic_back, "back", dp(32), () -> {
            page = PAGE_MENU;
            focusTag = "Appearance";
            build();
            restoreFocus();
        }), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView title = text("Appearance", 20, Theme.fg(), "sans-serif-medium");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(8);
        header.addView(title, tlp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.setMargins(dp(4), dp(8), 0, 0);
        panel.addView(header, hlp);
        panel.addView(rule(), ruleParams(dp(16), dp(18)));

        // Mode: Day / Night segmented control.
        panel.addView(label("MODE"), labelParams());
        LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setClipChildren(false);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(Theme.soft());
        segBg.setCornerRadius(dp(22));
        seg.setBackground(segBg);
        seg.addView(segment(R.drawable.ic_sun, "Day", !Theme.night), new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        nlp.leftMargin = dp(4);
        seg.addView(segment(R.drawable.ic_moon, "Night", Theme.night), nlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(dp(2), dp(10), dp(2), 0);
        panel.addView(seg, slp);

        // Glass opacity slider.
        FrameLayout opRow = new FrameLayout(ctx);
        opRow.addView(label("GLASS OPACITY"), new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        final TextView value = text(Theme.opacity + "%", 13, Theme.fg(0xCC), "sans-serif-medium");
        opRow.addView(value, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.setMargins(dp(6), dp(28), dp(6), 0);
        panel.addView(opRow, olp);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(40));
        sl.setMargins(dp(4), dp(10), dp(4), 0);
        panel.addView(slider(value), sl);

        TextView hint = text("◀ ▶ to adjust", 12, Theme.fg(0x80), "sans-serif");
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hint2 = new LinearLayout.LayoutParams(-1, -2);
        hint2.topMargin = dp(10);
        panel.addView(hint, hint2);
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

    private TextView label(String s) {
        TextView t = text(s, 11, Theme.fg(0x99), "sans-serif-medium");
        t.setLetterSpacing(0.15f);
        return t;
    }

    private LinearLayout.LayoutParams labelParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6);
        return lp;
    }

    private View rule() {
        View v = new View(getContext());
        v.setBackgroundColor(Theme.rule());
        return v;
    }

    private LinearLayout.LayoutParams ruleParams(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.setMargins(dp(4), top, dp(4), bottom);
        return lp;
    }

    private Runnable launcher(final Intent intent, final String label) {
        return () -> {
            Intent i = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PackageManager pm = getContext().getPackageManager();
            if (i.resolveActivity(pm) == null) {
                i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            final Intent go = i;
            hide(() -> host.openApp(go, label));
        };
    }

    private View profileCard() {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(10), dp(8));

        String name = userName();
        TextView avatar = text(name.substring(0, 1).toUpperCase(), 15, Color.WHITE, "sans-serif-medium");
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF7A59, 0xFFB43AF5});
        circle.setShape(GradientDrawable.OVAL);
        avatar.setBackground(circle);
        card.addView(avatar, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout words = new LinearLayout(ctx);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(10), 0, dp(4), 0);
        words.addView(text(name, 15, Theme.fg(), "sans-serif-medium"));

        card.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    /** The TV's name is usually "<Name>'s Fire TV"; show just the name. */
    private String userName() {
        if (Demo.on(getContext())) return Demo.PROFILE;
        String n = null;
        try {
            n = Settings.Global.getString(getContext().getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().isEmpty()) return "Fire TV";
        n = n.trim();
        int i = n.indexOf("'s ");
        if (i < 0) i = n.indexOf("’s ");
        return i > 0 ? n.substring(0, i) : n;
    }

    private View roundButton(int icon, String name, final int size, final Runnable action) {
        final FrameLayout b = new FrameLayout(getContext());
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setContentDescription(name);
        b.setTag(name);
        b.setBackground(roundBg(false));
        final ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setColorFilter(Theme.fg());
        b.addView(iv, new FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(roundBg(has));
            iv.setColorFilter(has ? Theme.focusText() : Theme.fg());
            b.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable roundBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(focused ? Theme.focusFill() : Theme.soft());
        return g;
    }

    private void item(LinearLayout list, int icon, String label, boolean last, final Runnable action) {
        Context ctx = getContext();
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setTag(label);
        row.setPadding(dp(8), 0, dp(10), 0);

        final GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(Theme.soft());
        FrameLayout bubble = new FrameLayout(ctx);
        bubble.setBackground(bubbleBg);
        final ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        iv.setColorFilter(Theme.fg());
        bubble.addView(iv, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        row.addView(bubble, new LinearLayout.LayoutParams(dp(30), dp(30)));

        final TextView t = text(label, 15, Theme.fg(), "sans-serif");
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
            // Icon bubble flips colours with the pill so the icon stays readable.
            bubbleBg.setColor(has ? Theme.focusText() : Theme.soft());
            iv.setColorFilter(has ? Theme.focusFill() : Theme.fg());
        });
        row.setOnClickListener(v -> action.run());
        list.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));

        if (!last) {
            View r = rule();
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            rlp.setMargins(dp(50), dp(1), dp(10), dp(1));
            list.addView(r, rlp);
        }
    }

    /** One half of the Day/Night switch. */
    private View segment(int icon, final String name, final boolean selected) {
        Context ctx = getContext();
        final LinearLayout s = new LinearLayout(ctx);
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER);
        s.setFocusable(true);
        s.setFocusableInTouchMode(true);
        s.setTag(name);
        final ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        s.addView(iv, new LinearLayout.LayoutParams(dp(16), dp(16)));
        final TextView t = text(name, 14, Theme.fg(), "sans-serif-medium");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(6);
        s.addView(t, tlp);

        final Runnable paint = () -> {
            boolean focused = s.isFocused();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            int fg;
            if (focused) {
                bg.setColor(Theme.focusFill());
                fg = Theme.focusText();
            } else if (selected) {
                bg.setColor(Theme.night ? Theme.col(0xFFFFFF, 0.28f) : Theme.col(0xFFFFFF, 0.95f));
                fg = Theme.fg();
            } else {
                bg.setColor(Color.TRANSPARENT);
                fg = Theme.fg(0xAA);
            }
            s.setBackground(bg);
            t.setTextColor(fg);
            iv.setColorFilter(fg);
        };
        paint.run();
        s.setOnFocusChangeListener((v, has) -> paint.run());
        s.setOnClickListener(v -> {
            boolean wantNight = "Night".equals(name);
            if (wantNight == Theme.night) return;
            prefs.setNight(wantNight);
            Theme.night = wantNight;
            focusTag = name;
            onThemeChanged.run();
        });
        return s;
    }

    /** Opacity slider: Left/Right moves it in 10% steps; the change applies live. */
    private View slider(final TextView value) {
        Context ctx = getContext();
        final FrameLayout track = new FrameLayout(ctx);
        track.setFocusable(true);
        track.setFocusableInTouchMode(true);
        track.setTag("Opacity");
        track.setClipChildren(false);
        track.setClipToPadding(false);

        final View rail = new View(ctx);
        final GradientDrawable railBg = new GradientDrawable();
        railBg.setCornerRadius(dp(4));
        railBg.setColor(Theme.soft());
        rail.setBackground(railBg);
        track.addView(rail, new FrameLayout.LayoutParams(-1, dp(8), Gravity.CENTER_VERTICAL));

        final View fill = new View(ctx);
        final GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(dp(4));
        fill.setBackground(fillBg);
        fill.setPivotX(0);
        track.addView(fill, new FrameLayout.LayoutParams(-1, dp(8), Gravity.CENTER_VERTICAL));

        final View knob = new View(ctx);
        final GradientDrawable knobBg = new GradientDrawable();
        knobBg.setShape(GradientDrawable.OVAL);
        knob.setBackground(knobBg);
        knob.setElevation(dp(3));
        track.addView(knob, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL));

        final Runnable paint = () -> {
            boolean focused = track.isFocused();
            int w = track.getWidth();
            int x = Math.round((w - dp(22)) * Theme.opacity / 100f);
            // The fill is full width and scaled, so no relayout is needed while sliding.
            fill.setScaleX(w == 0 ? 0f : (x + dp(11)) / (float) w);
            knob.setTranslationX(x);
            fillBg.setColor(focused ? Theme.fg() : Theme.fg(0x99));
            knobBg.setColor(Color.WHITE);
            knobBg.setStroke(Math.max(1, dp(focused ? 3 : 1)),
                    focused ? (Theme.night ? 0xFF8E8E93 : 0xFF1C1C1E) : Theme.col(0x000000, 0.15f));
            knob.setScaleX(focused ? 1.15f : 1f);
            knob.setScaleY(focused ? 1.15f : 1f);
            value.setText(Theme.opacity + "%");
        };
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint.run());
        track.setOnFocusChangeListener((v, has) -> paint.run());
        track.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                int next = Theme.opacity + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10);
                next = Math.max(0, Math.min(100, next));
                if (next != Theme.opacity) {
                    Theme.opacity = next;
                    prefs.setOpacity(next);
                    paint.run();
                    focusTag = "Opacity";
                    onThemeChanged.run();
                }
            }
            return true;
        });
        return track;
    }

    // ---------------------------------------------------------------- glass

    /** Translucent tint for the glass. */
    private Drawable tint() {
        return Glass.tint();
    }

    /**
     * Fake "live" glass: snapshot of the home screen behind the panel, shrunk and scaled back
     * up so it looks blurred, with the tint on top. Cheap enough for this stick's GPU.
     */
    private Drawable frosted() {
        View behind = (View) getParent();
        if (behind == null) return tint();
        int w = dp(WIDTH_DP), h = behind.getHeight() - dp(40);
        return Glass.frost(behind, this, behind.getWidth() - dp(40) - w, dp(20), w, h);
    }
}
