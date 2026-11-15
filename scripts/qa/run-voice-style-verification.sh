#!/usr/bin/env bash
# 音色目录与回落链验证。
#
# 分两段：
#   [A] 结构：音色目录必须住在 core（可被 harness 独立编译）；播报必须经 TextSpeaker
#       统一出口（否则「正在说话」会有第二份判定，打断仲裁看不到）；在线那一档
#       不许出现未声明的逆向接口。
#   [B] 行为：真编真跑目录与回落链，逐条钉死「什么情况退到哪一条」。
#
# 为什么值得一个独立脚本：这套逻辑错了**不报错**。链空了是「没声音」，
# 顺序错了是「断网退回电话音」，去重错了是「同一句念两遍」——三种都像「卡了一下」。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core/voice
CATALOG="$CORE/VoiceCatalog.kt"
PLAN="$CORE/VoicePlan.kt"
SPEAKER=app/src/main/java/com/beichen/polaris/audio/TextSpeaker.kt
VOICE_SPEECH=app/src/main/java/com/beichen/polaris/audio/tts/VoiceSpeech.kt
LOCAL_ENGINE=app/src/main/java/com/beichen/polaris/audio/tts/LocalVoiceEngine.kt
SCREEN=app/src/main/java/com/beichen/polaris/ui/VoiceStyleScreen.kt

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 结构：目录在 core、播报只有一个出口"

if [ -f "$CATALOG" ] && ! grep -qE '^import android' "$CATALOG" 2>/dev/null; then
  pass "A1 音色目录在 core 且零 Android 依赖"
else
  fail "A1 目录缺失或引入 android.* —— 回落链将无法被 harness 验证"
fi

if [ -f "$PLAN" ] && ! grep -qE '^import (android|com\.beichen\.polaris\.(audio|ui|tools))' "$PLAN" 2>/dev/null; then
  pass "A2 回落链不依赖 app 层"
else
  fail "A2 回落链反向依赖 app 层 —— 分层倒置"
fi

# 播报出口唯一：合成结果必须由 TextSpeaker 播，否则 speaking 会分裂成两份。
if grep -qF "voice.prepare(" "$SPEAKER" && grep -qF "voice.playAsync(" "$SPEAKER"; then
  pass "A3 播报经 TextSpeaker 统一出口"
else
  fail "A3 TextSpeaker 没有接音色路由 —— 会出现两份「正在说话」判定"
fi

# 试听同样要占租约：单独写一条「只播不登记」的捷径，用户试听时点麦克风会录进机器音。
if grep -qF "suspend fun audition(" "$SPEAKER" && grep -qF "_speaking.value = true" "$SPEAKER"; then
  pass "A4 试听走同一条播报路径（占麦克风、可被打断）"
else
  fail "A4 试听没有登记播报态 —— 试听中录音会录到机器音"
fi

# 回落必须留下原因，否则「为什么听到的不是我选的」永远查不出来。
if grep -qF "lastDemotion" "$VOICE_SPEECH" && grep -qF "lastDemotion" "$SCREEN"; then
  pass "A5 回落原因可被界面读到"
else
  fail "A5 回落原因没有落到界面 —— 用户只会觉得「设置没生效」"
fi

# 车不能开到「用逆向接口白嫖在线音色」上：那是会被 403 挡掉、且失败方式与断网无法区分的路。
if ! grep -rqiE "speech\.platform\.bing\.com|Sec-MS-GEC|edge-tts" app/src core/src 2>/dev/null; then
  pass "A6 代码里没有逆向的在线合成接口"
else
  fail "A6 出现了逆向在线接口 —— 它会在某天全量 403，且与断网无法区分"
fi

# 在线音色当前未接入：界面必须明说，不能点一下什么都不发生。
if grep -qF "需 key" "$SCREEN" && grep -qF "暂未启用" "$SCREEN" || grep -qF "还没接上服务端 key" "$SCREEN"; then
  pass "A7 在线音色在界面上标明未启用"
else
  fail "A7 在线音色没有状态说明 —— 用户会以为功能坏了"
fi

if grep -qF "matcha" "$LOCAL_ENGINE" && grep -qF "MODE_STATIC" app/src/main/java/com/beichen/polaris/audio/tts/PcmPlayer.kt; then
  pass "A8 本地引擎与播放器形状正确（matcha + 整段播放）"
else
  fail "A8 本地引擎缺模型配置或播放器不是整段播放"
fi

echo ""
echo "[B] 行为：真编真跑目录与回落链"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

if [ -z "${KOTLINC:-}" ] || [ -z "${STDLIB:-}" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin 编译器构件" >&2
  exit 3
fi

OUT=/tmp/qa-voice-style
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$STDLIB" -d "$OUT" -nowarn \
  "$CATALOG" \
  "$PLAN" \
  scripts/qa/VoiceStyleVerification.kt \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -d "$OUT/com" ]; then
  fail "B 编译失败（上面是输出）—— 行为断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$STDLIB" com.beichen.polaris.qa.VoiceStyleVerification)
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  line=$(echo "$BODY" | grep -oE '通过 [0-9]+ / 失败 [0-9]+' | tail -1)
  if [ -z "$line" ]; then
    fail "B 段没有结果行（harness 中途退出）—— 未跑完不算通过"
  else
    bp=$(echo "$line" | grep -oE '通过 [0-9]+' | grep -oE '[0-9]+'); bp=${bp:-0}
    bf=$(echo "$line" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+'); bf=${bf:-0}
    p=$((p + bp)); f=$((f + bf))
  fi
fi

echo ""
echo "音色与回落链：$p 通过 / $f 失败"
[ "$f" -eq 0 ]
