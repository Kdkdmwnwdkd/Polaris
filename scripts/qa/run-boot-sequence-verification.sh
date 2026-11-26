#!/usr/bin/env bash
# R-15（启动顺序显式化）验证。
#
# 守的是「顺序错了但不报错」这一类失效：
#   `AgentLogger.init` 一旦被挪到 `LocalLlmRuntime.init` 之后，编译通过、启动正常、
#   全部 harness 绿——只有真机排障时才会发现那段日志是黑的，而那时你已经没证据了。
#
# 所以这里同时断言三件事：
#   ① 初始化只有一条路（Application 不能绕过 BootSequence 自己调）
#   ② 顺序是数据（枚举声明序），且关键约束用行号兑现在断言里
#   ③ 失败语义没丢（每步独立 runCatching，一步失败不阻断后续）
set -uo pipefail

cd "$(dirname "$0")/../.."
SRC=app/src/main/java/com/beichen/polaris
APP="$SRC/AgentKitApplication.kt"
BOOT="$SRC/startup/BootSequence.kt"

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }

echo "=== R-15 启动顺序显式化 ==="
echo ""

[ -f "$BOOT" ] || { echo "FAIL 缺少 $BOOT"; exit 1; }
[ -f "$APP" ]  || { echo "FAIL 缺少 $APP"; exit 1; }

echo "[A] 初始化只有一条路"

if grep -q "BootSequence.run(this)" "$APP"; then
  good "A1 Application 走 BootSequence.run(this)"
else
  bad "A1 Application 没有调用 BootSequence.run(this)"
fi

# 最容易复发的方式：在 onCreate 里"顺手"再加一行初始化，绕过顺序表。
direct=$(grep -nE "CrashLogger\.install|AgentLogger\.init|TraceSink\.init|LocalLlmRuntime\.init|LeaseRegistry\.start|ShizukuShell\.init|AdbSession\.init|NativeCrashMemory\.handleStartup|AndroidDeviceProbe\.profile" "$APP" || true)
if [ -z "$direct" ]; then
  good "A2 Application 内无任何直接初始化（9 类入口都不在）"
else
  bad "A2 Application 又出现了绕开顺序表的初始化："
  echo "$direct" | sed 's/^/         /'
fi

# 留着 import 但不用，是下一次复发的入口；一并清掉。
stale_import=$(grep -nE "^import com\.beichen\.polaris\.(core\.(CrashLogger|AgentLogger|telemetry\.TraceSink|llm\.local)|runtime|automation)" "$APP" || true)
if [ -z "$stale_import" ]; then
  good "A3 Application 不再 import 业务初始化类（只留 BootSequence）"
else
  bad "A3 Application 残留初始化相关 import："
  echo "$stale_import" | sed 's/^/         /'
fi

app_lines=$(wc -l < "$APP" | tr -d ' ')
if [ "$app_lines" -le 70 ]; then
  good "A4 Application 保持薄（$app_lines 行 ≤ 70）"
else
  bad "A4 Application 涨到 $app_lines 行（上限 70）——初始化又在往里塞"
fi

echo ""
echo "[B] 顺序是数据，且关键约束成立"

steps=$(awk '/enum class Step/,/^    }/' "$BOOT" | grep -oE '^        [A-Z_]{3,}' | tr -d ' ')
expected="CRASH_LOGGER LOGGER NATIVE_CRASH_MEMORY CRASH_REPORT SHIZUKU ADB TRACE RUNTIME LEASE DEVICE_PROBE"
actual=$(echo "$steps" | tr '\n' ' ' | sed 's/ $//')
if [ "$actual" = "$expected" ]; then
  good "B1 步骤序列与冻结顺序一致（$(echo "$steps" | wc -l | tr -d ' ') 步）"
else
  bad "B1 步骤序列被改动："
  echo "         期望：$expected"
  echo "         实际：$actual"
fi

line_of() { grep -nE "^        $1\(" "$BOOT" | head -1 | cut -d: -f1; }

first=$(echo "$steps" | head -1)
if [ "$first" = "CRASH_LOGGER" ]; then
  good "B2 崩溃捕获仍是第一步（它之后才有证据）"
else
  bad "B2 第一步变成了 $first —— 启动期崩溃会丢证据"
fi

