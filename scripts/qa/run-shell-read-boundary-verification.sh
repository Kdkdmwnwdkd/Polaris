#!/usr/bin/env bash
# F7「工作区沙箱对 shell 是句空话」验证（审计遗留项：三条高权限后端无路径边界）。
#
# [行为] 编译真实的 ShellCommandRules / ApprovalScope / ApprovalGrouping 跑 24 条断言：
#        受保护目录识别（相册 / 下载 / 应用私有目录）、前缀边界（/sdcardevil）、
#        `..` 穿越的规范化、授权键按目录分档、以及三条不变量——
#          ① 同意过 shell 组 ≠ 同意读相册；
#          ② 带重定向的读仍落「每次都问」，不会被读授权洗白；
#          ③ 换目录必须重问。
# [接线] ShellTool 拖 Android，只能静态断言：判据真的接在 needsApprovalFor 上、
#        ApprovalScope 真的产出 shell_read 键。判据写得再对，没接上就是白写。
#
# 为什么值得单独一个 runner：这条判据决定「一条提示注入能不能把相册读进上下文」，
# 而它的实现是纯函数，可以在无 Android 环境下逐条对拍。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/ShellReadBoundaryVerification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
SER_PLUGIN=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable" "kotlin-serialization-compiler-plugin-embeddable-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${SER_JSON:-}" ] || SER_JSON="$KC_HOME/lib/kotlinx-serialization-json-jvm.jar"
[ -n "${SER_CORE:-}" ] || SER_CORE="$KC_HOME/lib/kotlinx-serialization-core-jvm.jar"
[ -n "${SER_PLUGIN:-}" ] || SER_PLUGIN="$KC_HOME/lib/kotlin-serialization-compiler-plugin.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"
need "kotlinx-serialization-json-jvm" "$SER_JSON"

OUT=/tmp/qa-shell-read
RUNTIME="$STDLIB:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

# 这几个文件里没有 @Serializable，序列化插件不是必需——有就带上，没有也能编。
PLUGIN_ARGS=()
[ -f "$SER_PLUGIN" ] && PLUGIN_ARGS=("-Xplugin=$SER_PLUGIN")

SOURCES=(
  "$SRC_CORE/core/llm/JsonSafe.kt"
  "$SRC_CORE/core/agent/ShellCommandRules.kt"
  "$SRC_CORE/core/tool/ToolCatalog.kt"
  "$SRC_CORE/core/agent/ApprovalGrouping.kt"
  "$SRC_CORE/core/agent/ApprovalScope.kt"
)

for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

compile_and_run() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -cp "$RUNTIME" \
    "${PLUGIN_ARGS[@]}" \
    -d "$OUT" -nowarn \
    "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
  [ -d "$OUT/com" ] || return 1
  java -cp "$OUT:$RUNTIME" ShellReadBoundaryVerificationKt
}

BODY=$(compile_and_run); rc=$?
# 两种失败要分开报：编译不过（没有产物）与断言不过（有产物、退出码非 0）。
# 混成一句「编译或运行失败」会让真回归看起来像环境问题——上一轮就踩过。
if [ ! -d "$OUT/com" ]; then
  echo "$BODY"
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi
if [ "$rc" -ne 0 ]; then
  echo "$BODY"
  echo "FAIL 行为断言未通过（见上方 FAIL 行）"
  exit 1
fi
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [W] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
w_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
w_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[W] 接线"
SHELL_TOOL=app/src/main/java/com/beichen/polaris/tools/ShellTool.kt
SCOPE="$SRC_CORE/core/agent/ApprovalScope.kt"
GROUPING="$SRC_CORE/core/agent/ApprovalGrouping.kt"

# W1：判据必须接在「要不要弹窗」的那道门上。只加纯函数不接等于没修。
if grep -q 'ShellCommandRules.sensitiveReadRoot(command) != null' "$SHELL_TOOL"; then
  w_good "W1 ShellTool.needsApprovalFor 走 sensitiveReadRoot"
else
  w_bad "W1 ShellTool 没有接 sensitiveReadRoot —— 读相册仍然零弹窗"
fi

# W2：审批键必须换成按目录的 shell_read，仍用 shell 组等于没改粒度。
if grep -q 'GROUP_SHELL_READ}:\$readScope' "$SCOPE"; then
  w_good "W2 ApprovalScope 产出按目录的 shell_read 键"
else
  w_bad "W2 ApprovalScope 没有产出 shell_read 键 —— 一次构建授权就能放行读相册"
fi

# W3：读档必须在「不可记忆」判定**之后**取。顺序反了，`echo x > /sdcard/a`
# 会复用「读 /sdcard/a」的授权，把写入洗白。
if [ "$(grep -n 'val memorizable = ShellCommandRules.isMemorizable(command)' "$SCOPE" | cut -d: -f1)" -lt \
     "$(grep -n 'val readScope = ShellCommandRules.sensitiveReadRoot(command)' "$SCOPE" | cut -d: -f1)" ]; then
  w_good "W3 读档在不可记忆判定之后（重定向不会被读授权洗白）"
else
  w_bad "W3 读档插在了不可记忆判定之前 —— 写入可以复用读授权"
fi

# W4：弹窗文案要有人话，否则用户看到的是裸键 shell_read。
if grep -q 'GROUP_SHELL_READ ->' "$GROUPING"; then
  w_good "W4 归组有中文文案"
else
  w_bad "W4 ApprovalGrouping 没有 shell_read 的文案"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
