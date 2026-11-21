#!/usr/bin/env bash
# ApprovalBridge 行为判据（2026-11-21）。
#
# 审批浮层的中转层有三条「顺序」不变量：发布即见、决策先撤卡再回调、没有 sink 不假装成功。
# 写错了不会崩、不会报错，只在真机上表现成「点了没反应」或「卡片赖着不走」。
# 所以用 JVM harness 真跑，而不是在源码里数 withdraw() 出现了几次。
#
# 只编译 ApprovalBridge.kt 本身 + harness：它是纯 Kotlin（除 coroutines 外零依赖），
# 不需要 Gradle 产物，沙箱里也能跑。
#
# 退出码：0 通过 / 1 有失败 / 3 环境不具备（明确跳过，不伪装成通过）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"

if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi
if [ -z "${COROUTINES:-}" ] || [ ! -f "$COROUTINES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 kotlinx-coroutines-core-jvm（Gradle 缓存未预热）" >&2
  exit 3
fi

OUT=/tmp/qa-verify-approval-bridge
rm -rf "$OUT" && mkdir -p "$OUT"

DEPS="$STDLIB:$COROUTINES:$JB_ANNOTATIONS"

echo "[1/2] compiling ApprovalBridge + harness ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$DEPS" \
  -d "$OUT" \
  "$ROOT/app/src/main/java/com/beichen/polaris/automation/ApprovalBridge.kt" \
  "$ROOT/scripts/qa/ApprovalBridgeVerification.kt" 2>&1 | grep -v "^warning:" || true

if ! ls "$OUT"/*.class >/dev/null 2>&1; then
  echo "FATAL: 编译没有产出 class（见上方 error）" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" ApprovalBridgeVerificationKt
