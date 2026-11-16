#!/usr/bin/env bash
# 离线语音识别（自带模型）验证。
#
# 为什么值得一个独立脚本：这条链路的价值全在「系统识别不可用的时候它还在」。
# 而它一旦坏了，表现和「机器上没装识别引擎」一模一样——**什么都没发生**。
# 没有报错、没有日志、麦克风甚至连占用都没有。那种故障只能靠断言提前拦。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)

ASR_DIR=app/src/main/java/com/beichen/polaris/audio/asr
ENGINE=$ASR_DIR/StreamingAsr.kt
INPUT=$ASR_DIR/LocalSpeechInput.kt
INSTALLER=$ASR_DIR/AsrModelInstaller.kt
MODEL_DIR=app/src/main/java/com/beichen/polaris/model
DOWNLOADER=$MODEL_DIR/ModelDownloader.kt
SPEC=$MODEL_DIR/ModelSpec.kt
ACCURATE=$ASR_DIR/OfflineAsrEngine.kt
ROUTER=$ASR_DIR/AsrRouter.kt
PET=app/src/main/java/com/beichen/polaris/pet/PetVoice.kt
CHAT=app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt
SHEET=app/src/main/java/com/beichen/polaris/ui/LocalVoiceSheet.kt
JNI=app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
WORKFLOW=.github/workflows/build.yml

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 模型：不进包、可删、下完要校验"

for f1 in "$ENGINE" "$INPUT" "$INSTALLER" "$SHEET"; do
  [ -f "$f1" ] || { fail "A0 缺文件 $f1"; }
done

# 24MB 的模型对「只想让桌宠说话」的人是白付的流量。它必须是按需下载。
if grep -qF 'File(File(context.filesDir, "asr"), "zh-streaming")' "$ENGINE"; then
  pass "A1 模型落在应用私有目录（filesDir/asr），不进 APK"
else
  fail "A1 模型路径变了——若改回 assets，安装包会白涨 52MB"
fi

# 断在半截的 zip 解出来只会是「模型文件缺失」，原生层再回一句看不出原因的话。
# 校验放在统一下载器里：四套模型都要过同一道关。
if grep -qF 'MessageDigest.getInstance("SHA-256")' "$DOWNLOADER" \
   && grep -qF 'entry.digestMatches(part)' "$DOWNLOADER"; then
  pass "A2 每个整包下完核对 sha256（截断即失败，不当成功）"
else
  fail "A2 下载没有校验哈希——坏文件会被当成装好了"
fi

# 删目录前必须释放原生句柄，否则那份模型在内存里继续占着，用户会以为「删了没效果」。
if grep -qF 'onBeforeRemove = { router.releaseAll() }' "$INSTALLER"; then
  pass "A3 删除先释放两套识别器，再删目录"
else
  fail "A3 删除没先 release —— 原生层仍持有已删文件"
fi

# 真机实测（2026-11-15）：`failed to connect to github.com…after 10000ms`。
# 仓库是自己的没用——**域名**在手机网络上到不了。所以下载器必须有多源候选表。
if grep -qF 'GitHubMirror.sources(' "$DOWNLOADER"; then
  pass "A4 模型下载走多源候选（镜像 → 镜像 → 直连）"
else
  fail "A4 下载写死单源 —— 直连 github.com 在真机上超时，模型永远装不上"
fi

# 两处各写一张候选表，就会出现「APK 能下、模型下不了」这种各修一半的状态。
APK_DL=core/src/main/java/com/beichen/polaris/core/update/ApkDownloader.kt
if grep -qF 'GitHubMirror.sources(officialUrl)' "$APK_DL"; then
  pass "A5 APK 与模型共用同一张候选表（一处改，两处生效）"
else
  fail "A5 APK 下载自己维护了一份源列表 —— 迟早与模型那份不一致"
fi

# 失败时必须说清「哪个源、为什么」：只报最后一句的话，用户分不清
# 「模型没上传」和「网络到不了」。
if grep -qF 'failures.joinToString' "$DOWNLOADER"; then
  pass "A6 多源失败时汇总每个源的原因"
