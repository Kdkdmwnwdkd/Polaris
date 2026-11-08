#!/usr/bin/env bash
# W-79 harness：本地小模型「能干点活」的三条硬契约（纯静态，只读源码）。
#
# 背景：3B 在真机上 Agent 模式的 prompt 是 1086 token，光 prefill 就要 68 秒，
# 用户看到的是「发出去没反应」。参考同类产品的做法（IRIDIS，Apache-2.0，只学原理）
# 后确认问题不在模型，在三件事上：提示词太长、解析太严、审批太吵。
#
# 这套断言只锁「契约还在」，不锁具体写法：
#   1. Agent 模式的系统提示必须整条换成英文精简版（不是追加），且每个工具一行；
#   2. 工具调用必须宽容解析（别名 / 编辑距离 / 围栏 / 标签 / 尾逗号 / 截断）；
#   3. 解析失败必须静默重试一次，且重试有硬超时（不能回到「等几分钟」）；
#   4. shell 审批必须按命令内容判定（只读放行、写操作询问）。
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)

PROMPT="$ROOT/core/src/main/java/com/beichen/polaris/core/llm/local/LocalAgentPrompt.kt"
PARSER="$ROOT/core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt"
CLIENT="$ROOT/core/src/main/java/com/beichen/polaris/core/llm/local/LocalLlamaClient.kt"
RULES="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ShellCommandRules.kt"
SHELL="$ROOT/app/src/main/java/com/beichen/polaris/tools/ShellTool.kt"
CONTRACT="$ROOT/core/src/main/java/com/beichen/polaris/core/AgentContract.kt"
EXECUTOR="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ToolExecutor.kt"

pass=0
fail=0
failed=""

check() {
  local name="$1" cond="$2"
  if [ "$cond" = "1" ]; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    failed="$failed\n  - $name"
    echo "FAIL: $name"
  fi
}

expect() { # 文件里必须出现某段
  local name="$1" file="$2" pattern="$3"
  if grep -qE -- "$pattern" "$file" 2>/dev/null; then check "$name" 1; else check "$name" 0; fi
}

fixed() { # 纯文本匹配（正则里有反斜杠时用它，免得转义打架）
  local name="$1" file="$2" needle="$3"
  if grep -qF -- "$needle" "$file" 2>/dev/null; then check "$name" 1; else check "$name" 0; fi
}

refute() { # 文件里必须不出现某段
  local name="$1" file="$2" pattern="$3"
  if grep -qE -- "$pattern" "$file" 2>/dev/null; then check "$name" 0; else check "$name" 1; fi
}

# —— 1. 精简系统提示 ——————————————————————————————————————————————
check "精简提示文件存在" "$([ -f "$PROMPT" ] && echo 1 || echo 0)"
expect "身份行（英文）" "$PROMPT" 'You are 北辰, an AI agent running on the user.s Android phone'
expect "规则：中文回答但提示词英文" "$PROMPT" 'Answer in Chinese'
expect "规则：一次只调一个工具" "$PROMPT" 'call ONE tool, then stop and wait'
expect "给出一个具体调用样例" "$PROMPT" '\{"tool": "read_file", "arguments"'
expect "工具按固定顺序渲染（KV 前缀稳定）" "$PROMPT" 'CapabilityManifest\.sort'
refute "本文件不再自带顺序表（顺序唯一来源 = 清单）" "$PROMPT" 'private val ORDER'
expect "工具一行：name(参数) 形式" "$PROMPT" '\$\{tool.name\}\('
expect "枚举参数摊开取值" "$PROMPT" 'joinToString\("\|"\)'
# 提示词里不该再出现中文长句（除了名字「北辰」）。
#
# 只看**真正拼进提示词的字符串字面量**，不看整个源文件——本文件的注释是中文，
# 一个像「请/不要/如果/可以」这样的常用词（比如注释里写「请求体」）就会让整条断言
# 无辜变红，于是下一次有人只会去改注释迁就正则，而不是去查提示词到底长不长。
prompt_text=$(python3 - "$PROMPT" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
# 取 build() 函数体（到下一个顶层 `private fun`/`fun` 之前）
body = src[src.index('fun build('):]
cut = re.search(r'\n    (private )?fun ', body[10:])
if cut:
    body = body[:cut.start() + 10]
print("".join(re.findall(r'"(?:[^"\\]|\\.)*"', body)))
PY
)
if printf '%s' "$prompt_text" | grep -qE '【|】|请|不要|如果|可以'; then
  check "提示词不含中文长句（token 经济）" 0
else
  check "提示词不含中文长句（token 经济）" 1
fi

# —— 2. 整条替换而不是追加 ————————————————————————————————————————
expect "Agent 模式走精简消息" "$CLIENT" 'compactAgentMessages'
expect "过滤掉云端长系统提示" "$CLIENT" 'filterNot \{ it is SystemMessage \}'
expect "精简提示替换整个 SystemMessage" "$CLIENT" 'listOf\(SystemMessage\(compact\)\) \+ rest' 
expect "没有 system 通道的模型并进首条用户消息" "$CLIENT" 'supportsSystemPrompt\(\)'
refute "旧的中文工具协议已删除（只留一套口径）" "$CLIENT" 'toolProtocolPrompt'
expect "buildPrompt 不再拼工具说明" "$CLIENT" 'private fun buildPrompt\(messages: List<ChatMessage>\): String'
expect "buildPrompt 不再拼工具说明" "$CLIENT" 'private fun buildPrompt\(messages: List<ChatMessage>\): String'
expect "技能只列名字（不传整段 XML）" "$CLIENT" 'Regex\("<name>\(\.\*\?\)</name>"\)'
expect "提示长度写进日志（可核对）" "$CLIENT" 'Agent 精简提示：\$\{compact.length\} 字符'

