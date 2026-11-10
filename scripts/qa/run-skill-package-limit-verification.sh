#!/usr/bin/env bash
# 审计 F10「本地 zip 导入没有体积上限」验证（三条入口的体积闸门）。
#
# [行为] 编译真实的 SkillPackage 跑 13 条断言：readCapped 的边界（== 上限放行、
#        +1 字节中止、空流、分块无长度）、不替调用方关流、内容原样，
#        requireWithin 的预判（含 -1 未知长度不算超限），上限值，以及面向用户的消息。
# [接线] 两个读取点在 ChatViewModel（拖 Android），只能静态断言：
#        URL 入口不再自己 openStream、SAF 入口真的走 readCapped、installZip 有兜底。
#
# 为什么值得单独一个 runner：`OutOfMemoryError` 是 Error 不是 Exception，
# `runCatching` 抓不住——这条缺陷的表现不是「导入失败」，是**整个 App 没了**。
# 而它只在「有人给了个大包」时才发生，平时测不出来。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/SkillPackageLimitVerification.kt
PKG="$SRC_CORE/skill/SkillPackage.kt"

CACHE="$QA_GRADLE_CACHE"
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-1.9*.jar")
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"

KOTLINC="$KC_HOME/bin/kotlinc"
if [ ! -x "$KOTLINC" ]; then
  KOTLINC_JAR=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
  [ -n "${KOTLINC_JAR:-}" ] || KOTLINC_JAR="$KC_HOME/lib/kotlin-compiler.jar"
fi

need() { [ -e "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }; }
need "Kotlin 编译器" "${KOTLINC}"
[ -x "$KOTLINC" ] || need "kotlin-compiler-embeddable" "${KOTLINC_JAR}"
need "kotlin-stdlib" "$STDLIB"

OUT=/tmp/qa-skill-package
rm -rf "$OUT" && mkdir -p "$OUT"

compile() {
  if [ -x "$KOTLINC" ]; then
    "$KOTLINC" -no-reflect -classpath "$STDLIB" -d "$OUT" -nowarn "$PKG" "$HARNESS" 2>&1 | head -20
  else
    java -cp "$KOTLINC_JAR:$STDLIB" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$STDLIB" -d "$OUT" -nowarn "$PKG" "$HARNESS" 2>&1 | head -20
  fi
}

BODY=$(compile)
RUN=$(java -cp "$OUT:$STDLIB" SkillPackageLimitVerificationKt 2>&1); rc=$?
echo "$RUN"
if [ ! -d "$OUT/com" ]; then
  echo "$BODY"
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi
if [ "$rc" -ne 0 ]; then
  echo "FAIL 行为断言未通过（见上方 FAIL 行）"
  exit 1
fi
JS=$(echo "$RUN" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [W] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
w_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
w_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[W] 接线"
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
CLIENT="$SRC_CORE/skill/market/MarketClient.kt"
REPO="$SRC_CORE/skill/SkillImportRepository.kt"

# W1：URL 入口不能再自己开流。`URL(...).openStream()` 没有任何限制，
# 而且绕开了统一 HTTP 客户端这条约定。
# 只扫代码行：注释里写着「原来用的是 URL(url).openStream()」是正常的（说明改动缘由），
# 把它也判红等于逼人删掉解释。
if grep -E 'URL\(url\)\.openStream\(\)' "$VM" | grep -qvE '^[[:space:]]*(//|\*)'; then
  w_bad "W1 ChatViewModel 仍在 URL(url).openStream() —— 无上限、无超时、绕过统一客户端"
else
  w_good "W1 URL 入口不再自己开流"
fi

# W2：改走市场那条（协议白名单 + 8MB + 20s/60s 超时）。
if grep -q 'market.fetchPackage(url)' "$VM"; then
  w_good "W2 URL 入口走 MarketClient.fetchPackage"
else
  w_bad "W2 URL 入口没走 fetchPackage —— 三条限制一条都没生效"
fi

# W3：SAF 的流没有长度可预判，必须边读边计数。
if grep -q 'SkillPackage.readCapped(' "$VM"; then
  w_good "W3 SAF 入口边读边计数"
else
  w_bad "W3 SAF 入口仍在 readBytes() —— content:// 没有 Content-Length，整份铺进内存"
fi

# W4：installZip 是唯一落盘口，兜底放在这里——将来多一个入口忘了加，也不会一路走进解压循环。
if grep -q 'SkillPackage.requireWithin(zipBytes.size.toLong()' "$REPO"; then
  w_good "W4 installZip 有体积兜底"
else
  w_bad "W4 installZip 没有体积兜底 —— 新入口一漏就是 OOM"
fi

# W5：fetchPackage 必须先过协议白名单（否则 file:// / content:// 也能进）。
if sed -n '/suspend fun fetchPackage/,/^    }/p' "$CLIENT" | grep -q 'MarketRules.validHttpUrl'; then
  w_good "W5 fetchPackage 先校验 http/https"
else
  w_bad "W5 fetchPackage 没走 validHttpUrl —— 任意 scheme 都能取包"
fi

# W6：两处上限必须相等（写死两个数字又不加断言，正是「迟早有一处被改掉」的形态）。
zip_expr=$(sed -n 's/.*const val MAX_ZIP_BYTES: Int = \([0-9][0-9]* \* [0-9][0-9]* \* [0-9][0-9]*\).*/\1/p' "$PKG" | head -1)
pkg_expr=$(sed -n 's/.*const val MAX_PACKAGE_BYTES = \([0-9][0-9]*L\? \* [0-9][0-9]* \* [0-9][0-9]*\).*/\1/p' \
  "$SRC_CORE/skill/market/MarketModels.kt" | head -1)
if [ -n "$zip_expr" ] && [ -n "$pkg_expr" ]; then
  zip_v=$(( zip_expr )); pkg_v=$(( ${pkg_expr//L/} ))
  if [ "$zip_v" -eq "$pkg_v" ]; then
    w_good "W6 两处上限相等（$zip_v 字节）"
  else
    w_bad "W6 两处上限不一致：SkillPackage=$zip_v / MarketRules=$pkg_v"
  fi
else
  w_bad "W6 解析不到上限常量（判据失效，视为失败）"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
