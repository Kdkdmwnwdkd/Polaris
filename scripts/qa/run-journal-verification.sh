#!/usr/bin/env bash
# journal 三件套的独立验证：修复器 / 落盘 / 运行时门面。
#
# 与其余 harness 的区别：**不依赖 AGP 编译产物**。它只编译
# app/src/main/java/.../core/agent/journal/*.kt 这 4 个文件 + 3 个最小替身，
# 所以任何有 JDK 的机器都能跑，不需要先 ./gradlew。
#
# 为什么单独一套：journal 是恢复链路的唯一依据，改一行就该跑一次；
# 塞进需要 3 分钟 Gradle 构建的主 harness 里，改的人会不想跑。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
# trove4j / annotations 用带回落的版本：编译器内部要用，缺了不是「跑不了」而是
# `ClassNotFoundException: gnu.trove.THashMap`——报错形态完全不像依赖缺失。
TROVE=$(qa_find_jar_or_local "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar" "trove4j.jar")
JB_ANNOTATIONS=$(qa_find_jar_or_local "$CACHE/org.jetbrains/annotations" "annotations-*.jar" "annotations.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi
# 协程是 TurnJournal（Mutex / withContext）的必需依赖，缺了编译会跑到一半才炸。
if [ -z "${COROUTINES:-}" ] || [ ! -f "$COROUTINES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 kotlinx-coroutines-core-jvm（Gradle 缓存未就绪）" >&2
  exit 3
fi

JV="$ROOT/scripts/qa/journal-verify"
SRC="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/journal"
# P5-1：RunJournal 的修复路径要记「重放了几次」，故连上遥测（两个纯 JVM 文件，零 Android 依赖）
TELEMETRY="$ROOT/core/src/main/java/com/beichen/polaris/core/telemetry"
OUT=/tmp/qa-verify-journal

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling (journal sources + stubs, no Android deps) ..."
COMPILE_LOG=/tmp/qa-journal-compile.log
# 编译器自己的 classpath 必须带 stdlib：kotlin-compiler-embeddable 不是 fat jar，
# 少了它会在 K2JVMCompiler.main 里 NoClassDefFoundError（不是编译报错，是启动就炸）。
if ! java -cp "$KOTLINC:$STDLIB:$COROUTINES:$TROVE:$JB_ANNOTATIONS" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -classpath "$STDLIB:$COROUTINES" \
    -d "$OUT" \
    "$JV/stubs/AgentLogger.kt" "$JV/stubs/ToolCall.kt" "$JV/stubs/ToolCallOutcome.kt" \
    "$JV/RepairVerification.kt" "$JV/JournalIoVerification.kt" "$JV/TurnJournalVerification.kt" \
    "$SRC/RunEvent.kt" "$SRC/RunScope.kt" "$SRC/RunEventJson.kt" "$SRC/RunJournalRepair.kt" "$SRC/RunJournal.kt" "$SRC/TurnJournal.kt" \
    "$TELEMETRY/TraceSink.kt" "$TELEMETRY/StoreWriteTrace.kt" \
    > "$COMPILE_LOG" 2>&1; then
  echo "编译失败：" >&2
  grep -v '^warning:' "$COMPILE_LOG" | tail -30 >&2
  exit 1
fi
grep -v '^warning:' "$COMPILE_LOG" || true

RUN_CP="$OUT:$STDLIB:$COROUTINES"
fail=0

echo "[2/2] running 3 suites ..."
for main in RepairVerificationKt JournalIoVerificationKt verify.TurnJournalVerificationKt; do
  echo "--- $main"
  if ! java -cp "$RUN_CP" "$main"; then
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "JOURNAL_VERIFICATION: FAIL"
  exit 1
fi
echo "JOURNAL_VERIFICATION: ALL PASS"
