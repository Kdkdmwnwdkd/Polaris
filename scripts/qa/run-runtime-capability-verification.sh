#!/usr/bin/env bash
# 运行层契约静态 harness（R6 重写版）。
#
# ## 为什么重写
# 本脚本原先断言的是 R3 收缩**之前**的运行层 API：
#   `CapabilityHost`（通用能力注册表）、`AgentCapability.onUse()`、
#   `inFlight` 手工计数、`CapabilityStartException`。
# R3 把通用能力框架整体删除（1 个实现、0 个消费方），这些断言随之失效——
# 脚本变成「永远红的死门禁」：它不在 known-broken.txt 里，
# run-all.sh 会把它判为 FAIL 并**阻塞 CI**，而失败原因与产品代码无关。
#
# 重写后的断言对象是 **R6 收口后的真实契约**：
#   1. 空闲释放只有一个判据源（LeaseRegistry），不再有第二个节拍器；
#   2. `idleTimeoutMs` 语义两侧一致（0=立即满足 / 负数=禁用）；
#   3. 工具预算可按工具覆盖，且自声明值被夹在安全区间。
#
# 这些同样是「看不见的坑」：改错一处不会编译失败，只会让用户的设置失效、
# 或让长任务被误杀。必须靠断言锁住。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
ROOT=$(pwd)

RT="$ROOT/app/src/main/java/com/beichen/polaris/runtime"
LEASE="$RT/lease"
CAP="$RT/AgentCapability.kt"
LLM="$RT/LocalLlmRuntime.kt"
REG="$LEASE/LeaseRegistry.kt"
LEASE_IF="$LEASE/RuntimeLease.kt"
LOCAL_LEASE="$LEASE/LocalLlmLease.kt"
BUDGET="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ToolBudget.kt"
CONTRACT="$ROOT/core/src/main/java/com/beichen/polaris/core/AgentContract.kt"
EXECUTOR="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ToolExecutor.kt"
APP="$ROOT/app/src/main/java/com/beichen/polaris/AgentKitApplication.kt"
BOOT="$ROOT/app/src/main/java/com/beichen/polaris/startup/BootSequence.kt"

pass=0
fail=0
failed=""

ok()  { pass=$((pass + 1)); echo "  PASS  $1"; }
bad() { fail=$((fail + 1)); failed="$failed|$1"; echo "  FAIL  $1"; }

# 文件里必须出现某段字面量
expect() {
  local name="$1" file="$2" needle="$3"
  if [ -f "$file" ] && grep -qF -- "$needle" "$file"; then ok "$name"; else bad "$name（缺：$needle）"; fi
}

# 文件里必须不出现某段字面量（防回退）
reject() {
  local name="$1" file="$2" needle="$3"
  if [ -f "$file" ] && grep -qF -- "$needle" "$file"; then bad "$name（不该有：$needle）"; else ok "$name"; fi
}

# 整个目录里不得再出现某段字面量
reject_in() {
  local name="$1" dir="$2" needle="$3"
  if [ -d "$dir" ] && grep -rqF -- "$needle" "$dir" 2>/dev/null; then
    bad "$name（不该有：$needle）"
  else
    ok "$name"
  fi
}

echo "== 静态：文件与契约 =="
for f in "$CAP" "$LLM" "$REG" "$LEASE_IF" "$LOCAL_LEASE" "$BUDGET"; do
  [ -f "$f" ] && ok "存在 $(basename "$f")" || bad "缺失 $f"
done

expect "接口声明 start"            "$CAP" 'suspend fun start()'
expect "接口声明 stop"             "$CAP" 'suspend fun stop()'
expect "接口声明 state"            "$CAP" 'val state: StateFlow<CapabilityState>'
expect "状态含 FAILED"             "$CAP" 'FAILED'

echo
echo "== 静态：空闲释放只有一个判据源（R6 核心） =="
# 旧的双节拍器：IdleUnloadPolicy 与 LeaseRegistry 各有一套 idleTimeoutMs，
# 且语义相反（policy 0=永不释放 / registry 0=立即回收）。
reject_in "runtime 包内不再有第二个节拍器 IdleUnloadPolicy" "$RT" 'class IdleUnloadPolicy'
reject_in "runtime 包内不再有 IdleReleasable"               "$RT" 'interface IdleReleasable'
reject "本地模型运行时不再建 policy"      "$LLM" 'IdleUnloadPolicy('
reject "本地模型运行时不再建 releasable"  "$LLM" 'IdleReleasable'
# 租约侧是唯一判据源：阈值挂在租约上
expect "租约接口声明 idleTimeoutMs"      "$LEASE_IF" 'val idleTimeoutMs: Long? get() = null'
expect "注册表按租约阈值取值"            "$REG" 'lease.idleTimeoutMs ?: idleTimeoutMs'
expect "注册表有禁用常量"                "$REG" 'DISABLED_IDLE_TIMEOUT_MS'
expect "禁用常量为负数"                  "$REG" 'const val DISABLED_IDLE_TIMEOUT_MS: Long = -1L'
expect "注册表禁用判断用 < 0"            "$REG" 'if (timeout < 0L) continue'
expect "本地模型租约阈值可覆盖"          "$LOCAL_LEASE" 'override var idleTimeoutMs: Long?'

