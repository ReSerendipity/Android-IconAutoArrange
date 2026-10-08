#!/usr/bin/env bash
# 最小 PoC APK 构建脚本（不依赖 Gradle / AGP）
# 只用 SDK 自带的 aapt2 / d8 / zipalign / apksigner
#
# 可移植性：
#   SDK 路径  按 ANDROID_SDK_ROOT → ANDROID_HOME → 常见默认位置 依次探测
#   版本      优先用与目标 API 匹配的 build-tools / platform，缺失则退到最新可用版本
#   解释器    python3 → python → py（可用 PYTHON=... 覆盖）
#
# 可用环境变量覆盖：
#   ANDROID_SDK_ROOT / ANDROID_HOME   SDK 根目录
#   TARGET_API                        目标 API（默认 35），同时决定首选 platform
#   BT_VERSION                        首选 build-tools 版本（默认 35.0.0）
#   PYTHON                            指定 python 解释器
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
APK="$HERE/poc-layoutprovider.apk"
KEYSTORE="$HERE/debug.keystore"

TARGET_API="${TARGET_API:-35}"       # 与 AndroidManifest 的 targetSdkVersion 一致
BT_VERSION="${BT_VERSION:-35.0.0}"   # 首选 build-tools 版本
MIN_API=26                           # 与 AndroidManifest 的 minSdkVersion 一致

# ------------------------------------------------------------------ 定位 SDK
find_sdk() {
  local c
  for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
           "$HOME/AppData/Local/Android/Sdk" \
           "$HOME/Library/Android/sdk" \
           "$HOME/Android/Sdk" \
           "/usr/lib/android-sdk"; do
    if [ -n "$c" ] && [ -d "$c" ]; then echo "$c"; return 0; fi
  done
  return 1
}
if ! SDK="$(find_sdk)"; then
  echo "错误：找不到 Android SDK。" >&2
  echo "      请设置 ANDROID_SDK_ROOT（或 ANDROID_HOME）指向 SDK 根目录。" >&2
  exit 1
fi

# --------------------------------------------- 选 build-tools / platform 目录
# pick_dir <父目录> <首选版本> <目录名前缀>
pick_dir() {
  local parent="$1" want="$2" prefix="$3" best
  if [ -n "$want" ] && [ -d "$parent/$prefix$want" ]; then
    echo "$parent/$prefix$want"; return 0
  fi
  best="$(ls -1 "$parent" 2>/dev/null | grep -E "^${prefix}[0-9]" | sort -V | tail -1 || true)"
  if [ -n "$best" ]; then echo "$parent/$best"; return 0; fi
  return 1
}
if ! BT="$(pick_dir "$SDK/build-tools" "$BT_VERSION" "")"; then
  echo "错误：$SDK/build-tools 下没有可用的 build-tools。" >&2; exit 1
fi
if ! PLATFORM_DIR="$(pick_dir "$SDK/platforms" "$TARGET_API" "android-")"; then
  echo "错误：$SDK/platforms 下没有可用的 platform。" >&2; exit 1
fi
PLATFORM="$PLATFORM_DIR/android.jar"
PLATFORM_API="${PLATFORM_DIR##*/android-}"

# ------------------------------------------------------- 定位 python 解释器
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
fi
if [ -z "$PY" ]; then
  echo "错误：找不到 python 解释器（可用 PYTHON=... 指定）。" >&2; exit 1
fi

# build-tools 在各平台后缀不同：Linux/macOS 无后缀，Windows 为 .exe / .bat
tool() {
  local n="$1" cand
  for cand in "$BT/$n" "$BT/$n.exe" "$BT/$n.bat"; do
    if [ -f "$cand" ]; then echo "$cand"; return 0; fi
  done
  echo "错误：build-tools（$BT）里找不到 $n" >&2
  return 1
}
AAPT2="$(tool aapt2)"
D8="$(tool d8)"
ZIPALIGN="$(tool zipalign)"
APKSIGNER="$(tool apksigner)"

echo "SDK         : $SDK"
echo "build-tools : $(basename "$BT")"
echo "platform    : $(basename "$PLATFORM_DIR")"
echo "python      : $PY"
if [ "$PLATFORM_API" != "$TARGET_API" ]; then
  echo "注意：未找到 android-$TARGET_API，已改用 android-$PLATFORM_API（targetSdkVersion 仍为 $TARGET_API）"
fi

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
"$AAPT2" link -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  -A "$HERE/assets" \
  --min-sdk-version "$MIN_API" --target-sdk-version "$TARGET_API"

echo "[3/6] d8 -> classes.dex（含 Shizuku SDK）"
"$D8" --lib "$PLATFORM" --min-api "$MIN_API" \
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
"$ZIPALIGN" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "[6/6] apksigner（debug keystore）"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/app-aligned.apk"

echo "--- 产物 ---"
ls -la "$APK"
"$AAPT2" dump badging "$APK" 2>/dev/null | head -5 || true
