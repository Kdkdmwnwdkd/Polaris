#!/usr/bin/env bash
# R-07「停用 MCP 服务器后工具仍在」验证。
#
# [行为] 编译真实的 ToolRegistry.kt + McpToolSync.kt 跑 —— 「停一台、另一台还在不在」
#        只有真跑能证明。不依赖 AGP 产物。
# [接线] McpScreen / AppScaffold / ChatViewModel 拖 Compose 与 Android，只能静态断言：
#        改配置之后到底有没有人去同步工具表。
#
# 反向验证（可选）：AK_ALLOW_SOURCE_MUTATION=1 时把 unregisterOwner 改成 no-op 重编译，
# 行为断言必须变红。默认只读，避免中途被杀留下脏仓库。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/R07Verification.kt

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
need "kotlinx-coroutines-core-jvm" "$COROUTINES"
need "kotlinx-serialization-json-jvm" "$SER_JSON"
need "序列化编译器插件" "$SER_PLUGIN"

OUT=/tmp/qa-r07
RUNTIME="$STDLIB:$COROUTINES:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS:$COROUTINES"

SOURCES=(
  "$SRC_CORE/core/AgentContract.kt"
  "$SRC_CORE/core/ToolResult.kt"
  "$SRC_CORE/core/agent/ToolDescriptor.kt"
  "$SRC_CORE/core/ToolLoadProfiler.kt"
  "$SRC_CORE/core/ToolLimits.kt"
  "$SRC_CORE/core/ToolRegistry.kt"
  "$SRC_CORE/core/mcp/McpModels.kt"
  "$SRC_CORE/core/mcp/McpToolSync.kt"
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
  java -cp "$OUT:$RUNTIME" R07VerificationKt
}

for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

BODY=$(compile_and_run)
rc=$?
if [ $rc -ne 0 ] || [ -z "$BODY" ]; then
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
SCREEN=app/src/main/java/com/beichen/polaris/ui/McpScreen.kt
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
SCAFFOLD=app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt

# G1：改配置的三条路径（保存 / 启停 / 删除）都必须回调。
# 只补一条等于只修了「用户点删除」这一种情况。
n=$(grep -c 'onServersChanged()' "$SCREEN")
if [ "$n" -ge 3 ]; then
  g_good "G1 保存/启停/删除三条路径都触发同步（$n 处）"
else
  g_bad "G1 onServersChanged 只调了 $n 次（应为 3：保存、启停、删除）"
fi

# G2：页面参数真的被接上了。函数有参数、调用点不传，等于没修。
if grep -q 'McpScreen(vm::refreshMcpTools)' "$SCAFFOLD"; then
  g_good "G2 AppScaffold 把 MCP 页的变更接到 vm.refreshMcpTools"
else
  g_bad "G2 AppScaffold 仍在 McpScreen() 裸调——配置变了没人同步工具表"
fi

# G3：注册必须走「按持有者整体替换」，不能再逐条 register。
if grep -q 'registry.registerOwned(' "$SRC_CORE/core/mcp/McpToolSync.kt"; then
  g_good "G3 同步器用 registerOwned 整体替换"
else
  g_bad "G3 同步器没有调用 registerOwned"
fi

# G4：ChatViewModel 自己不能再直接注册 MCP 工具（那样又绕开了回卷）。
if grep -q 'registry.register(McpToolAdapter(' "$VM"; then
  g_bad "G4 ChatViewModel 仍直接 register(McpToolAdapter(...))——绕开了回卷"
else
  g_good "G4 ChatViewModel 不再直接注册 MCP 工具"
fi

# G5：撤销要真有人调。修前 ToolRegistry.unregister 全仓零调用，就是这条缺陷。
n=$(grep -rl 'unregisterOwner(' app/src/main core/src/main --include='*.kt' | wc -l | tr -d ' ')
if [ "$n" -ge 2 ]; then
  g_good "G5 unregisterOwner 有真实调用点（$n 个文件）"
else
  g_bad "G5 unregisterOwner 只在定义处出现——又变成「写了没人调」"
fi

# ── 反向验证（可选，会改源码） ─────────────────────────────────────────────
if [ "${AK_ALLOW_SOURCE_MUTATION:-}" = "1" ]; then
  echo ""
  echo "[M] 源码变异：把 unregisterOwner 改成 no-op，行为断言必须变红"
  TARGET="$SRC_CORE/core/ToolRegistry.kt"
  BAK=$(mktemp); cp "$TARGET" "$BAK"
  restore() { [ -f "$BAK" ] && cp "$BAK" "$TARGET" && rm -f "$BAK"; }
  trap restore EXIT

  python3 - "$TARGET" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
# 只改返回值，保留函数体签名：模拟「写了撤销但撤销没生效」
s2 = re.sub(r'(fun unregisterOwner\(owner: String\): Int = synchronized\(lock\) \{)',
            r'\1\n        if (true) return 0', s, count=1)
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
      g_bad "M1 撤销失效后断言仍然绿——断言没测到点子上"
    elif echo "$OUT2" | grep -q 'A2'; then
      g_good "M1 撤销失效后 A2 变红（exit=$rc2）"
    else
      g_bad "M1 变红了但红的不是撤销相关断言"
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
