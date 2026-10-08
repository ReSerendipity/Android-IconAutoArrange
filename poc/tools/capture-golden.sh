#!/usr/bin/env bash
# 抓取 provider 当前生成的布局 XML 作为回归基线。
#
# 用法：bash capture-golden.sh <输出目录>
#
# 两个模式都**免 root**、结果确定：
#   full  —— 用 `--extra forceFull:b:true` 让 provider 忽略合并基线（**不要**去挪 app 私有目录里的
#            基线文件：shell 身份进不去 /data/data/<pkg>/，早期版本靠 mv 的做法只在 adbd 恰好是
#            root 时才有效，是个隐蔽缺陷）
#   merge —— 默认走合并基线
#
# 末尾会**断言**实际拿到的模式与预期一致，不一致就非零退出 —— 避免"以为抓到了全量、其实是合并"。
#
# 可用环境变量覆盖：
#   ANDROID_SDK_ROOT / ANDROID_HOME   SDK 根目录（用于定位 platform-tools/adb）
#   ADB                               直接指定 adb 可执行文件
#   DEV                               设备选择，如 DEV="-s emulator-5554"；留空=自动选择唯一设备
#   PYTHON                            指定 python 解释器
set -euo pipefail

AUTH="com.example.layoutprovider"
OUT="${1:?用法: capture-golden.sh <输出目录>}"
DEV="${DEV:-}"          # 留空 = 自动选择唯一连接的设备

# ------------------------------------------------------------------ 定位 adb
find_adb() {
  local c
  for c in "${ADB:-}" \
           "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
           "${ANDROID_HOME:-}/platform-tools/adb" \
           "$HOME/AppData/Local/Android/Sdk/platform-tools/adb" \
           "$HOME/Library/Android/sdk/platform-tools/adb" \
           "$HOME/Android/Sdk/platform-tools/adb" \
           "/usr/lib/android-sdk/platform-tools/adb"; do
    if [ -n "$c" ]; then
      if [ -x "$c" ]; then echo "$c"; return 0; fi
      if [ -x "$c.exe" ]; then echo "$c.exe"; return 0; fi
    fi
  done
  if command -v adb >/dev/null 2>&1; then command -v adb; return 0; fi
  return 1
}
if ! ADB_BIN="$(find_adb)"; then
  echo "错误：找不到 adb。" >&2
  echo "      请设置 ANDROID_SDK_ROOT（或 ANDROID_HOME），或用 ADB=<路径> 指定。" >&2
  exit 1
fi

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

# 预检：把 adb 那句难懂的 "more than one device" 换成明确提示
if [ -n "$DEV" ]; then
  if ! "$ADB_BIN" $DEV get-state >/dev/null 2>&1; then
    echo "错误：DEV=\"$DEV\" 指定的设备不可用。" >&2
    "$ADB_BIN" devices >&2 || true
    exit 1
  fi
else
  DEV_LIST="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print "      "$1}')"
  DEV_N="$(printf '%s\n' "$DEV_LIST" | grep -c '[^[:space:]]' || true)"
  if [ "$DEV_N" -eq 0 ]; then
    echo "错误：没有已连接的设备（或设备未授权）。" >&2
    echo "      请先启动模拟器 / 连接设备；当前 adb devices 输出：" >&2
    "$ADB_BIN" devices >&2 || true
    exit 1
  elif [ "$DEV_N" -gt 1 ]; then
    echo "错误：检测到 $DEV_N 台设备，请用 DEV=\"-s <serial>\" 指定其中一台：" >&2
    printf '%s\n' "$DEV_LIST" >&2
    exit 1
  fi
fi

mkdir -p "$OUT"

"$ADB_BIN" $DEV shell "content call --uri content://$AUTH --method PLAN_JSON --extra forceFull:b:true" \
  > "$OUT/raw-full.txt" 2>&1
"$ADB_BIN" $DEV shell "content call --uri content://$AUTH --method PLAN_JSON" \
  > "$OUT/raw-merge.txt" 2>&1

"$PY" - "$OUT" <<'PYEOF'
import json, os, sys

out = sys.argv[1]
EXPECT = {'full': False, 'merge': True}
bad = 0

def extract(path):
    raw = open(path, encoding='utf-8', errors='replace').read()
    i = raw.find('JSON=')
    if i < 0:
        return None
    s = raw[i + 5:].rstrip()
    if s.endswith('}]'):
        s = s[:-2]
    return json.loads(s)

for tag in ('full', 'merge'):
    try:
        d = extract(os.path.join(out, 'raw-%s.txt' % tag))
    except Exception as e:
        print('[%s] 解析失败: %s' % (tag, e)); bad += 1; continue
    if d is None:
        print('[%s] 没找到 JSON' % tag); bad += 1; continue

    open(os.path.join(out, 'golden-%s.xml' % tag), 'w', encoding='utf-8').write(d.get('xml', ''))
    got = bool(d.get('mergeMode'))
    ok = (got == EXPECT[tag])
    if not ok:
        bad += 1
    print('[%s] 应用=%s 槽位=%s 合并=%s 手动分组=%s 排除=%s  %s'
          % (tag, d.get('totalApps'), len(d.get('placements', [])), got,
             d.get('customFolders'), d.get('excluded'),
             'OK' if ok else '❌ 模式不符（期望 merge=%s）' % EXPECT[tag]))

sys.exit(1 if bad else 0)
PYEOF

echo "基线已写入 $OUT"
