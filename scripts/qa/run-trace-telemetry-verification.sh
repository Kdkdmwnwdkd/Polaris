#!/usr/bin/env bash
# 遥测流水 harness：编译 + 运行（纯 JVM，不加载 Android 类）。
#
# 编译单元 = TurnObserver（事件模型）+ RunScope + TurnEventNdjson + TraceSink + harness。
# 刻意**不拉 AgentLogger**：它链到 ConfigSanitizer → 供应商目录，会把十几个无关文件
# 拖进编译（R12 三个 harness 因此挂过）。文本行那条通道不在这里验——它是给人看的，
# 没有契约要守，验它等于把措辞钉死。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-trace-telemetry
CORE="$ROOT/core/src/main/java/com/beichen/polaris/core"

: "${QA_GRADLE_CACHE:=${AGENTKIT_GRADLE_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1}}"
CACHE="$QA_GRADLE_CACHE"
find_jar() { find "$CACHE/$1" -name "$2" 2>/dev/null | grep -v sources | head -1 || true; }

KOTLINC_JAR=$(find_jar "org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(find_jar "org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(find_jar "org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(find_jar "org.jetbrains/annotations" "annotations-*.jar")

if [ -z "${KOTLINC_JAR:-}" ] || [ -z "${STDLIB:-}" ]; then
  KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
  if [ -x "$KC_HOME/bin/kotlinc" ]; then
    KOTLINC_BIN="$KC_HOME/bin/kotlinc"
    STDLIB=${STDLIB:-$KC_HOME/lib/kotlin-stdlib.jar}
  fi
fi
[ -z "${KOTLINC_BIN:-}" ] && [ -z "${KOTLINC_JAR:-}" ] && { echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（${KOTLINC_JAR:-$KC_HOME/lib/kotlin-compiler.jar}）" >&2; exit 3; }

DEPS=$STDLIB
[ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && DEPS=$DEPS:$JB_ANNOTATIONS

SOURCES="$CORE/agent/TurnObserver.kt $CORE/agent/journal/RunScope.kt \
$CORE/agent/TurnEventNdjson.kt $CORE/telemetry/TraceSink.kt \
$ROOT/scripts/qa/TraceTelemetryVerification.kt"

rm -rf "$OUT" && mkdir -p "$OUT"

compile_once() {
  local out
  if [ -n "${KOTLINC_BIN:-}" ]; then
    out=$("$KOTLINC_BIN" -classpath "$DEPS" -d "$OUT" $SOURCES 2>&1) || true
  else
    local kcp="$KOTLINC_JAR:$STDLIB"
    [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ] && kcp=$kcp:$TROVE
    [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && kcp=$kcp:$JB_ANNOTATIONS
    out=$(java -cp "$kcp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" $SOURCES 2>&1) || true
  fi
  echo "$out" | grep -v "^warning:" || true
  echo "$out" | grep -q "error:" && return 1
  echo "$out" | grep -q "BackendException\|NoClassDefFoundError" && return 2
  return 0
}

echo "[1/2] compiling trace telemetry harness ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e; compile_once; crc=$?; set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then echo "FATAL: 编译失败" >&2; exit 1; fi
  echo "  (attempt $attempt 重试 ...)" >&2
  rm -rf "$OUT" && mkdir -p "$OUT"
done
[ $rc -ne 0 ] && { echo "FATAL: 编译重试 5 次仍失败" >&2; exit 1; }

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" TraceTelemetryVerificationKt "$ROOT"
