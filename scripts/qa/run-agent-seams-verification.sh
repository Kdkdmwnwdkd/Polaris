#!/usr/bin/env bash
# 接缝（AgentSeams）与守卫链（ToolGuardChain）验证。
#
# 分两段，各自回答一个不同的问题：
#   [A] 接线：loop 到底有没有调用这三个接缝 —— 行为测试测不到「没有调用」，
#       一个定义了却没人调用的接缝，跑一万条行为断言也全绿。
#   [B] 行为：真编真跑实现本身（不是替身），验证四条最容易写错的语义：
#       首个拒绝即终止 / 不调 next 即截断 / 异常与超时方向 / 监听器并发。
#
# 为什么不用 AGENTKIT_CLASSES：本 harness 的被测文件全是零 Android 依赖的纯 Kotlin，
# 手列 9 个源文件即可编译，任何装了 kotlinc 的机器都能真跑，不会因为「没编过 APK」而 SKIP。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core
LOOP="$CORE/agent/RealAgentLoop.kt"
SEAMS="$CORE/agent/AgentSeams.kt"
GUARD="$CORE/agent/ToolGuard.kt"
DENY="$CORE/agent/SeamDeny.kt"

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 接线：loop 真的调用了三个接缝"

if grep -qF "seams.applyUserMessage(" "$LOOP"; then
  pass "A1 RealAgentLoop 调用用户消息接缝"
else
  fail "A1 RealAgentLoop 没有调用 applyUserMessage —— 接缝①是死代码"
fi

if grep -qF "seams.denyOutcomeOrNull(" "$LOOP"; then
  pass "A2 RealAgentLoop 在工具执行前调用守卫链"
else
  fail "A2 RealAgentLoop 没有调用守卫链 —— 接缝③是死代码"
fi

if grep -qF "seams.notifyAssistant(" "$LOOP"; then
  pass "A3 RealAgentLoop 在助手消息产出后通知监听器"
else
  fail "A3 RealAgentLoop 没有调用 notifyAssistant —— 接缝②是死代码"
fi

if grep -qE "private val seams: AgentSeams = AgentSeams\.NONE" "$LOOP"; then
  pass "A4 构造参数默认 AgentSeams.NONE（不传 = 行为与改造前一致）"
else
  fail "A4 构造参数缺失默认值 —— 子代理与 harness 的既有调用点会编译失败"
fi

# 拒绝必须走「产出 Completed」这条统一收尾路径：直接 return 会让工具卡片永远停在「执行中」。
if grep -qF "ToolCallOutcome.Completed(" "$DENY"; then
  pass "A5 守卫拒绝产出 Completed（与审批拒绝同一条收尾路径）"
else
  fail "A5 守卫拒绝没有走 Completed —— UI 的「执行中」卡片不会被翻成终态"
fi

# 核心不变量：守卫裁决里不存在「允许」。这条一旦被加回来，整条链就从
# 「单调加限制」退化成「谁先说话谁算批准」，而且是静默退化。
if grep -qE "object Allow|data object Allow|Allow *: *GuardDecision" "$GUARD"; then
  fail "A6 GuardDecision 里出现了 Allow —— 单调性被破坏（见 GuardDecision 的 KDoc）"
else
  pass "A6 GuardDecision 仍然没有 Allow（单调性成立）"
fi

# 空装配必须零开销：三个分发方法都在首行判空返回，否则「不传接缝」也会走一遍协程调度。
shortcut=$(grep -cE 'if \((userMessage|toolGuards|assistantListeners)\.isEmpty\(\)\)' "$SEAMS" || true)
if [ "${shortcut:-0}" -ge 3 ]; then
  pass "A7 三个分发方法都有空装配短路（$shortcut 处）"
else
  fail "A7 AgentSeams 只有 $shortcut 处空装配短路，应为 3 —— NONE 也会产生协程开销"
fi

echo ""
echo "[B] 行为：真编真跑（9 个纯 Kotlin 源文件）"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANN:-}" ] || JB_ANN="$KC_HOME/lib/annotations.jar"

if [ ! -f "$KOTLINC" ] || [ ! -f "$STDLIB" ] || [ -z "${COROUTINES:-}" ] || [ -z "${SER_JSON:-}" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin 编译器或协程/序列化构件" >&2
  exit 3
fi

OUT=/tmp/qa-agent-seams
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN"
DEPS="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
  "$CORE/AgentContract.kt" \
  "$CORE/ToolResult.kt" \
  "$CORE/ToolLimits.kt" \
  "$CORE/agent/ToolDescriptor.kt" \
  "$GUARD" \
  "$SEAMS" \
  scripts/qa/AgentSeamsVerification.kt \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -d "$OUT/com" ]; then
  fail "B 编译失败（上面是输出）—— 行为断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$DEPS" AgentSeamsVerificationKt)
  rc=$?
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  line=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
  # 没有结果行 = harness 没跑完（崩了 / 被 kill）。默认「没失败就是通过」会让
  # 整段静默消失，而日志里看起来只有 A 段的几条 PASS。
  if [ -z "$line" ]; then
    fail "B 段没有结果行（harness 中途退出，rc=$rc）—— 未跑完不算通过"
  else
    bp=$(echo "${line%% 通过*}" | tr -dc '0-9'); bp=${bp:-0}
    bf=$(echo "${line##* / }" | tr -dc '0-9'); bf=${bf:-0}
    p=$((p + bp)); f=$((f + bf))
    [ "$bf" -eq 0 ] || echo "        B 段有 $bf 条行为断言失败"
  fi
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] || exit 1
