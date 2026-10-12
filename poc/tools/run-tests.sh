#!/usr/bin/env bash
# 单元测试运行器（零外部依赖、无真机）：复用 build.sh 的 SDK 探测逻辑，
# 编译 poc/src + poc/test 后运行 PlannerTest（覆盖 esc()/render() 纯函数，DEBT.md D-01）。
# 用法：bash poc/tools/run-tests.sh
# 说明：与 build.sh 的 pick_dir 不同，这里保留"退到最新可用 platform"的回退——
#       测试只要求能编译，platform 与 TARGET_API 不完全一致不影响 esc()/render() 语义。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"   # poc/tools
POC="$(cd "$HERE/.." && pwd)"           # poc

TARGET_API="${TARGET_API:-35}"

# ---------------------------------------------------- SDK 探测（与 poc/build.sh 一致）
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
pick_dir() {
  local parent="$1" want="$2" prefix="$3" best
  if [ -n "$want" ] && [ -d "$parent/$prefix$want" ]; then
    echo "$parent/$prefix$want"; return 0
  fi
  best="$(find "$parent" -maxdepth 1 -mindepth 1 -type d -name "${prefix}[0-9]*" -exec basename {} \; | sort -V | tail -1 || true)"
  if [ -n "$best" ]; then echo "$parent/$best"; return 0; fi
  return 1
}
if ! SDK="$(find_sdk)"; then
  echo "错误：找不到 Android SDK。请设置 ANDROID_SDK_ROOT（或 ANDROID_HOME）。" >&2
  exit 1
fi
if ! PLATFORM_DIR="$(pick_dir "$SDK/platforms" "$TARGET_API" "android-")"; then
  echo "错误：$SDK/platforms 下没有可用的 platform。" >&2
  exit 1
fi
PLATFORM="$PLATFORM_DIR/android.jar"
PLATFORM_API="${PLATFORM_DIR##*/android-}"
if [ "$PLATFORM_API" != "$TARGET_API" ]; then
  echo "注意：未找到 android-$TARGET_API，已改用 android-$PLATFORM_API（仅编译，不影响测试语义）" >&2
fi

# Shizuku 依赖：MainActivity/LayoutProvider 编译需要（仅 classpath，测试本体不触达）
SZK="$POC/shizuku"
SZK_JARS="$SZK/api-classes.jar:$SZK/provider-classes.jar:$SZK/aidl-classes.jar:$SZK/shared-classes.jar:$SZK/annotation-1.3.0.jar"

OUT="$POC/out-test"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "[1/3] javac（src + test）"
mapfile -t SRC_FILES < <(find "$POC/src" "$POC/test" -name '*.java')
if [ "${#SRC_FILES[@]}" -eq 0 ]; then
  echo "错误：src/test 下没找到任何 .java" >&2
  exit 1
fi
javac -source 11 -target 11 -encoding UTF-8 \
  -classpath "$PLATFORM:$SZK_JARS" -d "$OUT/classes" \
  "${SRC_FILES[@]}"

echo "[2/3] 运行 PlannerTest"
java -cp "$OUT/classes" com.example.layoutprovider.PlannerTest

echo "[3/3] 清理 $OUT"
rm -rf "$OUT"
echo "测试通过"