else
  fail "A6 失败只报一句话 —— 用户无从判断是网络还是服务端缺文件"
fi

# 两套模型是互补的，但优先级只能是「准的那个先」：真机上流式模型把
# 「你在这吗」认成「你在这好」，那种错字用户没法用，快 0.8 秒毫无意义。
if awk '/fun active\(\)/,/^    }/' "$ROUTER" | grep -q 'accurate.isInstalled()'; then
  pass "A7 路由优先「更准」那套（装了就压过流式）"
else
  fail "A7 路由顺序变了 —— 更准的模型装了却不用"
fi

echo ""
echo "[B] 接入：两条入口都优先用自带识别"

# 系统识别已删除（2026-11-16）：桌宠只剩这一条路，所以判据从「先本地再系统」
# 变成「没模型就明确拒绝并指路」，以及「文件里不该再有 SpeechRecognizer」。
# 只看代码行：KDoc 里解释「为什么删掉系统识别」时必然会写 SpeechRecognizer。
if grep -qF 'if (!localAsr.isInstalled())' "$PET" \
   && ! grep -vE '^[[:space:]]*(\*|//)' "$PET" | grep -qF 'SpeechRecognizer'; then
  pass "B1 桌宠只用自带模型，没装时明确拒绝并指路（系统识别已删）"
else
  fail "B1 桌宠还在走/还留着系统识别 —— 那条路在真机上一个字都出不了"
fi

if grep -qF 'if (AsrDirectory.of(ctx).isInstalled())' "$CHAT" \
   && ! grep -vE '^[[:space:]]*(\*|//)' "$CHAT" | grep -qF 'RecognizerIntent'; then
  pass "B2 聊天页只用自带识别，没模型就指路（不再弹系统识别页）"
else
  fail "B2 聊天页还留着系统识别页 —— 国行机上照样没字"
fi

# 两处各写一套录音，迟早出现「桌宠能听、聊天页不能」这种各修一半的状态。
if grep -qF 'LocalSpeechInput(' "$PET" && grep -qF 'LocalSpeechInput(' "$SHEET"; then
  pass "B3 桌宠与聊天页共用同一个录音实现"
else
  fail "B3 两条入口各自实现录音 —— 会各坏一半"
fi

echo ""
echo "[C] 参数：错一个就是满屏乱码"

# 模型是 16kHz 训练的，喂 44.1kHz 进去得到的是一串音近词（看着像「识别不准」）。
# 录音端（LocalSpeechInput）与两套模型都必须钉在 16kHz。录音端现在用自己的常量，
# 不再引用 StreamingAsr.SAMPLE_RATE——两条引擎共用同一个采样率，引用谁都不合适。
if grep -qF 'const val SAMPLE_RATE = 16_000' "$INPUT" \
   && grep -qF 'const val SAMPLE_RATE = 16_000' "$ENGINE" \
   && grep -qF 'const val SAMPLE_RATE = 16_000' "$ACCURATE"; then
  pass "C1 录音与模型同为 16kHz"
else
  fail "C1 采样率没跟模型对齐 —— 会识别成音近词"
fi

# VOICE_RECOGNITION 会关掉对识别有害的 AGC/降噪；用 MIC 在部分 ROM 上明显更差。
if grep -qF 'MediaRecorder.AudioSource.VOICE_RECOGNITION' "$INPUT"; then
  pass "C2 录音源用 VOICE_RECOGNITION"
else
  fail "C2 录音源退回 MIC —— 部分 ROM 上准确率明显下降"
fi

# 模型装好但 .so 里没有对应符号，会在用户点第一次语音时才崩；这里提前拦。
# 先把符号表落到变量里再匹配：`strings | grep -q` 会让 strings 吃到 SIGPIPE，
# 本脚本开了 pipefail，整条管道于是非零——断言会因为「查得太快」而红。
SYMS=$(strings -a "$JNI" 2>/dev/null || true)
# 用 herestring 而不是 `printf | grep -q`：后者匹配到就退出，printf 吃 SIGPIPE，
# 本脚本开着 pipefail，整条管道于是变成非零——「查得太快」会让断言假红（本轮踩了两次）。
if grep -q 'Java_com_k2fsa_sherpa_onnx_OnlineRecognizer_newFromFile' <<<"$SYMS"; then
  pass "C3 JNI 里带 OnlineRecognizer（不用另编 NDK）"
