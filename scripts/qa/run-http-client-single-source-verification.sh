#!/usr/bin/env bash
# R-13（HTTP 客户端单一来源）验证。
#
# 为什么需要：收口之前全仓 14 处各自 `OkHttpClient.Builder()`，超时各写各的
# （10s / 12s / 20s / 30s / 60s / 120s / 180s）。收口本身不难，难的是**不再长回来**——
# 新增一个功能时顺手 `OkHttpClient()` 是最自然的写法，而这类回归没有任何症状：
# 请求照样能通，只是超时又变成了没人说得清的值。
#
# 所以这条断言守的不是「现在对不对」，是「以后不许退化」。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HUB="$SRC_CORE/core/llm/LlmHttp.kt"

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }

echo "=== R-13 HTTP 客户端单一来源 ==="
echo ""
echo "[A] 唯一构造点"

[ -f "$HUB" ] || { echo "FAIL 缺少 $HUB"; exit 1; }

# 允许出现 OkHttpClient.Builder() 的地方只有一处：LlmHttp 自己的 build()。
stray=$(grep -rn "OkHttpClient.Builder()" --include=*.kt "$SRC" "$SRC_CORE" | grep -v "core/llm/LlmHttp.kt" || true)
if [ -z "$stray" ]; then
  good "A1 除 LlmHttp 外无第二处 Builder 构造（全仓收口）"
else
  bad "A1 出现新的独立客户端："
  echo "$stray" | sed 's/^/         /'
fi

raw=$(grep -rn "OkHttpClient()" --include=*.kt "$SRC" "$SRC_CORE" | grep -v "^.*LlmHttp.kt" || true)
if [ -z "$raw" ]; then
  good "A2 无裸 OkHttpClient() （裸构造 = OkHttp 默认 10s 读超时）"
else
  bad "A2 出现裸 OkHttpClient()："
  echo "$raw" | sed 's/^/         /'
fi

echo ""
echo "[B] 三个预设的取值"

for p in "llm:15,180" "short:10,30" "download:30,120"; do
  name=${p%%:*}
  if grep -qE "val $name: OkHttpClient by lazy \{ build\(" "$HUB"; then
    good "B 预设 $name 存在"
  else
    bad "B 预设 $name 缺失"
  fi
done

if grep -qE 'val llm: OkHttpClient by lazy \{ build\(connect = 15, read = 180' "$HUB"; then
  good "B1 llm = 15/180（推理通道，覆盖首 token 前长时间静默）"
else
  bad "B1 llm 取值被改动（原 15/180）"
fi

if grep -qE 'val short: OkHttpClient by lazy \{ build\(connect = 10, read = 30' "$HUB"; then
  good "B2 short = 10/30（覆盖原 10/12/20 三档，只放宽）"
else
  bad "B2 short 取值被改动（原 10/30）"
fi

if grep -qE 'val download: OkHttpClient by lazy \{ build\(connect = 30, read = 120' "$HUB"; then
  good "B3 download = 30/120（大文件传输）"
else
  bad "B3 download 取值被改动（原 30/120）"
fi

echo ""
echo "[C] 兼容别名"

if grep -qE 'val shared: OkHttpClient get\(\) = llm' "$HUB"; then
  good "C1 shared 仍指向 llm（三处 LLM 客户端不用改）"
else
  bad "C1 shared 别名被删或改了语义"
fi

# 调用点数量：13 处外部 + 1 处 UI，收口后应全部走预设。
uses=$(grep -rn "LlmHttp\.\(llm\|short\|download\|shared\)" --include=*.kt "$SRC" "$SRC_CORE" | grep -v "LlmHttp.kt" | wc -l | tr -d ' ')
if [ "$uses" -ge 13 ]; then
  good "C2 $uses 处调用点已切到预设"
else
  bad "C2 只有 $uses 处调用预设（收口前是 14 处）"
fi

echo ""
echo "=== $npass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]
