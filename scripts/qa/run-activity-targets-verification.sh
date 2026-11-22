#!/usr/bin/env bash
# app_launch 的 activity 候选 harness（纯 JVM，只编一个无依赖源文件）。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-activity-targets

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling activity-targets harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$STDLIB" -d "$OUT" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/tool/ActivityTargets.kt" \
  "$ROOT/scripts/qa/ActivityTargetsVerification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" ActivityTargetsVerificationKt
