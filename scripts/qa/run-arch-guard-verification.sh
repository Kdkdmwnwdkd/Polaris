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
#   G 拆分后的所有权：共享状态靠注入，写点唯一
#   H 工具装配单点：清单只在 ToolAssembler
#   I 冷启动恢复单入口：运行态与回合日志在裁决前合流
#   J Android API 级别：minSdk 26 下不得用 Java 9+ 平台类库符号（真机崩、单测看不见）
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

# 超限判定抽成函数：下面 ① 与反向自检必须走**同一段逻辑**。
# 自检若另写一遍 if，验的只是「自检自己写得对不对」，不是守卫。
lines_over_limit() { # $1=文件 $2=上限 → 0 表示超限
  [ "$(wc -l < "$1")" -gt "$2" ]
}

# ① 基线里的每一条 lines 都必须生效，**不看它当前是否还 >1500**。
#
# 原来这里只有一个循环（find + awk '$1>1500'），于是「拆到 1500 以下」等于自动退管：
# RealAgentLoop 从 1532 拆到 1391 之后不再被 ② 扫到，而基线仍写着 1388——
# 实际超 3 行，门禁全绿。拆分成果就这样被静默还了回去，几十天也发现不了。
while read -r p max; do
  [ -z "${p:-}" ] && continue
  if [ ! -f "$p" ]; then
    bad "[B] 基线条目指向不存在的文件：$p"
    oversize=$((oversize + 1))
    continue
  fi
  if lines_over_limit "$p" "$max"; then
    bad "[B] 文件又长了：$p 现 $(wc -l < "$p") 行 > 冻结上限 $max（只减不增）"
    oversize=$((oversize + 1))
  else
    n=$(wc -l < "$p")
    [ "$n" -lt "$max" ] && say "  NOTE  $p 已降到 $n 行（上限 $max），可同步下调基线"
  fi
done < <(awk '$1=="lines"{print $2" "$3}' "$BASELINE")

# ② 新出现的超标文件：>1500 且基线里**没有**条目 → 判红（语义与原来一致）
while read -r n f; do
  [ -z "${f:-}" ] && continue
  if printf '%s\n' "$lines_paths" | grep -Fxq "$f"; then continue; fi
  bad "[B] 新增超标文件：$f（$n 行 > 1500）——请拆分，或先想清楚为什么不能拆"
  oversize=$((oversize + 1))
done < <(find app/src/main/java core/src/main/java -name '*.kt' -exec wc -l {} + | awk '$1>1500 && $2!="total"{print $1" "$2}')

if [ "$oversize" -eq 0 ]; then
  n=$(printf '%s\n' "$lines_paths" | awk 'NF' | wc -l | tr -d ' ')
  good "[B] 基线内 $n 个受管文件均未超各自上限；无新增超标文件"
fi

# ③ 反向自检：复制一条受管文件、人为加 3 行，喂给**同一个判定函数**，必须判红。
# 不做这一步，① 里比较方向写反（-gt 写成 -lt）、wc 取错文件，全都照样绿。
selftest_dir=$(mktemp -d)
managed=$(printf '%s\n' "$lines_paths" | awk 'NF' | head -1)
if [ -n "$managed" ] && [ -f "$managed" ]; then
  cp "$managed" "$selftest_dir/probe.kt"
  printf '\n// selftest\n// selftest\n// selftest\n' >> "$selftest_dir/probe.kt"
  real_lines=$(wc -l < "$managed")
  if lines_over_limit "$selftest_dir/probe.kt" "$real_lines"; then
    good "[B] 守卫自检：受管文件超过上限 3 行会被判红"
  else
    bad "[B] 守卫自检失败：加了 3 行仍判不红，这条守卫等于没写"
  fi
  if lines_over_limit "$managed" "$real_lines"; then
    bad "[B] 守卫自检失败：刚好等于上限却判红（上限对齐时会误伤）"
  else
    good "[B] 守卫自检：刚好等于上限不判红（不误伤）"
  fi
else
  bad "[B] 守卫自检失败：基线里找不到可用的受管文件"
fi
rm -rf "$selftest_dir"

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
say "[G] 拆分后的所有权：共享状态靠注入，写点必须唯一"

# 3.1 第五批把「工具组开关」与「测试连接」搬出了 ChatViewModel。这两块都有一条
# **静默失效**的失败模式，不靠编译期、只靠「谁持有那个对象」：
#   · 工具组集合若在 facade 里自建，用户点击与 agent 的 __use_tools 各写各的，
#     表现是「设置页显示已关闭，实际每轮仍把 ui 组发给模型」——不报错、不崩溃；
#   · 测试连接的探测若在宿主里再写一份，两处端点/归因会漂移，卡片与 401 气泡对不上。
# 所以判据不是「有没有这个类」，而是「宿主传的是不是同一个对象」「写点是不是只剩一处」。
if grep -qE 'mutableSetOf[(]|ToolActivationStore[.]load[(]' app/src/main/java/com/beichen/polaris/ui/ToolGroupsFacade.kt; then
  bad "[G] ToolGroupsFacade 自建/自读了组集合——必须用宿主注入的实例（否则与 loop 的 activeGroups 不是同一个）"
