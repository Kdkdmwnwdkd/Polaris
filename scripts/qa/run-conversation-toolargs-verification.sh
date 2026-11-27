#!/usr/bin/env bash
# 会话工具参数（toolArgs）落盘/读取验证：**一条坏消息不许毁掉整个应用**。
#
# 2026-11-27 真机根因，崩溃报告里的 Caused by 原文：
#   Caused by: kotlinx.serialization.json.internal.JsonDecodingException: Unexpected EOF at path: $
#   JSON input: ....." width="7" height="11"/>\n    <rect x="322" y="215"
#       at ConversationStoreKt.parseToolArgs(ConversationStore.kt:376)
#       at ConversationStoreKt.toUiItem(ConversationStore.kt:274)
#       at ConversationStoreKt.toUiItems(ConversationStore.kt:341)
#       at ConversationProjection.loadOf(ConversationProjection.kt:197)
#       at ConversationProjection.<init>(ConversationProjection.kt:74)
#       at ChatViewModel.<init>(ChatViewModel.kt:990)
#
# 成因：toolArgs 与正文共用同一条 `take(MAX_FIELD_CHARS)`。正文是纯文本，切一半只是
# 显示不全；参数是**结构化 JSON**，切一半就是非法 JSON——下次启动解析必抛，
# 而它在 ViewModel 构造路径上，于是「点开就闪退、每次启动都复现」。
# 用户那一小时崩了十次，十次的 Caused by 一模一样。
#
# 为什么这里用静态断言而不是真跑：ConversationStore 拖 Android 依赖，沙箱编不动。
# 但这两个函数的行为边界很清楚，且**负向断言**（不许再出现 take 截断）能钉死回退，
# 这是本轮唯一需要防的事。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

STORE="core/src/main/java/com/beichen/polaris/core/ConversationStore.kt"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

echo "== 写入侧：toolArgs 不许被按字符截断 =="

# 负向断言。这条表达式就是根因本身：写回来就等于是把这个 bug 重新种回去。
# 只匹配**赋值点**：日志与遥测里也有一处 `.take(120)` / `.take(300)`，
# 那是往日志里写字，读不回来、不会解析，截断无害。钉得太宽会误伤它们。
if grep -qF 'toolArgs = call.arguments.toString().take(' "$STORE"; then
    fail "toMsgRecord 又把 toolArgs 硬截断（切一半 = 非法 JSON）"
else
    pass "toolArgs 不再走字符截断"
fi

if grep -qF 'toolArgs = encodeToolArgs(call.arguments),' "$STORE"; then
    pass "toolArgs 走 encodeToolArgs"
else
    fail "toMsgRecord 没接 encodeToolArgs"
fi

# 未超预算时必须**原样**保存：正常路径的字节不能因为我们加了兜底而变化，
# 否则每一份历史会话都会被重写一遍，diff、备份、指纹全跟着抖。
if grep -qF 'if (raw.length <= MAX_FIELD_CHARS) return raw' "$STORE"; then
    pass "未超预算时原样返回（不改变正常路径的字节）"
else
    fail "encodeToolArgs 缺「未超预算原样返回」"
fi

# 超预算后必须仍是**合法 JSON**：占位对象要能过 parseToolArgs 自己那一关。
if grep -qF 'put("__note", JsonPrimitive(' "$STORE"; then
    pass "超预算改存合法占位（写明省略了多少）"
else
    fail "超预算没有产出合法占位对象"
fi

echo "== 读取侧：任何坏数据都不许抛 =="

# 三道门：空值 / 解析失败 / 解析成功但非对象。
# 1.911 的注释写着「损坏时退回空 object」，但代码只挡了第三道——
# 承诺和实现对不上，而这一种对不上恰好落在启动路径上。
if grep -qF 'runCatching { Json.parseToJsonElement(raw).objOrNull() }' "$STORE"; then
    pass "解析失败退化为空对象（不再抛 JsonDecodingException）"
else
    fail "parseToolArgs 仍是裸调用——异常会一路冒到 ChatViewModel 构造"
fi

if grep -qF 'if (raw.isNullOrBlank()) return JsonObject(emptyMap())' "$STORE"; then
    pass "空值门仍在"
else
    fail "空值门被删"
fi

if grep -qF '.getOrNull() ?: JsonObject(emptyMap())' "$STORE"; then
    pass "非对象门仍在（W-47 的软转换）"
else
    fail "非对象门被删（null / 数组 / 数字会抛 IllegalArgumentException）"
fi

echo ""
echo "=== 合计 $PASS 通过 / $FAIL 失败 ==="
[ "$FAIL" -eq 0 ]
