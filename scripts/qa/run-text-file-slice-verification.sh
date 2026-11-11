#!/usr/bin/env bash
# 审计 F19：read_file 的 limit 必须限制「读多少」，而不是只限制「返回多少」。
#
# [行为] TextFileSlice 零 Android 依赖（只用 java.io），编译真文件跑；
#        进程以 -Xmx64m 启动，36MB 文件在旧实现（readText 整份载入）下必 OOM。
# [接线] ReadFileTool 拖 Android/OkHttp，只能静态核对。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris/core
TOOLS=app/src/main/java/com/beichen/polaris/tools/BuiltInTools.kt
SLICE="$SRC_CORE/TextFileSlice.kt"
ENCODING="$SRC_CORE/TextEncoding.kt"
HARNESS=scripts/qa/TextFileSliceVerification.kt

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

OUT=/tmp/qa-f19
rm -rf "$OUT"; mkdir -p "$OUT"

RUNTIME="$STDLIB"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

for f in "$SLICE" "$ENCODING" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$RUNTIME" \
  -d "$OUT" -nowarn \
  "$SLICE" "$ENCODING" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
if [ ! -d "$OUT/com" ]; then
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

# -Xmx64m：B1 那条的意义全在这个上限上。改大等于把「不整份载入」这条断言删掉。
BODY=$(java -Xmx64m -cp "$OUT:$RUNTIME" TextFileSliceVerificationKt)
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

# W1：read_file 里不能再有整份载入。判据写对、调用点还留着 readText，等于没修。
# 只扫 ReadFileTool 自己的类体：edit_file 读全文做替换是另一回事（它必须看到全文）。
READFILE_BODY=$(awk '/^class ReadFileTool/{f=1;next} f && /^(abstract )?class /{exit} f{print}' "$TOOLS")
if echo "$READFILE_BODY" | grep -q '\.readText()'; then
  w_bad "W1 ReadFileTool 仍在 readText() 整份载入"
else
  w_good "W1 ReadFileTool 不再整份载入"
fi

# W2：真的走了分页实现。
if grep -qF 'TextFileSlice.read(file, offset, limit)' "$TOOLS"; then
  w_good "W2 ReadFileTool 调用 TextFileSlice.read"
else
  w_bad "W2 ReadFileTool 没有调用 TextFileSlice.read"
fi

# W3：续读提示必须用返回值 nextOffset——自己按 offset+limit 手算，页尾多带 1 个码元时就会漏字。
if grep -qF 'offset=${slice.nextOffset}' "$TOOLS"; then
  w_good "W3 续读提示用返回的 nextOffset"
else
  w_bad "W3 续读提示没用 nextOffset（手算 offset 会在代理对边界漏字）"
fi

# W4：分页逻辑单点定义（不许工具里再写一份 substring 版本）。
n=$(grep -rl 'object TextFileSlice' app/src/main core/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$n" = "1" ]; then
  w_good "W4 TextFileSlice 单点定义"
else
  w_bad "W4 TextFileSlice 出现在 $n 个文件里"
fi

# W5：上限只在实现里。工具里再 coerce 一次 65536，两处默认值迟早会分叉。
if grep -qF 'coerceAtMost(65536)' "$TOOLS"; then
  w_bad "W5 工具里仍硬顶 65536（上限应只在 TextFileSlice）"
else
  w_good "W5 上限单点在 TextFileSlice"
fi

# W6：edit_file 不能再走 readText()。它对非 UTF-8 文件做的是「替换成 U+FFFD 再写回」——
# 不报错、不抛异常，只是把用户的字节永久换掉。这条守的是「文件不会被写坏」，不是代码长相。
EDITFILE_BODY=$(awk '/^class EditFileTool/{f=1;next} f && /^class /{exit} f{print}' "$TOOLS")
if echo "$EDITFILE_BODY" | grep -q '\.readText()'; then
  w_bad "W6 edit_file 仍用 readText() 整份载入（非 UTF-8 文件会被写坏）"
else
  w_good "W6 edit_file 不再用 readText()"
fi

# W7：真的走了严格解码。只把 readText 删掉、改成别的宽松读法同样会写坏。
if grep -qF 'TextEncoding.readStrictUtf8(file)' "$TOOLS"; then
  w_good "W7 edit_file 调用 TextEncoding.readStrictUtf8"
else
  w_bad "W7 edit_file 没有走严格解码"
fi

# W8：编码判据单点（两个工具各写一份 lenient/strict 判断，迟早分叉）。
n=$(grep -rl 'object TextEncoding' app/src/main core/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$n" = "1" ]; then
  w_good "W8 TextEncoding 单点定义"
else
  w_bad "W8 TextEncoding 出现在 $n 个文件里"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
