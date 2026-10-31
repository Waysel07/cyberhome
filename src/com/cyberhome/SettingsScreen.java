package com.cyberhome;

import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings: seven categories. What can be done with normal app rights is done here (Wi-Fi, volume, Bluetooth,
 * device info); the rest opens the matching page of the stock Settings app or the projector's own tools.
 */
final class SettingsScreen implements Screen {
    private static final int INFO = 0, ACTION = 1, SLIDER = 2, TOGGLE = 3;

    private static final String[] CATS = {"NETWORK", "SOUND", "PROJECTOR", "BLUETOOTH", "INPUT & TIME", "DEVICE", "SYSTEM"};
    private static final String[] SUBS = {"WI-FI  //  ETHERNET", "VOLUME  //  MUTE", "FOCUS  //  KEYSTONE  //  ZOOM",
            "ACCESSORIES", "KEYBOARD  //  LANGUAGE", "MODEL  //  MEMORY  //  STORAGE", "APPS  //  DEVELOPER  //  ALL"};

    private static final class Row {
        final String label;
        final int kind;
        String value = "";
        float ratio;        // slider fill, 0..1
        Runnable action;

        Row(String label, int kind) {
            this.label = label;
            this.kind = kind;
        }
    }

    private final Context ctx;
    private final Ui ui;
    private final Services svc;
    private final Nav nav;
    private final Runnable redraw;
    private final AudioManager audio;

    private int cat;
    private int rowIdx;
    private boolean inRows;
    private List<Row> rows = new ArrayList<>();

    SettingsScreen(Context c, Ui ui, Services svc, Nav nav, Runnable redraw) {
        this.ctx = c;
        this.ui = ui;
        this.svc = svc;
        this.nav = nav;
        this.redraw = redraw;
        this.audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onShow() {
        inRows = false;
        rowIdx = 0;
        build();
    }

    @Override
    public void onHide() {
    }

    // ---- rows -------------------------------------------------------------------------------------------

    private Row info(String label, String value) {
        Row r = new Row(label, INFO);
        r.value = value;
        return r;
    }

    private Row go(String label, String value, final Intent intent) {
        Row r = new Row(label, ACTION);
        r.value = value;
        r.action = new Runnable() {
            @Override public void run() {
                svc.launch(intent);
            }
        };
        return r;
    }

    private static Intent sys(String action) {
        return new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Intent comp(String pkg, String cls) {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void build() {
        List<Row> r = new ArrayList<>();
        switch (cat) {
            case 0:
                networkRows(r);
                break;
            case 1:
                soundRows(r);
                break;
            case 2:
                projectorRows(r);
                break;
            case 3:
                bluetoothRows(r);
                break;
            case 4:
                r.add(info("KEYBOARD", keyboardName()));
                r.add(go("KEYBOARD SETTINGS", "OPEN", sys("android.settings.INPUT_METHOD_SETTINGS")));
                r.add(go("LANGUAGE", Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).toUpperCase(Locale.ENGLISH),
                        sys("android.settings.LOCALE_SETTINGS")));
                r.add(go("DATE & TIME", "OPEN", sys("android.settings.DATE_SETTINGS")));
                break;
            case 5:
                deviceRows(r);
                break;
            default:
                r.add(go("APPS", "OPEN", sys("android.settings.APPLICATION_SETTINGS")));
                r.add(go("STORAGE", "OPEN", sys("android.settings.INTERNAL_STORAGE_SETTINGS")));
                r.add(go("DEFAULT HOME", "OPEN", sys("android.settings.HOME_SETTINGS")));
                r.add(go("DEVELOPER OPTIONS", "OPEN", sys("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")));
                r.add(go("ALL SETTINGS  (STOCK)", "OPEN", sys("android.settings.tv.SETTINGS")));
        }
        rows = r;
        rowIdx = Math.min(rowIdx, Math.max(0, rows.size() - 1));
    }

    /** A system property, read through reflection (the API is hidden); empty when unset. */
    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return String.valueOf(c.getMethod("get", String.class).invoke(null, key));
        } catch (Exception e) {
            return "";
        }
    }

