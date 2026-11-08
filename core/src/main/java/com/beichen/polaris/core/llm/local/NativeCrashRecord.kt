package com.beichen.polaris.core.llm.local

/**
 * 一次**原生**崩溃的现场（由 `native-crash.cpp` 的信号处理器写出）。
 *
 * 和 `CrashLogger` 抓的不是一类东西：CrashLogger 挂的是
 * `Thread.UncaughtExceptionHandler`，只能看见 Java 异常。GPU / Vulkan / ggml 崩起来
 * 走的是信号，进程当场消失，Java 那层连日志都来不及写——用户看到的就是「闪退，
 * 日志里什么都没有」。这类崩溃唯一的证据就是这个文件。
 */
data class NativeCrash(
    /** 信号编号（11 = SIGSEGV）。 */
    val signal: Int,
    /** 信号名（SIGSEGV / SIGABRT / …）。 */
    val signalName: String,
    /** 崩在哪一步（DEVICE_INIT / MODEL_LOAD / CTX_CREATE / PREFILL / DECODE / IDLE）。 */
    val stage: String,
    /** 当时卸载到 GPU 的层数（0 = 纯 CPU）。 */
    val gpuLayers: Int,
    /** 当时的上下文窗口。 */
    val ctx: Int,
    /** 当时的线程数。 */
    val threads: Int,
    /** 模型文件名。 */
    val model: String,
    /** 当时包里是否编入了 Vulkan 后端。 */
    val vulkanBuilt: Boolean,
    /**
     * 崩溃瞬间的常驻内存（KB，来自 `/proc/self/status` 的 VmRSS）；读不到时为 0。
     *
     * 这一项是用来区分「内存不够」和「断言失败」的：
     * SIGABRT 在 ggml 里几乎只有两种来源 —— 分配器返回空后 abort，或 GGML_ASSERT 失败。
     * 前者 VmRSS 已经贴到进程上限，后者通常在几百 MB 就停了。
     * 没有这个数，两种病在文件里长得一模一样。
     */
    val rssKb: Int = 0,
    /** 崩溃瞬间的虚拟地址空间（KB，VmSize）；读不到时为 0。 */
    val vmKb: Int = 0,
    /**
     * 「不是信号打死的」理由，目前只有一类：没被接住的 C++ 异常。
     *
     * 有值时 `signal` 恒为 0（`native-crash.cpp` 里写的是 `signal=0 (TERMINATE)`）。
     * 这条信息的分量比信号编号大得多——它是推理库自己说的话，比如
     * `failed to allocate KV cache` 或 `unknown model architecture`，
     * 而信号编号只能告诉我们「它死了」。
     */
    val reason: String = ""
) {
    /** 崩的时候是不是在用 GPU。这是「要不要自动关掉 GPU」的唯一判据。 */
    val gpuInvolved: Boolean get() = gpuLayers > 0

    /** 阶段的中文说法（写进日志/界面，别让用户去猜 DEVICE_INIT 是什么）。 */
    val stageLabel: String
        get() = when (stage) {
            "DEVICE_INIT" -> "初始化显卡"
            "MODEL_LOAD" -> "把模型装进显存"
            "CTX_CREATE" -> "分配上下文缓存"
            "PREFILL" -> "读题（prefill）"
            "DECODE" -> "生成文字"
            else -> "空闲"
        }
}

/**
 * 崩溃文件的解析与判定。
 *
 * **刻意不 import 任何 Android 类型**：这些规则决定了「下次要不要关掉用户的 GPU」，
 * 是最该被断言覆盖的一段逻辑，而它必须能在纯 JVM 里被跑起来（QA harness 里没有
 * android.jar）。文件读写、SharedPreferences 那些留在 [NativeCrashMemory]。
 */
object NativeCrashParser {

    /** 与 `native-crash.cpp` 里的文件名必须一致。 */
    const val FILE_NAME: String = "native-crash.txt"

    /**
     * 归档名：启动时被消费掉的现场改名到这里，供「生成诊断包」带走。
     *
     * 刻意不用 `native-crash.txt` 本身——那个名字代表「待消费」，
     * 留着会让 [NativeCrashMemory.handleStartup] 每次启动都再判一遍。
     */
    const val ARCHIVE_NAME: String = "native-crash.last.txt"