else
  fail "C3 打包的 .so 不含流式识别符号 —— 运行到第一次识别才会炸"
fi

echo ""
echo "[D] 发布：四套模型从同一个 Release 出"

# 2026-11-16：识别两套、音色两套，全部走 model-manifest.txt + zip。
# 以前是每套一张清单、逐文件下；四套各写一遍，改一处必然漏一处。
if grep -qF 'model-manifest.txt' "$WORKFLOW" && grep -qF 'gh release upload "$TAG" "$OUT"/*.zip' "$WORKFLOW"; then
  pass "D1 CI 把四套模型与统一清单传到 latest Release（与 APK 同源）"
else
  fail "D1 CI 没上传模型 —— App 里点下载会 404"
fi

# 每次构建都传 430MB 是纯浪费，而且会把 Release 的更新时间刷成构建时间。
# 四件资产齐全才跳：只看清单会漏掉「传到一半崩了」，那种情况 App 会一直 404。
if grep -qF 'complete=1' "$WORKFLOW" && grep -qF 'tts-zipvoice.zip' "$WORKFLOW"; then
  pass "D2 上传是幂等的（四件资产齐全才跳过）"
else
  fail "D2 每轮构建都会重传模型，或漏判「zip 少一个」"
fi

# 只上传流式那套的话，用户在「更准识别」上点下载会 404。
if grep -qF 'asr-accurate.zip' "$WORKFLOW" && grep -qF "assets/155854908" "$WORKFLOW"; then
  pass "D3 整段识别模型也传了（asr-accurate.zip）"
else
  fail "D3 CI 没上传整段模型 —— 「更准识别」点了会 404"
fi

echo ""
echo "[E] 音色模型：同样按需下载，安装包不带 335MB"

# 四套模型必须在同一张表里。散着写会出现「识别能删、音色不能删」这种半套体验。
for spec in AsrFast AsrAccurate TtsMatcha TtsZipVoice; do
  grep -qF "$spec(" "$SPEC" || fail "E1 ModelSpec 少了 $spec —— 那一套就没法下载/删除"
done
grep -qF 'TtsMatcha(' "$SPEC" && grep -qF 'TtsZipVoice(' "$SPEC" \
  && pass "E1 四套模型统一登记（识别 2 + 音色 2）"

# 引擎读的必需文件必须与下载器判定的同一份，否则「下载说装好了、引擎说缺文件」。
if grep -qF 'ModelSpec.TtsMatcha.required' app/src/main/java/com/beichen/polaris/audio/tts/LocalVoiceEngine.kt \
   && grep -qF 'ModelSpec.TtsZipVoice.required' app/src/main/java/com/beichen/polaris/audio/tts/ZipVoiceEngine.kt; then
  pass "E2 音色引擎按同一份必需清单校验（不再各写一份）"
else
  fail "E2 音色引擎还在自己列文件 —— 改模型时必然漏一处"
fi

# 安装包里一个权重都不该有：fetch 脚本必须只往工作树外写。
if ! grep -qF 'assets", "tts"' scripts/qa/fetch-tts-assets.py; then
  pass "E3 拉取脚本不再往 assets/tts 写（安装包因此小 335MB）"
else
  fail "E3 拉取脚本仍写入 assets/tts —— 模型会被打进 APK"
fi

# 包体守卫：assets/tts 非空时 CI 必须红，否则「悄悄涨回 361MB」没人发现。
if grep -qF 'assets/tts 非空' "$WORKFLOW"; then
  pass "E4 CI 有包体守卫（assets/tts 非空即失败）"
else
  fail "E4 CI 没拦「模型又被打进包」 —— 包体会悄悄涨回去"
fi

echo "离线识别：$p 通过 / $f 失败"
[ "$f" -eq 0 ]
