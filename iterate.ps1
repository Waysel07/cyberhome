# Rebuild, install, restart Home on the projector, press keys, render the screen to snaps\<name>.png.
# Usage: .\iterate.ps1 -Name v2 [-Keys "RIGHT,RIGHT,DOWN"] [-NoBuild]
param([string]$Name = 'snap', [string]$Keys = '', [switch]$NoBuild, [string]$Tv = $env:TV_ADB)  # e.g. 192.168.1.50:5555
$ErrorActionPreference = 'Stop'
if (-not $Tv) { throw 'pass -Tv <ip>:5555 or set TV_ADB' }
$adb = 'adb'
$p = $PSScriptRoot
if (-not $NoBuild) {
    python "$p\tools\make_bg.py" $p | Out-Null
    Remove-Item "$p\cyberhome.apk" -ErrorAction SilentlyContinue
    $ErrorActionPreference = 'Continue'
    powershell -ExecutionPolicy Bypass -File "$p\build.ps1" 2>&1 | Select-Object -Last 4
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path "$p\cyberhome.apk")) { throw 'build failed' }
    & $adb -s $Tv push "$p\cyberhome.apk" /data/local/tmp/ch.apk | Out-Null
    & $adb -s $Tv shell 'pm install -r /data/local/tmp/ch.apk; rm -f /data/local/tmp/ch.apk'
}
$extra = ''
if ($Keys) { $extra = "--es keys $Keys" }
& $adb -s $Tv shell "logcat -c; am force-stop com.cyberhome; am start -n com.cyberhome/.MainActivity >/dev/null; sleep 6; am broadcast -a com.cyberhome.SNAP --es name $Name.png $extra >/dev/null; sleep 3; logcat -d | grep -E 'AndroidRuntime|FATAL' | head -5"
& $adb -s $Tv pull "/sdcard/Android/data/com.cyberhome/files/$Name.png" "$p\snaps\$Name.png" | Out-Null
"snaps\$Name.png"