    /**
     * 解析 native 写出的 `key=value` 文本。
     *
     * 认不出的内容一律返回 null 而不是抛异常：这份文件是崩溃现场，
     * 写它的时候进程正在死，**可能只写了一半**（比如只写到 `signal=1`）。
     * 半个文件不能当成有效证据去关用户的 GPU。
     */
    fun parse(text: String): NativeCrash? {
        if (!text.contains("kind=native-crash")) return null
        val map = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val rawSignal = map["signal"] ?: return null
        // gpu_layers 必须存在：它是「要不要关 GPU」的唯一依据。
        // 只写了一半的文件（进程死在 write 中间）拿不到这个键，宁可当成没有记录，
        // 也不要拿残缺证据去关用户的 GPU。
        val gpuLayers = map["gpu_layers"]?.toIntOrNull() ?: return null
        // `signal=11 (SIGSEGV)`：编号在前，括号里是名字。
        val signal = rawSignal.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val name = Regex("\\(([A-Z0-9]+)\\)").find(rawSignal)?.groupValues?.get(1)
            ?: "SIG$signal"
        return NativeCrash(
            signal = signal,
            signalName = name,
            stage = map["stage"] ?: "IDLE",
            gpuLayers = gpuLayers,
            ctx = map["ctx"]?.toIntOrNull() ?: 0,
            threads = map["threads"]?.toIntOrNull() ?: 0,
            model = map["model"] ?: "?",
            vulkanBuilt = map["vulkan_built"] == "1",
            rssKb = memValue(text, "VmRSS:"),
            vmKb = memValue(text, "VmSize:"),
            reason = map["reason"] ?: ""
        )
    }

    /**
     * 要不要因为这次崩溃把 GPU 关掉。
     *
     * 判据只有一条：**崩的时候 GPU 层数大于 0**。
     * 不按阶段判，是因为「纯 CPU 运行也会崩在 DECODE」（内存不足、模型损坏），
     * 那种情况关掉 GPU 没有任何意义，只会让用户少一个选项还查不出原因。
     */
    fun shouldDisableGpu(crash: NativeCrash?): Boolean = crash != null && crash.gpuInvolved

    /**
     * 要不要因为这次崩溃开「安全模式」（压上下文 + 压批）。
     *
     * 条件是**纯 CPU 且崩在推理阶段**。这三件事缺一不可：
     *  - 纯 CPU：GPU 崩溃该关 GPU，不该动用户的内存参数；
     *  - PREFILL / DECODE：崩在装载/建上下文是模型文件或内存对不上，压批也救不了；
     *  - 推理阶段意味着「模型装得下、一算就死」——这正是 KV + 中间张量顶爆画像。
     *
     * DEVICE_INIT 也排除：那是 Vulkan 初始化，跟批大小无关。
     */
    fun shouldEnableSafeMode(crash: NativeCrash?): Boolean {
        if (crash == null) return false
        if (crash.gpuInvolved) return false
        return crash.stage == "PREFILL" || crash.stage == "DECODE"
    }

    /**
     * 从现场文本里抠出 `mem=` 那一行中的某个数值（KB）。
     *
     * 不放进 map 解析，是因为 mem 行不是 `key=value` 而是
     * `mem= VmPeak: 1 kB VmSize: 2 kB VmRSS: 3 kB` 这种一段话，
     * 硬塞进统一解析只会让两边都变难读。找不到就返回 0。
     */
    private fun memValue(text: String, key: String): Int {
        val line = text.lineSequence().firstOrNull { it.startsWith("mem=") } ?: return 0
        val at = line.indexOf(key)
        if (at < 0) return 0
        val rest = line.substring(at + key.length).trimStart()
        return rest.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    /**
     * 把 `reason=` 搓成一句能塞进界面的短话。
     *
     * 截断是必须的：C++ 的 what() 可能带整条路径甚至整段模板报错，设置页那行红字
     * 只能放一句。完整的原文在崩溃文件和诊断包里，这里只负责「一眼看出是哪一类」。
     */
    private fun shortReason(crash: NativeCrash, max: Int = 60): String {
        val r = crash.reason.trim().replace('\n', ' ')
        if (r.isEmpty()) return ""
        return if (r.length <= max) r else r.take(max) + "…"
    }

    /** 给用户看的一句话（设置页 + 日志共用，措辞保持一致）。 */
    fun describe(crash: NativeCrash): String {
        val why = shortReason(crash)
        val tail = if (why.isEmpty()) "" else "，原因：$why"
        return "上次在「${crash.stageLabel}」阶段崩溃（${crash.signalName}，" +
            "GPU ${crash.gpuLayers} 层 / 上下文 ${crash.ctx}）$tail，已自动关闭 GPU 加速"
    }

    /**
     * 纯 CPU 推理崩溃时给用户看的一句话（安全模式那条）。
     *
     * 措辞刻意不说「内存不足」：我们手里只有 VmRSS，没有「当时机器还剩多少」，
     * 说死了会在别的机型上变成假结论。所以只陈述事实 + 已做的处理。
     */
    fun describeSafeMode(crash: NativeCrash): String {
        val mem = if (crash.rssKb > 0) {
            "，崩溃时占用 ${crash.rssKb / 1024} MB 内存"
        } else ""
        val why = shortReason(crash)
        val tail = if (why.isEmpty()) "" else "，原因：$why"
        return "上次在「${crash.stageLabel}」阶段被系统终止（${crash.signalName}，" +
            "上下文 ${crash.ctx}${mem}）$tail，已开启安全模式：上下文 2048、批 128"
    }
}
