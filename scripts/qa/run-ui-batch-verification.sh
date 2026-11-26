#!/usr/bin/env bash
# ui_batch 判据（2026-11-21）。
#
# 由来：用户真机反馈「无障碍操控又慢」。慢的不是点击（几十毫秒），是**每一次界面
# 动作都要一次完整的模型往返**——点搜索框、输入、回车、看结果、点第一条，五个动作
# 五次往返，每次 2~5 秒网络加 1.5 秒回执。ui_batch 把这五个动作压进一次调用。
#
# 这个工具的收益完全建立在「一次审批 + 批内连续执行」上，所以判据的重心不是
# 「有没有实现」，而是**三个安全边界有没有被绕过**：
#
#   [A] 白名单是写死的七个动作，不含 ui_screenshot——后者会回灌图片，
#       一批 8 个就是 8 张图，直接把上下文打爆；
#   [B] 不做 `ui_` 前缀匹配。前缀匹配等于把「一次审批」变成「任意 ui 工具批量执行」，
#       以后谁再加一个危险工具都会被自动纳进批处理；
#   [C] 先整体校验、再开始执行。半途因为一个拼错的 action 停下，前面已经点掉的
#       动作收不回来——这是「顺序」问题，用行号比，不看有没有写校验。
#
# 另有 [D] 审批与副作用标注、[E] 失败必须带 hint、[F] 反向自检（喂坏样本必须红）。
#
# 退出码：0 通过 / 1 有违规。
set -uo pipefail
cd "$(dirname "$0")/../.."

BATCH=app/src/main/java/com/beichen/polaris/automation/UiBatchTool.kt
ASM=app/src/main/java/com/beichen/polaris/tools/ToolAssembler.kt

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }
say()  { echo "$*"; }

# 剔掉整行注释。这个文件里有大段注释解释「为什么不能收 ui_screenshot」，
# 不剔的话 grep 会把解释当成实现，尤其反向自检会永远绿。
code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

# `code x | grep -q p` 在 `set -o pipefail` 下会假红：grep -q 命中即退出，上游 grep -v
# 还在写就收到 SIGPIPE（141），整条管道判假。文件越大、匹配越靠前越容易碰上，
# 表现为「同一份代码单跑全绿、全量跑偶发红」（2026-11-26 approval-overlay 就是这么红的）。
# 先落地成变量再匹配，管道就不存在了。
has() {
  local text
  text=$(code "$1") || true
  grep -q -- "$2" <<<"$text"
}

hasE() {
  local text
  text=$(code "$1") || true
  grep -qE -- "$2" <<<"$text"
}

say "ui_batch 判据"
say ""

# ---------- [A] 存在与注册 ----------
if [ -f "$BATCH" ]; then good "[A1] UiBatchTool.kt 存在"; else bad "[A1] 找不到 $BATCH"; fi
if has "$BATCH" 'override val name = "ui_batch"'; then
  good "[A2] 工具名是 ui_batch"
else
  bad "[A2] 工具名不是 ui_batch（模型看不见它）"
fi

# 注册必须传 this：它靠 registry.get(name) 找目标工具，拿不到注册表就是空壳。
if grep -q 'register(UiBatchTool(this))' "$ASM"; then
  good "[A3] 已在 ToolAssembler 注册且传入 registry"
else
  bad "[A3] 未注册，或没传 registry（工具表里看不到 / 找不到目标工具）"
fi

# 注册位置必须在全部 ui_* 之后：它按名字取工具，先注册等于取不到。
click_line=$(grep -n 'register(UiClickTool())' "$ASM" | head -1 | cut -d: -f1)
batch_line=$(grep -n 'register(UiBatchTool(this))' "$ASM" | head -1 | cut -d: -f1)
if [ -n "$click_line" ] && [ -n "$batch_line" ] && [ "$batch_line" -gt "$click_line" ]; then
  good "[A4] 注册在 ui_* 之后（依赖顺序正确）"
else
  bad "[A4] 注册顺序早于 ui_*，registry.get 会取不到目标工具"
fi

# ---------- [B] 安全边界 ----------
if has "$BATCH" 'ui_screenshot'; then
  bad "[B1] 白名单或映射里出现了 ui_screenshot（一批 8 张图会打爆上下文）"
else
  good "[B1] 白名单不含 ui_screenshot"
fi

if hasE "$BATCH" 'startsWith\("ui_"\)|name\.startsWith'; then
  bad "[B2] 出现 ui_ 前缀匹配（会把任意 ui 工具放进批处理，边界失效）"
else
  good "[B2] 不做前缀匹配，白名单写死"
fi

acts=$(code "$BATCH" | grep -oE '"[a-z_]+" to "ui_[a-z_]+"' | wc -l | tr -d ' ')
if [ "$acts" -eq 7 ]; then
  good "[B3] 白名单恰好 7 个动作（tap/click/long_press/swipe/input/key/tree）"
