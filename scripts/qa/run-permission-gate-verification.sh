#!/usr/bin/env bash
# 权限闸门判据（2026-11-20）：把「声明了却不申请」与「申请了却没出口」两类死路变成硬断言。
#
# 由来：清单里 29 条权限、日常路径零弹窗。核查发现两件事——
#   1) 聊天页语音入口只判断「离线模型装没装」，没麦克风权限时面板里只回报一行红字，
#      没有按钮也没有跳转，而本应用设置页里根本没有「麦克风」这一项。从聊天页进来的
#      用户永远拿不到授权（同一仓库里 VoiceCloneScreen 的写法是对的，属漏改）；
#   2) 11 条权限在代码里零使用：CAMERA、READ/WRITE_EXTERNAL_STORAGE、READ_MEDIA_*、
#      MANAGE_EXTERNAL_STORAGE、WRITE_SETTINGS、SCHEDULE_EXACT_ALARM、SET_ALARM、
#      KILL_BACKGROUND_PROCESSES。
#
# 这类缺陷的共同点：编译过、JVM 单测过、真机上只是「某个按钮点了没反应」，
# 静态读代码也看不出来。所以判据必须同时盯住「声明」和「申请点」两侧。
#
# 2026-11-23：这条判据原先有个洞——「授予后开面板」用 grep 看 open() 是否跟
# 在 if (granted) 后面，把 open() 挪进 else 分支照样全绿。判定的语义已搬到
# VoiceInputDecision（纯函数，有 JUnit 与变异门禁 ui 组），本脚本只守接线。
#
# 退出码：0 通过 / 1 有违规。
set -uo pipefail
cd "$(dirname "$0")/../.."

CHAT=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt
GATE=app/src/main/java/com/beichen/polaris/ui/VoiceInputGate.kt
MANIFEST=app/src/main/AndroidManifest.xml

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }

say() { echo "$*"; }

# ── 语音入口闸门：六个要素缺一不可 ────────────────────────────────────────────
# 抽成函数是为了反向自检：同一套判据喂旧实现必须判红，否则判据只是摆设。
voice_gate_ok() {
  local f=$1
  grep -q 'isInstalled()' "$f" || return 1
  grep -q 'checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)' "$f" || return 1
  grep -q 'ActivityResultContracts.RequestPermission()' "$f" || return 1
  grep -q 'launch(Manifest.permission.RECORD_AUDIO)' "$f" || return 1
  grep -q 'ACTION_APPLICATION_DETAILS_SETTINGS' "$f" || return 1
  # 分支判定必须交给纯函数（2026-11-23）。
  # 旧判据是 `grep -A3 'if (granted) {' | grep 'open()'`，只看字面顺序不看语义——
  # 把 open() 挪进 else 分支，六条 grep 全绿而真机「授权了也不开面板」，已实测复现。
  # 语义现在由 VoiceInputDecisionTest + mutation-gate 的 ui 组守；这里只守接线。
  grep -q 'VoiceInputDecision.next(' "$f" || return 1
  # 四条出路都要有落点，且 when 不许写 else 兜底：加了 else，新枚举值会被静默吞掉。
  for a in TellInstallModel OpenPanel GoSettings RequestPermission; do
    grep -q "VoiceInputNext.$a" "$f" || return 1
  done
  if grep -q 'else ->' "$f"; then return 1; fi
  return 0
}

say "=== 权限闸门判据 ==="
say ""

say "[A] 聊天页语音入口：未授权时必须能拿到授权（结构 + 接线；语义由 ui 组守）"
if [ ! -f "$GATE" ]; then
  bad "找不到 $GATE"
elif voice_gate_ok "$GATE"; then
  good "入口具备：模型分支 + 权限检查 + 系统申请 + 授予后开面板 + 永久拒绝兜底"
else
  bad "入口仍是死路（缺上列某要素，详见下方逐项）"
  grep -q 'isInstalled()' "$GATE"                            || bad "  └ 缺「模型未装」分支"
  grep -q 'checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)' "$GATE" || bad "  └ 缺 checkSelfPermission"
  grep -q 'ActivityResultContracts.RequestPermission()' "$GATE" || bad "  └ 缺 RequestPermission launcher"
  grep -q 'launch(Manifest.permission.RECORD_AUDIO)' "$GATE"  || bad "  └ 缺 launch(RECORD_AUDIO)"
  grep -q 'ACTION_APPLICATION_DETAILS_SETTINGS' "$GATE"       || bad "  └ 缺永久拒绝后的设置页兜底"
  grep -q 'VoiceInputDecision.next(' "$GATE"                  || bad "  └ 分支判定没交给 VoiceInputDecision（又回到 if/else 手写）"
  for a in TellInstallModel OpenPanel GoSettings RequestPermission; do
    grep -q "VoiceInputNext.$a" "$GATE"                       || bad "  └ 缺分支落点：$a"
  done
  if grep -q 'else ->' "$GATE"; then bad "  └ when 写了 else 兜底（新枚举值会被静默吞掉）"; fi
