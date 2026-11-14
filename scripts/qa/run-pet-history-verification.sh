#!/usr/bin/env bash
# 桌宠上下文维护（PetAgentSession.ask 里的 history 增删）验证。
#
# 背景（2026-11-14 第三轮模拟器实测，假模型端点把请求体整份落盘才发现）：
#   第二轮请求的 messages 里出现两个一模一样的 system prompt，
#   第三轮又只剩一个——不是修好了，是 takeLast 恰好把旧 system 挤了出去。
#   根因：loop.send 返回的是整段上下文（开头是它自己 prepend 的 systemPrompt），
#   原样存回 history，下一轮作为 prior 再传一遍，loop 又 prepend 一次。
#   模型看到两份同样的指令，每次都多付那段 prompt（实测 304 字符）。
#
# 分两段：
#   [A] 结构：history 存的必须是被过滤过的上下文，且 import 到位。
#       行为测试测不到「以后有人把这行改回去」——这类回归只会静默地多付 prompt。
#   [B] 行为：真编真跑规则模型，并把**修复前的写法**一起跑作为对照。
#       没有对照的测试证明不了自己有分辨力：换成旧写法它必须红。
#
# 为什么 B 段复刻类型而不用真实 ChatMessage：PetAgentSession 依赖 Context，
# 纯 JVM 编不动。这里验的是规则（send 返回整段 → history 只留对话），
# A 段负责保证源码里用的确实是这条规则。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SESSION=app/src/main/java/com/beichen/polaris/pet/PetAgentSession.kt
VERIFY=scripts/qa/PetHistoryVerification.kt

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 结构：只把对话部分写回 history"

if [ ! -f "$SESSION" ]; then
  fail "找不到 $SESSION —— 文件改名了？本 harness 失效"
  echo ""
  echo "$p 通过 / $f 失败"
  exit 1
fi

if grep -q '^import com.beichen.polaris.core.SystemMessage' "$SESSION"; then
  pass "A1 显式 import SystemMessage（没有靠全限定名硬写）"
else
  fail "A1 没有 import SystemMessage —— 过滤写不成"
fi

if grep -qF 'history.addAll(messages.filterNot { it is SystemMessage }' "$SESSION"; then
  pass "A2 history 写入前过滤掉 SystemMessage"
else
  fail "A2 history 仍在原样存整段上下文 —— 下一轮会多出一个 system prompt"
fi

# 单独拎出来防回退：这条正是修复前的写法，它一旦回来就说明改回去了，
# 而代码照样编译、照样跑，只有账单上多 304 字符。
if grep -qE 'history\.addAll\(messages\.takeLast\(' "$SESSION"; then
  fail "A3 出现裸 messages.takeLast(...) 写回 history —— 缺陷回退了"
else
  pass "A3 没有裸 takeLast 写回（system 不会回流）"
fi

if grep -qE 'takeLast\(MAX_HISTORY\)' "$SESSION"; then
  pass "A4 条数上限仍在（过滤不能顺手把截断丢了）"
else
  fail "A4 找不到 takeLast(MAX_HISTORY) —— 上限丢了，历史会无限长"
fi

if grep -qE 'private val history = mutableListOf<ChatMessage>' "$SESSION"; then
  pass "A5 history 类型未变（仍是 ChatMessage 列表）"
else
  fail "A5 history 声明变了，本 harness 的前提失效，需人工确认"
fi

# 空输入必须提前返回，否则空串会进历史、下一轮 prompt 里多一条空 user。
if grep -qE 'if \(text\.isEmpty\(\)\) return@withLock' "$SESSION"; then
  pass "A6 空输入提前返回，不写历史"
else
  fail "A6 空输入没有提前返回 —— 会产生空的 user 消息"
fi

echo ""
echo "[B] 行为：真编真跑（规则模型 + 修复前写法对照）"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANN:-}" ] || JB_ANN="$KC_HOME/lib/annotations.jar"

if [ ! -f "$KOTLINC" ] || [ ! -f "$STDLIB" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin 编译器构件（$KOTLINC）" >&2
  exit 3
fi

OUT=/tmp/qa-pet-history
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN"
DEPS="$STDLIB"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
  "$VERIFY" \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -f "$OUT/PetHistoryVerificationKt.class" ]; then
  fail "B 编译失败（上面是输出）—— 行为断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$DEPS" PetHistoryVerificationKt)
  rc=$?
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  line=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
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
