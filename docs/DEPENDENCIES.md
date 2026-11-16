# 北辰依赖台账

> 目的：把「这个 App 到底依赖了什么、谁提供的、什么许可、出问题怎么兜底」写清楚。
> 每次增删依赖都要改这份文件，并在 PR/提交信息里点名。
>
> 最后核对：2026-11-16（D-9：语音栈补全 + 权重分发方式变更 + espeak-ng 的 GPL 义务）。

## 一句话结论

北辰的本地推理有**两条**技术栈，各自独立、互不调用：

| 栈 | 用途 | 运行时 |
|---|---|---|
| GGUF + llama.cpp（CPU 后端）+ 自写 JNI 桥 | 文字生成（本地大模型） | `libagentkit-llama.so` |
| sherpa-onnx v1.13.8 + onnxruntime | 语音识别与合成（本地） | `libsherpa-onnx-jni.so` + `libonnxruntime.so` |

没有 MNN、没有 LiteRT、没有 Qualcomm QNN。APK 里的 `.so` 是
`libagentkit-llama.so`、`libagentkit-termux.so`、`libproot.so`，
加上语音栈的两个（按 ABI 各一份）。**四套语音权重不进 APK**，由 `ModelSpec`
在应用内按需下载（§4.4）。

## 1. 运行时依赖（Maven，随 APK 分发）

| 坐标 | 版本 | 用途 | 许可 |
|---|---|---|---|
| `dev.rikka.shizuku:api` / `:provider` | 13.1.5 | Shizuku 提权通道（adb 级操作的授权入口） | Apache-2.0 |
| `androidx.work:work-runtime-ktx` | 2.9.1 | 定时巡检（最小周期 15 分钟） | Apache-2.0 |
| `androidx.compose:compose-bom` | 2024.09.00 | Compose 版本对齐（UI 全家桶） | Apache-2.0 |
| `androidx.compose.ui` / `material3` / `material-icons-extended` | 随 BOM | 界面与图标 | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.9.2 | Compose 宿主 Activity | Apache-2.0 |
| `androidx.lifecycle:*` | 2.8.4 | ViewModel / 生命周期感知收集 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.1 | 协程 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.6.3 | JSON（设置、消息、工具参数） | Apache-2.0 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | 云端供应商 HTTP / SSE 流式 | Apache-2.0 |
| `org.mozilla:rhino-runtime` | 1.7.14 | 技能（Skill）里的 JS 脚本执行 | MPL-2.0 |
| `androidx.core:core-ktx` | 1.13.1 | AndroidX 基础扩展 | Apache-2.0 |
| `com.google.mlkit:text-recognition-chinese` | 16.0.1 | 离线中文 OCR（模型打进 APK，不需要 GMS） | Google ML Kit 条款 |

### 1.1 模块归属（3.5 拆模块，2026-11-08）

工程从单模块拆成 `:core` + `:app` 两个 Gradle 模块。依赖的归属因此变得可判定：

| 模块 | 依赖 |
|---|---|
| `:core` | `androidx.core:core-ktx` 1.13.1 / `kotlinx-coroutines-android` 1.8.1 / `kotlinx-serialization-json` 1.6.3 / `okhttp` 4.12.0 / `rhino-runtime` 1.7.14 —— **全部用 `api` 声明**，版本取自 `gradle/libs.versions.toml` |
| `:app` | 上表其余全部（Compose、WorkManager、Shizuku、ML Kit、Rhino…），另有 `implementation(project(":core"))` |

两条约束：

1. **五个共用依赖的版本号只在 `gradle/libs.versions.toml` 写一份**（2026-11-08 收口）。
   `:core` 与 `:app` 都用 `libs.*` 引用，升级改一处即可。搬进目录的判据是「第二个模块
   要用它」——只有 app 用的 Compose / WorkManager / Shizuku / ML Kit 仍留在
   `app/build.gradle.kts` 里，不为了统一制造一层没有共享价值的间接。
   `:core` 用 `api` 是为了让 app 侧能解析出现在 core 公共签名里的 okhttp / serialization 类型。
2. **`:core` 不得依赖 Compose、WorkManager、Shizuku、ML Kit**。前三者属于界面/宿主调度，
   ML Kit 只被 `ui/MediaScreen` 用。要加新依赖先问：它是不是内核跑起来就必需的？

## 2. 构建期依赖（不进 APK）

| 项 | 版本 | 说明 |
|---|---|---|
| Android Gradle Plugin | 8.5.0 | |
| Kotlin | 1.9.25 | Compose 编译器 1.5.15 |
| compileSdk / minSdk / targetSdk | 34 / 26 / 28 | targetSdk 28 是**故意**的：≥29 禁止从应用数据目录 `execve`，proot 会直接失败 |
| NDK | 26.3.11579264 | 由 CI 的 `sdkmanager` 安装 |
| CMake | 3.22.1 | |
| JDK | 17 | |

