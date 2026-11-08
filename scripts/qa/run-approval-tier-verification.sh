#!/usr/bin/env bash
# 审批档位验证（W-RT3）：危险操作「问多严」的三档规则有没有接对。
#
# 起因：审批此前只有「问 / 不问」两态，连续界面操作被弹窗打断十几次，
# 想清净又只能一刀切全放。档位的全部价值在两条纪律里——坏配置退「常规」
# 永不放大权限、全自动只放低风险组（shell / app_manage / git_write 必问）。
# 所以这里既查静态（接线没断、低风险组清单没被改宽），也真编真跑（规则没有反着写）。
#
# 移植说明：来自 runtime/ssh-backend-w5（旧包名 com.example.agentkit）。
# 本轮架构里状态持在 SettingsFacade 而不是 VM，故两条断言的落点随之改到 Facade 上；
# 另加一条「逐步确认连截图回合缓存也不用」——旧分支没有这个缓存，是本轮既有行为。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

TIER="core/src/main/java/com/beichen/polaris/core/agent/ApprovalTier.kt"
GROUPING="core/src/main/java/com/beichen/polaris/core/agent/ApprovalGrouping.kt"
CONFIG="core/src/main/java/com/beichen/polaris/core/ConfigStore.kt"
VM="app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt"
FACADE="app/src/main/java/com/beichen/polaris/ui/SettingsFacade.kt"
SCREEN="app/src/main/java/com/beichen/polaris/ui/SettingsScreen.kt"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

has() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then pass "$name"; else fail "$name（缺：$needle）"; fi
}

hasnt() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then fail "$name（不该出现：$needle）"; else pass "$name"; fi
}

echo "== 静态：结构 =="

if [ -f "$TIER" ]; then pass "档位规则存在"; else fail "档位规则缺失：$TIER"; fi

# 与 LocalDeviceRules / ResourcePlan 同一条纪律：规则层必须纯 Kotlin，行为断言才编得过、跑得动。
hasnt "规则层不依赖 Android" "$TIER" "import android."

echo "== 静态：三档与坏配置纪律（改语义必须同步改这里） =="

has "三档齐全" "$TIER" "val ALL_MODES: List<String> = listOf(STRICT, STANDARD, AUTO)"
has "逐步确认：会话白名单也失效" "$TIER" "STRICT -> false"
has "坏配置回常规（normalize）" "$TIER" "fun normalize(mode: String?): String = if (mode != null && mode in ALL_MODES) mode else STANDARD"

# 全自动的边界是安全底线：清单里出现高风险组 = 配置坏了比修复前更危险。
hasnt "全自动不放行 shell 组" "$TIER" "GROUP_SHELL,"
hasnt "全自动不放行应用装卸组" "$TIER" "ApprovalGrouping.GROUP_APP_MANAGE"
hasnt "全自动不放行 GitHub 提交组" "$TIER" "ApprovalGrouping.GROUP_GIT_WRITE"
has "全自动放行界面操作组" "$TIER" "ApprovalGrouping.GROUP_UI_ACTION"
has "全自动放行截图组" "$TIER" "ApprovalGrouping.GROUP_UI_SCREENSHOT"
has "全自动放行设备输出组" "$TIER" "ApprovalGrouping.GROUP_DEVICE_OUTPUT"
has "全自动放行文件写入组" "$TIER" "ApprovalGrouping.GROUP_FILE_WRITE"

echo "== 静态：接线 =="

has "设置项存在" "$CONFIG" "approval_tier"
has "配置层坏值出口指向 normalize" "$CONFIG" "ApprovalTier.normalize"
has "状态持在 SettingsFacade" "$FACADE" "ApprovalTier.normalize(configStore.approvalTier())"
has "Facade 有 setter" "$FACADE" "fun setApprovalTier(mode: String)"
has "视图模型有状态" "$VM" "approvalTier: StateFlow<String>"
has "视图模型有 setter" "$VM" "fun setApprovalTier"
# 传的是归组键而不是 grant.key：shell 的 key 带命令前缀，拿 key 比集合永远不等。
has "审批入口走档位判定" "$VM" "ApprovalTier.shouldSkipDialog(mode, group, ApprovalScope.allows(grant, sessionAllowed))"
has "审批入口取归组键" "$VM" "val group = ApprovalGrouping.groupOf(tool.name)"
# 逐步确认档下截图回合缓存要失效，否则「每次都问」名不副实。
has "逐步确认不用截图回合缓存" "$VM" "mode != ApprovalTier.STRICT &&"
has "设置页有档位单选" "$SCREEN" "setApprovalTier"

