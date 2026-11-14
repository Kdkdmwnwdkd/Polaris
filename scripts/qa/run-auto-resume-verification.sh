#!/usr/bin/env bash
# 自动续跑（进程被杀后，冷启动替用户接着做）验证。
#
# 背景：`AgentRunService` 一直在，但它的边界写着「进程真被杀时，任务**不会**自动续跑」。
# 用户回来只能看到一句「上次中断在第 3 步，可以说继续」，然后自己打字。
# 本功能把这句话变成真的动作——但每跑一次就是一次 LLM 请求，所以判定必须极保守。
#
# 分两段：
#   [A] 结构：调用点、顺序、默认开关、纯 Kotlin 边界。防的是「以后有人把接线改回去」——
#       这类回退**照样编译、照样跑**，只是功能静默失效，行为测试测不到。
#   [B] 行为：真编真跑决策表 + 熔断端到端。含单字段对照（B1 vs B7），
#       证明「正在跑就不插队」这条判断真的在起作用。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

POLICY=core/src/main/java/com/beichen/polaris/core/agent/AutoResumePolicy.kt
STORE=core/src/main/java/com/beichen/polaris/core/agent/AgentRunStateStore.kt
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
CFG=core/src/main/java/com/beichen/polaris/core/ConfigStore.kt
VERIFY=scripts/qa/AutoResumeVerification.kt

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 结构：接线、顺序、默认值"

for src in "$POLICY" "$STORE" "$VM" "$CFG" "$VERIFY"; do
  [ -f "$src" ] || { fail "找不到 $src —— 文件改名了？本 harness 失效"; echo ""; echo "$p 通过 / $f 失败"; exit 1; }
done
pass "A1 五个文件都在位"

# core 必须零 Android 依赖，否则这条策略不能被 JVM harness 真跑（只能 grep，等于假绿）。
if grep -qE '^import android\.' "$POLICY"; then
  fail "A2 AutoResumePolicy 引入了 android.* —— 决策逻辑就不能纯 JVM 跑了"
else
  pass "A2 决策器仍是纯 Kotlin（无 android import）"
fi

if grep -q 'AutoResumePolicy.decide(' "$VM"; then
  pass "A3 ChatViewModel 真的调用了决策器（不是写了个没人用的类）"
else
  fail "A3 ChatViewModel 没有调用 AutoResumePolicy.decide —— 功能没接上"
fi

# 顺序：必须先记熔断计数，再发请求。反过来则「发出去那一轮」不计入，熔断永远差一轮。
MARK_LINE=$(grep -n 'agentRunStateStore.markAutoResumeAttempt()' "$VM" | head -1 | cut -d: -f1)
SEND_AFTER=$(awk -v s="${MARK_LINE:-0}" 'NR>s && /^        send\(\)$/ {print "yes"; exit}' "$VM")
if [ -n "$MARK_LINE" ] && [ "${SEND_AFTER:-}" = "yes" ]; then
  pass "A4 先记熔断计数、再 send（顺序反了会漏计一轮）"
else
  fail "A4 markAutoResumeAttempt 与 send 的顺序不对（mark=${MARK_LINE:-无}）"
fi

# 最容易被“优化”掉的一行：send 里新造的 AgentRunState 会整体覆盖磁盘状态，
# 不带这个字段就等于每轮把计数清零。
if grep -q 'autoResumeAttempts = priorState?.autoResumeAttempts' "$VM"; then
  pass "A5 send 里把熔断计数随状态搬过去（否则计数每轮复位）"
else
  fail "A5 send 的 save 没有保留 autoResumeAttempts —— 熔断形同虚设"
fi

if grep -q 'sp.getBoolean(KEY_AUTO_RESUME, true)' "$CFG"; then
  pass "A6 自动续跑默认开启（默认关等于没做这个功能）"
else
  fail "A6 开关默认值不是 true —— 功能默认不可见"
fi

