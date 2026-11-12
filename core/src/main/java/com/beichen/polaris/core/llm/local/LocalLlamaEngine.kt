package com.beichen.polaris.core.llm.local

import com.beichen.polaris.core.AgentLogger
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 本地 llama 推理引擎（进程级单例）。
 *
 * 存在的原因：llama.cpp 加载模型是秒级操作（含 mmap 预热），而 ChatViewModel 的
 * provider 工厂每轮对话都会新建一个 client——若由 client 各自持句柄，等于每轮
 * 重新加载模型，体验不可接受。这里把「当前加载的实例」提到进程级，按
 * (模型路径 + 推理参数) 复用；参数变化或显式 [unload] 时才真正释放。
 *
 * 内存取舍：模型常驻是本地推理的常规做法（RikkaHub / llama.cpp server 同理），
 * 用户切换模型或点「卸载」才回收。真机 8GB+ 内存跑 1.5B~4B Q4 模型无压力。
 *
 * 线程安全：所有公开方法都在 [lock] 内完成状态变更；nativeGenerate 本身阻塞，
 * 由调用方自行放到 Dispatchers.IO。
 */
object LocalLlamaEngine {

    private const val TAG = "LocalLlama"

    private val lock = Any()

    /** 已加载实例的快照（句柄 + 加载参数）。句柄为 0 表示未加载。 */
    private data class Loaded(
        val handle: Long,
        val modelPath: String,
        val nCtx: Int,
        val nThreads: Int,
        val nGpuLayers: Int
    )

    private var current: Loaded? = null

    /**
     * 加载锁：**整段加载过程互斥**。
     *
     * 为什么不能只靠 [lock]：`current` 是加载完成后才写进去的，所以在「A 开始加载」
     * 到「A 加载完成」这段时间里，B 看到的 current 仍是 null，于是 B 也会去加载。
     * 真机 2026-10-29 15:02 就是这个形态——主对话和「AI 标题生成」同时进来：
     *
     *   15:02:17.525  加载模型 …（第 1 次）
     *   15:02:17.528  加载模型 …（第 2 次）
     *   15:02:27.883  模型就绪 10358 ms
     *   15:02:31.717  模型就绪 14188 ms
     *   15:02:45      prefill … RSS 4078 MB
     *
     * 同一个 2GB 权重被 mmap 了两份、两个 llama_context 并存，RSS 从 2.1GB 涨到 4.1GB，
     * 两个 prefill 互相抢 CPU，预热 decode 从 110 ms 恶化到 20004 ms（「异常慢」），
     * 还带出一次 `llama_decode 失败 rc=-1`。
     *
     * 加这把锁之后，第二个调用方会**等**第一个加载完，进来时 current 已就绪 → 直接复用。
     */
    private val loadLock = ReentrantLock()

    /**
     * 推理互斥锁：**同一个 llama_context 上不允许有两个 decode 并发**。
     *
     * ## 修前是什么样
     * [generate] / [chatTemplate] 只经 [withHandle] 做了「防释放计数」，没有任何互斥。
     * 于是两个协程可以同时进 `nativeGenerate(同一个 handle, …)`。llama.cpp 的
     * context 不是线程安全的：KV cache、`n_past`、采样器状态、logits 缓冲全是共享可变状态，
     * 两条序列交错写同一段 KV。表现不是「报个错」，而是：
     *  - 输出**串台**（A 的回答里出现 B 的 prompt 内容），或逻辑突然跳转；
     *  - `n_past` 互相覆盖 → 复读、退化；
     *  - 极端情况写坏 KV 索引 → SIGSEGV（现场看起来像「模型坏了」）。
     *
     * 真实触发路径不需要用户做怪事：主对话正在流式生成时，「AI 标题生成」在另一个
     * 协程里对同一个引擎调 generate——这正是 [isBusy] 想拦却没拦住的那条（它只是
     * 给调用方一个**建议**，不是闸门）。
     *
     * ## 为什么是 ReentrantLock 而不是 Mutex
     * [generate] 本身是阻塞调用（`nativeGenerate` 在 JNI 里同步跑完），
     * 不在挂起函数里、也不能挂起。协程的 Mutex 在这里既用不上也不该用。
     * 调用方（LocalLlamaClient）已经在 IO 线程上，阻塞等待是预期行为。
     */
    private val inferenceLock = ReentrantLock()

