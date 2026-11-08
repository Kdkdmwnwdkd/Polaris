#!/usr/bin/env bash
# R11（供应商身份不丢）行为验证。
#
# 与 R10 同一类：需要 AGP 编译产物，不依赖网络与设备，纯对象构造。
# 单独成文件而不是并进 R10，是因为两者测的是不同层：
#   R10 测纯函数（目录查得到吗、收敛公式对不对）；
#   R11 测接线（生产代码到底把哪个字符串传进去了）。
# 混在一起会重演「R10 全绿但功能未生效」那种误判。
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

OUT=/tmp/qa-verify-provider-id
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$OKHTTP:$OKIO

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="${CLASSES//:/,}" \
  -classpath "$DEPS" \
  -d "$OUT" "$ROOT/scripts/qa/ProviderIdWiringVerification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" ProviderIdWiringVerificationKt
