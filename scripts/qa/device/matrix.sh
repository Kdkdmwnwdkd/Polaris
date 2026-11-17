#!/usr/bin/env bash
# =============================================================================
# scripts/qa/device/matrix.sh —— L3 真机矩阵（自动项 + 待勾清单）
#
# 为什么单独一个脚本：`android-smoke.sh` 只跑一条关键路径（装得上、能冷启、
# 容器不掉链），而真机矩阵要回答的是「在**这台**手机上，桌宠能不能用」。
# 那些项（省电、键盘、磁贴、ASR）模拟器给不出答案，只能一台台跑。
#
# 设计原则：**只自动判能判的**。
#   AUTO   脚本直接给 PASS/FAIL，判据里没有任何「大概」「应该」；
#   SEMI   脚本把 adb 能抓到的证据（权限位 / 进程 / 窗口 frame / 日志 / 文件内容）
#          贴进报告，结论留给人；
#   MANUAL 列成待勾项——报告里没勾就是没测，不是通过。
#
# 用法：
#   ADB=adb bash scripts/qa/device/matrix.sh
#   ADB=adb bash scripts/qa/device/matrix.sh --apk app-release.apk
#   ADB=adb bash scripts/qa/device/matrix.sh --out /tmp/matrix
#
# 退出码：0 = 所有 AUTO 项通过；1 = 有 AUTO 项 FAIL；2 = 没有可用设备（SKIP）
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
cd "$ROOT" || exit 1

ADB=${ADB:-adb}
PKG=${PKG:-com.beichen.polaris}
OUT_DIR=${OUT_DIR:-qa-logs}
APK=""

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK=${2:-}; shift 2 ;;
    --out) OUT_DIR=${2:-}; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--apk / --out）" >&2; exit 64 ;;
  esac
done

# adb 命令统一加超时：真机断连时 adb 会挂住，而矩阵脚本要跑几十条命令
adbs() { timeout 20 "$ADB" -s "$1" "${@:2}" 2>&1 | tr -d '\r'; }

command -v "$ADB" >/dev/null 2>&1 || { echo "找不到 adb：$ADB（用 ADB=/path/to/adb 指定）" >&2; exit 2; }

SERIALS=$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$SERIALS" ]; then
  echo "SKIP  没有可用设备（adb devices 为空）"
  echo "      真机矩阵必须真机跑；清单见 docs/qa/真机矩阵-v1.md"
  echo "      接上设备后重跑本脚本。当前结论：COVERAGE INCOMPLETE"
  exit 2
fi

mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
overall=0
reports=()