if grep -q 'fun isAutoResumeEnabled' "$CFG" && grep -q 'fun setAutoResumeEnabled' "$CFG"; then
  pass "A7 开关可读可写（用户能关掉，不然就是偷偷花钱）"
else
  fail "A7 开关缺 getter 或 setter"
fi

# 时序：自动续跑必须挂在类体末尾那个 init（所有字段已初始化）。
# 挂在前面的 init 里会读到未初始化的 _activeConversationId，低端机直接 NPE（2026-11-09 复现过）。
if grep -q 'runStateReady.await()' "$VM"; then
  pass "A8 自动续跑等对账结果再执行（不抢在属性初始化之前）"
else
  fail "A8 没有 await runStateReady —— 可能在字段初始化前执行"
fi

if grep -q 'runStateReady.complete(interrupted)' "$VM"; then
  pass "A9 对账结论必定 complete（异常路径也算，否则 await 永久挂起）"
else
  fail "A9 runStateReady 没有无条件 complete —— 异常时功能静默失效"
fi

# 代发的那句话必须在 isResumeIntent 白名单里，否则续跑上下文不拼进去，
# 「自动续跑」退化成「把两个字发一遍」。
if grep -q 'const val AUTO_RESUME_TEXT = "继续"' "$VM"; then
  pass "A10 代发文本是「继续」（在白名单内，能触发续跑上下文）"
else
  fail "A10 AUTO_RESUME_TEXT 不是「继续」—— 续跑上下文不会拼进请求"
fi

if grep -q 'buildResumeContext()' "$VM"; then
  pass "A11 续跑上下文仍在（功能没有绕开已有的 C 批逻辑）"
else
  fail "A11 找不到 buildResumeContext —— 续跑只剩一句空话"
fi

# 自动续跑要往输入框写「继续」。聊天页输入框自动聚焦，用户很可能在冷启动后
# 几百毫秒内已经在打字——那一下会把敲了一半的字整个清掉。必须让决策器知道这件事。
if grep -q 'inputBlank = input.value.isBlank()' "$VM"; then
  pass "A12 判定了输入框是否为空（否则会清掉用户正在打的字）"
else
  fail "A12 没有传 inputBlank —— 用户输入到一半的字会被自动续跑清空"
fi

echo ""
echo "[B] 行为：真编真跑（决策表 + 熔断端到端）"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-1.9*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "*.jar")
SER_PLUGIN=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable" "*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANN:-}" ] || JB_ANN="$KC_HOME/lib/annotations.jar"
# 序列化构件只在本机发行版里有；缓存缺失时优先用它，实在没有才 SKIP。
[ -n "${SER_JSON:-}" ] || SER_JSON="$KC_HOME/lib/kotlinx-serialization-json-jvm.jar"
[ -n "${SER_CORE:-}" ] || SER_CORE="$KC_HOME/lib/kotlinx-serialization-core-jvm.jar"
[ -n "${SER_PLUGIN:-}" ] || SER_PLUGIN="$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar"

if [ ! -f "$KOTLINC" ] || [ ! -f "$STDLIB" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin 编译器构件（$KOTLINC）" >&2
  exit 3
fi
if [ ! -f "${SER_JSON:-}" ] || [ ! -f "${SER_CORE:-}" ] || [ ! -f "${SER_PLUGIN:-}" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 kotlinx-serialization 构件（$SER_JSON）" >&2
  exit 3
fi

OUT=/tmp/qa-auto-resume
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN"
DEPS="$STDLIB:$SER_JSON:$SER_CORE"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -Xplugin="$SER_PLUGIN" -classpath "$DEPS" -d "$OUT" -nowarn \
  "core/src/main/java/com/beichen/polaris/core/io/AtomicJsonFile.kt" \
  "$STORE" \
  "$POLICY" \
  "$VERIFY" \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -f "$OUT/AutoResumeVerificationKt.class" ]; then
  fail "B 编译失败（上面是输出）—— 行为断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$DEPS" AutoResumeVerificationKt)
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