last=$(echo "$steps" | tail -1)
if [ "$last" = "DEVICE_PROBE" ]; then
  good "B3 设备画像仍在最后（异步，不拖冷启动）"
else
  bad "B3 最后一步变成了 $last"
fi

order_ok=1
pair_check() {
  local a b why=$3
  local la lb
  la=$(line_of "$1"); lb=$(line_of "$2")
  if [ -n "$la" ] && [ -n "$lb" ] && [ "$la" -lt "$lb" ]; then
    good "B4 $1 在 $2 之前（$why）"
  else
    bad "B4 $1（行 ${la:-?}）必须早于 $2（行 ${lb:-?}）：$why"
    order_ok=0
  fi
}
pair_check LOGGER RUNTIME "运行层启动即写日志，日志目录必须就绪"
pair_check LOGGER TRACE "流水与日志同目录"
pair_check LOGGER CRASH_REPORT "未查看崩溃要用 logger 留痕"
pair_check RUNTIME LEASE "回收的是 runtime 注册的资源"
pair_check CRASH_LOGGER NATIVE_CRASH_MEMORY "信号处理器要先有捕获器兜底"

echo ""
echo "[C] 失败语义：一步失败不阻断后续"

if grep -q "runCatching { execute(step, app) }" "$BOOT"; then
  good "C1 每步独立 runCatching"
else
  bad "C1 缺少逐步 runCatching —— 一步失败会中断整个启动"
fi

n_enum=$(echo "$steps" | grep -c . || true)
n_arm=$(grep -cE '^            Step\.[A-Z_]+ ->' "$BOOT" || true)
if [ "$n_enum" -eq "$n_arm" ]; then
  good "C2 枚举 $n_enum 项 ↔ when 分支 $n_arm 条（无遗漏）"
else
  bad "C2 枚举 $n_enum 项，但 when 只有 $n_arm 条 —— 有步骤没实现"
fi

if grep -qE '^            else ->' "$BOOT"; then
  bad "C3 when 里有 else —— 新增步骤会被静默跳过"
else
  good "C3 when 无 else（枚举穷尽，新增步骤必须显式实现）"
fi

# DEVICE_PROBE 在新线程里跑，外层 runCatching 抓不到它——内层必须自己兜。
# 范围要卡到 `.start()` 为止：原来用 `^            }` 收尾会一路吃到 run() 里的
# runCatching，于是无论内层有没有兜都会判绿——这条断言本身踩过一次假绿。
probe_block=$(awk '/Step.DEVICE_PROBE ->/{f=1} f{print} f&&/\.start\(\)/{exit}' "$BOOT")
# 只认真正的调用，不认注释里解释「为什么要兜」的那句话——否则断言会被自己的注释骗过。
if grep -qE 'runCatching[[:space:]]*\{[[:space:]]*AndroidDeviceProbe' <<<"$probe_block"; then
  good "C4 设备画像线程内自带 runCatching（跨线程异常外层抓不到）"
else
  bad "C4 设备画像缺线程内 runCatching —— probe 崩了只留一行 worker 日志"
fi

echo ""
echo "[D] 收口补漏：租约节拍器不再自建 scope"

if grep -q "fun start(scope: CoroutineScope = AppScope.app)" "$SRC/runtime/lease/LeaseRegistry.kt"; then
  good "D1 LeaseRegistry.start 默认用 AppScope.app"
else
  bad "D1 LeaseRegistry.start 仍在默认参数里新建 scope"
fi

left=$(grep -rn "CoroutineScope(SupervisorJob() + Dispatchers.Default)" --include=*.kt "$SRC" || true)
if [ -z "$left" ]; then
  good "D2 全仓无 SupervisorJob + Dispatchers.Default 自建残留"
else
  bad "D2 仍有自建默认调度器 scope："
  echo "$left" | sed 's/^/         /'
fi

echo ""
echo "[E] 顺序可读、可对账"

if grep -q "it.name.lowercase().replace('_', '-')" "$BOOT"; then
  good "E1 describe() 输出稳定格式（供日志页与断言对照）"
else
  bad "E1 describe() 格式被改，排障串会对不上"
fi

if grep -q 'AgentLogger.i("Boot", "启动顺序' "$BOOT"; then
  good "E2 启动时把实际顺序写进日志（真机可直接核对）"
else
  bad "E2 启动未记录顺序"
fi

echo ""
echo "=== $npass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]
