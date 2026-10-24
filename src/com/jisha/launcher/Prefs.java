package com.jisha.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stores app order, hidden apps and whether the Home button should land on Jisha. */
final class Prefs {
    private static final String FILE = "jisha";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_REDIRECT = "home_redirect";
    private static final String KEY_NIGHT = "night";
    private static final String KEY_OPACITY = "glass_opacity";
    private static final String KEY_LAST_FOCUS = "last_focus";

    /** Apps shown first, in this order, until the user rearranges them. */
    static final List<String> DEFAULT_ORDER = Arrays.asList(
            "com.netflix.ninja",
            "com.amazon.firetv.youtube",
            "com.amazon.firebat",
            "in.startv.hotstar",
            "com.onemainstream.sonyliv.android",
            "com.mxtech.videoplayer.television",
            "com.amazon.cloud9",
            "com.amazon.venezia");

    /** Shopping / promo apps hidden by default (can be shown again from the menu). */
    static final List<String> DEFAULT_HIDDEN = Arrays.asList(
            "com.amazon.alexashopping",
            "com.amazon.shoptv.client",
            "com.amazon.shoptv.firetv.client",
            "com.amazon.minitv.android.app",
            "com.amazon.tahoe",
            "com.amazon.kor.demo",
            "com.amazon.imdb.tv.android.app",
            "com.imdb.livingroom.firetv",
            "com.amazon.avod",
            "com.amazon.ftv.profilepicker",
            "com.amazon.ssm",
            "com.amazon.ftv.screensaver",
            "com.android.providers.downloads.ui",
            "com.amazon.hedwig",
            "com.amazon.gamehub");

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    List<String> order() {
        String s = sp.getString(KEY_ORDER, null);
        if (s == null) return new ArrayList<>(DEFAULT_ORDER);
        return new ArrayList<>(Arrays.asList(TextUtils.split(s, ",")));
    }

    void setOrder(List<String> order) {
        sp.edit().putString(KEY_ORDER, TextUtils.join(",", order)).apply();
    }

    Set<String> hidden() {
        Set<String> def = new HashSet<>(DEFAULT_HIDDEN);
        return new HashSet<>(sp.getStringSet(KEY_HIDDEN, def));
    }

    void setHidden(Set<String> hidden) {
        sp.edit().putStringSet(KEY_HIDDEN, hidden).apply();
    }

    boolean homeRedirect() {
        return sp.getBoolean(KEY_REDIRECT, true);
    }

    void setHomeRedirect(boolean on) {
        sp.edit().putBoolean(KEY_REDIRECT, on).apply();
    }

    boolean night() {
        return sp.getBoolean(KEY_NIGHT, true);
    }

    void setNight(boolean on) {
        sp.edit().putBoolean(KEY_NIGHT, on).apply();
    }

    /** Glass opacity, 0 (clear) to 100 (solid). */
    int opacity() {
        return sp.getInt(KEY_OPACITY, 40);
    }

    void setOpacity(int value) {
        sp.edit().putInt(KEY_OPACITY, Math.max(0, Math.min(100, value))).apply();
    }

    /** What was last opened from the home screen (app id or top-button key). */
    String lastFocus() {
        return sp.getString(KEY_LAST_FOCUS, null);
    }

    void setLastFocus(String key) {
        sp.edit().putString(KEY_LAST_FOCUS, key).apply();
    }
}
