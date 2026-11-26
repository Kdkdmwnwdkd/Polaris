#!/usr/bin/env bash
# 静音幻觉判据（2026-11-21）。
#
# 由来（用户真机反馈）：按住麦克风**一个字没说**，识别出了 3 个字；连按四次得到
# 3 / 3 / 4 / 0 字。日志里这四次全是「离线识别完成：N 字」，没有任何错误——
# 也就是说，用户不开口，也会凭空得到一句话。
#
# 根因是两件事叠在一起：
#   1. 整段识别收尾时把峰值拉到 0.9（救轻声说话）。静音峰值只有 0.003 上下，
#      这一拉等于把底噪放大 300 倍，送到模型面前就是「一段很响的音频」；
#   2. paraformer 对任何非静音输入都会给出一串最可能的汉字，不会说「没人说话」。
#
# 所以判定必须发生在**放大之前**，这就是本判据 [A] 的核心：
# 不是「有没有判」，而是「判在放大前还是后」。顺序反了，判定看到的是被自己
# 放大的噪声，阈值再准也没用。
#
#   [A] 离线路径：判静音 → 早退，且语句在 normalize 之前；
#   [B] 判定走 SpeechPresence，不许在引擎里另写一套阈值（两套阈值必然漂移）；
#   [C] 流式路径同样要拦——它不缓存 PCM，所以用逐帧 RMS 入口；
#   [D] 单测必须覆盖五个现场：全零、底噪、瞬态、轻声、连续说话；
#   [E] 反向自检：把 [A] 的判定删掉必须变红（否则这条判据只是装饰）。
#
# 退出码：0 通过 / 1 有违规。
set -uo pipefail
cd "$(dirname "$0")/../.."

OFFLINE=app/src/main/java/com/beichen/polaris/audio/asr/OfflineAsrEngine.kt
STREAM=app/src/main/java/com/beichen/polaris/audio/asr/StreamingAsr.kt
PRESENCE=core/src/main/java/com/beichen/polaris/core/voice/SpeechPresence.kt
TEST=core/src/test/java/com/beichen/polaris/core/voice/SpeechPresenceTest.kt

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }
say()  { echo "$*"; }

# 剔掉整行注释：解释「为什么不能先 normalize」的注释里就写着 normalize，
# 不剔的话 [A] 的行号比较会拿注释去比，结论随机。
code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

# `code x | grep -q p` 在 `set -o pipefail` 下会假红：grep -q 命中即退出，上游 grep -v
# 还在写就收到 SIGPIPE（141），整条管道判假。文件越大、匹配越靠前越容易碰上，
# 表现为「同一份代码单跑全绿、全量跑偶发红」（2026-11-26 approval-overlay 就是这么红的）。
# 先落地成变量再匹配，管道就不存在了。
has() {
  local text
  text=$(code "$1") || true
  grep -q -- "$2" <<<"$text"
}

hasE() {
  local text
  text=$(code "$1") || true
  grep -qE -- "$2" <<<"$text"
}

# ── [A] 的检查抽成函数，[E] 反向自检直接喂坏样本 ─────────────────────────────
silence_guard_ok() {
  local f=$1
  # 必须真的调用判定
  has "$f" 'SpeechPresence.voicedMillis' || return 1
  # 必须拿判定结果跟阈值比较后早退
  has "$f" 'MIN_SPEECH_MS' || return 1
  # 判定的行号必须早于 normalize 调用（这就是这一整轮的教训）
  local judge norm
  judge=$(grep -n 'SpeechPresence.voicedMillis' "$f" | grep -vE ':[[:space:]]*(\*|//)' | head -1 | cut -d: -f1)
  norm=$(grep -n 'normalize(pcm)' "$f" | grep -vE ':[[:space:]]*(\*|//)' | head -1 | cut -d: -f1)
  [ -n "$judge" ] || return 1
  [ -n "$norm" ] || return 1
  [ "$judge" -lt "$norm" ] || return 1
  return 0
}

say "=== 静音幻觉判据 ==="
say ""

say "[A] 离线整段识别：判静音必须在放大之前"
if [ ! -f "$OFFLINE" ]; then
  bad "找不到 $OFFLINE"
elif silence_guard_ok "$OFFLINE"; then
  good "判定早于 normalize，静音段不会进模型"
else
  bad "离线路径缺静音判定，或判定写在了 normalize 之后（底噪会被放大成「人声」）"
fi

say ""
say "[B] 阈值只许有一份"
if has "$PRESENCE" 'MIN_SPEECH_MS' && has "$PRESENCE" 'FLOOR_RMS'; then
  good "阈值集中在 SpeechPresence"
else
  bad "SpeechPresence 缺少阈值常量"
fi
if hasE "$OFFLINE" '(0\.006f|MIN_SPEECH_MS[[:space:]]*=[[:space:]]*200)'; then
  bad "引擎里又写了一份阈值（两套阈值必然漂移）"
else
  good "引擎没有内联阈值"
fi

say ""
say "[C] 流式识别同样要拦"
if has "$STREAM" 'SpeechPresence.hasSpeechInFrames'; then
  good "流式收尾走帧级判定"
else
  bad "流式模型对底噪也会吐字，finish 里必须有判定"
fi
if has "$STREAM" 'hasSpeech('; then
  bad "流式路径误用了 PCM 入口（第二参数是采样率，会把它当帧长）"
else
  good "入口没串用"
fi

say ""
say "[D] 单测覆盖五个现场"
if [ ! -f "$TEST" ]; then
  bad "找不到 $TEST"
else
  for case in "全零" "底噪" "瞬态" "轻声" "整段都在说话"; do
    if grep -q "$case" "$TEST"; then
      good "用例：$case"
    else
      bad "缺用例：$case"
    fi
  done
fi

say ""
say "[E] 反向自检：删掉判定必须变红"
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
if [ -f "$OFFLINE" ]; then
  grep -v 'SpeechPresence.voicedMillis' "$OFFLINE" \
    | grep -v 'MIN_SPEECH_MS' > "$tmp"
  if silence_guard_ok "$tmp"; then
    bad "把判定删干净了判据还绿——这条判据没有牙齿"
  else
    good "坏样本被捕获"
  fi
  # 顺序反了也必须红：把判定挪到 normalize 之后
  if has "$OFFLINE" 'normalize(pcm)'; then
    awk '{
      if ($0 ~ /SpeechPresence\.voicedMillis/) { held = held $0 "\n"; next }
      if ($0 ~ /normalize\(pcm\)/) { print; printf "%s", held; held = ""; next }
      print
    }' "$OFFLINE" > "$tmp"
    if silence_guard_ok "$tmp"; then
      bad "判定在放大之后，判据仍是绿的——顺序没被检查"
    else
      good "顺序颠倒被捕获"
    fi
  fi
fi

say ""
if [ "$fail" -eq 0 ]; then
  say "静音判定：$npass 通过 / 0 失败"
  exit 0
else
  say "静音判定：$npass 通过 / $fail 失败"
  exit 1
fi
