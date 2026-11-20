#!/usr/bin/env bash
# 会话编辑簇（ConversationEditor）行为验证。
#
# 为什么不能只 grep：这一簇判定的全是「同一批状态按不同顺序改」——删掉当前会话之后
# drafts.json 里还有没有它的草稿、新建时工具组重置发生在 create 之前还是之后、
# 同 id 切换会不会把输入框内容又存又读一遍。grep 只能证明某条 if 存在。
#
# 与依赖 AGP 产物的 harness 的区别：被测文件（ConversationStore / DraftStore /
# ConversationEditor）都是纯 JVM，本脚本直接编译源码，沙箱里也能真跑而不是 SKIP。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/ConversationEditorVerification.kt

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

OUT=/tmp/qa-conv-editor-build
rm -rf "$OUT"; mkdir -p "$OUT"

RUNTIME="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS:$COROUTINES"

# 被测文件全部是纯 JVM（无 Android / Compose 依赖），这是它们能在这里真跑的前提。
SOURCES=(
  "$SRC_CORE/core/AgentContract.kt"
  "$SRC_CORE/core/UiItem.kt"
  "$SRC_CORE/core/ToolResult.kt"
  "$SRC_CORE/core/ConversationStore.kt"
  "$SRC_CORE/core/ConversationArchive.kt"
  "$SRC_CORE/core/ConversationShard.kt"
  "$SRC_CORE/core/lifecycle/AppBackgroundHooks.kt"
  "$SRC_CORE/core/ConversationBranch.kt"
  "$SRC_CORE/core/io/AtomicJsonFile.kt"
  "$SRC_CORE/core/telemetry/StoreWriteTrace.kt"
  "$SRC_CORE/core/telemetry/TraceSink.kt"
  "$SRC_CORE/core/llm/JsonSafe.kt"
  "$SRC_CORE/core/DraftStore.kt"
  "$SRC/ui/ConversationEditor.kt"
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

BODY=$(java -cp "$OUT:$RUNTIME" ConversationEditorVerificationKt)
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线：行为用例覆盖不到 VM 那一层（它拖 Compose，沙箱编不动） ─────────
gp=0
gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线：VM 只转发，实现只有一份"
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
EDITOR=$SRC/ui/ConversationEditor.kt

# G1：旧实现必须彻底消失。留着影子 loadConversation，迟早有人再往里面加逻辑，
# 于是「草稿怎么存」又变成两处。
if grep -q "private fun loadConversation" "$VM"; then
  g_bad "G1 ChatViewModel 仍有 loadConversation 影子实现（迁移逻辑会分裂成两份）"
else
  g_good "G1 loadConversation 已删除（迁移只剩 ConversationEditor.open 一处）"
fi

# G2：六个入口都必须转发到同一个实例。
missing=""
for call in "editor.create()" "editor.switchTo(id)" "editor.delete(id)" \
            "editor.rename(id, title)" "editor.setPinned(id, pinned)" "editor.bindWorkspace(convId, dir)"; do
  grep -qF "$call" "$VM" || missing="$missing [$call]"
done
if [ -n "$missing" ]; then
  g_bad "G2 以下入口没接到 ConversationEditor：$missing —— 有入口绕过了草稿/守卫规则"
else
  g_good "G2 新建/切换/删除/重命名/置顶/绑目录六个入口都经 ConversationEditor"
fi

# G3：编辑类写操作不得绕过 Editor 直调 store。标题自动生成那两处 rename 属于
# 「消息域」（后台给非 active 会话命名），不在本簇范围，故只查这五个。
bypass=$(grep -nE 'conversationStore\.(create|delete|setPinned|setWorkspaceDir)\(|draftStore\.set\(' "$VM" || true)
if [ -n "$bypass" ]; then
  g_bad "G3 VM 绕过 Editor 直接改会话/草稿：$bypass"
else
  g_good "G3 VM 无绕过路径（create/delete/pinned/workspaceDir/draftStore.set 均不在 VM 里）"
fi

# G4：editor 的构造晚于它捕获的字段。Kotlin 按声明顺序初始化，顺序错会在构造期
# 拿到 null 或未就绪的 StateFlow——编译通过，运行时才炸（且往往只在冷启动路径上）。
line_editor=$(grep -n 'private val editor = ConversationEditor(' "$VM" | cut -d: -f1)
line_proj=$(grep -n 'private val projection = ConversationProjection(' "$VM" | cut -d: -f1)
line_gen=$(grep -n 'val generation: GenerationRunController' "$VM" | cut -d: -f1)
if [ -n "$line_editor" ] && [ -n "$line_proj" ] && [ -n "$line_gen" ] \
   && [ "$line_editor" -gt "$line_proj" ] && [ "$line_editor" -gt "$line_gen" ]; then
  g_good "G4 editor 声明晚于 projection / generation（初始化顺序）"
else
  g_bad "G4 editor 声明位置($line_editor) 早于依赖($line_proj/$line_gen) —— 构造期会拿到未就绪对象"
fi

# G5：删除必须走 adopt，不能走 switchTo。走 switchTo 会把输入框内容存回刚删掉的 id，
# 正是本轮修掉的那个孤儿草稿。这条用调用点判，不依赖注释。
del_block=$(sed -n "/fun deleteConversation(id: String)/,/^    }/p" "$VM")
if echo "$del_block" | grep -q 'editor.switchTo'; then
  g_bad "G5 删除路径调了 switchTo —— 会把输入框内容存回已删会话（孤儿草稿复发）"
elif echo "$del_block" | grep -q 'editor.delete(id)'; then
  g_good "G5 删除走 editor.delete（内部用 adopt，不保存旧草稿）"
else
  g_bad "G5 删除入口没有接到 editor.delete"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
# run-all 用**最后一行**判非零失败，所以合计必须压轴
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
