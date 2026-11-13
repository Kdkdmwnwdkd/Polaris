#!/usr/bin/env bash
# 桌面助手（桌宠一期）守卫。
#
# ## 为什么这个脚本只能做静态断言
#
# 桌宠的三块内容——前台服务、WindowManager 悬浮窗、Compose 视图——没有一块能在
# JVM 上跑：它们要真实的 `Service` 上下文、`WindowManager`、消息循环。所以这里
# 不假装有行为测试，而是把**那些一旦写错就会静默失效的不变量**逐条钉住。
#
# 下面每一条都对应一个具体的失败模式，不是「代码风格检查」：
#
#   A  服务注册   漏了 specialUse → Android 14 上前台服务起不来，悬浮窗一闪就没
#   B  启动顺序   startForeground 晚于加窗 → 5 秒超时 ANR（用户看到的是「点开启就闪退」）
#   C  状态回路   onDestroy 不回报 running=false → 设置页永远显示「运行中」，开关成摆设
#   D  工具边界   桌宠没有审批界面，一旦混进写工具就是「无审批 + 有副作用」
#   E  窗口语义   收起态不缩到形象大小 → 透明矩形挡住底下应用的点击
#   F  单点复用   自己 new TextSpeaker → 与主界面叠音（AudioGraph 的 KDoc 写了原因）
#   G  常驻成本   一期不做语音，服务里出现 AudioRecord 就是范围失控
#
# 退出码：0 通过 / 1 有违规。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)

fail=0
npass=0
say()  { echo "$*"; }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }

MANI=app/src/main/AndroidManifest.xml
SERVICE=app/src/main/java/com/beichen/polaris/pet/PetOverlayService.kt
STATE=app/src/main/java/com/beichen/polaris/pet/PetState.kt
SESSION=app/src/main/java/com/beichen/polaris/pet/PetAgentSession.kt
OVERLAY=app/src/main/java/com/beichen/polaris/pet/PetOverlay.kt
PANEL=app/src/main/java/com/beichen/polaris/pet/PetPanel.kt
SETTINGS=app/src/main/java/com/beichen/polaris/ui/SettingsScreen.kt
GRAPH=app/src/main/java/com/beichen/polaris/AppGraph.kt
APP=app/src/main/java/com/beichen/polaris/AgentKitApplication.kt
ACT=app/src/main/java/com/beichen/polaris/MainActivity.kt

say "=== 桌面助手守卫（服务 / 状态 / 工具边界 / 窗口语义）==="
say ""

missing=0
for f in "$MANI" "$SERVICE" "$STATE" "$SESSION" "$OVERLAY" "$PANEL" "$SETTINGS" "$GRAPH"; do
  if [ ! -f "$f" ]; then
    echo "FAIL 缺少文件 $f"
    missing=$((missing + 1))
  fi
done
if [ "$missing" -gt 0 ]; then
  echo "FAIL 桌宠骨架文件不全，后续断言无法判定"
  exit 1
fi

# ---------------------------------------------------------------- A 服务注册
say "[A] 前台服务注册"

if grep -q 'android:name=".pet.PetOverlayService"' "$MANI"; then
  good "A1 Manifest 注册了 PetOverlayService"
else
  bad "A1 Manifest 里找不到 .pet.PetOverlayService —— 服务起不来"
fi

svc_block=$(awk '/\.pet\.PetOverlayService/{f=1} f{print} f&&/\/>/{exit}' "$MANI")
if printf '%s' "$svc_block" | grep -q 'foregroundServiceType="specialUse"'; then
  good "A2 前台服务类型为 specialUse（Android 14 上不声明会起不来）"
else
  bad "A2 缺少 foregroundServiceType=\"specialUse\""
fi

if printf '%s' "$svc_block" | grep -q 'android:exported="false"'; then
  good "A3 exported=false（只由本 App 启动，不对外暴露）"
else
  bad "A3 服务没有显式 exported=false"
fi

if grep -q 'android:process' "$MANI"; then
  # 跨进程会让 AudioGraph / PetState 各有一份，麦克风与 TTS 的「唯一持有者」当场失效
  bad "A4 Manifest 里出现了 android:process —— 桌宠必须与主应用同进程（否则 AudioGraph 不是同一个实例）"
else
  good "A4 全应用单进程，AudioGraph 与 PetState 是同一个实例"
fi

say ""

# ---------------------------------------------------------------- B 启动顺序
say "[B] 前台服务的启动顺序"

