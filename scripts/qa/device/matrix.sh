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
# 退出码：0 = 所有 AUTO 项通过；1 = 有 AUTO 项 FAIL；
#         2 = 没有可用设备，或设备在但一项 AUTO 都没判（COVERAGE INCOMPLETE，不是通过）
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

# 用 sed 而不是 awk 解析：矩阵脚本可能在「什么工具都缺」的机器上跑（2026-11-17
# 就在一台没有 awk 的沙箱里试过，表现为「永远没有设备」——最糟的那种静默失败）。
SERIALS=$("$ADB" devices 2>/dev/null | tr -d '\r' | sed -n '2,$s/[[:space:]][[:space:]]*device$//p')
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
  # `shell` 不能省：`adb -s X getprop` 是「未知子命令」，adb 会把错误文本
  # （`unknown command getprop`）当正常输出吐出来，于是一路写进报告标题。
  # 2026-11-17 首次在模拟器上跑，整份报告的「厂商 / ROM / Android」全是这句报错。
  model=$(adbs "$serial" shell getprop ro.product.model | head -1)
  api=$(adbs "$serial" shell getprop ro.build.version.sdk | head -1)
  rel=$(adbs "$serial" shell getprop ro.build.version.release | head -1)
  vendor=$(adbs "$serial" shell getprop ro.product.manufacturer | head -1)
  rom=$(adbs "$serial" shell getprop ro.build.display.id | head -1)
  name=$(printf '%s-api%s' "$model" "$api" | tr -c 'A-Za-z0-9._-' '-' | cut -c1-48)
  report="$OUT_DIR/device-matrix-${name}-${STAMP}.md"

  local p=0 f=0 s=0
  # 版本号要在报告头里写死一行；以前是在表头之后追加，结果把 Markdown 表
  # 劈成两半（表头 + 一行列表项 + 数据行），GitHub 上直接渲染成乱码表格。
  local pkg_path ver
  pkg_path=$(adbs "$serial" shell pm path "$PKG" 2>/dev/null | head -1)
  ver=""
  [ -n "$pkg_path" ] && ver=$(adbs "$serial" shell dumpsys package "$PKG" | grep -m1 versionName | sed 's/.*versionName=//')
  {
    echo "# 真机矩阵报告 — $model（API $api）"
    echo ""
    echo "- 序列号：\`$serial\`"
    echo "- 厂商 / ROM：$vendor / $rom"
    echo "- Android：$rel（API $api）"
    echo "- 已装版本：\`${ver:-未安装}\`"
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
  if [ -z "$pkg_path" ]; then
    {
      echo ""
      echo "> **BLOCKED**：设备上没有安装 \`$PKG\`。用 \`--apk <path>\` 重跑。"
      echo "> 本轮 AUTO 一项都没验，汇总不应被读成「通过」。"
      echo ""
      echo "## 汇总"
      echo ""
      echo "AUTO PASS=0 FAIL=0 SKIP=0（BLOCKED）"
    } >>"$report"
    echo "  BLOCKED  $PKG 未安装（$serial）"
    overall=1
    reports+=("$report")
    return
  fi

  local overlay_op pid
  overlay_op=$(adbs "$serial" shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | head -1)
  pid=$(adbs "$serial" shell pidof "$PKG" | tr -d '\r')

  # A3：没有悬浮窗权限时不该有服务在跑
  # 已授权时这条判据的前提（加窗失败）根本不成立——以前这里记 PASS，
  # 于是「应用没启动、权限没给」的整轮空跑也会产出 4 个 PASS。前提不成立就是 SKIP。
  local a3=0 a3e
  if echo "$overlay_op" | grep -qi 'ignore'; then
    if [ -z "$pid" ]; then a3=1; a3e="appops=ignore，pid 为空"; else a3e="appops=ignore 但进程在跑（pid=$pid）"; fi
    judge "A3" "加窗失败不装运行" "$a3" "$a3e"
  else
    skip_row "A3" "加窗失败不装运行" "已授权（${overlay_op:-unknown}），判据不适用（要撤权后才验）"
  fi

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

  # A8：常驻通知只有一条（重复开启幂等）
  #
  # 判据是 NotificationRecord 的行数，**不能数 channel 名**。channel 名在 dumpsys 里
  # 天然出现三次：记录本身、该通知的 effectiveNotificationChannel、全局 channel 表。
  # 2026-11-17 在模拟器上就吃了这个亏——服务一切正常，却被判成「疑似重复通知」FAIL。
  # 真正的重复开启需要 UI 点两次，脚本不做交互，所以这里只采样当前条数，证据里写明。
  local n
  n=$(adbs "$serial" shell dumpsys notification --noredact | grep -cE "NotificationRecord\([^)]*pkg=$PKG " || true)
  if [ "${n:-0}" -eq 0 ]; then
    skip_row "A8" "常驻通知只有一条" "没有本应用的 NotificationRecord（服务没起）"
  else
    local a8=1 a8e="NotificationRecord=$n 条（仅采样，未做二次开启）"
    [ "${n:-0}" -gt 1 ] && { a8=0; a8e="NotificationRecord=$n 条（>1，重复通知）"; }
    judge "A8" "常驻通知只有一条" "$a8" "$a8e"
  fi

  # C4：常驻通知必须是最低优先级（importance=2 = MIN）
  # 同样限定到本应用的 NotificationRecord：dumpsys 里有几十条别的应用的通知，
  # 取全局第一个 importance= 会取到别人家的。
  local rec imp
  rec=$(adbs "$serial" shell dumpsys notification --noredact | grep -E "NotificationRecord\([^)]*pkg=$PKG " | head -1)
  imp=$(printf '%s' "$rec" | grep -o 'importance=[0-9]' | head -1)
  if [ -z "$imp" ]; then
    skip_row "C4" "常驻通知最低优先级" "未找到本应用的通知记录（服务没起，优先级无从判）"
  else
    local c4=1 c4e="$imp"
    if [ "${imp#importance=}" -gt 2 ]; then c4=0; c4e="$imp（应为 importance=2）"; fi
    judge "C4" "常驻通知最低优先级" "$c4" "$c4e"
  fi

  # G3 / G4：桌宠历史落盘（只有 debug 包能 run-as）
  # 保留 stderr：release 包的 run-as 会吐 `run-as: package not debuggable`，
  # 以前把 stderr 丢了、只认 'no such file'，于是 release 包被判成「解析失败」FAIL——
  # 一个只跟打包类型有关的环境差异，被记成产品缺陷。
  local hist
  hist=$(adbs "$serial" shell run-as "$PKG" cat files/pet/history.json 2>&1)
  if [ -z "$hist" ] || echo "$hist" | grep -qi 'not debuggable\|unknown package\|no such file\|permission denied\|run-as:'; then
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
    IFS=' ' read -r cnt role_ok ver_ok <<<"$stats"
    case "${cnt:-}" in
      ''|*[!0-9]*) skip_row "G3" "落盘内容是纯对话" "解析失败：$stats"; skip_row "G4" "历史上限 20 条" "同上" ;;
      0) skip_row "G3" "落盘内容是纯对话" "history.json 是空数组（没聊过，写盘路径未验证）"
         skip_row "G4" "历史上限 20 条" "同上" ;;
      *)
        local g3=1 g3e="turns=$cnt role=$role_ok version=$ver_ok"
        [ "$role_ok" != "role_ok" ] && { g3=0; g3e="历史里出现非对话角色：$role_ok"; }
        judge "G3" "落盘内容是纯对话" "$g3" "$g3e"

        local g4=1 g4e="turns=$cnt（上限 20）"
        [ "$cnt" -gt 20 ] && { g4=0; g4e="turns=$cnt 超过上限 20"; }
        judge "G4" "历史上限 20 条" "$g4" "$g4e" ;;
    esac
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
    if [ "$p" -eq 0 ]; then
      echo ""
      echo "> **INCOMPLETE**：这一轮没有任何 AUTO 项真正判定（全是 SKIP）。"
      echo "> 设备在，但应用没跑到可观测状态。先手动开一次桌面助手"
      echo "> （给悬浮窗权限、点「开启」）再重跑，否则本报告不能当通过。"
    fi
  } >>"$report"

  echo "  报告：$report（PASS=$p FAIL=$f SKIP=$s）"
  # 一项都没判 = 没验证。退回 2 而不是 0：退出码 0 会被下游读成「AUTO 全过」。
  [ "$p" -eq 0 ] && overall=2
  reports+=("$report")
}

