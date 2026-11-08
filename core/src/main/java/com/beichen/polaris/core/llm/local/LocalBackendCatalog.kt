package com.beichen.polaris.core.llm.local

/**
 * 本地推理的「后端目录」：格式 → 后端 → 引擎，三张表加一条降级链。
 *
 * 为什么要单独一个文件，而且是纯 Kotlin（不 import android）：
 *  1. 这里的判定全是字符串与集合运算（文件名、目录内容、设备型号），可以脱离真机在 CI 里断言。
 *     接第二家引擎最容易出错的地方恰恰是这些「看着像常量、其实是规则」的东西。
 *  2. 接 MNN / LiteRT 时，改动集中在这三张表里，不会散进 client、ViewModel、UI。
 *
 * 这套「按能力分档登记后端」的结构，关键是**表里的值必须按本项目自己的构建能力填**：
 * 别人的构建带 OpenCL 就填 GPU 可用，我们得按我们编进 APK 的库来填。
 * 照抄一张写着 true 的表，换来的是一台真机上「点了 GPU，转三分钟，回退 CPU」。
 */

/** 计算后端。三档，降级顺序 NPU → GPU → CPU（越专用越靠前，回退代价也越大）。 */
enum class LocalBackend(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU");

    companion object {
        val FALLBACK_ORDER = listOf(NPU, GPU, CPU)

        fun parse(text: String?): LocalBackend? {
            val t = text?.trim() ?: return null
            for (b in values()) if (b.name.equals(t, ignoreCase = true)) return b
            return null
        }
    }
}

/**
 * 模型格式。取值决定的不只是「用哪个引擎」，还有两件上层不该操心的事：
 *  - 谁负责分词与对话模板（GGUF 用模型自带 template；MNN 读 tokenizer.txt + Jinja；
 *    LiteRT 内部把整段对话交给它自己的 template）；
 *  - KV 缓存的语义（能否公共前缀复用、能否平移）。
 */
enum class LocalModelFormat(val label: String) {
    GGUF("GGUF · llama.cpp"),
    MNN("MNN-LLM"),
    LITERT("LiteRT-LM"),
    QAIRT("GenieX / QAIRT");

    companion object {
        fun parse(text: String?): LocalModelFormat? {
            val t = text?.trim() ?: return null
            for (f in values()) if (f.name.equals(t, ignoreCase = true)) return f
            return null
        }
    }
}

/**
 * 设备能力快照。采集一次、进程内复用（采集本身要读文件系统与 Build 字段，不该每轮问一次）。
 *
 * 两个默认值是有意的「保守」：
 *  - [llamaGpuEnabled] 默认 false —— 这一位必须由调用方用 native 的编译期事实来填
 *    （`LlamaJni.gpuBackendAvailable`），不能在 Kotlin 里写死：写死就会和 CMake 脱节，
 *    结果是界面显示「GPU 可用」、点下去转三分钟再退回 CPU。宁可灰，不可假。
 *  - [hasMnn] / [hasLitert] 默认 false —— 运行时库随构建形态变化，由调用方按 build 配置传入。
 */
data class LocalEngineEnvironment(
    val llamaGpuEnabled: Boolean = false,
    val hasMnn: Boolean = false,
    val hasLitert: Boolean = false,
    val hasGenieX: Boolean = false,
    val hasOpenCl: Boolean = false,
    /** 归一化后的 SoC 基型号（如 sm8750）；null = 取不到（API < 31 或厂商没填）。 */
    val socModel: String? = null,
    /** 高通 NPU 运行库/QNN 是否已在应用目录里就绪。 */
    val npuRuntimeInstalled: Boolean = false,
    /**
     * 设备画像（可选）。给了就把「本机是什么 GPU」写进不可用原因里，而不是让用户猜。
     * null 时行为与之前完全一致——这一层是增量，不是改造。
     */
    val device: LocalDeviceProfile? = null,
    /**
     * 设备侧是否存在可用的 Vulkan 1.2+ 驱动。
     *
     * 和 [llamaGpuEnabled] 是**两件事**：那个问「包里编没编」，这个问「这台机器能不能跑」。
     * 只在拿不到设备画像时用作兜底判断；有画像时以 [LocalDeviceProfile.vulkanVersion]
     * 为准（画像给得出具体版本号，能解释「为什么不行」）。
     */
    val hasVulkan: Boolean = false,
)

