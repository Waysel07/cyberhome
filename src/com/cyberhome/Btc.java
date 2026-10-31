package com.cyberhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Bitcoin candles from Coinbase's public API (no key): the last 24 hours in 15 minute steps, fetched every
 * five minutes while Home is on screen. Keeps the last good answer when the network is gone.
 */
final class Btc {
    static final String PAIR = "BTC-EUR";
    private static final long REFRESH_MS = 5 * 60_000L;

    interface Listener { void onBtc(); }

    double[] closes = new double[0];
    double high, low;
    long updated;
    boolean failed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean running;
    private int generation;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            fetch();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    Btc(Listener l) {
        listener = l;
    }

    void start() {
        if (running) return;
        running = true;
        main.post(tick);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    double last() {
        return closes.length == 0 ? 0 : closes[closes.length - 1];
    }

    double changePct() {
        return closes.length < 2 ? 0 : (last() - closes[0]) / closes[0] * 100.0;
    }

    private void fetch() {
        final int gen = ++generation;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    long end = System.currentTimeMillis() / 1000;
                    String url = "https://api.exchange.coinbase.com/products/" + PAIR + "/candles?granularity=900"
                            + "&start=" + iso(end - 86_400L) + "&end=" + iso(end);
                    JSONArray rows = new JSONArray(get(url));
                    final int n = rows.length();
                    final double[] c = new double[n];
                    double hi = -Double.MAX_VALUE, lo = Double.MAX_VALUE;
                    // Rows are [time, low, high, open, close, volume], newest first.
                    for (int i = 0; i < n; i++) {
                        JSONArray r = rows.getJSONArray(n - 1 - i);
                        c[i] = r.getDouble(4);
                        hi = Math.max(hi, r.getDouble(2));
                        lo = Math.min(lo, r.getDouble(1));
                    }
                    final double fhi = hi, flo = lo;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;
                            if (n >= 2) {
                                closes = c;
                                high = fhi;
                                low = flo;
                                updated = System.currentTimeMillis();
                                failed = false;
                            } else {
                                failed = closes.length < 2;
                            }
                            listener.onBtc();
                        }
                    });
                } catch (Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;
                            failed = closes.length < 2;
                            listener.onBtc();
                        }
                    });
                }
            }
        }).start();
    }

    private static String iso(long epochSeconds) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochSeconds * 1000));
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(15_000);
        con.setRequestProperty("User-Agent", "cyberhome/1.0");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }
}