l_fg=$(grep -n 'startAsForeground()' "$SERVICE" | head -1 | cut -d: -f1)
l_show=$(grep -n 'host.show()' "$SERVICE" | head -1 | cut -d: -f1)
if [ -n "${l_fg:-}" ] && [ -n "${l_show:-}" ] && [ "$l_fg" -lt "$l_show" ]; then
  good "B1 startForeground（第 $l_fg 行）早于加窗（第 $l_show 行）——不会撞 5 秒 ANR"
else
  bad "B1 startForeground 必须在加窗之前（当前 fg=${l_fg:-无} show=${l_show:-无}）"
fi

if awk '/override fun onStartCommand/,/^    }/' "$SERVICE" | grep -q 'overlay == null'; then
  good "B2 onStartCommand 里判空：被 START_STICKY 重建但 onCreate 已判定不该存在时，立刻退出"
else
  bad "B2 onStartCommand 没检查 overlay 是否为空 —— 服务会空转"
fi

say ""

# ---------------------------------------------------------------- C 状态回路
say "[C] 开关状态与运行态的回路"

if awk '/override fun onDestroy/,/^    }/' "$SERVICE" | grep -q 'markRunning(false)'; then
  good "C1 onDestroy 回报 running=false（否则设置页永远显示运行中）"
else
  bad "C1 onDestroy 没有 PetState.markRunning(false)"
fi

if awk '/override fun onCreate/,/^    }/' "$SERVICE" | grep -q 'markRunning(true)'; then
  good "C2 onCreate 回报 running=true"
else
  bad "C2 onCreate 没有 PetState.markRunning(true)"
fi

if awk '/fun start\(/,/^    }/' "$STATE" | grep -q 'canDrawOverlays'; then
  good "C3 启动前复查悬浮窗权限（权限被回收时不会静默失败）"
else
  bad "C3 PetState.start 没有检查 canDrawOverlays"
fi

if awk '/fun stop\(/,/^    }/' "$STATE" | grep -q 'stopService'; then
  good "C4 PetState.stop 真的停服务，不只是改标志位"
else
  bad "C4 PetState.stop 没有 stopService —— 关不掉"
fi

if grep -q 'PetState.reset()' "$GRAPH" && grep -q 'name = "PetState"' "$GRAPH"; then
  good "C5 AppGraph 登记了 PetState（含 reset）"
else
  bad "C5 AppGraph 未登记 PetState —— 全局状态清单不再完整"
fi

if grep -q 'PetState.ensureRunning' "$ACT"; then
  good "C6 MainActivity.onResume 补启动：服务被系统杀了，回 App 能自己回来"
else
  bad "C6 MainActivity 没有 ensureRunning —— 服务被清理后只能手动重开"
fi

if grep -q 'PetState.start(context)' "$SETTINGS"; then
  good "C7 设置页有开关入口"
else
  bad "C7 设置页没有接入 PetState"
fi

say ""

# ---------------------------------------------------------------- D 工具边界
say "[D] 工具边界（无审批 ⇒ 只能只读）"

allowed="CalculatorTool WebSearchTool ReadFileTool MemorySearchTool SpeakTool"
registered=$(grep -oE 'register\([A-Za-z]+Tool\(' "$SESSION" | sed 's/register(//; s/($//' | sort -u)
if [ -z "$registered" ]; then
  bad "D1 没解析到任何 register(...) —— 桌宠拿不到任何工具"
else
  rogue=""
  for t in $registered; do
    if ! printf '%s' " $allowed " | grep -q " $t "; then
      rogue="$rogue $t"
    fi
  done
  if [ -n "$rogue" ]; then
    bad "D1 桌宠注册了白名单外的工具：$rogue
        桌宠没有审批界面，加写工具前必须先做审批通道，不能只往 registry 里补一行"
  else
    good "D1 工具集只在只读白名单内（$(printf '%s' "$registered" | tr '\n' ' ')）"
  fi
fi

if grep -qE '^\s*approval\s*=' "$SESSION"; then
  bad "D2 PetAgentSession 传了 approval —— 桌宠没有审批界面，这个参数只该保持 null"
else
  good "D2 approval 保持 null（与「只有只读工具」这条边界一致）"
fi

if grep -q 'local-llama' "$SESSION" && grep -qi '不支持本地模型' "$SESSION"; then
  good "D3 本地模型明确拒绝（端侧推理是分钟级，桌宠要的是立刻出声）"
else
  bad "D3 没有对 local-llama 的明确拒绝 —— 用户会看到一个永远转圈的桌宠"
