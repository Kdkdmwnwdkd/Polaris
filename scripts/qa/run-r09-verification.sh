#!/usr/bin/env bash
# R-09「重试导致已上屏文本重放」验证。
#
# [行为] 编译真实的 RetryVisibility.kt 跑：阈值边界与「可重试 × 可见输出」四象限。
# [接线] RealAgentLoop 拖 Android/OkHttp，沙箱编不动，只能静态断言：
#        闸门到底有没有被调用、正文增量到底有没有进计数器、失败分支有没有留残稿。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/R09Verification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"

OUT=/tmp/qa-r09
RUNTIME="$STDLIB"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

SOURCES=("$SRC_CORE/core/agent/RetryVisibility.kt")
for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$RUNTIME" \
  -d "$OUT" -nowarn \
  "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
if [ ! -d "$OUT/com" ]; then
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

BODY=$(java -cp "$OUT:$RUNTIME" R09VerificationKt)
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线"
LOOP=$SRC_CORE/core/agent/RealAgentLoop.kt
VM=$SRC/ui/ChatViewModel.kt

# G1：闸门必须真的被调用。纯函数写得再对，没人调等于没修。
if grep -q 'retryAllowed(retryable, attemptOut)' "$LOOP"; then
  g_good "G1 RealAgentLoop 用 retryAllowed 决定是否重试"
else
  g_bad "G1 闸门没有被调用——重试逻辑仍只看异常类型"
fi

# G2：只有正文增量进计数器。推理增量（ReasoningDelta）不进——它不参与重放判断，
# 计进去会让「思考很久但正文没出」的失败被误判成不可重试。
if grep -q 'is ChatStreamEvent.TextDelta -> { onTextDelta?.invoke(event.text); attemptOut.add(event.text) }' "$LOOP"; then
  g_good "G2 只累加正文增量（ReasoningDelta 不计数）"
else
  g_bad "G2 正文增量没有进计数器（阈值永远为 0，等于没修）"
fi

# G3：每次 attempt 必须重新计数。跨 attempt 累积会让第 2 次尝试就误判「已可见」。
if [ "$(grep -c 'attemptOut.reset()' "$LOOP")" = "1" ]; then
  g_good "G3 每次 attempt 起始重置计数（单点）"
else
  g_bad "G3 attemptOut.reset() 出现 $(grep -c 'attemptOut.reset()' "$LOOP") 次（应为 1）"
fi

# G4：失败分支必须保留残稿。只加闸门不留残稿，用户会从「看到重复」变成「什么都看不到」。
if grep -q 'R-09：已生成的部分不能丢' "$VM" && grep -q 'partialDraft?.trim()?.takeIf' "$VM"; then
  g_good "G4 失败分支保留已生成的部分"
else
  g_bad "G4 失败分支仍在丢残稿"
fi

# G5：常量只有一处定义。两处各写一个阈值，改一处忘另一处就是行为漂移。
n=$(grep -rl 'const val RETRY_VISIBLE_CHARS' app/src/main core/src/main --include='*.kt' | wc -l | tr -d ' ')
if [ "$n" = "1" ]; then
  g_good "G5 阈值单点定义"
else
  g_bad "G5 RETRY_VISIBLE_CHARS 出现在 $n 个文件里"
fi

# ── 反向验证（可选，会改源码） ─────────────────────────────────────────────
if [ "${AK_ALLOW_SOURCE_MUTATION:-}" = "1" ]; then
  echo ""
  echo "[M] 源码变异：让闸门恒放行（忽略可见输出），B 段必须变红"
  TARGET="$SRC_CORE/core/agent/RetryVisibility.kt"
  BAK=$(mktemp); cp "$TARGET" "$BAK"
  restore() { [ -f "$BAK" ] && cp "$BAK" "$TARGET" && rm -f "$BAK"; }
  trap restore EXIT

  python3 - "$TARGET" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
s2 = s.replace("    retryable && !output.visible", "    retryable")
if s2 == s:
    sys.exit(1)
open(p, 'w').write(s2)
print("MUTATED")
PY
  if [ $? -ne 0 ]; then
    g_bad "M1 变异注入失败（结构变了？）"
  else
    rm -rf "$OUT"; mkdir -p "$OUT"
    java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -cp "$RUNTIME" -d "$OUT" -nowarn \
      "${SOURCES[@]}" "$HARNESS" >/dev/null 2>&1
    OUT2=$(java -cp "$OUT:$RUNTIME" R09VerificationKt); rc2=$?
    if [ $rc2 -eq 0 ]; then
      g_bad "M1 闸门失效后断言仍然绿——断言没测到点子上"
    elif echo "$OUT2" | grep -q 'B2'; then
      g_good "M1 闸门失效后 B2 变红（exit=$rc2）"
    else
      g_bad "M1 变红了但红的不是可见输出相关断言"
    fi
    restore
  fi
else
  echo ""
  echo "  (跳过源码变异：设 AK_ALLOW_SOURCE_MUTATION=1 可做反向验证)"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