echo
echo "== 静态：用户设置真的作用到回收上（R6 修掉的真实 bug） =="
# R6 之前：enabled/idleTimeoutMinutes 只写旧 policy，registry 照旧按全局 30 分钟回收，
# 用户关掉「空闲释放」形同虚设。
expect "设置下发到租约阈值"              "$LLM" 'lease.idleTimeoutMs = timeout'
expect "关闭时用禁用常量"                "$LLM" 'DISABLED_IDLE_TIMEOUT_MS'
reject "关闭时不再用 0 表示禁用"         "$LLM" 'policy.idleTimeoutMs = if (value) timeoutMsFromPrefs() else 0L'
reject "初始化不再单独设 policy 阈值"    "$LLM" 'policy.idleTimeoutMs = if (enabled) timeoutMsFromPrefs() else 0L'
expect "本地模型默认 30 分钟"            "$LLM" 'const val DEFAULT_IDLE_MINUTES = 30'
reject "没有残留 5 分钟默认值"            "$LLM" 'DEFAULT_IDLE_MINUTES = 5'

echo
echo "== 静态：接线与两道闸门 =="
expect "启动顺序里拉起运行层"    "$BOOT" 'LocalLlmRuntime.init(app)'
expect "启动顺序里拉起租约节拍"  "$BOOT" 'LeaseRegistry.start(AppScope.app)'
expect "本地模型登记租约"          "$LLM" 'LeaseRegistry.register(lease)'
expect "本地模型注册空闲释放器"    "$LLM" 'LeaseRegistry.registerIdleReleaser(lease.id)'
expect "释放前问能否安全释放"      "$LEASE_IF" 'val isSafeToRelease: Boolean'
expect "释放日志说清代价"          "$LLM" '下次对话会自动重载'

echo
echo "== 静态：工具预算可按工具覆盖（R6） =="
expect "工具接口声明 timeoutBudgetMs"   "$CONTRACT" 'val timeoutBudgetMs: Long? get() = null'
expect "预算解析器存在"                 "$BUDGET" 'object ToolBudget'
expect "解析优先级：自声明 > 分档"       "$BUDGET" 'safeMeta(tool, null) { it.timeoutBudgetMs }'
# 元数据读取一律走安全访问器：接口默认方法 + 增量编译会让旧实现缺桥接（AbstractMethodError）
expect "元数据读取容忍二进制不兼容"      "$BUDGET" 'catch (e: LinkageError)'
expect "自声明值被夹紧"                 "$BUDGET" 'declared.coerceIn(lower, upper)'
expect "下界 1 秒"                      "$BUDGET" 'MIN_DECLARED_BUDGET_MS: Long = 1_000L'
expect "上界为回合软截止"                "$BUDGET" 'MAX_DECLARED_BUDGET_MS: Long = 30 * 60_000L'
expect "executor 走预算解析"            "$EXECUTOR" 'ToolBudget.resolve(tool'
# 分档默认必须原样使用——测试注入 80ms 断言超时路径，夹住就永远测不到
expect "分档默认原样使用"               "$BUDGET" 'return if (readOnlyOf(tool)) readOnlyBudgetMs else writeBudgetMs'
reject "分档默认不得被夹紧"             "$BUDGET" 'readOnlyBudgetMs.coerceIn'
# 等用户的工具不吃预算（awaitsUser 优先于自声明）
expect "executor 尊重 awaitsUser"       "$EXECUTOR" 'ToolBudget.awaitsUserOf(tool)'

echo
echo "== 静态：失效断言不得复活 =="
reject_in "runtime 包内不再有 CapabilityHost" "$RT" 'object CapabilityHost'
reject_in "runtime 包内不再有 CapabilityRegistry" "$RT" 'object CapabilityRegistry'
expect "能力契约没有 onUse（已由租约取代）" "$CAP" 'val state: StateFlow<CapabilityState>'

echo
if [ "$fail" -eq 0 ]; then
  # 汇总行必须带 FAIL=0：run-all.sh 靠这个串识别「跑完且全过」
  echo "RESULT: PASS=$pass FAIL=0"
  exit 0
fi
echo "RESULT: PASS=$pass FAIL=$fail$failed"
exit 1
