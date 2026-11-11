#!/usr/bin/env bash
# F9「读用户私有数据的工具零审批」验证（剪贴板 / 通知 / 截屏 OCR）。
#
# [行为] 编译真实的 ApprovalScope / ApprovalGrouping / ToolCatalog 跑 23 条断言：
#        剪贴板读取（含缺省与大小写）、写入不问、通知读取要问、两档互不放行、
#        勾选框文案、读屏的第二个入口（ocr_image 与 ui_screenshot 同档），
#        以及四条「既有授权不能顺带放行」的不变量。
# [接线] 三个工具类拖 Android（Context / org.json），只能静态断言：
#        它们真的覆盖了 needsApproval(For)，目录里真的标了归组键，归组真的有文案。
#        判据写得再对，工具不接就是白写——这正是这条缺陷的形态（工具从来没问过）。
#
# 为什么值得单独一个 runner：这两个工具没有「路径」可判，PathGuard 一类判据够不着；
# 唯一能表达的约束就是「读的那一刻弹一次窗」，而它只能靠断言钉住。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/PrivateReadApprovalVerification.kt

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

OUT=/tmp/qa-private-read
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
  java -cp "$OUT:$RUNTIME" PrivateReadApprovalVerificationKt
}

BODY=$(compile_and_run); rc=$?
# 两种失败分开报：编译不过（无产物）与断言不过（有产物、退出码非 0）。
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
CLIP_TOOL=app/src/main/java/com/beichen/polaris/tools/BuiltInTools.kt
NOTIFY_TOOL=app/src/main/java/com/beichen/polaris/automation/DeviceNotificationsTool.kt
CATALOG="$SRC_CORE/core/tool/ToolCatalog.kt"
GROUPING="$SRC_CORE/core/agent/ApprovalGrouping.kt"

# W1：剪贴板工具必须自己覆写 needsApprovalFor。不覆写就落回默认 false——
# 那正是审计 F9 的原始形态（判据写对了，工具从来没问过）。
if grep -q 'override fun needsApprovalFor(args: JsonObject): Boolean' "$CLIP_TOOL"; then
  w_good "W1 ClipboardTool 覆写了 needsApprovalFor"
else
  w_bad "W1 ClipboardTool 没有覆写 needsApprovalFor —— 读剪贴板仍然零弹窗"
fi

# W2：set 必须被放行，否则「帮我复制一段文字」也要弹窗，会训练出闭眼点允许的习惯。
if grep -q 'return action != "set"' "$CLIP_TOOL"; then
  w_good "W2 剪贴板写入（set）不弹窗"
else
  w_bad "W2 剪贴板 set 也被拦了 —— 无害动作开始要授权，真正危险的那次就更没人看"
fi

# W3：通知工具必须显式要审批（默认 false）。
if grep -q 'override val needsApproval = true' "$NOTIFY_TOOL"; then
  w_good "W3 DeviceNotificationsTool 要审批"
else
  w_bad "W3 通知读取仍然零审批 —— 验证码与聊天内容直接进上下文"
fi

# W4：目录里两个工具都要标 PRIVATE_READ（否则弹窗文案退化成裸键）。
if grep -q 'CatalogTool("clipboard", "sys", Capability.PRIVILEGED, Approval.PRIVATE_READ)' "$CATALOG" \
   && grep -q 'CatalogTool("device_notifications", "device", Capability.DANGEROUS, Approval.PRIVATE_READ)' "$CATALOG"; then
  w_good "W4 两个工具在目录里都标了 PRIVATE_READ"
else
  w_bad "W4 目录里没标 PRIVATE_READ —— 弹窗会显示裸键 private_read"
fi

# W5：归组要有中文文案。
if grep -q 'GROUP_PRIVATE_READ ->' "$GROUPING"; then
  w_good "W5 归组有中文文案"
else
  w_bad "W5 ApprovalGrouping 没有 private_read 的文案"
fi

OCR_TOOL=app/src/main/java/com/beichen/polaris/automation/OcrTool.kt

# W6（F9b）：ocr_image 不传 path 时截当前屏——这个分支必须自己问，否则「拒了截图
# 就改用 OCR」是一条现成的侧门。光有签名不算数：判据里必须真的看 path，
# 否则一个恒 false 的实现照样把侧门留着（这正是「签名对、语义空」的老毛病）。
if grep -A2 'override fun needsApprovalFor(args: JsonObject): Boolean' "$OCR_TOOL" | grep -q 'args\["path"\]'; then
  w_good "W6 OcrTool 按 path 是否为空决定要不要问"
else
  w_bad "W6 OcrTool 没按 path 判定 —— 截屏 OCR 仍然零审批"
fi

# W7（F9b）：光问还不够，要落在**截图那一档**。落在私有数据档会让同一个动作
# 在不同工具名下问两遍；落空（没标归组）则退化成裸键 ocr_image。
if grep -q 'CatalogTool("ocr_image", "media", Capability.PRIVILEGED, Approval.UI_SCREENSHOT)' "$CATALOG"; then
  w_good "W7 ocr_image 与 ui_screenshot 共用一个审批档"
else
  w_bad "W7 ocr_image 没标 UI_SCREENSHOT —— 读屏被拆成两档，同一个动作要问两遍"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
