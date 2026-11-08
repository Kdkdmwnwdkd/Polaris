package com.beichen.polaris.core.llm.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地推理参数持久化（SharedPreferences）。
 *
 * 与 [com.beichen.polaris.core.ConfigStore] 平级：ModelConfig 管「云端供应商三元组」，
 * 这里管「本地 GGUF 模型与推理参数」。两套配置互不污染——
 * providerId 决定用哪套，local-llama 时只看这里。
 *
 * 线程数默认取 min(4, 可用核心)：手机大核通常 4 个以内，再多收益递减还抢主线程。
 */
class LocalLlamaSetting(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前选中的 GGUF 文件名（位于 files/models/ 下）；空串 = 未选择。 */
    var activeModel: String
        get() = sp.getString(KEY_ACTIVE_MODEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_ACTIVE_MODEL, value).apply()

    /** 上下文窗口（token 数）。越大越吃内存，2048~8192 覆盖手机端常见场景。 */
    var nCtx: Int
        get() = sp.getInt(KEY_N_CTX, DEFAULT_N_CTX)
        set(value) = sp.edit().putInt(KEY_N_CTX, value.coerceIn(512, 16384)).apply()

    /** 推理线程数。 */
    var nThreads: Int
        get() {
            migrateThreadDefaultOnce()
            return sp.getInt(KEY_N_THREADS, defaultThreads())
        }
        set(value) = sp.edit()
            .putInt(KEY_N_THREADS, value.coerceIn(1, 8))
            .putInt(KEY_THREADS_VERSION, THREADS_VERSION)
            .apply()

    /**
     * 一次性迁移：让「线程数默认值」的修正对老用户也生效。
     *
     * 老版本把「大核数」（8 核机 = 4）写进了 SharedPreferences，而新的默认是「核数 − 2」
     * （= 6）。不清掉旧值的话，改默认值只对新装用户有效——老用户看到的现象是
     * 「更新了但一点没变快」，而这恰恰是最需要变快的那批人。
     *
     * 代价：手动调过线程数的用户会被重置一次。可接受——线程数就在设置页里，
     * 而「默认值永远不生效」是更坏的 bug。
     */
    private fun migrateThreadDefaultOnce() {
        if (sp.getInt(KEY_THREADS_VERSION, 0) >= THREADS_VERSION) return
        sp.edit()
            .remove(KEY_N_THREADS)
            .putInt(KEY_THREADS_VERSION, THREADS_VERSION)
            .apply()
    }

    /** 单次生成上限（token）。 */
    var maxTokens: Int
        get() = sp.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = sp.edit().putInt(KEY_MAX_TOKENS, value.coerceIn(64, 8192)).apply()

    /**
     * GPU（Vulkan）卸载策略。默认 [GpuMode.AUTO]：由设备规则决定，不在这里猜。
     *
     * 存字符串而不是布尔，是为了以后能加档（比如「只用 GPU 跑 prefill」）而不破坏老配置；
     * 解析失败一律回 AUTO —— 配置被写坏时宁可退回自动判断，也不要让本地推理直接不可用。
     */
    var gpuMode: GpuMode
        get() = GpuMode.parse(sp.getString(KEY_GPU_MODE, null))
        set(value) = sp.edit().putString(KEY_GPU_MODE, value.name).apply()

    /**
     * 上次「开 GPU 崩掉」的说明（空串 = 没有）。
     *
     * 由 [NativeCrashMemory.handleStartup] 写入、设置页读取。**必须持久化**：
     * 崩溃发生在进程死亡的那一瞬间，内存里什么都留不下，只有提前写进磁盘的内容
     * 能让下一次启动解释「为什么 GPU 被关掉了」。用户手动重选档位时清空。
     */
    var lastGpuCrashNote: String
        get() = sp.getString(KEY_GPU_CRASH_NOTE, "") ?: ""
        set(value) = sp.edit().putString(KEY_GPU_CRASH_NOTE, value).apply()

    /**
     * 安全模式：把上下文压到 2048、物理批压到 128。
     *
     * 触发条件是「上次在 prefill/decode 阶段被信号打死，且当时就是纯 CPU」。
     * 那种情况下关 GPU 毫无意义——本来就没开——能动的只剩内存占用：
     * 权重是死的（3B Q4 约 1.9GB），KV 和 prefill 的中间张量是活的，
     * 于是把它们按比例压下来。
     *
     * **用户可手动关**。安全模式是「先让你能用」的临时档位，不是终态：
     * 有人在 12GB 机器上崩一次只是偶然，不该被永久锁在 2048。
     */
    var safeMode: Boolean
        get() = sp.getBoolean(KEY_SAFE_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_SAFE_MODE, value).apply()

    /**
     * 本地 Agent 模式：是否把工具协议、完整系统提示、历史一并塞进 prompt。
     *
     * **默认关**，这是 W-74 的核心结论。
     *
     * 同一台手机、同一个 `qwen2.5-3b-instruct-q4_k_m.gguf`：
     *   - 只带用户那句话（7 token）→ 首字 4.8 秒、生成 4.8 tok/s；
     *   - 带上工具协议 + 系统提示 + 设备上下文（1086 token）→ 180 秒还没算完 prefill。
     *
     * 手机 CPU 的算力是固定的，prompt 长 150 倍就是慢 150 倍。所以「本地能聊天」
     * 和「本地能调工具」必须是两个档位，而不是一个默认值：
     * 轻聊档默认开，要工具时由用户显式打开 Agent 模式（并接受它慢）。
     */
    var agentMode: Boolean
        get() = sp.getBoolean(KEY_AGENT_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_AGENT_MODE, value).apply()

    /**
     * 默认推理线程数。
     *
     * 早先取「最高频簇的核心数」（8 核机 → 4），理由是「大小核混跑要等最慢的小核」。
     * 真机日志推翻了这条：3B Q4_K_M 在 8 核机上只有 2~3 tok/s 的 prefill，比同机型
     * 上别的本地推理 App 低一个数量级——瓶颈不是「小核拖后腿」，而是线程太少，
     * 内存访问的延迟盖不住。
     *
     * 现在的口径：**核数 − 2，夹在 4~6**。
     *   - 减 2 是给渲染线程与系统留出余量，否则生成时界面掉帧、点停止都要等；
     *   - 上限 6 是因为手机 SoC 上 7~8 线程的边际收益为负（内存带宽争抢 + 发热降频）；
     *   - 下限 4 保证 4 核老机型也能吃满。
     *
     * 这是「默认值」，不是「最优值」：不同 SoC 的最优点差 1~2 个线程，用户可在
     * 「模型配置 → 本地模型 → 推理参数」里自己调，调完立刻生效（下次加载）。
     */
    private fun defaultThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(4, 6)
    }

    companion object {
        private const val PREFS_NAME = "agentkit_local_llama"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_N_CTX = "n_ctx"
        private const val KEY_N_THREADS = "n_threads"
        private const val KEY_THREADS_VERSION = "threads_default_version"

        /** 线程默认值口径的版本号。改默认策略时 +1，老配置会被重置一次。 */
        private const val THREADS_VERSION = 2
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_AGENT_MODE = "agent_mode"
        private const val KEY_GPU_MODE = "gpu_mode"
        private const val KEY_GPU_CRASH_NOTE = "gpu_crash_note"
    private const val KEY_SAFE_MODE = "safe_mode"
        const val DEFAULT_N_CTX = 4096

        /**
         * 单次生成长度上限。
         *
         * 曾经是 2048 —— 那个值对云端 API 无所谓，对本地是「一张 8 分钟的空头支票」：
         * 3B Q4 在手机 CPU 上约 3~5 tok/s，模型一旦不吐结束符（小模型常见），用户就要
         * 对着空白等到 2048 token 全部吐完。512 够一段完整回答，最坏情况也被压在
         * 两三分钟内；要长文由用户自己在设置里调。
         */
        const val DEFAULT_MAX_TOKENS = 512

        /** 模型文件统一存放目录（App 私有空间，卸载 App 即清理）。 */
        fun modelsDir(context: Context): java.io.File =
            java.io.File(context.filesDir, "models").apply { mkdirs() }
    }
}
