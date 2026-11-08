#!/usr/bin/env bash
# Phase1 补口 · 应用操控工具集 QA 行为验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）
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
# JB_ANNOTATIONS 直接取 Gradle 离线缓存（与 run-t01-verification.sh 同一路径模式）
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

# W-40：Gradle 缓存缺失时回落本机 kotlinc 发行版（沙箱/净机器都能跑）
[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
# W-41：编译器两条路都没有 → 明确 SKIP（exit 3），不让它变成一堆「找不到类」
if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi

CLASSES=${AGENTKIT_CLASSES:-$(qa_classpath "$ROOT")}
# W-39：集成型 harness 需要 AGP 编译产物。缺失时明确跳过（exit 3），
# 绝不把「环境不具备」伪装成「验证失败」，也不伪装成通过。
if [ -z "$CLASSES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺少 Gradle 编译产物：$CLASSES" >&2
  echo "  先执行 ./gradlew :app:compileDebugKotlin，或用 AGENTKIT_CLASSES 指向已有目录" >&2
  exit 3
fi

OUT=/tmp/qa-verify-w9-screenshot
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$JB_ANNOTATIONS

rm -rf "$OUT" && mkdir -p "$OUT"

# -Xfriend-paths 让 harness 可访问 main 模块的 internal 纯函数（name/包名解析等）。
# 编译带分类重试：后端注解解析竞态可重试；前端 error: 是真实代码错误，立即失败不重试。
compile_w9_screenshot() {
  local attempt=$1 out
  out=$(java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -Xfriend-paths="${CLASSES//:/,}" \
    -classpath "$DEPS" \
    -d "$OUT" $ROOT/scripts/qa/W9ScreenshotVerification.kt 2>&1) || true
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

echo "[1/2] compiling w9-screenshot harness with kotlin-compiler-embeddable ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e
  compile_w9_screenshot "$attempt"
  crc=$?
  set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then exit 1; fi
  rm -rf "$OUT" && mkdir -p "$OUT"
done
if [ $rc -ne 0 ]; then
  echo "FATAL: w9-screenshot compile keeps hitting backend annotation race after 5 attempts" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  W9ScreenshotVerificationKt
