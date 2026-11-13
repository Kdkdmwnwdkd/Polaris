#!/usr/bin/env bash
# 音频占用仲裁（MicClaim）与打断仲裁（InterruptPolicy）验证。
#
# 分两段：
#   [A] 结构与接线：分层有没有被破坏、接线点有没有真的接上 —— 行为测试测不到
#       「定义了却没人调」，而没人调的仲裁器跑一万条断言也全绿。
#   [B] 行为：真编真跑两个纯 Kotlin 模块，验状态迁移与规则表。
#
# 为什么这两个模块能真跑：它们零 Android 依赖（日志出口是构造参数注入的），
# 手列 2 个源文件即可编译，任何装了 kotlinc 的机器都能跑，不会因「没编过 APK」而 SKIP。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core
MIC="$CORE/voice/MicClaim.kt"
POLICY="$CORE/voice/InterruptPolicy.kt"
ARBITER=$(find app/src/main -name "VoiceArbiter.kt" 2>/dev/null | head -1)
SPEAK_TOOL=$(find app/src/main -name "SpeakTool.kt" 2>/dev/null | head -1)
CHAT_SCREEN=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt
SPEAKER=app/src/main/java/com/beichen/polaris/audio/TextSpeaker.kt
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
GEN=app/src/main/java/com/beichen/polaris/ui/GenerationRunController.kt

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 结构：分层与接线"

# core 是纯 Kotlin 层。一旦 voice 包 import android，它就不能被 harness 编译，
# 于是这套仲裁逻辑会退化成「只能靠真机试」——而它恰恰是那种错了不报错的逻辑。
if grep -qE '^import android' "$MIC" "$POLICY" 2>/dev/null; then
  fail "A1 core/voice 引入了 android.* —— 仲裁逻辑将无法被 harness 独立验证"
else
  pass "A1 core/voice 零 Android 依赖（可独立编译）"
fi

# 反向依赖：core 不能知道 app 层的 TextSpeaker / VoiceArbiter 存在。
if grep -qE '^import com\.beichen\.polaris\.(audio|tools|ui)' "$MIC" "$POLICY" 2>/dev/null; then
  fail "A2 core/voice 反向依赖了 app 层 —— 分层倒置"
else
  pass "A2 core/voice 不依赖 app 层"
fi

# 三个持有者的优先级必须两两不同：相等 = 谁都不抢，等于没有优先级。
prio=$(grep -oE '(Wake|Speak|Capture)\([0-9]+\)' "$MIC" | grep -oE '[0-9]+' | sort -u | wc -l | tr -d ' ')
if [ "$prio" = "3" ]; then
  pass "A3 三个持有者优先级两两不同"
else
  fail "A3 优先级只有 $prio 种取值 —— 相等优先级会导致「谁都不抢」"
fi

# 裁决只有三种形态。加第四种（AcceptWithWarning 之类）等于把决策责任推回调用方。
# 按声明名统计而不是按行尾的 `: InterruptDecision`：带参数的 data class 中，
# 父类型写在右括号之后，行内匹配会漏。
extra=$(grep -cE '^[[:space:]]*(internal )?(data )?(object|class) (Accept|AcceptAfter|Reject)\b' "$POLICY" || true)
if [ "${extra:-0}" -eq 3 ]; then
  pass "A4 InterruptDecision 恰好三种形态"
elif [ "${extra:-0}" -gt 3 ]; then
  fail "A4 InterruptDecision 有 $extra 种形态（应为 3）—— 多出来的分支会把「怎么办」推回调用方"
else
  fail "A4 InterruptDecision 只有 ${extra:-0} 种形态（应为 3）—— 分支缺失"
fi

# 接线：语音输入必须先过仲裁，否则「播报中点麦克风会录到自己的声音」这个
# 真机上能复现的缺陷依然存在（改造前就是这个样子）。
if [ -n "$CHAT_SCREEN" ] && grep -qF "beginVoiceCapture(" "$CHAT_SCREEN"; then
  pass "A5 语音输入前调用 beginVoiceCapture（先停播报、再请求麦克风）"
else
  fail "A5 语音输入没有过仲裁 —— 播报中录音会把播报声录进去"
fi

# speak 工具必须走仲裁后的出口，否则模型可以在用户说话时插播。
# 断言构造参数而不是「全文没有 TextSpeaker」：注释里为了解释「为什么不再直连」
# 必然会出现这个词，按全文匹配会误伤（这个坑和 R-13 那次一样）。
if [ -n "$SPEAK_TOOL" ] && grep -qE 'class SpeakTool\(private val [a-z]+: VoiceArbiter\)' "$SPEAK_TOOL"; then
  pass "A6 SpeakTool 持有 VoiceArbiter（走仲裁出口）"
else
  fail "A6 SpeakTool 仍直连 TextSpeaker —— 绕过了音频仲裁"
fi

# 没有 stop 就永远停不下来；TTS 的 QUEUE_FLUSH 只在新 speak 时生效，不是停止。
if grep -qE 'fun stop\(\)' "$SPEAKER"; then
  pass "A7 TextSpeaker 提供 stop()"
else
  fail "A7 TextSpeaker 没有 stop() —— 「停止播报」无处可落"
fi

# 播报状态必须可观察，否则 Activity.speaking 永远是 false，仲裁表第一维永远为空。
if grep -qE 'val speaking: StateFlow<Boolean>' "$SPEAKER"; then
  pass "A8 TextSpeaker 暴露 speaking 状态流"
else
  fail "A8 TextSpeaker 没有可观察的播报态 —— 仲裁器判断不出「正在说话」"
fi

# 仲裁器有裁决能力，但裁决必须有人问。send() 里不问，这张规则表就只是文档。
if grep -qF "InterruptPolicy.decide(" "$VM"; then
  pass "A9 ChatViewModel.send() 在运行中会问仲裁器"
else
  fail "A9 send() 没有询问 InterruptPolicy —— 规则表是死代码，「发送即打断」不生效"
fi

# 打断要等旧轮真收尾，否则旧轮的 finish 会抹掉新一轮的运行态（见 cancelAndWait 注释）。
if grep -qF "cancelAndWait(" "$VM" && grep -qF "cancelAndWait(" "$GEN"; then
  pass "A10 打断走 cancelAndWait（不是裸 cancel）"
else
  fail "A10 打断没有等待旧轮收尾 —— 会出现「运行态被旧轮抹掉」或两轮重叠"
fi

# 运行中只有「停止」键的话，send 的打断逻辑在 UI 上根本触达不到。
if grep -qF "interrupting = true" "$CHAT_SCREEN"; then
  pass "A11 运行中输入非空时给出发送键（打断入口可达）"
else
  fail "A11 运行中仍然只有停止键 —— 「发送即打断」用户按不到"
fi

echo ""
echo "[B] 行为：真编真跑（2 个纯 Kotlin 源文件）"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANN:-}" ] || JB_ANN="$KC_HOME/lib/annotations.jar"

if [ ! -f "$KOTLINC" ] || [ ! -f "$STDLIB" ] || [ -z "${COROUTINES:-}" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin 编译器或协程构件" >&2
  exit 3
fi

OUT=/tmp/qa-voice-arbiter
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN"
DEPS="$STDLIB:$COROUTINES"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
  "$MIC" \
  "$POLICY" \
  scripts/qa/VoiceArbiterVerification.kt \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -d "$OUT/com" ]; then
  fail "B 编译失败（上面是输出）—— 行为断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$DEPS" VoiceArbiterVerificationKt)
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
