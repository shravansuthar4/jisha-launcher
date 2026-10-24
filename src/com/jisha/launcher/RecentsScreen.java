package com.jisha.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen "Recent Apps": RAM usage at the top right, open apps as cards (newest first).
 * Left/Right moves, OK switches to the app, Up closes it, Back returns to the home screen.
 * When the last app is closed, the home screen is shown.
 */
final class RecentsScreen extends FrameLayout {

    private static final String TAG = "Jisha";
    private static final int CARD_W = 300, CARD_H = 169, GAP = 36;
    private static final int MOVE_MS = 340;
    private static final Interpolator EASE = new AccelerateDecelerateInterpolator();
    /** Never closed by Clear RAM: Jisha itself, Amazon's home service, keyboard and core system. */
    private static final Set<String> KEEP = new HashSet<>(java.util.Arrays.asList(
            "com.jisha.launcher", "com.amazon.tv.launcher", "android", "system", "com.android.systemui",
            "com.android.phone", "com.amazon.tv.ime", "com.android.inputmethod.pinyin",
            "com.android.providers.settings", "com.android.bluetooth"));
    private static final float SMALL = 0.84f;
    /** Spacing of the stacked cards (dp, between visual left edges), like the tvOS switcher. */
    private static final int OVERLAP = 48;
    private static final int CLEAR_GLOW = 0xFF3A6FD8;

    private final HomeScreen.Host host;
    private final Map<String, HomeScreen.App> apps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View glowA, glowB;
    private boolean glowOnA = true;
    private int glowColor;
    private boolean clearing;
    private final TextView ramText;
    private final View ramFill;
    /** Holds the Clear RAM tile (slot 0) and one card per open app (slots 1…n). */
    private final FrameLayout stage;
    private final View clearTile;
    private final FrameLayout clearCard;
    private final ImageView clearIcon;
    private final TextView clearTitle, clearSub;
    private final Map<String, View> cardViews = new java.util.HashMap<>();
    /** The focus border of each slot's card (Clear RAM tile included), keyed by holder view. */
    private final Map<View, GradientDrawable> borders = new java.util.HashMap<>();
    private final TextView empty, hint;
    private final List<String> ids = new ArrayList<>();
    /** Selected slot: 0 = Clear RAM, 1… = apps. */
    private int sel = 1;
    private boolean open;
    private int loadToken;
    private long openedAt;
    /** Told when this screen hides itself or closes an app, so other copies stay in sync. */
    interface Listener {
        void onRecentsHidden(RecentsScreen from);

        void onRecentsChanged(RecentsScreen from);
    }

    private Listener listener;

    void setListener(Listener l) {
        listener = l;
    }

