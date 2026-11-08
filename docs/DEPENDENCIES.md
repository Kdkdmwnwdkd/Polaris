# 北辰依赖台账

> 目的：把「这个 App 到底依赖了什么、谁提供的、什么许可、出问题怎么兜底」写清楚。
> 每次增删依赖都要改这份文件，并在 PR/提交信息里点名。
>
> 最后核对：1.441（2026-10-28）。

## 一句话结论

北辰的本地推理**只有一条技术栈**：GGUF 模型 + llama.cpp（CPU 后端）+ 自写 JNI 桥。
没有 MNN、没有 LiteRT、没有 ONNX Runtime、没有 Qualcomm QNN。APK 里的 `.so` 只有
`libagentkit-llama.so`、`libagentkit-termux.so`、`libproot.so` 三个。

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
| `:core` | `androidx.core:core-ktx` 1.13.1 / `kotlinx-coroutines-android` 1.8.1 / `kotlinx-serialization-json` 1.6.3 / `okhttp` 4.12.0 —— **全部用 `api` 声明** |
| `:app` | 上表其余全部（Compose、WorkManager、Shizuku、ML Kit、Rhino…），另有 `implementation(project(":core"))` |

两条约束：

1. **四个共用依赖的版本号必须两边一致**。它们目前各写一份（`core/build.gradle.kts` 与
   `app/build.gradle.kts`），没有 version catalog 兜底——升级时两处一起改。`:core` 用 `api`
   是为了让 app 侧能解析出现在 core 公共签名里的 okhttp / serialization 类型。
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
| sherpa-onnx（语音识别/TTS） | 本地语音对话 | 北辰没有本地语音需求（语音走系统 TTS/输入法） |
| Qualcomm QNN / GenieX（51 个 .so，~82 MB） | 骁龙 NPU 加速 | 需要按芯片离线转换模型，通用 GGUF 用不上；且要下载 82 MB 运行时 |
| KleidiAI | ARM 官方 matmul 内核 | **值得跟进**：需要配套较新的 ggml，见「已知缺口」 |

结论：北辰的本地推理栈比 IRIDIS 小一个数量级（约 3 MB native vs 100+ MB），代价是
只支持 GGUF + 只跑 CPU。

## 6. 已知缺口与下一步

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
```
