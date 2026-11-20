#!/usr/bin/env bash
# P1-3 第二阶段：桌宠审批通道验证。
#
# [A] 接线事实：卡片真的在渲染、宿主真的会把气泡弹开、销毁真的会解除等待。
#     这些无法用行为 harness 证：Compose 渲染与 WindowManager 都要真机/模拟器，
#     而「宿主忘了订阅」这种疏漏正是最可能发生的一种——写好了组件却没人画它。
# [B] 网关行为：真编真跑 `ApprovalGate`，验证四种收场 + 不串台 + 点「停」不算拒绝。
#
# 两段缺一不可：A 证明「有东西会显示」，B 证明「等待本身对」。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core
APP=app/src/main/java/com/beichen/polaris
GATE="$CORE/ApprovalGate.kt"
APPROVAL="$APP/pet/PetApproval.kt"
PET="$APP/pet/PetAgentSession.kt"
OVERLAY="$APP/pet/PetOverlay.kt"
PANEL="$APP/pet/PetPanel.kt"
# 3.1 第六批：ConfirmRequiredTool 从 pet/ 迁到 tools/（它是对任意工具的通用装饰，
# 放在 pet 会让装配器反向依赖界面入口）。
WRAP="$APP/tools/ConfirmRequiredTool.kt"
ASM="$APP/tools/ToolAssembler.kt"

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

echo "[A] 接线：卡片有人画、气泡会自动弹开、销毁会解除等待"

[ -f "$GATE" ] && pass "A0 ApprovalGate.kt 存在" || fail "A0 找不到 ApprovalGate.kt"
[ -f "$APPROVAL" ] && pass "A1 PetApproval.kt 存在" || fail "A1 找不到 PetApproval.kt"

# 有副作用的工具必须经确认才能进桌宠的工具表。直接 register(AppLaunchTool(...))
# 等于给桌宠开了一条免确认的写路径。
# 注册点随装配收口搬进了 tools/ToolAssembler 的 pet 分支（3.1 第六批）。
# 守的是「app_launch 这个写工具进桌宠时必须带确认」，不是「这一行写在哪个文件」。
if code_has "$ASM" "ConfirmRequiredTool(AppLaunchTool"; then
  pass "A2 桌宠的 app_launch 包了 ConfirmRequiredTool（不是直接注册）"
else
  fail "A2 桌宠直接注册了写工具 —— 它有副作用却不问用户"
fi

# 装饰器必须同时覆盖两个入口：只覆盖 needsApproval，工具自带的参数级判断会把包装盖掉。
has_level=$(grep -c 'override val needsApproval = true' "$WRAP" 2>/dev/null || echo 0)
has_args=$(grep -c 'needsApprovalFor(args: JsonObject): Boolean = true' "$WRAP" 2>/dev/null || echo 0)
if [ "${has_level:-0}" -ge 1 ] && [ "${has_args:-0}" -ge 1 ]; then
  pass "A3 装饰器同时覆盖 needsApproval 与 needsApprovalFor"
else
  fail "A3 装饰器只覆盖了一个入口（level=$has_level args=$has_args）—— 参数级判断会把包装盖掉"
fi

# 卡片必须被渲染。写了组件却没人调用是这类改动最常见的失败形态。
if code_has "$PANEL" "ApprovalCard(" && code_has "$PANEL" "onApprovalAllow" && code_has "$PANEL" "onApprovalDeny"; then
  pass "A4 PetPanel 渲染了审批卡并接了两个按钮"
else
  fail "A4 PetPanel 没渲染审批卡（或按钮没接）—— Ask 只是一句空话"
fi

# 宿主必须订阅 pending，并且把气泡弹开。只订阅不展开 = 收起态下用户永远看不到卡。
if code_has "$OVERLAY" "PetApproval.pending.collect" && code_has "$OVERLAY" "applyExpanded(true, focusInput = false)"; then
  pass "A5 宿主订阅 pending 并自动展开气泡（收起态也能看到卡）"
else
  fail "A5 宿主没订阅 pending 或没自动展开 —— 用户看着悬浮球空等 45 秒"
fi

# 窗口销毁必须解除等待，否则那一轮会吊到超时。
if code_has "$OVERLAY" "PetApproval.onHostDestroyed()"; then
  pass "A6 关闭桌宠时解除等待（按拒绝收场）"
else
  fail "A6 销毁时没解除等待 —— 协程会吊满 45 秒，桌宠像卡住"
fi

# 超时窗口要有上下界：太短用户来不及读，太长把回合吊死。
# 常量写成 `45_000L`，先去掉下划线再取数字——直接 grep 会把 45 当成 45ms。
timeout_ms=$(grep -oE 'DEFAULT_TIMEOUT_MS *= *[0-9_]+' "$GATE" 2>/dev/null | grep -oE '[0-9_]+' | tail -1 | tr -d '_')
if [ -n "${timeout_ms:-}" ] && [ "$timeout_ms" -ge 10000 ] && [ "$timeout_ms" -le 120000 ]; then
  pass "A7 审批超时窗口 ${timeout_ms}ms（在 10s–120s 之间）"
else
  fail "A7 超时窗口异常（${timeout_ms:-未找到}）—— 太短用户来不及看，太长会把回合吊死"
fi

# 审批不能缓存成「永久允许」：那需要一个持久化策略表，而这里没有。
if grep -qE 'SharedPreferences|putBoolean' "$PANEL" "$APPROVAL" 2>/dev/null; then
  fail "A8 审批路径里出现了持久化 —— 「本次允许」会被用户当成长期授权"
else
  pass "A8 审批不落盘（每次都问，没有假授权）"
fi

echo ""
echo "[B] 网关行为"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
KREFLECT=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-reflect" "kotlin-reflect-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")

if [ -z "${KOTLINC:-}" ] || [ ! -f "$KOTLINC" ] || [ -z "${COROUTINES:-}" ] || [ ! -f "$COROUTINES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin / coroutines 运行库" >&2
  exit 3
fi

OUT=/tmp/qa-pet-consent
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN${KREFLECT:+:$KREFLECT}${COROUTINES:+:$COROUTINES}"
DEPS="$STDLIB:$COROUTINES"

# 一个源文件就够：ApprovalGate 不碰 Android、不碰 UI、不碰序列化。
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
  "$GATE" \
  scripts/qa/PetConsentVerification.kt \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -f "$OUT/PetConsentVerificationKt.class" ]; then
  fail "B 编译失败（上面是输出）—— 网关断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$DEPS" PetConsentVerificationKt)
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
