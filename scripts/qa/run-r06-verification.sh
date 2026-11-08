#!/usr/bin/env bash
# R-06「中断后残留执行中工具卡」行为验证。
#
# 为什么这条不能只做源码 grep：缺陷的全部内容是「同一张卡在两个时间点被写成两种状态」，
# grep 只能证明函数存在，证明不了「切走再切回那张卡显示什么」。所以这里直接编译真实的
# ConversationStore / ConversationProjection / GenerationRunController 跑一遍。
#
# 与其它 harness 的区别：**不需要 AGP 产物**（编译的是源码本身），因此沙箱里也能真跑，
# 而不是 SKIP。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/R06Verification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
SER_PLUGIN=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable" "kotlin-serialization-compiler-plugin-embeddable-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

# 回落到本机 kotlinc 发行版（沙箱 / 净机器）
[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${SER_PLUGIN:-}" ] || SER_PLUGIN="$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar"
[ -n "${COROUTINES:-}" ] || COROUTINES="$KC_HOME/lib/kotlinx-coroutines-core-jvm.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"
[ -n "${SER_JSON:-}" ] || SER_JSON="$KC_HOME/lib/kotlinx-serialization-json-jvm.jar"
[ -n "${SER_CORE:-}" ] || SER_CORE="$KC_HOME/lib/kotlinx-serialization-core-jvm.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"
need "kotlinx-coroutines-core-jvm" "$COROUTINES"
need "kotlinx-serialization-json-jvm" "$SER_JSON"
need "kotlinx-serialization-core-jvm" "$SER_CORE"
need "序列化编译器插件" "$SER_PLUGIN"

OUT=/tmp/qa-r06
rm -rf "$OUT"; mkdir -p "$OUT"

RUNTIME="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS:$COROUTINES"

# 被测文件全部是纯 JVM（无 Android / Compose 依赖），这是它们能在这里真跑的前提。
SOURCES=(
  "$SRC_CORE/core/AgentContract.kt"
  "$SRC_CORE/core/UiItem.kt"
  "$SRC_CORE/core/ToolResult.kt"
  "$SRC_CORE/core/ConversationStore.kt"
  # W-RT9：存储层现在调 ConversationArchive（同包另一文件），不列进来会编译失败。
  "$SRC_CORE/core/ConversationArchive.kt"
  "$SRC_CORE/core/ConversationShard.kt"
  "$SRC_CORE/core/lifecycle/AppBackgroundHooks.kt"
  "$SRC_CORE/core/ConversationBranch.kt"
  "$SRC_CORE/core/io/AtomicJsonFile.kt"
  "$SRC_CORE/core/telemetry/StoreWriteTrace.kt"
  "$SRC_CORE/core/telemetry/TraceSink.kt"
  "$SRC_CORE/core/llm/JsonSafe.kt"
  "$SRC/ui/ConversationProjection.kt"
  "$SRC/ui/GenerationRunController.kt"
)
for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$RUNTIME" \
  -Xplugin="$SER_PLUGIN" \
  -d "$OUT" -nowarn \
  "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
if [ ! -d "$OUT/com" ]; then
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

# 行为用例：输出透传（失败时带清单），同时取出它自己的计数
BODY=$(java -cp "$OUT:$RUNTIME" R06VerificationKt)
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线与文案（行为用例覆盖不到 Compose / ViewModel 那一层） ───────────
# 这三处只能静态断言：ChatViewModel 的构造点与 ChatScreen 的状态渲染都拖 Compose 依赖，
# 沙箱里编不动。但「收口函数写好了、没人调用」正是本轮最容易复发的那种缺陷。
gp=0
gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线与文案单点"
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
SCREEN=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt
UIITEM=core/src/main/java/com/beichen/polaris/core/UiItem.kt

if grep -q 'onSettling = { owner -> projection.settleInterrupted(owner) },' "$VM"; then
  g_good "G1 收尾时调用 projection.settleInterrupted（owner 传入）"
else
  g_bad "G1 ChatViewModel 没接 onSettling —— 收口函数写好了但没人调用"
fi

if grep -q 'interrupted -> ToolStatus.INTERRUPTED' "$SCREEN"; then
  g_good "G2 中断态在 status() 里单列（不混进「已失败」）"
else
  g_bad "G2 ChatScreen 缺 interrupted 分支 —— 中断卡会被渲染成已完成/已失败"
fi

if grep -q 'ToolStatus.INTERRUPTED -> .*to "已中断"' "$SCREEN"; then
  g_good "G3 状态文案「已中断」+ 中性色（颜色与文案同源）"
else
  g_bad "G3 ChatScreen 缺「已中断」文案"
fi

if grep -q 'val interrupted: Boolean = false' "$UIITEM"; then
  g_good "G4 UiItem.Tool.interrupted 有默认值（旧构造点零改动）"
else
  g_bad "G4 UiItem.Tool 缺 interrupted 字段或没给默认值"
fi

# R-06 的**根因**不在收口函数，而在这行：落库曾经被绑在「显示命中」的条件上。
# 只加收口、不拆这个 if，切走期间完成的工具仍会先被写成占位、再在收尾时被误判成
# 「已中断」——症状从「永远转圈」变成「明明跑完了却说没跑完」，更难解释。
if grep -q 'if (projection.finishTool(ev.message.id, done)) appendToStore(done)' "$VM"; then
  g_bad "G5 ToolFinished 仍把落库绑在显示命中上 —— 切走后终态永远不落库"
elif ! grep -q '^ *appendToStore(done)$' "$VM"; then
  g_bad "G5 ToolFinished 没有无条件落库语句"
else
  g_good "G5 ToolFinished 落库与显示解耦（无条件 appendToStore）"
fi

# 文案单点：同一句话出现两处，迟早漂移成「一边说未完成、一边说已失败」
note_defs=$(grep -rl '该调用未完成' app/src/main core/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$note_defs" = "1" ]; then
  g_good "G6 中断文案只有一处定义（TOOL_INTERRUPTED_NOTE）"
else
  g_bad "G6 中断文案出现在 $note_defs 个文件里 —— 会漂移"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
# run-all 用**最后一行**判非零失败，所以合计必须压轴
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
