package com.jisha.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;
import java.util.Locale;

/**
 * Standby: a full-screen calendar and clock (a big numeral marks today). Any of OK, Back or
 * Select returns to the home screen.
 */
public class StandbyActivity extends Activity {

    private CalendarView view;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            view.invalidate();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new CalendarView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        registerReceiver(tick, f);
        view.invalidate();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(tick);
        super.onPause();
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_A) {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    /** True when the text box clearly touches at (cx, cy) lies on the big numeral. */
    private static boolean onBig(android.graphics.Region big, float cx, float cy, float tw, float th) {
        int hits = 0;
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 3; j++) {
                float x = cx - tw / 2f + tw * i / 4f, y = cy - th / 2f + th * j / 2f;
                if (big.contains((int) x, (int) y)) hits++;
            }
        }
        return hits >= 2;
    }

    /** Draws the month: header, weekday row, six weeks of dates, today's big numeral. */
    private static final class CalendarView extends View {
        private static final int BG = 0xFFD9D4D6, RED = 0xFFE0473C, BLUE = 0xFF2F5FC4,
                NAVY = 0xFF0E1E45, FADED = 0x552F5FC4;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CalendarView(Context c) {
            super(c);
            setKeepScreenOn(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(BG);
            Calendar now = Calendar.getInstance();
            int today = now.get(Calendar.DAY_OF_MONTH);
            float padX = w * 0.11f, top = h * 0.12f;

            // Header: "October 2026" on the left, the time on the right.
            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            p.setTypeface(medium);
            p.setTextSize(h * 0.062f);
            p.setColor(RED);
            p.setTextAlign(Paint.Align.LEFT);
            String month = now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
                    + " " + now.get(Calendar.YEAR);
            c.drawText(month, padX, top + h * 0.05f, p);
            p.setTextAlign(Paint.Align.RIGHT);
            String time = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm", now).toString();
            c.drawText(time, w - padX, top + h * 0.05f, p);

            // Grid geometry: 7 columns, a weekday row, then 6 weeks.
            float gridTop = top + h * 0.16f, gridBottom = h * 0.93f;
            float colW = (w - 2 * padX) / 7f;
            float rowH = (gridBottom - gridTop) / 7f;

            Calendar first = (Calendar) now.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int lead = first.get(Calendar.DAY_OF_WEEK) - first.getFirstDayOfWeek();
            if (lead < 0) lead += 7;
            int todayCol = (now.get(Calendar.DAY_OF_WEEK) - now.getFirstDayOfWeek() + 7) % 7;
            int todayRow = (lead + today - 1) / 7;

            // Today's big serif numeral, centred on the screen behind the grid.
            p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(RED);
            // Drawn as a path: the TV's GPU skips text this large when drawn as text.
            p.setTextSize(gridBottom - gridTop);
            String num = String.valueOf(today);
            android.graphics.Path path = new android.graphics.Path();
            p.getTextPath(num, 0, num.length(), 0, 0, path);
            RectF b = new RectF();
            path.computeBounds(b, true);
            // As tall as the grid.
            float k = (gridBottom - gridTop) * 0.92f / b.height();
            float cx = w / 2f;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postTranslate(-b.centerX(), -b.centerY());
            m.postScale(k, k);
            m.postTranslate(cx, (gridTop + gridBottom) / 2f + rowH * 0.15f);
            path.transform(m);
            p.setStyle(Paint.Style.FILL);
            c.drawPath(path, p);
            // Region of the numeral, so text sitting on it can be drawn darker.
            android.graphics.Region big = new android.graphics.Region();
            big.setPath(path, new android.graphics.Region(0, 0, w, h));

            // Weekday initials.
            p.setTypeface(medium);
            p.setTextSize(rowH * 0.42f);
            Calendar wd = (Calendar) now.clone();
            for (int i = 0; i < 7; i++) {
                wd.set(Calendar.DAY_OF_WEEK, (now.getFirstDayOfWeek() - 1 + i) % 7 + 1);
                String name = wd.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
                float ix = padX + colW * (i + 0.5f), iy = gridTop + rowH * 0.5f;
                p.setColor(onBig(big, ix, iy, p.measureText("W"), rowH * 0.42f) ? NAVY : BLUE);
                c.drawText(name.substring(0, 1).toUpperCase(Locale.getDefault()),
                        padX + colW * (i + 0.5f), gridTop + rowH * 0.62f, p);
            }

            // Dates: this month in blue (navy where they sit on the big numeral), others faded.
            Calendar d = (Calendar) first.clone();
            d.add(Calendar.DAY_OF_MONTH, -lead);
            p.setTextSize(rowH * 0.46f);
            for (int row = 0; row < 6; row++) {
                for (int col = 0; col < 7; col++) {
                    boolean inMonth = d.get(Calendar.MONTH) == now.get(Calendar.MONTH);
                    float x = padX + colW * (col + 0.5f);
                    float y = gridTop + rowH * (row + 1) + rowH * 0.5f;
                    String n = String.valueOf(d.get(Calendar.DAY_OF_MONTH));
                    if (inMonth && row == todayRow && col == todayCol) {
                        float rad = rowH * 0.42f;
                        p.setColor(NAVY);
                        c.drawCircle(x, y, rad, p);
                        p.setColor(0xFFFFFFFF);
                    } else if (!inMonth) {
                        p.setColor(FADED);
                    } else {
                        p.setColor(onBig(big, x, y, p.measureText(n), rowH * 0.46f) ? NAVY : BLUE);
                    }
                    c.drawText(n, x, y - (p.descent() + p.ascent()) / 2f, p);
                    d.add(Calendar.DAY_OF_MONTH, 1);
                }
            }

            // Hint at the bottom.
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextSize(h * 0.022f);
            p.setColor(0x80000000);
            c.drawText("Press OK to return home", w / 2f, h * 0.975f, p);
        }
    }
}
