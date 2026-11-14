#!/usr/bin/env bash
# 语音识别能力判定（SpeechCapabilityPolicy）验证。
#
# 分两段：
#   [A] 结构与接线：判定逻辑必须住在 core（能被 harness 独立编译）、
#       两条语音入口必须都调它、清单必须声明识别服务可见性。
#   [B] 行为：真编真跑判定表，逐条钉死「什么情况给什么结论」。
#
# 为什么这类逻辑必须有 harness：它错了**不报错**，只是静默失败——
# 桌宠表现为「按了没反应」，聊天页表现为「弹了个系统页然后什么都没发生」。
# CI 里没有真机也没有真引擎，行为测试永远抓不到它。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

CORE=core/src/main/java/com/beichen/polaris/core
POLICY="$CORE/voice/SpeechCapabilityPolicy.kt"
PROBE=$(find app/src/main -name "SpeechProbe.kt" 2>/dev/null | head -1)
PET_VOICE=$(find app/src/main -name "PetVoice.kt" 2>/dev/null | head -1)
CHAT_SCREEN=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt
MANIFEST=app/src/main/AndroidManifest.xml

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 结构：分层、接线与清单"

# 判定表必须留在 core。一旦它 import android，harness 就编不了它，
# 于是这张表退化成「只能靠真机试」——而它恰恰是那种错了不报错的逻辑。
if [ -n "$POLICY" ] && ! grep -qE '^import android' "$POLICY" 2>/dev/null; then
  pass "A1 判定表在 core 且零 Android 依赖（可独立编译）"
else
  fail "A1 判定表缺失或引入了 android.* —— 判定逻辑将无法被 harness 验证"
fi

# 反向依赖：core 不能知道 app 层的 SpeechProbe 存在。
if [ -n "$POLICY" ] && ! grep -qE '^import com\.beichen\.polaris\.(audio|ui|tools)' "$POLICY" 2>/dev/null; then
  pass "A2 判定表不依赖 app 层"
else
  fail "A2 判定表反向依赖了 app 层 —— 分层倒置"
fi

# 两条入口必须都调判定表。只改一边的话，用户会觉得「桌宠坏了但聊天页能用」，
# 或者反过来——而实际上两边的结论必须一致。
if [ -n "$PET_VOICE" ] && grep -qF "probe.check(" "$PET_VOICE"; then
  pass "A3 桌宠语音入口调用了能力判定"
else
  fail "A3 桌宠语音入口没有调用能力判定 —— 它还在自己判断，结论可能与聊天页不一致"
fi

if [ -n "$CHAT_SCREEN" ] && grep -qF "speechProbe.check(" "$CHAT_SCREEN"; then
  pass "A4 聊天页语音入口调用了能力判定"
else
  fail "A4 聊天页语音入口没有调用能力判定 —— 两条路结论会不一致"
fi

# 两条路必须共用同一个探测实现。
if [ -n "$PET_VOICE" ] && [ -n "$CHAT_SCREEN" ] &&
   grep -qF "SpeechProbe(" "$PET_VOICE" && grep -qF "SpeechProbe(" "$CHAT_SCREEN"; then
  pass "A5 两条路共用同一个探测实现（SpeechProbe）"
else
  fail "A5 两条路没有共用探测实现 —— 结论可能分叉"
fi

# package visibility：targetSdk 升到 30+ 的那天，没有 <queries> 就会静默失效。
if [ -n "$MANIFEST" ] && grep -qF "android.speech.RecognitionService" "$MANIFEST"; then
  pass "A6 清单声明了识别服务可见性（<queries>）"
else
  fail "A6 清单没有声明识别服务可见性 —— targetSdk 一升到 30+，语音会毫无征兆地失效"
fi

