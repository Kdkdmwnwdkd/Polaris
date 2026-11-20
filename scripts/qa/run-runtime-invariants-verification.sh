#!/usr/bin/env bash
# P1 `AgentRuntime` 四条硬不变量验证（v4 §3.2 / §4 不变量 1–4）。
#
# 四条不变量都能 grep 验证，所以本 harness 是纯静态段，不需要编译器、不 exit 3。
#
# 分层判定（不是「全绿才过」，而是「已达标项硬断言、待收口项显式挂账」）：
#   PASS    已达标的不变量，源码里这条线必须在；破了就是真红。
#   PENDING 已知违例、属后续轮次的活，不阻塞构建，但必须在这里可见、
#           可勾对——让「还剩几条」不再是一句口头的估计。
#
# 三入口 loop 收口（P1 第三轮）已把 C1 / B4 / D1a 收成 PASS 硬断言；
# 执行权收口（P1 第四轮）新增 [E] 段——构造点收拢之后，回合必须真的向 runtime
# 登记（acquireTurn / close）、事件必须按代次过滤，否则队列层只是库里的死代码。
# 工具装配收口（P1 第六轮）把 D1b 也收成硬断言：ui / pet 不再 import ToolRegistry，
# 装配清单搬进 tools/ToolAssembler。四条不变量至此全部 PASS，无 PENDING。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core
APP=app/src/main/java/com/beichen/polaris
RT="$CORE/runtime/AgentRuntime.kt"
BOUND="$CORE/runtime/RuntimeBoundAgentLoop.kt"
FACTORY="$CORE/runtime/AgentLoopFactory.kt"
CTRL="$APP/ui/GenerationRunController.kt"
SUB="$CORE/agent/SubAgentRunner.kt"
VM="$APP/ui/ChatViewModel.kt"
PET="$APP/pet/PetAgentSession.kt"

p=0; f=0; pending=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }
pend() { echo "  PENDING $*"; pending=$((pending + 1)); }

# 生产代码里的一行「字面量出现次数」，跳过 build 与 test。
prod_count() { # $1=pattern
  grep -rE "$1" core/src/main/java app/src/main/java --include="*.kt" 2>/dev/null \
    | grep -v '/build/' | wc -l | tr -d ' '
}

# 生产代码里「跳过注释行」后的匹配行（文件:行号:内容）。
# 注释里的字面量（如 Consent.kt 那句「96 处 RealAgentLoop(」）不算——grep 语义只看代码。
prod_matches() { # $1=pattern
  for f in $(grep -rlE "$1" core/src/main/java app/src/main/java --include="*.kt" 2>/dev/null | grep -v '/build/'); do
    awk -v pat="$1" '
      {
        line=$0
        sub(/^[ \t]+/, "", line)
        if (line ~ /^(\/\/|\*|\/\*)/) next
        sub(/\/\/.*/, "", line)
        if (line ~ pat) printf "%s:%d:%s\n", FILENAME, NR, $0
      }
    ' "$f"
  done
}

echo "[A] 不变量 3：TaskSpec 必含 quota 与 consent（无默认值，类型强制）"

if grep -qE 'val quota: Quota[,$]' "$RT" && ! grep -qE 'val quota: Quota\s*=' "$RT"; then
  pass "A1 TaskSpec.quota 无默认值——任何 TaskSpec(...) 都必须显式给配额"
else
  fail "A1 TaskSpec.quota 给了默认值——「忘写配额」会静默退成「不限量」"
fi

if grep -qE 'val consent: Consent[,$]' "$RT" && ! grep -qE 'val consent: Consent\s*=' "$RT"; then
  pass "A2 TaskSpec.consent 无默认值——任何 TaskSpec(...) 都必须显式表态审批"
else
  fail "A2 TaskSpec.consent 给了默认值——「忘写审批」会静默退成默认拒绝/放行"
fi

echo ""
echo "[B] 不变量 4：进程内仅一个 Semaphore；SubAgentRunner 不自带并发限制器"

