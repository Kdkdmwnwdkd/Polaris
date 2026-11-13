package com.beichen.polaris.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一轮生成的全部**运行态**：在跑没跑、归属哪个会话、流式草稿攒到哪、收尾怎么做。
 *
 * ## 为什么它必须是一个有状态的类
 *
 * 改动前这套状态是 ViewModel 的 7 个私有字段 + 一个 `private inner class TurnRunner`
 * （send/regenerate 的开场收尾仪式）。当时的注释写着「不独立成类是因为这些字段全是
 * ViewModel 的私有可变状态，独立类要么暴露 8 个 setter，要么搬出去再引一堆回调」。
 * 那个判断在**状态与编排混在一起**时是对的；一旦把状态本身搬到这个类，理由就反过来了：
 * 状态有了唯一所有者，setter 一个都不需要暴露，只剩两个宿主钩子（收尾时要做的事）。
 *
 * ## 三个不变量
 *
 * ① **owner 是 @Volatile**：写点是主线程（start/finish），读点却在工具回调、落盘协程
 *   （[ConversationProjection.appendToStore]）这些 IO 线程上。少了它，后台线程可能读到
 *   stale null，`owner ?: active` 就会把旧会话的结果写进当前会话——写串。
 * ② **草稿缓冲是权威态，[streamDraft] 只是投影**：用户切走时显示态清空、缓冲继续攒，
 *   切回时原样恢复（打字机从中间继续，好过丢半截）。投影条件 = owner 就是当前会话。
 * ③ **收尾必走 [finish]**：`finally` 里调用，取消路径（CancellationException）也覆盖，
 *   否则 UI 永远停在「运行中」、发送键禁用。
 *
 * 草稿的读写与「重试时清空」共用 [draftLock]：`onTextDelta` 来自流式读取协程，
 * 与事件回调里的清空是两条线程，不加锁会出现「重试后又把旧增量拼回去」。
 *
 * 锁定范围是**全部**触碰 [draftBuffer] / [reasoningBuffer] 的地方，包括 [start] 里的
 * 重置、[finish] 里的清空、[syncDisplay] 里的读取。两个理由：① `StringBuilder` 本身
 * 不是线程安全的，两个线程并发 `append` 会把内部数组写坏；② 普通字段没有可见性保证，
 * `start` 在主线程置了占位缓冲，IO 线程的 `appendText` 可能仍读到 `null`，于是另建一个
 * StringBuilder 把增量写进野对象——占位气泡永远不出字。
 * 只给 `appendText` 加锁是**假互斥**：不参与同一把锁的写点等于没被保护。
 */