    /**
     * 当前**正在生成**的句柄（0 = 没有生成在跑）。
     *
     * [stop] 必须停它，而不是 `current.handle`：生成期间用户可能切换了模型，
     * [releaseLocked] 已把旧句柄挪进 [retiredHandles]，`current` 指向的是新实例。
     * 修前 `stop()` 停 current——于是「取消旧生成」这个动作落在了一个根本没在跑的
     * 实例上，真正在跑的旧 handle 收不到中断，只能等它自己吐完 maxTokens。
     */
    @Volatile
    private var generatingHandle: Long = 0L

    /**
     * 当前句柄的「正在使用」计数（generate / chatTemplate 期间）。
     *
     * 用来防止 use-after-free：参数变化时 `releaseLocked()` 会 free 旧 context，
     * 但可能仍有线程在这个 handle 上跑生成。free 掉正在跑的 context 不是「报错」，
     * 是向已释放内存写 KV——直接 SIGSEGV。
     */
    private var activeUsers = 0

    /** 已被替换、但仍有人在用、等最后一个用户退出后再释放的句柄。 */
    private val retiredHandles = ArrayList<Long>()

    /** 单调递增的代际号：每次加载/卸载 +1，供 UI 判断「加载中的是不是最新请求」。 */
    private val generation = AtomicLong(0)

    /**
     * 进程级：GPU 后端在本进程里炸过（原因文本；null 表示没炸过）。
     *
     * **必须放在这里而不是 client 里。** LocalLlamaClient 是每轮对话新建的（见类注释），
     * 实例字段活不过一轮——真机 2026-10-29 13:13 的表现就是「13:13:09 刚说已关闭 GPU，
     * 13:13:16 又打出 GPU 卸载：99 层」，同一个坏驱动被反复踩。
     *
     * 用户把「自动」理解成「安全」是合理的，所以这里不赌第二次：一旦置位，
     * 之后所有 acquire 一律按 CPU 装，直到进程重启。
     */
    @Volatile
    var gpuDisabledReason: String? = null
        private set

    fun disableGpu(reason: String) {
        if (gpuDisabledReason == null) gpuDisabledReason = reason
    }

    /** 当前代际（加载成功后与 generation 对齐）。 */
    @Volatile
    var loadedGeneration: Long = -1L
        private set

    /** 已加载模型的展示名（文件名），未加载为 null。供 UI 状态卡展示。 */
    val loadedModelName: String?
        get() = synchronized(lock) { current?.let { File(it.modelPath).name } }

    val isLoaded: Boolean
        get() = synchronized(lock) { current != null }

