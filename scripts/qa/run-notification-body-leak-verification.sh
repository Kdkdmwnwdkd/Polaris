#!/usr/bin/env bash
# 审计 F11「通知正文随每条消息自动外发」验证。
#
# [行为] 编译真实的 NotificationDigest / DeviceContext（零 Android 依赖）跑 34 条断言：
#        注入文本里没有任何正文片段、明确声明「没有正文」、聚合计数与排序、
#        超限折叠、时间措辞（含时钟回拨）、结构不变量（NotificationSummary 装不下正文）。
# [接线] AgentNotificationListener 拖 NotificationListenerService，只能静态断言：
#        它不再拼 "$label - $body"、DeviceContextStore 只收 NotificationSummary、
#        自动注入链路仍在（没把功能一起砍掉）、NotificationStore 仍存全文（工具还要用）。
#
# 为什么值得单独一个 runner：这条缺陷**不会以「出错」的形式暴露**。
# 通知正文合法地、按设计地流进上下文，界面一切正常，日志一切正常；
# 唯一的证据是「本该弹授权的那次读取，从来没有发生过」。
# 而 F9 的审批覆盖了 `device_notifications`，看起来这件事已经做完了。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

AUTO=app/src/main/java/com/beichen/polaris/automation
HARNESS=scripts/qa/NotificationBodyLeakVerification.kt
DIGEST="$AUTO/NotificationDigest.kt"
CTX="$AUTO/DeviceContext.kt"

CACHE="$QA_GRADLE_CACHE"
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-1.9*.jar")
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"

KOTLINC="$KC_HOME/bin/kotlinc"
if [ ! -x "$KOTLINC" ]; then
  KOTLINC_JAR=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
  [ -n "${KOTLINC_JAR:-}" ] || KOTLINC_JAR="$KC_HOME/lib/kotlin-compiler.jar"
fi

need() { [ -e "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }; }
need "Kotlin 编译器" "${KOTLINC}"
[ -x "$KOTLINC" ] || need "kotlin-compiler-embeddable" "${KOTLINC_JAR}"
need "kotlin-stdlib" "$STDLIB"
need "NotificationDigest.kt" "$DIGEST"
need "DeviceContext.kt" "$CTX"

OUT=/tmp/qa-notification-leak
rm -rf "$OUT" && mkdir -p "$OUT"

compile() {
  if [ -x "$KOTLINC" ]; then
    "$KOTLINC" -no-reflect -classpath "$STDLIB" -d "$OUT" -nowarn "$DIGEST" "$CTX" "$HARNESS" 2>&1 | head -20
  else
    java -cp "$KOTLINC_JAR:$STDLIB" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$STDLIB" -d "$OUT" -nowarn "$DIGEST" "$CTX" "$HARNESS" 2>&1 | head -20
  fi
}

BODY=$(compile)
RUN=$(java -cp "$OUT:$STDLIB" NotificationBodyLeakVerificationKt 2>&1); rc=$?
echo "$RUN"
if [ ! -d "$OUT/com" ]; then
  echo "$BODY"
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi
if [ "$rc" -ne 0 ]; then
  echo "FAIL 行为断言未通过（见上方 FAIL 行）"
  exit 1
fi
JS=$(echo "$RUN" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [W] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
w_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
w_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[W] 接线"
LISTENER="$AUTO/AgentNotificationListener.kt"
STORE="$AUTO/DeviceContextStore.kt"
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
CONSOLE=app/src/main/java/com/beichen/polaris/ui/ConsoleScreen.kt

# W1：缺陷的原始写法。注释里提到它是可以的（说明改动缘由），代码行里出现就是回归。
if grep -E 'addNotification\("' "$LISTENER" | grep -qvE '^[[:space:]]*(//|\*)'; then
  w_bad "W1 监听服务仍在传字符串（正文会跟着进去）"
else
  w_good "W1 监听服务不再把正文拼进设备上下文"
fi

# W2：传的必须是结构化摘要。
if grep -q 'NotificationSummary(' "$LISTENER"; then
  w_good "W2 监听服务传的是 NotificationSummary（只有来源与时间）"
else
  w_bad "W2 监听服务没有构造 NotificationSummary"
fi

# W3：store 的入参类型。参数写成 String 的话，下一个改动者随手就能塞正文。
if grep -qE 'fun addNotification\(summary: NotificationSummary\)' "$STORE"; then
  w_good "W3 addNotification 的入参类型是 NotificationSummary"
else
  w_bad "W3 addNotification 入参不是 NotificationSummary —— 类型上又能装正文了"
fi

# W4：队列元素类型。这是「装不下」的结构性保证，比出口过滤可靠。
if grep -q 'ConcurrentLinkedQueue<NotificationSummary>' "$STORE"; then
  w_good "W4 队列元素类型是 NotificationSummary"
else
  w_bad "W4 队列仍是 ConcurrentLinkedQueue<String>"
fi

# W5：`List<String>` 这种「什么都能装」的形状是缺陷根源，不能再出现在通知列表上。
if grep -q 'recentNotifications: List<String>' "$STORE"; then
  w_bad "W5 recentNotifications 仍是 List<String>"
else
  w_good "W5 通知列表不再是 List<String>"
fi

# W6：自动注入链路必须还在。反过来把整段删掉也能让上面全绿，但那是砍功能不是修缺陷。
if grep -q 'DeviceContextStore.snapshot().toPrompt()' "$VM"; then
  w_good "W6 ChatViewModel 仍注入设备上下文（只删了正文，没删功能）"
else
  w_bad "W6 ChatViewModel 不再注入设备上下文 —— 修法是砍功能，不是收敛数据"
fi

# W7：正文不能跟着一起被删。`device_notifications` 还要读它（那条路有 F9 的审批）。
if grep -q 'NotificationStore.add(' "$LISTENER"; then
  w_good "W7 NotificationStore 仍存全文（device_notifications 的数据源）"
else
  w_bad "W7 NotificationStore 不再收通知 —— 正文来源被误删"
fi

# W8：控制台展示也要走聚合（否则 UI 会试着渲染 NotificationSummary，编不过）。
if grep -q 'NotificationDigest.lines(' "$CONSOLE"; then
  w_good "W8 控制台展示走 NotificationDigest.lines"
else
  w_bad "W8 控制台仍在直接渲染 notifications 元素"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
