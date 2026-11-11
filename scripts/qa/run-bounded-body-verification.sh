#!/usr/bin/env bash
# 审计 F21：HTTP 响应体的上限必须作用在「读的过程」，而不是「读完之后的 take」。
#
# [行为] BoundedBody 零 Android 依赖（只用 java.io），编译真文件跑；
#        进程以 -Xmx64m 启动，B1 用一个「永远读不完的流」钉住「读满即停」。
# [接线] http_request / WebSearchTool 拖 Android/OkHttp，只能静态核对。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris/core
BOUNDED="$SRC_CORE/net/BoundedBody.kt"
BUILTIN=app/src/main/java/com/beichen/polaris/tools/BuiltInTools.kt
WEBSEARCH=app/src/main/java/com/beichen/polaris/tools/WebSearchTool.kt
HARNESS=scripts/qa/BoundedBodyVerification.kt

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

OUT=/tmp/qa-f21
rm -rf "$OUT"; mkdir -p "$OUT"

RUNTIME="$STDLIB"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

for f in "$BOUNDED" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$RUNTIME" \
  -d "$OUT" -nowarn \
  "$BOUNDED" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
if [ ! -d "$OUT/com" ]; then
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

# -Xmx64m：B1 的意义全在这个上限上。改大等于把「不整份载入」这条断言删掉。
BODY=$(java -Xmx64m -cp "$OUT:$RUNTIME" BoundedBodyVerificationKt)
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

# W1：http_request 里不能再有 body.string()。判据写对、调用点还留着整份读，等于没修。
HTTP_BODY=$(awk '/^class HttpRequestTool/{f=1;next} f && /^class /{exit} f{print}' "$BUILTIN")
if echo "$HTTP_BODY" | grep -q 'body?.string()'; then
  w_bad "W1 http_request 仍在 body.string() 整份载入"
else
  w_good "W1 http_request 不再整份载入响应体"
fi

# W2：真的走了限读实现。
if echo "$HTTP_BODY" | grep -qF 'BoundedBody.read'; then
  w_good "W2 http_request 调用 BoundedBody.read"
else
  w_bad "W2 http_request 没有调用 BoundedBody.read"
fi

# W3：搜索页 HTML 是第二个入口。只修 http_request 等于把同一个洞留半开。
if grep -q 'body?.string()' "$WEBSEARCH"; then
  w_bad "W3 WebSearchTool 仍在 body.string() 整份载入"
else
  w_good "W3 WebSearchTool 不再整份载入响应体"
fi

# W4：限读实现单点（两处各写一份「读 N 字节」的循环，上限迟早分叉）。
n=$(grep -rl 'object BoundedBody' app/src/main core/src/main --include=*.kt | wc -l | tr -d ' ')
if [ "$n" = "1" ]; then
  w_good "W4 BoundedBody 单点定义"
else
  w_bad "W4 BoundedBody 出现在 $n 个文件里"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