# 语言标签必须是 BCP-47。Locale.toString() 给的是 zh_CN（下划线），
# 不是 BCP-47，严格按规范解析的引擎读不出语言。
if [ -n "$CHAT_SCREEN" ] && ! grep -qF "Locale.getDefault().toString()" "$CHAT_SCREEN"; then
  pass "A7 聊天页用 BCP-47 语言标签（toLanguageTag，不是 toString）"
else
  fail "A7 聊天页仍用 Locale.toString()（zh_CN）—— 不是 BCP-47，部分引擎读不出语言"
fi

if [ -n "$PET_VOICE" ] && grep -qF "toLanguageTag()" "$PET_VOICE"; then
  pass "A8 桌宠用 BCP-47 语言标签"
else
  fail "A8 桌宠没有用 BCP-47 语言标签"
fi

# A9 聊天页**不得**用我们的麦克风权限去拦语音输入。
#
# 聊天页走的是系统识别页（ACTION_RECOGNIZE_SPEECH），录音发生在**系统进程**里，
# 我们的 RECORD_AUDIO 跟它无关（依据见 core/voice/SpeechCapabilityPolicy 的 VoicePath 注释：
# AOSP 的 attribution 链 + 框架 SearchView 的旁证）。
#
# 曾经这里加过一个「缺权限就当场申请」的入口，那是误诊。留着它的代价是：
# 只用聊天页语音、没在设置里开过桌宠的用户，点麦克风会被拦下——
# 而那条路本来是通的。**拦住一条本来能用的路，比不拦更糟**：
# 用户看到的还是「语音没反应」，而且这次是我们自己挡的。
# 查的是**代码引用**（`permission.RECORD_AUDIO` / `micGranted` / `micPermission`），
# 不是文字出现——注释里说明「我们不需要这个权限」是应该写的，
# 把注释也算进去就成了「不许提这个词」，那不是这条断言想管的事。
if [ -n "$CHAT_SCREEN" ] && ! grep -qF "RequestPermission()" "$CHAT_SCREEN" &&
   ! grep -qE "permission\.RECORD_AUDIO|micGranted|micPermission" "$CHAT_SCREEN"; then
  pass "A9 聊天页不拿我们的麦克风权限拦语音输入（系统页录音，权限归系统）"
else
  fail "A9 聊天页用麦克风权限拦语音输入 —— 会挡住一条本来能用的路"
fi

# 错误码翻译必须只有一份。两处各写一套的话，同一个错误码在两个入口显示不同的话。
if [ -n "$PET_VOICE" ] && grep -qF "SpeechCapabilityPolicy.explainError(" "$PET_VOICE"; then
  pass "A10 桌宠的错误码翻译走统一表格"
else
  fail "A10 桌宠没有走统一的错误码翻译 —— 同一个错误码两个入口说法会不一样"
fi

# 判定不能只看「有没有服务」就下结论：系统没选默认引擎时同样 unusable。
if [ -n "$POLICY" ] && grep -qF "hasDefaultEngine" "$POLICY"; then
  pass "A11 判定表包含「系统是否选定默认引擎」这一维"
else
  fail "A11 判定表没查默认引擎 —— 系统没选引擎时会被误判成可用"
fi

# A12 常量脱钩检测（**本段最要紧的一条**）。
#
# core 里复制了一份 AOSP 的错误码常量（为了保持零 Android 依赖、能让 harness
# 编译）。复制的代价是：将来 AOSP 改号，这张表就悄悄错位——表现是
# 「网络超时」被翻译成「没听清」，而且**永远不会有人发现**，直到用户投诉。
#
# 所以这里拿 AOSP 的真实常量逐个比对。改号 → 立刻红。
#
# 怎么拿到 AOSP 的真值：用 android.jar 的常量值（javap 读常量池）。
# 找不到 android.jar 时跳过并提示——CI 一定有，开发者本机不一定。
if [ -n "$POLICY" ] && grep -q "object ErrorCode" "$POLICY"; then
  pass "A12 判定表用具名常量承载错误码（便于与 AOSP 对齐）"
