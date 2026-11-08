package com.beichen.polaris.core.llm.local

/**
 * 设备画像：把「这台手机是什么芯片、什么 GPU、多少内存」翻译成「它该走哪条推理路」。
 *
 * 为什么需要这一层，而不是在 UI 里写 `if (Build.SOC_MODEL == "SM8650")`：
 *
 *  1. NPU 路线天生「一机一包」。LiteRT 的 QNN 模型是按具体 DSP 版本 AOT 编译的，
 *     换一颗芯片直接 `Failed to create QNN context: 1002`。Google 官方支持列表从
 *     SM8450（8 Gen 1）开始——也就是说 8 Gen 1 以下、以及全部天玑/麒麟/紫光，
 *     NPU 这条路根本不存在，不是「没适配好」。
 *  2. 所以「覆盖大多数人」的那条路不是 NPU，是 GPU（Vulkan）与 CPU。
 *     而 GPU 的可用性又分三档：Adreno 成熟、Mali 需按机型验证、PowerVR 基本放弃。
 *  3. 这三件事都是字符串判定，可以脱离真机在 CI 里断言。放这里，不散进 UI。
 *
 * 本文件纯 Kotlin，不 import android：调用方把 Build.* 与 GL_RENDERER 采好传进来。
 */
enum class SocVendor(val label: String) {
    QUALCOMM("高通骁龙"),
    MEDIATEK("联发科天玑"),
    KIRIN("海思麒麟"),
    SAMSUNG("三星 Exynos"),
    GOOGLE("Google Tensor"),
    UNISOC("紫光展锐"),
    UNKNOWN("未知");
}

/**
 * GPU 的 Vulkan 成熟度。用来决定「默认开不开 GPU」，不是用来排序性能。
 *
 * GOOD   —— 同一份代码在 Adreno 6xx/7xx 上表现一致，可以默认启用。
 * MIXED  —— 厂商驱动差异大，能跑但需要按机型白名单，默认关、用户可手动开。
 * POOR   —— 驱动长期不更新，跑大模型容易 device lost，不建议开。
 */
enum class VulkanMaturity { GOOD, MIXED, POOR, UNKNOWN }

enum class GpuFamily(val label: String, val maturity: VulkanMaturity) {
    ADRENO("Adreno", VulkanMaturity.GOOD),
    MALI("Mali", VulkanMaturity.MIXED),
    IMMORTALIS("Immortalis", VulkanMaturity.MIXED),
    XCLIPSE("Xclipse", VulkanMaturity.MIXED),
    POWERVR("PowerVR", VulkanMaturity.POOR),
    UNKNOWN("未知", VulkanMaturity.UNKNOWN);
}

/**
 * 设备档位。只由「内存 + SoC 代际」决定，不看跑分——
 * 跑分决定的是「快多少」，内存决定的是「能不能装下」，后者才是硬门槛。
 */
enum class DeviceTier(val label: String) {
    ENTRY("入门（4GB 级）"),
    LOW("中低端（6GB 级）"),
    MID("中高端（8GB 级）"),
    HIGH("旗舰（12GB+）"),
}

/**
 * GPU 这条路的三态。
 *
 * 为什么不是布尔：Mali 的 Vulkan 驱动在中文 ROM 上「能跑但会在长时间推理后
 * VK_ERROR_DEVICE_LOST」，布尔表达不了这种状态。三态才能让策略是
 * 「Adreno 默认开 / Mali 默认关但可手开 / PowerVR 直接不给开后门」。
 */
enum class GpuPath(val label: String) {
    STABLE("可用"),
    RISKY("可用但需按机型验证"),
    NONE("不可用"),
}

/**
 * 用户的 GPU 意愿。三档而不是布尔，因为「默认」与「我要关」是两件事：
 *
 *  - [AUTO]：交给规则判。Adreno + Vulkan 1.2 才上，Mali 之类一律 CPU。
 *  - [FORCE]：明知有风险也用。这是用户看过提示之后的显式选择，我们只负责记进
 *    崩溃记忆，不再替他拦。
 *  - [OFF]：永远不用。有些机型 GPU 能跑但更费电/更热，用户有权拒绝。
 */