fi

say ""

# ---------------------------------------------------------------- E 窗口语义
say "[E] 悬浮窗语义"

if grep -q 'TYPE_APPLICATION_OVERLAY' "$OVERLAY"; then
  good "E1 使用 TYPE_APPLICATION_OVERLAY（API 26+ 唯一合法类型）"
else
  bad "E1 没有使用 TYPE_APPLICATION_OVERLAY"
fi

if grep -q 'FLAG_NOT_FOCUSABLE.inv()' "$OVERLAY"; then
  good "E2 展开时清 FLAG_NOT_FOCUSABLE（不清则输入框点不出软键盘）"
else
  bad "E2 展开态没有清 FLAG_NOT_FOCUSABLE —— 打字打不了"
fi

if grep -q 'FLAG_NOT_TOUCH_MODAL' "$OVERLAY"; then
  good "E3 保留 FLAG_NOT_TOUCH_MODAL（窗口外的触摸要能穿透到下面的应用）"
else
  bad "E3 缺 FLAG_NOT_TOUCH_MODAL —— 展开时整块屏幕都点不动"
fi

if grep -q 'px(PET_SIZE_DP)' "$OVERLAY"; then
  good "E4 收起态窗口宽度等于形象尺寸（透明矩形一样会拦截触摸）"
else
  bad "E4 收起态没有缩到形象尺寸 —— 桌宠旁边会有一片点不动的区域"
fi

if grep -q 'snapToEdge' "$OVERLAY"; then
  good "E5 松手吸附到左右边缘"
else
  bad "E5 没有贴边吸附，桌宠会被拖到屏幕外"
fi

say ""

# ---------------------------------------------------------------- F 单点复用
say "[F] 复用既有单点"

if grep -q 'AudioGraph.speaker(context).speak' "$OVERLAY"; then
  good "F1 朗读走 AudioGraph.speaker（进程级唯一引擎）"
else
  bad "F1 朗读没有走 AudioGraph —— 会得到第二个 TTS 引擎，与主界面叠音"
fi

if grep -q 'AudioGraph.arbiter(appContext)' "$SESSION"; then
  good "F2 speak 工具拿到的是进程级仲裁器（播报与录音互斥才成立）"
else
  bad "F2 SpeakTool 没有接 AudioGraph.arbiter"
fi

if grep -q 'new TextSpeaker' "$OVERLAY" "$SESSION" "$SERVICE" 2>/dev/null; then
  bad "F3 桌宠里出现了 new TextSpeaker —— 必须是 AudioGraph.speaker(ctx)"
else
  good "F3 桌宠没有自己 new TTS 引擎"
fi

# 踩过一次：`core.lifecycle.launchAppIntentForNotification` 是 internal，
# core 模块内部（AgentRunService）能用，app 模块调用直接编译失败。
# 这不是桌宠特有的坑，但桌宠是唯一需要「从 Service 拉起 App」的地方，
# 所以钉在这里：以后有人图省事再 import 回来，本地就会红，不必等 24 分钟 CI。
if grep -n 'launchAppIntentForNotification' "$SERVICE" 2>/dev/null \
    | grep -qvE '^[0-9]+:[[:space:]]*(\*|//)'; then
  bad "F4 PetOverlayService 引用了 core 的 internal 函数（跨模块编译不过），用 packageManager.getLaunchIntentForPackage"
else
  good "F4 拉起主应用走 packageManager，没有跨模块引用 internal"
fi

say ""

# ---------------------------------------------------------------- G 范围控制
say "[G] 一期范围（不做常驻麦克风）"

if grep -qE 'AudioRecord|RECORD_AUDIO' "$SERVICE" "$OVERLAY" "$SESSION" 2>/dev/null; then
  bad "G1 一期骨架里出现了录音相关代码 —— 语音是二期，别把耗电风险提前引进来"
else
  good "G1 没有常驻录音（一期只有文字问答 + 朗读）"
fi

if grep -q 'IMPORTANCE_MIN' "$SERVICE"; then
  good "G2 常驻通知走最低优先级渠道（不响、不亮、不打扰）"
else
  bad "G2 常驻通知没有用 IMPORTANCE_MIN —— 用户会被一条常驻通知烦到"
fi

say ""
if [ "$fail" -eq 0 ]; then
  echo "结果：$npass 通过 / 0 失败"
  exit 0
else
  echo "结果：$npass 通过 / $fail 失败"
  exit 1
fi
