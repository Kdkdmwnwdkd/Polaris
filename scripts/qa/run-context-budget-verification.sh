#!/usr/bin/env bash
# W-10 上下文预算 harness：编译 + 运行（纯 JVM，不加载 Android 类）。
#
# 构件解析两级：优先 Gradle 缓存（CI/完整开发机），缺失时回落本机 kotlinc 发行版
# （沙箱环境 /tmp/kotlinc + /tmp/w7-jars）——沙箱无 Gradle 缓存，但验证口径不变。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-context-budget

# W-41：缓存根按环境推导（CI 是 /home/runner/.gradle），可被 AGENTKIT_GRADLE_CACHE 覆盖
: "${QA_GRADLE_CACHE:=${AGENTKIT_GRADLE_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1}}"
CACHE="$QA_GRADLE_CACHE"
KOTLINC_JAR=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name "kotlin-compiler-embeddable-*.jar" 2>/dev/null | grep -v sources | head -1) || true
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-1.9*.jar" 2>/dev/null | grep -v sources | head -1) || true
SERIALIZATION=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" -name "*.jar" 2>/dev/null | grep -v sources | head -1) || true
SER_CORE=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" -name "*.jar" 2>/dev/null | grep -v sources | head -1) || true
TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name "trove4j-*.jar" 2>/dev/null | head -1) || true
JB_ANNOTATIONS=$(find "$CACHE/org.jetbrains/annotations" -name "annotations-*.jar" 2>/dev/null | head -1) || true

# 沙箱回落：完整 kotlinc 发行版 + 预置 jar
if [ -z "${KOTLINC_JAR:-}" ] || [ -z "${STDLIB:-}" ] || [ -z "${SERIALIZATION:-}" ]; then
  KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
  if [ -x "$KC_HOME/bin/kotlinc" ]; then
    KOTLINC_BIN="$KC_HOME/bin/kotlinc"
    STDLIB=${STDLIB:-$KC_HOME/lib/kotlin-stdlib.jar}
    SERIALIZATION=${SERIALIZATION:-/tmp/w7-jars/kotlinx-serialization-json-jvm-1.6.3.jar}
    SER_CORE=${SER_CORE:-/tmp/w7-jars/kotlinx-serialization-core-jvm-1.6.3.jar}
  fi
fi

if [ -z "${KOTLINC_BIN:-}" ] && [ -z "${KOTLINC_JAR:-}" ]; then
  echo "FATAL: 找不到 kotlinc（Gradle 缓存与本机发行版均缺失）" >&2
  exit 1
fi
for j in "$STDLIB" "$SERIALIZATION" "$SER_CORE"; do
  if [ ! -f "$j" ]; then echo "FATAL: 缺少依赖 $j" >&2; exit 1; fi
done

DEPS=$STDLIB:$SERIALIZATION:$SER_CORE
if [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ]; then DEPS=$DEPS:$JB_ANNOTATIONS; fi

rm -rf "$OUT" && mkdir -p "$OUT"

compile_ctx() {
  local attempt=$1 out
  if [ -n "${KOTLINC_BIN:-}" ]; then
    out=$("$KOTLINC_BIN" -classpath "$DEPS" -d "$OUT" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/AgentContract.kt" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/ToolResult.kt" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/ToolLimits.kt" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ContextBudget.kt" \
      "$ROOT/scripts/qa/ContextBudgetVerification.kt" 2>&1) || true
  else
    local kcp="$KOTLINC_JAR:$STDLIB"
    if [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ]; then kcp=$kcp:$TROVE; fi
    if [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ]; then kcp=$kcp:$JB_ANNOTATIONS; fi
    out=$(java -cp "$kcp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/AgentContract.kt" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/ToolResult.kt" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/ToolLimits.kt" \
      "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ContextBudget.kt" \
      "$ROOT/scripts/qa/ContextBudgetVerification.kt" 2>&1) || true
  fi
  if echo "$out" | grep -q "error:"; then
    echo "$out"; return 1
  fi
  if echo "$out" | grep -q "BackendException\|NoClassDefFoundError"; then
    echo "  (compile attempt $attempt hit backend annotation race, retrying ...)" >&2
    return 2
  fi
  echo "$out" | grep -v "^warning:" || true
  return 0
}

echo "[1/2] compiling context-budget harness ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e
  compile_ctx "$attempt"
  crc=$?
  set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then exit 1; fi
  rm -rf "$OUT" && mkdir -p "$OUT"
done
if [ $rc -ne 0 ]; then
  echo "FATAL: context-budget compile keeps hitting backend annotation race after 5 attempts" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" ContextBudgetVerificationKt
