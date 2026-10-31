package com.cyberhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CyberView.Host, Btc.Listener {
    static final String SNAP = "com.cyberhome.SNAP";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppInfo> all = new ArrayList<>();
    private final List<AppInfo> dock = new ArrayList<>();
    private final Sys sys = new Sys();
    private Btc btc;
    private CyberView view;
    private String lastSig = "";
    private FrameLayout promptLayer;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sys.refresh(MainActivity.this);
            // Redraw the cached layer only when something it shows has changed.
            String sig = (System.currentTimeMillis() / 60_000L) + "/" + sys.ramPct + "/" + sys.storagePct + "/"
                    + sys.signalPct + "/" + sys.network;
            if (!sig.equals(lastSig)) {
                lastSig = sig;
                view.dataChanged();
            }
            main.postDelayed(this, 20_000);
        }
    };

    private final BroadcastReceiver snapReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String keys = i.getStringExtra("keys");
            if (keys != null) {
                for (String k : keys.split(",")) {
                    if (k.trim().equalsIgnoreCase("BACK")) { view.back(); continue; }
                    Integer code = keyCode(k.trim());
                    if (code != null) view.key(code);
                }
            }
            try {
                long t0 = System.nanoTime();
                Bitmap b;
                if (promptLayer != null) {
                    // The text prompt is real views over Home: draw the whole window.
                    View decor = getWindow().getDecorView();
                    b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                    decor.draw(new Canvas(b));
                } else {
                    b = view.snap();
                }
                android.util.Log.d("CyberHome", "software render ms=" + (System.nanoTime() - t0) / 1_000_000);
                File out = new File(getExternalFilesDir(null), i.hasExtra("name") ? i.getStringExtra("name") : "snap.png");
                try (FileOutputStream f = new FileOutputStream(out)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, f);
                }
                b.recycle();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "snap failed: " + e, Toast.LENGTH_LONG).show();
            }
        }
    };

    private static Integer keyCode(String k) {
        switch (k.toUpperCase(Locale.ROOT)) {
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "OK": return KeyEvent.KEYCODE_DPAD_CENTER;
            default: return null;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadApps();
        btc = new Btc(this);
        sys.refresh(this);
        view = new CyberView(this, this, btc, sys, dock, all);
        setContentView(view);
        hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(snapReceiver, new IntentFilter(SNAP));
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        btc.start();
        main.post(tick);
        view.resumeScreens();
    }

    @Override
    protected void onPause() {
        view.pauseScreens();
        btc.stop();
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(snapReceiver);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // Home pressed while Home is showing: back to the first tab.
        if (Intent.ACTION_MAIN.equals(i.getAction())) view.resetFocus();
    }

    @Override
    public void onBackPressed() {
        if (promptLayer != null) {
            closePrompt();
            return;
        }
        // Closes a page, the Inputs list or the grid; Home itself has nowhere to go back to.
        view.back();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // While the text prompt is up, its field and the keyboard get the keys.
        if (promptLayer != null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && view.key(e.getKeyCode())) return true;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_LEFT
                    || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBtc() {
        view.dataChanged();
    }

    // ---- apps ------------------------------------------------------------------------------------------

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.startsWith("com.gathin.tvhome")
                    || pkg.equals("com.siviton.substanbolauncher")) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            all.add(new AppInfo(pkg, String.valueOf(ri.loadLabel(pm)), launch, toBitmap(ri.loadIcon(pm))));
        }
        Collections.sort(all, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo a, AppInfo b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("dock.txt")))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                for (AppInfo a : all) {
                    if (a.pkg.equals(line)) { dock.add(a); break; }
                }
            }
        } catch (Exception ignored) {
            // No dock list: the first apps stand in.
        }
        if (dock.isEmpty()) {
            for (int i = 0; i < Math.min(5, all.size()); i++) dock.add(all.get(i));
        }
    }

    private static Bitmap toBitmap(Drawable d) {
        Bitmap b = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, 72, 72);
        d.draw(c);
        return b;
    }

    // ---- actions ---------------------------------------------------------------------------------------

    @Override
    public void open(AppInfo app) {
        try {
            startActivity(app.intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public List<String[]> inputs() {
        List<String[]> out = new ArrayList<>();
        int hdmi = 0;
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput()) continue;
                CharSequence custom = info.loadCustomLabel(this);
                String label = String.valueOf(custom != null ? custom : info.loadLabel(this));
                if (info.getType() == TvInputInfo.TYPE_HDMI && !label.matches(".*\\d.*")) {
                    label = "HDMI " + (++hdmi);
                }
                out.add(new String[]{label.trim().toUpperCase(Locale.ENGLISH), info.getId()});
            }
        } catch (Exception ignored) {
            // No TV input framework: the Inputs button falls back to a toast.
        }
        return out;
    }

    @Override
    public void input(String id) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot switch input", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Services for the Settings and Wi-Fi pages --------------------------------------------------------

    @Override
    public void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open that screen", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean ensureLocation() {
        boolean ok = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!ok) requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
        return ok;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        view.resumeScreens();
    }

    /** A line of text over Home: a styled field, with the on-screen keyboard raised for it. */
    @Override
    public void prompt(String title, String hint, boolean visible, final Services.PromptResult result) {
        closePrompt();
        float s = getResources().getDisplayMetrics().widthPixels / 1920f;
        Typeface semi = Typeface.createFromAsset(getAssets(), "Rajdhani-SemiBold.ttf");
        Typeface medium = Typeface.createFromAsset(getAssets(), "Rajdhani-Medium.ttf");

        final FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0xD0000000);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(36 * s);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xF01C090E);
        pbg.setStroke(Math.max(2, Math.round(3 * s)), 0xFFD43838);
        panel.setBackground(pbg);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(semi);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 34 * s);
        t.setTextColor(0xFF36E2EE);
        t.setLetterSpacing(0.08f);
        panel.addView(t);

        final EditText e = new EditText(this);
        e.setTypeface(medium);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 44 * s);
        e.setTextColor(0xFFFFFFFF);
        e.setSingleLine(true);
        e.setInputType(visible ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_CLASS_TEXT);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(0xFF0C0A10);
        ebg.setStroke(Math.max(2, Math.round(3 * s)), 0xFF36E2EE);
        e.setBackground(ebg);
        e.setPadding(Math.round(20 * s), Math.round(14 * s), Math.round(20 * s), Math.round(14 * s));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Math.round(24 * s);
        panel.addView(e, elp);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTypeface(medium);
        h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 20 * s);
        h.setTextColor(0xFFFF6E6E);
        h.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Math.round(18 * s);
        panel.addView(h, hlp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Math.round(1100 * s), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.topMargin = Math.round(190 * s);
        layer.addView(panel, plp);

        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                String text = e.getText().toString();
                closePrompt();
                result.onText(text);
                return true;
            }
        });
        promptLayer = layer;
        addContentView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        e.requestFocus();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (promptLayer != null) imm.showSoftInput(e, 0);
            }
        }, 200);
    }

    private void closePrompt() {
        if (promptLayer == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(promptLayer.getWindowToken(), 0);
        ((ViewGroup) promptLayer.getParent()).removeView(promptLayer);
        promptLayer = null;
    }

    @Override
    public void action(int id) {
        try {
            switch (id) {
                case CyberView.ACT_SETTINGS:
                    startActivity(new Intent("android.settings.tv.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case CyberView.ACT_WIFI:
                    startActivity(new Intent("android.settings.WIFI_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                default:
                    Toast.makeText(this, "Coming next", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open that screen", Toast.LENGTH_SHORT).show();
        }
    }
}
