package com.jisha.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Glass dropdown under the Wi-Fi button: nearby networks (strongest first, the connected one on
 * top), a refresh button that rescans, and "Wi-Fi settings" as the last row.
 */
final class WifiDropdown extends FrameLayout {

    private static final int WIDTH_DP = 300;
    private static final int MAX_LIST_DP = 300;

    /** One network in the list. */
    private static final class Net {
        String ssid;
        int level;      // 0-2 (arcs lit: 1-3)
        boolean secure;
        String caps = "";
        boolean connected;
        int savedId = -1;
    }

    private final HomeScreen.Host host;
    private final WifiManager wifi;
    private final LinearLayout panel;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final TextView status;
    private ImageView refreshIcon;
    private View refreshButton;
    private View anchor;
    private boolean open;
    private boolean scanning;
    private Runnable onClosed;

    /** Arc-style Wi-Fi icons (same as the Wi-Fi button) with 1, 2 or 3 arcs lit. */
    private static final int[] SIGNAL = {R.drawable.ic_wsig_1, R.drawable.ic_wsig_2, R.drawable.ic_wsig_3};

    /** Icon for a signal strength in dBm, or the "no signal" icon. */
    static int signalIcon(int rssi, boolean connected) {
        if (!connected) return R.drawable.ic_wsig_off;
        return SIGNAL[Math.max(0, Math.min(2, WifiManager.calculateSignalLevel(rssi, 3)))];
    }

    // Joining a password-protected network from the dropdown.
    private Net joining;
    private int joinId = -1;
    private EditText password;
    private TextView joinStatus;
    private boolean connecting;
    private final Runnable joinTimeout = () -> joinFailed("Couldn't connect. Try again.");

