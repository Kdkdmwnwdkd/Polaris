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

# 长连接是第四档，语义与前三档都不同：WebSocket 建立后 readTimeout 变成
# 「两帧之间允许多久没消息」，短档的 30s 会在长句中间把自己掐断。
# 它必须带 ping 保活，否则服务端静默十几秒就断，表现是「说到一半没了」。
if grep -qE 'val ws: OkHttpClient by lazy \{' "$HUB" && grep -qE 'ping = 20' "$HUB"; then
  good "B4 ws = 10/60 + 20s ping（长连接保活）"
else
  bad "B4 ws 预设缺失或丢了 ping 保活 —— 长句会说到一半断掉"
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
echo "[D] 阻塞调用收口"

# 为什么守这条：`newCall(x).execute()` 在协程被取消时**不会停**——线程照旧卡在 socket
# 读上，要等对端发完或 readTimeout（llm 档 180s）。用户点「停止」只停了界面，请求还在跑、
# 还在计费。2026-11-26 真机实测：点停止到收尾隔了 14 秒。
# 正确做法只有一处：LlmHttp.runCancellable（把 Call 挂到协程取消的那一刻，同步 cancel()）。
#
# 2026-11-26 批二已收口：媒体四家 9 处 + ModelDownloader 2 处。白名单已删除，D1 覆盖全仓。
stray_exec=$(grep -rn "\.execute()" --include=*.kt "$SRC" "$SRC_CORE" \
  | grep -v "core/llm/LlmHttp.kt" \
  | grep -vE ":[0-9]+:[[:space:]]*(//|\*)" || true)
if [ -z "$stray_exec" ]; then
  good "D1 除 runCancellable 内部外，全仓无裸 execute()"
else
  bad "D1 出现裸 execute()（点停止会等它自己读到超时）："
  echo "$stray_exec" | sed 's/^/         /'
fi

# 反面：别再用 enqueue 只等响应头那套。它覆盖不到 body 读取——真机上「回了头、
# 慢慢吐 body」的请求照样卡满，但看起来像修好了（906 就是这么误判的）。
# 注：suspendCancellableCoroutine 本身没问题，把系统回调转挂起（无障碍、OCR、WebView、
# GGUF 下载）是正当用法，所以这里只拦「给 OkHttp 造等待器」这一种。
dup=$(grep -rnE "fun Call\.awaitResponse|class CancellableCall" --include=*.kt "$SRC" "$SRC_CORE" || true)
if [ -z "$dup" ]; then
  good "D2 没有第二套 OkHttp 取消实现（enqueue 方案覆盖不到 body 读取）"
else
  bad "D2 出现自造的 OkHttp 等待器：改用 runCancellable"
  echo "$dup" | sed 's/^/         /'
fi

if grep -q "suspend fun <T> OkHttpClient.runCancellable" "$HUB"; then
  good "D3 runCancellable 入口仍在"
else
  bad "D3 runCancellable 被删或改了签名"
fi

# 媒体与下载是「用户正在盯着进度条」的两条路，单独钉一遍：
# D1 只保证没有裸 execute()，理论上还能有人绕开（比如新加一个文件直接用别的客户端）。
# 这四家 provider 各要至少一处 runCancellable，ModelDownloader 要两处（清单 + 整包）。
media_ok=0
for f in "$SRC_CORE/core/media/providers/"*MediaProvider.kt; do
  [ -f "$f" ] || continue
  if ! grep -q "runCancellable(" "$f"; then
    bad "D4 ${f##*/} 没有走 runCancellable：退出页面后请求仍会跑完"
    media_ok=1
  fi
done
dl=$(grep -c "runCancellable(" "$SRC_CORE/model/ModelDownloader.kt" 2>/dev/null || echo 0)
if [ "$dl" -lt 2 ]; then
  bad "D4 ModelDownloader 只有 $dl 处 runCancellable（清单 + 整包都要）"
  media_ok=1
fi
[ "$media_ok" -eq 0 ] && good "D4 媒体 4 家与模型下载全部可取消"

echo ""
echo "=== $npass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]