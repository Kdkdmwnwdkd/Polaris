#!/usr/bin/env bash
# 架构守卫（P0）：把「分层不许破、文件不许再长、schema 不许再各写一份」变成硬断言。
#
# 为什么需要它：这三条都是**缓慢失效**的约束——没有守卫时，一次 import 就破一层，
# 一个功能就长 200 行，一个供应商就复制一段序列化。失效当天没人疼，三个月后
# 才发现「core 引了 ui，这个模块拆不出去」。守卫的价值是把「以后会疼」提前成「现在就红」。
#
# 三条规则 + 一条自检：
#   A 分层：core 不得 import ui / tools / MainActivity（白名单见 arch-baseline.txt）
#   B 行数：单个 .kt 上限 1500 行，存量见白名单且只减不增
#   C 单点：工具 schema 序列化只允许 ToolSchemaJson 一处实现
#   D 自检：白名单条目必须仍然命中（文件改名/删掉后残留条目会让守卫变成摆设）
#   E 单点：读文件 / 读响应体必须经 BoundedBody 限长（「读完再截断」已犯五次）
#
# 退出码：0 通过 / 1 有违规 / 3 环境不具备（这里不用 3，纯静态检查总是可跑）。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
BASELINE="scripts/qa/arch-baseline.txt"

fail=0
npass=0
say()  { echo "$*"; }
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }

say "=== 架构守卫（分层 / 行数 / schema 单点）==="

if [ ! -f "$BASELINE" ]; then
  echo "FAIL 缺少基线文件 $BASELINE（守卫无法判定，视为失败）"
  exit 1
fi

# 白名单查询：layer 条目 → 文件路径集合；lines 条目 → 路径与上限
layer_paths=$(awk '$1=="layer"{print $2}' "$BASELINE")
lines_paths=$(awk '$1=="lines"{print $2}' "$BASELINE")

say ""
say "[A] 分层：core 不得反向依赖 ui / tools / MainActivity"

viol=0
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  file=${hit%%:*}
  if printf '%s\n' "$layer_paths" | grep -qx "$file"; then
    continue
  fi
  bad "分层违规（未在基线中）：$hit"
  viol=$((viol + 1))
done < <(grep -rn --include=*.kt \
  -e '^import com\.beichen\.polaris\.ui\.' \
  -e '^import com\.beichen\.polaris\.tools\.' \
  -e '^import com\.beichen\.polaris\.MainActivity' \
  core/src/main/java/com/beichen/polaris 2>/dev/null)

if [ "$viol" -eq 0 ]; then
  n=$(printf '%s\n' "$layer_paths" | grep -c . || true)
  good "无新增分层违规（基线豁免 ${n} 个文件）"
fi

# 基线里的 layer 条目必须仍然真实存在且仍有违规，否则说明基线该更新
say ""
say "[D] 自检：基线条目必须仍然命中"

stale=0
while IFS= read -r p; do
  [ -z "$p" ] && continue
  if [ ! -f "$p" ]; then
    bad "基线指向的文件已不存在：$p"
    stale=$((stale + 1))
    continue
  fi
  if ! grep -qE '^import com\.beichen\.polaris\.(ui\.|tools\.|MainActivity)' "$p"; then
    bad "基线条目已失效（该文件不再违规，请从 $BASELINE 删除）：$p"
    stale=$((stale + 1))
  fi
done <<< "$layer_paths"

if [ "$stale" -eq 0 ]; then
  good "基线无残留条目"
fi

say ""
say "[B] 行数：单文件上限 1500 行，存量只减不增"

oversize=0
while read -r n f; do
  [ -z "${f:-}" ] && continue
  base=$(printf '%s\n' "$lines_paths" | grep -Fx "$f" || true)
  if [ -z "$base" ]; then
    bad "新增超标文件：$f（$n 行 > 1500）——请拆分，或先想清楚为什么不能拆"
    oversize=$((oversize + 1))
    continue
  fi
  limit=$(awk -v p="$f" '$1=="lines" && $2==p {print $3}' "$BASELINE")
  if [ "$n" -gt "$limit" ]; then
    bad "文件又长了：$f 现 $n 行 > 冻结上限 $limit（只减不增）"
    oversize=$((oversize + 1))
  elif [ "$n" -lt "$limit" ]; then
    say "  NOTE  $f 已降到 $n 行（上限 $limit），可同步下调基线"
  fi
done < <(find app/src/main/java core/src/main/java -name '*.kt' -exec wc -l {} + | awk '$1>1500 && $2!="total"{print $1" "$2}')

