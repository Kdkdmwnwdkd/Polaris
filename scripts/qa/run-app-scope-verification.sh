#!/usr/bin/env bash
# R-14（协程上下文收口）验证。
#
# 守的是一条很容易被好心改坏的设计：
#   **收口的是「调度器 + 异常出口」，不是「生命周期」。**
#
# 如果有人（包括未来的我）看到 6 个 `CoroutineScope(SupervisorJob() + ...)`
# 觉得「这不就是重复代码吗」，把它们换成一个全局 scope，后果是：
#   `TerminalSessionPool.closeAll()` 里那句 `scope.cancel()` 会取消整个进程的所有协程。
# 编译照样通过，测试照样绿，真机上表现为「关掉一个终端池，后台任务全没了」。
#
# 所以这里必须同时断言两件事：
#   ① 上下文统一了（AppScope.io）
#   ② Job 仍然是各实例自己的（SupervisorJob 还在、io 里没有 Job、cancel 还在）
set -uo pipefail

cd "$(dirname "$0")/../.."
SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
HUB="$SRC_CORE/core/AppScope.kt"

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }

echo "=== R-14 协程上下文收口 ==="
echo ""
echo "[A] 上下文统一"

[ -f "$HUB" ] || { echo "FAIL 缺少 $HUB"; exit 1; }

n=$(grep -rn "SupervisorJob() + AppScope.io" --include=*.kt "$SRC" "$SRC_CORE" | wc -l | tr -d ' ')
if [ "$n" -ge 6 ]; then
  good "A1 $n 处实例作用域已接入 AppScope.io"
else
  bad "A1 只有 $n 处接入（收口前是 6 处）"
fi

naked=$(grep -rn "CoroutineScope(Dispatchers.IO)" --include=*.kt "$SRC" "$SRC_CORE" | grep -v "AppScope.kt" || true)
if [ -z "$naked" ]; then
  good "A2 无裸 CoroutineScope(Dispatchers.IO) 启动（两处已归入 AppScope.app）"
else
  bad "A2 仍有裸启动："
  echo "$naked" | sed 's/^/         /'
fi

old=$(grep -rn "SupervisorJob() + Dispatchers.IO)" --include=*.kt "$SRC" "$SRC_CORE" | grep -v "AppScope.kt" || true)
if [ -z "$old" ]; then
  good "A3 无遗留的「各写一份 Dispatchers.IO」"
else
  bad "A3 仍有未接入的旧写法："
  echo "$old" | sed 's/^/         /'
fi

echo ""
echo "[B] 生命周期没有被收口（关键）"

if grep -qE 'val io: CoroutineContext[[:space:]]*=' "$HUB"; then
  # io 必须是「Dispatchers + Name + Handler」，一旦混入 Job 就会顶掉实例自己的 Job
  io_line=$(grep -A2 'val io: CoroutineContext' "$HUB" | tr '\n' ' ')
  if echo "$io_line" | grep -q "SupervisorJob\|Job()"; then
    bad "B1 AppScope.io 里混入了 Job —— 会顶掉实例自有生命周期"
  else
    good "B1 AppScope.io 不含 Job（生命周期仍归各实例）"
  fi
else
  bad "B1 AppScope 缺 io 上下文"
fi

cancels=$(grep -rn "scope.cancel()" --include=*.kt "$SRC" "$SRC_CORE" | wc -l | tr -d ' ')
if [ "$cancels" -ge 3 ]; then
  good "B2 实例取消点保留（$cancels 处 scope.cancel 未被误删）"
else
  bad "B2 只剩 $cancels 处 scope.cancel —— 生命周期可能被误收口"
fi

if grep -q "val app: CoroutineScope" "$HUB"; then
  good "B3 进程级作用域 app 存在（给异步释放/空闲回收用）"
else
  bad "B3 缺 app 作用域"
fi

uses=$(grep -rn "AppScope.app.launch" --include=*.kt "$SRC" "$SRC_CORE" | wc -l | tr -d ' ')
if [ "$uses" -ge 2 ]; then
  good "B4 $uses 处「不受实例取消影响」的动作有了归属"
else
  bad "B4 只有 $uses 处用 app（应为异步释放 + 空闲回收两处）"
fi

echo ""
echo "[C] 异常不再静默"

if grep -q "CoroutineExceptionHandler" "$HUB"; then
  good "C1 装了异常处理器（原来抛到线程默认处理器，日志里只有一行 worker-N）"
else
  bad "C1 缺 CoroutineExceptionHandler"
fi

echo ""
echo "=== $npass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]
