package com.cyberhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The whole Home screen, drawn on one canvas in a 1920x1080 space (scaled to the display), in the look of
 * the Cyberpunk 2077 pause menu: red-black glass panels, a red rule across the top, cyan labels and a
 * single selection at a time. Focus is handled here so D-pad movement is exact.
 */
final class CyberView extends View {
    interface Host extends Services {
        void open(AppInfo app);
        void action(int id);
        /** The TV's inputs as {label, id} pairs. */
        List<String[]> inputs();
        void input(String id);
    }

    static final int GRID_COLS = 8;

    static final int ACT_ALL = 0, ACT_INPUTS = 1, ACT_WIFI = 2, ACT_SETTINGS = 3;
    static final String[] ACTIONS = {"ALL APPS", "INPUTS", "WI-FI", "SETTINGS"};
    static final String[] SUBTITLES = {"EVERY INSTALLED APPLICATION", "HDMI  //  AV  //  LIVE", "NETWORK CONNECTION",
            "DISPLAY  //  SYSTEM  //  APPS"};

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

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect bounds = new Rect();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final DecimalFormat grouped = new DecimalFormat("#,##0");
    private final Typeface light, medium, semi;
    private Bitmap bg;
    private Bitmap base;
    private boolean baseDirty = true;
    private int baseScreen = -1;

    private final Host host;
    private final Btc btc;
    private final Sys sys;
    private final List<AppInfo> dock;
    private final List<AppInfo> all;
    private final int appCount;

    int zone;   // 0 = tab row, 1 = right column
    int idx;
    int screen; // 0 = home, 1 = all apps grid
    int gridIdx;
    List<String[]> popup; // the Inputs list while it is open, else null
    int popIdx;

    // Settings and Wi-Fi pages: `active` is the one on screen, null on Home and the app grid.
    private final Ui ui;
    private final SettingsScreen settings;
    private final WifiScreen wifi;
    private Screen active;
    private Screen wifiParent;

