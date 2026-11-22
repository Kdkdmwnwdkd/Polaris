#!/usr/bin/env bash
# 审批记账 harness：超时三态 + 本回合不再打扰 + 连拒读屏时 App 说真话。
# 纯 JVM：ApprovalTurn 与 DenyMessage 都不依赖 Android。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
source scripts/qa/lib/deps.sh
OUT=/tmp/qa-verify-approval-turn

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar
# 协程只在 ApprovalDeadline 用到。优先 Gradle 缓存（CI 有），沙箱回落 /tmp/gcache。
CORO=${CORO:-$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar" 2>/dev/null)}
[ -n "${CORO:-}" ] || CORO=$(find /tmp/gcache -name "kotlinx-coroutines-core-jvm-*.jar" 2>/dev/null | head -1)
[ -n "${CORO:-}" ] || { echo "FATAL: 找不到 kotlinx-coroutines-core-jvm" >&2; exit 1; }

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling approval-turn harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$STDLIB:$CORO" -d "$OUT" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ApprovalDeadline.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ApprovalGrouping.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/DenyMessage.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ApprovalTurn.kt" \
  "$ROOT/scripts/qa/ApprovalTurnVerification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB:$CORO" ApprovalTurnVerificationKt
