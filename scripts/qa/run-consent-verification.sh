#!/usr/bin/env bash
# P1-3 `Consent` 契约验证。
#
# [A] 契约事实：`null` 不可表达、默认值方向是关、四个生产入口都表了态。
#     这些是「源码里有没有这条线」——行为 harness 测不到「某个入口忘了传」，
#     而「忘了传」正是这次改造要消灭的东西。
# [B] 裁决行为：真编真跑 `Consent.decide`，验证四种裁决 + 取消上抛 + 不缓存授权。
#
# 两段缺一不可：A 只证明写了 `when`，B 只证明裁决本身对，但证明不了执行器在拒绝时
# 提前 return。A6 用源码顺序断言补这一条。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core
CONSENT="$CORE/Consent.kt"
EXEC="$CORE/agent/ToolExecutor.kt"
LOOP="$CORE/agent/RealAgentLoop.kt"
SUB="$CORE/agent/SubAgentRunner.kt"
SKILL="$ROOT/core/src/main/java/com/beichen/polaris/skill/SkillExecutor.kt"
APP=app/src/main/java/com/beichen/polaris
VM="$APP/ui/ChatViewModel.kt"
PET="$APP/pet/PetAgentSession.kt"

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

code_has() { # $1=文件 $2=字面量（跳过注释行）
  [ -f "$1" ] || return 1
  awk -v pat="$2" '
    {
      line=$0
      sub(/^[ \t]+/, "", line)
      if (line ~ /^(\/\/|\*|\/\*)/) next
      sub(/\/\/.*/, "", line)
      if (index(line, pat) > 0) { found=1; exit }
    }
    END { exit(found ? 0 : 1) }
  ' "$1"
}

echo "[A] 契约：null 不可表达、默认值方向是关、四个入口都表了态"

if [ -f "$CONSENT" ]; then
  pass "A0 Consent.kt 存在"
else
  fail "A0 找不到 Consent.kt —— 契约没落地"
fi

if code_has "$CONSENT" "sealed interface Consent"; then
  pass "A1 Consent 是 sealed interface（封闭类型，穷举 when 由编译器保证）"
else
  fail "A1 Consent 不是 sealed —— 新增一种裁决时所有 when 不会报错"
fi

# 关键判据：参数类型里不许再出现可空的审批回调。这条一破，「忘了传」就又能编译通过。
stale=""
for src in "$EXEC" "$LOOP" "$SUB"; do
  if grep -qE 'val approval: *ToolApproval\?' "$src" 2>/dev/null; then
    stale="$stale $(basename "$src")"
  fi
done
if [ -z "$stale" ]; then
  pass "A2 三个执行器的参数不再是 ToolApproval?（null 不可表达）"
else
  fail "A2 仍有可空审批参数：$stale —— null 会重新变成「设计」与「疏忽」的共同表示"
fi

