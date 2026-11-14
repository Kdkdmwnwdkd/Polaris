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
ENGINE="$CORE/voice/RecognitionEngine.kt"
PROBE=$(find app/src/main -name "SpeechProbe.kt" 2>/dev/null | head -1)
PET_VOICE=$(find app/src/main -name "PetVoice.kt" 2>/dev/null | head -1)
PET_OVERLAY=$(find app/src/main -name "PetOverlay.kt" 2>/dev/null | head -1)
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

# A17 引擎选择必须住在 core（纯逻辑），且零 Android 依赖。
# 为什么要单独一条：这段逻辑挑错引擎**不报错**，只是「语音还是不能用」——
# 和判定表一样属于「错了没声音」的那类，必须能被 JVM 断言。
if [ -f "$ENGINE" ] && ! grep -qE '^import android\.' "$ENGINE" &&
   grep -qF "object EngineSelector" "$ENGINE"; then
  pass "A17 引擎选择在 core 且零 Android 依赖（可独立断言）"
else
  fail "A17 引擎选择缺失或引了 android.* —— 挑错引擎不会报错，只会静默失败"
fi

# A18 桌宠必须真的把识别**指向**选中的引擎，而不是只记录了选择。
# 只记录不使用的症状是：日志里写着「已切换到 X」，实际还在用默认引擎——
# 日志与实际行为不一致，比没有日志更误导。
if [ -n "$PET_VOICE" ] &&
   grep -qE 'createSpeechRecognizer\(app, *component\)' "$PET_VOICE"; then
  pass "A18 桌宠用 createSpeechRecognizer(ctx, component) 指定引擎"
else
  fail "A18 桌宠只记录选择、不指定引擎 —— 日志会说谎"
fi

# A19 桌宠起来时必须探一次「哪个引擎认当前语言」。
# 不探的话，默认引擎不认中文时用户只会收到一个错误码，
# 而设备上明明还装着另一个能用的引擎。
if [ -n "$PET_OVERLAY" ] && grep -qF "refreshEngine()" "$PET_OVERLAY"; then
  pass "A19 桌宠启动时探测引擎（不是在长按时才探）"
else
  fail "A19 桌宠没有在启动时探测引擎 —— 默认引擎不认中文时用户只会收到错误码"
fi

# A20 语言标签比较必须处理 cmn / 下划线这类真实写法。
# 引擎回报 `cmn-Hans-CN`（普通话）时，用 == 比 `zh-CN` 会判成不支持，
# 然后提示「换系统语言」——而实际上它完全能识别。
# 这是只有真机才暴露的问题：单测里引擎返回什么由我们说了算。
if [ -f "$ENGINE" ] && grep -qF "cmn" "$ENGINE" && grep -qF 'replace('"'"'_'"'"', '"'"'-'"'"')' "$ENGINE"; then
  pass "A20 语言标签比较处理 cmn / 下划线写法"
else
  fail "A20 语言标签按 == 比较 —— 引擎返回 cmn-Hans-CN 时会被误判成不支持"
fi

# A21 桌宠**不得**传 EXTRA_PREFER_OFFLINE。
#
# 这个名字听起来无害（prefer = 偏好），但在厂商实现里常被当成 require：
# 当前语言的离线包没装时，引擎会直接回 ERROR_LANGUAGE_NOT_SUPPORTED(12)，
# 而不是退回在线识别。2026-11-14 的真机日志正是「每按一次就 code=12」，
# 而同机的在线识别本来是能用的。
#
# 断言查的是**代码**（排除注释行）：注释里讨论这个 extra 是应该写的，
# 把它也算进去就变成「不许提这个词」。
if [ -n "$PET_VOICE" ] &&
   ! grep -vE '^[[:space:]]*//' "$PET_VOICE" | grep -qF "EXTRA_PREFER_OFFLINE"; then
  pass "A21 桌宠不传 EXTRA_PREFER_OFFLINE（否则只有在线中文的引擎会被逼成 code=12）"
else
  fail "A21 桌宠仍在传 EXTRA_PREFER_OFFLINE —— 没装离线包的中文识别会被直接拒掉"
fi

# A22 探测失败的引擎必须**保留在清单里**，标成「没问到」。
#
# 2026-11-14 的诊断报告：设备 2 个识别服务，报告只列出 1 个。被吞掉的正是
# 系统默认引擎（它没在超时内应答）。后果不是「少一行字」而是**结论反转**：
# 剩下那项明确不支持中文 → 报告宣布「没有能用的」→ 语音被拦住，
# 而我们从未问过最该问的那个引擎。
if [ -n "$PROBE" ] && grep -qF "probed = false" "$PROBE" &&
   ! grep -qF "oneDone(null)" "$PROBE"; then
  pass "A22 探测失败的引擎被保留为「未应答」（不是从清单里删掉）"
