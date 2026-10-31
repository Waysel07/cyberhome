package com.cyberhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Wi-Fi: the networks in range, what is connected, and connect / disconnect / forget with the remote. */
final class WifiScreen implements Screen {
    private static final int OPEN = 0, WEP = 1, PSK = 2, UNSUPPORTED = 3;
    private static final int VISIBLE_ROWS = 8;

    private static final class Net {
        String ssid;
        int security;
        int level;      // dBm
        int freq;
        int netId = -1; // saved configuration, or -1
        boolean connected;
        String securityName;
    }

    private final Context ctx;
    private final Ui ui;
    private final Services svc;
    private final Nav nav;
    private final Runnable redraw;
    private final WifiManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<Net> nets = new ArrayList<>();
    private int sel;        // index into nets; nets.size() is the "hidden network" row
    private int top;        // first visible row
    private boolean inButtons;
    private int btn;
    private String message = "";
    private boolean registered;
    private boolean permissionNote;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (!message.isEmpty() && (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)
                    || WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(a))) {
                // Only report a connection the user just asked for.
                WifiInfo wi = wm.getConnectionInfo();
                if (message.startsWith("CONNECTING") && wi != null
                        && wi.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED) {
                    say("CONNECTED");
                }
            }
            rebuild();
        }
    };

    private final Runnable rescan = new Runnable() {
        @Override public void run() {
            try {
                wm.startScan();
            } catch (Exception ignored) {
                // Scanning not allowed right now.
            }
            main.postDelayed(this, 15_000);
        }
    };

    WifiScreen(Context c, Ui ui, Services svc, Nav nav, Runnable redraw) {
        this.ctx = c;
        this.ui = ui;
        this.svc = svc;
        this.nav = nav;
        this.redraw = redraw;
        this.wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    // ---- life -------------------------------------------------------------------------------------------

    @Override
    public void onShow() {
        sel = 0;
        top = 0;
        inButtons = false;
        btn = 0;
        message = "";
        permissionNote = !svc.ensureLocation();
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        if (!registered) {
            ctx.registerReceiver(rx, f);
            registered = true;
        }
        rebuild();
        main.removeCallbacks(rescan);
        main.post(rescan);
    }

    @Override
    public void onHide() {
        main.removeCallbacks(rescan);
        if (registered) {
            try {
                ctx.unregisterReceiver(rx);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
            registered = false;
        }
    }

    // ---- data -------------------------------------------------------------------------------------------

    private static int securityOf(String caps) {
        if (caps == null) return OPEN;
        if (caps.contains("EAP") || caps.contains("SAE") || caps.contains("OWE")) return UNSUPPORTED;
        if (caps.contains("WEP")) return WEP;
        if (caps.contains("PSK") || caps.contains("WPA")) return PSK;
        return OPEN;
    }

    private static String securityName(String caps) {
        if (caps == null) return "OPEN";
        if (caps.contains("SAE")) return "WPA3";
        if (caps.contains("EAP")) return "802.1X";
        if (caps.contains("WPA2")) return "WPA2";
        if (caps.contains("WPA")) return "WPA";
        if (caps.contains("WEP")) return "WEP";
        return "OPEN";
    }

    private static String unquote(String s) {
        if (s == null) return "";
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private void rebuild() {
        String keepSsid = sel < nets.size() ? nets.get(sel).ssid : null;
        boolean keepHidden = sel >= nets.size() && sel > 0;   // the "add hidden network" row
        nets.clear();
        try {
            List<ScanResult> found = wm.getScanResults();
            Map<String, Net> best = new HashMap<>();
            if (found != null) {
                for (ScanResult r : found) {
                    if (r.SSID == null || r.SSID.isEmpty()) continue;
                    Net old = best.get(r.SSID);
                    if (old != null && old.level >= r.level) continue;
                    Net n = new Net();
                    n.ssid = r.SSID;
                    n.level = r.level;
                    n.freq = r.frequency;
                    n.security = securityOf(r.capabilities);
                    n.securityName = securityName(r.capabilities);
                    best.put(r.SSID, n);
                }
            }
            nets.addAll(best.values());
            List<WifiConfiguration> saved = wm.getConfiguredNetworks();
            WifiInfo wi = wm.getConnectionInfo();
            String current = wi != null && wi.getNetworkId() != -1 ? unquote(wi.getSSID()) : null;
            for (Net n : nets) {
                if (saved != null) {
                    for (WifiConfiguration cfg : saved) {
                        if (unquote(cfg.SSID).equals(n.ssid)) n.netId = cfg.networkId;
                    }
                }
                n.connected = current != null && current.equals(n.ssid);
            }
            Collections.sort(nets, new Comparator<Net>() {
                @Override public int compare(Net a, Net b) {
                    if (a.connected != b.connected) return a.connected ? -1 : 1;
                    return b.level - a.level;
                }
            });
        } catch (SecurityException e) {
            permissionNote = true;
        }
        sel = keepHidden ? nets.size() : 0;
        if (keepSsid != null) {
            for (int i = 0; i < nets.size(); i++) if (nets.get(i).ssid.equals(keepSsid)) sel = i;
        }
        clampScroll();
        redraw.run();
    }

    /** Shows a status line for a few seconds. */
    private void say(String s) {
        message = s;
        main.removeCallbacks(clearMessage);
        main.postDelayed(clearMessage, 7000);
        redraw.run();
    }

    private final Runnable clearMessage = new Runnable() {
        @Override public void run() {
            message = "";
            redraw.run();
        }
    };

    private void clampScroll() {
        int n = nets.size() + 1;
        if (sel < top) top = sel;
        if (sel >= top + VISIBLE_ROWS) top = sel - VISIBLE_ROWS + 1;
        top = Math.max(0, Math.min(top, Math.max(0, n - VISIBLE_ROWS)));
    }

    private Net current() {
        return sel < nets.size() ? nets.get(sel) : null;
    }

    // ---- actions ----------------------------------------------------------------------------------------

    private List<String> buttons() {
        List<String> b = new ArrayList<>();
        Net n = current();
        if (n != null) {
            b.add(n.connected ? "DISCONNECT" : "CONNECT");
            if (n.netId != -1) b.add("FORGET");
        } else {
            b.add("ADD NETWORK");
        }
        b.add("RESCAN");
        return b;
    }

    private void runButton(String name) {
        Net n = current();
        switch (name) {
            case "CONNECT":
                connect(n);
                break;
            case "ADD NETWORK":
                addHidden();
                break;
            case "DISCONNECT":
                wm.disconnect();
                say("DISCONNECTED");
                break;
            case "FORGET":
                if (wm.removeNetwork(n.netId)) {
                    wm.saveConfiguration();
                    say("FORGOT " + n.ssid);
                } else {
                    say("CANNOT FORGET HERE - USE ADVANCED");
                    svc.launch(new Intent("android.settings.WIFI_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                break;
            default:
                say("SCANNING");
                try {
                    wm.startScan();
                } catch (Exception ignored) {
                    // Throttled.
                }
        }
        rebuild();
    }

    private void connect(final Net n) {
        if (n == null) return;
        if (n.connected) {
            say("ALREADY CONNECTED");
            return;
        }
        if (n.netId != -1) {
            wm.disconnect();
            wm.enableNetwork(n.netId, true);
            wm.reconnect();
            say("CONNECTING TO " + n.ssid);
            return;
        }
        if (n.security == UNSUPPORTED) {
            say(n.securityName + " NEEDS THE STOCK WI-FI SCREEN");
            svc.launch(new Intent("android.settings.WIFI_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (n.security == OPEN) {
            save(n.ssid, OPEN, null, false);
            return;
        }
        final int sec = n.security;
        svc.prompt("PASSWORD FOR " + n.ssid.toUpperCase(Locale.ENGLISH), n.securityName + "  //  PRESS ENTER ON THE KEYBOARD",
                true, new Services.PromptResult() {
                    @Override public void onText(String text) {
                        save(n.ssid, sec, text, false);
                        rebuild();
                    }
                });
    }

    private void addHidden() {
        svc.prompt("NETWORK NAME", "HIDDEN NETWORK  //  PRESS ENTER ON THE KEYBOARD", true, new Services.PromptResult() {
            @Override public void onText(final String ssid) {
                if (ssid.isEmpty()) return;
                svc.prompt("PASSWORD FOR " + ssid.toUpperCase(Locale.ENGLISH), "LEAVE EMPTY FOR AN OPEN NETWORK", true,
                        new Services.PromptResult() {
                            @Override public void onText(String pw) {
                                save(ssid, pw.isEmpty() ? OPEN : PSK, pw, true);
                                rebuild();
                            }
                        });
            }
        });
    }

    private void save(String ssid, int security, String password, boolean hidden) {
        if (security == PSK && (password == null || password.length() < 8)) {
            say("PASSWORD TOO SHORT (8+)");
            return;
        }
        WifiConfiguration cfg = new WifiConfiguration();
        cfg.SSID = "\"" + ssid + "\"";
        cfg.hiddenSSID = hidden;
        if (security == PSK) {
            cfg.preSharedKey = "\"" + password + "\"";
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        } else if (security == WEP) {
            cfg.wepKeys[0] = "\"" + password + "\"";
            cfg.wepTxKeyIndex = 0;
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        } else {
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }
        int id = wm.addNetwork(cfg);
        if (id < 0) {
            say("COULD NOT SAVE THE NETWORK");
            return;
        }
        wm.disconnect();
        wm.enableNetwork(id, true);
        wm.reconnect();
        wm.saveConfiguration();
        say("CONNECTING TO " + ssid);
    }

    // ---- keys -------------------------------------------------------------------------------------------

    @Override
    public boolean key(int code) {
        int count = nets.size() + 1;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inButtons) { if (btn > 0) btn--; } else if (sel > 0) sel--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inButtons) { if (btn < buttons().size() - 1) btn++; } else if (sel < count - 1) sel++;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (inButtons) inButtons = false; else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!inButtons) { inButtons = true; btn = 0; } else return true;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (inButtons) {
                    List<String> b = buttons();
                    runButton(b.get(Math.min(btn, b.size() - 1)));
                } else if (current() == null) {
                    addHidden();
                } else {
                    connect(current());
                    redraw.run();
                }
                return true;
            default:
                return false;
        }
        btn = Math.min(btn, buttons().size() - 1);
        clampScroll();
        redraw.run();
        return true;
    }

    @Override
    public boolean back() {
        if (inButtons) {
            inButtons = false;
            redraw.run();
            return true;
        }
        return false;
    }

    // ---- drawing ----------------------------------------------------------------------------------------

    @Override
    public void drawBase(Canvas c) {
        ui.title(c, "WI-FI");
        ui.txt(c, "NETWORKS IN RANGE", 160, 170, 16, Color.argb(210, 255, 110, 110), ui.medium, Paint.Align.LEFT, 0.16f);
        ui.txt(c, "DETAILS", 1110, 170, 16, Color.argb(210, 255, 110, 110), ui.medium, Paint.Align.LEFT, 0.16f);
        ui.line(c, 160, 186, 1760, 186, Color.argb(110, 232, 64, 64), 1);
        ui.footer(c);
    }

    @Override
    public void drawLive(Canvas c) {
        list(c);
        details(c);
        // Message line.
        if (!message.isEmpty()) {
            ui.txt(c, message, 160, 925, 20, Ui.YELLOW, ui.semi, Paint.Align.LEFT, 0.12f);
        } else if (permissionNote) {
            ui.txt(c, "SCAN BLOCKED  //  LOCATION PERMISSION OR LOCATION SWITCH IS OFF", 160, 925, 18, Ui.RED_TEXT, ui.medium,
                    Paint.Align.LEFT, 0.1f);
        } else if (!wm.isWifiEnabled()) {
            ui.txt(c, "WI-FI IS SWITCHED OFF", 160, 925, 20, Ui.RED_TEXT, ui.semi, Paint.Align.LEFT, 0.12f);
        } else if (nets.isEmpty()) {
            ui.txt(c, "SCANNING FOR NETWORKS...  IF NOTHING SHOWS, LOCATION ACCESS IS OFF", 160, 925, 18, Ui.CYAN_DIM, ui.medium,
                    Paint.Align.LEFT, 0.1f);
        }
    }

    private void list(Canvas c) {
        float x0 = 160, x1 = 1060, y0 = 206, rowH = 82, gap = 8;
        int count = nets.size() + 1;
        for (int v = 0; v < VISIBLE_ROWS; v++) {
            int i = top + v;
            if (i >= count) break;
            float y = y0 + v * (rowH + gap);
            boolean focus = i == sel && !inButtons;
            boolean marked = i == sel;
            Path pa = ui.cut(x0, y, x1, y + rowH, 0, 0, 14, 0);
            ui.fill(c, pa, focus ? Color.argb(235, 8, 52, 64) : Color.argb(205, 17, 10, 14));
            ui.stroke(c, pa, focus ? Ui.CYAN : marked ? Ui.RED : Ui.RED_BORDER, focus ? 3.5f : 1.5f);
            ui.rect(c, x0, y, x0 + 4, y + rowH, focus ? Ui.CYAN : Ui.RED);
            float cy = y + rowH / 2f;
            if (i == nets.size()) {
                ui.txt(c, "+", x0 + 36, cy, 40, Ui.CYAN, ui.light, Paint.Align.CENTER);
                ui.txt(c, "ADD HIDDEN NETWORK", x0 + 96, cy, 24, focus ? Color.WHITE : Ui.CYAN, ui.semi, Paint.Align.LEFT);
                continue;
            }
            Net n = nets.get(i);
            int level = Math.max(0, Math.min(4, WifiManager.calculateSignalLevel(n.level, 5)));
            ui.bars(c, x0 + 22, cy + 14, level, n.connected ? Ui.GREEN : Ui.CYAN);
            String name = n.ssid;
            ui.txt(c, name, x0 + 96, cy, ui.fit(name, 26, ui.semi, 540), focus ? Color.WHITE : Ui.CYAN, ui.semi, Paint.Align.LEFT);
            float tx = x1 - 24;
            if (n.security != OPEN) {
                ui.lock(c, tx - 10, cy + 2, Ui.CYAN_DIM);
                tx -= 44;
            }
            if (n.connected) {
                tag(c, tx, cy, "CONNECTED", Ui.GREEN);
            } else if (n.netId != -1) {
                tag(c, tx, cy, "SAVED", Ui.YELLOW);
            }
        }
        // Scroll marks.
        if (top > 0) ui.txt(c, "▲", 1040, y0 - 12, 14, Ui.CYAN_DIM, ui.medium, Paint.Align.RIGHT);
        if (top + VISIBLE_ROWS < count) ui.txt(c, "▼", 1040, y0 + VISIBLE_ROWS * (rowH + gap) + 2, 14, Ui.CYAN_DIM, ui.medium, Paint.Align.RIGHT);
    }

    private void tag(Canvas c, float rightX, float cy, String s, int color) {
        float w = ui.width(s, 16, ui.semi, 0.14f) + 22;
        Path pa = ui.cut(rightX - w, cy - 15, rightX, cy + 15, 0, 0, 7, 0);
        ui.stroke(c, pa, color, 1.8f);
        ui.txt(c, s, rightX - w / 2, cy, 16, color, ui.semi, Paint.Align.CENTER, 0.14f);
    }

    private void details(Canvas c) {
        float x0 = 1110, x1 = 1760, y0 = 206, y1 = 880;
        Path box = ui.cut(x0, y0, x1, y1, 0, 0, 16, 0);
        ui.fill(c, box, Color.argb(215, 24, 9, 14));
        ui.stroke(c, box, Ui.RED_BORDER, 1.5f);
        Net n = current();
        if (n == null) {
            ui.txt(c, "HIDDEN NETWORK", x0 + 28, y0 + 44, 28, Ui.CYAN, ui.semi, Paint.Align.LEFT, 0.06f);
            ui.txt(c, "TYPE THE NAME AND PASSWORD", x0 + 28, y0 + 82, 14, Color.argb(190, 255, 110, 110), ui.medium, Paint.Align.LEFT, 0.12f);
        } else {
            ui.txt(c, n.ssid, x0 + 28, y0 + 44, ui.fit(n.ssid, 32, ui.semi, 590), Color.WHITE, ui.semi, Paint.Align.LEFT, 0.04f);
            String state = n.connected ? "CONNECTED" : n.netId != -1 ? "SAVED" : "NOT SAVED";
            ui.txt(c, state, x0 + 28, y0 + 82, 16, n.connected ? Ui.GREEN : Ui.YELLOW, ui.semi, Paint.Align.LEFT, 0.16f);
            int pct = Math.max(0, Math.min(100, 2 * (n.level + 100)));
            row(c, y0 + 130, "SECURITY", n.securityName);
            row(c, y0 + 176, "SIGNAL", pct + "%   " + n.level + " dBm");
            row(c, y0 + 222, "BAND", n.freq > 4900 ? "5 GHZ" : "2.4 GHZ");
            if (n.connected) {
                WifiInfo wi = wm.getConnectionInfo();
                row(c, y0 + 268, "LINK SPEED", wi.getLinkSpeed() + " MBIT/S");
                row(c, y0 + 314, "IP ADDRESS", ip(wi.getIpAddress()));
            }
        }
        // Buttons.
        List<String> b = buttons();
        for (int i = 0; i < b.size(); i++) {
            float by = y1 - 24 - (b.size() - i) * 68;
            boolean focus = inButtons && i == btn;
            Path pa = ui.cut(x0 + 24, by, x1 - 24, by + 58, 0, 0, 10, 0);
            boolean danger = b.get(i).equals("FORGET") || b.get(i).equals("DISCONNECT");
            ui.fill(c, pa, focus ? Color.argb(235, 8, 52, 64) : danger ? Color.argb(170, 96, 24, 30) : Color.argb(205, 17, 10, 14));
            ui.stroke(c, pa, focus ? Ui.CYAN : danger ? Ui.RED : Ui.RED_BORDER, focus ? 3.5f : 1.5f);
            ui.txt(c, b.get(i), (x0 + x1) / 2, by + 29, 24, focus ? Color.WHITE : Ui.CYAN, ui.semi, Paint.Align.CENTER, 0.14f);
        }
    }

    private void row(Canvas c, float cy, String label, String value) {
        ui.txt(c, label, 1138, cy, 17, Color.argb(200, 255, 110, 110), ui.medium, Paint.Align.LEFT, 0.16f);
        ui.txt(c, value, 1732, cy, 24, Ui.CYAN, ui.medium, Paint.Align.RIGHT);
        ui.line(c, 1138, cy + 22, 1732, cy + 22, Color.argb(60, 232, 64, 64), 1);
    }

    private static String ip(int a) {
        return (a & 0xff) + "." + ((a >> 8) & 0xff) + "." + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
    }
}
