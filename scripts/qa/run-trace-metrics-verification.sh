#!/usr/bin/env bash
# 度量脚本自省（P5-1 的消费者）。
#
# 这个脚本验的不是 App，而是 **trace-metrics.py 自己算得对不对**。
# 理由很直接：三项度量是「方案是否生效」的唯一证据，如果计算本身错了，
# 它会给出一个看起来很好的数字，然后所有人拿它当结论。这类错误没有症状。
#
# 三段：
#   1) 内置样例（--selftest）：3600 行以内的手算答案；
#   2) 端到端：造一份 ndjson → --json → 逐字段比对；
#   3) 门禁：给阈值 → 超限时退出码 1（CI 靠它拦住退化）。
set -uo pipefail
cd "$(dirname "$0")/../.."

PY=scripts/qa/trace-metrics.py
fail=0
pass=0

ok() { pass=$((pass + 1)); echo "  ✅ $1"; }
bad() { fail=$((fail + 1)); echo "  ❌ $1"; }

[ -f "$PY" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Python 脚本：$PY" >&2; exit 3; }
command -v python3 >/dev/null || { echo "KS_HARNESS_SKIP $(basename "$0") 没有 python3（PATH 里找不到可执行文件）" >&2; exit 3; }

echo "[1/3] 内置样例"
out=$(python3 "$PY" --selftest 2>&1); rc=$?
if [ $rc -eq 0 ]; then ok "内置样例全部对得上"; else bad "内置样例算错：$(echo "$out" | grep '❌' | head -3)"; fi

echo "[2/3] 端到端"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/sample.ndjson" <<'JSONL'
{"ev":"turn.start","ts":1000,"turn":"t1","scope":"main","step":0,"ms":0,"chars":10,"tools":5}
{"ev":"llm.call","ts":1100,"turn":"t1","scope":"main","step":0,"ms":100,"attempt":1}
{"ev":"llm.end","ts":1200,"turn":"t1","scope":"main","step":0,"ms":200,"attempt":3,"dur":800,"ok":false,"code":"auth","calls":0}
{"ev":"store.write","ts":1300,"store":"conversations","bytes":1572864,"dur":40,"coalesced":9}
{"ev":"turn.end","ts":1400,"turn":"t1","scope":"main","step":0,"ms":400,"status":"failed","steps":1}
{"ev":"turn.start","ts":2000,"turn":"t2","scope":"main","step":0,"ms":0,"chars":8,"tools":5}
{"ev":"llm.end","ts":2100,"turn":"t2","scope":"main","step":0,"ms":100,"attempt":1,"dur":3000,"ok":false,"code":"rate_limit","calls":0}
{"ev":"store.write","ts":2200,"store":"conversations","bytes":524288,"dur":20,"coalesced":1}
{"ev":"store.repair","ts":2300,"store":"journal","n":1,"note":"turn=x"}
{"ev":"turn.end","ts":2400,"turn":"t2","scope":"main","step":0,"ms":400,"status":"completed","steps":1}
JSONL

json=$(python3 "$PY" "$TMP/sample.ndjson" --json 2>&1)
field() { echo "$json" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['$1'])"; }

[ "$(field turns)" = "2" ] && ok "回合数 2" || bad "回合数=$(field turns)"
# 只有 auth 那条计入；rate_limit 不计（退避后能成功，不算白等）
[ "$(field det_count)" = "1" ] && ok "只有确定性错误被计入（429 排除）" || bad "det_count=$(field det_count)"
[ "$(field det_wait_ms_avg)" = "800" ] && ok "白等均值取的是失败那次调用的耗时" || bad "白等=$(field det_wait_ms_avg)"
[ "$(field det_retried)" = "1" ] && ok "识别出「确定性错误还重试了 3 次」" || bad "det_retried=$(field det_retried)"
[ "$(field bytes_per_turn_avg)" = "1048576" ] && ok "每回合写入归到所属回合（(1.5MB+0.5MB)/2）" || bad "bytes=$(field bytes_per_turn_avg)"
[ "$(field bytes_per_turn_max)" = "1572864" ] && ok "峰值回合 1.5MB" || bad "峰值=$(field bytes_per_turn_max)"
[ "$(field repaired)" = "1" ] && ok "重放次数 1" || bad "repaired=$(field repaired)"

human=$(python3 "$PY" "$TMP/sample.ndjson" 2>&1)
grep -q "不该重试的错误被重试了" <<<"$human" && ok "人读输出点名了「不该重试却重试」" || bad "人读输出缺少归因"
grep -q "目标 <1MB" <<<"$human" && ok "人读输出带目标值（不带目标的数字没法判断好坏）" || bad "人读输出缺目标"

echo "[3/3] 门禁"
python3 "$PY" "$TMP/sample.ndjson" --max-deterministic-ms=500 >/dev/null 2>&1
[ $? -eq 1 ] && ok "白等超限 → 退出码 1" || bad "白等超限没拦住"
python3 "$PY" "$TMP/sample.ndjson" --max-deterministic-ms=100000 --max-turn-bytes=100000000 >/dev/null 2>&1
[ $? -eq 0 ] && ok "放宽阈值 → 退出码 0" || bad "放宽阈值仍报错"
python3 "$PY" "$TMP/sample.ndjson" --max-repair=0 >/dev/null 2>&1
[ $? -eq 1 ] && ok "出现重放 → 退出码 1" || bad "重放没拦住"

# 空文件 / 不存在：必须是用法错误（2），不能假装成功
python3 "$PY" "$TMP/nope.ndjson" >/dev/null 2>&1
[ $? -eq 2 ] && ok "文件不存在 → 退出码 2（不假装成功）" || bad "文件不存在时退出码不对"

echo
echo "结果：PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ] || exit 1