else
  bad "[B3] 白名单动作数 = $acts，期望 7（增删都要先想清楚安全边界）"
fi

steps=$(code "$BATCH" | grep -oE 'MAX_STEPS = [0-9]+' | grep -oE '[0-9]+' | head -1)
if [ -n "$steps" ] && [ "$steps" -le 10 ] && [ "$steps" -ge 2 ]; then
  good "[B4] MAX_STEPS = $steps（在 2..10 内）"
else
  bad "[B4] MAX_STEPS 缺失或超出 2..10：${steps:-无}"
fi

# ---------- [C] 先校验后执行 ----------
last_plan=$(code "$BATCH" | grep -n 'plan.add' | tail -1 | cut -d: -f1)
first_exec=$(code "$BATCH" | grep -n 'tool!!.execute\|\.execute(p.args' | head -1 | cut -d: -f1)
if [ -n "$last_plan" ] && [ -n "$first_exec" ] && [ "$first_exec" -gt "$last_plan" ]; then
  good "[C1] 全部步骤先校验再执行（plan.add 全部早于第一次 execute）"
else
  bad "[C1] 边解析边执行，或校验与执行交织——半途报错会留下已生效的动作"
fi

# ---------- [D] 审批与副作用 ----------
if has "$BATCH" 'override val needsApproval = true'; then
  good "[D1] needsApproval = true（整批一次审批）"
else
  bad "[D1] 未声明 needsApproval，批处理会绕过用户审批"
fi
if has "$BATCH" 'override val readOnly = false'; then
  good "[D2] readOnly = false（不被并行调度）"
else
  bad "[D2] 未标注 readOnly=false，会被当成只读工具并行执行"
fi

# ---------- [E] 失败必须带修复指引 ----------
if has "$BATCH" 'code = "batch_stopped"'; then
  if code "$BATCH" | grep -A4 'code = "batch_stopped"' | grep -q 'hint ='; then
    good "[E1] 中途失败返回 batch_stopped 且带 hint"
  else
    bad "[E1] batch_stopped 分支缺 hint（模型不知道从哪一步继续）"
  fi
else
  bad "[E1] 没有 batch_stopped 分支：某步失败后行为不明"
fi

# ---------- [F] 反向自检 ----------
# 把 ui_screenshot 注入白名单，判据必须变红；否则 [B1] 只是装饰。
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/automation" "$tmp/tools"
cp "$BATCH" "$tmp/automation/UiBatchTool.kt"
cp "$ASM" "$tmp/tools/ToolAssembler.kt"

# 变异 1：白名单里塞一个 ui_screenshot
awk '{ sub(/"tree" to "ui_tree"/, "\"tree\" to \"ui_tree\", \"shot\" to \"ui_screenshot\""); print }' \
  "$BATCH" > "$tmp/automation/m1.kt"
if has "$tmp/automation/m1.kt" 'ui_screenshot'; then
  good "[F1] 变异捕获：白名单注入 ui_screenshot 后判据变红"
else
  bad "[F1] 变异未被捕获：判据无法发现越权工具"
fi

# 变异 2：拿掉 needsApproval
sed 's/override val needsApproval = true/override val needsApproval = false/' \
  "$BATCH" > "$tmp/automation/m2.kt"
if has "$tmp/automation/m2.kt" 'override val needsApproval = true'; then
  bad "[F2] 变异未被捕获：摘掉审批标注后判据仍绿"
else
  good "[F2] 变异捕获：摘掉 needsApproval 后判据变红"
fi

# ---------- [G] 审批卡必须说人话 ----------
# 整批审批的代价是卡片上看不到每步细节。如果它显示的是 `{"steps":[...]}` 一坨，
# 用户看不懂就会直接点「允许」——这次审批等于没有。所以调用点必须带工具名。
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
if grep -q 'ApprovalPreview.of(toolName, args)' "$VM"; then
  good "[G1] 审批预览按工具名分派（ui_batch 走人话摘要）"
else
  bad "[G1] 审批预览退化成 ApprovalPreview.of(args.toString())，批处理卡片会显示一坨 JSON"
fi

if grep -q 'fun batchSummary' core/src/main/java/com/beichen/polaris/core/agent/ApprovalPreview.kt; then
  good "[G2] 摘要生成在 core（纯函数，可单测）"
else
  bad "[G2] 找不到 batchSummary：摘要逻辑缺失或散落在 UI 层"
fi

say ""
if [ "$fail" -eq 0 ]; then
  say "ui_batch：$npass 通过 / 0 失败"
  exit 0
else
  say "ui_batch：$npass 通过 / $fail 失败"
  exit 1
fi
