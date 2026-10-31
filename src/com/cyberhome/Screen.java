package com.cyberhome;

import android.graphics.Canvas;

/** A full page drawn over the Home background: Settings, Wi-Fi. */
interface Screen {
    /** Navigation the pages ask of the Home view. */
    interface Nav {
        void openWifi();
        void close();
    }

    /** Parts that rarely change; drawn into the cached layer. */
    void drawBase(Canvas c);

    /** Parts that depend on the selection or on live data; drawn on every frame. */
    void drawLive(Canvas c);

    /** A remote key; returns true when used. */
    boolean key(int code);

    /** Back: returns true when the page stepped back inside itself, false when it should be closed. */
    boolean back();

    void onShow();

    void onHide();
}
