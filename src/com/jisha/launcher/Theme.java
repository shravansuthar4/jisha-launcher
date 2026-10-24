package com.jisha.launcher;

import android.content.Context;
import android.graphics.Color;

/** Day/Night colours and glass opacity, shared by the home screen and the settings panel. */
final class Theme {
    static boolean night = true;
    /** 0 = clear glass, 100 = solid. */
    static int opacity = 40;
    /** App size: Small shows 5 apps per row, Large shows 4. */
    static boolean largeApps = false;

    private Theme() {
    }

    static void load(Context c) {
        Prefs p = new Prefs(c);
        night = p.night();
        opacity = p.opacity();
        largeApps = p.largeApps();
    }

    private static float o() {
        return opacity / 100f;
    }

    static int col(int rgb, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** Screen background behind the glow. */
    static int base() {
        return night ? 0xFF07070B : 0xFFE6E6EB;
    }

    /** Main text/icon colour, optionally faded (alpha 0-255). */
    static int fg() {
        return night ? Color.WHITE : 0xFF1C1C1E;
    }

    static int fg(int alpha) {
        return (alpha << 24) | (fg() & 0xFFFFFF);
    }

    /** Top-to-bottom fill for glass buttons and the dock. */
    static int[] glass(boolean focused) {
        float k = 0.3f + 1.6f * o();
        if (night) {
            return focused ? new int[]{col(0xFFFFFF, 0.40f * k), col(0xFFFFFF, 0.22f * k)}
                    : new int[]{col(0xFFFFFF, 0.17f * k), col(0xFFFFFF, 0.07f * k)};
        }
        return focused ? new int[]{col(0xFFFFFF, 0.97f), col(0xFFFFFF, 0.88f)}
                : new int[]{col(0xFFFFFF, 0.32f * k), col(0xFFFFFF, 0.18f * k)};
    }

    static int glassRim(boolean focused) {
        if (night) return focused ? 0xCCFFFFFF : col(0xFFFFFF, 0.22f);
        return focused ? Color.WHITE : col(0xFFFFFF, 0.65f);
    }

    /** Fade at the bottom of the home screen that helps the tiles stand out. */
    static int shade() {
        return night ? 0x99000000 : 0x40FFFFFF;
    }

    /** Tint layered over the blurred background of the settings panel. */
    static int[] panelTint() {
        if (night) return new int[]{col(0x303036, 0.15f + 0.8f * o()), col(0x141418, 0.25f + 0.72f * o())};
        return new int[]{col(0xFFFFFF, 0.25f + 0.7f * o()), col(0xEFEFF4, 0.3f + 0.65f * o())};
    }

    /** Profile card / icon bubbles inside the panel. */
    static int soft() {
        return night ? col(0xFFFFFF, 0.14f) : col(0x000000, 0.06f);
    }

    static int rule() {
        return night ? col(0xFFFFFF, 0.12f) : col(0x000000, 0.10f);
    }

    /** Selected list item: white pill at night, dark pill by day. */
    static int focusFill() {
        return night ? Color.WHITE : 0xFF1C1C1E;
    }

    static int focusText() {
        return night ? 0xFF111114 : Color.WHITE;
    }

    /** Glow colours (centre, middle) for the app-coloured background. */
    static int[] glow(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (night) {
            hsv[2] *= 0.32f;
            return new int[]{color, Color.HSVToColor(hsv), base()};
        }
        hsv[1] *= 0.75f;
        int centre = Color.HSVToColor(hsv);
        hsv[1] *= 0.45f;
        hsv[2] = Math.min(1f, hsv[2] * 0.4f + 0.6f);
        return new int[]{centre, Color.HSVToColor(hsv), base()};
    }
}
