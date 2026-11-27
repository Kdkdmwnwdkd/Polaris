#!/usr/bin/env bash
# 启动自救（StartupGuard）行为验证。
#
# 为什么不能只做源码 grep：这套逻辑只在「用户已经被锁在门外」的那一刻执行，
# 开发机上永远自然触发不到。grep 能证明 `StartupGuard.begin(this)` 这行存在，
# 证明不了「连续三次没起来时它真的搬了状态、而且没把日志搬走」。
# 所以这里编译真实的 StartupGuardPolicy / StartupQuarantine 跑一遍。
#
# 与其它 harness 的区别：被测的两个文件**不依赖 Android**（判据与搬移都抽成了纯逻辑），
# 因此这里不需要 Android 桩，沙箱里也能真跑。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC=app/src/main/java/com/beichen/polaris/startup
HARNESS=scripts/qa/StartupGuardVerification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"

SOURCES=(
  "$SRC/StartupGuardPolicy.kt"
  "$SRC/StartupQuarantine.kt"
)
for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

OUT=/tmp/qa-startup-guard
rm -rf "$OUT"; mkdir -p "$OUT"

COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -cp "$STDLIB" \
  -d "$OUT" -nowarn \
  "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
if [ ! -d "$OUT/com" ]; then
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi

BODY=$(java -cp "$OUT:$STDLIB" StartupGuardVerificationKt)
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线：判据再好，没人调用也等于没有 ───────────────────────────────────
# 这三处只能静态断言：Application 与 MainActivity 都拖 Android/Compose 依赖，沙箱编不动。
# 但「函数写好了、没人调用」正是本轮最容易复发的那种缺陷。
gp=0; gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线单点"
APP=app/src/main/java/com/beichen/polaris/AgentKitApplication.kt
MAIN=app/src/main/java/com/beichen/polaris/MainActivity.kt

if grep -q 'StartupGuard.begin(this)' "$APP"; then
  g_good "G1 Application.onCreate 调用 StartupGuard.begin"
else
  g_bad "G1 没接 begin —— 计数永远不会开始，自救是死代码"
fi

# begin 必须在 BootSequence 之前：BootSequence 里有会起线程/起 EGL 的步骤，
# 排在它后面就会把「已经崩了」的时间算进启动耗时，判定窗口失真。
if [ -n "$(grep -n 'StartupGuard.begin(this)' "$APP" | head -1)" ] &&
   [ -n "$(grep -n 'BootSequence.run(this)' "$APP" | head -1)" ]; then
  b=$(grep -n 'StartupGuard.begin(this)' "$APP" | head -1 | cut -d: -f1)
  s=$(grep -n 'BootSequence.run(this)' "$APP" | head -1 | cut -d: -f1)
  if [ "$b" -lt "$s" ]; then
    g_good "G2 begin 排在 BootSequence 之前"
  else
    g_bad "G2 begin 排在 BootSequence 之后（应更早）"
  fi
else
  g_bad "G2 找不到 begin 或 BootSequence 调用点"
fi

# markBooted 必须在「viewModel 创建之后」：ChatViewModel 的构造期读盘是最可能崩的地方，
# 早于它就标记成功，等于把每次启动都判成「起来了」，自救永不触发。
if grep -q 'StartupGuard.markBooted' "$MAIN"; then
  # 判据只认「创建点」这个语义，不认具体写法。
  #   2026-11-27 之前是 `viewModel<ChatViewModel>()`；
  #   之后为了让构造异常可捕获（而不是在组合期崩掉整个界面），改成
  #   `ViewModelProvider(this, chatVmFactory)[ChatViewModel::class.java]`。
  # 两种形态都算创建点——钉写法的话，每次正当重构都会红一次，红多了就没人看了。
  v=$(grep -nE 'viewModel<ChatViewModel>\(|ChatViewModel::class\.java' "$MAIN" | head -1 | cut -d: -f1)
  m=$(grep -n 'StartupGuard.markBooted' "$MAIN" | head -1 | cut -d: -f1)
  if [ -n "$v" ] && [ -n "$m" ] && [ "$m" -gt "$v" ]; then
    g_good "G3 markBooted 在 ChatViewModel 创建之后"
  else
    g_bad "G3 markBooted 早于 ChatViewModel 创建 —— 构造期崩溃会被判成启动成功"
  fi
else
  g_bad "G3 没接 markBooted —— 每次启动都算失败，用几天就会被误隔离"
fi

# 全仓只能有一处 markBooted：多一处（比如某个 onResume）就会在首帧前把计数清零
mc=$(grep -rn 'StartupGuard.markBooted' --include=*.kt app core | wc -l | tr -d ' ')
if [ "$mc" = "1" ]; then
  g_good "G4 markBooted 只有一处调用"
else
  g_bad "G4 markBooted 出现 $mc 处 —— 任一处提前调用都会让自救失效"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
