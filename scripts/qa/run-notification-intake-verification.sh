#!/usr/bin/env bash
# 审计 F18：通知入库判据（自己发的不收）+ 定时任务通知正文不放任务描述。
#
# [行为] NotificationIntake 零依赖（只有 String 比较），直接编译真文件跑。
# [接线] 监听器与通知构造拖 Android，只能静态核对。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_APP=app/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/NotificationIntakeVerification.kt
INTAKE="$SRC_APP/automation/NotificationIntake.kt"
LISTENER="$SRC_APP/automation/AgentNotificationListener.kt"
NOTIFIER="$SRC_APP/workflow/WorkflowNotifier.kt"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() { [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }; }
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"

OUT=/tmp/qa-f18
rm -rf "$OUT"; mkdir -p "$OUT"

RUNTIME="$STDLIB"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

for f in "$INTAKE" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$RUNTIME" \
  -d "$OUT" -nowarn \
  "$INTAKE" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
if [ ! -d "$OUT/com" ]; then
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

BODY=$(java -cp "$OUT:$RUNTIME" NotificationIntakeVerificationKt)
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线 ───────────────────────────────────────────────────────────────
gp=0
gf=0
w_good() { echo "  PASS  $*"; gp=$((gp + 1)); }
w_bad()  { echo "  FAIL  $*"; gf=$((gf + 1)); }

echo ""
echo "[G] 接线"

# W1：监听器真的调了判据。判据写对、调用点没接，等于没修。
if grep -q 'NotificationIntake.shouldIngest(sbn.packageName, packageName)' "$LISTENER"; then
  w_good "W1 监听器用 shouldIngest 过滤来源"
else
  w_bad "W1 监听器没有调用 NotificationIntake.shouldIngest"
fi

# W2：判据必须在「落库之前」（NotificationStore.add 之前），否则只是算了一下。
if [ -n "$(awk '/shouldIngest/{s=NR} /NotificationStore.add/{a=NR} END{if (s && a && s<a) print "ok"}' "$LISTENER")" ]; then
  w_good "W2 判据在落库之前（先筛后存）"
else
  w_bad "W2 判据不在 NotificationStore.add 之前 —— 过滤发生在入库之后，等于没拦"
fi

# W3：定时任务通知不得把任务描述放进正文。
if grep -q 'setContentText(workflow.prompt' "$NOTIFIER"; then
  w_bad "W3 定时任务通知正文仍是任务描述（锁屏可见）"
else
  w_good "W3 定时任务通知正文不含任务描述"
fi

# W4：BigTextStyle 那条也是正文出口，容易漏（只改 setContentText 不够）。
if grep -q 'bigText(workflow.prompt' "$NOTIFIER"; then
  w_bad "W4 BigTextStyle 仍塞任务描述 —— 展开通知照样能看到"
else
  w_good "W4 展开态也不含任务描述"
fi

# W5：判据单点定义。
n=$(grep -rl 'fun shouldIngest' app/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$n" = "1" ]; then
  w_good "W5 shouldIngest 单点定义"
else
  w_bad "W5 shouldIngest 出现在 $n 个文件里"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
