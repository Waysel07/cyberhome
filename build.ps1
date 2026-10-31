# Builds cyberhome.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner (debug key).
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\34.0.0"; $jar = "$sdk\platforms\android-34\android.jar"
# keytool and jar come from the JDK on PATH; javac must be Java 8 (d8 34 crashes on newer class files).
$javac = if ($env:JAVAC8) { $env:JAVAC8 } else { 'javac' }
$out = "$here\build"; Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\res","$out\classes","$out\gen" | Out-Null
& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res"; if ($LASTEXITCODE) { throw 'aapt2 compile' }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" -A "$here\assets" --java "$out\gen" @((Get-ChildItem "$out\res" -Filter *.flat).FullName) --min-sdk-version 26 --target-sdk-version 28
if ($LASTEXITCODE) { throw 'aapt2 link' }
$src = (Get-ChildItem "$here\src","$out\gen" -Filter *.java -Recurse).FullName
& $javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $jar -d "$out\classes" $src; if ($LASTEXITCODE) { throw 'javac' }
$cls = (Get-ChildItem "$out\classes" -Filter *.class -Recurse).FullName
& "$bt\d8.bat" --min-api 26 --lib $jar --output $out $cls; if ($LASTEXITCODE) { throw 'd8' }
Push-Location $out
Copy-Item base.apk unaligned.apk
# Add classes.dex to the apk with the jar tool (keeps aapt2's resources table stored uncompressed).
jar uf unaligned.apk classes.dex; if ($LASTEXITCODE) { throw 'jar' }
Pop-Location
& "$bt\zipalign.exe" -f -p 4 "$out\unaligned.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'zipalign' }
$ks = "$here\debug.keystore"
if (-not (Test-Path $ks)) {
    $ErrorActionPreference = 'Continue'
    keytool -genkeypair -keystore $ks -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=cyberhome' 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path $ks)) { throw 'keytool' }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$here\cyberhome.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'apksigner' }
& "$bt\apksigner.bat" verify "$here\cyberhome.apk"; "built: $here\cyberhome.apk ($((Get-Item "$here\cyberhome.apk").Length) bytes)"




