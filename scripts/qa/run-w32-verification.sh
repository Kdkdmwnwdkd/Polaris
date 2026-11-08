#!/usr/bin/env bash
# W-32 harness：GitHub 提交纯逻辑（blob sha / 路径规范化 / 差异比对 / 提交消息）。
# 纯 JVM：只编 1 个无依赖源文件 + harness。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-w32

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/build/GitHubGitLogic.kt"
SRC2="$ROOT/scripts/qa/W32Verification.kt"

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "$SRC1" "$SRC2"; do
  [ -f "$f" ] || { echo "FATAL: 缺少 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling w32 harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$STDLIB" -d "$OUT" "$SRC1" "$SRC2"

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" W32VerificationKt