else
  good "[G] 工具组集合由宿主注入，facade 不自己造"
fi

if grep -q 'ToolGroupsFacade(app, activeToolGroups, registry)' app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt; then
  good "[G] 宿主把 activeToolGroups 本身交给 facade（与 loop 的 activeGroups 同源）"
else
  bad "[G] 宿主没把 activeToolGroups 交给 facade——点击开关影响不到下一轮请求"
fi

if grep -q 'ToolActivationStore[.]save(' app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt; then
  bad "[G] 落盘点又回到 ChatViewModel——两处写同一份激活组，迟早顺序打架"
else
  good "[G] 激活组落盘只剩 facade 一处写点"
fi

if grep -q 'testPing[(]' app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt; then
  bad "[G] 宿主里还有 testPing——测试连接探测点不唯一"
else
  good "[G] 测试连接探测点唯一（ConnectionProbe）"
fi

say ""
say "[H] 工具装配单点：清单只在 ToolAssembler，能力边界可对照"

ASM="app/src/main/java/com/beichen/polaris/tools/ToolAssembler.kt"

# H1 装配知识不许回到界面层。原来 ui 里有 52 个内联 register(...)，pet 里另有一份 5 个的
# 迷你版——「能不能让模型用某个能力」实际由这两个界面文件决定。收口后判据很硬：
# ui/ 与 pet/ 目录下一个 register( 都不该有。MCP 的动态注册走 ToolRegistry 自己的 API，
# 不在这些目录里。
leak=$(grep -rl 'register(' app/src/main/java/com/beichen/polaris/ui app/src/main/java/com/beichen/polaris/pet 2>/dev/null || true)
if [ -z "$leak" ]; then
  good "[H] ui / pet 目录内零 register(——装配清单不再由界面层决定"
else
  bad "[H] 界面层又出现 register(：$leak——工具集重新变成「界面文件决定」"
fi

# H2 工具数只增不减。装配搬家的典型事故不是编译错，而是**搬漏一行**：少注册一个
# read_file，模型就会说「我没有读取文件的能力」，而代码、日志、测试全绿。
# 52 是 2026-11-20 的实际条目数；只允许往上。
n_main=$(awk '/fun assemble[(]/,/fun pet[(]/' "$ASM" | grep -cE '^[[:space:]]*register[(]')
if [ "$n_main" -ge 52 ]; then
  good "[H] 主装配 $n_main 个 register 条目（下限 52，只增不减）"
else
  bad "[H] 主装配只有 $n_main 个 register 条目（下限 52）——搬移时漏了工具，模型会静默失去能力"
fi

# H3 桌宠的能力边界。复用的是「图省事让桌宠直接调 assemble()」这条错路：
# 那会给悬浮球 52 个工具（含 shell、写文件、装应用）。它没有界面让人确认，
# 用户说完就走——这条边界一旦破了，表现只是「桌宠偶尔会自己干点什么」。
pet_body=$(awk '/fun pet[(]/,/fun attachSkillTools[(]/' "$ASM")
if printf '%s' "$pet_body" | grep -qE 'WriteFileTool|EditFileTool|ShellRunTool|ShellBackgroundTool|AppInstallTool'; then
  bad "[H] 桌宠装配里出现写文件 / shell / 装应用——悬浮球没有确认界面，这条边界不能靠自觉"
else
  good "[H] 桌宠只拿只读工具 + 一个带确认的 app_launch"
fi

# H4 装配器不得反向依赖界面层：它要能被任何入口（含无 UI 的后台服务）调用。
if grep -qE '^import com[.]beichen[.]polaris[.](ui|pet)[.]' "$ASM"; then
  bad "[H] ToolAssembler 反向 import 了 ui / pet——装配器不再是入口无关的"
else
  good "[H] 装配器不依赖 ui / pet（任何入口都能用）"
fi

# H5 会变的依赖必须是函数。activeConversationId 若被写成快照字符串，todo 工具会永远
# 写进「启动时那个会话」——不报错，用户只看到待办莫名跑到别的对话里。
if grep -q 'val activeConversationId: () -> String?' "$ASM" && grep -q 'val planModeEnabled: () -> Boolean' "$ASM"; then
  good "[H] 运行期会变的依赖（会话 id / 计划模式）是函数，不是快照"
else
  bad "[H] 会话 id / 计划模式变成了快照——todo 会写错会话且无任何报错"
fi

say ""
say "[I] 冷启动恢复单入口：两份证据必须在裁决之前合流"

POLICY=core/src/main/java/com/beichen/polaris/core/agent/AutoResumePolicy.kt

# 3.2 的坑是**两个恢复协程各跑各的**：运行态先 complete，日志扫描后到，
# 于是「有写操作可能已生效」赶不上自动续跑的裁决——应用照样发「继续」，
# 模型把同一件事做第二遍。表现不是崩溃，是用户的文件被改了两次。
# 判据：扫描点唯一（在 TurnJournal）、合成点唯一（recoveryFor）、且先于 complete。
if grep -q 'scanAndRepair(' app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt; then
  bad "[I] ChatViewModel 又自己扫回合日志——恢复逻辑回到界面层，两份证据会再次分叉"
