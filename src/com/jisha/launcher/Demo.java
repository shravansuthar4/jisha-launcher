package com.jisha.launcher;

import android.content.Context;
import android.provider.Settings;

/**
 * Screenshot mode: replaces the profile name and Wi-Fi network names with sample ones, so
 * screenshots never show personal details. Turn on from a computer with
 * {@code adb shell settings put global jisha_demo 1} (and {@code 0} to turn off).
 */
final class Demo {
    static final String PROFILE = "Alex";
    static final String HOME_WIFI = "Home Wi-Fi";
    private static final String[] NEARBY = {"Home Wi-Fi", "Office 5G", "Cafe Guest", "Apartment 4B",
            "Neighbour 2.4", "Studio", "Library", "Garden", "Workshop", "Guest Network", "Attic", "Garage"};

    private Demo() {
    }

    static boolean on(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), "jisha_demo", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** A stable sample name for a real network name (the connected one is always "Home Wi-Fi"). */
    static String ssid(String real, String connected, java.util.Map<String, String> map) {
        if (real.equals(connected)) return HOME_WIFI;
        String m = map.get(real);
        if (m == null) {
            m = NEARBY[Math.min(NEARBY.length - 1, 1 + map.size())];
            map.put(real, m);
        }
        return m;
    }
}