/** 一个后端对这个模型「能不能用」，不能用时必须给出给人看的原因。 */
data class LocalBackendOption(
    val backend: LocalBackend,
    val available: Boolean,
    /** null 表示可用。给用户看的短句，会直接进错误面板。 */
    val reason: String? = null,
    /** 可用时的补充说明（走哪条内核 / 有什么代价）。 */
    val detail: String = "",
)

/**
 * 引擎能力位。上层（参数面板、上下文裁剪、重试策略）只查这些字段，不写 `if (engine == MNN)`。
 *
 * 这张表比后端表更重要：三家的「同名参数」并不等价。MNN 的 thread_num 在 OpenCL 上是
 * 调优模式位（68 = BUFFER|WIDE），LiteRT 的 minP 根本不存在，GGUF 能做上下文平移而另外两家
 * 只能截断。把这些差异写成布尔值，上层才有依据降级，而不是透传之后静默变差。
 */
data class LocalEngineFeatures(
    /** 支持上下文平移（挤掉最老的 token，而不是直接停）。 */
    val contextShift: Boolean = false,
    /** KV 量化（Q8_0 / Q4_0，需要 Flash Attention）。 */
    val kvQuant: Boolean = false,
    /** 权重内存映射（决定「加载快但首字慢」还是「加载慢但首字快」）。 */
    val mmap: Boolean = false,
    val minP: Boolean = true,
    val repeatPenalty: Boolean = true,
    val presenceFrequency: Boolean = false,
    val seed: Boolean = false,
    val batchSize: Boolean = false,
    /** 回复过程中改线程数无需重载。 */
    val liveThreads: Boolean = false,
    /** 上下文长度由模型文件写死（NPU 编译版），非 null 时上层不得再改。 */
    val fixedContext: Int? = null,
    /**
     * 引擎自己能按最长公共 token 前缀复用 KV（新对话不必重建 system prompt 的 KV）。
     * 只有 llama.cpp 是 true；另外两家只能精确续接，新对话必须显式 reset。
     */
    val reusesCommonPrefix: Boolean = false,
)

object LocalEngineCatalog {

    // ---------------------------------------------------------------- 格式识别

    /**
     * 从「文件名 + 是否目录 + 目录里的条目名」判断格式。
     *
     * 之所以把目录条目作为参数传进来而不是自己去读：判定规则要能在 CI 里跑。
     * 真实调用方传 File 的 list()，测试传几个字符串。
     */
    fun detectFormat(fileName: String, isDirectory: Boolean, entries: Set<String> = emptySet()): LocalModelFormat? {
        val name = fileName.lowercase()
        if (isDirectory) {
            // MNN-LLM 的模型是一个文件夹：config.json + llm.mnn + tokenizer.txt。
            // 只认前两个就够——tokenizer.txt 有的仓库没带（靠 config.json 里的 vocab 兜底）。
            if ("config.json" in entries && "llm.mnn" in entries) return LocalModelFormat.MNN
            // QAIRT / AI Hub 导出包：genie_config.json 是它的入口标志。
            if ("genie_config.json" in entries) return LocalModelFormat.QAIRT
            return null
        }
        return when {
            name.endsWith(".gguf") -> LocalModelFormat.GGUF
            name.endsWith(".litertlm") -> LocalModelFormat.LITERT
            name.endsWith(".mnn") -> LocalModelFormat.MNN
            else -> null
        }
    }

    /**
     * LiteRT 的 NPU 编译版文件名里带 SoC 型号（`..._qualcomm_sm8750.litertlm`）。
     * 这种模型是**按芯片 AOT 编译**的，图里写死了 Hexagon 的指令，换芯片就是一堆无效算子。
     */
    fun npuTarget(fileName: String): String? {
        val m = Regex("[._-](sm\\d{4})", RegexOption.IGNORE_CASE).find(fileName) ?: return null
        return m.groupValues[1].lowercase()
    }