    /** Auto focus and auto keystone need the projector's USB camera; zero here means calibration cannot run. */
    @SuppressWarnings("deprecation")
    private static int cameraCount() {
        try {
            return android.hardware.Camera.getNumberOfCameras();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String onOff(String key) {
        return "1".equals(prop(key)) ? "ON" : "OFF";
    }

    /**
     * Projector controls. Running a focus pass, the keystone/angle screens and the switches all belong to the
     * projector's own apps (system apps with the rights to change the lens motor and the picture geometry), so
     * this page starts them and shows their current state; it does not try to imitate them.
     */
    private void projectorRows(List<Row> r) {
        Row focus = new Row("RUN AUTO FOCUS NOW", ACTION);
        focus.value = "START";
        focus.action = new Runnable() {
            @Override public void run() {
                // The same trigger the vendor's own menu uses; the focus screen opens and drives the lens motor.
                ctx.sendBroadcast(new Intent("svt.AutoFocus.action.dialog.start"));
            }
        };
        r.add(focus);
        r.add(go("PROJECTION SETTINGS", "KEYSTONE  //  ZOOM", comp("com.eternal.projection.settings",
                "com.eternal.projection.settings.MainActivity")));
        r.add(go("KEYSTONE ANGLE", "MANUAL", comp("com.eternal.projection.settings",
                "com.eternal.projection.settings.AngleActivity")));
        Intent sw = comp("com.siviton.substanbolauncher", "com.siviton.substanbolauncher.SettingsActivity");
        sw.putExtra("extra_start_fragment_index", 2);
        r.add(go("AUTO FOCUS & KEYSTONE SWITCHES", "OPEN", sw));
        r.add(info("CALIBRATION CAMERA", cameraCount() > 0 ? "DETECTED" : "NOT DETECTED"));
        r.add(info("AUTO FOCUS", onOff("persist.svt.auto.AutoFocus")));
        r.add(info("FOCUS AT POWER-ON", onOff("persist.svt.poweron.auto.AutoFocus")));
        r.add(info("AUTO KEYSTONE", onOff("persist.svt.auto.trapezoid")));
        r.add(go("TV MENU  //  PICTURE", "OPEN", comp("com.mediatek.wwtv.tvcenter",
                "com.mediatek.wwtv.tvcenter.nav.TurnkeyUiMainActivity")));
    }

    private void networkRows(List<Row> r) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        String ssid = "OFFLINE";
        try {
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null && wi.getNetworkId() != -1) {
                ssid = wi.getSSID() == null || wi.getSSID().contains("unknown") ? "CONNECTED" : wi.getSSID().replace("\"", "");
            }
        } catch (SecurityException ignored) {
            // Name hidden without the location permission.
        }
        Row wifi = new Row("WI-FI NETWORKS", ACTION);
        wifi.value = ssid;
        wifi.action = new Runnable() {
            @Override public void run() {
                nav.openWifi();
            }
        };
        r.add(wifi);
        r.add(info("IP ADDRESS", ip()));
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo eth = cm.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
        r.add(info("ETHERNET", eth != null && eth.isConnected() ? "CONNECTED" : "NO CABLE"));
        r.add(go("ADVANCED NETWORK", "OPEN", sys("android.settings.WIFI_SETTINGS")));
    }

    private void soundRows(List<Row> r) {
        final int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        Row vol = new Row("VOLUME", SLIDER);
        vol.ratio = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        vol.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " / " + max;
        r.add(vol);
        Row mute = new Row("MUTE", TOGGLE);
        mute.value = audio.isStreamMute(AudioManager.STREAM_MUSIC) ? "ON" : "OFF";
        mute.action = new Runnable() {
            @Override public void run() {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            }
        };
        r.add(mute);
        r.add(go("SOUND SETTINGS", "OPEN", sys("android.settings.SOUND_SETTINGS")));
    }

