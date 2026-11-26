#!/usr/bin/env bash
# 工具面预算门（P3-5 配套）：工具表是**每轮都要付**的成本，涨了必须有人拦。
#
# 背景：架构方案的「度量」表里写着「工具面默认 token ≈525，目标 ≤525（不退化）」。
# 那张表在这之前只是个数字——没有任何断言读它。于是「给某个工具的描述补两句说明」
# 这种看似无害的改动，会把每轮成本加上去，而所有现有断言都不响（它们验的是
# 「分组对不对」，不是「付多少钱」）。
#
# 这门卡三件事：
#   1. 默认面（云端只开 file）≤ 525 token；
#   2. 满载（file + 最重的 2 组）≤ 3500 token；
#   3. 源码里的每个工具都在能力清单里登记（否则它会落进 misc，组索引查不到）。
#
# 数字来自 `scripts/tool-surface-audit.py`：它静态解析真实源码里的 description 与
# parameters，比 harness 里那份「每个工具 90 字模板话」的假数据准得多。
set -uo pipefail
cd "$(dirname "$0")/../.."

AUDIT="scripts/tool-surface-audit.py"
[ -f "$AUDIT" ] || { echo "FAIL: 找不到 $AUDIT"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "KS_HARNESS_SKIP $(basename "$0") 没有 python3（PATH 里找不到可执行文件）"; exit 3; }

pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ✅ $1"; }
bad()  { fail=$((fail+1)); echo "  ❌ $1"; }

echo "[1/3] 预算门（真实源码口径）"
out=$(python3 "$AUDIT" --gate 2>&1); rc=$?
echo "$out" | sed -n '/预算门/,/^$/p' | sed 's/^/    /'
[ $rc -eq 0 ] && ok "预算门通过（默认面 ≤525 / 满载 ≤3500）" || bad "预算门未通过（exit=$rc）"

echo "[2/3] 清单覆盖"
if grep -q "全部登记（0 个落进 misc）" <<<"$out"; then
  ok "所有工具都在清单里"
else
  bad "有工具没登记：$(echo "$out" | grep "不在清单里" || echo '未输出覆盖行')"
fi

echo "[3/3] 反向验证：把预算压到 1 token，门必须红"
out2=$(python3 "$AUDIT" --gate --budget-default=1 --budget-peak=1 2>&1); rc2=$?
if [ $rc2 -ne 0 ] && grep -q "❌" <<<"$out2"; then
  ok "预算超标时确实报红（exit=$rc2）"
else
  bad "预算被压到 1 仍不报红——这道门是装饰（exit=$rc2）"
fi

echo
echo "工具面预算：$pass 通过 / $fail 失败"
[ $fail -ne 0 ] && exit 1
echo "PASS 工具面预算（默认面 ≤525 / 满载 ≤3500 / 清单全覆盖）"
exit 0
