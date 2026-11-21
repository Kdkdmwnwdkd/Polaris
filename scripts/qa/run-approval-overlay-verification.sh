#!/usr/bin/env bash
# 跨应用审批浮层判据（2026-11-21）。
#
# 由来（用户真机反馈）：让 Agent 去 QQ 发消息，界面已经跳到 QQ，审批卡却在北辰里。
# 用户必须切回北辰点「允许」再切回 QQ；一回去 Agent 可能又把界面跳走，来回折腾，
# 30 秒一过自动拒绝。
#
# 修法：审批卡改用无障碍浮层（TYPE_ACCESSIBILITY_OVERLAY），画在所有应用之上。
# 这条判据盯的是「浮层不可能变成新的坑」：
#   [A] 窗口类型只能是无障碍浮层——换成 APPLICATION_OVERLAY 就要多要一个
#       SYSTEM_ALERT_WINDOW，用户得自己去设置里开；
#   [B] 窗口标志三位一体：不抢焦点（否则顶掉输入法）、可触摸（否则按钮点不动）、
#       窗外穿透（否则挡住目标应用）；
#   [C] 必须排除在无障碍树之外——否则 ui_tree 会把「允许」读给模型，模型自己批自己；
#   [D] 接线完整：服务连上挂、销毁摘、ViewModel 发、撤销、绑定决策；
#   [E] 不双份：浮层在线时应用内对话框必须让位；
#   [F] 挂载失败必须降级，不能静默黑屏。
#
# 退出码：0 通过 / 1 有违规。
set -uo pipefail
cd "$(dirname "$0")/../.."

OVERLAY=app/src/main/java/com/beichen/polaris/automation/ApprovalOverlay.kt
BRIDGE=app/src/main/java/com/beichen/polaris/automation/ApprovalBridge.kt
SERVICE=app/src/main/java/com/beichen/polaris/automation/AgentAccessibilityService.kt
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
HOST=app/src/main/java/com/beichen/polaris/ui/ApprovalHost.kt

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }
say()  { echo "$*"; }

# 只取「代码行」：注释里出现的标记词（比如解释「不能加 FLAG_NOT_TOUCHABLE」）
# 不能被当成实现命中，否则判据会把一段正确的注释判成缺陷。
code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

# ── 浮层要素：抽成函数，反向自检时喂坏样本 ────────────────────────────────────
overlay_ok() {
  local f=$1
  # A：只能是无障碍浮层
  code "$f" | grep -q 'TYPE_ACCESSIBILITY_OVERLAY' || return 1
  if code "$f" | grep -q 'TYPE_APPLICATION_OVERLAY'; then return 1; fi
  # B：不抢焦点 + 可触摸 + 窗外穿透
  code "$f" | grep -q 'FLAG_NOT_FOCUSABLE' || return 1
  if code "$f" | grep -q 'FLAG_NOT_TOUCHABLE'; then return 1; fi
  code "$f" | grep -q 'FLAG_NOT_TOUCH_MODAL' || return 1
  # C：不进无障碍树
  code "$f" | grep -q 'IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS' || return 1
  # F：挂载失败必须显式降级
  code "$f" | grep -q 'markOverlayReady(false)' || return 1
  return 0
}

say "=== 跨应用审批浮层判据 ==="
say ""

say "[A] 浮层文件与窗口类型"
if [ ! -f "$OVERLAY" ]; then
  bad "找不到 $OVERLAY"
else
  if code "$OVERLAY" | grep -q 'TYPE_ACCESSIBILITY_OVERLAY'; then
    good "用 TYPE_ACCESSIBILITY_OVERLAY（不需要 SYSTEM_ALERT_WINDOW）"
  else
    bad "没有用 TYPE_ACCESSIBILITY_OVERLAY"
  fi
  if code "$OVERLAY" | grep -q 'TYPE_APPLICATION_OVERLAY'; then
    bad "用了 TYPE_APPLICATION_OVERLAY——它要求 SYSTEM_ALERT_WINDOW，等于多要一个用户得手动开的权限"
  else
    good "没有退化成 APPLICATION_OVERLAY"
  fi
fi