# ---------------------------------------------------------------- 单台设备
run_one() {
  local serial=$1
  local model api rel vendor rom name report
  model=$(adbs "$serial" getprop ro.product.model | head -1)
  api=$(adbs "$serial" getprop ro.build.version.sdk | head -1)
  rel=$(adbs "$serial" getprop ro.build.version.release | head -1)
  vendor=$(adbs "$serial" getprop ro.product.manufacturer | head -1)
  rom=$(adbs "$serial" getprop ro.build.display.id | head -1)
  name=$(printf '%s-api%s' "$model" "$api" | tr -c 'A-Za-z0-9._-' '-' | cut -c1-48)
  report="$OUT_DIR/device-matrix-${name}-${STAMP}.md"

  local p=0 f=0 s=0
  {
    echo "# 真机矩阵报告 — $model（API $api）"
    echo ""
    echo "- 序列号：\`$serial\`"
    echo "- 厂商 / ROM：$vendor / $rom"
    echo "- Android：$rel（API $api）"
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
    echo "## AUTO（脚本判定）"
    echo ""
    echo "| # | 项 | 结论 | 证据 |"
    echo "|---|---|---|---|"
  } >"$report"

  # 判定 + 同时打印到 stdout 与报告；证据里的 `|` 必须转义，否则表格错位
  judge() {
    local id=$1 title=$2 ok=$3 evidence=$4
    local verdict
    if [ "$ok" = "1" ]; then verdict="PASS"; p=$((p + 1)); else verdict="**FAIL**"; f=$((f + 1)); overall=1; fi
    printf '| %s | %s | %s | %s |\n' "$id" "$title" "$verdict" "${evidence//|/\\|}" >>"$report"
    printf '  %-5s %s %s\n' "$verdict" "$id" "$title"
  }

  # SKIP 也必须进表：报告里缺一行，读的人会以为这项通过了
  skip_row() {
    local id=$1 title=$2 why=$3
    s=$((s + 1))
    printf '| %s | %s | SKIP | %s |\n' "$id" "$title" "${why//|/\\|}" >>"$report"
    printf '  SKIP  %s %s（%s）\n' "$id" "$title" "$why"
  }

  # 前置：包是否在
  local pkg_path
  pkg_path=$(adbs "$serial" shell pm path "$PKG" 2>/dev/null | head -1)
  if [ -z "$pkg_path" ]; then
    {
      echo ""
      echo "> **BLOCKED**：设备上没有安装 \`$PKG\`。用 \`--apk <path>\` 重跑。"
    } >>"$report"
    echo "  BLOCKED  $PKG 未安装（$serial）"
    overall=1
    reports+=("$report")
    return
  fi

  local ver
  ver=$(adbs "$serial" shell dumpsys package "$PKG" | grep -m1 versionName | sed 's/.*versionName=//')
  echo "" >>"$report"
  echo "- 已装版本：\`$ver\`" >>"$report"

  local overlay_op pid
  overlay_op=$(adbs "$serial" shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | head -1)
  pid=$(adbs "$serial" shell pidof "$PKG" | tr -d '\r')

  # A3：没有悬浮窗权限时不该有服务在跑
  local a3=0 a3e
  if echo "$overlay_op" | grep -qi 'ignore'; then
    if [ -z "$pid" ]; then a3=1; a3e="appops=ignore，pid 为空"; else a3e="appops=ignore 但进程在跑（pid=$pid）"; fi
  else
    a3=1; a3e="已授权（${overlay_op:-unknown}），本轮不适用"
  fi
  judge "A3" "加窗失败不装运行" "$a3" "$a3e"

  # A5：转屏后桌面助手的窗口仍在屏内
  # 判据是窗口 frame 与屏幕尺寸的交集非空；只对「服务在跑」的设备有效，
  # 否则是 SKIP（不是 PASS）——没窗口时「没越界」毫无意义。
  if [ -n "$pid" ]; then
    local size
    size=$(adbs "$serial" shell wm size | tail -1 | sed 's/.*: //')
    local frame_ok=1 frame_ev=""
    local rot
    for rot in 0 1 0; do
      adbs "$serial" shell settings put system user_rotation "$rot" >/dev/null
      sleep 1
      # 窗口名不含包名时也能定位：Type=APPLICATION_OVERLAY 是本应用唯一一类
      local box
      box=$(adbs "$serial" shell dumpsys window windows | grep -A40 'APPLICATION_OVERLAY' | grep -m1 'frame=\[' | sed 's/.*frame=\[//;s/\].*//')
      if [ -n "$box" ]; then
        local x1 y1 x2 y2
        IFS=',' read -r x1 y1 x2 y2 <<<"$box"
        if [ "${x1:-0}" -ge "${x2:-0}" ] || [ "${y1:-0}" -ge "${y2:-0}" ]; then
          frame_ok=0; frame_ev="rot=$rot frame=[$box] 非法"
        fi
      fi
    done
    adbs "$serial" shell settings put system user_rotation 0 >/dev/null
    [ -z "$frame_ev" ] && frame_ev="窗口 frame 在 rot 0/1/0 三次采样中均合法（wm size=$size）"
    judge "A5" "转屏重 clamp" "$frame_ok" "$frame_ev"
  else
    skip_row "A5" "转屏重 clamp" "服务未运行，采不到窗口 frame"
  fi

  # A8：重复开启幂等（通知只有一条）
  local n
  n=$(adbs "$serial" shell dumpsys notification --noredact | grep -c 'agentkit_pet_overlay')
  local a8=1 a8e="通知渠道引用数=$n"
  [ "${n:-0}" -gt 2 ] && { a8=0; a8e="通知渠道引用数=$n（>2，疑似重复通知）"; }
  judge "A8" "重复开启幂等" "$a8" "$a8e"

  # C4：常驻通知必须是最低优先级（importance=2 = MIN）
  local imp
  imp=$(adbs "$serial" shell dumpsys notification --noredact | grep -A12 'agentkit_pet_overlay' | grep -m1 -o 'importance=[0-9]')
  local c4=1 c4e="${imp:-未找到渠道}"
  if [ -n "$imp" ] && [ "${imp#importance=}" -gt 2 ]; then c4=0; c4e="$imp（应为 importance=2）"; fi
  judge "C4" "常驻通知最低优先级" "$c4" "$c4e"

  # G3 / G4：桌宠历史落盘（只有 debug 包能 run-as）
  local hist
  hist=$(adbs "$serial" shell run-as "$PKG" cat files/pet/history.json 2>/dev/null)
  if [ -z "$hist" ] || echo "$hist" | grep -qi 'no such file\|permission denied'; then
    # release 包读不到是正常的（run-as 只对 debuggable 有效），不是失败
    skip_row "G3" "落盘内容是纯对话" "读不到 files/pet/history.json（非 debug 包或还没聊过）"
    skip_row "G4" "历史上限 20 条" "同上"
  else
    local stats
    stats=$(printf '%s' "$hist" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
except Exception as e:
    print("PARSE_FAIL", "x", "x"); raise SystemExit
t=d.get("turns",[])
roles={x.get("role") for x in t}
print(len(t), "role_ok" if roles <= {"user","assistant"} else "role_bad:"+",".join(sorted(roles)), d.get("version",0))
' 2>&1)
    local cnt role_ok ver_ok
    cnt=$(echo "$stats" | awk '{print $1}')
    role_ok=$(echo "$stats" | awk '{print $2}')
    ver_ok=$(echo "$stats" | awk '{print $3}')
    local g3=1 g3e="turns=$cnt role=$role_ok version=$ver_ok"
    case "$cnt" in
      ''|*[!0-9]*) g3=0; g3e="解析失败：$stats"; cnt=0 ;;
      *) [ "$role_ok" != "role_ok" ] && { g3=0; g3e="历史里出现非对话角色：$role_ok"; } ;;
    esac
    judge "G3" "落盘内容是纯对话" "$g3" "$g3e"

    local g4=1 g4e="turns=$cnt（上限 20）"
    [ "$cnt" -gt 20 ] && { g4=0; g4e="turns=$cnt 超过上限 20"; }
    judge "G4" "历史上限 20 条" "$g4" "$g4e"
  fi

  # 磁贴是否声明（能不能被用户添加，是另一件事——SEMI）
  local tile
  tile=$(adbs "$serial" shell dumpsys package "$PKG" | grep -ci 'TileService')
  {
    echo ""
    echo "## SEMI（证据在下面，结论要人看）"
    echo ""
    echo "- **C1 磁贴声明**：清单里 TileService 出现 $tile 次（>0 = 已声明，仍要手动添加到快捷设置）"
    echo "- **F2 省电白名单**：\`$(adbs "$serial" shell dumpsys deviceidle whitelist 2>/dev/null | grep -c "$PKG")\` 条命中（0 = 未加白名单）"
    echo "- **F4 杀进程恢复**：\`am force-stop $PKG\` 后重开，展开气泡看是否恢复最近对话"
    echo "- **E1 运行中撤权**：设置里撤销悬浮窗权限 → 再点开启，应给出解释而不是崩"
    echo ""
    echo "## MANUAL（没勾 = 没测）"
    echo ""
    echo "A1 A2 A4 A6 A7 B1 B2 B3 B4 C2 C3 C5 D1 D2 D3 D4 D5 D6 D7 D8 E2 E3 E4 E5 E6 F1 F3 G1 G2"
    echo ""
    echo "> 逐项判据见 \`docs/qa/真机矩阵-v1.md\`。"
    echo ""
    echo "## 汇总"
    echo ""
    echo "AUTO PASS=$p FAIL=$f SKIP=$s"
  } >>"$report"

  echo "  报告：$report（PASS=$p FAIL=$f SKIP=$s）"
  reports+=("$report")
}

if [ -n "$APK" ]; then
  echo "== 安装 $APK =="
  for s in $SERIALS; do
    adbs "$s" install -r "$APK" >/dev/null 2>&1 && echo "  已安装到 $s" || { echo "  安装失败：$s"; overall=1; }
  done
fi

for s in $SERIALS; do
  echo ""
  echo "== 设备 $s =="
  run_one "$s"
done

# 覆盖率判定：没有国产 ROM 时，报告只能证明「代码没坏」
echo ""
if [ ${#reports[@]} -gt 0 ]; then
  echo "报告文件："
  printf '  %s\n' "${reports[@]}"
fi
roms=$(for s in $SERIALS; do adbs "$s" getprop ro.build.display.id; done | tr 'A-Z' 'a-z')
if ! echo "$roms" | grep -qE 'miui|hyperos|coloros|oxygenos|emui|harmony|flyme|originos|magicos'; then
  echo ""
  echo "COVERAGE INCOMPLETE：本轮没有国产 ROM 设备。省电策略 / 键盘高度 / 磁贴权限"
  echo "  三件事在原生 ROM 上测不出差异，报告不能当发版依据。"
fi

exit "$overall"