    /** NPU 编译版把 KV 容量写进文件名（`_ekv1280`），这是上限，不是建议值。 */
    fun npuKvCapacity(fileName: String): Int? {
        val m = Regex("ekv(\\d+)", RegexOption.IGNORE_CASE).find(fileName) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it >= 128 }
    }

    /** SoC 型号比对：SM8750P / SM8750-AB / sm8750 视为同一颗。 */
    fun socMatches(target: String, actual: String?): Boolean {
        val a = actual?.trim()?.lowercase() ?: return false
        if (a == target) return true
        return a.startsWith(target) || target.startsWith(a)
    }

    // ---------------------------------------------------------------- 后端可用性

    /**
     * 「包里没编 Vulkan」时，再补一句「你这台机器开了能不能用」。
     *
     * 只写「当前构建未启用」是实话，但对用户没用——他还想知道值不值得等一个能开 GPU 的版本。
     * 判定复用 [LocalDeviceRules]，这里只拼句子，不重复写规则。
     */
    private fun buildOffHint(advice: DeviceAdvice): String {
        val gpu = advice.gpuModel ?: advice.gpu.label
        return when (advice.gpuPath) {
            GpuPath.STABLE -> "本机 $gpu，开启 Vulkan 后可直接用"
            GpuPath.RISKY -> "本机 $gpu（${advice.vendor.label}），Vulkan 需按机型验证"
            GpuPath.NONE -> "本机 $gpu：${advice.gpuPathReason ?: "GPU 不可用"}"
        }
    }

    /**
     * GPU 这个选项到底给不给。
     *
     * 三个前提依次成立才算可用，任一不成立都要说清楚是哪一条：
     *  1. **包里编了 Vulkan**（[LocalEngineEnvironment.llamaGpuEnabled]，编译期事实）；
     *  2. **这台机器能跑**（Vulkan ≥ 1.2 且不是被劝退的 GPU 家族，见 `LocalDeviceRules.vulkanPath`）；
     *  3. 用户没在设置里关掉（这一条由调用方在装载时判，选项表只回答「能不能」）。
     */
    private fun gpuOption(env: LocalEngineEnvironment): LocalBackendOption {
        val advice = env.device?.let { LocalDeviceRules.advise(it) }
        if (!env.llamaGpuEnabled) {
            val base = "当前构建的 llama.cpp 只编了 CPU 后端（Vulkan / OpenCL 未启用）"
            return LocalBackendOption(
                LocalBackend.GPU, false,
                reason = advice?.let { "$base；${buildOffHint(it)}" } ?: base,
            )
        }
        if (advice == null) {
            // 没有画像时只能靠调用方给的布尔兜底。文案要说清这是「没探测到」，不是「不支持」。
            return if (env.hasVulkan) {
                LocalBackendOption(LocalBackend.GPU, true, detail = "Vulkan（未取得 GPU 型号）")
            } else {
                LocalBackendOption(LocalBackend.GPU, false, reason = "未探测到 Vulkan 1.2+ 驱动，本机只能用 CPU")
            }
        }
        val gpu = advice.gpuModel ?: advice.gpu.label
        val ver = advice.vulkanVersion ?: "?"
        return when (advice.gpuPath) {
            GpuPath.STABLE -> LocalBackendOption(
                LocalBackend.GPU, true, detail = "$gpu · Vulkan $ver",
            )
            GpuPath.RISKY -> LocalBackendOption(
                LocalBackend.GPU, false,
                reason = "$gpu · Vulkan $ver 能跑，但驱动在长时间推理后可能中断；" +
                    "需要的话可在「推理参数 › GPU 加速」里选「强制开启」",
            )
            GpuPath.NONE -> LocalBackendOption(
                LocalBackend.GPU, false,
                reason = "$gpu：${advice.gpuPathReason ?: "GPU 不可用"}",
            )
        }
    }

    fun options(
        format: LocalModelFormat,
        fileName: String,
        env: LocalEngineEnvironment,
    ): List<LocalBackendOption> = when (format) {
        LocalModelFormat.GGUF -> listOf(
            LocalBackendOption(LocalBackend.CPU, true, detail = "llama.cpp · NEON + dotprod"),
            gpuOption(env),
            LocalBackendOption(
                LocalBackend.NPU, false,
                reason = "llama.cpp 没有高通 NPU 后端；NPU 需要按芯片编译的 .litertlm 模型",
            ),
        )

        LocalModelFormat.MNN -> if (!env.hasMnn) {
            listOf(
                LocalBackendOption(LocalBackend.CPU, false, "当前构建未编入 MNN 运行时"),
                LocalBackendOption(LocalBackend.GPU, false, "当前构建未编入 MNN 运行时"),
                LocalBackendOption(LocalBackend.NPU, false, "当前构建未编入 MNN 运行时"),
            )
        } else listOf(
            LocalBackendOption(LocalBackend.CPU, true, detail = "MNN-LLM · ARMv8.2 FP16"),
            if (env.hasOpenCl) {
                LocalBackendOption(LocalBackend.GPU, true, detail = "Adreno · OpenCL（首字更快，长文更慢）")
            } else {
                LocalBackendOption(LocalBackend.GPU, false, reason = "本机没有可用的 OpenCL 驱动")
            },
            LocalBackendOption(
                LocalBackend.NPU, false,
                reason = "MNN 的 QNN 后端需要按芯片离线转换的专用模型，当前构建未编入",
            ),
        )

        LocalModelFormat.LITERT -> if (!env.hasLitert) {
            listOf(
                LocalBackendOption(LocalBackend.CPU, false, "当前构建未编入 LiteRT-LM"),
                LocalBackendOption(LocalBackend.GPU, false, "当前构建未编入 LiteRT-LM"),
                LocalBackendOption(LocalBackend.NPU, false, "当前构建未编入 LiteRT-LM"),
            )
        } else {
            val target = npuTarget(fileName)
            if (target != null) {
                // AOT 编译：图只能在同一个 NPU 上跑，CPU / GPU 没有对应的算子实现。
                val onlyNpu = "这是 ${target.uppercase()} NPU 专用编译的模型，只能在 NPU 上运行"
                val npuReason = when {
                    env.socModel == null -> "取不到本机芯片型号，无法确认是否为 ${target.uppercase()}"
                    !socMatches(target, env.socModel) ->
                        "模型为 ${target.uppercase()} 编译，本机是 ${env.socModel.uppercase()}"
                    !env.npuRuntimeInstalled -> "需要先下载 NPU 运行库（设置 › 本地模型）"
                    else -> null
                }
                listOf(
                    LocalBackendOption(LocalBackend.CPU, false, onlyNpu),
                    LocalBackendOption(LocalBackend.GPU, false, onlyNpu),
                    LocalBackendOption(LocalBackend.NPU, npuReason == null, npuReason, "Hexagon · QNN"),
                )
            } else listOf(
                LocalBackendOption(LocalBackend.CPU, true, detail = "XNNPACK"),
                LocalBackendOption(
                    LocalBackend.GPU, true,
                    detail = "Adreno · ML Drift（首次加载要编译着色器，可能几分钟）",
                ),
                LocalBackendOption(
                    LocalBackend.NPU, false,
                    reason = "通用 .litertlm 不能上 NPU；需要带 _${env.socModel ?: "smXXXX"} 的 NPU 编译版",
                ),
            )
        }

        LocalModelFormat.QAIRT -> listOf(
            LocalBackendOption(LocalBackend.CPU, false, "QAIRT 包绑定 Hexagon，不能回退到 CPU"),
            LocalBackendOption(LocalBackend.GPU, false, "QAIRT 包绑定 Hexagon，不能回退到 GPU"),
            LocalBackendOption(
                LocalBackend.NPU, env.hasGenieX && env.npuRuntimeInstalled,
                if (env.hasGenieX) "需要先下载 GenieX / QAIRT 运行库" else "当前构建未编入 GenieX 运行时",
                "Hexagon · QAIRT",
            ),
        )
    }

    // ---------------------------------------------------------------- 能力位

    fun features(format: LocalModelFormat, fileName: String): LocalEngineFeatures = when (format) {
        LocalModelFormat.GGUF -> LocalEngineFeatures(
            contextShift = true,
            kvQuant = true,
            mmap = true,
            minP = true,
            repeatPenalty = true,
            presenceFrequency = true,
            seed = true,
            batchSize = true,
            liveThreads = true,
            reusesCommonPrefix = true,
        )
        // MNN 的 prompt cache 只能精确续接，位置不能平移；采样侧只认 temperature/topP/minP/repeat。
        LocalModelFormat.MNN -> LocalEngineFeatures(mmap = true, minP = true, repeatPenalty = true)
        // LiteRT 的采样由运行时决定，minP / 重复惩罚都传不进去；KV 由运行时管理。
        LocalModelFormat.LITERT -> LocalEngineFeatures(
            minP = false,
            repeatPenalty = false,
            fixedContext = npuTarget(fileName)?.let { npuKvCapacity(fileName) },
        )
        LocalModelFormat.QAIRT -> LocalEngineFeatures(
            minP = false,
            repeatPenalty = false,
            fixedContext = npuKvCapacity(fileName),
        )
    }

    /** 上下文放不下时要告诉用户「为什么不能平移」，而不是只说一句截断。 */
    fun contextShiftUnsupportedReason(format: LocalModelFormat?): String? = when (format) {
        LocalModelFormat.GGUF, null -> null
        LocalModelFormat.LITERT -> "LiteRT-LM 的会话 KV 由运行时管理，不支持上下文平移；将按 token 预算截断"
        LocalModelFormat.MNN -> "MNN 的 prompt cache 不支持位置平移；将按 token 预算截断"
        LocalModelFormat.QAIRT -> "QAIRT 包的 KV 在编译时固定，不支持上下文平移；将按 token 预算截断"
    }

    /** 格式 + 后端 → 引擎名。只有 GGUF 有两种选择（llama.cpp / GenieX），其余一对一。 */
    fun engineFor(format: LocalModelFormat, backend: LocalBackend): String = when (format) {
        LocalModelFormat.GGUF -> if (backend == LocalBackend.NPU && hasGenieXForGguf) ENGINE_GENIEX else ENGINE_LLAMA
        LocalModelFormat.MNN -> ENGINE_MNN
        LocalModelFormat.LITERT -> ENGINE_LITERT
        LocalModelFormat.QAIRT -> ENGINE_GENIEX
    }

    const val ENGINE_LLAMA = "llama.cpp"
    const val ENGINE_MNN = "MNN-LLM"
    const val ENGINE_LITERT = "LiteRT-LM"
    const val ENGINE_GENIEX = "GenieX"

    /**
     * GGUF 走不走 GenieX。默认 false：GenieX 是另一套 AAR（80MB 级）与另一套运行时下载流程，
     * 只有在确实要跑 NPU 时才接。写成常量而不是参数，是为了让「接没接」在代码里只有一个答案。
     */
    private const val hasGenieXForGguf = false

    // ---------------------------------------------------------------- 装载计划

    /**
     * 一次装载的完整计划。
     *
     * [chain] 是要按顺序尝试的后端；[notes] 是「首选项为什么没被用上」的人话说明。
     * 后者不是日志噪声——静默降级是这类应用最招人恨的行为：用户选了 GPU，跑了两周才发现一直在 CPU 上。
     */
    data class LoadPlan(
        val chain: List<LocalBackend>,
        val notes: List<String>,
    )

    fun planLoad(
        preferred: LocalBackend,
        options: List<LocalBackendOption>,
        crashed: Set<LocalBackend> = emptySet(),
    ): LoadPlan {
        val notes = ArrayList<String>(2)
        val byBackend = options.associateBy { it.backend }
        byBackend[preferred]?.let { opt ->
            if (!opt.available) {
                notes += "${preferred.label}：${opt.reason ?: "不可用"}"
            }
        }
        if (preferred in crashed) {
            notes += "${preferred.label}：上次在这个后端上加载或首次生成时进程崩溃，已跳过"
        }
        val available = options.filter { it.available }.map { it.backend }.toSet()
        val start = LocalBackend.FALLBACK_ORDER.indexOf(preferred).coerceAtLeast(0)
        val chain = LocalBackend.FALLBACK_ORDER
            .drop(start)
            .filter { it in available && it !in crashed }
        // 全崩过也别拒绝加载：退回链尾最稳的那个（通常是 CPU），让用户至少能出字。
        if (chain.isEmpty()) {
            val last = LocalBackend.FALLBACK_ORDER.drop(start).filter { it in available }.lastOrNull()
            if (last != null) {
                notes += "所有后端都曾崩溃，仍尝试 ${last.label}"
                return LoadPlan(listOf(last), notes)
            }
        }
        return LoadPlan(chain, notes)
    }

    /** 首选项本身可用、且没崩过时，一次就中——这条路径不该产生任何 note。 */
    fun isDirectHit(plan: LoadPlan, preferred: LocalBackend): Boolean =
        plan.chain.firstOrNull() == preferred && plan.notes.isEmpty()
}
