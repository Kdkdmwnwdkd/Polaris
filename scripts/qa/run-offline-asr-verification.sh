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
# 模型层 2026-11-16 从 app 移到 core（容器 rootfs 也要用同一套下载器）。
# 带兜底：路径再变一次也不至于让整段 [E][F][G] 全红。
MODEL_DIR=core/src/main/java/com/beichen/polaris/model
[ -d "$MODEL_DIR" ] || MODEL_DIR=app/src/main/java/com/beichen/polaris/model
DOWNLOADER=$MODEL_DIR/ModelDownloader.kt
SPEC=$MODEL_DIR/ModelSpec.kt
SCREEN=app/src/main/java/com/beichen/polaris/ui/VoiceEngineScreen.kt
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

for f1 in "$ENGINE" "$INPUT" "$DOWNLOADER" "$SPEC" "$SHEET"; do
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
# 删目录前必须释放原生句柄，否则那份模型在内存里继续占着，用户会以为「删了没效果」。
# 释放挂在界面的删除回调上（模型行是通用的，识别页自己知道该放哪两套）。
if grep -qF 'AsrDirectory.of(context).releaseAll()' "$SCREEN"; then
  pass "A3 删除识别模型前先释放两套识别器"
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
for spec in AsrFast AsrAccurate TtsMatcha TtsZipVoice RootfsAlpine Shizuku; do
  grep -qF "$spec(" "$SPEC" || fail "E1 ModelSpec 少了 $spec —— 那一套就没法下载/删除"
done
grep -qF 'TtsMatcha(' "$SPEC" && grep -qF 'TtsZipVoice(' "$SPEC" \
  && grep -qF 'RootfsAlpine(' "$SPEC" && grep -qF 'Shizuku(' "$SPEC" \
  && pass "E1 六套可选内容统一登记（识别 2 + 音色 2 + 容器 + Shizuku）"

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

echo ""
echo "[F] 存储账：把 300 多 MB 花在哪儿摊开给用户看"

# 用户报「内存都 300 多了，开始才 30 多」。这时最坏的回答是「这些都是必要的」——
# 他会认定在糊弄。正确做法是分账：包体 / 模型 / 缓存三笔，哪笔能退、退多少。
STORAGE=$MODEL_DIR/StorageReport.kt
if [ -f "$STORAGE" ] && grep -qF 'val apkBytes' "$STORAGE" \
   && grep -qF 'val nativeBytes' "$STORAGE" && grep -qF 'val cacheBytes' "$STORAGE"; then
  pass "F1 存储账分三笔（包体 / 运行时库 / 模型 / 缓存）"
else
  fail "F1 只有一个「总占用」数字 —— 用户没法知道哪笔能退"
fi

# 应用本体必须能单独算：它包含解压的 .so（容器要 exec，删不掉），
# 和模型混在一起报会让「删了模型还是 300 多」变成新的困惑。
if grep -qF 'nativeLibraryDir' "$STORAGE" && grep -qF 'sourceDir' "$STORAGE"; then
  pass "F2 应用本体按「包体 + 解压的运行时库」单算，与模型分开"
else
  fail "F2 没拆开包体与运行时库 —— 删模型后数字对不上，会被当成没删干净"
fi

# 清缓存只清我们自己的临时目录。整个 cacheDir 里还可能有别的模块要用的东西，
# 一个叫「清缓存」的按钮不该替所有人做决定。
if grep -qF 'File(context.cacheDir, "models")' "$STORAGE" \
   && grep -qF '"updates"' "$STORAGE" \
   && ! grep -qF 'cacheDir.deleteRecursively()' "$STORAGE"; then
  pass "F3 清缓存只清 models 与已装完的更新包，不误删别的模块的缓存"
else
  fail "F3 清缓存会整目录删，或漏了 updates 里那份已装完的 APK"
fi

# 更新包落地之后没有任何地方删它（ApkDownloader 只保证「不累积」），
# 于是 cacheDir 里常驻一个等于包体大小的 .apk。清理时必须跳过 .part，
# 否则会把用户正在下的那一份删掉。
if grep -qF 'endsWith(".apk")' "$STORAGE" && grep -qF '.part' "$STORAGE"; then
  pass "F4 更新包只删已下完的 .apk，跳过正在下载的 .part"
else
  fail "F4 清更新包没跳过 .part —— 会把正在下的包删掉"
fi