## 3. 源码依赖：llama.cpp

| 项 | 值 |
|---|---|
| 上游 | `ggml-org/llama.cpp`（MIT） |
| 版本 | tag `v0.7.0`（2026-10-15），钉死 commit `620d2225cc436ab0b001e3200e590e3b286ef9ab` |
| 获取方式 | `scripts/fetch-llama.sh`（git 浅克隆；受限网络可走 CDN 逐文件拉） |
| 校验 | 克隆后 `git rev-parse HEAD` 必须等于上面的 commit，否则**构建失败** |
| 编译选项 | 只编 CPU 后端；`GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`；`GGML_CPU_REPACK=OFF`；`GGML_CPU_KLEIDIAI=OFF`；关闭 OpenCL/Vulkan/CUDA/SYCL/OpenMP |

为什么钉 commit 而不只钉 tag：tag 可以被上游移动（force-push / 重打），漂移是**静默**的——
同一份 `--branch v0.7.0` 在不同时间会编出不同产物。钉 commit 让漂移当场失败。

为什么不开 i8mm：属 ARMv8.6，只有骁龙 8 Gen 1 及以后才有；dotprod/fp16 从 Cortex-A75
（2017）起普及，覆盖面大得多。缺失时 `LlamaJni` 在读 `/proc/cpuinfo` 阶段就拒绝加载
`.so`，不会 SIGILL 崩溃。

## 4. 随包二进制（非 Maven、非源码编译）

| 文件 | 大小 | 来源 | 许可 |
|---|---|---|---|
| `assets/bin/arm64-v8a/proot` | 301 KB | Termux 官方预编译 5.1.107.96 | GPL-2.0 |
| `assets/bin/arm64-v8a/libtalloc.so.2` | 33 KB | Termux 官方预编译（proot 依赖） | LGPL-3.0 |
| `assets/bin/arm64-v8a/libandroid-shmem.so` | 14 KB | Termux 官方预编译（Android 无 SysV shm 的垫片） | BSD-2-Clause |
| `jniLibs/arm64-v8a/libproot.so` | 301 KB | 同上 proot，改名为 `.so` 以便落到 `nativeLibraryDir`（可 `execve`） | GPL-2.0 |
| `jniLibs/{arm64-v8a,x86_64}/libsherpa-onnx-jni.so` | ~5 MB / ABI | sherpa-onnx **v1.13.8** 官方 android 包（`scripts/qa/fetch-tts-assets.py` 解包后直挂 jniLibs） | Apache-2.0 |
| `jniLibs/{arm64-v8a,x86_64}/libonnxruntime.so` | ~15 MB / ABI | 同上（jni 库的运行时依赖，缺它 dlopen 直接失败） | MIT（onnxruntime） |
| `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` | ~1.2k 行 | 同上包内的 Kotlin API，整份照搬（改包名），文件头保留原版权 | Apache-2.0 |
| `filesDir/tts/zipvoice/espeak-ng-data/` | ~12 MB | 随 ZipVoice 模型包分发（用户按需下载到设备，**不进 APK**） | **GPL-3.0**（见 §4.3） |

### 4.1 `.so` 清单为什么写「按包内实际内容安装」而不是逐名列举

`install_runtime()` 的做法是**把 android tar 包里所有 `.so` 都装上**（不限名字），
只按 ABI 过滤。这样升级 sherpa 版本时不会出现「新版本多了一个依赖库、而脚本不认识
这个名字」——那种失败的表现是 `UnsatisfiedLinkError`，而错误信息只说找不到某个符号，
看不出缺的是哪个文件。

代价是台账无法给出一个永远准确的名单。所以这里给的是**当前版本的事实**，
并给出核对方式：

```bash
# 任何时刻以这条命令的输出为准
ls app/src/main/jniLibs/arm64-v8a/ app/src/main/jniLibs/x86_64/
```

### 4.2 语音栈的版本必须三处一致

| 三处 | 在哪 |
|---|---|
| Kotlin API | `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt`（从同版本 android 包拷入） |
| 原生 `.so` | `app/src/main/jniLibs/<abi>/`（同版本 android 包解出） |
| 权重与模型目录结构 | 由 `ModelSpec` 的 `required` 列表约束（`filesDir/asr|tts/...`） |

前两者由 `SHERPA_VERSION = "1.13.8"` 一处决定。跨版本混用的报错只有一句
`Invalid OfflineTtsConfig`——Kotlin data class 是 JNI 反序列化契约，字段对不上不会
说「少的是 referenceAudio」。所以**升级 sherpa 时三处一起改，别只换 `.so`**。

