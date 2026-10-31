package com.cyberhome;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.StatFs;

/** Reads the numbers behind the three gauges and the network label. Cheap; called when Home is drawn. */
final class Sys {
    int ramPct, storagePct, signalPct;
    String network = "OFFLINE";
    long ramTotalMb, storageTotalGb;

    void refresh(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        ramTotalMb = mi.totalMem / (1024 * 1024);
        ramPct = mi.totalMem == 0 ? 0 : (int) Math.round(100.0 * (mi.totalMem - mi.availMem) / mi.totalMem);

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
        storageTotalGb = total / (1024L * 1024 * 1024);
        storagePct = total == 0 ? 0 : (int) Math.round(100.0 * (total - free) / total);

        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        signalPct = 0;
        if (ni == null || !ni.isConnected()) {
            network = "OFFLINE";
        } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            network = ssid == null || ssid.contains("unknown") ? "WI-FI" : ssid.replace("\"", "");
            if (wi != null) signalPct = Math.max(0, Math.min(100, 2 * (wi.getRssi() + 100)));
        } else {
            network = ni.getTypeName().toUpperCase();
            signalPct = 100;
        }
    }
}