else
  good "[I] 回合日志只在 TurnJournal 扫描（界面层不再持扫描点）"
fi

if grep -q 'turnJournal.recoveryFor(' app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt; then
  good "[I] 运行态与日志在 recoveryFor 一处合流"
else
  bad "[I] 找不到 turnJournal.recoveryFor —— 两份证据又变成两条路径"
fi

# 构造函数是 private 的：只能经 build() 造。这一条钉住 TurnJournal 真的调了 build
# （而不是拿一份空结论充数——那样会把「有写操作已生效」整个吞掉，且不报错）。
TJ=core/src/main/java/com/beichen/polaris/core/agent/journal/TurnJournal.kt
if awk '/suspend fun recoveryFor[(]/,/^    }/' "$TJ" | grep -q 'StartupRecovery.build(orphans'; then
  good "[I] recoveryFor 把扫描结果真的合成了（build(orphans)）"
else
  bad "[I] recoveryFor 没有调用 StartupRecovery.build —— 日志结论被吞，写操作会被重做"
fi

# 顺序：判断「该不该跑」的开关必须先于「写操作结局未知」。
# 反过来，一个已经关掉自动续跑的用户每次冷启动都会被告知「你有写操作结局未知」。
EN_LINE=$(grep -n 'if (!input.enabled) return skip' "$POLICY" | head -1 | cut -d: -f1)
UNK_LINE=$(grep -n 'if (input.unknownWrites > 0)' "$POLICY" | head -1 | cut -d: -f1)
if [ -n "$EN_LINE" ] && [ -n "$UNK_LINE" ] && [ "$EN_LINE" -lt "$UNK_LINE" ]; then
  good "[I] 开关判定先于写操作判定（关掉功能的用户不会被反复教育）"
else
  bad "[I] 写操作判定跑到了开关前面（enabled=${EN_LINE:-无} unknown=${UNK_LINE:-无}）"
fi

# 未知写操作那条的提示**不许**覆盖启动提示：后者带着工具名与「我不会自动重做」。
# 这一行被改成 true 的话，用户只会看到一句笼统的「上次被中断」，细节全丢。
N_FALSE=$(grep -c 'overrideNotice = false' "$POLICY" || true)
if [ "${N_FALSE:-0}" -eq 1 ] && awk '/UNKNOWN_WRITES,/{f=1} f&&/overrideNotice = false/{found=1} END{exit !found}' "$POLICY"; then
  good "[I] 只有「写操作结局未知」不覆盖启动提示（明细由 note 提供）"
else
  bad "[I] overrideNotice=false 的位置不对（count=${N_FALSE:-0}）——细节提示会被笼统句盖掉"
fi

say "[J] Android API 级别：minSdk 26 下不得使用 Java 9+ 平台类库符号"

# 为什么这条必须存在：
#
# `BigInteger.TWO` 是 Java 9 加的静态字段，Android 平台的 `java.math.BigInteger`
# 直到 **API 33** 才有它；minSdk 是 26。项目没开 core library desugaring，所以
# `GETSTATIC BigInteger.TWO` 在 Android 8.0–12L 上是 NoSuchFieldError。
#
# 它躲过了所有既有判据：编译过（JDK 21 有这个字段）、JVM 单测过（同上）、
# arch-guard 过（不涉及分层/行数）、CI 过（CI 不跑 lint）。2026-11-20 才被
# 云端 `:app:lintDebug` 第一次抓出来——而它写在 object 的顶层 val 初始化式里，
# 意味着「进 ADB 配对就崩」，连 try/catch 都接不住。
#
# 不查 `"x".repeat(n)` 与 `.toList()`：前者是 kotlin.text.repeat 扩展，
# 后者是 Kotlin 集合的 toList()，都不是 Java 11/16 的同名成员，lint 也不报。
API_PATTERNS='BigInteger\.TWO|\bList\.of\(|\bSet\.of\(|\bMap\.of\(|\bList\.copyOf\(|\bSet\.copyOf\(|\bMap\.copyOf\('
api_hits=$(grep -rnE "$API_PATTERNS" --include=*.kt app/src/main core/src/main 2>/dev/null \
  | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' || true)

if [ -z "$api_hits" ]; then
  good "[J] 未发现 Java 9+ 平台类库符号（BigInteger.TWO / List.of / Map.of 等）"
else
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    bad "[J] 需要 API>26 的符号：$hit"
  done <<< "$api_hits"
fi

# 反向自检：这条守卫必须真能抓到东西——把已知坏样本喂进同一个正则。
# 不做这一步的话，正则写错（比如转义吞掉了）会让守卫永远「通过」。
if printf 'val P = BigInteger.TWO.pow(255)\nval x = List.of(1, 2)\n' | grep -qE "$API_PATTERNS"; then
  good "[J] 守卫自检：已知坏样本能被正则命中"
else
  bad "[J] 守卫自检失败：正则抓不到 BigInteger.TWO，这条守卫等于没写"
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
