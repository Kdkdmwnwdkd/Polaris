#!/usr/bin/env bash
# R2（三层超时）+ R4（审批收口）行为验证。
#
# 与 T01 同类：需要 AGP 编译产物（ScriptedClient/RegistryToolExecutor 都来自主源码），
# 但不依赖网络与设备。超时被压缩到毫秒级，整套 3 秒内跑完。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
SERIALIZATION=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")
OKHTTP=$(qa_find_jar "$CACHE/com.squareup.okhttp3/okhttp" "okhttp-*.jar")
OKIO=$(qa_find_jar "$CACHE/com.squareup.okio/okio-jvm" "okio-jvm-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi

CLASSES=${AGENTKIT_CLASSES:-$(qa_classpath "$ROOT")}
if [ -z "$CLASSES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺少 Gradle 编译产物：$CLASSES" >&2
  echo "  先执行 ./gradlew :app:compileDebugKotlin" >&2
  exit 3
fi

OUT=/tmp/qa-verify-r2r4
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$OKHTTP:$OKIO

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="${CLASSES//:/,}" \
  -classpath "$DEPS" \
  -d "$OUT" "$ROOT/scripts/qa/R2R4Verification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" R2R4VerificationKt
