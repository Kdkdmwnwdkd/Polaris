#!/usr/bin/env bash
# 「提示缓存」QA 行为验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）。
#
# 与其他 harness 的差别：这份要真发一次 HTTP 请求——本地起一个假 Anthropic 端点，
# 把 ClaudeClient 收到的请求体截下来逐字段断言。所以要额外带 okhttp/okio。
set -euo pipefail

# W-39：仓库根目录由脚本位置推导，不再硬编码 /workspace/AgentKit
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
SERIALIZATION=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
OKHTTP=$(qa_find_jar "$CACHE/com.squareup.okhttp3/okhttp" "okhttp-*.jar")
OKIO=$(qa_find_jar "$CACHE/com.squareup.okio/okio-jvm" "okio-jvm-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains/annotations" "annotations-*.jar")

# W-40：Gradle 缓存缺失时回落本机 kotlinc 发行版
[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
# W-41：编译器两条路都没有 → 明确 SKIP（exit 3），不伪装成通过也不伪装成失败
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

# okhttp/okio 找不到就没法真发请求；明确跳过而不是让它变成「找不到类」
if [ ! -f "$OKHTTP" ] || [ ! -f "$OKIO" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缓存里缺 okhttp/okio（$OKHTTP / $OKIO）" >&2
  exit 3
fi

OUT=/tmp/qa-verify-prompt-cache
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$OKHTTP:$OKIO

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="${CLASSES//:/,}" \
  -classpath "$DEPS" \
  -d "$OUT" $ROOT/scripts/qa/PromptCacheVerification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  PromptCacheVerificationKt
