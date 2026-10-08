#!/usr/bin/env bash
# 确定性重建 references/ —— 4 个上游参考仓库，固定到笔记引用过的 commit。
#
# 用法：
#   bash tools/fetch-references.sh               # 还原到 <项目>/references
#   bash tools/fetch-references.sh <目标目录>     # 还原到指定目录
#
# 为什么需要这个脚本：
#   references/ 因体积与版权不入公开仓，但笔记里的结论**依赖具体 commit**
#   （尤其 lawnchair），随手 clone 最新版会让行号与行为结论全部失效。
#   本脚本把 URL / 分支 / commit / 稀疏检出规则全部固化，保证结果可重现。
#
# 参考仓库的用途与看点见 README.md「各参考项目看点」，精读笔记见 notes/。
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$HERE/references}"
mkdir -p "$DEST"

echo "目标目录: $DEST"
echo

# 普通仓库：全量克隆后检出指定 commit
clone_at() {   # clone_at <目录名> <URL> <commit>
  local name="$1" url="$2" sha="$3" dir="$DEST/$1"
  if [ -d "$dir/.git" ]; then echo "  [跳过] $name（已存在）"; return 0; fi
  printf '  [克隆] %-28s' "$name"
  git clone -q --no-checkout "$url" "$dir"
  git -C "$dir" -c advice.detachedHead=false checkout -q "$sha"
  echo " OK  ${sha:0:12}"
}

# 稀疏 + 部分克隆：lawnchair 全量约 929 MB，必须稀疏检出
clone_sparse_at() {   # clone_sparse_at <目录名> <URL> <分支> <commit> <路径...>
  local name="$1" url="$2" branch="$3" sha="$4"
  shift 4
  local dir="$DEST/$name"
  if [ -d "$dir/.git" ]; then echo "  [跳过] $name（已存在）"; return 0; fi
  printf '  [克隆] %-28s' "$name"
  git clone -q --filter=blob:none --sparse --depth=1 --branch "$branch" "$url" "$dir"
  git -C "$dir" sparse-checkout set "$@"
  git -C "$dir" fetch -q --depth=1 origin "$sha"
  git -C "$dir" -c advice.detachedHead=false checkout -q "$sha"
  echo " OK  ${sha:0:12}  (sparse: $*)"
}

echo "1/4 android-app-organizer"
clone_at android-app-organizer \
  https://github.com/device-kunkun/android-app-organizer.git \
  fec9666686b103ecdffffa64279213499e779b14

echo "2/4 android-folderautomanager"
clone_at android-folderautomanager \
  https://github.com/hekizoglu/android-folderautomanager.git \
  bbe3671f13ba0b2e53fef02904097a6b443025e4

echo "3/4 lawnchair（稀疏 + 部分克隆）"
clone_sparse_at lawnchair \
  https://github.com/LawnchairLauncher/lawnchair.git \
  16-dev \
  126b2250d76c6d17c04d45e691d57b67860395a1 \
  compatLib lawnchair schemas shared src

echo "4/4 novalaunchereditor"
clone_at novalaunchereditor \
  https://github.com/SamLeatherdale/novalaunchereditor.git \
  4eb86f12409daf1b7ecc37ea1c31cdf6ed4ef00d

echo
echo "=== 校验（HEAD 是否等于钉住的 commit） ==="
expect() {   # expect <目录名> <期望 commit>
  local got
  got="$(git -C "$DEST/$1" rev-parse HEAD 2>/dev/null || echo MISSING)"
  if [ "$got" = "$2" ]; then
    printf '  ✅ %-28s %s\n' "$1" "${got:0:12}"
  else
    printf '  ❌ %-28s 期望 %s，实得 %s\n' "$1" "${2:0:12}" "${got:0:12}"
    return 1
  fi
}
expect android-app-organizer     fec9666686b103ecdffffa64279213499e779b14
expect android-folderautomanager bbe3671f13ba0b2e53fef02904097a6b443025e4
expect lawnchair                 126b2250d76c6d17c04d45e691d57b67860395a1
expect novalaunchereditor        4eb86f12409daf1b7ecc37ea1c31cdf6ed4ef00d

echo
echo "完成。总体积：$(du -sh "$DEST" 2>/dev/null | cut -f1)"
echo
echo "注意：novalaunchereditor **未声明 License**（默认保留所有权利）——"
echo "      仅可参考思路，请勿复制其代码。详见 notes/03-novalaunchereditor.md。"