# 默认值的方向：忘记表态时必须是拒绝，不能是放行。
unset_n=$(grep -c 'Consent\.unset(' "$EXEC" "$LOOP" "$SUB" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')
if [ "${unset_n:-0}" -ge 3 ]; then
  pass "A3 三处默认值都是 Consent.unset()（缺省方向为关）"
else
  fail "A3 只找到 ${unset_n:-0} 处 Consent.unset()，预期 ≥3 —— 有一处默认值可能仍是放行"
fi

# 四个生产入口必须逐个表态。这是默认值方案唯一的代价：默认值让「忘了传」仍能编译，
# 所以由这条门禁盯住真正需要判别力的四处。
missing=""
code_has "$VM" "consent = Consent.ask" || missing="$missing ChatViewModel(主loop)"
[ "$(grep -c 'consent = Consent.ask' "$VM" 2>/dev/null || echo 0)" -ge 2 ] || missing="$missing ChatViewModel(子代理)"
code_has "$PET" "consent = Consent.ask" || missing="$missing PetAgentSession"
code_has "$SKILL" "consent = Consent.ask" || missing="$missing SkillExecutor"
if [ -z "$missing" ]; then
  pass "A4 四个生产入口都显式声明了 consent"
else
  fail "A4 入口没表态：$missing —— 它们会退到默认拒绝，功能整体不可用"
fi

# 桌宠从 Deny 升到 Ask 的前提是**卡片真的会显示**。写一个没人接的 Ask 比写 Deny 更糟：
# Deny 会老实说「我没有审批界面」，假 Ask 会挂 45 秒然后说「用户拒绝」。
if code_has "$PET" "PetApproval.ask" && [ -f "$APP/pet/PetApproval.kt" ]; then
  pass "A5 桌宠的 Ask 接到了 PetApproval（不是一句假装有地方问的声明）"
else
  fail "A5 桌宠写了 Ask 却没有接 PetApproval —— 用户看不到卡片，只会看到「拒绝了该操作」"
fi

# 拒绝分支必须在工具执行之前。这条是本 harness 里唯一能防「先执行再拒绝」的断言：
# B 段只测裁决，测不到执行器的分支顺序。
decide_line=$(grep -n 'consent\.decide(' "$EXEC" 2>/dev/null | head -1 | cut -d: -f1)
exec_line=$(grep -n 'tool\.execute(' "$EXEC" 2>/dev/null | head -1 | cut -d: -f1)
noappr_line=$(grep -n '"no_approver"' "$EXEC" 2>/dev/null | head -1 | cut -d: -f1)
if [ -n "$decide_line" ] && [ -n "$exec_line" ] && [ -n "$noappr_line" ] \
   && [ "$decide_line" -lt "$exec_line" ] && [ "$noappr_line" -lt "$exec_line" ]; then
  pass "A6 审批裁决与拒绝分支都在 tool.execute 之前（行号 $decide_line/$noappr_line < $exec_line）"
else
  fail "A6 裁决顺序不对或找不到判据（decide=$decide_line no_approver=$noappr_line execute=$exec_line）——「拒绝」可能发生在执行之后"
fi

# 审批语义只许有一份。执行器里若仍在直接调用审批回调（而不是问 Consent.decide），
# 说明有人把规则抄回去了——改一处时另一处不会跟着变。
if grep -qE 'approver\s*\(|\.handler\s*\(' "$EXEC" 2>/dev/null; then
  fail "A7 执行器里仍在直接调用审批回调 —— 语义被抄成了两份，改一处另一处不会跟着变"
else
  pass "A7 审批语义只在 Consent 一处（执行器只剩翻译四个分支）"
fi

if code_has "$CONSENT" "data class Unavailable" && code_has "$CONSENT" "data object Denied"; then
  pass "A8 Decision 把「没问成」与「问了被拒」分成两个值（布尔会压扁这个区别）"
else
  fail "A8 Decision 没有区分 Unavailable / Denied —— 用户会看到错误的原因"
fi

echo ""
echo "[B] 裁决行为"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
KREFLECT=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-reflect" "kotlin-reflect-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")

if [ -z "${KOTLINC:-}" ] || [ ! -f "$KOTLINC" ] || [ -z "${SER_JSON:-}" ] || [ ! -f "$SER_JSON" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin / kotlinx-serialization 运行库" >&2
  exit 3
fi

OUT=/tmp/qa-consent
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN${KREFLECT:+:$KREFLECT}${COROUTINES:+:$COROUTINES}"
DEPS="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"

# 三个源文件就够：Consent 只依赖 AgentTool + JsonObject。这正是把裁决从
# RegistryToolExecutor（牵 ToolRegistry → JsonSchema.serializer() → 要编译器插件）搬出来的收益。
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
  "$CONSENT" \
  "$CORE/ToolResult.kt" \
  "$CORE/AgentContract.kt" \
  scripts/qa/ConsentVerification.kt \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -f "$OUT/ConsentVerificationKt.class" ]; then
  fail "B 编译失败（上面是输出）—— 裁决断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$DEPS" ConsentVerificationKt)
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