internal class GenerationRunController(
    private val scope: CoroutineScope,
    /** 当前显示的会话 id——判断草稿要不要上屏。 */
    private val activeId: () -> String?,
    /** 收尾：清「正在恢复」横幅、落盘工具组、按需重建消息列表、刷新日志。 */
    private val onFinished: (rebuildMessages: Boolean) -> Unit,
    /**
     * 每轮生成**开始**时回调（[start] 是所有入口的唯一汇聚点：send / regenerate 都走这里）。
     * 宿主用它清掉上一轮的临时提示——只挂在收尾处不够，收尾回调可能紧接着本轮的
     * 事件之后执行，会把刚置上的提示又清掉。
     */
    private val onStarted: () -> Unit = {},
    /**
     * 收尾时**在 owner 清空之前**调用，入参是此刻仍然有效的归属会话 id（R-06）。
     *
     * 为什么不能并进 [onFinished]：那里 owner 已经是 null，宿主只能定位「当前显示会话」。
     * 而工具卡最常见的残留场景恰恰是「发出去 → 切到别的会话 → 在别处点停止」，
     * 此时当前显示会话根本不是本轮归属的会话，一条残留也收不到。
     */
    private val onSettling: (owner: String) -> Unit = {},
    /**
     * 生命周期观测点。生产传 [GenerationRunHooks.None]（零开销），harness 传记录实现。
     *
     * 为什么这不是「为了测试而加的钩子」：这套状态机原本是 `private inner class`，
     * 只有跑起整个 ViewModel（要 Application、要 Compose）才能碰到，于是它的复位逻辑
     * 从来没有被单独验过——真机故障「停止后发送键永久禁用」就是这么漏出去的。
     * 现在状态是独立的类，钩子让「开始/收尾/复位」的顺序变成可断言的事实。
     */
    private val hooks: GenerationRunHooks = GenerationRunHooks.None,
) {

    private val draftLock = Any()

    /** T05：运行句柄，供 [cancel] 取消（AgentLoop 在轮次边界 ensureActive 退出并闭合树尾）。 */
    @Volatile
    var job: Job? = null
        private set

    /** hotfix30：在途任务的归属会话 id（start 捕获，finish 清 null）。见类注释①。 */
    @Volatile
    var owner: String? = null
        private set

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** hotfix30：正在生成的会话 id（抽屉显示「生成中」标识；可点进在途会话续看）。 */
    private val _generating = MutableStateFlow<String?>(null)
    val generating: StateFlow<String?> = _generating.asStateFlow()

    /** 流式草稿文本：非 null 时 UI 在消息末尾渲染一个「正在生成」的助手气泡（尚未落库）。 */
    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft.asStateFlow()

    /** 流式推理过程（P1-9）：与 [draft] 同生命周期，非 null = 模型正在思考。 */
    private val _reasoning = MutableStateFlow<String?>(null)
    val reasoning: StateFlow<String?> = _reasoning.asStateFlow()

    /**
     * 草稿累积缓冲。用 [StringBuilder] 而不是 `String?` 做 `+=`：
     * 流式增量每段只有几个字符，`buf = (buf ?: "") + delta` 每次都要把已有全文复制一遍，
     * 2 万字回答按 4 字一段算是 5,000 次、累计约 5,000 万字符的搬运。追加本身是摊还 O(1)。
     *
     * 注意**只解决了累加**：投影到 `_draft` 仍要在每次增量后给出一个新 String（StateFlow 是
     * `String` 契约），这一步的拷贝还在。要再省得把 UI 侧改成「版本号 + 共享缓冲」并做帧级
     * 节流，属于界面层的独立改动，不在本次范围内。
     */
    private var draftBuffer: StringBuilder? = null
    private var reasoningBuffer: StringBuilder? = null

    /** owner 是否就是当前显示会话——在途 UI 更新（草稿/工具卡/气泡）的投影开关。 */
    fun isOwnerActive(): Boolean = owner != null && owner == activeId()

    /** 落库目标会话：在途按 owner，否则按当前会话。 */
    fun targetConversation(): String? = owner ?: activeId()

    /** 切会话 / 收尾后把缓冲投影到显示态：owner==active 恢复，否则清（缓冲不丢）。 */
    fun syncDisplay() {
        synchronized(draftLock) {
            _draft.value = if (isOwnerActive()) draftBuffer?.toString() else null
            _reasoning.value = if (isOwnerActive()) reasoningBuffer?.toString() else null
        }
    }

    fun appendText(delta: String) = synchronized(draftLock) {
        val buf = draftBuffer ?: StringBuilder().also { draftBuffer = it }
        buf.append(delta)
        if (isOwnerActive()) _draft.value = buf.toString()
    }

    fun appendReasoning(delta: String) = synchronized(draftLock) {
        val buf = reasoningBuffer ?: StringBuilder().also { reasoningBuffer = it }
        buf.append(delta)
        if (isOwnerActive()) _reasoning.value = buf.toString()
    }

    /** 清草稿（重试开始 / 最终回答落库前）。返回清空前的内容，供「停止」路径抢救已生成部分。 */
    fun takeDraft(): String? {
        val partial = synchronized(draftLock) {
            val kept = draftBuffer?.toString()?.takeIf { it.isNotBlank() }
            draftBuffer = null
            reasoningBuffer = null
            _draft.value = null
            _reasoning.value = null
            kept
        }
        // 钩子在锁外调：它是观测点，不该把外部代码拖进锁里
        hooks.onDraftTaken(partial?.length ?: 0)
        return partial
    }

    /** 只清不取（重试会把整段重发，前半段必须丢，见 RetryStarted 的说明）。 */
    fun clearDrafts() {
        takeDraft()
    }

    /**
     * 开一轮。
     *
     * @param owner 本轮归属的会话 id（用户切走后，在途写仍按它落盘）
     * @param placeholderDraft true = 立刻渲染一个空草稿气泡（regenerate 用：没有新用户气泡，
     *   得自己造一个「正在重做」的占位）；false = 等第一个 delta 再出气泡（send 用）
     * @param rebuildMessages 收尾时是否用轮次变体结构重建消息列表。send 需要（补上助手气泡的
     *   变体游标元数据）；regenerate 不需要——它在成功/失败分支各自 reload 过，取消路径
     *   草稿已清、列表未变，再 reload 是多余 IO
     * @param body 本轮的实际工作（调用 AgentLoop、落库、出气泡）
     */
    fun start(
        owner: String,
        placeholderDraft: Boolean,
        rebuildMessages: Boolean,
        body: suspend CoroutineScope.() -> Unit,
    ) {
        this.owner = owner
        _generating.value = owner
        onStarted()
        // 与 appendText 共用 draftLock：这里是主线程重置缓冲，那边是 IO 线程追加。
        synchronized(draftLock) {
            draftBuffer = if (placeholderDraft) StringBuilder() else null
            reasoningBuffer = null
        }
        syncDisplay()
        _running.value = true
        hooks.onBegin(owner, placeholderDraft)
        job = scope.launch {
            try {
                body()
            } finally {
                job = null
                finish(rebuildMessages)
            }
        }
    }

    /** 收尾：清归属/生成中/缓冲、关运行态，再交给宿主做落盘与重建。 */
    private fun finish(rebuildMessages: Boolean) {
        // R-06：先把「还在执行中」的工具卡收口——这是 owner 仍然可读的最后时刻。
        // runCatching 是必须的：这里抛异常会跳过下面的复位，isRunning 永真 =
        // 发送键永久灰（W-102 那类「一次异常永久卡死」的老毛病）。
        owner?.let { who -> runCatching { onSettling(who) } }
        owner = null
        _generating.value = null
        // 同上：收尾在协程 finally（可能是 IO 线程），必须与 appendText 同一把锁。
        synchronized(draftLock) {
            draftBuffer = null
            reasoningBuffer = null
        }
        syncDisplay()
        _running.value = false
        hooks.onReset()
        hooks.onFinish(rebuildMessages)
        onFinished(rebuildMessages)
    }

    /** 用户点「停止」：取消在途协程。未在运行时返回 false（no-op）。 */
    fun cancel(): Boolean {
        val j = job ?: return false
        j.cancel()
        job = null
        return true
    }

    /**
     * 取消并**等它真的收尾完**，供「新输入打断旧回合」使用。
     *
     * 为什么不能只调 [cancel]：`cancel()` 只是发出取消信号，`finish()` 要等协程调度到
     * `finally` 才执行。在这段窗口里 `owner` 还是旧值、`_running` 还是 true——
     * 此时若直接 `start` 新一轮，旧轮的 `finish()` 会紧接着把**新一轮**的 owner 与
     * running 全部抹掉（它是无条件清理的），结果是界面卡在「运行中」或者两条回答写串会话。
     *
     * 返回 false = 超时没等到。**调用方此时必须放弃**，不能开始新一轮——
     * 那正是上面描述的重叠场景。返回 true 时 `job` 必为 null（由 `finish` 置），可以安全开新轮。
     *
     * @param timeoutMs 上限。用 4 秒而不是无限等：卡住的回合若连取消都不响应，
     *   继续等只会让用户以为「按了发送没反应」。
     */
    suspend fun cancelAndWait(timeoutMs: Long): Boolean {
        val j = job ?: return true
        j.cancel()
        return withTimeoutOrNull(timeoutMs) {
            j.join()
            true
        } ?: false
    }
}

/**
 * 一轮生成的生命周期观测点。
 *
 * 顺序契约：`onBegin` → (`onDraftTaken` 若干次) → `onReset` → `onFinish`。
 * 生产环境用 [None]，不产生任何额外对象与分支成本。
 */
internal interface GenerationRunHooks {

    /** 开场：owner 已置位、运行态已打开。 */
    fun onBegin(owner: String, placeholderDraft: Boolean) = Unit

    /** 取走草稿（停止前抢救 / 落库前清空）；[chars] = 被取走的正文长度，0 表示本来就没有。 */
    fun onDraftTaken(chars: Int) = Unit

    /** 运行态已经复位（owner / 生成中 / 缓冲 / isRunning 全清）——此后再调任何写都是错的。 */
    fun onReset() = Unit

    /** 收尾钩子（宿主落盘与重建）**之前**调用，便于把「复位」与「收尾」分开观测。 */
    fun onFinish(rebuildMessages: Boolean) = Unit

    object None : GenerationRunHooks
}
