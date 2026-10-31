package com.cyberhome;

import android.content.Intent;

/** What the pages need from the activity. */
interface Services {
    interface PromptResult {
        void onText(String text);
    }

    void launch(Intent intent);

    /** Asks for a line of text with the on-screen keyboard; the visible-password style shows what is typed. */
    void prompt(String title, String hint, boolean visible, PromptResult result);

    /** True when Wi-Fi scan results may be read; asks for the permission when they may not. */
    boolean ensureLocation();
}
