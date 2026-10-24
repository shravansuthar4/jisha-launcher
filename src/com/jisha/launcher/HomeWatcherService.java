package com.jisha.launcher;

import android.app.Notification;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fire OS always sends the Home button to Amazon's launcher and blocks every official way
 * to change that. This service notices Amazon's home screen starting and immediately brings
 * Jisha back to the front.
 *
 * Primary method: read the system log (needs READ_LOGS, granted once over ADB) and react to
 * ActivityManager's "START ... category.HOME" line. Fallback: poll UsageStats (needs the
 * GET_USAGE_STATS app-op, also granted over ADB) for Amazon's launcher coming to the front.
 */
public class HomeWatcherService extends Service {
    static final String EXTRA_BOOT = "boot";
    private static final String TAG = "Jisha";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher";
    private static final long DEBOUNCE_MS = 400;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread worker;
    private Process logcat;
    private long lastJump;
    /** Fire TV's Settings grid opens inside Amazon's home app; its "home" start isn't a Home press. */
    private volatile long ignoreHomeUntil;
    private void openRecents() {
        // Overlay first so Recent Apps appears instantly, then bring Jisha itself to the front
        // with Recent Apps open, so it stays after the overlay goes away.
        showOverlay();
        HomeScreen.openRecentsEverywhere();
        Intent i = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_RECENTS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "could not open Recent Apps", e);
        }
    }

    /** Android blocks app switches for 5 s after Home; the overlay covers Amazon's screen meanwhile. */
    private static final long SWITCH_BLOCK_MS = 5000;
    private static HomeWatcherService instance;
    private View overlayView;
    private HomeScreen overlayHome;
    private boolean overlayLaunching;
    private final Runnable overlayTimeout = this::removeOverlay;

    @Override
    public void onCreate() {
        super.onCreate();
        Notification n = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Jisha")
                .setContentText("Keeping Jisha as your home screen")
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build();
        startForeground(7, n);
        instance = this;
    }

    /** Called by MainActivity once the real Jisha screen is showing. */
    static void hideOverlay() {
        final HomeWatcherService s = instance;
        if (s != null) s.main.post(s::removeOverlay);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::watch, "jisha-home-watcher");
            worker.start();
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_BOOT, false)) {
            // Amazon's home finishes loading a few seconds after boot; land on Jisha after it.
            main.post(() -> jumpHome(false));
            main.postDelayed(() -> jumpHome(false), 3000);
            main.postDelayed(() -> jumpHome(false), 8000);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        removeOverlay();
        if (logcat != null) logcat.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void watch() {
        boolean canReadLogs = checkCallingOrSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "watcher started, readLogs=" + canReadLogs);
        while (running) {
            if (canReadLogs) {
                watchLogcat();
            } else {
                watchUsageStats();
            }
            SystemClock.sleep(1000);
        }
    }

    private void watchLogcat() {
        // Lines written before we started are skipped by comparing timestamps.
        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try {
            logcat = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-v", "time", "-T", "1", "ActivityManager:I", "FireTVKeyPolicyManager:I", "*:S"});
            BufferedReader r = new BufferedReader(new InputStreamReader(logcat.getInputStream()), 4096);
            String line;
            while (running && (line = r.readLine()) != null) {
                if (line.length() < 18 || line.substring(0, 18).compareTo(since) < 0) continue;
                if (line.contains("handleDoublePressOnHome")) {
                    // Fire OS recognised a double-press of Home (and does not go home for it).
                    main.post(this::openRecents);
                    continue;
                }
                if (!line.contains("START u0")) continue;
                if (line.contains("com.amazon.tv.launcher/.ui.SettingsActivity")) {
                    ignoreHomeUntil = SystemClock.uptimeMillis() + 3000;
                } else if (line.contains("category.HOME") && !line.contains(getPackageName())
                        && SystemClock.uptimeMillis() > ignoreHomeUntil) {
                    main.post(() -> jumpHome(true));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "logcat watcher stopped", e);
        } finally {
            if (logcat != null) logcat.destroy();
        }
    }

    private void watchUsageStats() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long last = System.currentTimeMillis();
        UsageEvents.Event ev = new UsageEvents.Event();
        while (running) {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(last, now);
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        && AMAZON_HOME.equals(ev.getPackageName())) {
                    main.post(() -> jumpHome(true));
                }
            }
            last = now;
            SystemClock.sleep(250);
        }
    }

    private void jumpHome(boolean afterHomePress) {
        if (!new Prefs(this).homeRedirect()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastJump < DEBOUNCE_MS) return;
        lastJump = now;
        if (afterHomePress) showOverlay();
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "could not open Jisha", e);
        }
    }

    // ---------------------------------------------------------------- instant overlay

    private void showOverlay() {
        main.removeCallbacks(overlayTimeout);
        main.postDelayed(overlayTimeout, SWITCH_BLOCK_MS + 1500);
        if (overlayView != null) {
            overlayLaunching = false;
            overlayHome.goFirst();
            return;
        }
        try {
            Context themed = new ContextThemeWrapper(this, R.style.JishaTheme);
            overlayHome = new HomeScreen(themed, (launch, label) -> {
                // Starts once Android lifts its app-switch block; until then, say what's coming.
                overlayHome.setHint(label.isEmpty() ? "Opening…" : "Opening " + label + "…");
                overlayLaunching = true;
                try {
                    startActivity(launch);
                } catch (Exception e) {
                    Log.w(TAG, "could not open " + label, e);
                }
            }, true);
            FrameLayout frame = new FrameLayout(themed) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent ev) {
                    // Back does nothing on a home screen; Home is handled by the watcher.
                    if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK && !overlayHome.recentsOpen()) return true;
                    return super.dispatchKeyEvent(ev);
                }

                @Override
                public void onWindowFocusChanged(boolean hasFocus) {
                    super.onWindowFocusChanged(hasFocus);
                    // The app picked from the overlay is now in front: get out of its way.
                    if (!hasFocus && overlayLaunching) main.post(HomeWatcherService.this::removeOverlay);
                }
            };
            frame.addView(overlayHome.view(), new FrameLayout.LayoutParams(-1, -1));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(frame, lp);
            overlayView = frame;
        } catch (Exception e) {
            Log.w(TAG, "overlay failed", e);
            overlayView = null;
            overlayHome = null;
        }
    }

    private void removeOverlay() {
        main.removeCallbacks(overlayTimeout);
        if (overlayView == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlayView);
        } catch (Exception ignored) {
        }
        overlayHome.destroy();
        overlayView = null;
        overlayHome = null;
        overlayLaunching = false;
    }
}
