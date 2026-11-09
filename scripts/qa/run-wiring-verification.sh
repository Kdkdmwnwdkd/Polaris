#!/usr/bin/env bash
# 契约接线验证（2026-11-09）：把「实现完整但没人调」的 10 条清一遍。
#
# 结果不是「10 条全接上」，而是三种处置：
#   接了 6 条（hasUnseen / markSeen / popularModels / detectFormat / exportNdjson / outstandingCount）
#   删了 4 条（isDownloaded / keyEvent / getUiTreeJson / runNow）——它们各自都已有等价实现，
#   再接一次等于给同一个动作开第二条路，出事时两条路会给出不同答案。
#
# 所以这里既断言「该在的调用在」，也断言「该没的定义真的没了」——后者和 R19 台账互为
# 交叉验证：台账说 DELETED，本文件再独立验一次源码。
#
# 行为断言的取舍：这 10 条大多落在 Compose / Android API 上（需要 AGP 产物与设备），
# 唯一能纯 JVM 真跑的是 RunJournal 的导出复用，所以只给它一条 parity 断言，
# 其余靠静态扫描 + 人工比对。不假装覆盖面更大。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

CRASH="core/src/main/java/com/beichen/polaris/core/CrashLogger.kt"
PROVIDER="core/src/main/java/com/beichen/polaris/core/llm/ProviderCatalog.kt"
GGUF="core/src/main/java/com/beichen/polaris/core/llm/local/GgufModelStore.kt"
CATALOG="core/src/main/java/com/beichen/polaris/core/llm/local/LocalBackendCatalog.kt"
JOURNAL="core/src/main/java/com/beichen/polaris/core/agent/journal/RunJournal.kt"
LEASE="app/src/main/java/com/beichen/polaris/runtime/lease/LocalLlmLease.kt"
REG="app/src/main/java/com/beichen/polaris/runtime/lease/LeaseRegistry.kt"
MODEL="app/src/main/java/com/beichen/polaris/ui/ModelConfigScreen.kt"
LOGS="app/src/main/java/com/beichen/polaris/ui/LogsScreen.kt"
SCAFFOLD="app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt"
CONSOLE="app/src/main/java/com/beichen/polaris/ui/ConsoleScreen.kt"
SHELLUI="app/src/main/java/com/beichen/polaris/automation/ShellUiBackend.kt"
A11Y="app/src/main/java/com/beichen/polaris/automation/AgentAccessibilityService.kt"
WORKER="app/src/main/java/com/beichen/polaris/workflow/WorkflowWorker.kt"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1（缺：$3）"; fi; }
hasnt() { if grep -qF -- "$3" "$2" 2>/dev/null; then fail "$1（不该有：$3）"; else pass "$1"; fi; }
line_of() { grep -nF -- "$2" "$1" 2>/dev/null | head -1 | cut -d: -f1; }

echo "== 一、模型页：常用模型建议（popularModels） =="

has "数据源接上了（供应商一换就重算）" "$MODEL" "ProviderCatalog.popularModels(providerId)"
has "渲染成 chip" "$MODEL" "AkChip("
has "当前模型高亮" "$MODEL" "selected = model == name"
has "点一下只填入，不保存" "$MODEL" "onClick = { onModel(name) }"
has "数据本体仍在目录里（不是搬进 UI）" "$PROVIDER" "fun popularModels(id: String): List<String>"

echo "== 二、模型导入：格式前置识别（detectFormat） =="

has "导入流程调了格式识别" "$GGUF" "LocalEngineCatalog.detectFormat(displayName"
has "只放行 GGUF" "$GGUF" "named != null && named != LocalModelFormat.GGUF"
has "拒绝时说清是什么格式" "$GGUF" "看起来是 \${named.label} 格式"
DF_LINE=$(line_of "$GGUF" "LocalEngineCatalog.detectFormat(displayName")
READ_LINE=$(line_of "$GGUF" "resolver.openInputStream(uri)")
if [ -n "$DF_LINE" ] && [ -n "$READ_LINE" ] && [ "$DF_LINE" -lt "$READ_LINE" ]; then
    pass "判定在复制之前（行 $DF_LINE < $READ_LINE，不再白拷几 GB）"
else
    fail "格式判定不在复制之前（detect=$DF_LINE read=$READ_LINE）——前置就失去意义了"
fi
has "认不出的格式仍走魔数校验（文件名不可信）" "$GGUF" 'String(head, Charsets.US_ASCII) != "GGUF"'
has "识别规则仍在纯层（CI 可断言）" "$CATALOG" "fun detectFormat(fileName: String, isDirectory: Boolean"

