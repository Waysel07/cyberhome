package com.cyberhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Colours, fonts and the small drawing helpers the Settings and Wi-Fi screens share (1920x1080 space). */
final class Ui {
    static final int CYAN = Color.rgb(54, 226, 238);
    static final int CYAN_DIM = Color.rgb(120, 205, 215);
    static final int RED = Color.rgb(236, 62, 62);
    static final int RED_TEXT = Color.rgb(255, 96, 92);
    static final int RED_BORDER = Color.rgb(132, 36, 42);
    static final int RED_FILL = Color.rgb(122, 28, 34);
    static final int YELLOW = Color.rgb(240, 205, 62);
    static final int GREEN = Color.rgb(64, 224, 96);
    static final int PANEL = Color.argb(205, 16, 9, 13);
    static final int NAVY = Color.rgb(12, 16, 36);

    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final Typeface light, medium, semi;
    private final Rect bounds = new Rect();

    Ui(Context c) {
        light = Typeface.createFromAsset(c.getAssets(), "Rajdhani-Light.ttf");
        medium = Typeface.createFromAsset(c.getAssets(), "Rajdhani-Medium.ttf");
        semi = Typeface.createFromAsset(c.getAssets(), "Rajdhani-SemiBold.ttf");
    }

    void txt(Canvas c, String s, float x, float cy, float size, int color, Typeface tf, Paint.Align a) {
        txt(c, s, x, cy, size, color, tf, a, 0.04f);
    }

    /** Text with its capital height centred on cy. */
    void txt(Canvas c, String s, float x, float cy, float size, int color, Typeface tf, Paint.Align a, float spacing) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(a);
        p.setLetterSpacing(spacing);
        p.getTextBounds("H", 0, 1, bounds);
        c.drawText(s, x, cy + bounds.height() / 2f, p);
        p.setLetterSpacing(0);
    }

    float width(String s, float size, Typeface tf, float spacing) {
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setLetterSpacing(spacing);
        float w = p.measureText(s);
        p.setLetterSpacing(0);
        return w;
    }

    /** Lowers the size so the text fits maxW. */
    float fit(String s, float size, Typeface tf, float maxW) {
        float w = width(s, size, tf, 0.04f);
        return w <= maxW ? size : size * maxW / w;
    }

    Path cut(float x0, float y0, float x1, float y1, float tl, float tr, float br, float bl) {
        path.reset();
        path.moveTo(x0 + tl, y0);
        path.lineTo(x1 - tr, y0);
        path.lineTo(x1, y0 + tr);
        path.lineTo(x1, y1 - br);
        path.lineTo(x1 - br, y1);
        path.lineTo(x0 + bl, y1);
        path.lineTo(x0, y1 - bl);
        path.lineTo(x0, y0 + tl);
        path.close();
        return path;
    }

    void fill(Canvas c, Path pa, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(pa, p);
    }

    void stroke(Canvas c, Path pa, int color, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawPath(pa, p);
    }

    void line(Canvas c, float x0, float y0, float x1, float y1, int color, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawLine(x0, y0, x1, y1, p);
    }

    void rect(Canvas c, float x0, float y0, float x1, float y1, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRect(x0, y0, x1, y1, p);
    }

    void hex(Canvas c, float cx, float cy, float r, int color, float w) {
        path.reset();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i + 30);
            float x = (float) (cx + r * Math.cos(a)), y = (float) (cy + r * Math.sin(a));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        stroke(c, path, color, w);
    }

    /** The Wi-Fi symbol: three arcs centred on the dot at (cx, cy). */
    void wifi(Canvas c, float cx, float cy, int color, float k) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * k);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        for (int i = 1; i <= 3; i++) {
            float r = 7 * k * i;
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 228, 84, false, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, 2.8f * k, p);
    }

    /** Four signal bars, the first `level` (0..4) lit. (x, y) is the bottom-left corner. */
    void bars(Canvas c, float x, float y, int level, int color) {
        for (int i = 0; i < 4; i++) {
            float h = 8 + i * 7;
            rect(c, x + i * 10, y - h, x + i * 10 + 6, y, i < level ? color : Color.argb(80, 120, 190, 205));
        }
    }

    void lock(Canvas c, float cx, float cy, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(color);
        c.drawArc(new RectF(cx - 7, cy - 17, cx + 7, cy - 1), 180, 180, false, p);
        line(c, cx - 7, cy - 9, cx - 7, cy - 4, color, 2.5f);
        line(c, cx + 7, cy - 9, cx + 7, cy - 4, color, 2.5f);
        rect(c, cx - 11, cy - 4, cx + 11, cy + 11, color);
    }

    void hint(Canvas c, float cx, float cy, String label, boolean ring) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(CYAN);
        c.drawCircle(cx, cy, 11, p);
        if (ring) {
            c.drawCircle(cx, cy, 5, p);
        } else {
            line(c, cx - 4, cy - 4, cx + 4, cy + 4, CYAN, 2.5f);
            line(c, cx + 4, cy - 4, cx - 4, cy + 4, CYAN, 2.5f);
        }
        txt(c, label, cx + 19, cy, 24, CYAN, medium, Paint.Align.LEFT, 0.02f);
    }

    /** The page title under the red rule, as on the Applications grid. */
    void title(Canvas c, String s) {
        txt(c, s, 960, 112, 26, CYAN, semi, Paint.Align.CENTER, 0.18f);
        line(c, 760, 134, 1160, 134, Color.argb(160, 54, 226, 238), 1.5f);
    }

    void footer(Canvas c) {
        line(c, 60, 955, 1860, 955, Color.argb(190, 232, 64, 64), 1.5f);
        hint(c, 1688, 1042, "Select", false);
        hint(c, 1800, 1042, "Close", true);
    }
}
