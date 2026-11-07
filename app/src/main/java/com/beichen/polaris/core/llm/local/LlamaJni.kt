package com.beichen.polaris.core.llm.local

import android.util.Log
import java.io.File

/**
 * llama.cpp JNI 桥的 Kotlin 侧声明。
 *
 * 对应 app/src/main/cpp/llama-jni.cpp 的 native 函数（净室重写，不套任何
 * 第三方封装）。协议细节（消息→模板→工具提示→采样）都在 [LocalLlamaClient] 编排，
 * 这里只做「句柄 + 几个原语」。
 *
 * 库名与 CMakeLists.txt 的 add_library(agentkit-llama SHARED ...) 一致。
 * 只在真机 arm64 上存在——模块缺失时 [ensureLoaded] 返回 false，UI 走降级文案。
 */
object LlamaJni {

    private const val TAG = "LlamaJni"

    /** 生成过程中的逐 token 回调（native 线程调用，实现方必须快速返回）。 */
    fun interface TokenListener {
        fun onToken(piece: String)
    }

    @Volatile
    private var loadAttempted = false

    @Volatile
    var available: Boolean = false
        private set

    /** 加载失败的用户可读原因（ABI 不符 / CPU 指令集不足）；成功时为 null。 */
    @Volatile
    var unavailableReason: String? = null
        private set

    /**
     * 刻意的 no-op（登记在 [com.beichen.polaris.AppGraph] 里，`resettable = false`）。
     *
     * `System.loadLibrary` 不可逆：.so 载入后无法卸载，失败的尝试（ABI 不符、
     * 缺 CPU 指令集）重试也是同样结果，而 [loadAttempted] 正是用来「只试一次」的
     * 闸门。把这两个字段清掉只会让下一次调用再走一遍注定失败的加载，
     * 并在日志里留下一条看起来像「第二次尝试新方法」的噪声。
     */
    fun reset() {
        // 加载状态是不可逆的事实，不清。
    }

    /** 首次调用时加载 .so；失败（如在 x86 模拟器上）只记一次日志，后续直接返回 false。 */
    fun ensureLoaded(): Boolean {
        if (available) return true
        if (loadAttempted) return false
        loadAttempted = true
        missingCpuFeatures()?.let { reason ->
            Log.w(TAG, "不加载 libagentkit-llama.so：$reason")
            unavailableReason = reason
            return false
        }
        available = try {
            System.loadLibrary("agentkit-llama")
            // 装原生崩溃捕获：必须在**任何** native 调用之前。
            //
            // 放在 dlopen 成功之后而不是 Application.onCreate，是为了不让启动变慢：
            // 这段逻辑只有在真正要用推理时才会跑到，而崩溃也只可能在这之后发生。
            // 路径由 Application 提前塞进 [crashDir]（那里没有 .so 依赖，可以随便调）。
            installCrashHandler()
            true
        } catch (e: UnsatisfiedLinkError) {
            unavailableReason = "native 库不可用（当前 ABI 未编入？）"
            Log.w(TAG, "libagentkit-llama.so 不可用: ${e.message}")
            false
        }
        return available
    }