say ""
say "[B] 窗口标志：不抢焦点 / 可触摸 / 窗外穿透"
if [ -f "$OVERLAY" ]; then
  code "$OVERLAY" | grep -q 'FLAG_NOT_FOCUSABLE' \
    && good "FLAG_NOT_FOCUSABLE：不顶掉输入法（自动化可能正在打字）" \
    || bad "缺 FLAG_NOT_FOCUSABLE：浮层会抢焦点，把输入法顶掉"
  if code "$OVERLAY" | grep -q 'FLAG_NOT_TOUCHABLE'; then
    bad "有 FLAG_NOT_TOUCHABLE：按钮永远点不动"
  else
    good "没有 FLAG_NOT_TOUCHABLE（按钮可点）"
  fi
  code "$OVERLAY" | grep -q 'FLAG_NOT_TOUCH_MODAL' \
    && good "FLAG_NOT_TOUCH_MODAL：窗外触摸穿透到下面的应用" \
    || bad "缺 FLAG_NOT_TOUCH_MODAL：浮层会吞掉窗外触摸"
fi

say ""
say "[C] 不进无障碍树（否则模型能自己点「允许」）"
if [ -f "$OVERLAY" ]; then
  code "$OVERLAY" | grep -q 'IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS' \
    && good "浮层及其子节点对无障碍不可见" \
    || bad "缺 IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS：ui_tree 会读到「允许」按钮，模型可以自己批自己"
fi

say ""
say "[D] 接线：服务挂载 / 销毁摘除 / ViewModel 发布与撤销 / 决策回填"
if grep -A6 'override fun onServiceConnected' "$SERVICE" | grep -q 'ApprovalOverlay.attach'; then
  good "onServiceConnected 挂载浮层"
else
  bad "onServiceConnected 没挂载浮层"
fi
if grep -A6 'override fun onDestroy' "$SERVICE" | grep -q 'ApprovalOverlay.detach'; then
  good "onDestroy 摘除浮层"
else
  bad "onDestroy 没摘除浮层（服务停了窗口还在，点了没人接）"
fi
if grep -q 'ApprovalBridge.publish(' "$VM"; then
  good "ChatViewModel 发布审批请求"
else
  bad "ChatViewModel 没发布审批请求——浮层永远不显示"
fi
if grep -B4 '_pendingApproval.value = null' "$VM" | grep -q 'ApprovalBridge.withdraw()'; then
  good "finally 里撤销浮层卡"
else
  bad "finally 没撤销浮层卡——审批结束后卡片会留在屏幕上"
fi
if grep -q 'ApprovalBridge.bind(::approveOnce' "$VM"; then
  good "决策回填：浮层三个按钮接到 approveOnce / approveAlways / denyApproval"
else
  bad "没绑定决策回填——点浮层按钮没有效果"
fi

say ""
say "[E] 不双份：浮层在线时应用内对话框必须让位"
if grep -q 'ApprovalBridge.overlayReady' "$HOST" && grep -q 'if (overlayReady) return' "$HOST"; then
  good "ApprovalHost 读 overlayReady 并提前返回"
else
  bad "ApprovalHost 没有让位逻辑：浮层和对话框会同时出现，点一个另一个还挂着"
fi

say ""
say "[F] 挂载失败必须降级，不能静默"
if [ -f "$OVERLAY" ]; then
  if code "$OVERLAY" | grep -B6 'markOverlayReady(false)' | grep -q 'addView'; then
    good "addView 失败路径会置 overlayReady=false（退回应用内对话框）"
  else
    bad "addView 失败时没有降级——用户一个卡都看不到，只能等 30 秒自动拒绝"
  fi
fi

say ""
say "[G] 反向自检：坏样本必须被判红，好样本必须判绿"
TMP=$(mktemp -d)
cat > "$TMP/bad.kt" <<'EOF'
val lp = WindowManager.LayoutParams(0, 0, TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
EOF
cat > "$TMP/good.kt" <<'EOF'
val lp = WindowManager.LayoutParams(0, 0, TYPE_ACCESSIBILITY_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
ApprovalBridge.markOverlayReady(false)
EOF
if overlay_ok "$TMP/bad.kt"; then
  bad "自检失效：坏样本（APPLICATION_OVERLAY + NOT_TOUCHABLE）被判过了"
else
  good "坏样本被正确判红"
fi
if overlay_ok "$TMP/good.kt"; then
  good "好样本被判绿（判据没有写死到具体文件）"
else
  bad "自检失效：好样本被判红，判据本身有问题"
fi
rm -rf "$TMP"

say ""
if [ "$fail" -eq 0 ]; then
  echo "审批浮层：$npass 通过 / 0 失败"
  echo "FAIL=0"
  exit 0
else
  echo "审批浮层：$npass 通过 / $fail 失败"
  exit 1
fi
