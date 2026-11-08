#!/usr/bin/env bash
# R12（输出上限按目录声明）验证。
#
# 为什么需要：`AgentContract.kt:132` 的注释早就写明「DeepSeek 官方 chat 默认只有 4K，
# 长回答会在 4K 被服务端砍断，旧实现里 App 完全不知道」——但只做了「让用户看见
# 截断」（finish_reason + 输入栏横幅），没做「让它不发生」。OpenAI 兼容路径一个
# max_tokens 都不传，35 家里 28 家用的是各家服务端默认值。
#
# 为什么不能一律硬塞 8192：o1 / gpt-5 系列只认 `max_completion_tokens`，传
# `max_tokens` 会直接 400。所以规则是「目录声明才发，不声明不发」——把「要不要
# 发」变成一个可查的目录事实，而不是散在客户端的 if。
#
# 与前两个 harness 的分工（三个都要跑，缺一不可）：
#   R10 测纯函数（公式对不对）        —— 全绿也可能没接线
#   R11 测对象接线（传进去的字符串）  —— 需要编译产物
#   R12 测源码结构（发不发、在哪发、先后顺序）—— 纯静态，无依赖
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
CATALOG="$SRC_CORE/core/llm/ProviderCatalog.kt"
CLIENT="$SRC_CORE/core/LLMClient.kt"

fail=0
npass=0
say()  { echo "$*"; }
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }

say "=== R12 输出上限按目录声明 ==="

for f in "$CATALOG" "$CLIENT"; do
  if [ ! -f "$f" ]; then
    echo "FAIL 缺少源文件 $f（无法判定，视为失败）"
    exit 1
  fi
done

# ── A 目录层：字段存在、deepseek 填了、兜底条目不填 ──────────────────
say ""
say "[A] 目录声明"

if grep -q 'val maxOutputTokens: Int = 0' "$CATALOG"; then
  good "A1 LlmProvider 有 maxOutputTokens 字段（0 = 不发送）"
else
  bad "A1 LlmProvider 缺 maxOutputTokens 字段"
fi

block_of() {  # 取某个 id 条目的块（从 id 行到该条目结束的 '),'）
  awk -v pat="$1" '
    $0 ~ pat { inside=1 }
    inside   { print }
    inside && /^ *\),/ { exit }
  ' "$CATALOG"
}

ds=$(block_of 'id = "deepseek"')
if [ -z "$ds" ]; then
  bad "A2 找不到 deepseek 条目（目录结构变了？）"
elif echo "$ds" | grep -qE 'maxOutputTokens = [1-9][0-9]*'; then
  good "A2 deepseek 声明了输出上限（$(echo "$ds" | grep -oE 'maxOutputTokens = [0-9_]+')）"
else
  bad "A2 deepseek 未声明输出上限 —— 4K 截断仍会发生"
fi

# 兜底条目（OpenAI 兼容通用项）**必须留 0**：o1/gpt-5 类走这条，
# 一旦有人图省事给它填个数，28 家会共用一个错的上限并集体 400。
oa=$(block_of 'id = STYLE_OPENAI')
if [ -z "$oa" ]; then
  bad "A3 找不到 OpenAI 兼容兜底条目"
elif echo "$oa" | grep -qE 'maxOutputTokens = [1-9]'; then
  bad "A3 兜底条目声明了非零上限 —— o1/gpt-5 会因 max_tokens 被 400"
else
  good "A3 兜底条目保持不声明（保护只认 max_completion_tokens 的模型）"
fi

# ── B 配置层：LLMConfig 承载这个值 ──────────────────────────────
say ""
say "[B] 配置传递"

if grep -q 'val maxOutputTokens: Int = 0' "$CLIENT"; then
  good "B1 LLMConfig 有 maxOutputTokens 字段"
else
  bad "B1 LLMConfig 缺 maxOutputTokens —— 客户端拿不到目录声明"
fi

create_body=$(awk '/fun create\(/,/^    \}/' "$CLIENT")
if echo "$create_body" | grep -q 'ProviderCatalog.byId(' &&
   echo "$create_body" | grep -q 'maxOutputTokens'; then
  good "B2 create() 从目录取值盖章进配置"
else
  bad "B2 create() 未把目录值写进配置（同 R-01 的「过桥丢字段」）"
fi

# ── C 请求层：两条通道都发，且都在 customBody 之前 ────────────────
say ""
say "[C] 请求体接线"

send_lines=$(grep -n 'put("max_tokens", config.maxOutputTokens)' "$CLIENT" | cut -d: -f1 || true)
send_count=$(echo "$send_lines" | grep -c . || true)
if [ "$send_count" -eq 2 ]; then
  good "C1 两条通道各发一次（非流式 callOnce + 流式 readSseDataObjects）"
else
  bad "C1 期望 2 处发送，实际 $send_count 处（行号：$(echo "$send_lines" | tr '\n' ' ')）"
fi

# 顺序：每一处 customBody 之前 20 行内必须有一处发送。
# 理由不是洁癖——同文件 W-38 的事故就是「temperature 写在 customBody 之后，
# 把用户填的值静默覆盖」。max_tokens 一样的坑：写在后面等于用户改不了。
cb_lines=$(grep -n 'config.customBody.forEach' "$CLIENT" | cut -d: -f1 || true)
if [ -z "$cb_lines" ]; then
  bad "C2 找不到 customBody 应用点（结构变了，断言需重写）"
else
  seq_ok=1
  for cb in $cb_lines; do
    hit=""
    for mt in $send_lines; do
      if [ "$mt" -lt "$cb" ] && [ $((cb - mt)) -le 20 ]; then hit=$mt; fi
    done
    if [ -z "$hit" ]; then
      seq_ok=0
      bad "C2 customBody 行 $cb 之前 20 行内没有 max_tokens 发送（顺序反了，用户改不动）"
    fi
  done
  [ "$seq_ok" -eq 1 ] && good "C2 两处都在 customBody 之前（用户自定义体可覆盖）"
fi

# ── D 反向约束：不许硬编码 ────────────────────────────────────
say ""
say "[D] 反向约束"

hard=$(grep -nE 'put\("max_tokens", *[0-9]' "$CLIENT" | cut -d: -f1 || true)
hard_count=$(echo "$hard" | grep -c . || true)
# 允许且仅允许 pingOnce 的探活值 1（它本来就不该走目录）
if [ "$hard_count" -eq 1 ] && grep -q 'put("max_tokens", 1)' "$CLIENT"; then
  good "D1 除探活（max_tokens=1）外无硬编码上限"
else
  bad "D1 存在硬编码 max_tokens（$hard_count 处：$(echo "$hard" | tr '\n' ' ')）"
fi

if grep -q 'put("max_completion_tokens"' "$CLIENT"; then
  echo "  NOTE  已出现 max_completion_tokens 分支（新协议支持，请同步更新本断言）"
fi

# ── 汇总 ─────────────────────────────────────────────────────
say ""
say "=== $npass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]