    /**
     * 取（必要时加载）推理实例。
     *
     * @return native 句柄；0 表示加载失败（原因看 AgentLogger）
     */
    fun acquire(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long {
        if (!LlamaJni.ensureLoaded()) {
            AgentLogger.e(TAG, LlamaJni.unavailableReason ?: "native 库不可用，本地推理终止")
            return 0L
        }
        // 本进程里 GPU 已经炸过 → 无论调用方要几层都不给。
        //
        // 调用方（LocalLlamaClient.resolveGpuPlan）也有一判断，但它是每轮新建的对象，
        // 状态会丢；这里是进程级单例，是唯一可靠的闸门。两层都留，是因为
        // 「请求 99 层 → 被强制改成 0 层」这件事必须同时反映在日志和 Loaded 缓存里。
        var layers = nGpuLayers
        gpuDisabledReason?.let { why ->
            if (layers > 0) {
                AgentLogger.w(
                    TAG,
                    "本进程内 GPU 已遭禁用（$why），忽略请求的 $layers 层，按 CPU 加载",
                )
                layers = 0
            }
        }
        synchronized(lock) {
            val cur = current
            if (cur != null &&
                cur.modelPath == modelPath &&
                cur.nCtx == nCtx &&
                cur.nThreads == nThreads &&
                cur.nGpuLayers == layers
            ) {
                AgentLogger.i(TAG, "复用已加载实例: ${File(modelPath).name} (ctx=$nCtx, threads=$nThreads)")
                return cur.handle
            }
        }
        // 到这里说明「需要加载」。**必须持 loadLock 再做一次检查**：
        // 两个线程可能同时判定 current 不匹配而走到这里，其中一个先加载完，
        // 后进来的如果不复查就会把刚加载好的实例再释放、再加载一遍（就是上面那次事故）。
        loadLock.lock()
        try {
            synchronized(lock) {
                val cur = current
                if (cur != null &&
                    cur.modelPath == modelPath &&
                    cur.nCtx == nCtx &&
                    cur.nThreads == nThreads &&
                    cur.nGpuLayers == layers
                ) {
                    AgentLogger.i(TAG, "复用已加载实例（等锁期间已就绪）: ${File(modelPath).name}")
                    return cur.handle
                }
                // 参数变了：释放旧实例再加载（模型文件级切换）
                releaseLocked()
            }
            return load(modelPath, nCtx, nThreads, layers)
        } finally {
            loadLock.unlock()
        }
    }

    private fun load(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long {
        val file = File(modelPath)
        if (!file.isFile) {
            AgentLogger.e(TAG, "模型文件不存在: $modelPath")
            return 0L
        }
        val gen = generation.incrementAndGet()
        AgentLogger.i(
            TAG,
            "加载模型 ${file.name}（${file.length() / 1024 / 1024} MB, ctx=$nCtx, threads=$nThreads" +
                (if (nGpuLayers > 0) ", gpu=$nGpuLayers 层" else ", 纯 CPU") + "）…"
        )
        val startedAt = System.currentTimeMillis()
        val handle = LlamaJni.nativeInit(modelPath, nCtx, nThreads, nGpuLayers)
        if (handle == 0L) {
            AgentLogger.e(TAG, "nativeInit 失败（详见 logcat llama-jni）: ${file.name}")
            return 0L
        }
        if (nGpuLayers > 0) {
            // 请求了 GPU 就把「ggml 到底认出了哪些设备」记下来。
            // Vulkan 初始化失败是静默的：加载照样成功、只是全程跑在 CPU 上。
            // 没有这一行，用户报「开了 GPU 还是很慢」时我们只能猜。
            val probe = LlamaJni.gpuProbe()
            if (probe.isNotBlank()) AgentLogger.i(TAG, "后端设备：$probe")
        }
        synchronized(lock) {
            current = Loaded(handle, modelPath, nCtx, nThreads, nGpuLayers)
            loadedGeneration = gen
        }
        AgentLogger.i(
            TAG,
            "模型就绪: ${file.name}，耗时 ${System.currentTimeMillis() - startedAt} ms"
        )
        return handle
    }

    /** 卸载当前模型，释放 native 内存。幂等。 */
    fun unload() {
        loadLock.lock()
        try {
            synchronized(lock) {
                releaseLocked()
                generation.incrementAndGet()
                loadedGeneration = -1L
            }
        } finally {
            loadLock.unlock()
        }
        AgentLogger.i(TAG, "模型已卸载")
    }

    /**
     * 卸载并清掉进程级故障记忆（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 顺序是「先 unload 再清标记」：反过来的话，unload 内部的日志记录会看到
     * `gpuDisabledReason == null`，把一次正常的清场写成「GPU 恢复可用」。
     *
     * `gpuDisabledReason` 一旦置位就**刻意不再清**（见字段注释：同一块坏驱动不赌第二次）。
     * 这里必须清，是因为 harness 要在同一 JVM 里模拟「新进程」；生产没有人调 reset。
     *
     * `generation` 归零而不是递增：它是给 UI 判断「加载中的是不是最新请求」的代际号，
     * 清场后应当如同从未加载过。若此刻仍有加载在飞，归零会让那一轮的代际号
     * 看起来「更新」——但 reset 的前置条件就是没有在飞的加载，这一点由调用方保证。
     */
    fun reset() {
        unload()
        synchronized(lock) {
            gpuDisabledReason = null
            generation.set(0L)
            loadedGeneration = -1L
        }
    }

    /**
     * 释放当前实例。
     *
     * 有人正在用这个句柄时**不能立即 free**：free 掉正在 decode 的 context 会让
     * llama.cpp 往已释放内存里写 KV，表现为随机 SIGSEGV（而且现场看起来像「模型坏了」）。
     * 改为登记到 [retiredHandles]，等最后一个使用者退出时统一释放。
     */
    private fun releaseLocked() {
        val cur = current ?: return
        current = null
        if (activeUsers > 0) {
            retiredHandles.add(cur.handle)
            AgentLogger.w(TAG, "旧实例仍有 $activeUsers 处在使用，延迟到它们退出后再释放")
            return
        }
        LlamaJni.nativeFree(cur.handle)
    }

    /** 使用者归零时回收延迟释放的句柄。调用方必须已持有 [lock]。 */
    private fun releaseRetiredLocked() {
        if (activeUsers > 0 || retiredHandles.isEmpty()) return
        for (h in retiredHandles) LlamaJni.nativeFree(h)
        AgentLogger.i(TAG, "已释放 ${retiredHandles.size} 个延迟回收的旧实例")
        retiredHandles.clear()
    }

    /**
     * 在「当前实例」的保护下执行一段 native 调用。
     *
     * 计数期间该句柄不会被 free（见 [releaseLocked]）。未加载时返回 null。
     */
    private inline fun <T> withHandle(block: (Long) -> T): T? {
        val handle = synchronized(lock) {
            val h = current?.handle ?: 0L
            if (h == 0L) 0L else {
                activeUsers++
                h
            }
        }
        if (handle == 0L) return null
        try {
            return block(handle)
        } finally {
            synchronized(lock) {
                activeUsers--
                if (activeUsers == 0) releaseRetiredLocked()
            }
        }
    }

    /** 引擎是否正在跑推理。标题生成这类「锦上添花」的任务据此让路。 */
    val isBusy: Boolean
        get() = synchronized(lock) { activeUsers > 0 }

    /** 请求停止当前生成（协程取消时调用；生成循环每 token 检查停止标志）。 */
    fun stop() {
        // 优先停「正在生成的那个句柄」。原因见 [generatingHandle]：生成期间可能已换模型，
        // 此时 current 是新实例，停它等于什么都没停。
        val running = generatingHandle
        if (running != 0L) {
            LlamaJni.nativeStop(running)
            return
        }
        // 没有生成在跑：可能 stop 与 generate 之间有极短窗口（取消先到）。
        // 仍然对 current 发一次是安全的——native 侧在每次 nativeGenerate 开头
        // 都会把 stop 标志清零（llama-jni.cpp 里的 `stop.store(false)`），
        // 所以这里不会把「下一次生成」提前掐死。
        synchronized(lock) { current?.handle }?.let { handle ->
            if (handle != 0L) LlamaJni.nativeStop(handle)
        }
    }

    /** 应用模型自带对话模板；未加载时返回空串。 */
    fun chatTemplate(roles: Array<String>, contents: Array<String>): String =
        // 同 [generate]：模板渲染也会碰同一个 context 上的状态，必须与 decode 串行。
        // 它的耗时是毫秒级，排队代价可以忽略；放过去则是「生成到一半被改了 prompt 缓冲」。
        inferenceLock.withLock {
            withHandle { LlamaJni.nativeChatTemplate(it, roles, contents) }
        } ?: ""

    /** 阻塞式生成；未加载时返回空串。listener 可空。 */
    fun generate(prompt: String, maxTokens: Int, listener: LlamaJni.TokenListener?): String =
        // ★ 互斥：同一个 context 上只能有一条序列在 decode（原因见 [inferenceLock]）。
        // 等待期间不占 activeUsers，`isBusy` 仍为 true（先到者还没退出），
        // 标题生成这类「锦上添花」的调用据此外退，不会在队列里干等。
        inferenceLock.withLock {
            withHandle { h ->
                generatingHandle = h
                try {
                    LlamaJni.nativeGenerate(h, prompt, maxTokens, listener)
                } finally {
                    // 无论正常结束、抛异常还是被 stop 中断，都要清掉：
                    // 留着会让下一次 stop() 去停一个已经不在生成的句柄。
                    generatingHandle = 0L
                }
            }
        } ?: ""

    /**
     * 取走「GPU 后端在生成期间自己抛异常」的记录（取走即清空，空串表示没发生）。
     *
     * 真机 2026-10-29：Adreno 740 + 99 层全卸载，首次生成时
     * `vk::Device::createComputePipeline: ErrorUnknown`，异常穿过 JNI 边界直接闪退。
     * native 侧现在就地接住，这里把原因交给调用方去降级——序列不消费会导致
     * 「重试那一轮又被判成故障」的死循环。
     */
    fun takeGpuFault(): String {
        val handle = synchronized(lock) { current?.handle } ?: return ""
        if (handle == 0L) return ""
        val fault = LlamaJni.takeGpuFault(handle)
        // 取走的同时钉进进程级状态：调用方（可能已经忘了处理、或者下一轮是个新对象）
        // 不需要额外做什么，闸门就已经落下。
        if (fault.isNotEmpty()) disableGpu(fault)
        return fault
    }
}
