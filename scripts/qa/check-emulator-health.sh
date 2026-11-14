#!/usr/bin/env bash
# 开模拟器之前先跑这个：三项硬指标，任何一项不达标就别浪费 40 分钟。
#
# 背景（2026-11-14 第四轮）：没有 KVM 的 TCG 环境能装能启动 Android 14，
# 但 system_server 每 2–4 分钟被 watchdog 杀掉一次，装包要 200 秒、
# 交互实验根本做不了。第三次能验完 5 条是运气好赶在两次 watchdog 之间。
#
# 用法：bash scripts/qa/check-emulator-health.sh [serial]
#   不带参数时只查宿主，带 serial（如 emulator-5554）时连设备一起查。
#
# 退出码：0 = 可以跑；1 = 不建议跑（有硬伤）；2 = 能跑但会慢（软警告）。

set -uo pipefail

SERIAL="${1:-}"
FAIL=0
WARN=0

say()  { printf '%s\n' "$*"; }
bad()  { printf '  [FAIL] %s\n' "$*"; FAIL=$((FAIL + 1)); }
warn() { printf '  [WARN] %s\n' "$*"; WARN=$((WARN + 1)); }
ok()   { printf '  [OK]   %s\n' "$*"; }

say "== 宿主 =="

if [ -c /dev/kvm ]; then
  ok "/dev/kvm 存在，可以硬件加速"
else
  warn "没有 /dev/kvm → 只能 -accel off（TCG）"
  warn "  Android 14 的 system_server 在 TCG 下大概率被 watchdog 杀，建议改用 API 30 或真机"
fi

if [ -r /proc/cpuinfo ]; then
  ok "CPU 核数 $(nproc)"
fi

MEM_GB=$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo 2>/dev/null || echo 0)
if [ "${MEM_GB:-0}" -ge 8 ]; then
  ok "内存 ${MEM_GB} GB"
else
  warn "内存只有 ${MEM_GB} GB，模拟器 + 构建会吃紧"
fi

AVAIL_GB=$(df -BG --output=avail / 2>/dev/null | tail -1 | tr -dc '0-9')
if [ -n "${AVAIL_GB:-}" ] && [ "$AVAIL_GB" -ge 20 ]; then
  ok "根分区可用 ${AVAIL_GB} GB"
else
  warn "根分区可用 ${AVAIL_GB:-?} GB（SDK + 镜像约 2 GB，装完还会涨）"
fi

if [ -r /proc/pressure/cpu ]; then
  SOME=$(awk '/^some/ {for (i = 1; i <= NF; i++) if ($i ~ /^avg10=/) {sub("avg10=", "", $i); print $i}}' /proc/pressure/cpu)
  say "  CPU pressure some avg10=${SOME:-?}%"
  if [ -n "${SOME:-}" ] && [ "$(printf '%.0f' "$SOME")" -gt 40 ]; then
    warn "CPU 压力已经 >40%，模拟器起来后 watchdog 迟早会来"
  fi
fi

if [ -n "$SERIAL" ]; then
  say ""
  say "== 设备 $SERIAL =="
  ADB=${ADB:-adb}
  if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    bad "adb 连不上 $SERIAL"
  else
    BOOT=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [ "$BOOT" = "1" ]; then ok "boot_completed=1"; else warn "boot 未完成（sys.boot_completed='$BOOT'）"; fi

    # watchdog：system_server 被杀的次数，这是最直接的"这机器不能做交互实验"证据
    N=$("$ADB" -s "$SERIAL" shell 'ls /data/system/dropbox 2>/dev/null | grep -c watchdog' 2>/dev/null | tr -d '\r')
    if [ "${N:-0}" -gt 0 ]; then
      bad "dropbox 里有 $N 条 system_server watchdog —— 系统在反复重启，交互实验做不了"
      say "      看现场：adb shell 'cat /data/system/dropbox/system_server_watchdog@*.txt.gz | toybox gzip -d | head -30'"
    else
      ok "没有 watchdog 记录"
    fi

    PRESS=$("$ADB" -s "$SERIAL" shell 'cat /proc/pressure/cpu' 2>/dev/null | awk '/^some/ {print $2}' | tr -d '\r')
    [ -n "${PRESS:-}" ] && say "  设备内 CPU pressure ${PRESS}"
  fi
fi

say ""
if [ "$FAIL" -gt 0 ]; then
  say "结论：不建议在这台机器上跑交互验证（$FAIL 项硬伤，$WARN 项警告）。"
  exit 1
elif [ "$WARN" -gt 0 ]; then
  say "结论：能跑，但会慢（$WARN 项警告）。验之前先想清楚哪几条真的需要设备。"
  exit 2
else
  say "结论：环境健康，可以跑。"
  exit 0
fi