    CyberView(Context c, Host host, Btc btc, Sys sys, List<AppInfo> dock, List<AppInfo> all) {
        super(c);
        this.host = host;
        this.btc = btc;
        this.sys = sys;
        this.dock = dock;
        this.all = all;
        this.appCount = all.size();
        this.ui = new Ui(c);
        final Runnable redraw = new Runnable() {
            @Override public void run() {
                invalidate();
            }
        };
        Screen.Nav nav = new Screen.Nav() {
            @Override public void openWifi() {
                swap(wifi, settings);
            }

            @Override public void close() {
                closeScreens();
            }
        };
        this.settings = new SettingsScreen(c, ui, host, nav, redraw);
        this.wifi = new WifiScreen(c, ui, host, nav, redraw);
        light = Typeface.createFromAsset(c.getAssets(), "Rajdhani-Light.ttf");
        medium = Typeface.createFromAsset(c.getAssets(), "Rajdhani-Medium.ttf");
        semi = Typeface.createFromAsset(c.getAssets(), "Rajdhani-SemiBold.ttf");
        try (InputStream in = c.getAssets().open("bg.jpg")) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bg = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            bg = null;
        }
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0);
        ColorMatrix tint = new ColorMatrix(new float[]{
                0.21f * 0.35f, 0.72f * 0.35f, 0.07f * 0.35f, 0, 20,
                0.21f * 0.95f, 0.72f * 0.95f, 0.07f * 0.95f, 0, 40,
                0.21f * 1.05f, 0.72f * 1.05f, 0.07f * 1.05f, 0, 45,
                0, 0, 0, 1, 0});
        iconPaint.setColorFilter(new ColorMatrixColorFilter(tint));
        setFocusable(true);
        setBackgroundColor(Color.BLACK);
    }

    // ---- keys -------------------------------------------------------------------------------------------

    /** Returns true when the key moved the selection or opened something. */
    boolean key(int code) {
        if (active != null) {
            boolean used = active.key(code);
            if (used) invalidate();
            return used;
        }
        if (popup != null) return popupKey(code);
        if (screen == 1) return gridKey(code);
        int n = dock.size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (zone == 0) { if (idx > 0) idx--; else return true; }
                else { zone = 0; idx = Math.max(0, n - 1); }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (zone == 0) { if (idx < n - 1) idx++; else { zone = 1; idx = 1; } }
                else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == 1 && idx > 0) idx--; else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == 1 && idx < ACTIONS.length - 1) idx++; else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (zone == 0 && idx < n) host.open(dock.get(idx));
                else if (zone == 1 && idx == ACT_ALL) { screen = 1; gridIdx = 0; }
                else if (zone == 1 && idx == ACT_INPUTS) { openInputs(); }
                else if (zone == 1 && idx == ACT_WIFI) { swap(wifi, null); }
                else if (zone == 1 && idx == ACT_SETTINGS) { swap(settings, null); }
                else if (zone == 1) { host.action(idx); return true; }
                else return true;
                break;
            default:
                return false;
        }
        invalidate();
        return true;
    }

    private void openInputs() {
        List<String[]> items = host.inputs();
        if (items.isEmpty()) {
            host.action(ACT_INPUTS);
            return;
        }
        popup = items;
        popIdx = 0;
    }

    private boolean popupKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (popIdx > 0) popIdx--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (popIdx < popup.size() - 1) popIdx++;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                popup = null;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                String id = popup.get(popIdx)[1];
                popup = null;
                host.input(id);
                break;
            default:
                return false;
        }
        invalidate();
        return true;
    }

    private boolean gridKey(int code) {
        int n = all.size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (gridIdx % GRID_COLS > 0) gridIdx--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (gridIdx % GRID_COLS < GRID_COLS - 1 && gridIdx < n - 1) gridIdx++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (gridIdx >= GRID_COLS) gridIdx -= GRID_COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (gridIdx + GRID_COLS < n) gridIdx += GRID_COLS;
                else if (gridIdx / GRID_COLS < (n - 1) / GRID_COLS) gridIdx = n - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (gridIdx < n) host.open(all.get(gridIdx));
                return true;
            default:
                return false;
        }
        invalidate();
        return true;
    }

    /** Back: closes the Inputs list or the grid. Returns false when already on the home screen. */
    boolean back() {
        if (active != null) {
            if (active.back()) return true;
            if (active == wifi && wifiParent != null) swap(wifiParent, null); else closeScreens();
            return true;
        }
        if (popup != null) { popup = null; invalidate(); return true; }
        if (screen == 1) { screen = 0; invalidate(); return true; }
        return false;
    }

    void resetFocus() {
        zone = 0;
        idx = 0;
        screen = 0;
        popup = null;
        closeScreens();
    }

    /** Shows a page (Settings or Wi-Fi); `parent` is where Back returns to, null for Home. */
    private void swap(Screen next, Screen parent) {
        if (active != null) active.onHide();
        active = next;
        wifiParent = parent;
        screen = 2;
        baseDirty = true;
        next.onShow();
        invalidate();
    }

    private void closeScreens() {
        if (active != null) active.onHide();
        active = null;
        wifiParent = null;
        screen = 0;
        baseDirty = true;
        invalidate();
    }

    /** The activity is leaving the foreground: stop scanning. */
    void pauseScreens() {
        if (active != null) active.onHide();
    }

    void resumeScreens() {
        if (active != null) active.onShow();
    }

    Bitmap snap() {
        Bitmap b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        paintScreen(c);
        return b;
    }

    // ---- drawing helpers --------------------------------------------------------------------------------

    private void txt(Canvas c, String s, float x, float cy, float size, int color, Typeface tf, Paint.Align a) {
        txt(c, s, x, cy, size, color, tf, a, 0.04f);
    }

    /** Text with its capital height centred on cy. */
    private void txt(Canvas c, String s, float x, float cy, float size, int color, Typeface tf, Paint.Align a,
                     float spacing) {
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

    private float width(String s, float size, Typeface tf, float spacing) {
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setLetterSpacing(spacing);
        float w = p.measureText(s);
        p.setLetterSpacing(0);
        return w;
    }

    /** Fits the text into maxW by lowering its size when needed. */
    private float fit(String s, float size, Typeface tf, float maxW) {
        float w = width(s, size, tf, 0.04f);
        return w <= maxW ? size : size * maxW / w;
    }

    private Path cut(float x0, float y0, float x1, float y1, float tl, float tr, float br, float bl) {
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

    private void fill(Canvas c, Path pa, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(pa, p);
    }

    private void stroke(Canvas c, Path pa, int color, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawPath(pa, p);
    }

    private void line(Canvas c, float x0, float y0, float x1, float y1, int color, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawLine(x0, y0, x1, y1, p);
    }

    private void hex(Canvas c, float cx, float cy, float r, int color, float w) {
        path.reset();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i + 30);
            float x = (float) (cx + r * Math.cos(a)), y = (float) (cy + r * Math.sin(a));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        stroke(c, path, color, w);
    }

    private void icon(Canvas c, Bitmap b, float cx, float cy, float size) {
        if (b == null) return;
        RectF dst = new RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        c.drawBitmap(b, null, dst, iconPaint);
    }

    // ---- screen -----------------------------------------------------------------------------------------

    /** Data (price, stats, time) changed: the cached layer has to be redrawn. */
    void dataChanged() {
        baseDirty = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        c.save();
        c.scale(getWidth() / 1920f, getHeight() / 1080f);
        // The slow parts (background, gauges, chart, bars) live in a bitmap that is redrawn only when the
        // data or the screen changes; a key press then only repaints the few focus-dependent shapes.
        if (base == null) base = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        if (baseDirty || baseScreen != screen) {
            paintBase(new Canvas(base));
            baseDirty = false;
            baseScreen = screen;
        }
        c.drawBitmap(base, 0, 0, null);
        paintLive(c);
        c.restore();
    }

    private void paintScreen(Canvas c) {
        paintBase(c);
        paintLive(c);
    }

    private void paintBase(Canvas c) {
        if (bg != null) {
            c.drawBitmap(bg, null, new RectF(0, 0, 1920, 1080), null);
        } else {
            c.drawColor(Color.rgb(14, 7, 10));
        }
        topBar(c);
        if (active != null) {
            active.drawBase(c);
            return;
        }
        if (screen == 1) {
            gridHeader(c);
            return;
        }
        btcPanel(c);
        card(c, 688, "MEMORY " + sys.ramTotalMb + " MB", sys.ramPct, "RAM");
        card(c, 893, "STORAGE " + sys.storageTotalGb + " GB", sys.storagePct, "DISK");
        card(c, 1097, "LINK " + (sys.network.length() > 12 ? sys.network.substring(0, 12) : sys.network),
                sys.signalPct, "NET");
        logPanel(c);
        ticker(c);
        bottom(c);
    }

    /** What depends on the selection: drawn on every frame over the cached layer. */
    private void paintLive(Canvas c) {
        if (active != null) {
            active.drawLive(c);
            return;
        }
        if (screen == 1) {
            gridCells(c);
            return;
        }
        tabs(c);
        rightColumn(c);
        if (popup != null) inputsPopup(c);
        else hint(c, 1800, 1042, "Select", false);
    }

    /** The last day of BTC in four hour steps: time, price and the move since the step before. */
    private void logPanel(Canvas c) {
        float x0 = 1330, x1 = 1800, y0 = 96, y1 = 335;
        Path body = cut(x0, y0, x1, y1, 0, 0, 14, 0);
        fill(c, body, Color.argb(215, 24, 9, 14));
        p.setStyle(Paint.Style.FILL);
        p.setColor(NAVY);
        c.drawRect(x0, y0, x1, y0 + 44, p);
        stroke(c, body, RED_BORDER, 1.5f);
        txt(c, "MARKET LOG  //  4H", x0 + 16, y0 + 24, 18, CYAN, semi, Paint.Align.LEFT, 0.08f);
        txt(c, "BTC-EUR", x1 - 14, y0 + 24, 12, Color.rgb(110, 125, 185), medium, Paint.Align.RIGHT, 0.06f);
        int n = btc.closes.length;
        if (n < 17) {
            txt(c, "NO DATA", (x0 + x1) / 2, 215, 26, RED_TEXT, light, Paint.Align.CENTER);
            return;
        }
        long now = System.currentTimeMillis();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
        for (int i = 0; i < 6; i++) {
            int at = Math.max(0, n - 1 - (5 - i) * 16);
            int prev = Math.max(0, at - 16);
            double v = btc.closes[Math.min(n - 1, at)];
            double d = (v - btc.closes[prev]) / btc.closes[prev] * 100.0;
            float ry = y0 + 70 + i * 30;
            String t = hm.format(new java.util.Date(now - (long) (n - 1 - at) * 15 * 60_000L));
            if (i % 2 == 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(40, 255, 80, 80));
                c.drawRect(x0 + 10, ry - 13, x1 - 10, ry + 15, p);
            }
            txt(c, t, x0 + 22, ry, 19, CYAN_DIM, medium, Paint.Align.LEFT);
            txt(c, "€" + grouped.format(v), x0 + 200, ry, 22, Color.WHITE, light, Paint.Align.RIGHT, 0.02f);
            boolean up = d >= 0;
            txt(c, (up ? "▲ " : "▼ ") + String.format(Locale.US, "%.2f%%", Math.abs(d)), x1 - 22, ry, 19,
                    i == 0 ? CYAN_DIM : (up ? CYAN : RED_TEXT), semi, Paint.Align.RIGHT);
        }
    }

    /** One line of live numbers between the tab row and the bottom bars. */
    private void ticker(Canvas c) {
        float x0 = 165, x1 = 1425, y = 655;
        line(c, x0, y - 22, x1, y - 22, Color.argb(70, 232, 64, 64), 1);
        line(c, x0, y + 22, x1, y + 22, Color.argb(70, 232, 64, 64), 1);
        p.setStyle(Paint.Style.FILL);
        p.setColor(RED);
        c.drawRect(x0, y - 22, x0 + 4, y + 22, p);
        String[] cells = {
                "NET  " + sys.network,
                "SIGNAL  " + sys.signalPct + "%",
                "RAM  " + sys.ramPct + "%",
                "DISK  " + sys.storagePct + "%",
                btc.closes.length < 2 ? "BTC  --" : String.format(Locale.US, "BTC  %s%.2f%%", btc.changePct() >= 0 ? "+" : "",
                        btc.changePct())};
        float cw = (x1 - x0 - 20) / cells.length;
        for (int i = 0; i < cells.length; i++) {
            float cx = x0 + 20 + i * cw;
            txt(c, cells[i], cx, y, fit(cells[i], 17, medium, cw - 24), i == cells.length - 1 && btc.changePct() < 0 ? RED_TEXT : CYAN_DIM,
                    medium, Paint.Align.LEFT, 0.1f);
            if (i > 0) line(c, cx - 12, y - 10, cx - 12, y + 10, Color.argb(110, 232, 64, 64), 1);
        }
    }

    /** The Inputs list, in the style of the game's main menu: red items, the selected one framed in cyan. */
    private void inputsPopup(Canvas c) {
        float x0 = 1010, x1 = 1395, y0 = 470, rowH = 62;
        float y1 = y0 + 84 + popup.size() * rowH + 20;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(150, 0, 0, 0));
        c.drawRect(0, 90, 1920, 1080, p);
        Path box = cut(x0, y0, x1, y1, 0, 0, 18, 0);
        fill(c, box, Color.argb(240, 28, 9, 14));
        stroke(c, box, Color.rgb(200, 52, 52), 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(RED);
        c.drawRect(x0, y0, x0 + 4, y1, p);
        txt(c, "INPUTS", x0 + 28, y0 + 30, 26, CYAN, semi, Paint.Align.LEFT, 0.1f);
        txt(c, "SELECT A SOURCE", x0 + 28, y0 + 56, 10, Color.argb(160, 255, 110, 110), medium, Paint.Align.LEFT, 0.16f);
        line(c, x0 + 20, y0 + 72, x1 - 20, y0 + 72, Color.argb(120, 232, 64, 64), 1);
        for (int i = 0; i < popup.size(); i++) {
            float ry = y0 + 84 + i * rowH;
            boolean sel = i == popIdx;
            if (sel) {
                Path fr = cut(x0 + 16, ry, x1 - 16, ry + rowH - 8, 0, 0, 10, 0);
                fill(c, fr, Color.argb(130, 6, 50, 62));
                stroke(c, fr, CYAN, 2.5f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2);
                p.setColor(CYAN);
                c.drawRect(x1 - 62, ry + 10, x1 - 34, ry + rowH - 18, p);
            }
            txt(c, popup.get(i)[0], x0 + 40, ry + (rowH - 8) / 2f, 24, sel ? CYAN : RED_TEXT, semi, Paint.Align.LEFT);
        }
        hint(c, 1688, 1042, "Select", false);
        hint(c, 1800, 1042, "Close", true);
    }

    /** All apps as an inventory grid: dark cells with a light left edge, the selected one framed in cyan. */
    private void gridHeader(Canvas c) {
        // Header: the section name, centred under the red rule like the Backpack screen.
        txt(c, "APPLICATIONS", 960, 112, 26, CYAN, semi, Paint.Align.CENTER, 0.18f);
        line(c, 760, 134, 1160, 134, Color.argb(160, 54, 226, 238), 1.5f);
        txt(c, "FILTERS", 160, 170, 16, Color.argb(210, 255, 110, 110), medium, Paint.Align.LEFT, 0.16f);
        txt(c, "SORTING  A-Z", 1760, 170, 16, Color.argb(210, 255, 110, 110), medium, Paint.Align.RIGHT, 0.16f);
        line(c, 160, 186, 1760, 186, Color.argb(110, 232, 64, 64), 1);
        line(c, 60, 955, 1860, 955, Color.argb(190, 232, 64, 64), 1.5f);
    }

    private void gridCells(Canvas c) {
        int n = all.size();
        float cell = 150, gap = 16;
        float gridW = GRID_COLS * cell + (GRID_COLS - 1) * gap;
        float gx = (1920 - gridW) / 2f, gy = 210;
        int rows = Math.max(3, (n + GRID_COLS - 1) / GRID_COLS);
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < GRID_COLS; k++) {
                int i = r * GRID_COLS + k;
                float x = gx + k * (cell + gap), y = gy + r * (cell + gap);
                boolean sel = i == gridIdx;
                Path pa = cut(x, y, x + cell, y + cell, 0, 0, 14, 0);
                fill(c, pa, i < n ? Color.argb(sel ? 235 : 205, sel ? 8 : 12, sel ? 34 : 16, sel ? 44 : 30)
                        : Color.argb(120, 12, 9, 13));
                stroke(c, pa, sel ? CYAN : Color.argb(i < n ? 170 : 70, 132, 36, 42), sel ? 3 : 1.5f);
                if (i >= n) continue;
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.rgb(205, 212, 222));
                c.drawRect(x, y + 8, x + 4, y + cell - 18, p);
                p.setColor(YELLOW);
                c.drawRect(x + cell - 26, y + 8, x + cell - 10, y + 16, p);
                Bitmap ic = all.get(i).icon;
                if (ic != null) {
                    RectF dst = new RectF(x + cell / 2 - 38, y + cell / 2 - 44, x + cell / 2 + 38, y + cell / 2 + 32);
                    c.drawBitmap(ic, null, dst, null);
                }
                String nm = all.get(i).label.toUpperCase(Locale.ENGLISH);
                txt(c, nm, x + cell / 2, y + cell - 22, fit(nm, 14, medium, cell - 20), sel ? CYAN : CYAN_DIM, medium,
                        Paint.Align.CENTER, 0.08f);
            }
        }
        if (gridIdx < n) {
            float ty = gy + rows * (cell + gap) + 40;
            txt(c, all.get(gridIdx).label.toUpperCase(Locale.ENGLISH), gx, ty, 34, CYAN, semi, Paint.Align.LEFT, 0.06f);
            txt(c, all.get(gridIdx).pkg.toUpperCase(Locale.ENGLISH), gx, ty + 30, 12, Color.argb(190, 255, 110, 110), medium,
                    Paint.Align.LEFT, 0.12f);
        }
        txt(c, (gridIdx + 1) + " / " + n, 1760, gy + rows * (cell + gap) + 40, 26, CYAN_DIM, light, Paint.Align.RIGHT);
        hint(c, 1688, 1042, "Select", false);
        hint(c, 1800, 1042, "Close", true);
    }

    private void topBar(Canvas c) {
        Calendar now = Calendar.getInstance();
        int dim = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        float x = 82;
        String day = String.valueOf(now.get(Calendar.DAY_OF_MONTH));
        txt(c, day, x, 41, 40, CYAN, light, Paint.Align.LEFT, 0);
        float dx = x + width(day, 40, light, 0) + 10;
        String month = new SimpleDateFormat("MMM", Locale.ENGLISH).format(now.getTime()).toUpperCase(Locale.ENGLISH);
        txt(c, month, dx, 40, 26, CYAN_DIM, medium, Paint.Align.LEFT);
        bar(c, x + 34, 63, 78, now.get(Calendar.DAY_OF_MONTH) / (float) dim, Color.rgb(190, 232, 240));

        float gx = 300;
        txt(c, String.valueOf(appCount), gx, 41, 40, GREEN, light, Paint.Align.LEFT, 0);
        float gw = width(String.valueOf(appCount), 40, light, 0);
        txt(c, "APPS", gx + gw + 12, 40, 26, Color.rgb(120, 220, 140), medium, Paint.Align.LEFT);
        bar(c, gx + 42, 63, 78, sys.storagePct / 100f, Color.rgb(160, 240, 175));

        // Right side: price, network.
        String price = btc.closes.length < 2 ? "--" : grouped.format(btc.last());
        float pw = width(price, 34, light, 0.02f);
        txt(c, price, 1842, 46, 34, YELLOW, light, Paint.Align.RIGHT, 0.02f);
        txt(c, "BTC €", 1842 - pw - 12, 46, 26, Color.rgb(215, 178, 56), semi, Paint.Align.RIGHT);
        float nx = 1842 - pw - 12 - width("BTC €", 26, semi, 0.04f) - 54;
        txt(c, sys.network, nx, 46, 30, RED_TEXT, light, Paint.Align.RIGHT);
        float ix = nx - width(sys.network, 30, light, 0.04f) - 36;
        wifiIcon(c, ix, 56, RED, 1f);

        // The red rule.
        line(c, 51, 74, 1872, 74, Color.rgb(214, 56, 56), 2.5f);
    }

    private void bar(Canvas c, float x, float y, float w, float frac, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(120, 60, 110, 125));
        c.drawRect(x, y, x + w, y + 3, p);
        p.setColor(color);
        c.drawRect(x, y, x + w * Math.max(0.05f, Math.min(1f, frac)), y + 3, p);
        p.setColor(Color.argb(150, 120, 190, 205));
        for (int i = 0; i < 4; i++) c.drawRect(x + w + 10 + i * 9, y, x + w + 13 + i * 9, y + 3, p);
    }

    /** The Wi-Fi symbol: three arcs centred on the dot at (cx, cy), which is the icon's bottom point. */
    private void wifiIcon(Canvas c, float cx, float cy, int color, float k) {
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

    /** One of the three framed gauge cards: a navy header, a ring of segments and the percentage below. */
    private void card(Canvas c, float x, String title, int pct, String tag) {
        float y0 = 96, y1 = 335, w = 170;
        Path body = cut(x, y0, x + w, y1, 0, 0, 12, 0);
        fill(c, body, Color.argb(215, 24, 9, 14));
        p.setShader(new LinearGradient(0, y0, 0, y1, Color.argb(34, 255, 70, 70), Color.argb(0, 255, 70, 70),
                Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawPath(body, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(NAVY);
        c.drawRect(x, y0, x + w, y0 + 44, p);
        line(c, x + w / 2, y0, x + w / 2, y0 + 8, Color.rgb(140, 150, 190), 2);
        txt(c, title, x + w / 2, y0 + 24, fit(title, 12, medium, w - 16), Color.rgb(110, 125, 185), medium,
                Paint.Align.CENTER, 0.06f);
        stroke(c, body, RED_BORDER, 1.5f);
        // Corner brackets inside the body.
        int br = Color.argb(200, 255, 110, 110);
        float bx0 = x + 8, bx1 = x + w - 8, by0 = y0 + 54, by1 = y1 - 10;
        line(c, bx0, by0, bx0 + 12, by0, br, 2);
        line(c, bx0, by0, bx0, by0 + 12, br, 2);
        line(c, bx1, by0, bx1 - 12, by0, br, 2);
        line(c, bx1, by0, bx1, by0 + 12, br, 2);
        line(c, bx0, by1, bx0 + 12, by1, br, 2);
        line(c, bx0, by1, bx0, by1 - 12, br, 2);

        // Dot-matrix texture behind the ring.
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(46, 255, 90, 90));
        for (float dy = by0 + 10; dy < by1 - 6; dy += 9) {
            for (float dx = bx0 + 8; dx < bx1 - 4; dx += 9) c.drawCircle(dx, dy, 1.1f, p);
        }
        // Ring of segments.
        float cx = x + w / 2, cy = y0 + 148, r = 56;
        int segs = 36;
        int on = Math.round(segs * Math.max(0, Math.min(100, pct)) / 100f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(9);
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        for (int i = 0; i < segs; i++) {
            p.setColor(i < on ? Color.rgb(240, 78, 78) : Color.argb(150, 98, 28, 34));
            c.drawArc(oval, -90 + i * 360f / segs + 1.5f, 360f / segs - 3.5f, false, p);
        }
        txt(c, tag, cx, cy, 30, RED_TEXT, semi, Paint.Align.CENTER);
        txt(c, pct + "%", x + 2, 364, 24, CYAN_DIM, light, Paint.Align.LEFT);
    }

    private void btcPanel(Canvas c) {
        float x0 = 100, y0 = 96, x1 = 646, y1 = 335;
        Path body = cut(x0, y0, x1, y1, 0, 0, 14, 0);
        fill(c, body, Color.argb(215, 30, 11, 16));
        p.setStyle(Paint.Style.FILL);
        p.setColor(NAVY);
        c.drawRect(x0, y0, x1, y0 + 44, p);
        stroke(c, body, RED_BORDER, 1.5f);
        txt(c, "BTC / EUR  ·  24H", x0 + 16, y0 + 24, 18, CYAN, semi, Paint.Align.LEFT, 0.08f);
        txt(c, "CONNECTION 221.89.42", x1 - 14, y0 + 24, 12, Color.rgb(110, 125, 185), medium, Paint.Align.RIGHT, 0.06f);

        if (btc.closes.length < 2) {
            txt(c, btc.failed ? "NO CONNECTION" : "LOADING", (x0 + x1) / 2, 215, 30, RED_TEXT, light, Paint.Align.CENTER);
            return;
        }
        double last = btc.last(), pct = btc.changePct();
        boolean up = pct >= 0;
        int accent = up ? CYAN : RED;
        txt(c, "€" + grouped.format(last), x0 + 16, y0 + 80, 52, Color.WHITE, light, Paint.Align.LEFT, 0.01f);
        txt(c, String.format(Locale.US, "%s%.2f%%", up ? "+" : "", pct), x1 - 16, y0 + 72, 32, accent, semi,
                Paint.Align.RIGHT);
        txt(c, "H " + grouped.format(btc.high) + "    L " + grouped.format(btc.low), x1 - 16, y0 + 100, 15,
                Color.argb(170, 255, 255, 255), medium, Paint.Align.RIGHT, 0.06f);

        float l = x0 + 16, r = x1 - 16, t = y0 + 124, b = y1 - 14;
        double lo = btc.low, hi = btc.high, padV = (hi - lo) * 0.08;
        lo -= padV;
        hi += padV;
        if (hi - lo < 1e-9) hi = lo + 1;
        for (int i = 0; i <= 3; i++) {
            float gy = t + (b - t) * i / 3f;
            line(c, l, gy, r, gy, Color.argb(48, 255, 90, 90), 1);
        }
        int n = btc.closes.length;
        Path ln = new Path(), ar = new Path();
        for (int i = 0; i < n; i++) {
            float px = l + (r - l) * i / (float) (n - 1);
            float py = (float) (b - (btc.closes[i] - lo) / (hi - lo) * (b - t));
            if (i == 0) { ln.moveTo(px, py); ar.moveTo(px, b); ar.lineTo(px, py); } else { ln.lineTo(px, py); ar.lineTo(px, py); }
        }
        ar.lineTo(r, b);
        ar.close();
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, t, 0, b, Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent)), Shader.TileMode.CLAMP));
        c.drawPath(ar, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(accent);
        c.drawPath(ln, p);
        float ly = (float) (b - (btc.last() - lo) / (hi - lo) * (b - t));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(r, ly, 5, p);
    }

    private void tabs(Canvas c) {
        int n = dock.size();
        if (n == 0) return;
        float x0 = 165, x1 = 1425, y0 = 505, y1 = 578;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(105, 10, 8, 12));
        c.drawRect(x0, y0, x1, y1, p);
        line(c, x0, y0, x1, y0, Color.argb(60, 130, 36, 42), 1);
        line(c, x0, y1, x1, y1, Color.argb(60, 130, 36, 42), 1);
        float tw = (x1 - x0) / n;
        for (int i = 0; i < n; i++) {
            float x = x0 + i * tw;
            boolean sel = zone == 0 && idx == i;
            if (sel) {
                Path fr = cut(x + 3, y0, x + tw - 3, y1, 0, 0, 14, 0);
                fill(c, fr, Color.argb(120, 6, 50, 62));
                stroke(c, fr, CYAN, 2.5f);
            }
            int col = sel ? CYAN : Color.rgb(44, 190, 204);
            hex(c, x + 40, (y0 + y1) / 2, 24, col, 2.2f);
            hex(c, x + 40, (y0 + y1) / 2, 19.5f, Color.argb(sel ? 150 : 80, 54, 226, 238), 1.2f);
            icon(c, dock.get(i).icon, x + 40, (y0 + y1) / 2, 26);
            String label = dock.get(i).label.toUpperCase(Locale.ENGLISH);
            txt(c, label, x + 76, (y0 + y1) / 2 - 5, fit(label, 22, semi, tw - 90), col, semi, Paint.Align.LEFT);
            txt(c, sel ? "OK  TO LAUNCH" : "APPLICATION", x + 76, (y0 + y1) / 2 + 16, 9.5f,
                    Color.argb(sel ? 200 : 120, 54, 226, 238), medium, Paint.Align.LEFT, 0.14f);
        }
    }

    private void rightColumn(Canvas c) {
        float x0 = 1425, x1 = 1733, h = 77;
        float[] ys = {418, 503, 588, 674};
        for (int i = 0; i < ACTIONS.length; i++) {
            float y0 = ys[i], y1 = y0 + h;
            boolean sel = zone == 1 && idx == i;
            Path pa = cut(x0, y0, x1, y1, 0, 0, 16, 0);
            fill(c, pa, sel ? RED_FILL : PANEL);
            if (sel) {
                p.setStyle(Paint.Style.FILL);
                p.setShader(new LinearGradient(x0, 0, x1, 0, Color.argb(0, 255, 90, 90), Color.argb(70, 255, 90, 90),
                        Shader.TileMode.CLAMP));
                c.drawPath(pa, p);
                p.setShader(null);
            }
            stroke(c, pa, sel ? Color.rgb(240, 80, 80) : RED_BORDER, sel ? 2.5f : 1.5f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(sel ? Color.rgb(255, 110, 110) : RED);
            c.drawRect(x0, y0, x0 + 4, y1, p);
            float cy = (y0 + y1) / 2;
            hex(c, x0 + 36, cy, 22, CYAN, 2.2f);
            glyph(c, i, x0 + 36, cy);
            txt(c, ACTIONS[i], x0 + 68, cy - 5, 22, CYAN, semi, Paint.Align.LEFT);
            txt(c, SUBTITLES[i], x0 + 68, cy + 16, 9.5f, Color.argb(sel ? 210 : 120, 54, 226, 238), medium,
                    Paint.Align.LEFT, 0.14f);
            if (sel) {
                float bx = x1 - 82, by = cy - 20;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f);
                p.setColor(CYAN);
                c.drawRect(bx, by, bx + 40, by + 40, p);
                p.setStrokeWidth(1.5f);
                c.drawRect(bx + 8, by + 8, bx + 32, by + 32, p);
            }
        }
    }

    private void glyph(Canvas c, int id, float cx, float cy) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(CYAN);
        switch (id) {
            case ACT_ALL:
                for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
                    float sx = cx - 9 + a * 10, sy = cy - 9 + b * 10;
                    c.drawRect(sx, sy, sx + 8, sy + 8, p);
                }
                break;
            case ACT_INPUTS:
                c.drawRect(cx - 10, cy - 8, cx + 10, cy + 8, p);
                line(c, cx - 14, cy, cx - 3, cy, CYAN, 2);
                line(c, cx - 7, cy - 4, cx - 3, cy, CYAN, 2);
                line(c, cx - 7, cy + 4, cx - 3, cy, CYAN, 2);
                break;
            case ACT_WIFI:
                wifiIcon(c, cx, cy + 9, CYAN, 0.8f);
                break;
            default:
                for (int i = 0; i < 3; i++) {
                    float ly = cy - 8 + i * 8;
                    line(c, cx - 11, ly, cx + 11, ly, CYAN, 2);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(CYAN);
                    c.drawCircle(cx - 5 + ((i * 7) % 12), ly, 3, p);
                }
        }
    }

    private void bottom(Canvas c) {
        Calendar now = Calendar.getInstance();
        // The clock card, red like the game's in-world time.
        Path card = cut(72, 830, 242, 893, 0, 0, 10, 0);
        fill(c, card, Color.rgb(98, 24, 30));
        stroke(c, card, Color.rgb(236, 78, 78), 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(70, 18, 24));
        c.drawRect(56, 852, 68, 874, p);
        c.drawRect(246, 852, 258, 874, p);
        String time = new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now.getTime());
        txt(c, time, 157, 862, 38, Color.rgb(255, 140, 138), light, Paint.Align.CENTER, 0.02f);
        txt(c, "LOCAL", 90, 838, 9, Color.rgb(255, 120, 118), medium, Paint.Align.LEFT, 0.12f);
        txt(c, "CET", 224, 886, 9, Color.rgb(255, 120, 118), medium, Paint.Align.RIGHT, 0.12f);

        // Two wide bars.
        Path b1 = cut(272, 833, 680, 890, 0, 0, 10, 0);
        fill(c, b1, Color.argb(170, 8, 14, 30));
        stroke(c, b1, Color.argb(190, 132, 36, 42), 1.5f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(RED);
        c.drawRect(272, 833, 276, 890, p);
        String date = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(now.getTime()).toUpperCase(Locale.ENGLISH);
        txt(c, date, 292, 862, 24, CYAN, semi, Paint.Align.LEFT);

        Path b2 = cut(1438, 833, 1845, 890, 0, 0, 10, 0);
        fill(c, b2, Color.argb(170, 8, 14, 30));
        stroke(c, b2, Color.argb(190, 132, 36, 42), 1.5f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(RED);
        c.drawRect(1438, 833, 1442, 890, p);
        hex(c, 1474, 862, 20, CYAN, 2);
        for (int i = 0; i < 3; i++) line(c, 1464, 855 + i * 7, 1484, 855 + i * 7, CYAN, 2);
        txt(c, "SYSTEM", 1508, 862, 24, CYAN, semi, Paint.Align.LEFT);

        // Footer captions.
        line(c, 60, 955, 705, 955, Color.argb(190, 232, 64, 64), 1.5f);
        line(c, 1290, 955, 1860, 955, Color.argb(190, 232, 64, 64), 1.5f);
        int cap = Color.argb(210, 235, 70, 70);
        txt(c, "MODEL LINE   MT5862", 60, 967, 12, cap, medium, Paint.Align.LEFT, 0.06f);
        txt(c, "IMAGE NAME: CYBERHOME 0.1", 572, 965, 11, cap, medium, Paint.Align.LEFT, 0.06f);
        txt(c, "IMAGE TYPE: ANDROID 9 ISOLATED IMAGE", 572, 977, 11, cap, medium, Paint.Align.LEFT, 0.06f);
        txt(c, "DISPLAY: 1920X1080 ARMEABI-V7A", 572, 989, 11, cap, medium, Paint.Align.LEFT, 0.06f);
        txt(c, "LOAD ADDRESS: " + String.format(Locale.US, "%08X", sys.ramTotalMb * 4099L), 572, 1001, 11, cap, medium,
                Paint.Align.LEFT, 0.06f);
        txt(c, "TRN_CYBERHOME_000001", 1290, 967, 12, cap, medium, Paint.Align.LEFT, 0.06f);
        Path tag = cut(1600, 962, 1860, 988, 0, 0, 0, 0);
        stroke(c, tag, Color.argb(210, 235, 70, 70), 1.2f);
        line(c, 1752, 962, 1752, 988, Color.argb(210, 235, 70, 70), 1.2f);
        txt(c, "TASK_MANAGER_HOME", 1608, 975, 10, cap, medium, Paint.Align.LEFT, 0.03f);
        txt(c, "COMBAT COORDINATION", 1760, 970, 7, cap, medium, Paint.Align.LEFT, 0.03f);
        txt(c, "DEFENCE PROGRAM", 1760, 981, 7, cap, medium, Paint.Align.LEFT, 0.03f);

    }

    private void hint(Canvas c, float cx, float cy, String label, boolean ring) {
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
}