fi

say ""
say "[A2] 接线：ChatScreen 必须真的调用闸门，且不再内联第二份实现"
if grep -q 'rememberVoiceInputGate { localVoice = true }' "$CHAT"; then
  good "ChatScreen 调用 rememberVoiceInputGate"
else
  bad "ChatScreen 没接上闸门（实现存在但入口没调用，仍然是死路）"
fi
if grep -q 'launch(Manifest.permission.RECORD_AUDIO)' "$CHAT"; then
  bad "ChatScreen 仍有内联的 RECORD_AUDIO 申请（两份实现会分叉）"
else
  good "ChatScreen 无内联申请"
fi

say ""
say "[B] 清单最小化：零使用的声明必须已删除"
DEAD=(
  "android.permission.CAMERA"
  "android.permission.READ_EXTERNAL_STORAGE"
  "android.permission.WRITE_EXTERNAL_STORAGE"
  "android.permission.READ_MEDIA_IMAGES"
  "android.permission.READ_MEDIA_VIDEO"
  "android.permission.READ_MEDIA_AUDIO"
  "android.permission.MANAGE_EXTERNAL_STORAGE"
  "android.permission.WRITE_SETTINGS"
  "android.permission.SCHEDULE_EXACT_ALARM"
  "com.android.alarm.permission.SET_ALARM"
  "android.permission.KILL_BACKGROUND_PROCESSES"
)
for p in "${DEAD[@]}"; do
  if grep -q "android:name=\"$p\"" "$MANIFEST"; then
    bad "仍是死声明：$p"
  else
    good "已移除：$p"
  fi
done

say ""
say "[C] 保留项：库依赖与真实功能所需的声明不许被误删"
KEEP=(
  "android.permission.RECORD_AUDIO"
  "android.permission.POST_NOTIFICATIONS"
  "android.permission.WAKE_LOCK"
  "android.permission.RECEIVE_BOOT_COMPLETED"
  "android.permission.SYSTEM_ALERT_WINDOW"
  "android.permission.PACKAGE_USAGE_STATS"
  "android.permission.REQUEST_INSTALL_PACKAGES"
  "android.permission.QUERY_ALL_PACKAGES"
  "moe.shizuku.manager.permission.API_V23"
)
for p in "${KEEP[@]}"; do
  if grep -q "android:name=\"$p\"" "$MANIFEST"; then
    good "保留：$p"
  else
    bad "被误删：$p"
  fi
done

say ""
say "[D] 每个危险权限都必须有申请点（防「声明了却永远不申请」重演）"
for p in RECORD_AUDIO POST_NOTIFICATIONS; do
  if grep -rq "launch(Manifest.permission.$p)" app/src/main/java; then
    good "$p 有 launch 点"
  else
    bad "$p 声明了但全工程无申请点"
  fi
done

say ""
say "[E] 反向自检：旧实现必须被判红"
TMP=$(mktemp)
cat > "$TMP" <<'EOF'
    val startVoiceInput: () -> Unit = {
        if (AsrDirectory.of(ctx).isInstalled()) {
            localVoice = true
        } else {
            Toast.makeText(ctx, "先下模型", Toast.LENGTH_LONG).show()
        }
    }
EOF
if voice_gate_ok "$TMP"; then
  bad "自检失败：旧实现（只查模型）居然通过了判据，说明判据写空了"
else
  good "旧实现被判红"
fi
rm -f "$TMP"

# 第二种坏样本：调了纯函数，但把「跳设置页」写成 else 兜底（漏一支、且加了 else）。
TMP2=$(mktemp)
cat > "$TMP2" <<'EOF'
    when (VoiceInputDecision.next(installed, granted, asked)) {
        VoiceInputNext.TellInstallModel -> toast()
        VoiceInputNext.OpenPanel -> open()
        else -> goSettings()
    }
EOF
if voice_gate_ok "$TMP2"; then
  bad "自检失败：漏分支 + else 兜底的样本通过了判据"
else
  good "漏分支 / else 兜底被判红"
fi
rm -f "$TMP2"

say ""
if [ "$fail" -eq 0 ]; then
  echo "权限闸门：$npass 通过 / 0 失败"
  echo "FAIL=0"
  exit 0
else
  echo "权限闸门：$npass 通过 / $fail 失败"
  exit 1
fi
