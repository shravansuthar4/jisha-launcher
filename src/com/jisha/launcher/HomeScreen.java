package com.jisha.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
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
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Jisha home screen: a glow in the focused app's colour, a clock header with glass buttons,
 * the focused app's name, and a glass dock of app tiles.
 * Used both by {@link MainActivity} and by the instant overlay that {@link HomeWatcherService}
 * draws while Android blocks app switches for 5 seconds after a Home press.
 */
final class HomeScreen {

    /** What the hosting activity/overlay does when the user opens an app. */
    interface Host {
        void openApp(Intent launch, String label);
    }

    private static final int COLUMNS = 4;

    /** One tile on the home screen. */
    static final class App {
        String id;          // package name
        String label;
        Drawable banner;    // 16:9 TV banner if the app has one
        Drawable icon;
        int tint;           // dark colour behind icon-only tiles
        int glow;           // vivid colour for the background glow
        Intent launch;
    }

    /** Loaded apps are shared by the activity and the overlay (same process), so both open instantly. */
    private static final Map<String, App> CACHE = new LinkedHashMap<>();

    private final Context ctx;
    private final Host host;
    private final boolean overlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Prefs prefs;

    private FrameLayout root;
    private View glowA, glowB;
    private boolean showingA = true;
    private int currentGlow, lastGlow;
    private TextView greeting, appTitle, wifiText;
    private ImageView wifiIcon;
    private View settingsPill;
    private LinearLayout grid;
    private SettingsPanel settingsPanel;
    private ScrollView page;      // the whole screen scrolls as one page
    private String focusedId;
    /** Put focus on a tile after the next grid rebuild (first launch, after move/hide/show). */
    private boolean grabFocus = true;
    private GlassMenu optionsMenu;
    private WifiDropdown wifiDropdown;
    private RecentsScreen recents;

    /** Live home screens (the activity's and the instant overlay's), for Recent Apps. */
    private static final List<java.lang.ref.WeakReference<HomeScreen>> LIVE = new ArrayList<>();

    private static List<HomeScreen> live() {
        List<HomeScreen> out = new ArrayList<>();
        for (java.lang.ref.WeakReference<HomeScreen> r : new ArrayList<>(LIVE)) {
            HomeScreen h = r.get();
            if (h == null || h.destroyed) {
                LIVE.remove(r);
            } else {
                out.add(h);
            }
        }
        return out;
    }

    /** Double-press of Home: show Recent Apps on every live home screen. */
    static void openRecentsEverywhere() {
        for (HomeScreen h : live()) h.showRecents();
    }

    void showRecents() {
        if (settingsPanel != null && settingsPanel.isOpen()) settingsPanel.hide(() -> { });
        if (optionsMenu != null && optionsMenu.isOpen()) optionsMenu.hide(() -> { });
        if (wifiDropdown != null && wifiDropdown.isOpen()) wifiDropdown.hide(() -> { });
        recents.show();
    }

    boolean recentsOpen() {
        return recents != null && recents.isOpen();
    }

    private final RecentsScreen.Listener recentsListener = new RecentsScreen.Listener() {
        @Override
        public void onRecentsHidden(RecentsScreen from) {
            for (HomeScreen h : live()) {
                if (h.recents != from) h.recents.hideQuietly();
                h.focusFirstTile();
            }
        }

        @Override
        public void onRecentsChanged(RecentsScreen from) {
            for (HomeScreen h : live()) {
                if (h.recents != from) h.recents.reload();
            }
        }
    };