    private final BroadcastReceiver joinWatch = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!connecting || joining == null) return;
            if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(i.getAction())) {
                int err = i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0);
                if (err == WifiManager.ERROR_AUTHENTICATING) joinFailed("Wrong password. Try again.");
                return;
            }
            NetworkInfo ni = i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            WifiInfo wi = wifi.getConnectionInfo();
            if (ni != null && ni.isConnected() && wi != null && wi.getSSID() != null
                    && wi.getSSID().replace("\"", "").equals(joining.ssid)) {
                connecting = false;
                removeCallbacks(joinTimeout);
                joinStatus.setText("Connected");
                postDelayed(() -> hide(null), 1200);
            }
        }
    };

    private final BroadcastReceiver scanDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!open) return;
            scanning = false;
            refreshIcon.animate().cancel();
            refreshIcon.setRotation(0);
            fill();
        }
    };

    WifiDropdown(Context ctx, HomeScreen.Host host) {
        super(ctx);
        this.host = host;
        this.wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        setVisibility(GONE);

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22));
            }
        });
        panel.setClipToOutline(true);
        panel.setElevation(dp(20));
        addView(panel, new LayoutParams(dp(WIDTH_DP), -2, Gravity.END | Gravity.TOP));

        // Header: title + status on the left, refresh button on the right.
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(8), dp(2));
        header.setClipChildren(false);
        header.setClipToPadding(false);
        LinearLayout words = new LinearLayout(ctx);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text("Wi-Fi", 16, Theme.fg(), "sans-serif-medium"));
        status = text("", 11, Theme.fg(0x99), "sans-serif");
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-2, -2);
        stl.topMargin = dp(4);
        words.addView(status, stl);
        header.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(refreshButton(), new LinearLayout.LayoutParams(dp(34), dp(34)));
        // The panel's padding leaves room for the focused refresh button to grow, so the panel can
        // clip normally and the scrolled list can't spill over the header.
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        View rule = new View(ctx);
        rule.setTag("rule");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        rlp.setMargins(dp(4), dp(10), dp(4), dp(4));
        panel.addView(rule, rlp);

        scroll = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(MAX_LIST_DP), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    boolean isOpen() {
        return open;
    }

    /** Opens the dropdown under {@code anchor} (the Wi-Fi button). */
    void show(View anchor, Runnable onClosed) {
        if (open) return;
        this.anchor = anchor;
        this.onClosed = onClosed;
        open = true;
        restyle();

        // Place the panel's top-right corner just under the button's right edge.
        // (This overlay is GONE until now, so measure against the parent, which is full screen.)
        View parent = (View) getParent();
        int[] a = new int[2], me = new int[2];
        anchor.getLocationInWindow(a);
        parent.getLocationInWindow(me);
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        lp.topMargin = a[1] - me[1] + anchor.getHeight() + dp(10);
        lp.rightMargin = Math.max(dp(8), parent.getWidth() - (a[0] - me[0] + anchor.getWidth()));
        panel.setLayoutParams(lp);

        getContext().registerReceiver(scanDone, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        fill();
        rescan();

        setVisibility(VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationY(-dp(10));
        panel.animate().alpha(1f).translationY(0).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
        post(() -> {
            panel.setBackground(Glass.frost((View) getParent(), this,
                    panel.getLeft(), panel.getTop(), panel.getWidth(), panel.getHeight()));
            focusFirst();
        });
    }

    void hide(final Runnable after) {
        if (!open) return;
        open = false;
        scanning = false;
        try {
            getContext().unregisterReceiver(scanDone);
        } catch (Exception ignored) {
        }
        stopJoinWatch();
        hideKeyboard();
        joining = null;
        final Runnable closed = onClosed;
        panel.animate().alpha(0f).translationY(-dp(10)).setDuration(150).withEndAction(() -> {
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
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                if (joining != null) {
                    leaveJoin();
                } else {
                    hide(null);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** Keeps focus inside the dropdown; Up from the refresh button closes it. */
    @Override
    public View focusSearch(View focused, int direction) {
        View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
        if (next == null && direction == View.FOCUS_UP && joining == null) hide(null);
        return next != null ? next : focused;
    }

    // ---------------------------------------------------------------- data

    private void rescan() {
        if (wifi == null) return;
        scanning = true;
        status.setText("Searching…");
        refreshIcon.setRotation(0);
        refreshIcon.animate().rotationBy(360f * 20).setDuration(20000)
                .setInterpolator(new LinearInterpolator()).start();
        if (!wifi.startScan()) {
            scanning = false;
            refreshIcon.animate().cancel();
            refreshIcon.setRotation(0);
            fill();
        }
        // Some firmware never reports the scan; stop spinning after a while anyway.
        postDelayed(() -> {
            if (open && scanning) scanDone.onReceive(getContext(), null);
        }, 8000);
    }

    private List<Net> networks() {
        List<Net> out = new ArrayList<>();
        if (wifi == null) return out;
        String current = null;
        WifiInfo info = wifi.getConnectionInfo();
        if (info != null && info.getNetworkId() != -1 && info.getSSID() != null) {
            current = info.getSSID().replace("\"", "");
        }
        Map<String, Integer> saved = new HashMap<>();
        List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
        if (configs != null) {
            for (WifiConfiguration c : configs) {
                if (c.SSID != null) saved.put(c.SSID.replace("\"", ""), c.networkId);
            }
        }
        Map<String, Net> bySsid = new HashMap<>();
        List<ScanResult> results = wifi.getScanResults();
        if (results != null) {
            for (ScanResult r : results) {
                if (r.SSID == null || r.SSID.isEmpty()) continue;
                int level = WifiManager.calculateSignalLevel(r.level, 3);
                Net n = bySsid.get(r.SSID);
                if (n == null) {
                    n = new Net();
                    n.ssid = r.SSID;
                    n.level = level;
                    String caps = r.capabilities == null ? "" : r.capabilities;
                    n.secure = caps.contains("WEP") || caps.contains("PSK") || caps.contains("EAP");
                    n.caps = caps;
                    n.connected = r.SSID.equals(current);
                    Integer id = saved.get(r.SSID);
                    if (id != null) n.savedId = id;
                    bySsid.put(r.SSID, n);
                } else {
                    n.level = Math.max(n.level, level);
                }
            }
        }
        out.addAll(bySsid.values());
        if (Demo.on(getContext())) {
            Map<String, String> names = new HashMap<>();
            for (Net n : out) n.ssid = Demo.ssid(n.ssid, n.connected ? n.ssid : null, names);
        }
        Collections.sort(out, (x, y) -> {
            if (x.connected != y.connected) return x.connected ? -1 : 1;
            if (x.level != y.level) return y.level - x.level;
            return x.ssid.compareToIgnoreCase(y.ssid);
        });
        return out;
    }

    private void fill() {
        if (joining != null) return; // don't replace the password page
        View had = findFocus();
        String hadTag = had == null ? null : (String) had.getTag();
        list.removeAllViews();
        List<Net> nets = networks();
        if (!scanning) {
            status.setText(nets.isEmpty() ? "No networks found" : nets.size() + " networks");
        }
        for (Net n : nets) addNetwork(n);
        addSettingsRow();
        // Keep focus where it was (e.g. on refresh or a network). Removing the focused row lets
        // Android move focus elsewhere, so put it back once the new rows are laid out.
        if (had != null) {
            final String tag = hadTag;
            post(() -> {
                if (!open) return;
                View again = tag == null ? null : findViewWithTag(tag);
                if (again != null) {
                    again.requestFocus();
                } else {
                    focusFirst();
                }
                if (again != refreshButton) scroll.scrollTo(0, 0);
            });
        }
    }

    private void focusFirst() {
        View first = list.getChildCount() > 0 ? list.getChildAt(0) : refreshButton;
        first.requestFocus();
    }

    private void connect(Net n) {
        if (n.connected) {
            hide(null);
            return;
        }
        if (n.savedId != -1) {
            wifi.enableNetwork(n.savedId, true);
            wifi.reconnect();
            hide(null);
            return;
        }
        if (!n.secure) {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + n.ssid + "\"";
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            int id = wifi.addNetwork(c);
            if (id != -1) {
                wifi.enableNetwork(id, true);
                wifi.reconnect();
                hide(null);
                return;
            }
        }
        // Enterprise (EAP) networks need more than a password: use Fire TV's own page.
        if (n.caps.contains("EAP")) {
            openSettings();
            return;
        }
        showJoin(n);
    }

    // ---------------------------------------------------------------- password page

    private void showJoin(Net n) {
        joining = n;
        status.setText(n.ssid);
        list.removeAllViews();
        Context ctx = getContext();

        TextView label = text("Enter the password", 12, Theme.fg(0x99), "sans-serif");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.setMargins(dp(10), dp(8), 0, dp(8));
        list.addView(label, llp);

        // Password field with a show/hide button.
        LinearLayout field = new LinearLayout(ctx);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        password = new EditText(ctx);
        password.setTag("password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        password.setTextColor(Theme.fg());
        password.setHintTextColor(Theme.fg(0x66));
        password.setHint("Password");
        password.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        password.setPadding(dp(14), 0, dp(14), 0);
        password.setOnFocusChangeListener((v, has) -> password.setBackground(fieldBg(has)));
        password.setBackground(fieldBg(false));
        password.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO) {
                hideKeyboard();
                View join = findViewWithTag("join");
                if (join != null) join.requestFocus();
                join();
                return true;
            }
            return false;
        });
        field.addView(password, new LinearLayout.LayoutParams(0, dp(42), 1f));
        View eye = roundIcon("eye", R.drawable.ic_eye, () -> {
            boolean hidden = password.getTransformationMethod() instanceof PasswordTransformationMethod;
            password.setTransformationMethod(hidden ? null : PasswordTransformationMethod.getInstance());
            password.setSelection(password.getText().length());
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(36), dp(36));
        elp.leftMargin = dp(8);
        field.addView(eye, elp);
        field.setClipChildren(false);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.setMargins(dp(4), 0, dp(4), 0);
        list.addView(field, flp);

        joinStatus = text("", 12, Theme.fg(0xAA), "sans-serif");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.setMargins(dp(10), dp(10), 0, dp(4));
        list.addView(joinStatus, slp);

        list.addView(row("join", R.drawable.ic_check, "Join network", null, this::join),
                new LinearLayout.LayoutParams(-1, dp(46)));
        list.addView(row("cancel", R.drawable.ic_back, "Back to networks", null, this::leaveJoin),
                new LinearLayout.LayoutParams(-1, dp(46)));
        post(() -> {
            password.requestFocus();
            showKeyboard();
        });
    }

    private GradientDrawable fieldBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.soft());
        g.setCornerRadius(dp(12));
        g.setStroke(dp(focused ? 2 : 1), focused ? Theme.fg() : Theme.rule());
        return g;
    }

    private View roundIcon(String tag, int icon, final Runnable action) {
        final FrameLayout b = new FrameLayout(getContext());
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setTag(tag);
        final ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setColorFilter(Theme.fg());
        b.addView(iv, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        b.setBackground(roundBg(false));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(roundBg(has));
            iv.setColorFilter(has ? Theme.focusText() : Theme.fg());
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void showKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(password, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    /** Saves the network with the typed password and connects to it. */
    private void join() {
        if (joining == null || connecting) return;
        String pw = password.getText().toString();
        boolean wep = joining.caps.contains("WEP");
        if (pw.length() < (wep ? 5 : 8)) {
            joinStatus.setText(wep ? "Password is too short" : "Password must be at least 8 characters");
            password.requestFocus();
            return;
        }
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + joining.ssid + "\"";
        if (wep) {
            boolean hex = (pw.length() == 10 || pw.length() == 26 || pw.length() == 58) && pw.matches("[0-9A-Fa-f]+");
            c.wepKeys[0] = hex ? pw : "\"" + pw + "\"";
            c.wepTxKeyIndex = 0;
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        } else {
            c.preSharedKey = "\"" + pw + "\"";
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        joinId = wifi.addNetwork(c);
        if (joinId == -1) {
            joinStatus.setText("Couldn't save this network");
            return;
        }
        connecting = true;
        joinStatus.setText("Connecting…");
        IntentFilter f = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        getContext().registerReceiver(joinWatch, f);
        wifi.enableNetwork(joinId, true);
        wifi.saveConfiguration();
        wifi.reconnect();
        postDelayed(joinTimeout, 25000);
    }

    private void joinFailed(String why) {
        if (!connecting) return;
        connecting = false;
        removeCallbacks(joinTimeout);
        stopJoinWatch();
        if (joinId != -1) {
            wifi.removeNetwork(joinId); // don't keep a network with a wrong password
            wifi.saveConfiguration();
            joinId = -1;
        }
        wifi.reconnect(); // back to the previous network
        joinStatus.setText(why);
        password.requestFocus();
        password.selectAll();
    }

    private void stopJoinWatch() {
        connecting = false;
        removeCallbacks(joinTimeout);
        try {
            getContext().unregisterReceiver(joinWatch);
        } catch (Exception ignored) {
        }
    }

    private void leaveJoin() {
        hideKeyboard();
        stopJoinWatch();
        String ssid = joining == null ? null : joining.ssid;
        joining = null;
        fill();
        View row = ssid == null ? null : findViewWithTag("net:" + ssid);
        if (row != null) {
            row.requestFocus();
        } else {
            focusFirst();
        }
    }

    private void openSettings() {
        hide(() -> host.openApp(new Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Wi-Fi settings"));
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

    /** Colours that depend on Day/Night are applied each time the dropdown opens. */
    private void restyle() {
        View rule = panel.findViewWithTag("rule");
        if (rule != null) rule.setBackgroundColor(Theme.rule());
        ((TextView) ((ViewGroup) ((ViewGroup) panel.getChildAt(0)).getChildAt(0)).getChildAt(0))
                .setTextColor(Theme.fg());
        status.setTextColor(Theme.fg(0x99));
        refreshButton.setBackground(roundBg(refreshButton.isFocused()));
        refreshIcon.setColorFilter(refreshButton.isFocused() ? Theme.focusText() : Theme.fg());
    }

    private View refreshButton() {
        final FrameLayout b = new FrameLayout(getContext());
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setContentDescription("Refresh");
        b.setTag("refresh");
        refreshIcon = new ImageView(getContext());
        refreshIcon.setImageResource(R.drawable.ic_refresh);
        b.addView(refreshIcon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(roundBg(has));
            refreshIcon.setColorFilter(has ? Theme.focusText() : Theme.fg());
            b.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        b.setOnClickListener(v -> {
            if (!scanning) rescan();
        });
        refreshButton = b;
        return b;
    }

    private GradientDrawable roundBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(focused ? Theme.focusFill() : Theme.soft());
        return g;
    }

    private void addNetwork(final Net n) {
        LinearLayout row = row("net:" + n.ssid, SIGNAL[Math.max(0, Math.min(2, n.level))], n.ssid,
                n.connected ? "Connected" : (n.savedId != -1 ? "Saved" : null), () -> connect(n));
        if (n.secure) {
            ImageView lock = new ImageView(getContext());
            lock.setImageResource(R.drawable.ic_lock);
            lock.setColorFilter(Theme.fg());
            lock.setAlpha(0.55f);
            row.addView(lock, row.getChildCount() - 1, new LinearLayout.LayoutParams(dp(13), dp(13)));
        }
        list.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void addSettingsRow() {
        View r = new View(getContext());
        r.setBackgroundColor(Theme.rule());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        rlp.setMargins(dp(4), dp(4), dp(4), dp(4));
        list.addView(r, rlp);
        LinearLayout row = row("settings", R.drawable.ic_settings, "Wi-Fi settings", null,
                this::openSettings);
        list.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    /** A focusable row: icon, title (+ optional subtitle), trailing check/chevron area. */
    private LinearLayout row(String tag, int icon, String title, String sub, final Runnable action) {
        Context ctx = getContext();
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setTag(tag);
        row.setPadding(dp(10), 0, dp(12), 0);

        final ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        iv.setColorFilter(Theme.fg());
        row.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));

        LinearLayout words = new LinearLayout(ctx);
        words.setOrientation(LinearLayout.VERTICAL);
        final TextView t = text(title, 14, Theme.fg(), "sans-serif");
        words.addView(t);
        final TextView s = sub == null ? null : text(sub, 11, Theme.fg(0x99), "sans-serif");
        if (s != null) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = dp(3);
            words.addView(s, slp);
        }
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1f);
        wlp.leftMargin = dp(12);
        wlp.rightMargin = dp(8);
        row.addView(words, wlp);

        // Only the connected network gets a trailing tick; no arrows.
        final ImageView trail = new ImageView(ctx);
        boolean connected = "Connected".equals(sub);
        trail.setImageResource(R.drawable.ic_check);
        trail.setColorFilter(Theme.fg());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(connected ? dp(16) : 0, dp(16));
        tlp.leftMargin = connected ? dp(8) : 0;
        trail.setVisibility(connected ? VISIBLE : GONE);
        row.addView(trail, tlp);

        row.setOnFocusChangeListener((v, has) -> {
            if (has) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(Theme.focusFill());
                pill.setCornerRadius(dp(23)); // fully rounded, same as the settings menu
                row.setBackground(pill);
            } else {
                row.setBackground(null);
            }
            int fg = has ? Theme.focusText() : Theme.fg();
            t.setTextColor(fg);
            t.setTypeface(Typeface.create(has ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            if (s != null) s.setTextColor(has ? (fg & 0x00FFFFFF) | 0x99000000 : Theme.fg(0x99));
            iv.setColorFilter(fg);
            trail.setColorFilter(fg);
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c instanceof ImageView && c != iv && c != trail) ((ImageView) c).setColorFilter(fg);
            }
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }
}