sem_files=$(grep -rEl 'Semaphore\(' core/src/main/java app/src/main/java --include="*.kt" 2>/dev/null | grep -v '/build/')
if [ "$(echo "$sem_files" | grep -c .)" = "1" ] && echo "$sem_files" | grep -q 'runtime/AgentRuntime.kt'; then
  pass "B1 Semaphore( 构造只出现在 runtime/AgentRuntime.kt（$sem_files）"
else
  fail "B1 Semaphore( 出现在多处：$(echo "$sem_files" | tr '\n' ' ')——并发闸的真源不止一个"
fi

sub_chunked=$(awk -v pat='chunked' '
  {
    line=$0; sub(/^[ \t]+/, "", line)
    if (line ~ /^(\/\/|\*|\/\*)/) next
    sub(/\/\/.*/, "", line)
    if (line ~ pat) print NR
  }
' "$SUB")
if [ -z "$sub_chunked" ]; then
  pass "B2 SubAgentRunner 不再 chunked 分批——并发上限不在这里自决"
else
  fail "B2 SubAgentRunner 仍在 chunked 自带并发限制器（行 $sub_chunked）"
fi

sub_permit=$(awk -v pat='withPermit[(]"cloud"[)]' '
  {
    line=$0; sub(/^[ \t]+/, "", line)
    if (line ~ /^(\/\/|\*|\/\*)/) next
    sub(/\/\/.*/, "", line)
    if (line ~ pat) print NR
  }
' "$SUB")
if [ -n "$sub_permit" ]; then
  pass "B3 SubAgentRunner 每一路借 runtime.withPermit(\"cloud\")——上限由许可层定"
else
  fail "B3 SubAgentRunner 没借许可——并发上限没有单一真源"
fi

if grep -qE 'runtime: AgentRuntime = AgentRuntime\.of\(\)' "$SUB"; then
  pend "B4 SubAgentRunner 的 runtime 仍是默认独立实例（进程级单例待三入口接线注入）"
else
  pass "B4 SubAgentRunner 已注入进程级 runtime（不变量 4 前半收口）"
fi

echo ""
echo "[C] 不变量 1：RealAgentLoop( 生产代码仅 1 处，且位于 runtime/"

loop_sites=$(prod_matches 'RealAgentLoop[(]' | grep -v 'class RealAgentLoop')
loop_n=$(echo "$loop_sites" | grep -c . || true)
if [ "$loop_n" = "1" ] && echo "$loop_sites" | grep -q 'runtime/'; then
  pass "C1 RealAgentLoop( 仅 1 处且在 runtime/：$loop_sites"
else
  fail "C1 RealAgentLoop( 现 $loop_n 处，唯一构造点必须只在 runtime/AgentLoopFactory.kt"
  echo "$loop_sites" | sed 's/^/        /'
fi

echo ""
echo "[D] 不变量 2：ui / pet / entry 不 import RealAgentLoop / ToolRegistry"

# 两半都已收成硬断言：
#   D1a（RealAgentLoop）随 loop 构造收口（P1 第三轮）关闭，ui/pet 改走 AgentLoopFactory；
#   D1b（ToolRegistry）随工具装配收口（P1 第六轮）关闭，52 个 register 搬进
#   tools/ToolAssembler，ui/pet 只剩「持有」不再「决定」。
bad_loop_imports=""
bad_registry_imports=""
for src in "$VM" "$PET"; do
  if grep -qE 'import .*agent\.RealAgentLoop' "$src" 2>/dev/null; then
    bad_loop_imports="$bad_loop_imports $(basename "$src")"
  fi
  if grep -qE 'import .*ToolRegistry' "$src" 2>/dev/null; then
    bad_registry_imports="$bad_registry_imports $(basename "$src")"
  fi
done
if [ -z "$bad_loop_imports" ]; then
  pass "D1a ui/pet 不再 import RealAgentLoop（已走 AgentLoopFactory）"
else
  fail "D1a 仍 import RealAgentLoop：$bad_loop_imports"
fi
if [ -z "$bad_registry_imports" ]; then
  pass "D1b ui/pet 不再 import ToolRegistry（装配清单在 tools/ToolAssembler）"
else
  fail "D1b 仍 import ToolRegistry：$bad_registry_imports——工具集重新由界面层决定"
fi

echo ""
echo "[E] 执行权：回合必须真的向 runtime 登记（send 内 acquireTurn / close / 代次过滤）"

if grep -qE 'session: String[,$]' "$FACTORY" && ! grep -qE 'session: String\s*=' "$FACTORY"; then
  pass "E1 AgentLoopFactory.create 的 session 无默认值——入口必须显式表态回合身份"
else
  fail "E1 session 有默认值——两个入口会悄悄共用队列键，彼此串行且无人察觉"
fi

# 三个入口的调用点附近必须出现 session =。用 -A 10：create( 之后前十个参数内
# 还没有 session，说明调用方在用位置参数硬猜，或者压根没接。
e2_bad=""
for src in "$VM" "$PET" "$SUB"; do
  if ! grep -A 10 'AgentLoopFactory.create(' "$src" | grep -q 'session = '; then
    e2_bad="$e2_bad $(basename "$src")"
  fi
done
if [ -z "$e2_bad" ]; then
  pass "E2 三个入口都显式传 session（主界面 / 桌宠 / 子代理各有身份）"
else
  fail "E2 未传 session：$e2_bad——该入口的回合不会进队列层"
fi

if grep -q 'val held = runtime.acquireTurn(session, source)' "$BOUND"; then
  pass "E3 RuntimeBoundAgentLoop.send 内 acquireTurn——队列层对生产路径生效"
else
  fail "E3 send 没有 acquireTurn——队列层形同虚设（构造权收了，执行权没收）"
fi

e4_close=$(awk '
  /finally/ { inf = 1; sub(/^[ \t]+/, "", $0); if ($0 ~ /^\/\//) inf = 0 }
  inf && /held\.close\(\)/ { print NR; exit }
' "$BOUND")
if [ -n "$e4_close" ]; then
  pass "E4 send 的 finally 里归还租约（行 $e4_close）——异常/取消不会永久锁死入口"
else
  fail "E4 finally 里没有 held.close()——一次失败就让该 (session, source) 永久锁死"
fi

# 只查「isCloseout 这个名字存在」是不够的：把它改成 `stale && false` 照样能过，
# 而行为已经退化成「一律按代次丢」（正是 R10 那条变异）。所以这里钉**判断表达式本身**，
# 再补一条分类表的事实：收尾两支必须归 true。
if grep -q 'if (stale && !isCloseout(event)) return' "$BOUND" \
   && grep -q 'ToolFinished, AgentEvent.RetryFinished -> true' "$BOUND"; then
  pass "E5 收尾事件（ToolFinished / RetryFinished）不按代次丢弃——T05 取消后工具卡能落终态"
else
  fail "E5 代次判断没按「开始类丢、收尾类留」——取消后 UI 或永久停在「执行中」，或旧卡污染新回合"
fi

# 取消入口有两个（cancel / cancelAndWait），将来还会有第三个——所以「作废旧代次」
# 不写在调用方，而是收进 GenerationRunController 的 onCancelGeneration：在 job.cancel()
# 之前触发。断言两件事：两个入口都调了它；宿主真的把作废接到了 runtime。
cancels=$(grep -c 'onCancelGeneration()' "$CTRL" | tr -d ' ')
cancel_hooked=$(grep -c 'onCancelGeneration = { runtime\.cancel(' "$VM" | tr -d ' ')
if [ "$cancels" -ge 2 ] && [ "$cancel_hooked" -ge 1 ]; then
  pass "E6 取消入口收在 controller：cancel/cancelAndWait 都在 job.cancel 前作废旧代次（$cancels 处）"
else
  fail "E6 取消没接到代次号（入口 $cancels 处 / 宿主接线 $cancel_hooked 处）——闸门永远放行"
fi

echo ""
echo "[F] 默认值单一真源：工厂与 loop 不许各写一份字面量"

LOOP="$CORE/agent/RealAgentLoop.kt"
DEFAULTS="$CORE/agent/LoopDefaults.kt"

# 债的来源（§18.7）：不变量 1 只保证「只有一处 new」，不保证两处默认值一样。
# 历史上 maxSteps=16 截断长任务、providerRetry=2 让 429 第三档退避永不触发，
# 两次都编译通过、单测通过，只在真机上表现为「任务莫名停住」。
missing=""
for k in MAX_STEPS MAX_PARALLEL_TOOLS PROVIDER_RETRY MAX_HISTORY_MESSAGES LLM_CALL_TIMEOUT_MS TURN_BUDGET_MS CONSENT; do
  grep -qE "(const )?val $k" "$DEFAULTS" 2>/dev/null || missing="$missing $k"
done
if [ -f "$DEFAULTS" ] && [ -z "$missing" ]; then
  pass "F1 LoopDefaults 在位且七个常量齐全（纯常量默认值的唯一出处）"
else
  fail "F1 LoopDefaults 缺项：${missing:-文件不存在} —— 默认值又变成两处人肉复刻"
fi

# 判据是「两处右侧表达式逐字相同、且都以 LoopDefaults. 开头」。
# 只查「有没有引用」不够：两边都写成 `= 61` 也能过，而那正是漂移。
DRIFT=$(python3 - "$LOOP" "$FACTORY" <<'PY'
import re, sys
loop = open(sys.argv[1], encoding="utf-8").read()
fac = open(sys.argv[2], encoding="utf-8").read()
names = ["maxSteps: Int", "maxParallelTools: Int", "providerRetry: Int",
         "maxHistoryMessages: Int", "consent: Consent", "llmCallTimeoutMs: Long",
         "turnBudgetMs: Long"]
def rhs(src, name):
    m = re.search(re.escape(name) + r"\s*=\s*([^,\n]+)", src)
    return m.group(1).strip() if m else "<未找到>"
bad = []
for n in names:
    a, b = rhs(loop, n), rhs(fac, n)
    if a != b or not a.startswith("LoopDefaults."):
        bad.append("{}（loop={} / 工厂={}）".format(n, a, b))
print("; ".join(bad))
PY
)
if [ -z "$DRIFT" ]; then
  pass "F2 七个默认值两处逐字相同且都指向 LoopDefaults"
else
  fail "F2 默认值漂移：$DRIFT —— 改一处不改另一处，真机上会莫名截断或永不重试"
fi

# 有状态的实例不许进 LoopDefaults：共享一份会让两个 loop 的「已上屏字符数」互相污染。
# 只看**代码行**：KDoc 里必须能写出「为什么它不该进来」，那几行注释不算违规。
STATE_HIT=$(grep -n 'AttemptOutputTracker' "$DEFAULTS" 2>/dev/null \
  | grep -vE '^[0-9]+:[[:space:]]*(\*|//|/\*)' || true)
if [ -n "$STATE_HIT" ]; then
  fail "F3 LoopDefaults 里出现了有状态实例（AttemptOutputTracker）——必须每次 new"
else
  pass "F3 有状态默认值没被抽走（AttemptOutputTracker 仍每处 new）"
fi

# 默认审批值也只能有一份：别处再写一次 Consent.unset("RealAgentLoop")，
# 改语义时就会漏掉一处，表现为「某个入口突然开始拒一切工具」。
N_CONSENT=$(grep -rn 'Consent\.unset("RealAgentLoop")' core/src/main/java --include="*.kt" 2>/dev/null | grep -vc 'LoopDefaults.kt' || true)
if [ "${N_CONSENT:-0}" -eq 0 ]; then
  pass "F4 默认审批值只由 LoopDefaults 提供"
else
  fail "F4 还有 ${N_CONSENT} 处硬写默认审批值 —— 改语义会漏"
fi

echo ""
echo "$p 通过 / $f 失败 / $pending 待收口"
[ "$f" -eq 0 ] || exit 1
