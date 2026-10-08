#!/usr/bin/env bash
# 最小 PoC APK 构建脚本（不依赖 Gradle / AGP）
# 只用 SDK 自带的 aapt2 / d8 / zipalign / apksigner
set -euo pipefail

SDK="/c/Users/Doro/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
PY="/c/Users/Doro/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
APK="$HERE/poc-layoutprovider.apk"
KEYSTORE="$HERE/debug.keystore"

# Shizuku SDK（从 AAR 里抽出的 classes.jar；这些 AAR 无资源）
# 注意：给 Windows 版 javac 传 classpath 列表时，**必须用 `:` 分隔** ——
# Git Bash(MSYS) 会把 `:` 分隔的路径列表逐项转成 Windows 路径；用 `;` 会被当成单个路径。
SZK="$HERE/shizuku"
SZK_JARS="$SZK/api-classes.jar:$SZK/provider-classes.jar:$SZK/aidl-classes.jar:$SZK/shared-classes.jar:$SZK/annotation-1.3.0.jar"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] javac -> classes"
javac -source 11 -target 11 -encoding UTF-8 \
  -classpath "$PLATFORM:$SZK_JARS" -d "$OUT/classes" \
  $(find "$HERE/src" -name '*.java')

echo "[2/6] aapt2 link -> apk 骨架（含 manifest + assets）"
"$BT/aapt2.exe" link -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  -A "$HERE/assets" \
  --min-sdk-version 26 --target-sdk-version 35

echo "[3/6] d8 -> classes.dex（含 Shizuku SDK）"
"$BT/d8.bat" --lib "$PLATFORM" --min-api 26 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') \
  "$SZK/api-classes.jar" "$SZK/provider-classes.jar" \
  "$SZK/aidl-classes.jar" "$SZK/shared-classes.jar"

echo "[4/6] 把 classes.dex 塞进 apk"
OUT="$OUT" "$PY" - <<'PYEOF'
import os, zipfile
out = os.environ['OUT']
with zipfile.ZipFile(out + '/app-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(out + '/dex/classes.dex', 'classes.dex')
print('   classes.dex added')
PYEOF

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "[6/6] apksigner（debug keystore）"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/app-aligned.apk"

echo "--- 产物 ---"
ls -la "$APK"
"$BT/aapt2.exe" dump badging "$APK" 2>/dev/null | head -5 || true