    RecentsScreen(Context ctx, HomeScreen.Host host, Map<String, HomeScreen.App> apps) {
        super(ctx);
        this.host = host;
        this.apps = apps;
        setVisibility(GONE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Theme.base());

        glowA = new View(ctx);
        glowB = new View(ctx);
        glowB.setAlpha(0f);
        addView(glowA, new LayoutParams(-1, -1));
        addView(glowB, new LayoutParams(-1, -1));

        // Top left: title.
        TextView title = text("Recent Apps", 26, Theme.fg(), "sans-serif-medium");
        LayoutParams tlp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.setMargins(dp(60), dp(44), 0, 0);
        addView(title, tlp);

        // Top right: RAM glass pill with a bar.
        LinearLayout ram = new LinearLayout(ctx);
        ram.setOrientation(LinearLayout.HORIZONTAL);
        ram.setGravity(Gravity.CENTER_VERTICAL);
        ram.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Theme.glass(false));
        pill.setCornerRadius(dp(20));
        pill.setStroke(Math.max(1, dp(1)), Theme.glassRim(false));
        ram.setBackground(pill);
        ramText = text("RAM", 13, Theme.fg(), "sans-serif-medium");
        ram.addView(ramText);
        FrameLayout bar = new FrameLayout(ctx);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(3));
        track.setColor(Theme.fg(0x40));
        bar.setBackground(track);
        ramFill = new View(ctx);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(3));
        fill.setColor(Theme.fg());
        ramFill.setBackground(fill);
        ramFill.setPivotX(0);
        bar.addView(ramFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(90), dp(6));
        blp.leftMargin = dp(14);
        ram.addView(bar, blp);
        LayoutParams rlp = new LayoutParams(-2, dp(40), Gravity.TOP | Gravity.END);
        rlp.setMargins(0, dp(38), dp(48), 0);
        addView(ram, rlp);

        // Cards are positioned by translationX inside a full-width stage.
        stage = new FrameLayout(ctx);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        setClipChildren(false);
        LayoutParams slp = new LayoutParams(-1, dp(CARD_H + 34 + 40), Gravity.CENTER_VERTICAL);
        slp.topMargin = dp(20);
        addView(stage, slp);

        // Clear RAM tile: a glass card at the start of the row.
        LinearLayout ch = new LinearLayout(ctx);
        ch.setOrientation(LinearLayout.VERTICAL);
        ch.setClipChildren(false);
        TextView cl = text("Clear RAM", 14, Theme.fg(), "sans-serif-medium");
        LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(-2, dp(24));
        cll.bottomMargin = dp(10);
        ch.addView(cl, cll);
        clearCard = rounded(dp(18));
        clearCard.setElevation(dp(12));
        clearCard.setForeground(border(clearCard));
        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        clearIcon = new ImageView(ctx);
        clearIcon.setImageResource(R.drawable.ic_clean);
        inner.addView(clearIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        clearTitle = text("Clear RAM", 20, Theme.fg(), "sans-serif-medium");
        clearTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(-2, -2);
        ctl.topMargin = dp(12);
        inner.addView(clearTitle, ctl);
        clearSub = text("Close background apps", 12, Theme.fg(0x99), "sans-serif");
        LinearLayout.LayoutParams csl = new LinearLayout.LayoutParams(-2, -2);
        csl.topMargin = dp(6);
        inner.addView(clearSub, csl);
        clearCard.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        ch.addView(clearCard, new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)));
        clearTile = ch;
        borders.put(clearTile, (GradientDrawable) clearCard.getForeground());
        stage.addView(clearTile, new FrameLayout.LayoutParams(dp(CARD_W), -2, Gravity.START | Gravity.TOP));
        paintClear(false);

        empty = text("No apps open", 15, Theme.fg(0xAA), "sans-serif");
        LayoutParams elp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        elp.bottomMargin = dp(90);
        addView(empty, elp);

        hint = text("", 13, Theme.fg(0x80), "sans-serif");
        LayoutParams hlp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(40);
        addView(hint, hlp);
    }

    boolean isOpen() {
        return open;
    }

    /** True for a few seconds after opening, so a delayed "go home" doesn't close it again. */
    boolean justOpened() {
        return open && System.currentTimeMillis() - openedAt < 7000;
    }

    void show() {
        openedAt = System.currentTimeMillis();
        if (!open) {
            open = true;
            setVisibility(VISIBLE);
            setAlpha(0f);
            animate().alpha(1f).setDuration(200).start();
        }
        requestFocus();
        sel = 1;
        updateRam();
        load();
    }

    void hide() {
        hideQuietly();
        if (listener != null) listener.onRecentsHidden(this);
    }

    /** Re-reads the list (another copy closed an app). */
    void reload() {
        if (!open) return;
        updateRam();
        load();
    }

    void hideQuietly() {
        if (!open) return;
        open = false;
        loadToken++;
        animate().alpha(0f).setDuration(180).withEndAction(() -> setVisibility(GONE)).start();
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (!open) return super.dispatchKeyEvent(ev);
        try {
            int code = ev.getKeyCode();
            if (ev.getAction() != KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) hide();
                return true; // swallow everything while open
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    select(sel - 1, true);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    select(sel + 1, true);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (sel > 0) closeCurrent();
                    break;
                case KeyEvent.KEYCODE_MENU:
                    clearRam();
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (sel == 0) {
                        clearRam();
                    } else {
                        openCurrent();
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "recents key failed", t);
        }
        return true;
    }

    /** The Clear RAM tile is always solid: black at night, white by day. */
    private void paintClear(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.night ? 0xFF1C1C1E : Color.WHITE);
        bg.setCornerRadius(dp(18));
        clearCard.setBackground(bg);
        int fg = Theme.night ? Color.WHITE : 0xFF111114;
        clearIcon.setColorFilter(fg);
        clearTitle.setTextColor(fg);
        clearSub.setTextColor((fg & 0x00FFFFFF) | 0x99000000);
    }

    /** Focus border: white at night; dark by day so it shows against the light cards. */
    private GradientDrawable border(View card) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(3), Theme.night ? Color.WHITE : 0xFF1C1C1E);
        g.setAlpha(0);
        return g;
    }

    /**
     * Closes every background app and service (never Jisha, Amazon's home service, the keyboard
     * or core system parts), then shows how much memory was freed.
     */
    private void clearRam() {
        if (clearing) return;
        clearing = true;
        clearTitle.setText("Clearing…");
        final ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        new Thread(() -> {
            long freed = 0;
            try {
                long before = avail(am);
                Set<String> pkgs = new HashSet<>();
                for (String line : exec("ps")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 2) continue;
                    String name = parts[parts.length - 1];
                    int colon = name.indexOf(':');
                    if (colon > 0) name = name.substring(0, colon);
                    if (name.indexOf('.') < 0 || name.startsWith("/")) continue; // native daemons
                    pkgs.add(name);
                }
                for (String pkg : pkgs) {
                    if (KEEP.contains(pkg)) continue;
                    try {
                        am.killBackgroundProcesses(pkg);
                    } catch (Throwable ignored) {
                    }
                }
                Thread.sleep(900);
                freed = Math.max(0, avail(am) - before);
            } catch (Throwable t) {
                Log.w(TAG, "clear RAM failed", t);
            }
            final long result = freed;
            main.post(() -> {
                clearing = false;
                if (!open) return;
                clearTitle.setText("Freed " + mb(result));
                updateRam();
                load();
                if (listener != null) listener.onRecentsChanged(this);
                main.postDelayed(() -> {
                    clearTitle.setText("Clear RAM");
                    // Everything closed: back to the home screen, like closing the last app.
                    if (open && ids.isEmpty()) hide();
                }, 1800);
            });
        }, "jisha-clear").start();
    }

    private static long avail(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    // ---------------------------------------------------------------- data

    private void updateRam() {
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long total = mi.totalMem, used = mi.totalMem - mi.availMem;
        ramText.setText("RAM " + mb(used) + " used of " + mb(total));
        float f = total == 0 ? 0f : used / (float) total;
        ramFill.animate().scaleX(Math.max(0.02f, f)).setDuration(300).start();
    }

    private static String mb(long bytes) {
        long m = bytes / (1024 * 1024);
        return m >= 1024 ? String.format(java.util.Locale.US, "%.1f GB", m / 1024f) : m + " MB";
    }

    /** Reads the recent-task list and the running processes off the main thread. */
    private void load() {
        final int token = ++loadToken;
        new Thread(() -> {
            final List<String> found = recentRunning();
            main.post(() -> {
                if (token != loadToken || !open) return;
                String was = sel > 0 && sel <= ids.size() ? ids.get(sel - 1) : null;
                boolean wasClear = sel == 0 && !ids.isEmpty();
                ids.clear();
                ids.addAll(found);
                if (wasClear || ids.isEmpty()) {
                    sel = 0;
                } else {
                    int i = was == null ? -1 : ids.indexOf(was);
                    sel = i >= 0 ? i + 1 : Math.min(Math.max(1, sel), ids.size());
                }
                rebuild(false);
            });
        }, "jisha-recents").start();
    }

    private List<String> recentRunning() {
        Set<String> running = new HashSet<>();
        for (String line : exec("ps")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length > 0) running.add(parts[parts.length - 1]);
        }
        List<String> out = new ArrayList<>();
        for (String line : exec("dumpsys", "activity", "recents")) {
            int i = line.indexOf("realActivity=");
            if (i < 0) continue;
            String comp = line.substring(i + 13).trim();
            int slash = comp.indexOf('/');
            if (slash <= 0) continue;
            String pkg = comp.substring(0, slash);
            if (pkg.equals(getContext().getPackageName()) || pkg.equals("com.amazon.tv.launcher")) continue;
            if (!apps.containsKey(pkg) || out.contains(pkg)) continue; // only real, launchable apps
            if (running.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    private static List<String> exec(String... cmd) {
        List<String> lines = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "exec failed: " + cmd[0], e);
        }
        return lines;
    }

    private void closeCurrent() {
        if (sel < 1 || sel > ids.size()) return;
        final String pkg = ids.get(sel - 1);
        final View card = cardViews.get(pkg);
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        try {
            am.killBackgroundProcesses(pkg);
        } catch (Throwable t) {
            Log.w(TAG, "close failed: " + pkg, t);
        }
        if (card == null) return;
        card.animate().cancel();
        card.animate().translationY(-dp(260)).alpha(0f).setDuration(280)
                .setInterpolator(EASE).withEndAction(() -> {
                    ids.remove(pkg);
                    if (listener != null) listener.onRecentsChanged(this);
                    if (ids.isEmpty()) {
                        hide(); // all closed: back to the Jisha home screen
                        return;
                    }
                    sel = Math.min(sel, ids.size());
                    rebuild(true); // the rest slide together to close the gap
                    main.postDelayed(this::updateRam, 600);
                }).start();
    }

    private void openCurrent() {
        if (sel < 1 || sel > ids.size()) return;
        HomeScreen.App a = apps.get(ids.get(sel - 1));
        if (a == null) return;
        HomeScreen.noteOpened(getContext(), a.id);
        Intent i = new Intent(a.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        hide();
        host.openApp(i, a.label);
    }

    // ---------------------------------------------------------------- cards

    /** Syncs the stage with {@link #ids}, reusing cards that stay so they can glide. */
    private void rebuild(boolean animate) {
        Set<String> keep = new HashSet<>(ids);
        for (String id : new ArrayList<>(cardViews.keySet())) {
            if (!keep.contains(id)) {
                View gone = cardViews.remove(id);
                borders.remove(gone);
                stage.removeView(gone);
            }
        }
        for (String id : ids) {
            if (cardViews.containsKey(id)) continue;
            HomeScreen.App a = apps.get(id);
            if (a == null) continue;
            View v = card(a);
            cardViews.put(id, v);
            stage.addView(v, new FrameLayout.LayoutParams(dp(CARD_W), -2, Gravity.START | Gravity.TOP));
            v.setPivotX(dp(CARD_W) / 2f);
            v.setPivotY(dp(34) + dp(CARD_H) / 2f);
            v.setScaleX(SMALL);
            v.setScaleY(SMALL);
            v.setAlpha(0f);
            v.setTranslationX(getResources().getDisplayMetrics().widthPixels); // enters from the right
        }
        empty.setVisibility(ids.isEmpty() ? VISIBLE : GONE);
        select(sel, animate);
    }

    /** Places every slot around the selected one: it sits centred, neighbours tuck behind. */
    private void select(int s, boolean animate) {
        int n = ids.size();
        sel = Math.max(0, Math.min(n, s));
        int width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        float focusLeft = width / 2f - dp(CARD_W) / 2f;
        float inset = dp(CARD_W) * (1 - SMALL) / 2f; // scaled cards are drawn this far in
        for (int slot = 0; slot <= n; slot++) {
            View v = slot == 0 ? clearTile : cardViews.get(ids.get(slot - 1));
            if (v == null) continue;
            int d = slot - sel;
            // One continuous stack: every card covers a little (OVERLAP) of the card after it.
            float small = dp(CARD_W) * SMALL;
            float visLeft;
            if (d == 0) {
                visLeft = focusLeft;
            } else if (d > 0) {
                visLeft = focusLeft + dp(CARD_W) - dp(OVERLAP) + (d - 1) * (small - dp(OVERLAP));
            } else {
                visLeft = focusLeft - small + dp(OVERLAP) + (d + 1) * (small - dp(OVERLAP));
            }
            float scale = d == 0 ? 1f : SMALL;
            float x = d == 0 ? visLeft : visLeft - inset;
            // Solid cards (like the reference); only far-away cards fade out at the edges.
            float alpha = Math.abs(d) <= 2 ? 1f : Math.max(0f, 1f - 0.5f * (Math.abs(d) - 2));
            v.setPivotX(dp(CARD_W) / 2f);
            v.setPivotY(dp(34) + dp(CARD_H) / 2f);
            // Fixed stacking: earlier cards always on top of later ones, whatever is selected.
            v.setTranslationZ(dp(30) - slot * dp(1));
            GradientDrawable b = borders.get(v);
            if (b != null) b.setAlpha(d == 0 ? 255 : 0);
            v.animate().cancel();
            if (animate) {
                v.animate().translationX(x).translationY(0).scaleX(scale).scaleY(scale).alpha(alpha)
                        .setDuration(MOVE_MS).setInterpolator(EASE).start();
            } else {
                v.setTranslationX(x);
                v.setTranslationY(0);
                v.setScaleX(scale);
                v.setScaleY(scale);
                v.setAlpha(alpha);
            }
        }
        paintClear(sel == 0);
        hint.setText(sel == 0 ? "Press OK to clear RAM" : "▲  Close app        ◀  Clear RAM");
        HomeScreen.App a = sel > 0 ? apps.get(ids.get(sel - 1)) : null;
        setGlow(a == null ? CLEAR_GLOW : a.glow, animate);
    }

    /** Crossfades the background to the selected app's colour. */
    private void setGlow(int color, boolean animate) {
        if (color == glowColor) return;
        glowColor = color;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                Theme.night ? new int[]{color, mix(color, Theme.base(), 0.55f), Theme.base()}
                        : new int[]{mix(color, 0xFFFFFFFF, 0.25f), mix(color, Theme.base(), 0.7f), Theme.base()});
        View in = glowOnA ? glowB : glowA, out = glowOnA ? glowA : glowB;
        glowOnA = !glowOnA;
        in.setBackground(g);
        in.animate().cancel();
        out.animate().cancel();
        if (animate) {
            in.animate().alpha(1f).setDuration(MOVE_MS + 80).setInterpolator(EASE).start();
            out.animate().alpha(0f).setDuration(MOVE_MS + 80).setInterpolator(EASE).start();
        } else {
            in.setAlpha(1f);
            out.setAlpha(0f);
        }
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private View card(HomeScreen.App a) {
        Context ctx = getContext();
        LinearLayout holder = new LinearLayout(ctx);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);

        // Small icon + name above the card.
        LinearLayout label = new LinearLayout(ctx);
        label.setOrientation(LinearLayout.HORIZONTAL);
        label.setGravity(Gravity.CENTER_VERTICAL);
        Drawable small = a.icon != null ? a.icon : a.banner;
        FrameLayout chip = rounded(dp(6));
        ImageView ci = new ImageView(ctx);
        ci.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ci.setImageDrawable(a.banner != null ? a.banner : small);
        chip.addView(ci, new FrameLayout.LayoutParams(-1, -1));
        label.addView(chip, new LinearLayout.LayoutParams(dp(36), dp(20)));
        TextView name = text(a.label, 14, Theme.fg(), "sans-serif-medium");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.leftMargin = dp(10);
        label.addView(name, nlp);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, dp(24));
        llp.bottomMargin = dp(10);
        holder.addView(label, llp);

        // The card: app artwork on white, like a tvOS app switcher.
        FrameLayout card = rounded(dp(18));
        card.setElevation(dp(12)); // soft shadow under each card
        GradientDrawable focusBorder = border(card);
        card.setForeground(focusBorder);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.banner != null ? Color.WHITE : a.tint);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        ImageView art = new ImageView(ctx);
        if (a.banner != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageDrawable(a.banner);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setImageDrawable(a.icon);
            card.addView(art, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        }
        holder.addView(card, new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)));
        borders.put(holder, focusBorder);
        return holder;
    }

    private FrameLayout rounded(final int radius) {
        FrameLayout f = new FrameLayout(getContext());
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        f.setClipToOutline(true);
        return f;
    }

    // ---------------------------------------------------------------- helpers

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
}