else
  fail "A22 探测失败仍被丢弃 —— 诊断会少一个引擎，结论可能反转成「没有能用的」"
fi

# A23 诊断报告必须**强制重探**，不能读 15 秒前的缓存。
# 用户点「语音诊断」要的是这一秒的结论；缓存里可能正好存着上一轮
# 冷启动超时的那份残缺结果——读它等于把一次失败原样复述，还看起来像证据。
DIAG=$(find app/src/main -name "VoiceDiagnostics.kt" 2>/dev/null | head -1)
if [ -n "$DIAG" ] && grep -qF "force = true" "$DIAG"; then
  pass "A23 诊断报告强制重探引擎（不读节流缓存）"
else
  fail "A23 诊断报告读缓存 —— 会复述上一轮的残缺结果，用户以为那就是现状"
fi

# A24 诊断入口必须是异步的（探测要连服务，最坏 2.5 秒/引擎）。
# 同步版只能在**探测之前**出报告，也就是永远拿不到引擎那一维——
# 那一段会一直显示「未探测」，而用户点它就是为了看这一段。
SETTINGS=app/src/main/java/com/beichen/polaris/ui/SettingsScreen.kt
if [ -f "$SETTINGS" ] && grep -qF "buildReportAsync" "$SETTINGS"; then
  pass "A24 语音诊断入口异步出报告（探测完成后才生成）"
else
  fail "A24 语音诊断仍是同步生成 —— 引擎那一段永远是「未探测」"
fi

# A25 语言这一维只对「自己录音」的路径算数。
# 聊天页走系统识别页，页面里能自己换语言、自己下语言包；
# 拿引擎侧的探测去否定它，等于把一条本来能用的路自己堵死。
if [ -n "$POLICY" ] &&
   grep -qF "path == VoicePath.SelfRecorded && languageSupported == false" "$POLICY"; then
  pass "A25 语言判定限定在「自己录音」路径（聊天页不被误拦）"
else
  fail "A25 语言判定对所有路径生效 —— 聊天页会被自己的探测结论挡住"
fi

# A26 应用内必须有引擎选择页，并且是一条真路由。
#
# 2026-11-14 的真机截图：用户的「默认应用」里只有「文字转语音输出」，
# **没有「语音识别」**。我们此前所有「去系统设置换一个」的提示，
# 在这台机器上都是死路——用户照着走只会在 TTS 页面里打转。
# 系统改不了，但 createSpeechRecognizer(ctx, component) 能指定引擎，所以选择必须做进 App。
VOICE_SCREEN=$(find app/src/main -name "VoiceEngineScreen.kt" 2>/dev/null | head -1)
if [ -n "$VOICE_SCREEN" ] && grep -qF 'VOICE("voice"' app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt &&
   grep -qF "VoiceEngineScreen()" app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt; then
  pass "A26 应用内有语音引擎选择页（不依赖 ROM 是否把语音识别做成设置项）"
else
  fail "A26 没有引擎选择页 —— 提示只能指路系统设置，而那台机器上没有那一项"
fi

# A27 用户指定的引擎必须**高于**自动探测。
# 反过来的话，用户点了某个引擎、下次启动又被自动结果盖掉——
# 界面显示的和实际用的不是一个，属于最难查的那类问题。
if [ -n "$PET_VOICE" ] &&
   grep -qF "VoiceEngineStore.pinned(app) ?: preferredEngine" "$PET_VOICE"; then
  pass "A27 用户指定的引擎优先于自动探测"
else
  fail "A27 自动探测会盖掉用户的选择 —— 设置页显示与实际使用不一致"
fi

# A28 桌宠的「换引擎」出路必须跳**本应用**的页面。
# 仍然跳系统语音设置的话，用户点完会落到那个不存在的页面上，
# 然后回来问「找不到」——这正是 2026-11-14 发生的事。
if [ -n "$PET_OVERLAY" ] && grep -qF "openVoiceEngineSettings()" "$PET_OVERLAY" &&
   grep -qF "AppDest.VOICE.route" "$PET_OVERLAY"; then
  pass "A28 桌宠的换引擎出路直达本应用语音页"
else
  fail "A28 换引擎仍跳系统设置 —— 那台机器上没有这一项，用户会白跑"
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
  "$ENGINE" \
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
