#!/usr/bin/env bash
# 审计 F15「读通知的授权粒度停在『工具』这一层」验证。
#
# [行为] 编译真实的 NotificationScope / ApprovalScope / NotificationStore（零 Android 依赖）
#        跑 32 条断言：
#        - 解析：缺省 / null / 空数组 / 单串 / 数组 / 脏项 / 超限 各自落到哪一档；
#        - 授权键：顺序无关、全量键与点名键不同、可记忆性、升级前的老键不再命中；
#        - 文案：弹窗要写清是哪个应用；全量不能说「本次会话不再询问」；
#        - **同源不变量**：键里的包名集合 == 工具实际过滤用的集合（10 条，含跨应用不放行）；
#        - 边界：8 个可以、9 个拒。
# [接线] DeviceNotificationsTool 拖 org.json，只能静态断言：
#        它真的声明了 packages、真的走 NotificationStore.byPackages 过滤、
#        真的把无效范围当失败返回、并且真的用 NotificationScope.parse 与审批键同源。
#
# 为什么值得单独一个 runner：这条缺陷不会以「出错」的形式暴露。弹窗照弹、日志照记、
# 功能正常，唯一的证据是「用户以为自己只批了微信」。而只改审批键、不改读取过滤，
# 是这类修法里最典型的一种假修——弹窗写着微信，实际 60 条全读走。
# [D] 组就是专门对这条账的。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
SRC_APP=app/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/NotificationScopeVerification.kt
AUTO="$SRC_APP/automation"

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
need "NotificationScope.kt" "$SRC_CORE/core/agent/NotificationScope.kt"
need "NotificationStore.kt" "$AUTO/NotificationStore.kt"

OUT=/tmp/qa-notification-scope
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
  "$SRC_CORE/core/agent/NotificationScope.kt"
  "$AUTO/NotificationStore.kt"
)

for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

compile_only() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -cp "$RUNTIME" \
    "${PLUGIN_ARGS[@]}" \
    -d "$OUT" -nowarn \
    "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
}

CC=$(compile_only)
# 两种失败分开报：编译不过（无产物）与断言不过（有产物、退出码非 0）。
if [ ! -d "$OUT/com" ]; then
  echo "$CC"
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

RUN=$(java -cp "$OUT:$RUNTIME" NotificationScopeVerificationKt 2>&1); rc=$?
echo "$RUN"
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
TOOL="$AUTO/DeviceNotificationsTool.kt"
SCOPE="$SRC_CORE/core/agent/NotificationScope.kt"
AP="$SRC_CORE/core/agent/ApprovalScope.kt"
LISTENER="$AUTO/AgentNotificationListener.kt"

# W1：工具得真的声明了这个参数，否则模型永远不会传，判据再好也走不到。
if grep -q '"packages" to JsonSchema.Property' "$TOOL"; then
  w_good "W1 工具声明了 packages 参数"
else
  w_bad "W1 工具没有声明 packages —— 模型无从点名，判据形同虚设"
fi

# W2：参数必须可选。写成必填会让「我就想看看最近有什么」每次都失败。
if grep -q 'required = emptyList()' "$TOOL"; then
  w_good "W2 packages 是可选参数"
else
  w_bad "W2 packages 被声明成必填"
fi

# W3：过滤必须真的发生。直接用 NotificationStore.all 就是没修。
if grep -q 'NotificationStore.byPackages' "$TOOL"; then
  w_good "W3 工具按包名过滤（byPackages）"
else
  w_bad "W3 工具仍直接读 NotificationStore.all —— 弹窗与读取不同源"
fi

# W4：同源。工具与审批键必须用同一个解析函数，否则两边迟早各算一套。
if grep -q 'NotificationScope.parse(args)' "$TOOL" && grep -q 'NotificationScope.parse(args)' "$AP"; then
  w_good "W4 工具与审批键共用 NotificationScope.parse"
else
  w_bad "W4 工具或审批键没有走同一个解析函数（存在「批 A 读 B」的空档）"
fi

# W5：范围写坏要当失败返回，不能「当成全部读一遍」。
if grep -q 'notifications_bad_scope' "$TOOL"; then
  w_good "W5 无效范围返回失败（不静默放大成全量）"
else
  w_bad "W5 无效范围没有失败分支"
fi

# W6：全量读取不可记忆。这条是整条修法的兜底：只要它成立，任何解析降级都不会变成
# 「一次授权、以后随便读」。
if grep -q 'NotificationFilter.Named' "$SCOPE" && grep -q 'memorizable' "$SCOPE"; then
  w_good "W6 可记忆性由范围档决定（Named 才可记忆）"
else
  w_bad "W6 可记忆性没有跟着范围走"
fi

# W7：审批键必须带范围，而不是回到裸工具名。
if grep -q 'NotificationScope.keyOf(filter)' "$AP"; then
  w_good "W7 审批键由 NotificationScope.keyOf 生成（带应用集合）"
else
  w_bad "W7 审批键仍按工具名生成 —— 粒度又回到了「通知」"
fi

# W8：数据源必须可独立编译。监听服务拖 Android，如果把 NotificationStore 塞回去，
# 上面 [D] 组就没法真跑，只能靠 grep 猜。
if grep -q '^object NotificationStore' "$AUTO/NotificationStore.kt" 2>/dev/null \
   && ! grep -q '^object NotificationStore' "$LISTENER"; then
  w_good "W8 NotificationStore 独立成零依赖文件（可被 JVM harness 直接验证）"
else
  w_bad "W8 NotificationStore 又回到了监听服务文件里（行为断言失去入口）"
fi

# W9：工具名与 NotificationScope.TOOL 必须一致。工具侧保留字面量是刻意的——
# ApprovalGroupingVerification 用正则从源码里扫「工具名 ↔ needsApproval」，
# 写成常量引用会扫不到、被误判成死元数据。两处的一致性由这一条钉住。
if grep -q 'override val name = "device_notifications"' "$TOOL" \
   && grep -q 'const val TOOL = "device_notifications"' "$SCOPE"; then
  w_good "W9 工具名与 NotificationScope.TOOL 一致"
else
  w_bad "W9 工具名与 NotificationScope.TOOL 不一致（审批键会挂到另一个工具名下）"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
