package com.jisha.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Memory figures and "Clear RAM", shared by Recent Apps and the Control Center. */
final class RamTools {
    /** Never closed: Jisha itself, Amazon's home service, the keyboard and core system parts. */
    static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "com.jisha.launcher", "com.amazon.tv.launcher", "android", "system", "com.android.systemui",
            "com.android.phone", "com.amazon.tv.ime", "com.android.inputmethod.pinyin",
            "com.android.providers.settings", "com.android.bluetooth"));

    private RamTools() {
    }

    static long total(Context c) {
        return info(c).totalMem;
    }

    static long used(Context c) {
        ActivityManager.MemoryInfo mi = info(c);
        return mi.totalMem - mi.availMem;
    }

    private static ActivityManager.MemoryInfo info(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi;
    }

    static String mb(long bytes) {
        long m = bytes / (1024 * 1024);
        return m >= 1024 ? String.format(java.util.Locale.US, "%.1f GB", m / 1024f) : m + " MB";
    }

    /** Closes every background app and service. Blocking: call off the main thread. Returns bytes freed. */
    static long clear(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        try {
            long before = info(c).availMem;
            Set<String> pkgs = new HashSet<>();
            Process p = Runtime.getRuntime().exec("ps");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                String name = parts[parts.length - 1];
                int colon = name.indexOf(':');
                if (colon > 0) name = name.substring(0, colon);
                if (name.indexOf('.') < 0 || name.startsWith("/")) continue; // native daemons
                pkgs.add(name);
            }
            p.waitFor();
            for (String pkg : pkgs) {
                if (KEEP.contains(pkg)) continue;
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (Throwable ignored) {
                }
            }
            Thread.sleep(900);
            return Math.max(0, info(c).availMem - before);
        } catch (Throwable t) {
            Log.w("Jisha", "clear RAM failed", t);
            return 0;
        }
    }
}
