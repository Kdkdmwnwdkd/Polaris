#!/usr/bin/env bash
# R-05「消息列表无 key」验证。
#
# [行为] 编译真实的 core/UiItem.kt 跑 —— key 的对错只有一种判法：插入一项后其它项的
#        身份变没变。这个 grep 证明不了，只能真跑。不依赖 AGP 产物。
# [接线] ChatScreen 拖 Compose 依赖，沙箱编不动，只能静态断言 itemsIndexed 真的传了 key。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/R05Verification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
SER_PLUGIN=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable" "kotlin-serialization-compiler-plugin-embeddable-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${COROUTINES:-}" ] || COROUTINES="$KC_HOME/lib/kotlinx-coroutines-core-jvm.jar"
[ -n "${SER_JSON:-}" ] || SER_JSON="$KC_HOME/lib/kotlinx-serialization-json-jvm.jar"
[ -n "${SER_CORE:-}" ] || SER_CORE="$KC_HOME/lib/kotlinx-serialization-core-jvm.jar"
[ -n "${SER_PLUGIN:-}" ] || SER_PLUGIN="$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"
need "kotlinx-serialization-json-jvm" "$SER_JSON"
need "序列化编译器插件" "$SER_PLUGIN"

OUT=/tmp/qa-r05
rm -rf "$OUT"; mkdir -p "$OUT"

RUNTIME="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS:$COROUTINES"

SOURCES=(
  "$SRC_CORE/core/AgentContract.kt"
  "$SRC_CORE/core/ToolResult.kt"
  "$SRC_CORE/core/UiItem.kt"
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

BODY=$(java -cp "$OUT:$RUNTIME" R05VerificationKt)
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线 ───────────────────────────────────────────────────────────────
gp=0
gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线"
SCREEN=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt

# G1：itemsIndexed 必须真的传 key，且 key 必须是**整表算过唯一性**的那一份。
# 审计 F17：只传 uiKey 不够——供应商复用 call.id 时会撞 key、整页崩。
# 不锁死 key lambda 的具体写法：`messageKeys[index]` 在工具卡插入的那一帧会越界
# （keys 与 items 跨帧，实测 IndexOutOfBounds 整页崩），现在带 getOrNull 回落。
# 断言要的是「key 取自 messageKeys」，不是「某个字符序列必须一字不差」。
if grep -qE 'itemsIndexed\(messages, key = .*messageKeys' "$SCREEN" \
   && grep -q 'messages.uiKeySeq()' "$SCREEN"; then
  g_good "G1 消息列表的 itemsIndexed 用的是整表唯一化后的 key"
elif grep -qE 'itemsIndexed\(messages\) *\{' "$SCREEN"; then
  g_bad "G1 itemsIndexed 仍无 key —— Compose 用下标当身份，插入即整表重建"
else
  g_bad "G1 找不到消息列表的 itemsIndexed（结构变了，断言失效）"
fi

# G2：key 函数只有一处实现。两处各写一版，改一处忘另一处就是身份漂移。
n=$(grep -rl 'fun UiItem.uiKey' app/src/main core/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$n" = "1" ]; then
  g_good "G2 uiKey 单点定义"
else
  g_bad "G2 uiKey 出现在 $n 个文件里"
fi

# G3：整表 key 的唯一化只有一处实现。两处各写一版，改一处忘另一处就是身份漂移。
n=$(grep -rl 'fun List<UiItem>.uiKeySeq' app/src/main core/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$n" = "1" ]; then
  g_good "G3 uiKeySeq 单点定义"
else
  g_bad "G3 uiKeySeq 出现在 $n 个文件里"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
