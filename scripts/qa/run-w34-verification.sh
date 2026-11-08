#!/usr/bin/env bash
# W-34 harness：行级 diff 纯逻辑（LCS + 前后缀裁剪 + 退化保护）。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-w34

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/text/LineDiff.kt"
SRC2="$ROOT/scripts/qa/W34Verification.kt"

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "$SRC1" "$SRC2"; do
  [ -f "$f" ] || { echo "FATAL: 缺少 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling w34 harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$STDLIB" -d "$OUT" "$SRC1" "$SRC2"

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" W34VerificationKt
