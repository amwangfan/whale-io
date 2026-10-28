#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:?请先设置 ANDROID_HOME（Android SDK 路径）}
export JAVA_HOME=${JAVA_HOME:?请先设置 JAVA_HOME（JDK 17）}
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/36.0.0:$PATH"
BT=$ANDROID_HOME/build-tools/36.0.0
AJ=$ANDROID_HOME/platforms/android-36/android.jar
PROJ=$(cd "$(dirname "$0")/.." && pwd)
OUT=$PROJ/build
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
$BT/aapt2 compile --dir "$PROJ/res" -o "$OUT/compiled.zip"
$BT/aapt2 link -o "$OUT/base.apk" -I "$AJ" --manifest "$PROJ/AndroidManifest.xml" --java "$OUT/gen" --auto-add-overlay "$OUT/compiled.zip"
find "$PROJ/src" "$OUT/gen" -name '*.java' > "$OUT/sources.list"
javac -source 1.8 -target 1.8 -bootclasspath "$AJ" -classpath "$AJ" -d "$OUT/classes" @"$OUT/sources.list"
$BT/d8 --min-api 26 --output "$OUT/dex" --lib "$AJ" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned-unaligned.apk"
(cd "$OUT/dex" && jar uf "$OUT/unsigned-unaligned.apk" classes.dex)
$BT/zipalign -f 4 "$OUT/unsigned-unaligned.apk" "$OUT/unsigned.apk"
[ -f "$PROJ/debug.keystore" ] || keytool -genkeypair -keystore "$PROJ/debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NovaFakeCall,O=Nova,C=CN"
$BT/apksigner sign --ks "$PROJ/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$OUT/nova-fakecall.apk" "$OUT/unsigned.apk"
ls -la "$OUT/nova-fakecall.apk"
