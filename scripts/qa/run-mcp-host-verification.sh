#!/usr/bin/env bash
# MCP 对外提供（M1 只读桥）验证。
#
# [A] 装配事实：装的是哪几个工具、服务有没有真的进前台、清单/页面有没有接上。
#     这些是「源码里有没有这条线」，行为 harness 测不到「没接线」。
# [B] 协议与安全边界：真起 Socket、真发 HTTP，验证档位外拒绝、审批缺失拒绝、
#     鉴权、审计。核心断言是「被拒的调用一次都没执行」——扫描源码证明不了这点。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core
MCP="$CORE/mcp/server"
APP=app/src/main/java/com/beichen/polaris
TOOLSET="$APP/mcp/McpToolset.kt"
RUNTIME="$APP/mcp/McpHostRuntime.kt"
SERVICE="$APP/mcp/McpHostService.kt"
CARD="$APP/ui/McpHostCard.kt"
SCREEN="$APP/ui/McpScreen.kt"
MANIFEST=app/src/main/AndroidManifest.xml
GRAPH="$APP/AppGraph.kt"

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

code_has() { # $1=文件 $2=字面量（跳过注释行）
  [ -f "$1" ] || return 1
  awk -v pat="$2" '
    {
      line=$0
      sub(/^[ \t]+/, "", line)
      if (line ~ /^(\/\/|\*|\/\*)/) next
      sub(/\/\/.*/, "", line)
      if (index(line, pat) > 0) { found=1; exit }
    }
    END { exit(found ? 0 : 1) }
  ' "$1"
}

echo "[A] 装配：装了什么、服务是否真进前台、页面有没有接上"

if [ -f "$TOOLSET" ]; then
  n=$(grep -cE '^\s+"[a-z_]+",?$' "$TOOLSET" || true)
  if [ "${n:-0}" -eq 7 ]; then
    pass "A1 McpToolset 清单是 7 个工具"
  else
    fail "A1 McpToolset 清单条目数 ${n:-0}，预期 7——改工具集必须同步改断言与 UI 文案"
  fi
else
  fail "A1 找不到 McpToolset.kt"
fi

# 硬禁用 / 高危工具绝不能出现在这份装配里。写文件、shell、无障碍点击都在 M2。
leaked=""
for bad in write_file shell_run ui_tap ui_screenshot app_install; do
  if grep -qE "register\(\s*${bad}\s*\(" "$TOOLSET" 2>/dev/null; then
    leaked="$leaked $bad"
  fi
done
if [ -z "$leaked" ]; then
  pass "A2 McpToolset 没有注册写/shell/无障碍点击类工具"
else
  fail "A2 McpToolset 注册了越界工具:$leaked —— 它们属于 M2"
fi

if code_has "$SERVICE" "startForeground("; then
  pass "A3 服务在 onCreate/onStartCommand 里进了前台"
else
  fail "A3 服务没有 startForeground —— 后台会被回收，客户端看到的是连接被拒"
fi

if code_has "$SERVICE" "ensureChannel()"; then
  pass "A4 建通知渠道在 startForeground 之前"
else
  fail "A4 没有 ensureChannel —— Android 8+ 会对不存在的渠道抛 IllegalArgumentException"
fi

if grep -q 'android:name=".mcp.McpHostService"' "$MANIFEST"; then
  pass "A5 manifest 声明了 McpHostService"
else
  fail "A5 manifest 没声明服务 —— 启动会直接抛 Unable to start service"
fi

if code_has "$GRAPH" "McpHostRuntime.reset()"; then
  pass "A6 McpHostRuntime 已登记进 AppGraph"
else
  fail "A6 McpHostRuntime 没登记 —— run-appgraph-verification 会红，且重置路径漏掉它"
fi

if code_has "$SCREEN" "McpHostCard()"; then
  pass "A7 MCP 页挂上了对外提供卡片"
else
  fail "A7 MCP 页没挂卡片 —— 功能存在但用户找不到入口"
fi

# 安全关键：没有审批实现时，绝不能把 needsApprovalFor 的判定短路掉。
if code_has "$MCP/McpServerHost.kt" "needsApprovalFor(args)"; then
  pass "A8 宿主对每次调用都询问 needsApprovalFor"
else
  fail "A8 宿主没有检查 needsApprovalFor —— 外部调用会绕过审批"
fi

if code_has "$MCP/McpServerHost.kt" "approver?.let"; then
  pass "A9 没有审批实现时走拒绝分支（而不是放行）"
else
  fail "A9 审批缺失时的默认值不是拒绝"
fi

echo ""
echo "[B] 行为：真编真跑（鉴权 / 档位 / 审批 / 审计）"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "*.jar")
KREFLECT=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-reflect" "kotlin-reflect-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

if [ -z "${KOTLINC:-}" ] || [ ! -f "$KOTLINC" ] || [ -z "${SER_JSON:-}" ] || [ ! -f "$SER_JSON" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin / kotlinx-serialization 运行库" >&2
  exit 3
fi

OUT=/tmp/qa-mcp-host
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN${KREFLECT:+:$KREFLECT}${COROUTINES:+:$COROUTINES}"
DEPS="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" -nowarn \
  "$CORE/io/AtomicJsonFile.kt" \
  "$CORE/AgentContract.kt" \
  "$CORE/ToolResult.kt" \
  "$CORE/tool/ToolCatalog.kt" \
  "$CORE/agent/ToolDescriptor.kt" \
  "$CORE/agent/ToolGuard.kt" \
  "$MCP/McpToolSource.kt" \
  "$MCP/McpTier.kt" \
  "$MCP/McpPairing.kt" \
  "$MCP/McpAudit.kt" \
  "$MCP/McpServerHost.kt" \
  scripts/qa/McpHostVerification.kt \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -f "$OUT/McpHostVerificationKt.class" ]; then
  fail "B 编译失败（上面是输出）—— 协议断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$DEPS" McpHostVerificationKt)
  rc=$?
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  line=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
  if [ -z "$line" ]; then
    fail "B 段没有结果行（harness 中途退出，rc=$rc）—— 未跑完不算通过"
  else
    bp=$(echo "${line%% 通过*}" | tr -dc '0-9'); bp=${bp:-0}
    bf=$(echo "${line##* / }" | tr -dc '0-9'); bf=${bf:-0}
    p=$((p + bp)); f=$((f + bf))
    [ "$bf" -eq 0 ] || echo "        B 段有 $bf 条行为断言失败"
  fi
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] || exit 1