`1.12.4 → 1.13.8` 的原因：`OfflineTtsZipVoiceModelConfig`、`GenerationConfig.referenceAudio`
都是 v1.13.x 才进 Kotlin API 的。

### 4.3 espeak-ng-data 是 GPL-3.0，且**随模型分发给最终用户**

ZipVoice 包里的 `espeak-ng-data/`（词典与音素表）来自 espeak-ng，许可为 **GPL-3.0**，
而不是模型本身的 Apache-2.0。它与 proot 的处境不同：

- proot 是**独立可执行文件**，通过 `ProcessBuilder` 调用，不与北辰代码链接，GPL 的
  传染边界清楚；
- `espeak-ng-data` 是**数据文件**，被 sherpa 运行时读取。数据文件是否构成「链接」
  在 GPL 的解释里有争议，稳妥的读法是：既然它随我们的发布物一起到用户手上，
  就该按 GPL-3.0 的要求提供对应源码与许可全文。

当前的处理与待补项：

| 项 | 状态 |
|---|---|
| 许可全文随包 | **已补**（2026-11-16）：`assets/3rdparty/espeak-ng/COPYING`（GPL-3.0 全文，35 KB） |
| 源码获取方式 | **已补**：`assets/3rdparty/espeak-ng/SOURCE_OFFER.txt`（指向上游仓库 + 三年书面索取承诺） |
| 不修改 | 已满足：我们原样分发，未改数据文件 |
| 只在用户主动下载克隆模型时出现 | 已满足：默认安装包不含该目录 |

> 这一项曾经是**发布阻塞级**缺口：用户装的是发布物，发布物里带了 GPL 数据却没有
> 许可全文，就是不合规。现在两条分发路径都覆盖了：
>
> | 路径 | 声明文件在哪 |
> |---|---|
> | 安装包（APK） | `assets/3rdparty/espeak-ng/{COPYING,SOURCE_OFFER.txt}` |
> | 模型包（用户下载的 `tts-zipvoice.zip`） | CI 在打包前把同两份文件拷进 zip 根目录 |
>
> **重打包就会改哈希**：往 `tts-zipvoice.zip` 里加任何文件都会让它变一个全新的资产，
> 所以 `ModelSpec.TtsZipVoice.sha256`（D-7 的内嵌值）必须在每次重新打包后同步更新，
> 否则用户会看到「校验失败：发布清单里的哈希与内置值不一致」——那条文案本身没错，
> 但会让人以为是攻击，其实是自己改了包没改常量。

### 4.4 权重不进 APK（2026-11-16 变更，本节旧版写错了）

旧版这张表列的是 `assets/tts/matcha/*`（125 MB）与 `assets/tts/zipvoice/*`（155 MB），
那是 2026-11-15 的状态。11-16 起四套权重（两识别 / 两音色）改成**应用内按需下载**：

- 安装包里一个字节都不带，APK 因此从 361 MB 降到 30 MB 上下；
- 权重上传到 `latest` Release，由 `ModelSpec` 描述、`ModelDownloader` 拉取、
  `ModelZip` 解压到 `filesDir/{asr,tts,rootfs,tools}/`；
- 清单 `model-manifest.txt` 与 zip 同源，因此**校验必须靠内嵌哈希**（D-7，
  见 `ModelSpec.sha256`）——只信清单等于「服务端说这是对的」。

许可不因分发方式改变：模型仍是 Apache-2.0（icefall / ZipVoice）与 MIT（vocos），
`espeak-ng-data` 仍是 GPL-3.0。


**为什么 v1.12.4 → v1.13.8**：`OfflineTtsZipVoiceModelConfig`、
`GenerationConfig.referenceAudio` 都是 v1.13.x 才进 Kotlin API 的。
Kotlin data class 是 JNI 反序列化契约，原生 `.so` 必须同版本；
跨版本混用的报错只有一句 `Invalid OfflineTtsConfig`，看不出缺的是哪个字段。

**为什么两套模型都留着**：matcha 出一个固定女声（开箱即用、快），
ZipVoice 必须带用户的参考音频才能出声（克隆、慢）。前者不能替代后者，
后者也不能替代前者——没有参考音频时它根本不出声。

sherpa-onnx 的两部分（`app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` 源码 + jni 库）
同属 Apache-2.0，文件头保留了原版权声明（含版本号与「整份照搬」的理由）。
模型与运行时都不进 git，由 `scripts/qa/fetch-tts-assets.py` 按版本号取——理由见脚本注释。

GPL-2.0 合规措施：
- `assets/3rdparty/proot/COPYING` 收录许可全文；
- `assets/3rdparty/proot/SOURCE_OFFER.txt` 提供源码获取方式；
- 这三个程序**作为独立可执行文件被调用**（`ProcessBuilder`），不与北辰代码链接。