    private void bluetoothRows(List<Row> r) {
        final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) {
            r.add(info("BLUETOOTH", "NOT AVAILABLE"));
            return;
        }
        Row t = new Row("BLUETOOTH", TOGGLE);
        t.value = bt.isEnabled() ? "ON" : "OFF";
        t.action = new Runnable() {
            @Override public void run() {
                try {
                    if (bt.isEnabled()) bt.disable(); else bt.enable();
                } catch (SecurityException ignored) {
                    // No permission: the stock page below still works.
                }
            }
        };
        r.add(t);
        int paired = 0;
        try {
            paired = bt.isEnabled() ? bt.getBondedDevices().size() : 0;
        } catch (SecurityException ignored) {
            // Count unavailable.
        }
        r.add(info("PAIRED DEVICES", bt.isEnabled() ? String.valueOf(paired) : "-"));
        r.add(go("PAIR AN ACCESSORY", "OPEN", sys("android.settings.BLUETOOTH_SETTINGS")));
    }

    private void deviceRows(List<Row> r) {
        r.add(info("MODEL", (Build.MANUFACTURER + " " + Build.MODEL).toUpperCase(Locale.ENGLISH)));
        r.add(info("ANDROID", Build.VERSION.RELEASE + "  (SDK " + Build.VERSION.SDK_INT + ")"));
        r.add(info("BUILD", Build.DISPLAY.toUpperCase(Locale.ENGLISH)));
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        r.add(info("MEMORY", (mi.totalMem - mi.availMem) / (1024 * 1024) + " / " + mi.totalMem / (1024 * 1024) + " MB"));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
        r.add(info("STORAGE", String.format(Locale.US, "%.1f / %.1f GB", (total - free) / 1e9, total / 1e9)));
        long s = SystemClock.elapsedRealtime() / 1000;
        r.add(info("UPTIME", (s / 3600) + " H " + ((s / 60) % 60) + " MIN"));
        r.add(go("ABOUT  (STOCK)", "OPEN", sys("android.settings.ABOUT_DEVICE_SETTINGS")));
    }

    private String keyboardName() {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (id == null) return "-";
        String pkg = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
        try {
            return String.valueOf(ctx.getPackageManager().getApplicationLabel(
                    ctx.getPackageManager().getApplicationInfo(pkg, 0))).toUpperCase(Locale.ENGLISH);
        } catch (Exception e) {
            return pkg.toUpperCase(Locale.ENGLISH);
        }
    }

    private static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // No interface information.
        }
        return "-";
    }

    // ---- keys -------------------------------------------------------------------------------------------

    private boolean focusable(int i) {
        return rows.get(i).kind != INFO;
    }

    private boolean anyFocusable() {
        for (int i = 0; i < rows.size(); i++) if (focusable(i)) return true;
        return false;
    }

    private void adjustVolume(int dir) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        // About 20 steps from silent to full, whatever the firmware's range is (0-15 or 0-100).
        int step = Math.max(1, max / 20);
        int v = Math.max(0, Math.min(max, audio.getStreamVolume(AudioManager.STREAM_MUSIC) + dir * step));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
    }

    @Override
    public boolean key(int code) {
        if (!inRows) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (cat > 0) { cat--; rowIdx = 0; build(); }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (cat < CATS.length - 1) { cat++; rowIdx = 0; build(); }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (anyFocusable()) {
                        inRows = true;
                        rowIdx = 0;
                        while (!focusable(rowIdx)) rowIdx++;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                default:
                    return false;
            }
            redraw.run();
            return true;
        }
        Row r = rows.get(rowIdx);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                int i = rowIdx - 1;
                while (i >= 0 && !focusable(i)) i--;
                if (i >= 0) rowIdx = i;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int i = rowIdx + 1;
                while (i < rows.size() && !focusable(i)) i++;
                if (i < rows.size()) rowIdx = i;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (r.kind == SLIDER) { adjustVolume(-1); build(); } else inRows = false;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (r.kind == SLIDER) { adjustVolume(1); build(); } else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (r.action != null) {
                    r.action.run();
                    build();
                }
                break;
            default:
                return false;
        }
        redraw.run();
        return true;
    }

    @Override
    public boolean back() {
        if (inRows) {
            inRows = false;
            redraw.run();
            return true;
        }
        return false;
    }

    // ---- drawing ----------------------------------------------------------------------------------------

    @Override
    public void drawBase(Canvas c) {
        ui.title(c, "SETTINGS");
        ui.txt(c, "CATEGORIES", 160, 170, 16, Color.argb(210, 255, 110, 110), ui.medium, Paint.Align.LEFT, 0.16f);
        ui.txt(c, "OPTIONS", 620, 170, 16, Color.argb(210, 255, 110, 110), ui.medium, Paint.Align.LEFT, 0.16f);
        ui.line(c, 160, 186, 1760, 186, Color.argb(110, 232, 64, 64), 1);
        ui.footer(c);
    }

    @Override
    public void drawLive(Canvas c) {
        categories(c);
        panel(c);
    }

    private void categories(Canvas c) {
        float x0 = 160, x1 = 560, y0 = 206, h = 80, gap = 10;
        for (int i = 0; i < CATS.length; i++) {
            float y = y0 + i * (h + gap);
            boolean sel = i == cat;
            boolean focus = sel && !inRows;
            Path pa = ui.cut(x0, y, x1, y + h, 0, 0, 16, 0);
            ui.fill(c, pa, focus ? Ui.RED_FILL : sel ? Color.argb(210, 60, 18, 24) : Ui.PANEL);
            ui.stroke(c, pa, focus ? Color.rgb(240, 80, 80) : sel ? Ui.RED : Ui.RED_BORDER, focus ? 2.5f : 1.5f);
            ui.rect(c, x0, y, x0 + 4, y + h, focus ? Color.rgb(255, 110, 110) : Ui.RED);
            float cy = y + h / 2f;
            ui.txt(c, CATS[i], x0 + 28, cy - 6, 24, Ui.CYAN, ui.semi, Paint.Align.LEFT);
            ui.txt(c, SUBS[i], x0 + 28, cy + 18, 9.5f, Color.argb(focus ? 210 : 120, 54, 226, 238), ui.medium, Paint.Align.LEFT, 0.14f);
            if (focus) {
                float bx = x1 - 62, by = cy - 17;
                ui.p.setStyle(Paint.Style.STROKE);
                ui.p.setStrokeWidth(2.5f);
                ui.p.setColor(Ui.CYAN);
                c.drawRect(bx, by, bx + 34, by + 34, ui.p);
            }
        }
    }

    private void panel(Canvas c) {
        float x0 = 620, x1 = 1760, y0 = 206, y1 = 886;
        Path box = ui.cut(x0, y0, x1, y1, 0, 0, 18, 0);
        ui.fill(c, box, Color.argb(215, 24, 9, 14));
        ui.p.setStyle(Paint.Style.FILL);
        ui.p.setColor(Ui.NAVY);
        c.drawRect(x0, y0, x1, y0 + 56, ui.p);
        ui.stroke(c, box, Ui.RED_BORDER, 1.5f);
        ui.txt(c, CATS[cat], x0 + 28, y0 + 29, 24, Ui.CYAN, ui.semi, Paint.Align.LEFT, 0.12f);
        boolean onSlider = inRows && rowIdx < rows.size() && rows.get(rowIdx).kind == SLIDER;
        ui.txt(c, onSlider ? "LEFT / RIGHT  ADJUST     BACK  CATEGORIES" : inRows ? "LEFT  BACK TO CATEGORIES" : "RIGHT  OPEN",
                x1 - 28, y0 + 29, 12, Color.rgb(110, 125, 185), ui.medium, Paint.Align.RIGHT, 0.14f);

        boolean dense = rows.size() > 6;
        float rowH = dense ? 66 : 76, gap = dense ? 6 : 8, ry = y0 + 70;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float y = ry + i * (rowH + gap);
            boolean focus = inRows && i == rowIdx;
            Path pa = ui.cut(x0 + 24, y, x1 - 24, y + rowH, 0, 0, 12, 0);
            ui.fill(c, pa, focus ? Color.argb(235, 8, 52, 64) : Color.argb(190, 17, 10, 14));
            ui.stroke(c, pa, focus ? Ui.CYAN : Ui.RED_BORDER, focus ? 3.5f : 1.5f);
            float cy = y + rowH / 2f;
            boolean dim = r.kind == INFO;
            ui.txt(c, r.label, x0 + 56, cy, 22, dim ? Ui.CYAN_DIM : focus ? Color.WHITE : Ui.CYAN, ui.semi, Paint.Align.LEFT, 0.06f);
            float vx = x1 - 56;
            if (r.kind == SLIDER) {
                float sx0 = x0 + 400, sx1 = x1 - 200;
                int segs = 24;
                int on = Math.round(segs * r.ratio);
                float sw = (sx1 - sx0) / segs;
                for (int s = 0; s < segs; s++) {
                    ui.rect(c, sx0 + s * sw + 2, cy - 14, sx0 + (s + 1) * sw - 2, cy + 14,
                            s < on ? (focus ? Ui.CYAN : Ui.CYAN_DIM) : Color.argb(90, 98, 28, 34));
                }
                ui.txt(c, r.value, vx, cy, 24, Color.WHITE, ui.light, Paint.Align.RIGHT);
            } else if (r.kind == TOGGLE) {
                boolean on = "ON".equals(r.value);
                float bw = 96, bh = 36, bx = vx - bw, by = cy - bh / 2;
                Path tp = ui.cut(bx, by, bx + bw, by + bh, 0, 0, 8, 0);
                ui.fill(c, tp, on ? Color.argb(210, 10, 90, 100) : Color.argb(210, 60, 18, 24));
                ui.stroke(c, tp, on ? Ui.CYAN : Ui.RED, 2);
                ui.txt(c, on ? "ON" : "OFF", bx + bw / 2, cy, 20, on ? Ui.CYAN : Ui.RED_TEXT, ui.semi, Paint.Align.CENTER, 0.12f);
            } else if (r.kind == ACTION) {
                ui.txt(c, "›", vx, cy - 2, 34, focus ? Ui.CYAN : Ui.CYAN_DIM, ui.light, Paint.Align.RIGHT);
                ui.txt(c, r.value, vx - 34, cy, 22, Color.WHITE, ui.light, Paint.Align.RIGHT);
            } else {
                ui.txt(c, r.value, vx, cy, 22, Color.WHITE, ui.light, Paint.Align.RIGHT);
            }
        }
    }
}