if [ -n "$APK" ]; then
  echo "== 安装 $APK =="
  [ -f "$APK" ] || { echo "  APK 不存在：$APK" >&2; exit 2; }
  for s in $SERIALS; do
    # 不能走 adbs：那层 20 秒超时对 install 太短（26 MB 包在模拟器上要一分钟），
    # 被 timeout 掐掉时 adb 已经写了一半个包，而 124 会被判成「安装失败」——
    # 一次成功的安装被记成坏的，后面所有 AUTO 项都建在这个假失败上。
    out=$(timeout 300 "$ADB" -s "$s" install -r "$APK" 2>&1 | tr -d '\r')
    if printf '%s' "$out" | grep -q 'Success'; then
      echo "  已安装到 $s"
    else
      echo "  安装失败：$s —— $(printf '%s' "$out" | grep -v '^$' | tail -2 | tr '\n' ' ')"
      overall=1
    fi
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
roms=$(for s in $SERIALS; do adbs "$s" shell getprop ro.build.display.id; done | tr 'A-Z' 'a-z')
if ! echo "$roms" | grep -qE 'miui|hyperos|coloros|oxygenos|emui|harmony|flyme|originos|magicos'; then
  echo ""
  echo "COVERAGE INCOMPLETE：本轮没有国产 ROM 设备。省电策略 / 键盘高度 / 磁贴权限"
  echo "  三件事在原生 ROM 上测不出差异，报告不能当发版依据。"
fi

exit "$overall"