    private void focusFirstTile() {
        focusTarget(lastTarget());
    }
    private boolean loading;
    private boolean destroyed;

    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    private final BroadcastReceiver ticks = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateGreeting();
            updateWifi();
        }
    };

    HomeScreen(Context ctx, Host host, boolean overlay) {
        this.ctx = ctx;
        this.host = host;
        this.overlay = overlay;
        this.prefs = new Prefs(ctx);
        Theme.load(ctx);
        LIVE.add(new java.lang.ref.WeakReference<>(this));
        buildChrome();
        updateGreeting();
        updateWifi();
        if (!CACHE.isEmpty()) buildGrid();

        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        t.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        t.addAction(WifiManager.RSSI_CHANGED_ACTION);
        ctx.registerReceiver(ticks, t);
        if (!overlay) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            ctx.registerReceiver(packageChanges, f);
            reloadApps();
        } else if (CACHE.isEmpty()) {
            reloadApps();
        }
    }

    View view() {
        return root;
    }

    void destroy() {
        destroyed = true;
        if (recents != null) recents.hideQuietly();
        ctx.unregisterReceiver(ticks);
        if (!overlay) ctx.unregisterReceiver(packageChanges);
    }

    private final Runnable rebuild = this::applyTheme;

    /** Appearance changed in the settings panel: restyle shortly (debounced while sliding). */
    private void themeChanged() {
        main.removeCallbacks(rebuild);
        main.postDelayed(rebuild, 250);
    }

    private void applyTheme() {
        if (destroyed) return;
        Theme.load(ctx);
        buildChrome();
        updateGreeting();
        updateWifi();
        currentGlow = 0;
        if (lastGlow != 0) setGlow(lastGlow);
        buildGrid();
        settingsPanel.refresh();
    }

    /** Back on the Settings button after the menu closes. */
    private void panelClosed() {
        if (settingsPill != null) settingsPill.requestFocus();
    }

    /** Tags of the top buttons, used as "last used" keys alongside app ids. */
    static final String KEY_WIFI = "pill:wifi", KEY_SETTINGS = "pill:settings";

    /**
     * What was last opened from the home screen: an app id, or a top button key. Home brings
     * focus back to it. Saved, so it survives the TV closing Jisha to free memory.
     */
    private static volatile String lastOpenedId;

    static void noteOpened(Context c, String key) {
        lastOpenedId = key;
        new Prefs(c).setLastFocus(key);
    }

    private String lastKey() {
        if (lastOpenedId == null) lastOpenedId = prefs.lastFocus();
        return lastOpenedId;
    }

    /** The view for the last-used key (a tile or a top button), or the first app. */
    private View lastTarget() {
        String key = lastKey();
        View v = key == null || root == null ? null : root.findViewWithTag(key);
        if (v == null && grid != null) {
            String first = firstVisibleId();
            v = first == null ? null : grid.findViewWithTag(first);
        }
        return v;
    }

    /** Focuses {@code v}, re-applying the scroll position even if it already has focus. */
    private void focusTarget(View v) {
        if (v == null) return;
        Object tag = v.getTag();
        App a = tag instanceof String ? CACHE.get(tag) : null;
        if (a == null) {
            // A top button: show the top of the screen.
            focusedId = null;
            if (page != null) page.smoothScrollTo(0, 0);
            if (grid != null) grid.animate().translationY(0).setDuration(250).start();
            v.requestFocus();
            return;
        }
        focusedId = a.id;
        if (v.isFocused()) {
            onTileFocused(a, v);
        } else {
            v.requestFocus();
        }
    }

    /**
     * Coming back to the home screen: close any open menu and focus whatever was last opened
     * (an app tile or a top button), or the first app.
     */
    void goFirst() {
        if (recents != null && recents.justOpened()) return;
        if (recents != null && recents.isOpen()) recents.hideQuietly();
        if (settingsPanel != null && settingsPanel.isOpen()) settingsPanel.hide(() -> { });
        if (optionsMenu != null && optionsMenu.isOpen()) optionsMenu.hide(() -> { });
        if (wifiDropdown != null && wifiDropdown.isOpen()) wifiDropdown.hide(() -> { });
        if (grid == null) return;
        View target = lastTarget();
        if (target == null) {
            if (page != null) page.smoothScrollTo(0, 0);
            grid.animate().translationY(0).setDuration(250).start();
            return;
        }
        focusTarget(target);
    }

    /** Shows a short status (e.g. "Opening Netflix…") in place of the app name. */
    void setHint(String hint) {
        if (appTitle == null) return;
        appTitle.setText(hint);
        appTitle.setAlpha(1f);
    }

    // ---------------------------------------------------------------- helpers

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static Typeface font(String family) {
        return Typeface.create(family, Typeface.NORMAL);
    }

    private static void round(View v, final int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** Frosted-glass look: light-to-clear vertical fill with a thin bright rim. */
    private GradientDrawable glass(float radius, boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                Theme.glass(focused));
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, dp(1)), Theme.glassRim(focused));
        return g;
    }

    // ---------------------------------------------------------------- layout

    private void buildChrome() {
        if (root == null) {
            root = new FrameLayout(ctx);
        } else {
            root.removeAllViews();
        }
        root.setBackgroundColor(Theme.base());

        glowA = new View(ctx);
        glowB = new View(ctx);
        glowA.setAlpha(0f);
        glowB.setAlpha(0f);
        root.addView(glowA, match());
        root.addView(glowB, match());

        // Darken the bottom a little so tiles stand out.
        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, Theme.shade()}));
        root.addView(shade, match());

        // Everything except the glow scrolls together, so moving down past the second row
        // slides the clock and title away and leaves the dock at the top (like tvOS).
        page = new ScrollView(ctx) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0; // Jisha decides scroll positions itself (see onTileFocused).
            }

            @Override
            public boolean executeKeyEvent(KeyEvent event) {
                return false; // arrow keys only move focus between tiles, never scroll by themselves
            }
        };
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.setClipChildren(false);
        root.addView(page, match());

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(52), dp(30), dp(52), 0);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        page.addView(column, new ViewGroup.LayoutParams(-1, -2));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        // Big name of the focused app.
        appTitle = text("", 38, Theme.fg(), font("sans-serif"));
        appTitle.setSingleLine(true);
        appTitle.setEllipsize(TextUtils.TruncateAt.END);
        appTitle.setShadowLayer(dp(8), 0, dp(1), 0x66000000);
        appTitle.setAlpha(0f); // app names aren't shown; this only carries "Opening …" messages
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(194); // leaves only the top ~20% of the second row peeking in
        tlp.leftMargin = dp(4);
        column.addView(appTitle, tlp);

        // Glass dock + app rows.
        grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(14);
        glp.leftMargin = -dp(8);
        glp.rightMargin = -dp(8);
        column.addView(grid, glp);

        // Spare room at the bottom so the page can always slide the dock to the top.
        column.addView(new View(ctx), new LinearLayout.LayoutParams(-1, dp(540)));
        if (settingsPanel == null) {
            settingsPanel = new SettingsPanel(ctx, host, this::themeChanged, this::panelClosed);
        }
        root.addView(settingsPanel, match());
        if (optionsMenu == null) optionsMenu = new GlassMenu(ctx);
        root.addView(optionsMenu, match());
        if (wifiDropdown == null) wifiDropdown = new WifiDropdown(ctx, host);
        root.addView(wifiDropdown, match());
        if (recents == null || !recents.isOpen()) {
            recents = new RecentsScreen(ctx, host, CACHE);
            recents.setListener(recentsListener);
        }
        root.addView(recents, match());
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(ctx);
        header.setClipChildren(false);
        header.setClipToPadding(false);

        // Left: greeting, big time, divider, date.
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text("", 11, Theme.fg(0xDD), font("sans-serif-medium"));
        greeting.setLetterSpacing(0.22f);
        greeting.setAllCaps(true);
        left.addView(greeting);

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.BOTTOM);
        TextClock time = new TextClock(ctx);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextColor(Theme.fg());
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50);
        time.setTypeface(font("sans-serif-light"));
        time.setIncludeFontPadding(false);
        timeRow.addView(time);

        if (!DateFormat.is24HourFormat(ctx)) {
            TextClock ampm = new TextClock(ctx);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("a");
            ampm.setAllCaps(true);
            ampm.setTextColor(Theme.fg(0xDD));
            ampm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            ampm.setTypeface(font("sans-serif-medium"));
            ampm.setIncludeFontPadding(false);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.leftMargin = dp(6);
            alp.bottomMargin = dp(8);
            timeRow.addView(ampm, alp);
        }

        View divider = new View(ctx);
        divider.setBackgroundColor(Theme.fg(0x55));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(28));
        dlp.leftMargin = dp(20);
        dlp.rightMargin = dp(20);
        dlp.bottomMargin = dp(6);
        timeRow.addView(divider, dlp);

        TextClock date = new TextClock(ctx);
        date.setFormat12Hour("EEEE  d MMMM");
        date.setFormat24Hour("EEEE  d MMMM");
        date.setAllCaps(true);
        date.setLetterSpacing(0.18f);
        date.setTextColor(Theme.fg(0xCC));
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        date.setTypeface(font("sans-serif-medium"));
        date.setIncludeFontPadding(false);
        LinearLayout.LayoutParams datelp = new LinearLayout.LayoutParams(-2, -2);
        datelp.bottomMargin = dp(12);
        timeRow.addView(date, datelp);

        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(-2, -2);
        trlp.topMargin = dp(8);
        left.addView(timeRow, trlp);
        header.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP));

        // Right: glass buttons — Wi-Fi, Settings.
        LinearLayout pills = new LinearLayout(ctx);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        // Room for a focused button to grow without being cut off.
        pills.setClipChildren(false);
        pills.setClipToPadding(false);
        pills.setPadding(dp(8), dp(8), dp(8), dp(8));

        final View[] wifiRef = new View[1];
        View wifi = pill(R.drawable.ic_wifi, "Wi-Fi", () -> {
            noteOpened(ctx, KEY_WIFI);
            wifiDropdown.show(wifiRef[0], () -> wifiRef[0].requestFocus());
        }, "Wi-Fi");
        wifiRef[0] = wifi;
        wifi.setTag(KEY_WIFI);
        wifiIcon = (ImageView) ((ViewGroup) wifi).getChildAt(0);
        wifiText = (TextView) ((ViewGroup) wifi).getChildAt(1);
        pills.addView(wifi, new LinearLayout.LayoutParams(-2, -2));

        settingsPill = pill(R.drawable.ic_settings, null, () -> {
            noteOpened(ctx, KEY_SETTINGS);
            settingsPanel.show();
        }, "Settings");
        settingsPill.setTag(KEY_SETTINGS);
        View settings = settingsPill;
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(12);
        pills.addView(settings, slp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        plp.topMargin = dp(22);
        plp.rightMargin = -dp(8);
        header.addView(pills, plp);
        return header;
    }

    /** A rounded glass button with an icon and optional text. */
    private View pill(int iconRes, String label, final Runnable action, String name) {
        final LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setFocusable(true);
        p.setFocusableInTouchMode(true);
        p.setContentDescription(name);
        int h = dp(34);
        p.setMinimumHeight(h);
        p.setMinimumWidth(h);
        boolean iconOnly = label == null;
        p.setPadding(iconOnly ? dp(9) : dp(13), 0, iconOnly ? dp(9) : dp(16), 0);
        p.setBackground(glass(h / 2f, false));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Theme.fg());
        p.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView t = text(label == null ? "" : label, 13, Theme.fg(), font("sans-serif-medium"));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(150));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = iconOnly ? 0 : dp(8);
        t.setVisibility(iconOnly ? View.GONE : View.VISIBLE);
        p.addView(t, tlp);

        p.setOnFocusChangeListener((v, has) -> {
            p.setBackground(glass(h / 2f, has));
            p.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
        });
        p.setOnClickListener(v -> action.run());
        p.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                showMenu(null);
                return true;
            }
            return false;
        });
        return p;
    }

    /** Opens the first intent that resolves; Fire OS may not support every standard one. */
    private void openFirst(String name, Intent... intents) {
        PackageManager pm = ctx.getPackageManager();
        for (Intent i : intents) {
            if (i.resolveActivity(pm) != null) {
                host.openApp(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), name);
                return;
            }
        }
        Toast.makeText(ctx, "Not available on this TV", Toast.LENGTH_SHORT).show();
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening");
    }

    private void updateWifi() {
        String ssid = null;
        int rssi = -100;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                ssid = info.getSSID();
                rssi = info.getRssi();
            }
        } catch (Exception ignored) {
        }
        if (ssid != null) ssid = ssid.replace("\"", "");
        boolean connected = ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>");
        if (connected && Demo.on(ctx)) ssid = Demo.HOME_WIFI;
        wifiText.setText(connected ? ssid : "Not connected");
        // Same arc icon as the Wi-Fi list, lit according to the signal strength.
        wifiIcon.setImageResource(WifiDropdown.signalIcon(rssi, connected));
    }

    // ---------------------------------------------------------------- apps

    private void reloadApps() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            final Map<String, App> found = loadApps();
            main.post(() -> {
                loading = false;
                synchronized (CACHE) {
                    CACHE.clear();
                    CACHE.putAll(found);
                }
                if (!destroyed) buildGrid();
            });
        }, "jisha-load").start();
    }

    private Map<String, App> loadApps() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, ActivityInfo> byPkg = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || pkg.equals("com.amazon.tv.launcher")) continue;
                if (!byPkg.containsKey(pkg)) byPkg.put(pkg, ri.activityInfo);
            }
        }
        Map<String, App> out = new HashMap<>();
        for (Map.Entry<String, ActivityInfo> e : byPkg.entrySet()) {
            String pkg = e.getKey();
            ActivityInfo ai = e.getValue();
            App a = new App();
            a.id = pkg;
            a.label = String.valueOf(ai.loadLabel(pm));
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                launch = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, ai.name));
            }
            a.launch = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.banner = ai.loadBanner(pm);
                if (a.banner == null) a.banner = pm.getApplicationBanner(pkg);
            } catch (Exception ignored) {
            }
            try {
                a.icon = ai.loadIcon(pm);
            } catch (Exception ignored) {
            }
            finishArt(a);
            Log.i("Jisha", "app " + pkg + " = " + a.label + (a.banner != null ? " [banner]" : ""));
            out.put(pkg, a);
        }
        return out;
    }

    /**
     * Picks the app's signature colour from its artwork: the average of its colourful pixels
     * (so Netflix's white banner still gives red), boosted into a vivid glow.
     */
    private static void finishArt(App a) {
        Drawable art = a.banner != null ? a.banner : a.icon;
        Bitmap small = art == null ? null : toBitmap(art, 32, 18);
        if (small == null) {
            a.tint = 0xFF2C2C34;
            a.glow = 0xFF3A3F55;
            return;
        }
        long r = 0, g = 0, b = 0, n = 0, ar = 0, ag = 0, ab = 0, an = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                ar += Color.red(c);
                ag += Color.green(c);
                ab += Color.blue(c);
                an++;
                Color.colorToHSV(c, hsv);
                if (hsv[1] > 0.35f && hsv[2] > 0.25f) {
                    r += Color.red(c);
                    g += Color.green(c);
                    b += Color.blue(c);
                    n++;
                }
            }
        }
        int avg = an == 0 ? 0xFF444444 : Color.rgb((int) (ar / an), (int) (ag / an), (int) (ab / an));
        int vivid = n < 4 ? avg : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));

        Color.colorToHSV(vivid, hsv);
        boolean colourful = n >= 4;
        hsv[1] = colourful ? Math.max(hsv[1], 0.8f) : Math.min(hsv[1], 0.25f);
        hsv[2] = colourful ? 0.85f : 0.45f;
        a.glow = Color.HSVToColor(hsv);

        Color.colorToHSV(avg, hsv);
        hsv[2] = Math.min(hsv[2], 0.45f);         // keep icon tiles dark enough for white icons
        hsv[1] = Math.min(hsv[1] * 1.1f, 0.85f);
        a.tint = Color.HSVToColor(hsv);
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Visible tiles in display order: saved order first, then the rest alphabetically. */
    private List<String> visibleIds() {
        Set<String> hidden = prefs.hidden();
        List<String> ids = new ArrayList<>();
        for (String id : prefs.order()) {
            if (CACHE.containsKey(id) && !hidden.contains(id) && !ids.contains(id)) ids.add(id);
        }
        List<App> rest = new ArrayList<>();
        for (App a : CACHE.values()) {
            if (!ids.contains(a.id) && !hidden.contains(a.id)) rest.add(a);
        }
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        for (App a : rest) ids.add(a.id);
        return ids;
    }

    private String firstVisibleId() {
        List<String> ids = visibleIds();
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ---------------------------------------------------------------- dock + grid

    private void buildGrid() {
        // Rebuilding removes the focused tile; remember whether focus belongs back in the grid.
        View had = root.findFocus();
        boolean force = grabFocus || had == null || isInGrid(had);
        grabFocus = false;
        grid.removeAllViews();
        List<String> ids = visibleIds();
        int dockPad = dp(12), gap = dp(16);
        // 856dp wide content (incl. the grid's 8dp bleed each side) minus dock padding and gaps.
        int tileW = (dp(856) - 2 * dockPad - (COLUMNS - 1) * gap) / COLUMNS;
        int tileH = Math.round(tileW * 9f / 16f);
        LinearLayout row = null;
        View toFocus = null;
        for (int i = 0; i < ids.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(dockPad, dockPad, dockPad, dockPad);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.leftMargin = dp(8);
                lp.rightMargin = dp(8);
                if (i == 0) {
                    row.setBackground(glass(dp(26), false)); // the glass dock
                    lp.bottomMargin = dp(22);
                } else {
                    row.setPadding(dockPad, 0, dockPad, 0);
                    lp.bottomMargin = dp(26);
                }
                grid.addView(row, lp);
            }
            App a = CACHE.get(ids.get(i));
            boolean inDock = i < COLUMNS;
            View tile = inDock ? makeTile(a, tileW, tileH) : makeGridTile(a, tileW, tileH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, inDock ? tileH : -2);
            if (i % COLUMNS != 0) lp.leftMargin = gap;
            row.addView(tile, lp);
            if (i == 0 && toFocus == null) toFocus = tile;
            if (a.id.equals(focusedId != null ? focusedId : lastKey())) toFocus = tile;
        }
        if (focusedId == null && lastKey() != null && lastKey().startsWith("pill:")) {
            View pillView = root.findViewWithTag(lastKey());
            if (pillView != null) toFocus = pillView;
        }
        if (toFocus != null) {
            final View f = toFocus;
            if (force) f.requestFocus(); // right away, before Android hands focus to Search
            grid.postDelayed(() -> {
                if (settingsPanel != null && settingsPanel.isOpen()) return;
                if (optionsMenu != null && optionsMenu.isOpen()) return;
                if (wifiDropdown != null && wifiDropdown.isOpen()) return;
                if (recentsOpen()) return;
                // Don't steal focus from the top buttons unless this rebuild owns it.
                View cur = root.findFocus();
                if (!force && cur != null && !isInGrid(cur)) return;
                if (focusedId == null) page.scrollTo(0, 0);
                if (isInGrid(f)) {
                    f.requestFocus();
                } else {
                    focusTarget(f);
                }
            }, 60);
        }
    }

    private boolean isInGrid(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == grid) return true;
        }
        return false;
    }

    private View makeTile(final App a, final int w, final int h) {
        final FrameLayout card = new FrameLayout(ctx);
        card.setTag(a.id);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.tint);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        round(card, dp(16));
        card.setElevation(dp(3));

        ImageView img = new ImageView(ctx);
        if (a.banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(a.icon);
            card.addView(img, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        }

        // Glass rim + top sheen, shown when focused.
        GradientDrawable rim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        rim.setCornerRadius(dp(16));
        rim.setStroke(dp(3), Color.WHITE);
        rim.setAlpha(0);
        card.setForeground(rim);

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.07f : 1f;
            card.setPivotX(w / 2f);
            card.setPivotY(h / 2f);
            card.animate().scaleX(s).scaleY(s)
                    .setDuration(150).setInterpolator(new DecelerateInterpolator()).start();
            rim.setAlpha(has ? 255 : 0);
            if (has) onTileFocused(a, v);
        });
        card.setOnClickListener(v -> {
            noteOpened(ctx, a.id);
            host.openApp(a.launch, a.label);
        });
        card.setOnLongClickListener(v -> {
            showMenu(a);
            return true;
        });
        card.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                showMenu(a);
                return true;
            }
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
                return moveRow(v, code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            }
            return false;
        });
        return card;
    }

    /** A tile below the dock: flat until focused, then it lifts with a shadow and shows its name. */
    private View makeGridTile(final App a, final int w, final int h) {
        LinearLayout holder = new LinearLayout(ctx);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        holder.setTag(a.id);
        holder.setFocusable(true);
        holder.setFocusableInTouchMode(true);

        final FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.tint);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        round(card, dp(16));
        ImageView img = new ImageView(ctx);
        if (a.banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(a.icon);
            card.addView(img, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        }
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(16));
        border.setStroke(dp(3), Color.WHITE);
        border.setAlpha(0);
        card.setForeground(border);
        holder.addView(card, new LinearLayout.LayoutParams(w, h));

        holder.setOnFocusChangeListener((v, has) -> {
            border.setAlpha(has ? 255 : 0);
            float s = has ? 1.1f : 1f;
            card.setPivotX(w / 2f);
            card.setPivotY(h / 2f);
            card.animate().scaleX(s).scaleY(s).translationZ(has ? dp(18) : 0)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            if (has) onTileFocused(a, v);
        });
        holder.setOnClickListener(v -> {
            noteOpened(ctx, a.id);
            host.openApp(a.launch, a.label);
        });
        holder.setOnLongClickListener(v -> {
            showMenu(a);
            return true;
        });
        holder.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                showMenu(a);
                return true;
            }
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
                return moveRow(v, code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            }
            return false;
        });
        return holder;
    }

    /** Moves focus to the tile in the same column of the next/previous row (works off-screen too). */
    private boolean moveRow(View tile, int dir) {
        View row = (View) tile.getParent();
        int r = grid.indexOfChild(row);
        int target = r + dir;
        if (r < 0 || target < 0) return false; // Up from the dock: let Android go to the buttons
        if (target >= grid.getChildCount()) return true; // nothing below: stay put
        ViewGroup from = (ViewGroup) row;
        ViewGroup to = (ViewGroup) grid.getChildAt(target);
        int col = Math.min(from.indexOfChild(tile), to.getChildCount() - 1);
        to.getChildAt(col).requestFocus();
        return true;
    }

    private void onTileFocused(App a, View tile) {
        focusedId = a.id;
        setGlow(a.glow);

        // Dock: everything at rest.
        // Row 2: the clock and buttons stay put; only the dock and rows slide up beneath them.
        // Row 3 and below: the whole screen scrolls up so the dock sits at the top.
        View row = (View) tile.getParent();
        int index = grid.indexOfChild(row);
        int rowBottom = grid.getTop() + row.getBottom() + dp(20); // room for the lift + shadow
        int fit = Math.max(0, rowBottom - page.getHeight());
        if (index <= 0) {
            page.smoothScrollTo(0, 0);
            grid.animate().translationY(0).setDuration(250).setInterpolator(new DecelerateInterpolator()).start();
        } else if (index == 1) {
            page.smoothScrollTo(0, 0);
            grid.animate().translationY(-fit).setDuration(250).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            int dockTop = grid.getTop() + grid.getChildAt(0).getTop();
            grid.animate().translationY(0).setDuration(250).setInterpolator(new DecelerateInterpolator()).start();
            page.smoothScrollTo(0, Math.max(dockTop - dp(30), fit));
        }
    }

    /** Crossfades the big soft glow behind everything to the app's colour. */
    private void setGlow(int color) {
        if (color == currentGlow) return;
        currentGlow = color;
        lastGlow = color;
        // Draw the glow once into a small bitmap; scaled up with filtering it stays perfectly
        // smooth but costs the GPU almost nothing (unlike a full-screen live gradient).
        int bw = 192, bh = 108;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.38f, 0.40f);
        g.setGradientRadius(bw * 0.49f);
        g.setColors(Theme.glow(color));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        g.setBounds(0, 0, bw, bh);
        g.draw(new Canvas(bmp));
        android.graphics.drawable.BitmapDrawable bd =
                new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
        bd.setFilterBitmap(true);

        View in = showingA ? glowB : glowA;
        View out = showingA ? glowA : glowB;
        in.setBackground(bd);
        in.animate().alpha(1f).setDuration(380).withLayer().start();
        out.animate().alpha(0f).setDuration(380).withLayer().start();
        showingA = !showingA;
    }

    // ---------------------------------------------------------------- options menu

    /** Options for a tile (or general Jisha options when {@code a} is null), on a glass menu. */
    private void showMenu(final App a) {
        final View from = root.findFocus();
        List<GlassMenu.Item> items = new ArrayList<>();
        if (a != null) {
            items.add(new GlassMenu.Item(R.drawable.ic_first, "Move to front", () -> move(a.id, Integer.MIN_VALUE)));
            items.add(new GlassMenu.Item(R.drawable.ic_left, "Move left", () -> move(a.id, -1)));
            items.add(new GlassMenu.Item(R.drawable.ic_right, "Move right", () -> move(a.id, +1)));
            items.add(new GlassMenu.Item(R.drawable.ic_hide, "Hide app", () -> {
                Set<String> h = prefs.hidden();
                h.add(a.id);
                prefs.setHidden(h);
                focusedId = null;
                grabFocus = true;
                buildGrid();
            }));
            items.add(new GlassMenu.Item(R.drawable.ic_info, "App info", () -> host.openApp(
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.id))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), a.label + " info")));
        }
        items.add(new GlassMenu.Item(R.drawable.ic_show, "Show hidden apps", () -> showHiddenPicker(from)));
        final boolean on = prefs.homeRedirect();
        items.add(new GlassMenu.Item(R.drawable.ic_home,
                on ? "Home: Jisha ✓" : "Home: Fire TV", () -> {
                    prefs.setHomeRedirect(!on);
                    refocus(from);
                }));
        Drawable art = a == null ? null : (a.banner != null ? a.banner : a.icon);
        optionsMenu.show(a == null ? "Home screen" : a.label, art, items, () -> refocus(from));
    }

    private void refocus(View v) {
        if (v != null && v.isAttachedToWindow()) v.requestFocus();
    }

    private void move(String id, int delta) {
        List<String> ids = visibleIds();
        int from = ids.indexOf(id);
        if (from < 0) return;
        ids.remove(from);
        int to = delta == Integer.MIN_VALUE ? 0 : Math.max(0, Math.min(ids.size(), from + delta));
        ids.add(to, id);
        prefs.setOrder(ids);
        focusedId = id;
        grabFocus = true;
        buildGrid();
    }

    private void showHiddenPicker(final View from) {
        final Set<String> hidden = prefs.hidden();
        List<GlassMenu.Item> items = new ArrayList<>();
        for (final App a : CACHE.values()) {
            if (!hidden.contains(a.id)) continue;
            items.add(new GlassMenu.Item(R.drawable.ic_show, a.label, () -> {
                hidden.remove(a.id);
                prefs.setHidden(hidden);
                focusedId = a.id;
                grabFocus = true;
                buildGrid();
            }));
        }
        if (items.isEmpty()) {
            Toast.makeText(ctx, "No hidden apps", Toast.LENGTH_SHORT).show();
            refocus(from);
            return;
        }
        optionsMenu.show("Show app", null, items, () -> refocus(from));
    }
}