    /**
     * .so 按 `armv8.2-a+dotprod+fp16` 编译（见 CMakeLists.txt），在缺这些扩展的
     * 老 CPU 上加载即 SIGILL —— 必须在 dlopen 之前拦下来，否则是崩溃而不是降级。
     *
     * 读不到 /proc/cpuinfo 时返回 null（放行）：宁可让 dlopen 自己报错，
     * 也不要因为一个不可读的伪文件把能用的设备挡在外面。
     */
    private fun missingCpuFeatures(): String? {
        val features = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')?.trim()
                    ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet()
            }
        }.getOrNull()
        if (features.isNullOrEmpty()) return null
        val missing = buildList {
            if ("asimddp" !in features) add("dotprod")
            if ("asimdhp" !in features) add("fp16")
        }
        if (missing.isEmpty()) return null
        return "CPU 缺少 ${missing.joinToString("、")} 指令集，本地推理需 2018 年后机型（骁龙 845 及以上）"
    }

    /**
     * 原生崩溃落盘目录（由 Application 尽早赋值）。
     *
     * 用可变字段而不是构造参数传：这是一次性的进程级路径，传给每个调用方只是噪音。
     * 未赋值时 [installCrashHandler] 直接跳过——宁可没有崩溃记录，也不要因此让推理起不来。
     */
    @Volatile
    var crashDir: String? = null

    @Volatile
    private var crashHandlerReady = false

    /** 安装原生信号处理器（幂等）。 */
    private fun installCrashHandler() {
        if (crashHandlerReady) return
        val dir = crashDir ?: return
        runCatching {
            nativeInstallCrashHandler(dir)
            crashHandlerReady = true
        }.onFailure {
            Log.w(TAG, "原生崩溃捕获安装失败（不影响推理）: ${it.message}")
        }
    }

    /**
     * 把安全模式推给 native。
     *
     * **必须在 [nativeInit] 之前**：n_batch / n_ubatch 是在建 context 时定死的，
     * 装载后再改只能重建上下文（清 KV、几十毫秒）。所以这条配置在启动时就推一次，
     * 而不是每次装载前推——后者会让「用户中途开关安全模式」看起来有时生效有时不生效。
     *
     * native 侧是进程级静态变量，随进程重启而重置，因此每次启动都要重新推。
     */
    fun applySafeMode(on: Boolean) {
        if (!ensureLoaded()) return
        runCatching { nativeSetSafeMode(on) }
            .onFailure { Log.w(TAG, "安全模式推送失败（不影响推理）: ${it.message}") }
    }

    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeChatTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, listener: TokenListener?): String

    /**
     * 取走上一次生成期间「GPU 后端自己抛异常」的记录（取走即清空）。
     *
     * 空串 = 没发生过。非空就是原因，典型是
     * `vk::Device::createComputePipeline: ErrorUnknown`——Adreno 驱动在编译
     * 计算管线时失败，llama.cpp 的 vulkan-hpp 绑定直接 throw。
     *
     * W-87 之前这种异常会穿过 JNI 边界 → terminate → SIGABRT，整个 App 闪退；
     * 现在 native 侧就地接住，这里读到原因就说明**必须降到纯 CPU 重来**。
     */
    fun takeGpuFault(handle: Long): String {
        if (handle == 0L || !ensureLoaded()) return ""
        return runCatching { nativeTakeGpuFault(handle) }.getOrDefault("")
    }

    external fun nativeTakeGpuFault(handle: Long): String

    /**
     * 这个 APK 是否编入了 GPU（Vulkan）后端。由 C++ 编译期宏回答，不是 Kotlin 常量。
     *
     * 先走 [ensureLoaded]：x86 模拟器上库根本不会加载，靠抛 UnsatisfiedLinkError 再吞掉
     * 也能得到 false，但那是「用异常表达正常分支」，日志会很难看，也没法区分
     * 「没编 Vulkan」和「库都没加载」。
     */
    val gpuBackendAvailable: Boolean by lazy {
        if (!ensureLoaded()) return@lazy false
        runCatching { nativeGpuBackend() }.getOrDefault(false)
    }

    /**
     * 安装原生崩溃捕获（见 native-crash.cpp）。
     *
     * @param crashDir 崩溃文件目录的绝对路径（必须已存在；通常就是 `filesDir/crash`）
     */
    external fun nativeInstallCrashHandler(crashDir: String)

    /** 开关安全模式（压缩 n_batch/n_ubatch；必须在 nativeInit 之前调用）。 */
    external fun nativeSetSafeMode(on: Boolean)

    /**
     * 作废绑核投票结论（W-95），下次加载重新投票。
     *
     * 投票结论是进程级的，自愈重建直接沿用；但用户手动改线程数后沿用旧结论等于
     * 「设置没生效」，所以改参数时必须调一次这里。
     */
    external fun nativeResetAffinity()

    external fun nativeGpuBackend(): Boolean

    /**
     * ggml 认得的后端设备清单，如 `GPU 1 / 共 2：GPU:Vulkan0(Adreno (TM) 750) | CPU:NEON`。
     *
     * 这一行是「GPU 到底上没上」的唯一直接证据：Vulkan 初始化失败时 ggml 是**静默**
     * 跳过该后端的（reg_init 里 try/catch 后返回 nullptr），加载仍会成功、只是全程 CPU。
     * 所以它必须能被用户复制出来，而不是只躺在 logcat 里。
     *
     * 首次调用会初始化设备注册表（创建 VkInstance），可能几百毫秒——只在加载模型之后调。
     */
    fun gpuProbe(): String {
        if (!ensureLoaded()) return ""
        return runCatching { nativeGpuProbe() }.getOrDefault("")
    }

    external fun nativeGpuProbe(): String

    /**
     * 系统的 Vulkan 实例版本（VK_MAKE_VERSION 打包值，如 1.3.275 → 4206875），0 = 没有。
     *
     * 由 native 直接 `dlopen("libvulkan.so")` + `vkEnumerateInstanceVersion` 拿到，
     * 与 ggml-vulkan 自己的判据是同一个量——不会出现「我们判可用、它一进初始化就抛」。
     * 用 PackageManager 的 FeatureInfo.version 走过一次弯路：那个字段是 Int，
     * 且各版本 Android 对它的编码不统一。
     */
    val vulkanApiVersion: Int by lazy {
        if (!ensureLoaded()) return@lazy 0
        runCatching { nativeVulkanVersion() }.getOrDefault(0)
    }

    external fun nativeVulkanVersion(): Int

    /**
     * 原生层最近 80 条日志（tokenize 长度、prefill 复用/补算、decode 返回码、
     * 首个 token、空输出原因…），每行格式 `#<seq>|<正文>`。
     *
     * 为什么要这个通道：这些线索原本只在 logcat 里，而用户在手机上排查时没有电脑。
     * 生成结束后无条件读一次写进「运行日志」，用户复制一段文本就能把现场带回来。
     *
     * 序号是给 [nativeLogSeq] 配套用的：缓冲是进程级、跨请求累积的，只有比对序号
     * 才能分清「这一轮产生的行」和「上一轮留下的行」。
     */
    external fun nativeDiagnostics(): String

    /**
     * 当前诊断序号（下一条日志是 seq+1）。
     *
     * 在开始一轮生成前取一次，之后只消费 seq 更大的行 —— 等价于「本轮 native 日志」。
     * 不这么做的话，每一轮都会把上一轮的预热/prefill 行复读一遍：日志里看起来像
     * 「又卡了 13 秒」，看门狗也会拿着上一轮的 warmupMs 去算错误的超时预算。
     */
    external fun nativeLogSeq(): Long
}
