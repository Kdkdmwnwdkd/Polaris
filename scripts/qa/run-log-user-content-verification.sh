#!/usr/bin/env bash
# 审计 F12「日志里的用户内容会随诊断包外发」验证。
#
# [行为] 编译真实的 AgentLogger + ToolCatalog 跑 34 条断言：
#        敏感工具集是否从审批真源派生（需审批 = 敏感）、修前漏网的具体工具逐个点名、
#        正常工具不被误脱敏、补充集名字与真源一致、既有凭据掩码仍有效、URL 脱敏。
# [接线] ChatViewModel / BrowserHost 拖 Android，只能静态断言：
#        用户正文/拒绝理由/弹窗回答/记忆正文不再按原文入日志、URL 走 redactUrl、
#        诊断导出仍对日志做 Key 替换。
#
# 为什么值得单独一个 runner：这条缺陷的表现是「日志看起来一切正常，只是多记了一点」。
# 它不会报错，不会变慢，不会有人在 code review 时说「这里不对劲」；
# 唯一的后果发生在用户点「导出诊断包」求助的那一刻——**为了求救而泄露**。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

CORE=core/src/main/java/com/beichen/polaris/core
HARNESS=scripts/qa/LogUserContentVerification.kt
REDACTION="$CORE/LogRedaction.kt"
CATALOG="$CORE/tool/ToolCatalog.kt"

CACHE="$QA_GRADLE_CACHE"
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-1.9*.jar")
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"

# 零外部依赖：LogRedaction / ToolCatalog 都只用 Kotlin 标准库。
# 这本身就是抽文件的目的——判据不再被 AgentLogger 的协程/文件依赖挡在门外。

KOTLINC="$KC_HOME/bin/kotlinc"
if [ ! -x "$KOTLINC" ]; then
  KOTLINC_JAR=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
  [ -n "${KOTLINC_JAR:-}" ] || KOTLINC_JAR="$KC_HOME/lib/kotlin-compiler.jar"
fi

need() { [ -e "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }; }
need "Kotlin 编译器" "${KOTLINC}"
[ -x "$KOTLINC" ] || need "kotlin-compiler-embeddable" "${KOTLINC_JAR}"
need "kotlin-stdlib" "$STDLIB"
need "LogRedaction.kt" "$REDACTION"
need "ToolCatalog.kt" "$CATALOG"

DEPS="$STDLIB"
OUT=/tmp/qa-log-user-content
rm -rf "$OUT" && mkdir -p "$OUT"

compile() {
  if [ -x "$KOTLINC" ]; then
    "$KOTLINC" -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
      "$REDACTION" "$CATALOG" "$HARNESS" 2>&1 | head -20
  else
    java -cp "$KOTLINC_JAR:$DEPS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
      "$REDACTION" "$CATALOG" "$HARNESS" 2>&1 | head -20
  fi
}

BODY=$(compile)
RUN=$(java -cp "$OUT:$DEPS" LogUserContentVerificationKt 2>&1); rc=$?
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
BROWSER=app/src/main/java/com/beichen/polaris/browser/BrowserHost.kt
DIAG="$CORE/DiagnosticsExporter.kt"

# W1：白名单必须派生自审批真源。写死一张表也能让行为断言全绿（那张表恰好对得上），
# 但明天新增的工具就不会被纳入了——这正是修前 7 项名单的死法。
if grep -q 'ToolCatalog.tools.filter { it.approval != null }' "$REDACTION"; then
  w_good "W1 敏感工具集派生自 ToolCatalog 审批字段"
else
  w_bad "W1 敏感工具集又是手写表 —— 新增工具不会自动纳入"
fi

# W2：用户发送的正文。扫描代码行（注释里引用旧写法是正常的，说明改动缘由）。
leak() { grep -E "$1" "$2" | grep -qvE '^[[:space:]]*(//|\*)'; }
if leak '用户发送：\$\{text\.take\(' "$VM"; then
  w_bad "W2 用户发送正文仍按原文入日志"
else
  w_good "W2 用户发送正文不再按原文入日志（只记长度）"
fi

if leak '用户拒绝了计划 \$planId：\$\{reason\.take\(' "$VM"; then
  w_bad "W3 拒绝计划的理由仍按原文入日志"
else
  w_good "W3 拒绝理由不再按原文入日志"
fi

if leak '用户回答了提问：\$\{choice\.take\(' "$VM"; then
  w_bad "W4 弹窗里的自由输入仍按原文入日志"
else
  w_good "W4 弹窗自由输入不再按原文入日志"
fi

if leak '\$\{c\.content\.take\(' "$VM"; then
  w_bad "W5 自动抽取的记忆正文仍按原文入日志"
else
  w_good "W5 记忆正文不再按原文入日志"
fi

# W6：浏览器 URL 的 query 可能带授权码。
if grep -q 'AgentLogger.redactUrl(' "$BROWSER"; then
  w_good "W6 浏览器 URL 进日志前裁掉 query/fragment"
else
  w_bad "W6 浏览器 URL 原样入日志 —— ?code= 会跟着诊断包出去"
fi

# W7：既有的 Key 替换不能因为这次改动被删掉（诊断包里主 Key 与备用 Key 都要脱敏）。
if grep -q 'safeLog = safeLog.replace(k, maskKey(k))' "$DIAG"; then
  w_good "W7 诊断导出仍对日志做 Key 替换"
else
  w_bad "W7 诊断导出的 Key 替换没了 —— 日志里的 Key 会原样进诊断包"
fi

# W8/W9：执行器的日志调用必须带上**运行期**判据。只查静态表的话，MCP 工具
# （运行期注册、ToolCatalog 里没有条目）会一路明文——而它们的内容正是「外部服务
# 能看到的全部」。W9 单独列出来，是因为「入参脱敏了、结果没脱敏」是最典型的改一半。
EXEC="$CORE/agent/ToolExecutor.kt"
if grep -q 'LogRedaction.shouldRedact(' "$EXEC"; then
  w_good "W8 ToolExecutor 用运行期判据算日志敏感度"
else
  w_bad "W8 ToolExecutor 只查静态名单 —— MCP 工具的入参与输出会明文落盘"
fi

if grep -q 'toolCall(call.name, call.arguments.toString().take(300), logSensitive)' "$EXEC"; then
  w_good "W9a 工具入参日志带敏感标志"
else
  w_bad "W9a 工具入参日志没带敏感标志"
fi

if grep -q 'toolResult(call.name, !result.isError, cost, text.take(300), logSensitive)' "$EXEC"; then
  w_good "W9b 工具结果日志复用同一个标志（不是只改了入参）"
else
  w_bad "W9b 工具结果日志没带敏感标志 —— 入参脱敏了、结果漏了"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