# 界面上必须真的显示这笔账，否则代码写好了也没人看到。
SCREEN_VOICE=app/src/main/java/com/beichen/polaris/ui/VoiceEngineScreen.kt
if grep -qF '占了多大' "$SCREEN_VOICE" && grep -qF '应用本体' "$SCREEN_VOICE" \
   && grep -qF 'readStorage(context)' "$SCREEN_VOICE"; then
  pass "F5 语音页顶部摊开三笔账（含「应用本体」单独一行）"
else
  fail "F5 语音页没有存储明细 —— 用户只能看着系统设置里的数字猜"
fi

# 两套识别都装时只有一套在用。另一套的 24MB 是纯占位，必须在行首说出来，
# 否则用户看到「已下载 24MB」不会想到「这套其实没用上」。
if grep -qF '没在用' "$SCREEN_VOICE" && grep -qF '删掉不影响识别' "$SCREEN_VOICE"; then
  pass "F6 标明哪套没在用，并说明删掉不影响识别"
else
  fail "F6 没标「没在用」 —— 那 24MB 会一直躺在那儿"
fi

echo ""
echo "[G] 容器与 Shizuku：也不再随包"

# 用户的原话：「你要什么就自己下，而不是全部下好再给你」。
# 这两份以前在 assets 里（8.2MB + 2.5MB），只有少数人用。
ROOTFS=core/src/main/java/com/beichen/polaris/core/container/RootfsManager.kt
SHIZUKU=app/src/main/java/com/beichen/polaris/automation/shizuku/ShizukuInstaller.kt

# 归档路径只能有一处定义：下载器往哪儿放、引擎从哪儿读。
# 两边各写一遍，改目录时必然漏一处，表现是「下载完成但启动说没下载」。
if grep -qF 'File(context.filesDir, "rootfs/${distro.id}")' "$ROOTFS" \
   && grep -qF '"rootfs"' "$SPEC"; then
  pass "G1 容器归档路径与 ModelSpec.RootfsAlpine 对齐（下载器/管理器同一处）"
else
  fail "G1 容器归档路径两边不一致 —— 下完会「找不到」"
fi

# 下载落地后在 filesDir，assets 只留给调试包；顺序必须「先磁盘、后 assets」，
# 否则调试包里那份旧的会永远盖住用户下的新版本。
if grep -qF 'downloaded.isFile' "$ROOTFS" && grep -qF 'assets.open("containers/' "$ROOTFS"; then
  pass "G2 解压先读磁盘、再回退 assets（调试包不被旧文件盖住）"
else
  fail "G2 解压只读 assets —— 下载来的归档永远用不上"
fi

# 没下载时要能明确拒绝，而不是解压到一半失败。
if grep -qF 'if (!hasArchive(context, distro))' "$ROOTFS"; then
  pass "G3 归档未下载时明确返回（调用方据此给下载入口）"
else
  fail "G3 没判归档是否已下载 —— 会走进解压并抛一个看不懂的错"
fi

# Shizuku：安装包不在包里了，必须有「先去下载」这一态。
if grep -qF 'NEED_DOWNLOAD' "$SHIZUKU" \
   && ! grep -qF 'context.assets.open(ASSET_NAME)' "$SHIZUKU"; then
  pass "G4 Shizuku 安装包改为下载落地（不再从 assets 读）"
else
  fail "G4 Shizuku 仍在从 assets 读安装包 —— 它会被打进 APK"
fi

# 源码树里：Shizuku 安装包不该有（会被打进 APK）；容器 rootfs 反过来**必须内置**。
# 2026-11-16 晚改的向：8.7MB 的 tar 换「装完即可用、离线也能起容器」，值。
if [ ! -e app/src/main/assets/shizuku.apk ] \
   && [ -s app/src/main/assets/containers/alpine.tar ]; then
  pass "G5 assets 里无 Shizuku 安装包，容器 rootfs 已内置"
else
  fail "G5 assets 可选组件状态不对（Shizuku 不该在，containers/alpine.tar 该在）"
fi

# 同一份 tar 既当内置资源，也供 CI 打成 rootfs-alpine.zip —— 那是给 1.762 及更早
# 版本用的，它们只认下载。撤了资产，老版本的容器页就会 404。
if [ -s app/src/main/assets/containers/alpine.tar ] \
   && grep -qF 'rootfs-alpine.zip' "$WORKFLOW"; then
  pass "G6 内置 tar 与 CI 发布的 rootfs-alpine.zip 同源（老版本仍可下载）"
else
  fail "G6 归档没有源或 CI 不发布 —— 老版本点下载会 404"
fi

echo "离线识别：$p 通过 / $f 失败"
[ "$f" -eq 0 ]
