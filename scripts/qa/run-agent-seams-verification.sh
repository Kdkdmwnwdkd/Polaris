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
EGRESS="$CORE/agent/EgressGuard.kt"
APP=app/src/main/java/com/beichen/polaris
CHAT_VM="$APP/ui/ChatViewModel.kt"
PET_SESSION="$APP/pet/PetAgentSession.kt"

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

# 只匹配**代码行**里的字面量：跳过以 // /* * 开头的行，并剥掉行尾注释。
# 为什么必须有它：本项目 499 处 grep 里只有 2 处剥注释，于是「把实现删掉、注释留着」
# 依然能绿——今天在 run-desktop-pet-verification.sh 里刚踩过一次（KDoc 里写着
# `arbiter.stopSpeech()`，断言 grep 词根就命中了注释）。
code_has() { # $1=文件 $2=字面量
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
echo "[A2] 生产装配：接缝有没有真的接上"

# 机制写好了没人接，等于没有机制。这三条守的就是「有人把 seams 参数删掉」——
# 删掉之后所有行为测试照样全绿（它们直接构造 AgentSeams），线上却回到零守卫。
if code_has "$CHAT_VM" "seams = productionSeams("; then
  pass "A8 ChatViewModel 的 loop 传了 productionSeams"
else
  fail "A8 ChatViewModel 没有装配 seams —— 守卫链回到「机制在、没人接」"
fi

if code_has "$CHAT_VM" "EgressGuard("; then
  pass "A9 主界面挂了出站守卫（读过文件后外发要确认）"
else
  fail "A9 主界面没有挂 EgressGuard —— web_search 仍是免审外发通道"
fi

# 桌宠没有审批界面，它的 consent 必须恒 false（缺审批即拒绝），而不是「忘了接」。
if code_has "$PET_SESSION" "productionSeams(" && code_has "$PET_SESSION" "EgressGuard(consent = { _, _ -> false })"; then
  pass "A10 桌宠也接了出站守卫，且无审批时按拒绝处理"
else
  fail "A10 桌宠没接守卫，或 consent 不是恒 false —— 无 UI 入口会静默外发"
fi

if code_has "$GUARD" "val budget = guard.timeoutMs"; then
  pass "A11 守卫链按每个守卫自己的 timeoutMs 计时"
else
  fail "A11 守卫链仍用固定超时 —— 需要问用户的守卫会被 3 秒掐掉"
fi

echo ""
echo "[C] 出站通道的覆盖面"

# 漏一个 = 一条静默外发通道。2026-11-17 之前这里只有 web_search，
# 而 read_file → web_open("…?d=" + 内容) 全程零弹窗（web_* 在 ToolCatalog 里免审批）。
EGRESS_SET=$(awk '/val DEFAULT_EGRESS_TOOLS/,/^        \)$/' "$EGRESS")
for t in web_search web_open web_eval web_click web_type; do
  if grep -q "\"$t\"" <<<"$EGRESS_SET"; then
    pass "C1 出站通道包含 $t"
  else
    fail "C1 出站通道漏了 $t —— 它免审，可以直接把刚读到的内容送出去"
  fi
done

# http_request 有自己的逐域审批，塞进出站集合会让同一个动作弹两次框。
# 重复确认是负价值：用户会学会无脑点「允许」。
if grep -q '"http_request"' <<<"$EGRESS_SET"; then
  fail "C2 http_request 被塞进出站通道 —— 同一动作会连弹两次审批"
else
  pass "C2 http_request 不在出站通道（走自己的逐域审批）"
fi

if code_has "$EGRESS" "val taintedNow"; then
  pass "C3 守卫把「本回合读过私有内容」暴露给审批层"
else
  fail "C3 守卫不暴露 taintedNow —— 审批层补不了逐域记忆的盲区"
fi

if code_has "$CHAT_VM" "egressGuard.taintedNow" &&
   code_has "$CHAT_VM" "memorizable = false"; then
  pass "C4 读过私有内容时，外发审批降级为不可记忆"
else
  fail "C4 审批层没按 tainted 降级 —— 「上次允许过这个域名」会静默放行外发"
fi

# 绕过的是三个快路径：档位免问、会话白名单、截图回合缓存。
# 只绕一个不够——用户在 AUTO 档 + 勾过「始终允许」时，另外两个照样放行。
n=$(grep -c 'if (!egressNow &&' "$CHAT_VM")
if [ "${n:-0}" -ge 2 ]; then
  pass "C5 免打扰快路径（档位 / 白名单 / 截图缓存）全部让位于外发确认"
else
  fail "C5 只绕了 ${n:-0} 个快路径 —— 剩下的照样能把内容静默送出去"
fi

echo ""
echo "[B] 行为：真编真跑（11 个纯 Kotlin 源文件）"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
# 编译器的**启动**依赖：kotlin-compiler-embeddable 2.x 在 JVM 上初始化参数解析时会加载
# kotlin.reflect.jvm.ReflectJvmMapping，缺了它 `java -cp` 直接 NoClassDefFoundError，
# 看起来像「编译失败」。1.9.x 不需要，加上对旧版无害（-no-reflect 只管编译产物）。
KREFLECT=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-reflect" "kotlin-reflect-*.jar")
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
DEPS="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"
# 编译器进程自己也要能看见 DEPS：embeddable 2.x 在 JVM 上初始化时直接用到
# kotlinx.coroutines（CoreApplicationEnvironment），只放进 -classpath 不够。
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN${KREFLECT:+:$KREFLECT}:$DEPS"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
  "$CORE/AgentContract.kt" \
  "$CORE/ToolResult.kt" \
  "$CORE/ToolLimits.kt" \
  "$CORE/agent/ToolDescriptor.kt" \
  "$GUARD" \
  "$SEAMS" \
  "$EGRESS" \
  "$CORE/agent/ApprovalPreview.kt" \
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