else
  fail "A12 判定表没有具名错误码常量 —— 无法与 AOSP 对齐，改号时没人会发现"
fi

ANDROID_JAR=""
for j in "${ANDROID_HOME:-/opt/android-sdk}/platforms/"*/android.jar \
         "${ANDROID_SDK_ROOT:-/opt/android-sdk}/platforms/"*/android.jar; do
  [ -f "$j" ] && ANDROID_JAR="$j" && break
done
if [ -n "$ANDROID_JAR" ] && command -v javap >/dev/null 2>&1; then
  # 从 core 的常量表里抽出「名字 = 值」。
  #
  # 落到临时文件而不是关联数组：`while read` 配进程替换在某些 shell 下会在
  # 子 shell 里赋值，循环结束后数组仍是空的——表现为「core 侧全是空值」，
  # 像是常量全错了，其实是没读到。查文件最直白。
  CORE_CODES_FILE=$(mktemp)
  sed -n '/object ErrorCode/,/^    }/p' "$POLICY" |
    grep -oE 'const val [A-Z_]+ = [0-9]+' |
    sed -E 's/const val ([A-Z_]+) = ([0-9]+)/\1 \2/' > "$CORE_CODES_FILE"

  # AOSP 常量名 → core 里的名字
  declare -A AOSP_MAP=(
    [ERROR_NETWORK_TIMEOUT]=NETWORK_TIMEOUT
    [ERROR_NETWORK]=NETWORK
    [ERROR_AUDIO]=AUDIO
    [ERROR_SERVER]=SERVER
    [ERROR_CLIENT]=CLIENT
    [ERROR_SPEECH_TIMEOUT]=SPEECH_TIMEOUT
    [ERROR_NO_MATCH]=NO_MATCH
    [ERROR_RECOGNIZER_BUSY]=RECOGNIZER_BUSY
    [ERROR_INSUFFICIENT_PERMISSIONS]=INSUFFICIENT_PERMISSIONS
    [ERROR_SERVER_DISCONNECTED]=SERVER_DISCONNECTED
    [ERROR_LANGUAGE_NOT_SUPPORTED]=LANGUAGE_NOT_SUPPORTED
    [ERROR_LANGUAGE_UNAVAILABLE]=LANGUAGE_UNAVAILABLE
  )

  mismatch=""
  checked=0
  for aosp in "${!AOSP_MAP[@]}"; do
    core_name="${AOSP_MAP[$aosp]}"
    real=$(javap -constants -cp "$ANDROID_JAR" android.speech.SpeechRecognizer 2>/dev/null |
           grep -E "static final int $aosp =" |
           grep -oE '= [0-9]+' | tr -dc '0-9')
    mine=$(awk -v n="$core_name" '$1 == n { print $2 }' "$CORE_CODES_FILE")
    if [ -z "$real" ]; then
      continue
    fi
    checked=$((checked + 1))
    if [ "$real" != "$mine" ]; then
      mismatch="$mismatch $aosp(AOSP=$real,core=$mine)"
    fi
  done
  rm -f "$CORE_CODES_FILE"
  if [ "$checked" -eq 0 ]; then
    fail "A13 无法从 android.jar 读出任何错误码常量 —— 对齐检测没有真的跑"
  elif [ -n "$mismatch" ]; then
    fail "A13 错误码常量与 AOSP 不一致：$mismatch —— 翻译表已错位"
  else
    pass "A13 错误码常量与 AOSP 逐个对齐（核对 $checked 个）"
  fi
else
  echo "  WARN  A13 跳过：找不到 android.jar 或 javap，本机无法做脱钩检测"
fi