## 5. 明确不引入的依赖（以及为什么）

对标 IRIDIS 时逐个评估过，结论是不加：

| 它用的 | 它为什么用 | 北辰为什么不加 |
|---|---|---|
| MNN（阿里，静态库 ~6 MB） | 支持 taobao-mnn 模型目录格式；可走 OpenCL GPU | 北辰只支持 GGUF；多一个引擎 = 多一份内存占用与两套 prompt/KV 逻辑，收益不抵复杂度 |
| LiteRT-LM（+22 MB） | 跑 `.litertlm` 专有格式 | 与 GGUF 能力重叠，纯增体积 |
| sherpa-onnx（TTS + 零样本克隆） | 本地语音对话与声音克隆 | **2026-11-15 已接入**：系统 TTS 的音色由 ROM 决定（常见 8kHz 电话音），用户明确要「好听的声音」并能导入自己的声音。只取 TTS 一半（不引识别），体积换音质。见 `docs/archive/reports/音色选择-实现报告-2026-11-15.md` 与 `声音克隆-实现报告-2026-11-15.md` |
| Qualcomm QNN / GenieX（51 个 .so，~82 MB） | 骁龙 NPU 加速 | 需要按芯片离线转换模型，通用 GGUF 用不上；且要下载 82 MB 运行时 |
| KleidiAI | ARM 官方 matmul 内核 | **值得跟进**：需要配套较新的 ggml，见「已知缺口」 |

结论：文字生成这条栈仍然比 IRIDIS 小一个数量级（约 3 MB native vs 100+ MB），代价是
只支持 GGUF + 只跑 CPU。语音这条栈不是：sherpa-onnx + onnxruntime 每个 ABI 约 20 MB，
是**明确换来的**——用户要的不再是系统 TTS 那种 8kHz 电话音，而是能导入自己声音的合成。
两条栈互不调用，任何一条出问题都不影响另一条。

## 6. 已知缺口与下一步

0. **espeak-ng-data 的 GPL-3.0 义务未完成**（D-9 查出，优先级最高）。
   `assets/3rdparty/espeak-ng/COPYING` 与 `SOURCE_OFFER.txt` 都不存在，
   而 ZipVoice 模型包里带着它、会随用户下载到达设备。补法见 §4.3。
   在补齐之前，ZipVoice 不应出现在对外发布矩阵里——这是**发布阻塞项**，不是技术债。

1. ~~llama.cpp 版本偏旧（b4600，2025 年初）~~ → **已升级到 v0.7.0（2026-10-15）**。
   实测需要改的 API 只有三处（`llama_kv_cache_clear/seq_rm` → `llama_memory_*`、
   `flash_attn` → `flash_attn_type`、`use_mmap` → `load_mode`），外加
   `llama_sampler_init_penalties` 多了一个 `n_vocab` 首参。已在 `llama-jni.cpp`
   顶部做成 `#if AK_LLAMA_MEMORY_API` 双向兼容层，tag 回退到 b4600 也不用改 C++。
   仍未启用的新能力：`lazy_mode`（按需读权重，对口冷启动）、`GGML_CPU_KLEIDIAI`
   （ARM 手写 matmul 内核）。这两项各自单独验证，不和新版本一起上。

2. **proot 三件套没有版本锁定脚本**。
   现在是手工放置的二进制，没有 SHA-256 记录。若要升级，先补校验再换文件。

3. **ML Kit OCR 模型体积**。
   `text-recognition-chinese` 自带模型（约 10 MB），若后续要瘦身，可换成按需下载。

## 7. 怎么核对这份台账

```bash
# 运行时依赖（唯一真源）
grep -n "implementation\|platform(" app/build.gradle.kts

# 构建期版本
grep -n "compileSdk\|minSdk\|targetSdk\|ndkVersion\|version =" app/build.gradle.kts build.gradle.kts

# llama.cpp 钉的版本
grep -n "TAG=\|EXPECTED_COMMIT=" scripts/fetch-llama.sh

# 随包二进制
find app/src/main/assets/bin app/src/main/jniLibs -type f

# 语音栈版本（三处必须一致，见 §4.2）
grep -n "SHERPA_VERSION" scripts/qa/fetch-tts-assets.py
grep -rn "sherpa-onnx" app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt | head -3
ls app/src/main/jniLibs/arm64-v8a/

# 权重是否真的不在 APK 里（应该有输出：四套都不在）
grep -rn "assets/tts" app/src/main/assets 2>/dev/null || echo "OK：assets 下没有 tts 权重"

# ModelSpec 里哪些模型的哈希还没固定（D-7）
grep -n "sha256 = \"" core/src/main/java/com/beichen/polaris/model/ModelSpec.kt || echo "全部未固定"
```
