#!/usr/bin/env bash
# 输出截断「可查 → 可见」验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）。
# 形态学 scripts/qa/run-retry-verification.sh。
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
JB_ANNOTATIONS=$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi

CLASSES=${AGENTKIT_CLASSES:-$(qa_classpath "$ROOT")}
if [ -z "$CLASSES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺少 Gradle 编译产物：$CLASSES" >&2
  echo "  先执行 ./gradlew :app:compileDebugKotlin，或用 AGENTKIT_CLASSES 指向已有目录" >&2
  exit 3
fi

OUT=/tmp/qa-verify-truncation
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE

# 产物陈旧防护：源码比 class 新就是在读旧逻辑（本轮踩过——改完 ProviderCatalog 直接
# 跑 harness，读到的还是旧 class，报「期望 128000 实际 64000」）。
if [ -n "$(qa_classes_stale "$ROOT" "$CLASSES" 2>/dev/null || true)" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 编译产物陈旧（源码比 class 新）——先重编再跑" >&2
  exit 3
fi

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling truncation harness ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="${CLASSES//:/,}" \
  -classpath "$DEPS" \
  -d "$OUT" "$ROOT/scripts/qa/TruncationUiVerification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" TruncationUiVerificationKt
