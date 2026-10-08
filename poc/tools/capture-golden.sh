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
set -euo pipefail

ADB="/c/Users/Doro/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="${DEV:--s emulator-5554}"
AUTH="com.example.layoutprovider"
OUT="${1:?用法: capture-golden.sh <输出目录>}"
PY="/c/Users/Doro/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"

mkdir -p "$OUT"

"$ADB" $DEV shell "content call --uri content://$AUTH --method PLAN_JSON --extra forceFull:b:true" \
  > "$OUT/raw-full.txt" 2>&1
"$ADB" $DEV shell "content call --uri content://$AUTH --method PLAN_JSON" \
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