enum class GpuMode(val label: String) {
    AUTO("自动"),
    FORCE("强制开启"),
    OFF("关闭");

    companion object {
        fun parse(text: String?): GpuMode =
            values().firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * 设备快照。字段全部允许为 null：Android 12 以下拿不到 SOC_MODEL，
 * 部分厂商 ROM 会把 SOC_MANUFACTURER 填成空串。取不到时按 GPU 与内存兜底。
 *
 * @param glRenderer GL_RENDERER 原始串，如 "Adreno (TM) 740" / "Mali-G77 MC9"。
 *   这是所有 Android 设备都能拿到的，比 SOC_MODEL 可靠得多。
 * @param vulkanVersion 系统报告的 Vulkan 版本，如 "1.3" / "1.1"。
 *   ggml-vulkan 的硬门槛是 **1.2**（源码里 api_version < VK_API_VERSION_1_2 直接抛），
 *   所以这个字段不是「参考信息」，是「能不能开 GPU」的决定项。
 */
data class LocalDeviceProfile(
    val socModel: String? = null,
    val socManufacturer: String? = null,
    val hardware: String? = null,
    val glRenderer: String? = null,
    val sdkInt: Int = 0,
    val totalRamMb: Int = 0,
    val vulkanVersion: String? = null,
)

/** 一台设备最终该走哪条路，以及为什么。 */
data class DeviceAdvice(
    val tier: DeviceTier,
    val vendor: SocVendor,
    val gpu: GpuFamily,
    /** 归一化后的 GPU 名，如 "Adreno 740" / "Mali-G77"；取不到为 null。 */
    val gpuModel: String?,
    /** GPU 这条路的三态（STABLE / RISKY / NONE），比 preferredPath 更细。 */
    val gpuPath: GpuPath,
    /** 为什么是这三态之一；STABLE 时为 null。这句话会直接进用户能看到的日志。 */
    val gpuPathReason: String?,
    /** 系统报告的 Vulkan 版本；null = 未报告。 */
    val vulkanVersion: String?,
    /** NPU 是否真的能用（不是「有没有 NPU」——天玑也有 APU，但我们的构建用不了）。 */
    val npuUsable: Boolean,
    val npuReason: String?,
    /** 首选路径。降级顺序仍由 LocalEngineCatalog.planLoad 决定。 */
    val preferredPath: LocalBackend,
    /** 这台机器适合的模型量级，直接进设置页文案。 */
    val modelBudget: String,
    /** 权重 + KV 的估算峰值，超过它就该劝用户换小模型。 */
    val memoryBudgetMb: Int,
    val notes: List<String>,
)

object LocalDeviceRules {

    // ---------------------------------------------------------------- SoC 归一化

    /**
     * 高通型号里 `sm` 后面四位是档位信号：
     *   8xxx = 旗舰（8450=8Gen1, 8550=8Gen2, 8650=8Gen3, 8750=8Elite）
     *   7xxx = 中高端（7675/7750 = 7 系）
     *   6xxx = 中低端（6375 = 骁龙 695）
     * 用档位而不是硬编码型号表，是为了让还没发布的芯片也能落进正确的档。
     */
    fun qualcommTier(soc: String?): Int? {
        val s = soc?.trim()?.lowercase() ?: return null
        val m = Regex("sm(\\d{4})").find(s) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return n / 1000
    }

    /**
     * NPU 能不能用。判定条件是「厂商 + 代际」，不是「有没有 NPU」。
     *
     * 天玑有 APU、麒麟有达芬奇，但 LiteRT 要在上面跑需要各自的 AOT 转换链
     * （NeuroPilot / 达芬奇工具链），我们没有这条链，等于没有。所以这里对
     * 非高通一律返回 false，理由写清楚，而不是含糊地说「不支持」。
     *
     * 高通也要 8 Gen 1（sm8450）起：QNN 的 HTP delegate 官方支持列表从这里开始。
     */
    fun npuUsable(profile: LocalDeviceProfile): Pair<Boolean, String?> {
        val vendor = vendorOf(profile)
        if (vendor != SocVendor.QUALCOMM) {
            val reason = when (vendor) {
                SocVendor.MEDIATEK -> "天玑的 APU 需要 NeuroPilot 单独转换模型，当前构建用不了"
                SocVendor.KIRIN -> "麒麟的达芬奇 NPU 没有公开的转换链，用不了"
                SocVendor.SAMSUNG -> "Exynos 的 NPU 没有对应的 LiteRT 委托，用不了"
                SocVendor.GOOGLE -> "Tensor 的 NPU 需要专用编译版模型，当前构建用不了"
                SocVendor.UNISOC -> "紫光展锐没有可用的 NPU 委托"
                else -> "无法识别芯片厂商，NPU 不可用"
            }
            return false to reason
        }
        // 型号可能只在 SOC_MODEL 或只在 HARDWARE 里，两个都翻，取第一个能解析的。
        val socText = listOfNotNull(profile.socModel, profile.hardware).joinToString(" ").lowercase()
        val m = Regex("sm(\\d{4})").find(socText)
            ?: return false to "取不到骁龙具体型号（Android 11 及以下不给），无法确认 Hexagon 版本"
        val n = m.groupValues[1].toIntOrNull()
            ?: return false to "骁龙型号无法解析，无法确认 Hexagon 版本"
        if (n / 1000 < 8) return false to "这颗骁龙不是 8 系，Hexagon 不在 LiteRT 支持列表内"
        if (n < 8450) return false to "Hexagon 版本低于 v69（8 Gen 1 起才支持），NPU 用不了"
        return true to null
    }

    // ---------------------------------------------------------------- GPU 归一化

    /**
     * 从 GL_RENDERER 解析 GPU 家族与型号。
     *
     * 真实串长这样（各厂商写法不统一，所以用宽松匹配）：
     *   "Adreno (TM) 740"、"Adreno (TM) 640"、"Mali-G77 MC9"、"Mali-G610 MC6"、
     *   "Immortalis-G715 MC11"、"PowerVR Rogue GE8320"、"Xclipse 920"、
     *   联发科部分机型是 "Mali-G57 MC2" 带空格，三星是 "Mali-G78 MP14"（MP 不是 MC）。
     */
    fun gpuOf(renderer: String?): Pair<GpuFamily, String?> {
        val r = renderer?.trim() ?: return GpuFamily.UNKNOWN to null
        if (r.isEmpty()) return GpuFamily.UNKNOWN to null
        val lower = r.lowercase()

        if (lower.contains("adreno")) {
            val m = Regex("adreno\\D{0,6}(\\d{3})").find(lower)
            val model = m?.let { "Adreno ${it.groupValues[1]}" } ?: "Adreno"
            return GpuFamily.ADRENO to model
        }
        if (lower.contains("immortalis")) {
            val m = Regex("immortalis-?(g\\d{3})").find(lower)
            val model = m?.let { "Immortalis-${it.groupValues[1].uppercase()}" } ?: "Immortalis"
            return GpuFamily.IMMORTALIS to model
        }
        if (lower.contains("mali")) {
            // G77 / G610 / G715：先抓 G 开头的三位数，抓不到就只认家族。
            val m = Regex("mali-?(g\\d{2,3})").find(lower)
            val model = m?.let { "Mali-${it.groupValues[1].uppercase()}" } ?: "Mali"
            return GpuFamily.MALI to model
        }
        if (lower.contains("powervr") || lower.contains("rogue")) {
            val m = Regex("(ge\\d{4})").find(lower)
            val model = m?.let { "PowerVR ${it.groupValues[1].uppercase()}" } ?: "PowerVR"
            return GpuFamily.POWERVR to model
        }
        if (lower.contains("xclipse")) {
            val m = Regex("xclipse\\D{0,3}(\\d{3})").find(lower)
            val model = m?.let { "Xclipse ${it.groupValues[1]}" } ?: "Xclipse"
            return GpuFamily.XCLIPSE to model
        }
        return GpuFamily.UNKNOWN to null
    }

    // ---------------------------------------------------------------- 厂商归一化

    /**
     * 厂商判定走三路兜底，因为 Build.* 三个字段各有各的空法：
     *  - SOC_MANUFACTURER（API 31+）：QTI / MediaTek / HiSilicon / samsung / Google
     *  - SOC_MODEL（API 31+）：SM8650 / MT6989 / Tensor G3
     *  - HARDWARE：qcom / mt6893 / kirin9000 / exynos990 / tensor
     * 最后再看 GPU：Adreno 必然是骁龙，Mali 是「非高通」的强信号（但不能反推出天玑）。
     */
    fun vendorOf(profile: LocalDeviceProfile): SocVendor {
        val blob = listOfNotNull(
            profile.socManufacturer, profile.socModel, profile.hardware,
        ).joinToString(" ").lowercase()

        when {
            blob.contains("qti") || blob.contains("qualcomm") || blob.contains("qcom") -> return SocVendor.QUALCOMM
            blob.contains("mediatek") || blob.contains("mtk") -> return SocVendor.MEDIATEK
            blob.contains("hisilicon") || blob.contains("kirin") || blob.contains("hi3") -> return SocVendor.KIRIN
            blob.contains("samsung") || blob.contains("exynos") -> return SocVendor.SAMSUNG
            blob.contains("google") || blob.contains("tensor") -> return SocVendor.GOOGLE
            blob.contains("unisoc") || blob.contains("spreadtrum") -> return SocVendor.UNISOC
            Regex("\\bsm\\d{4}").containsMatchIn(blob) -> return SocVendor.QUALCOMM
            Regex("\\bmt\\d{4}").containsMatchIn(blob) -> return SocVendor.MEDIATEK
        }
        // 兜底：只有 Adreno 能反过来定厂商。Mali 只说明「不是高通」，不足以定位到天玑。
        val (gpu, _) = gpuOf(profile.glRenderer)
        if (gpu == GpuFamily.ADRENO) return SocVendor.QUALCOMM
        return SocVendor.UNKNOWN
    }

    // ---------------------------------------------------------------- 档位与预算

    /**
     * 内存分档。阈值不是拍的，是按「权重 + KV + 计算缓冲」反推的：
     * 3B Q4_K_M 权重约 1.9GB，4096 上下文再加 0.6~0.9GB，系统与 App 本身还要 1.5GB 左右。
     * 所以 6GB 机器跑 3B 是「刚好卡住」，8GB 才叫舒适。
     */
    fun tierOf(profile: LocalDeviceProfile, vendor: SocVendor = vendorOf(profile)): DeviceTier {
        val ram = profile.totalRamMb
        // 内存取不到时不要乐观：按中低端处理，宁可少推一个模型。
        if (ram <= 0) return DeviceTier.LOW
        if (ram < 4500) return DeviceTier.ENTRY
        if (ram < 7500) return DeviceTier.LOW
        if (ram < 11500) {
            // 8GB 机器：认得出的两家才叫 MID，其余算 LOW 的上沿（同样 8GB，闪存与带宽差很多）。
            return if (vendor == SocVendor.QUALCOMM || vendor == SocVendor.MEDIATEK) {
                DeviceTier.MID
            } else {
                DeviceTier.LOW
            }
        }
        return DeviceTier.HIGH
    }

    /** 这台机器建议的模型量级与峰值预算（MB）。 */
    fun modelBudget(tier: DeviceTier): Pair<String, Int> = when (tier) {
        DeviceTier.ENTRY -> "0.5B~1B（Q4）" to 1800
        DeviceTier.LOW -> "1B~1.5B（Q4）" to 2800
        DeviceTier.MID -> "1.5B~3B（Q4）" to 4200
        DeviceTier.HIGH -> "3B~4B（Q4），7B 勉强" to 7000
    }

    // ---------------------------------------------------------------- 推荐路径

    /**
     * GPU（Vulkan）这条路到底能不能走，以及为什么。
     *
     * 判定顺序是刻意的：**先看版本，再看家族**。
     *
     * ggml-vulkan 的源码里写死了 `if (api_version < VK_API_VERSION_1_2) throw`，
     * 也就是 Android 10/11 那些只给 Vulkan 1.1 的驱动连初始化都过不去。先判家族
     * 再判版本的话，会出现「骁龙 888 + Adreno 660 判定为可用，实际一开就抛」——
     * 这正是「看起来支持其实不支持」的典型错法。
     */
    /**
     * 把 `VK_MAKE_VERSION` 的打包值转成 `"1.3"` / `"1.1"`，0 或异常值返回 null。
     *
     * Vulkan 的版本编码是 `major << 22 | minor << 12 | patch`，不是十进制拼接。
     * 按十进制去解析会得到天文数字，然后被判成「版本足够高」——正好反了。
     */
    fun formatVulkanVersion(packed: Int): String? {
        if (packed <= 0) return null
        val major = packed ushr 22
        val minor = (packed ushr 12) and 0x3FF
        // major 大于 10 的打包值不可能来自真实 loader，视为脏数据（防止把 -1 之类当成高版本）。
        if (major <= 0 || major > 10) return null
        return "$major.$minor"
    }

    fun vulkanPath(profile: LocalDeviceProfile): Pair<GpuPath, String?> {
        val (gpu, _) = gpuOf(profile.glRenderer)
        if (gpu == GpuFamily.UNKNOWN) {
            return GpuPath.NONE to "取不到 GPU 型号，无法判断驱动支持情况"
        }
        val raw = profile.vulkanVersion?.trim()
        if (raw.isNullOrBlank()) {
            return GpuPath.NONE to "系统未报告 Vulkan 版本，按不支持处理"
        }
        val ver = raw.removePrefix("Vulkan ").trim()
        val major = ver.substringBefore('.').toIntOrNull()
            ?: return GpuPath.NONE to "Vulkan 版本无法解析：$raw"
        val minor = ver.substringAfter('.', "0").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        if (major < 1 || (major == 1 && minor < 2)) {
            return GpuPath.NONE to "本机 Vulkan $ver，ggml-vulkan 要求 1.2"
        }
        return when (gpu.maturity) {
            // 成熟度表已经按家族填好了（Adreno GOOD / Mali·Immortalis·Xclipse MIXED /
            // PowerVR POOR），这里只做「成熟度 → 三态」的翻译。
            // 不在这里另写一份家族分支：两份表早晚会不一致，而且不一致时没人会注意到。
            VulkanMaturity.GOOD -> GpuPath.STABLE to null
            VulkanMaturity.MIXED -> GpuPath.RISKY to null
            VulkanMaturity.POOR -> GpuPath.NONE to
                "${gpu.label} 驱动长期不更新，长时间推理容易中断"
            VulkanMaturity.UNKNOWN -> GpuPath.NONE to "取不到 GPU 型号"
        }
    }

    /**
     * 一次装载里 GPU 到底用不用、用几层、为什么不用。
     *
     * [layers] = 0 表示纯 CPU；[reason] 非 null 时一定要打日志——「用户以为在用 GPU，
     * 其实一直跑在 CPU 上」是这类应用最隐蔽也最招人恨的故障。
     */
    data class GpuPlan(val layers: Int, val reason: String?) {
        val usingGpu: Boolean get() = layers > 0
    }

    /**
     * 决定要不要把层卸载到 GPU。
     *
     * 三档 [GpuMode] 而不是一个布尔开关，是因为「默认」必须能区分「我不想用」和
     * 「我不知道该不该用」：前者是用户的决定，后者该由规则替他判断。
     *
     * 注意 [compiled] 是「安装包里有没有 Vulkan 后端」，来自 native 的编译期宏；
     * [advice] 是「这台设备能不能跑」。两个条件互不替代——一个都不能少。
     */
    fun resolveGpu(advice: DeviceAdvice, compiled: Boolean, mode: GpuMode): GpuPlan {
        if (mode == GpuMode.OFF) return GpuPlan(0, "已在设置里关闭 GPU")
        if (!compiled) return GpuPlan(0, "当前安装包未编入 Vulkan 后端（只用 CPU）")
        when (advice.gpuPath) {
            GpuPath.NONE -> return GpuPlan(0, "GPU 不可用：${advice.gpuPathReason ?: "原因未知"}")
            GpuPath.RISKY -> if (mode != GpuMode.FORCE) {
                return GpuPlan(0, "GPU 需按机型验证（${advice.gpuPathReason ?: advice.gpu.label}）；自动模式只用 CPU")
            }
            GpuPath.STABLE -> Unit
        }
        return GpuPlan(GPU_MAX_LAYERS, null)
    }

    /**
     * 「Adreno 驱动 + Q4_K 量化」是必崩组合；命中就返回要绕开 GPU 的理由。
     *
     * 依据是 llama.cpp issue #29786：Adreno 驱动在创建 `mul_mat_vec_q4_k_*` 的计算
     * 管线时直接返回 VK_ERROR_UNKNOWN，vulkan-hpp 绑定把它 throw 出来，ggml 不接，
     * 异常穿过 JNI 边界就是 SIGABRT —— 用户侧的表现是「发一句话，App 直接消失」。
     *
     * 真机 2026-10-29（Adreno 740 / Vulkan 1.4 / qwen2.5-3b-instruct-q4_k_m / 99 层）
     * 完全吻合：模型加载成功（权重都进显存了），第一次生成才炸，报的就是
     * `vk::Device::createComputePipeline: ErrorUnknown`。
     *
     * 同一份 SPIR-V 在 Turnip、lavapipe 上编译正常，所以不是我们的构建问题；
     * 38 个 mul_mat_vec 变体里只有 q4_k 家族挂，q4_0 / q5_k / q6_k / q8_0 都正常。
     * 结论：这不是调参能解决的，只能换量化格式，或者老老实实跑 CPU。
     */
    fun adrenoQ4kRisk(modelFile: String, gpuModel: String?): String? {
        if (gpuModel.isNullOrBlank() || !gpuModel.contains("Adreno", ignoreCase = true)) return null
        if (!modelFile.lowercase().contains("q4_k")) return null
        return "模型是 Q4_K 量化，$gpuModel 的驱动编不过这种着色器（llama.cpp #29786）。" +
            "换成 Q4_0 或 Q8_0 的模型就能用 GPU；继续用 Q4_K 只能跑 CPU"
    }

    /**
     * 卸载层数上限。99 是 llama.cpp 的惯用值，含义是「能卸的全卸」
     * （模型层数远小于它，等于 all layers）。写具体层数没有意义：
     * 不同模型的层数不同，而 llama.cpp 会按 min(n_layers, 上限) 处理。
     */
    const val GPU_MAX_LAYERS = 99

    /**
     * GPU 开启时允许的最大上下文。
     *
     * KV cache 和权重抢的是同一块显存。3B Q4 权重约 2GB，ctx=16384 的 KV 还要再吃
     * 六七百 MB；手机没有独立显存，单次分配上限由驱动给（常见 256MB～1GB），
     * 分配失败的表现是「装载很久 → 悄悄退回 CPU」，运气差一点就是整个进程消失
     * （真机 2026-10-29：3B + ctx=16384 + GPU 全量卸载，发一句话直接闪退）。
     *
     * 4096 覆盖手机上的绝大多数对话场景，又不至于把显存顶爆。确实需要更长上下文时，
     * 关掉 GPU 即可——那时 KV 在系统内存里，能开多大由内存决定而不是驱动。
     */
    const val GPU_MAX_CTX = 4096

    /**
     * GPU 开启时把上下文压到安全值；纯 CPU 原样返回用户设置。
     *
     * 刻意做成纯函数：这条规则一旦写错，后果是「用户的 8192 被悄悄改成 4096」
     * 这种只有对比日志才看得出的问题，所以它必须能被断言直接覆盖。
     */
    fun capCtxForGpu(nCtx: Int, gpuLayers: Int): Int =
        if (gpuLayers > 0) nCtx.coerceAtMost(GPU_MAX_CTX) else nCtx

    /**
     * 安全模式下的上下文上限。
     *
     * 2048 是「还够一轮正常对话」与「KV 只占几十 MB」的交点：3B 模型 KV 约
     * 36KB/token，2048 token ≈ 74MB；而 4096 是 148MB，加上 prefill 的中间张量
     * （按 ubatch 建图，另算几十到几百 MB），在 8GB 机器上就是压死进程的那一档。
     * 真机 2026-10-29 连续两次 SIGABRT 都发生在 4096 + 256 批的组合上。
     */
    const val SAFE_MAX_CTX = 2048

    /**
     * 本轮真正要交给 native 的上下文长度（安全模式与 GPU 限制叠在一起判）。
     *
     * 顺序不能反：先按安全模式压到 2048，再套 GPU 的 4096 上限——两个上限取更严的那个。
     * 反过来写（先 GPU 再安全模式）结果一样，但读起来会让人以为安全模式只对 CPU 生效。
     */
    fun effectiveCtx(nCtx: Int, gpuLayers: Int, safeMode: Boolean): Int {
        val capped = if (safeMode) nCtx.coerceAtMost(SAFE_MAX_CTX) else nCtx
        return capCtxForGpu(capped, gpuLayers)
    }

    /**
     * 综合判断：这台机器该先试哪条路。
     *
     * 规则刻意保守——GPU 默认只对「Vulkan 1.2+ 且 Adreno」打开，Mali 一律先给 CPU。
     * 理由不是 Mali 慢，是 Mali 的 Vulkan 驱动在中文 ROM 上常出现长时间推理后
     * `VK_ERROR_DEVICE_LOST`；而我们的场景恰恰是「挂着 Agent 跑几十分钟」。
     * 用户仍可手动切到 GPU，那时会带上「可能中断」的提示，并落进崩溃记忆。
     */
    fun advise(profile: LocalDeviceProfile): DeviceAdvice {
        val vendor = vendorOf(profile)
        val (gpu, gpuModel) = gpuOf(profile.glRenderer)
        val tier = tierOf(profile, vendor)
        val (budgetText, budgetMb) = modelBudget(tier)
        val (npuOk, npuReason) = npuUsable(profile)
        val (path, pathReason) = vulkanPath(profile)
        val notes = ArrayList<String>(4)

        val preferred = when {
            npuOk -> LocalBackend.NPU
            path == GpuPath.STABLE -> LocalBackend.GPU
            path == GpuPath.RISKY -> {
                notes += if (tier == DeviceTier.ENTRY || tier == DeviceTier.LOW) {
                    "${gpu.label} 在入门/中低端机上跑大模型容易内存不足，先用 CPU"
                } else {
                    "${gpu.label} 的 Vulkan 驱动按机型差异大，可在设置里手动试 GPU"
                }
                LocalBackend.CPU
            }
            else -> {
                // 版本不够 / 家族劝退：把原因说出来。用户最想知道的是「我这台为什么不行」。
                if (gpu != GpuFamily.UNKNOWN && pathReason != null) notes += "GPU：$pathReason"
                LocalBackend.CPU
            }
        }

        if (!npuOk && npuReason != null) notes += "NPU：$npuReason"
        if (vendor == SocVendor.UNKNOWN && profile.glRenderer.isNullOrBlank()) {
            notes += "取不到 GPU 与芯片信息，按最保守的 CPU 路线处理"
        }
        if (tier == DeviceTier.ENTRY) {
            notes += "内存偏小：建议不超过 1B 的模型，否则会在加载阶段被系统杀掉"
        }

        return DeviceAdvice(
            tier = tier,
            vendor = vendor,
            gpu = gpu,
            gpuModel = gpuModel,
            gpuPath = path,
            gpuPathReason = pathReason,
            vulkanVersion = profile.vulkanVersion,
            npuUsable = npuOk,
            npuReason = npuReason,
            preferredPath = preferred,
            modelBudget = budgetText,
            memoryBudgetMb = budgetMb,
            notes = notes,
        )
    }

    /**
     * 一行摘要，直接进设置页「本机诊断」。宁可长一点，也不要只说「已优化」。
     */
    fun summarize(advice: DeviceAdvice): String {
        val gpu = advice.gpuModel ?: advice.gpu.label
        return buildString {
            append(advice.vendor.label)
            append(" · ")
            append(gpu)
            advice.vulkanVersion?.let { append(" · Vulkan ").append(it) }
            append(" · ")
            append(advice.tier.label)
            append(" → 首选 ")
            append(advice.preferredPath.label)
            append("，建议 ")
            append(advice.modelBudget)
        }
    }
}
