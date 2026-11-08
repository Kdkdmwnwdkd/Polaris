#!/usr/bin/env bash
# PathGuard harness（纯 JVM，真建临时目录做行为断言）。
#
# 盯的是「工作区越界」：字符串前缀不等于目录前缀。`canonicalPath.startsWith(root)`
# 少一个 File.separator，`/w/polaris-evil/x` 就会被认成 `/w/polaris` 的子路径。
# 同类判据原本有三份手写实现，现收到 core/workspace/PathGuard.kt 一处。
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-path-guard
KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
KOTLINC_BIN="$KC_HOME/bin/kotlinc"
[ -x "$KOTLINC_BIN" ] || { echo "FATAL: 找不到 kotlinc（$KOTLINC_BIN）" >&2; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC_BIN" -d "$OUT" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/workspace/PathGuard.kt" \
  "$ROOT/scripts/qa/PathGuardVerification.kt"
java -cp "$OUT:$KC_HOME/lib/kotlin-stdlib.jar" PathGuardVerificationKt
