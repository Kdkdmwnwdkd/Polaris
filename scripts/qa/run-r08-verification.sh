#!/usr/bin/env bash
# R-08「审批记忆粒度过粗」验证。
#
# [行为] 编译真实的 ShellCommandRules / ApprovalGrouping / ApprovalScope 跑 ——
#        「记住 npm install 之后 rm -rf 还要不要问」只有真跑能证明。不依赖 AGP 产物。
# [接线] ChatViewModel / ChatScreen 拖 Compose 与 Android，只能静态断言：
#        授权键到底有没有换掉、approveAlways 有没有看 memorizable。
#
# 反向验证（可选）：AK_ALLOW_SOURCE_MUTATION=1 时把 isDestructive 改成恒 false 重编译，
# 行为断言必须变红。默认只读。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/R08Verification.kt

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

OUT=/tmp/qa-r08
RUNTIME="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS:$COROUTINES"

SOURCES=(
  "$SRC_CORE/core/llm/JsonSafe.kt"
  "$SRC_CORE/core/agent/ShellCommandRules.kt"
  "$SRC_CORE/core/tool/ToolCatalog.kt"
  "$SRC_CORE/core/agent/ApprovalGrouping.kt"
  "$SRC_CORE/core/agent/ApprovalScope.kt"
)

compile_and_run() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -cp "$RUNTIME" \
    -Xplugin="$SER_PLUGIN" \
    -d "$OUT" -nowarn \
    "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
  [ -d "$OUT/com" ] || return 1
  java -cp "$OUT:$RUNTIME" R08VerificationKt
}

for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

BODY=$(compile_and_run)
if [ $? -ne 0 ] || [ -z "$BODY" ]; then
  echo "$BODY"
  echo "FAIL 编译或运行失败（上面是输出）"
  exit 1
fi
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
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
SCREEN=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt

# G1：授权键必须来自 ApprovalScope（它才知道命令内容）。仍用 groupOf(tool.name) 就是没修。
if grep -q 'val grant = ApprovalScope.of(tool.name, args)' "$VM"; then
  g_good "G1 审批键由 ApprovalScope 按入参计算"
else
  g_bad "G1 ChatViewModel 没走 ApprovalScope.of —— 还在按工具名分组"
fi

# G2：approveAlways 必须看 memorizable。只看 group 就写白名单，等于把 rm 记进去了。
if grep -q 'if (pending.grant.memorizable) sessionAllowed += pending.grant.key' "$VM"; then
  g_good "G2 approveAlways 对不可记忆档不写白名单"
else
  g_bad "G2 approveAlways 仍在无条件写 sessionAllowed"
fi

# G3：白名单判定要走 allows（它同时检查 memorizable）。直接 `key in sessionAllowed` 会漏掉防御。
if grep -q 'ApprovalScope.allows(grant, sessionAllowed)' "$VM"; then
  g_good "G3 放行判定走 ApprovalScope.allows"
else
  g_bad "G3 放行判定没走 allows"
fi

# G4：弹窗文案必须来自 grant。残留 groupLabel 说明 UI 还在读旧字段。
if grep -q 'request.grant.memoLabel' "$SCREEN" && ! grep -q 'request.groupLabel' "$SCREEN"; then
  g_good "G4 审批弹窗文案走 grant.memoLabel"
else
  g_bad "G4 弹窗文案没换（或仍在引用已删除的 groupLabel）"
fi

# G5：不可记忆时勾选框要禁掉，否则用户勾了个不会生效的框。
if grep -q 'askNoMore && request.grant.memorizable' "$SCREEN"; then
  g_good "G5 不可记忆档的勾选框被禁用"
else
  g_bad "G5 勾选框没有跟随 memorizable"
fi

# ── 反向验证（可选，会改源码） ─────────────────────────────────────────────
if [ "${AK_ALLOW_SOURCE_MUTATION:-}" = "1" ]; then
  echo ""
  echo "[M] 源码变异：把 isDestructive 改成恒 false，行为断言必须变红"
  TARGET="$SRC_CORE/core/agent/ShellCommandRules.kt"
  BAK=$(mktemp); cp "$TARGET" "$BAK"
  restore() { [ -f "$BAK" ] && cp "$BAK" "$TARGET" && rm -f "$BAK"; }
  trap restore EXIT

  python3 - "$TARGET" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
s2 = re.sub(r'fun isDestructive\(command: String\): Boolean \{',
            r'fun isDestructive(command: String): Boolean {\n        if (true) return false', s, count=1)
if s2 == s:
    sys.exit(1)
open(p, 'w').write(s2)
print("MUTATED")
PY
  if [ $? -ne 0 ]; then
    g_bad "M1 变异注入失败（结构变了？）"
  else
    OUT2=$(compile_and_run); rc2=$?
    if [ $rc2 -eq 0 ]; then
      g_bad "M1 危险判定失效后断言仍然绿——断言没测到点子上"
    elif echo "$OUT2" | grep -qE 'A 危险：rm|C4'; then
      g_good "M1 危险判定失效后 A/C 组变红（exit=$rc2）"
    else
      g_bad "M1 变红了但红的不是危险分类断言"
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
