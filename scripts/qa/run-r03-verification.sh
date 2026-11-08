#!/usr/bin/env bash
# R-03「审批超时写在 Composable 里」验证。
#
# 分两段：
#   [行为] 编译真实的 core/agent/ApprovalDeadline.kt 跑一遍——超时、取消、已完成
#          这三条语义 grep 证明不了，只能真跑。不依赖 AGP 产物，沙箱里也真执行。
#   [接线] ViewModel / ChatScreen 拖 Compose 依赖，沙箱编不动，只能静态断言：
#          超时是不是真的接上了、UI 有没有又偷偷变成第二个超时实现。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/R03Verification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${COROUTINES:-}" ] || COROUTINES="$KC_HOME/lib/kotlinx-coroutines-core-jvm.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"
need "kotlinx-coroutines-core-jvm" "$COROUTINES"

OUT=/tmp/qa-r03
rm -rf "$OUT"; mkdir -p "$OUT"

RUNTIME="$STDLIB:$COROUTINES"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS:$COROUTINES"

SOURCES=("$SRC_CORE/core/agent/ApprovalDeadline.kt")
for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$RUNTIME" \
  -d "$OUT" -nowarn \
  "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
if [ ! -d "$OUT/com" ]; then
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

BODY=$(java -cp "$OUT:$RUNTIME" R03VerificationKt)
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线：行为对不代表接上了 ───────────────────────────────────────────
gp=0
gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线与单点"
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
SCREEN=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt
DEADLINE=$SRC_CORE/core/agent/ApprovalDeadline.kt

# G1：VM 必须真的走 awaitApprovalDecision。函数写得再对，没人调用等于没修。
if grep -qE '^ *val ok = try \{ awaitApprovalDecision\(deferred\) \} finally' "$VM"; then
  g_good "G1 requestApproval 经 awaitApprovalDecision 等待（超时归 ViewModel）"
elif grep -q 'deferred\.await()' "$VM"; then
  g_bad "G1 仍是裸 deferred.await() —— 超时只在弹窗倒计时里，切页即永久挂起"
else
  g_bad "G1 找不到 awaitApprovalDecision 调用点"
fi

# G2：UI 不能再自己实现一次超时。两处实现迟早漂移（弹窗说 30s、实际 15s 就拒）。
# 限定在 LaunchedEffect(request, visible) 块内：denyApproval 在按钮与 onDismiss 里还要用。
cd_block=$(awk '/LaunchedEffect\(request, visible\)/{f=1} f{print} f&&/^    \}$/{exit}' "$SCREEN")
if [ -z "$cd_block" ]; then
  g_bad "G2 找不到审批倒计时块（ChatScreen 结构变了，断言失效）"
elif echo "$cd_block" | grep -q 'denyApproval'; then
  g_bad "G2 倒计时块里仍在调 denyApproval —— 弹窗一走就没兜底了"
else
  g_good "G2 倒计时只做展示，不再自行拒绝"
fi

# G3：秒数必须由毫秒常量派生，不能另写一个 30。
if grep -q 'private val APPROVAL_TIMEOUT_SEC: Int = (APPROVAL_TIMEOUT_MS / 1000).toInt()' "$SCREEN"; then
  g_good "G3 弹窗秒数由 APPROVAL_TIMEOUT_MS 派生（不另写数字）"
else
  g_bad "G3 弹窗秒数没跟随常量 —— 改时限时只改一处会漂移"
fi

# G4：时限只有一处定义。
n=$(grep -rl 'const val APPROVAL_TIMEOUT_MS' app/src/main core/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$n" = "1" ] && grep -q 'const val APPROVAL_TIMEOUT_MS: Long = 30_000L' "$DEADLINE"; then
  g_good "G4 时限单点定义（30_000ms）"
else
  g_bad "G4 时限出现在 $n 个文件里，或数值不是 30_000L"
fi

# G5：finally 仍清 pending。超时路径若不清，下个工具进来会看到上一个的残留请求。
if grep -q 'finally { _pendingApproval.value = null }' "$VM"; then
  g_good "G5 超时/取消后 pending 一定清空"
else
  g_bad "G5 pending 清理丢了 —— 下一个工具会撞上残留审批"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
# run-all 用**最后一行**判非零失败，所以合计必须压轴
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
