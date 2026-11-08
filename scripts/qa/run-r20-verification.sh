#!/usr/bin/env bash
# R-20「工具 schema 暴露不可用后端」验证。
#
# [行为] 编译真实的 TerminalIdentity.kt + ShellRoute.kt 跑 —— 它们零 Android 依赖，
#        所以在沙箱里也是真跑，不是 SKIP。断言分四组：
#          A schema 取值  B 可用性标注  C 双向一致（加新身份忘标注会红）  D 路由真值表
# [接线] ShellRunTool.execute 拖 Android 编不动，只能静态断言：
#        enum 到底有没有换成生成的、未实现分支在不在、有没有第二份字面量实现。
#
# 反向验证（可选）：AK_ALLOW_SOURCE_MUTATION=1 时把 schemaValues 改成「全部暴露」，
# A 组必须变红。默认只读。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/R20Verification.kt

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

OUT=/tmp/qa-r20
RUNTIME="$STDLIB"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

SOURCES=(
  "$SRC_CORE/core/terminal/backend/TerminalIdentity.kt"
  "$SRC_CORE/core/terminal/backend/ShellRoute.kt"
)

for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

compile_and_run() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -cp "$RUNTIME" \
    -d "$OUT" -nowarn \
    "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
  [ -d "$OUT/com" ] || return 1
  java -cp "$OUT:$RUNTIME" R20VerificationKt
}

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
TOOL=$SRC/tools/ShellTool.kt

# G1：enum 必须由枚举生成。还是 listOf("user","su",...) 就是没修。
if grep -q 'enum = TerminalIdentity.schemaValues()' "$TOOL"; then
  g_good "G1 schema enum 由 TerminalIdentity.schemaValues() 生成"
else
  g_bad "G1 ShellTool 仍在手写 enum 字面量"
fi

# G2：参数描述也不能写死。字面量一写，加新身份时它不会跟着变。
if grep -q 'TerminalIdentity.schemaValuesText()' "$TOOL"; then
  g_good "G2 参数说明由 schemaValuesText() 生成"
else
  g_bad "G2 参数说明仍是写死的取值列表"
fi

# G3：全仓不许再有那份四值字面量（两份真相就是漂移的起点）。
n=$(grep -rc 'listOf("user", "su", "shizuku", "ssh")' --include='*.kt' app/src/main core/src/main 2>/dev/null | grep -v ':0$' | wc -l | tr -d ' ')
if [ "$n" = "0" ]; then
  g_good "G3 全仓无四值字面量残留"
else
  g_bad "G3 仍有 $n 个文件写着 listOf(\"user\",\"su\",\"shizuku\",\"ssh\")"
fi

# G4：未实现的取值要当场拒绝，不能丢进候选链再失败。
if grep -q 'identity_not_implemented' "$TOOL" && grep -q 'declaredIdentity.usable' "$TOOL"; then
  g_good "G4 未实现身份有专门错误码（identity_not_implemented）"
else
  g_bad "G4 没有拒绝未实现身份的分支——su/ssh 仍会掉进候选链"
fi

# G5：路由判定只能有一份。还留在 ShellTool 里就是两份，迟早再漂一次。
if [ "$(grep -rc 'fun shouldUsePrivilegedShell' --include='*.kt' app/src/main core/src/main | grep -v ':0$' | wc -l | tr -d ' ')" = "1" ]; then
  g_good "G5 shouldUsePrivilegedShell 全仓只有一处定义"
else
  g_bad "G5 shouldUsePrivilegedShell 有重复定义"
fi

# G6：描述里不许再逐个点名后端顺序。那份顺序的真源在 providersFor，
# 描述里抄一份，插一个新后端就过期；模型读到过时顺序会做出错误预期。
if [ "$(grep -c 'proot 容器 → Shizuku' "$TOOL")" = "0" ]; then
  g_good "G6 描述不再点名具体后端顺序（改为按层级表述）"
else
  g_bad "G6 描述又把后端顺序写成枚举了"
fi

# ── 反向验证（可选，会改源码） ─────────────────────────────────────────────
if [ "${AK_ALLOW_SOURCE_MUTATION:-}" = "1" ]; then
  echo ""
  echo "[M] 源码变异：schemaValues 改成「全部暴露」，A2/A3 必须变红"
  TARGET="$SRC_CORE/core/terminal/backend/TerminalIdentity.kt"
  BAK=$(mktemp); cp "$TARGET" "$BAK"
  restore() { [ -f "$BAK" ] && cp "$BAK" "$TARGET" && rm -f "$BAK"; }
  trap restore EXIT

  python3 - "$TARGET" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = "fun schemaValues(): List<String> = entries.filter { it.usable }.map { it.wire }"
new = "fun schemaValues(): List<String> = entries.map { it.wire }  // MUTATED"
if old not in s:
    sys.exit(1)
open(p, "w").write(s.replace(old, new))
print("MUTATED")
PY
  if [ $? -ne 0 ]; then
    g_bad "M1 变异注入失败（结构变了？）"
  else
    OUT2=$(compile_and_run)
    # 判据是「结果行里的失败数」，不是 java 的退出码：harness 刻意不 exitProcess
    # （那样「提前退出」和「断言失败」在日志里长得一样），所以 rc 恒为 0。
    f2=$(echo "$OUT2" | grep -oE '[0-9]+ 失败' | tail -1 | tr -dc '0-9')
    if [ "${f2:-0}" -eq 0 ]; then
      g_bad "M1 全部暴露后断言仍然绿——断言没测到点子上"
    elif echo "$OUT2" | grep -qE 'FAIL  A[23]'; then
      g_good "M1 全部暴露后 A 组变红（$f2 条失败）"
    else
      g_bad "M1 变红了但红的不是 schema 取值断言"
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
