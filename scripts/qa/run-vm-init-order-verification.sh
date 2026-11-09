#!/usr/bin/env bash
# ViewModel 初始化顺序守卫。
#
# 起因：ChatViewModel 3597 行，`init` 块在第 971 行，而大量字段在 1300 行之后才初始化。
# init 里启动的协程一旦抢在属性初始化之前执行，就会读到 null 抛 NPE。2026-11-09 在
# Android 模拟器上真复现了一次（四核、CPU 被别的任务占着）：
#
#   FATAL EXCEPTION: DefaultDispatcher-worker-2
#   java.lang.NullPointerException: Attempt to invoke virtual method
#     'void com.beichen.polaris.ui.WorkflowFacade.refreshWorkflows()' on a null object reference
#       at com.beichen.polaris.ui.ChatViewModel.refreshWorkflows(ChatViewModel.kt:1339)
#
# 编译期拦不住（语法完全合法），文本断言拦不住（「调用了」为真，只是调早了），
# CI 也拦不住（快机器上主线程总是先跑完构造）。只有慢设备真跑才会炸，
# 而这类崩溃发生在冷启动第一秒——用户看到的是「一开就闪退」。
#
# 所以这里守的不是「有没有调用」，是**顺序**：调用点必须晚于声明点。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
PASS=0; FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

[ -f "$VM" ] || { echo "FATAL: 缺 $VM" >&2; exit 1; }

DECL=$(grep -n "private val workflowFacade = WorkflowFacade(" "$VM" | head -1 | cut -d: -f1)
# 取第一个「构造期会真的跑」的调用点，两类排除：
#   `fun refreshWorkflows()` —— 委托实现本身，要等别人调；
#   `register(XxxTool(...) { refreshWorkflows() })` —— lambda 回调，工具被调用时才跑。
CALL=$(grep -n "refreshWorkflows()" "$VM" | grep -v "fun refreshWorkflows" | grep -v "register(" | head -1 | cut -d: -f1)

if [ -z "$DECL" ]; then
  fail "找不到 workflowFacade 声明（改名了？同步更新本脚本）"
elif [ -z "$CALL" ]; then
  fail "找不到列表载入的启动点（改名了？同步更新本脚本）"
elif [ "$CALL" -gt "$DECL" ]; then
  pass "列表载入晚于 facade 声明（行 $CALL > $DECL）"
else
  fail "列表载入（行 $CALL）早于 facade 声明（行 $DECL）——慢设备冷启动会 NPE"
fi

# 第一个 init 块（属性初始化之前执行的那个）里不得直接调 refreshWorkflows()。
# 只扫前 100 行：init 块本身不到 100 行，够用且不误伤后面的方法体。
INIT_START=$(grep -n "^    init {" "$VM" | head -1 | cut -d: -f1)
if [ -n "$INIT_START" ]; then
  HIT=$(sed -n "${INIT_START},$((INIT_START + 100))p" "$VM" | grep -c "refreshWorkflows()" || true)
  if [ "$HIT" = "0" ]; then
    pass "首个 init 块（行 $INIT_START 起）里没有 refreshWorkflows()"
  else
    fail "首个 init 块里出现 refreshWorkflows()——它在字段初始化之前执行"
  fi
else
  fail "找不到 init 块（结构变了？同步更新本脚本）"
fi

echo "== 汇总: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" = 0 ] || exit 1