echo "== 三、崩溃未读：控制台红点（hasUnseen） =="

has "控制台入口前问了崩溃目录" "$SCAFFOLD" "CrashLogger.hasUnseen(ctx)"
has "结果传给了 ConsoleScreen" "$SCAFFOLD" "ConsoleScreen(vm, unreadErrors, hasUnreadCrash, onNavigate)"
has "ConsoleScreen 接收该参数" "$CONSOLE" "hasUnreadCrash: Boolean = false"
has "没有 ERROR 但有崩溃时退化成红点" "$CONSOLE" "} else if (hasUnreadCrash) {"
has "数字仍然优先（有 ERROR 时报条数）" "$CONSOLE" "if (unreadErrors > 0) {"
has "读盘失败当没有（不吓人）" "$SCAFFOLD" "runCatching { CrashLogger.hasUnseen(ctx) }.getOrDefault(false)"

echo "== 四、崩溃未读：单条已读（markSeen） =="

has "展开单条时标记该条" "$LOGS" "CrashLogger.markSeen(ctx, name)"
has "只标点开的那一条（不是全标）" "$LOGS" "unseenCrashNames - name"
has "列表按文件名带未读态" "$LOGS" "unseenCrashNames = CrashLogger.unseenCrashRecords(ctx).map { it.fileName }.toSet()"
has "分区接收未读集" "$LOGS" "unseen: Set<String> = emptySet()"
has "未读条目有红点" "$LOGS" "if (record.fileName in unseen) {"
has "横幅的全标仍在（两者分工）" "app/src/main/java/com/beichen/polaris/ui/Components.kt" "CrashLogger.markAllSeen(ctx)"

echo "== 五、租约句柄（outstandingCount） =="

has "运行状态卡查了句柄数" "$MODEL" "LeaseRegistry.outstandingCount(LocalLlmLease.ID)"
has "只在有未归还时显示（常态不占地方）" "$MODEL" "if (leaseHandles > 0) {"
has "id 单一来源" "$LEASE" 'const val ID = "local-llm"'
has "默认参数引用常量（不是再写一遍字面量）" "$LEASE" "override val id: String = ID"
hasnt "没有第二处硬编码 id" "$MODEL" 'outstandingCount("local-llm")'
has "计数据本体仍在注册表" "$REG" "fun outstandingCount(id: String): Long"

echo "== 六、回合导出复用（exportNdjson） =="

has "全量导出复用单回合导出" "$JOURNAL" 'builder.append(exportNdjson(f.name.removeSuffix(".$EXT")))'
# 只数**代码行**：注释里提到 encode 是在解释「以前有两处」，不该算进去。
ENC=$(grep "RunEventJson.encode" "$JOURNAL" 2>/dev/null | grep -vc "^[[:space:]]*//" || true)
ENC=${ENC:-0}
if [ "$ENC" = "1" ]; then
    pass "编码只有一处（改动不会只漏一半）"
else
    fail "RunEventJson.encode 出现 $ENC 处代码——两份导出各编一遍，格式迟早分叉"
fi
has "单回合导出仍公开（R7 四条行为断言依赖它）" "$JOURNAL" "fun exportNdjson(turnId: String): String"

echo "== 七、删除确认：已有等价实现的四条 =="

hasnt "GgufModelStore 不再有 isDownloaded" "$GGUF" "fun isDownloaded("
hasnt "ShellUiBackend 不再有 keyEvent" "$SHELLUI" "fun keyEvent("
hasnt "无障碍服务不再有 getUiTreeJson" "$A11Y" "fun getUiTreeJson("
hasnt "调度器不再有 runNow" "$WORKER" "fun runNow("
has "ui_key 仍覆盖同一个动作（无障碍 → shell 兜底）" "app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt" '"input keyevent $c"'
has "ui_tree 直接取快照（要 kept/filtered 写文案）" "app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt" "svc.getFreshUiTreeSnapshot()"
has "立即运行改走前台对话" "app/src/main/java/com/beichen/polaris/ui/WorkflowFacade.kt" "fun runWorkflowNow(id: String)"

echo "== 八、不制造第二条路径（反向） =="

hasnt "导入没有第二处格式判定" "$MODEL" "LocalEngineCatalog.detectFormat"
hasnt "控制台红点没有复用 unreadErrors（崩溃不在 logs 里）" "$CONSOLE" "badge = if (unreadErrors > 0) {"
has "台账与实现同步（R19 已覆盖，这里只确认状态没退回 RESERVED）" "scripts/qa/contract-inventory.txt" "WIRED | hasUnseen"

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
