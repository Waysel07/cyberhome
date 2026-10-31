package com.cyberhome;

import android.content.Intent;
import android.graphics.Bitmap;

/** One launchable app: its name, its start intent and its icon as a small bitmap. */
final class AppInfo {
    final String pkg;
    final String label;
    final Intent intent;
    final Bitmap icon;

    AppInfo(String pkg, String label, Intent intent, Bitmap icon) {
        this.pkg = pkg;
        this.label = label;
        this.intent = intent;
        this.icon = icon;
    }
}