# A14 聊天页的「判定 → 动作」映射必须与判定表同源。
# 两处各写一套 when 分支的话，改了一处忘了另一处，两个入口行为就不一致了。
LOGIC=$(find app/src/main -name "VoiceInputLogic.kt" 2>/dev/null | head -1)
# 它消费的是判定表产出的 [SpeechCapability]——
# 查「是否引用了判定表的类型」，而不是查某个具体类名：
# 实现细节（叫 Policy 还是别的）不该被断言钉住，被钉住的应该是「同源」这件事。
if [ -n "$LOGIC" ] && grep -qE 'core\.voice\.SpeechCapability\b' "$LOGIC"; then
  pass "A14 聊天页的动作映射与判定表同源（不是各写一套 when）"
else
  fail "A14 聊天页没有复用判定表的结论 —— 两个入口行为会分叉"
fi

# A15 两条入口都必须把**路径**喂进判定。
#
# 「谁来录音」决定哪几维前置条件算数：桌宠自己录音，需要我们的麦克风权限；
# 聊天页走系统识别页，录音在系统进程里，我们的权限与它无关。
# 漏传路径（或者传错）就会拿权限去拦一条本来能用的路。
if [ -n "$CHAT_SCREEN" ] && grep -qF "VoicePath.SystemPage" "$CHAT_SCREEN"; then
  pass "A15 聊天页按「系统页录音」路径判定（不用我们的麦克风权限）"
else
  fail "A15 聊天页没有声明路径 —— 会把两条路的要求混在一起"
fi

if [ -n "$PET_VOICE" ] && grep -qF "VoicePath.SelfRecorded" "$PET_VOICE"; then
  pass "A15b 桌宠按「自己录音」路径判定（麦克风权限是硬前置）"
else
  fail "A15b 桌宠没有声明路径 —— 麦克风权限的判定会错位"
fi

# A16 路径这一维必须住在 core 的判定表里，而不是散在调用点。
# 散在调用点的后果是：改一条路径的要求要改两个文件，而漏改的那个不会报错——
# 只是那条路的行为悄悄变了。
if [ -n "$POLICY" ] && grep -qE 'enum class VoicePath' "$POLICY" &&
   grep -qF "VoicePath.SystemPage" "$POLICY" && grep -qF "VoicePath.SelfRecorded" "$POLICY"; then
  pass "A16 路径维度住在 core 判定表里（不是散在调用点）"
else
  fail "A16 路径维度没住在 core —— 改要求要改两个文件，漏改的那个不会报错"
fi

echo ""
echo "[B] 行为：真编真跑判定表"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANN:-}" ] || JB_ANN="$KC_HOME/lib/annotations.jar"

if [ ! -f "$KOTLINC" ] || [ ! -f "$STDLIB" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin 编译器构件" >&2
  exit 3
fi

OUT=/tmp/qa-speech-capability
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$STDLIB" -d "$OUT" -nowarn \
  "$POLICY" \
  scripts/qa/SpeechCapabilityVerification.kt \
  2>&1 | grep -v '^warning:' | head -25

if [ ! -d "$OUT/com" ]; then
  fail "B 编译失败（上面是输出）—— 行为断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$STDLIB" com.beichen.polaris.qa.SpeechCapabilityVerification)
  rc=$?
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  # 汇总行形如「=== 合计: 29 条，通过 29 / 失败 0 ===」。
  # 两个数字分别跟在「通过」与「失败」后面，**不是**用 / 分隔的两个数——
  # 按「数字 / 数字」去匹配会悄悄失配，表现为「断言全过却报没有结果行」。
  line=$(echo "$BODY" | grep -oE '通过 [0-9]+ / 失败 [0-9]+' | tail -1)
  if [ -z "$line" ]; then
    fail "B 段没有结果行（harness 中途退出，rc=$rc）—— 未跑完不算通过"
  else
    bp=$(echo "$line" | grep -oE '通过 [0-9]+' | grep -oE '[0-9]+'); bp=${bp:-0}
    bf=$(echo "$line" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+'); bf=${bf:-0}
    p=$((p + bp)); f=$((f + bf))
    [ "$bf" -eq 0 ] || echo "        B 段有 $bf 条行为断言失败"
  fi
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] || exit 1
