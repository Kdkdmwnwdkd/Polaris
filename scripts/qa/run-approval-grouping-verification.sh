#!/usr/bin/env bash
# W-36 harness：审批「同类操作」归组（纯 JVM，不加载 Android）。
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-approval-grouping
KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
KOTLINC_BIN="$KC_HOME/bin/kotlinc"
[ -x "$KOTLINC_BIN" ] || { echo "FATAL: 找不到 kotlinc（$KOTLINC_BIN）" >&2; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC_BIN" -d "$OUT" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ApprovalGrouping.kt" \
  "$ROOT/scripts/qa/ApprovalGroupingVerification.kt"
java -cp "$OUT:$KC_HOME/lib/kotlin-stdlib.jar" ApprovalGroupingVerificationKt
