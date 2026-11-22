#!/usr/bin/env bash
# 「查余额」端到端 harness：本地起一个假供应商，真发 HTTP。
# 验证的是真机上坏掉的那两件事：路径 404 会不会换候选、失败文案会不会变成 "null"。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
source scripts/qa/lib/deps.sh
OUT=/tmp/qa-verify-balance-e2e

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar
OKHTTP=${OKHTTP:-$(qa_find_jar "$QA_GRADLE_CACHE/com.squareup.okhttp3/okhttp/4.12.0" "okhttp-4.12.0.jar")}
OKIO=${OKIO:-$(qa_find_jar "$QA_GRADLE_CACHE/com.squareup.okio/okio-jvm/3.6.0" "okio-jvm-3.6.0.jar")}
CORO=${CORO:-$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1" "*.jar")}

# 缓存找不到时退回沙箱里手工放的产物（与 w29 脚本同一套兜底）
[ -n "${OKHTTP:-}" ] || OKHTTP=$(find /tmp/gcache -name "okhttp-4.12.0.jar" | head -1)
[ -n "${OKIO:-}" ] || OKIO=$(find /tmp/gcache -name "okio-jvm-3.6.0.jar" | head -1)
[ -n "${CORO:-}" ] || CORO=$(find /tmp/gcache -name "kotlinx-coroutines-core-jvm-1.8.1.jar" | head -1)

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "${OKHTTP:-}" "${OKIO:-}" "${CORO:-}"; do
  [ -f "$f" ] || { echo "FATAL: 缺少依赖 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"
DEPS="$STDLIB:$OKHTTP:$OKIO:$CORO"

echo "[1/2] compiling balance e2e harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$DEPS" -d "$OUT" \
  "$ROOT"/core/src/main/java/com/beichen/polaris/core/net/*.kt \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/llm/BalanceParsing.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/llm/BalanceFailure.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/llm/BalanceService.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/llm/LlmHttp.kt" \
  "$ROOT/scripts/qa/BalanceE2EVerification.kt"

echo "[2/2] running ..."
java --add-modules jdk.httpserver -cp "$OUT:$DEPS" BalanceE2EVerificationKt