if [ "$oversize" -eq 0 ]; then
  n=$(find app/src/main/java core/src/main/java -name '*.kt' -exec wc -l {} + | awk '$1>1500 && $2!="total"' | wc -l | tr -d ' ')
  good "超标文件 $n 个，均未超过冻结上限"
fi

say ""
say "[C] 单点：工具 schema 序列化只允许一处实现"

# 反模式：在客户端里内联展开 properties（正是 R11 修掉的那三份拷贝）
inline_hits=$(grep -rn --include=*.kt -B1 -A3 'putJsonObject("parameters") {\|putJsonObject("input_schema") {' \
  core/src/main/java/com/beichen/polaris 2>/dev/null \
  | grep -c 'put("type", "object")' || true)

if [ "${inline_hits:-0}" -gt 0 ]; then
  bad "发现 ${inline_hits} 处内联 schema 展开——必须改走 ToolSchemaJson.objectSchema()"
else
  good "无内联 schema 展开"
fi

if ! grep -q "ToolSchemaJson.objectSchema" core/src/main/java/com/beichen/polaris/core/LLMClient.kt; then
  bad "OpenAI 客户端未走 ToolSchemaJson"
else
  good "三个客户端均走 ToolSchemaJson（OpenAI 已确认）"
fi
for f in ClaudeClient GeminiClient; do
  if ! grep -q "ToolSchemaJson.objectSchema" "core/src/main/java/com/beichen/polaris/core/llm/$f.kt"; then
    bad "$f 未走 ToolSchemaJson"
  fi
done

say ""
say "[E] 单点：读文件 / 读响应体必须有上限"

# 「先整份读进内存、再在返回值上截断」这个错误本项目犯过五次：
#   read_file(F19) → http_request(F21) → 附件(F23) → MCP(F29) → 模型列表(F34)
# 每次都是单点补一处，所以每次都会再犯。规则：除下面两个自带「读之前先判长度」
# 的入口外，一律走 BoundedBody（边读边计数，到上限即停）。
RAW_ALLOW='TextEncoding\.kt|GitHubGitTools\.kt'
raw_hits=$(grep -rn --include=*.kt -E '\.readBytes\(\)|body\?\.string\(\)' \
  app/src/main/java core/src/main/java 2>/dev/null \
  | grep -vE ':[0-9]+:[[:space:]]*(//|\*)' \
  | grep -vE "$RAW_ALLOW" || true)

if [ -n "$raw_hits" ]; then
  n_raw=$(printf '%s\n' "$raw_hits" | wc -l | tr -d ' ')
  bad "发现 ${n_raw} 处无上限读取，必须改走 BoundedBody："
  printf '%s\n' "$raw_hits" | head -5 | sed 's/^/        /'
else
  good "无裸读：文件与响应体均经 BoundedBody 限长"
fi

say ""
say "[F] 单点：持久化存储必须走 AtomicJsonFile"

# 与 [E] 同源的病：同一条保证散在多处实现，靠人记就会漏。
# AtomicJsonFile 的 KDoc 明确写着「全仓唯一实现」，但仍出现过三份手写副本：
#   WriteFileTool（同文件的 edit_file 早已原子写，只有它漏了）
#   BuildStore / MediaTaskStore（各自写 writeText → renameTo，都没有 fd.sync）
# 后果链也一样：写坏的不是「一次更新」，而是「整份状态解析失败后按空处理，
# 下一次写入把历史覆盖成一条」。规则：*Store.kt / *Repository.kt 一律走统一入口。
STORE_HITS=$(grep -rn --include=*Store.kt --include=*Repository.kt -E '\.writeText\(' \
  core/src/main/java app/src/main/java 2>/dev/null \
  | grep -vE ':[0-9]+:[[:space:]]*(//|\*)' || true)

if [ -n "$STORE_HITS" ]; then
  n_store=$(printf '%s\n' "$STORE_HITS" | wc -l | tr -d ' ')
  bad "发现 ${n_store} 处存储类直接 writeText，必须改走 AtomicJsonFile："
  printf '%s\n' "$STORE_HITS" | head -5 | sed 's/^/        /'
else
  good "存储类无裸写：均经 AtomicJsonFile（tmp → fsync → rename）"
fi

say ""
if [ "$fail" -eq 0 ]; then
  echo "架构守卫：$npass 通过 / 0 失败"
  echo "FAIL=0"
  exit 0
else
  echo "架构守卫：$npass 通过 / $fail 失败"
  exit 1
fi
