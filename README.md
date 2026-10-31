# CyberHome

A cyberpunk-style home screen (launcher) for Android TV boxes and projectors, inspired by the Cyberpunk 2077 pause menu: red/cyan HUD, Rajdhani font, everything drawn on one canvas and driven by the D-pad remote.

## Features

- Home screen with a live BTC/EUR 24h chart and market log (Coinbase public API, no key)
- RAM, storage and network gauges, clock and date
- App dock (configured in `assets/dock.txt`, one package name per line) and an all-apps grid
- Built-in Settings and Wi-Fi pages (scan, connect via text prompt)
- Shortcuts to inputs (HDMI/AV) and vendor projector controls (focus, keystone)

`ref/` holds the Cyberpunk 2077 menu screenshots the design was matched to.

Targets Android 9 (SDK 28), minimum SDK 26, 1920x1080.

## Building

No Gradle. `build.ps1` runs aapt2 → javac → d8 → zipalign → apksigner directly.

Requirements:
- Android SDK with `build-tools/34.0.0` and `platforms/android-34` (`ANDROID_HOME`, defaults to `%LOCALAPPDATA%\Android\Sdk`)
- A JDK on `PATH` (for `keytool` and `jar`)
- A Java 8 `javac` (set `JAVAC8` to its path); d8 34 crashes on class files from newer compilers
- Python + Pillow to regenerate the background and icons (`tools/make_bg.py`)

```powershell
python tools\make_bg.py .
.\build.ps1
```

A debug keystore is generated on first build.

## Installing

```powershell
adb install -r cyberhome.apk
adb shell cmd package set-home-activity com.cyberhome/.MainActivity
```

Wi-Fi scanning needs location enabled and location permission granted to the app on some firmwares.

`iterate.ps1 -Tv <ip>:5555 -Name test -Keys "RIGHT,DOWN"` rebuilds, installs over network ADB, presses keys and pulls a render of the screen to `snaps/`.
