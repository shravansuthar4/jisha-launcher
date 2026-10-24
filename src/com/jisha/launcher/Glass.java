package com.jisha.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

/** Shared "liquid glass" background for Jisha's overlay panels. */
final class Glass {
    private Glass() {
    }

    /** Translucent tint for the glass in the current theme. */
    static Drawable tint() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Theme.panelTint());
    }

    /**
     * Fake live glass: a snapshot of {@code behind} under the given area, shrunk and scaled
     * back up so it looks blurred, with the tint on top. {@code self} (the overlay holding the
     * panel) is hidden while the snapshot is taken so it doesn't draw into its own background.
     */
    static Drawable frost(View behind, View self, int left, int top, int w, int h) {
        if (behind == null || behind.getWidth() == 0 || w <= 0 || h <= 0) return tint();
        int vis = self.getVisibility();
        try {
            self.setVisibility(View.INVISIBLE);
            float scale = 1f / 16f;
            Bitmap small = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            c.scale(scale, scale);
            c.translate(-left, -top);
            behind.draw(c);
            Bitmap tiny = Bitmap.createScaledBitmap(small, Math.max(1, small.getWidth() / 2),
                    Math.max(1, small.getHeight() / 2), true);
            Bitmap soft = Bitmap.createScaledBitmap(tiny, small.getWidth(), small.getHeight(), true);
            BitmapDrawable blur = new BitmapDrawable(behind.getResources(), soft);
            blur.setFilterBitmap(true);
            return new LayerDrawable(new Drawable[]{blur, tint()});
        } catch (Throwable t) {
            return tint();
        } finally {
            self.setVisibility(vis);
        }
    }
}
