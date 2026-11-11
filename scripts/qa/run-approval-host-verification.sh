#!/usr/bin/env bash
# 审计 F16：审批弹窗必须挂在全局，不能挂在某一个页面里。
#
# 为什么只能静态核对：`ApprovalHost` / `ApprovalDialog` 拖 Compose 运行时，
# 沙箱里编不动（与 F11 的 body-leak runner 同一约定）。它们守的是**结构事实**：
# 「审批的挂载点在哪里」。这类事实一旦回退，行为断言也抓不到——因为回退后
# 弹窗在对话页仍然正常显示，只有「用户不在对话页」时才消失。
#
# 真机证据（模拟器 API 30，2026-11-07）：
#   07:29:28 tool.start  tool=device_notifications args={"packages":["com.android.shell"]}
#   07:29:58 WARN [Approval] device_notifications 拒绝
#   07:29:58 tool.end code=approval_denied dur=30079ms      ← 30 秒超时自动拒绝
#   同一时刻 uiautomator dump 只有权限引导页文字，没有任何弹窗。
#
# 退出码：0 通过 / 1 有失败。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

HOST=app/src/main/java/com/beichen/polaris/ui/ApprovalHost.kt
SCREEN=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt
ACT=app/src/main/java/com/beichen/polaris/MainActivity.kt

pass=0
fail=0
ok()  { echo "  PASS  $*"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $*"; fail=$((fail + 1)); }

echo "=== 审计 F16：审批弹窗的挂载点 ==="
echo ""

for f in "$HOST" "$SCREEN" "$ACT"; do
  [ -f "$f" ] || { echo "FAIL 缺少 $f（无法判定，视为失败）"; exit 1; }
done

# W1：全局挂载点真的被调用。文件在、但没人调用，等于没修。
if grep -q 'ApprovalHost(vm = vm)' "$ACT"; then
  ok "W1 AgentKitApp 调用了 ApprovalHost"
else
  bad "W1 MainActivity 里找不到 ApprovalHost(vm = vm) —— 弹窗没有全局挂载点"
fi

# W2：调用点必须与 AppScaffold 同级（都在 AgentKitTheme 块内），而不是被塞进某一页。
host_line=$(grep -n 'ApprovalHost(vm = vm)' "$ACT" | head -1 | cut -d: -f1)
scaffold_line=$(grep -n 'AppScaffold(' "$ACT" | head -1 | cut -d: -f1)
theme_line=$(grep -n 'AgentKitTheme(' "$ACT" | head -1 | cut -d: -f1)
if [ -n "$host_line" ] && [ -n "$scaffold_line" ] && [ -n "$theme_line" ] \
   && [ "$host_line" -gt "$scaffold_line" ] && [ "$theme_line" -lt "$scaffold_line" ]; then
  ok "W2 挂载点在 AgentKitTheme 内、AppScaffold 之外（与路由无关）"
else
  bad "W2 挂载点位置不对：theme=$theme_line scaffold=$scaffold_line host=$host_line（应 theme < scaffold < host）"
fi

# W3：同一份状态源。挂载点自己再造一个「待审批」状态，会与 VM 的决策脱钩。
if grep -q 'vm.pendingApproval.collectAsStateWithLifecycle()' "$HOST"; then
  ok "W3 ApprovalHost 读的是 vm.pendingApproval（与审批决策同源）"
else
  bad "W3 ApprovalHost 没读 vm.pendingApproval —— 弹窗与决策可能不同步"
fi

# W4：ChatScreen 不得残留第二处挂载。两个 Dialog 同时渲染会各弹一次、
# 各自倒计时、点一次只回一个 CompletableDeferred。
# 注意判据带 `=`：函数定义是 `ApprovalDialog(request: ApprovalRequest, ...)`，
# 只有**调用**才写成 `ApprovalDialog(request = ...)`。用不带等号的串会把定义也算进来。
if grep -q 'ApprovalDialog(request =' "$SCREEN"; then
  bad "W4 ChatScreen 仍有 ApprovalDialog 挂载 —— 两处渲染 = 双弹窗"
else
  ok "W4 ChatScreen 不再挂载审批弹窗（单点）"
fi

# W5：弹窗本身要跨文件可见（internal），否则 AppScaffold 侧编不过；
# 但不能是 public（会变成对外 API，与「内部 UI 细节」的定位不符）。
if grep -q '^internal fun ApprovalDialog(' "$SCREEN"; then
  ok "W5 ApprovalDialog 是 internal（跨文件可用、不外泄）"
else
  bad "W5 ApprovalDialog 不是 internal —— 要么编不过，要么成了对外 API"
fi

# W6：退场动画的 150ms 保留在挂载点，不在两个地方各写一份。
if grep -q 'delay(150)' "$HOST"; then
  ok "W6 退场延迟只在挂载点一处"
else
  bad "W6 ApprovalHost 丢了退场延迟（request 消失瞬间卸载，动画会被截断）"
fi

echo ""
echo "=== 合计 $pass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]
