#!/usr/bin/env bash
# W-31 harness：并行子代理纯逻辑（参数校验/结论提取/汇总/并发/白名单）。
# 纯 JVM：只编 1 个无依赖源文件 + harness。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-w31

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/SubAgentLogic.kt"
SRC2="$ROOT/scripts/qa/W31Verification.kt"

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "$SRC1" "$SRC2"; do
  [ -f "$f" ] || { echo "FATAL: 缺少 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling w31 harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$STDLIB" -d "$OUT" "$SRC1" "$SRC2"

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" W31VerificationKt
