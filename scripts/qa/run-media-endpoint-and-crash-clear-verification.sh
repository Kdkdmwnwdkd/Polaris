#!/usr/bin/env bash
# 出图端点与崩溃清空的行为验证（2026-11-27 真机第二轮）。
#
# 两件事，都是「用户明明看着界面，却做不到」：
#
#  ① 生图 404。用户四条通道里只有 openai 有 Key，请求却打到了
#     `api.deepseek.com/v1/images/generations` —— 因为 OpenAI 图片通道的 baseUrl
#     一直在借用**对话**端点（`store.load().baseUrl`）。用户主对话换成 deepseek，
#     图片就跟着跑到 deepseek，那里没有 images 接口，必然 404。
#     报错长得像「中转站不支持」，把人和我一起带偏。
#
#  ② 崩溃删不掉。用户点「删除」，10 条崩溃还在——因为那个按钮只删日志；
#     而崩溃自己的「清空」藏在展开后的最底部，默认折叠状态下根本不存在。
#
# 这两处都只能静态断言：LogsScreen 拖 Compose，MediaRegistry 拖 ConfigStore/Android，
# 沙箱里编不动。所以判据要钉在「语义位置」上，而不是钉某一行的字面写法。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

REG=core/src/main/java/com/beichen/polaris/core/media/MediaRegistry.kt
STOR=core/src/main/java/com/beichen/polaris/core/ConfigStore.kt
OAI=core/src/main/java/com/beichen/polaris/core/media/providers/OpenAIMediaProvider.kt
LOGS=app/src/main/java/com/beichen/polaris/ui/LogsScreen.kt

pass=0; fail=0
ok()   { echo "  PASS  $*"; pass=$((pass + 1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }

for f in "$REG" "$STOR" "$OAI" "$LOGS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

echo "[M] 出图端点与对话端点分离"

# M1 负向：图片通道不许再拿对话的 baseUrl 构造。
# 这一条是根因本身——它回来了，404 就回来了。
#
# 判据不能写成「OpenAIMediaProvider(...) 参数里含 store.load().baseUrl」：
# `[^)]*` 会在 `("openai")` 的括号处截断，改回旧写法反而匹配不到（变异测试抓出来的）。
# 这个文件里除了给 OpenAI 传 baseUrl，没有任何地方需要 load().baseUrl，
# 所以直接对整个文件做「去注释后不得出现」的判定，宽一点但抓得准。
REG_CODE=$(sed 's://.*::' "$REG")
if printf '%s\n' "$REG_CODE" | grep -q 'store\.load()\.baseUrl'; then
  bad "M1 OpenAI 图片通道仍在借用对话 baseUrl —— 主对话换别家就必然 404"
else
  ok "M1 OpenAI 图片通道不再借用对话 baseUrl"
fi

# M2 媒体 baseUrl 有独立读取，且有官方回落（不能因为没配就是空串）。
if grep -q 'loadMediaBaseUrl("openai")' "$REG" &&
   grep -q 'DEFAULT_BASE_URL' "$REG"; then
  ok "M2 媒体 baseUrl 独立读取，未配置时回落官方端点"
else
  bad "M2 MediaRegistry 没接 loadMediaBaseUrl / 没回落 DEFAULT_BASE_URL"
fi

# M3 存储层有独立键
if grep -q 'fun saveMediaBaseUrl' "$STOR" && grep -q 'fun loadMediaBaseUrl' "$STOR"; then
  ok "M3 ConfigStore 提供媒体 baseUrl 读写"
else
  bad "M3 ConfigStore 缺 saveMediaBaseUrl / loadMediaBaseUrl"
fi

# M4 空串必须删键而不是存空串。空串拼出来是相对路径 `/images/generations`，
# OkHttp 直接抛 IllegalArgumentException，比 404 更难读。
if grep -q 'remove(KEY_MEDIA_BASE_PREFIX + providerId)' "$STOR"; then
  ok "M4 空值走 remove（不留空串）"
else
  bad "M4 空值仍写进存储 —— 会拼出相对路径，报错更难读"
fi

# M5 尾斜杠：`https://x/v1/` + `/images/generations` = `//images`，部分网关 404。
if grep -q "trimEnd('/')" "$OAI"; then
  ok "M5 baseUrl 去尾斜杠"
else
  bad "M5 baseUrl 没去尾斜杠 —— 用户照抄带斜杠的地址就会 404"
fi

# M6 404 的错误里必须写清「当前端点」。原文只有 {"message":"Not Found"}，
# 读的人不可能想到是端点指错了地方。
if grep -A 6 '404 ->' "$OAI" | grep -q 'Base URL'; then
  ok "M6 404 报错带当前 Base URL"
else
  bad "M6 404 报错没写端点 —— 用户只能猜"
fi

echo ""
echo "[C] 崩溃记录的清空入口"

# 提取 CrashSection 函数体，按「行号先后」判定入口位置。
BODY=$(awk '/^private fun CrashSection\(/,/^}$/' "$LOGS")
[ -n "$BODY" ] || { echo "FAIL 找不到 CrashSection 函数体"; exit 1; }

clear_ln=$(printf '%s\n' "$BODY" | grep -n 'onClick = onClear' | head -1 | cut -d: -f1)
open_ln=$(printf '%s\n' "$BODY" | grep -n 'if (blockOpen)' | head -1 | cut -d: -f1)

# C1 清空入口必须在「展开」分支之前——也就是折叠态就能看见。
# 修前它在 if (blockOpen) 里面、records 全部渲染之后，用户永远滚不到。
if [ -n "$clear_ln" ] && [ -n "$open_ln" ] && [ "$clear_ln" -lt "$open_ln" ]; then
  ok "C1 清空入口在折叠态可见（第 $clear_ln 行 < 展开分支第 $open_ln 行）"
else
  bad "C1 清空入口仍在展开分支里（clear=$clear_ln open=$open_ln）—— 折叠时等于没有"
fi

# C2 清空要二次确认：崩溃报告是进程死前唯一的现场，误删不可恢复。
if grep -q 'showClearCrash' "$LOGS" && grep -q 'title = "清空崩溃记录"' "$LOGS"; then
  ok "C2 清空崩溃有二次确认"
else
  bad "C2 清空崩溃没有二次确认 —— 一次误触就把现场删了"
fi

# C3 确认之后必须真删，并且刷新列表（否则用户看到「确认了但记录还在」）。
CONFIRM=$(awk '/title = "清空崩溃记录"/,/onDismiss/' "$LOGS")
if printf '%s\n' "$CONFIRM" | grep -q 'clearCrashFiles'; then
  ok "C3 确认后调用 clearCrashFiles"
else
  bad "C3 确认框没有真删 —— 会变成「确认了却没反应」"
fi

# C4 日志删除与崩溃清空是两件事，入口文案必须区分开。
# 用户就是点了「删除」发现崩溃还在，才说「删不掉」。
if grep -q 'text = "删除日志"' "$LOGS"; then
  ok "C4 日志按钮文案已明确为「删除日志」"
else
  bad "C4 日志按钮仍叫「删除」—— 用户会以为它能删崩溃"
fi

# C5 日志删除的确认文案要主动说明「崩溃不在这里删」，
# 否则用户仍然会在两个地方之间来回试。
if grep -q '崩溃记录不在这里删' "$LOGS"; then
  ok "C5 日志确认框说明崩溃记录另行处理"
else
  bad "C5 日志确认框没提崩溃 —— 用户仍会以为漏删"
fi

echo ""
echo "=== 合计 $pass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]