# —— 3. 宽容解析 ————————————————————————————————————————————————
check "解析器文件存在" "$([ -f "$PARSER" ] && echo 1 || echo 0)"
expect "认 <tool_call> 原生标签" "$PARSER" 'tool_call'
expect '认代码围栏' "$PARSER" 'FENCE'
expect "括号配平取 JSON（不用 lastIndexOf）" "$PARSER" 'depth\+\+'
fixed "修尾逗号" "$PARSER" ',\\s*([}\\]])'
expect "修字符串内裸换行" "$PARSER" 'escapeRawWhitespace'
expect "修被截断的 JSON" "$PARSER" 'closeUnbalanced'
expect "工具别名表" "$PARSER" '"read_file" to listOf\("read", "cat"'
expect "编辑距离纠错" "$PARSER" 'editDistanceLe2'
expect "只返回可用列表内的名字" "$PARSER" 'canonicalize\(cleaned, available\)'

# —— 4. 静默重试（有硬超时）——————————————————————————————————————
expect "解析失败后重试一次" "$CLIENT" 'TOOL_RETRY_HINT'
expect "重试有硬超时（不会回到干等）" "$CLIENT" 'TOOL_RETRY_BUDGET_MS'
expect "重试超时会 engine.stop()" "$CLIENT" 'engine.stop\(\)'
refute "重试不无限循环" "$CLIENT" 'while \(retry\)'

# —— 5. shell 审批按命令内容 ——————————————————————————————————————
check "命令规则文件存在" "$([ -f "$RULES" ] && echo 1 || echo 0)"
expect "只读白名单存在" "$RULES" 'READ_ONLY: Set<String>'
expect "重定向一律询问" "$RULES" "command.contains\('>'\)"
expect "命令替换一律询问" "$RULES" 'SUBSTITUTION'
expect "find -delete/-exec 不算只读" "$RULES" 'FIND_WRITES'
fixed "sort -o 不算只读" "$RULES" 'startsWith("-")'
expect "shell_run 覆写 needsApprovalFor" "$SHELL" 'override fun needsApprovalFor'
expect "执行器按参数判定审批" "$EXECUTOR" 'tool.needsApprovalFor\(call.arguments\)'
expect "接口默认行为不变" "$CONTRACT" 'fun needsApprovalFor\(args: JsonObject\): Boolean = needsApproval'

# —— 6. 两张表里的工具名必须真实存在 ————————————————————————————
# 这一节盯的是「死条目」：`LocalAgentPrompt.HINTS`（曾经还有一张 `ORDER`）里写着
# `list_dir` / `search_files`，解析器的别名表也把 `ls` / `grep` 往它们身上归——
# 可那两个工具从来没实现过。
#
# 它们不报错（`build()` 只渲染传入的工具，`nameOf()` 只在 available 里找），
# 也不占 token。正因如此才需要机器盯着：人看到这两行只会以为「我们有这工具」，
# 然后去别处追问「为什么模型列不了目录」。设计阶段的拟名必须清掉。
#
# P3-5 之后 `ORDER` 已删（顺序归清单），所以这一类死条目的来源少了一半——
# 剩下的一半（HINTS）仍然靠这段交叉比对盯着。
# R-13：工具名的真源从 CapabilityManifest（已变派生视图）搬到 ToolCatalog。
# 判据不变——仍是「提示词里提到的工具必须真实存在」——只是不再去视图里找名单。
MANIFEST="$ROOT/core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt"
missing=$(python3 - "$PROMPT" "$PARSER" "$MANIFEST" <<'PY'
import re, sys

prompt, parser, manifest = (open(p, encoding='utf-8').read() for p in sys.argv[1:4])

# 目录：`CatalogTool("name", "group", ...)` 的第一个参数即工具名。
# 不按「文件里出现的所有小写字面量」算——那样组名、审批键都会混进来，
# 判据就永远绿了（它们当然「存在」）。
declared = set(re.findall(r'CatalogTool\(\s*"([a-z0-9_]+)"', manifest))

# 提示词：HINTS 的键（值里的英文句子含空格，不会被名字正则吃进来）
used = set()
for pat in (r'private val HINTS.*?\n    \)',):
    m = re.search(pat, prompt, re.S)
    if m:
        used |= set(re.findall(r'"([a-z0-9_]+)"', m.group(0)))

# 别名表：每个左值都是一个工具名
m = re.search(r'private val ALIASES.*?\n    \)', parser, re.S)
if m:
    used |= set(re.findall(r'^\s*"([a-z0-9_]+)" to listOf', m.group(0), re.M))

print(" ".join(sorted(used - declared)))
PY
)
check "提示词/别名表的工具名都真实存在（虚构：${missing:-无}）" "$([ -z "$missing" ] && echo 1 || echo 0)"

echo
echo "本地 Agent 契约：$pass 通过 / $fail 失败"
if [ "$fail" -ne 0 ]; then
  echo -e "失败项：$failed"
  exit 1
fi
echo "PASS 本地 Agent 契约（提示瘦身 / 宽容解析 / 静默重试 / 命令审批）"