echo "== 行为：规则真编真跑（有 kotlinc 才跑，CI 里由 gradle 真编） =="

if command -v kotlinc >/dev/null 2>&1; then
    TMP="$(mktemp -d)"
    cat > "$TMP/stubs.kt" <<'EOF'
// 行为断言专用最小桩：只补齐 ApprovalTier.kt 引用的归组常量，判定逻辑一律真实实现。
package com.beichen.polaris.core.agent

object ApprovalGrouping {
    const val GROUP_UI_ACTION = "ui_action"
    const val GROUP_UI_SCREENSHOT = "ui_screenshot"
    const val GROUP_APP_MANAGE = "app_manage"
    const val GROUP_FILE_WRITE = "file_write"
    const val GROUP_GIT_WRITE = "git_write"
    const val GROUP_DEVICE_OUTPUT = "device_output"
    const val GROUP_SHELL = "shell"
    const val GROUP_SHELL_DESTRUCTIVE = "shell_destructive"
}
EOF
    cat > "$TMP/check.kt" <<'EOF'
import com.beichen.polaris.core.agent.ApprovalTier
import com.beichen.polaris.core.agent.ApprovalGrouping
var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }
fun main() {
// 常规（默认）：与旧行为逐条一致
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.STANDARD, ApprovalGrouping.GROUP_SHELL, false) == false, "常规：未授权过，弹窗")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.STANDARD, ApprovalGrouping.GROUP_SHELL, true) == true, "常规：始终允许过，放行")

// 逐步确认：连「始终允许」也失效
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.STRICT, ApprovalGrouping.GROUP_SHELL, true) == false, "逐步确认：允许过也弹窗")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.STRICT, ApprovalGrouping.GROUP_UI_ACTION, false) == false, "逐步确认：低风险组也弹窗")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.STRICT, ApprovalGrouping.GROUP_FILE_WRITE, true) == false, "逐步确认：文件写入允许过也弹窗")

// 全自动：低风险组免弹窗，高风险组维持原判
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_UI_ACTION, false) == true, "全自动：界面操作放行")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_UI_SCREENSHOT, false) == true, "全自动：截图放行")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_DEVICE_OUTPUT, false) == true, "全自动：朗读通知放行")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_FILE_WRITE, false) == true, "全自动：文件写入放行（有自动备份）")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_SHELL, false) == false, "全自动：shell 仍弹窗")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_APP_MANAGE, false) == false, "全自动：应用装卸仍弹窗")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_GIT_WRITE, false) == false, "全自动：GitHub 提交仍弹窗")
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_SHELL, true) == true, "全自动：shell 但允许过，放行")
// 不可逆 shell 的键带命令前缀，天然不在允许集合里
ok(ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE, false) == false, "全自动：不可逆 shell 仍弹窗")

// 坏配置退常规，永不放大权限
ok(ApprovalTier.normalize("写坏的值") == ApprovalTier.STANDARD, "坏配置：回常规")
ok(ApprovalTier.normalize(null) == ApprovalTier.STANDARD, "未设置：回常规")
ok(ApprovalTier.normalize("") == ApprovalTier.STANDARD, "空串：回常规")
ok(ApprovalTier.normalize("AUTO") == ApprovalTier.AUTO, "合法值原样通过")
ok(ApprovalTier.normalize("strict") == ApprovalTier.STANDARD, "大小写不匹配也算坏配置（不猜）")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
    # 目录模式 java -cp "$TMP" 缺 kotlin-stdlib → JVM 静默崩（stderr 被吞，OUT 为空被判红）。
    # 改 -include-runtime jar 自包含，环境无关。
    if kotlinc "$TMP/stubs.kt" "$TIER" "$TMP/check.kt" -d "$TMP/check.jar" -include-runtime >/dev/null 2>&1; then
        OUT=$(java -jar "$TMP/check.jar" 2>/dev/null)
        echo "$OUT"
        echo "$OUT" | grep -q "FAIL=0" && pass "行为断言全过" || fail "行为断言有红"
    else
        fail "行为断言编译失败"
    fi
    rm -rf "$TMP"
else
    echo "  SKIP  本机无 kotlinc，行为断言交 CI（静态断言已全跑）"
fi

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
