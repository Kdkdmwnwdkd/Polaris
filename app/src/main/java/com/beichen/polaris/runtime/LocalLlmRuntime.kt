package com.beichen.polaris.runtime

import android.content.Context
import android.content.SharedPreferences
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.ModelInfo
import com.beichen.polaris.core.PingResult
import com.beichen.polaris.core.llm.ChatStreamEvent
import com.beichen.polaris.core.llm.local.LocalLlamaEngine
import com.beichen.polaris.runtime.lease.DISABLED_IDLE_TIMEOUT_MS
import com.beichen.polaris.runtime.lease.LeaseHandle
import com.beichen.polaris.runtime.lease.LeaseRegistry
import com.beichen.polaris.runtime.lease.LocalLlmLease
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * 本地模型的生命周期运行时：运行层第一个落地能力。
 *
 * ## 职责边界
 * - **管**：模型「加载着还是卸载着」。空闲超过阈值自动卸载（省内存、降温、让
 *   SoC 睡觉）；下一轮推理时 LocalLlamaClient.ensureModelLoaded() 会自动重载，
 *   用户侧只体现为首 token 稍慢，无其他感知。
 * - **不管**：推理本身。prompt、工具解析、流式协议都是 LocalLlamaClient 的职责，
 *   这里一概不碰。
 *
 * ## 两半结构（为什么不是一个类）
 * LocalLlamaClient 是**每轮对话新建**的（provider 工厂，见其类注释），实例活不过
 * 一轮；而空闲策略、在途计数、配置是**进程级**状态。所以拆成两半：
 * - [LocalLlmRuntime]（object，单例）：计数、节拍器、配置、能力注册——进程级。
 * - [wrapLocalLlm]（函数，每轮）：把当轮客户端包一层轻装饰器，调用前后记账。
 *   装饰器轻到可以每轮新建，与工厂「每轮新建 client」的现有节奏完全一致。
 *
 * ## 并发安全（两道闸门）
 * 1. **在途计数**：节拍器看到在途 > 0 直接跳过本轮检查——绝大多数情况下让卸载
 *    绕开在途任务，避免「卸载-重载」抖动。
 * 2. **引擎兜底**：即便计数有漏洞，LocalLlamaEngine.releaseLocked() 的延迟回收
 *    （有使用者时句柄先登记、退出后再 free）保证不会 use-after-free。
 * 前者是「尽量别卸」，后者是「卸了也不崩」，性质不同、都留着。
 *
 * ## 接线方式（不动任何现有文件，两行）
 * 1. `AgentKitApplication.onCreate`：`LocalLlmRuntime.init(applicationContext)`
 * 2. provider 工厂创建本地客户端处：`return wrapLocalLlm(LocalLlamaClient(...))`
 */
object LocalLlmRuntime : AgentCapability {

    override val id: String = "local-llm"
    override val displayName: String = "本地模型"

    private const val TAG = "LocalLlmRuntime"

    private val stateFlow = MutableStateFlow(CapabilityState.REGISTERED)
    override val state: StateFlow<CapabilityState> = stateFlow

    private lateinit var prefs: SharedPreferences

    /**
     * R6：空闲释放开关。默认开；UI 可直接绑定这对存取器。
     *
     * **关键**：本开关必须真正作用到 [LeaseRegistry] 一侧。
     * R6 之前这里只写旧 policy 的字段，而实际回收由 registry 按全局 30 分钟执行——
     * 用户关掉开关，模型照样 30 分钟后被卸，**设置形同虚设**。
     * 现在改为下发到租约自身的阈值（负数 = 禁用回收），设置即刻生效。
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            applyIdlePolicy()
            AgentLogger.i(TAG, if (value) "空闲释放已开启" else "空闲释放已关闭")
        }

    /** 空闲阈值（分钟），1~120。默认 [DEFAULT_IDLE_MINUTES]。 */
    var idleTimeoutMinutes: Int
        get() = prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES)
        set(value) {
            prefs.edit().putInt(KEY_IDLE_MINUTES, value.coerceIn(1, 120)).apply()
            applyIdlePolicy()
        }

    /**
     * 把用户设置下发到租约侧。
     *
     * 「关闭」映射为**负数**（禁用回收），不是 0——
     * 0 在 [LeaseRegistry] 的语义里是「立即满足空闲条件」（测试用），
     * 拿它表示「禁用」会让模型每次检查都被回收。
     */
    private fun applyIdlePolicy() {
        val timeout = if (enabled) timeoutMsFromPrefs() else DISABLED_IDLE_TIMEOUT_MS
        lease.idleTimeoutMs = timeout
        AgentLogger.i(
            TAG,
            if (enabled) "空闲释放阈值：${idleTimeoutMinutes} 分钟" else "空闲释放已禁用（租约 ${lease.id}）"
        )
    }

    // ———— AgentCapability ————————————————————————————————————

    override suspend fun start() = startWatch()

    /**
     * 启动空闲节拍（幂等）。
     *
     * R6：这里**不再自己起节拍器**。回收统一由 [LeaseRegistry] 执行——
     * 本类只负责「租约已登记 + 阈值已按用户设置下发」，不再有第二个判据源。
     * 双节拍器的问题是真实存在过的：旧 policy 与 registry 各有一套
     * `idleTimeoutMs` 且语义相反（0 = 永不释放 vs 0 = 立即回收），
     * 用户关掉空闲释放时 registry 照旧回收，设置形同虚设。
     */
    private fun startWatch() {
        if (stateFlow.value == CapabilityState.READY) return
        stateFlow.value = CapabilityState.STARTING
        applyIdlePolicy()
        stateFlow.value = CapabilityState.READY
        val mode = if (enabled) "开，${idleTimeoutMinutes} 分钟" else "关"
        AgentLogger.i(TAG, "本地模型能力就绪（空闲释放：$mode）")
    }

    override suspend fun stop() {
        releaseIfLoaded("运行层主动停止")
        stateFlow.value = CapabilityState.STOPPED
    }

    /**
     * 回到「已注册未启动」（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 只复位能力状态，**不复位 prefs 与租约**：
     * - prefs 绑的是 `applicationContext`，进程内只有一份，换不掉也不需要换；
     * - 租约登记由 [LeaseRegistry.reset] 统一清，这里再动一次会清掉刚登记好的句柄。
     *
     * 也不调 [stop]：那会 `releaseIfLoaded`，把模型卸掉——reset 的使用方（harness）
     * 常常只是想重跑一遍状态机，不该连带付一次十几秒的重新加载。
     */
    fun reset() {
        stateFlow.value = CapabilityState.REGISTERED
    }

    // ———— 在途记账（仅供 wrapLocalLlm 使用）———————————————————

    /**
     * R3：本地模型租约。
     *
     * 租约化之后「谁在减计数」不再靠手工约定——`LeaseHandle.close()` 保证归还，
     * 漏一次不会永久泄漏计数。原 [beginUse] / [endUse] 保留为薄封装
     * （装饰器仍在用），但内部记账走租约。
     */
    private val lease = LocalLlmLease(id)

    /**
     * 心跳 + 在途 +1。装饰器在每次推理开始时调用。
     *
     * R3：改为**挂起**函数，借一个租约句柄；[endUse] 关闭它。
     * 挂起是必要的——租约的 acquire 是挂起操作，而 `runBlocking` 在这里
     * 有死锁风险（调用点可能在持有锁的协程里）。所有调用点本来就在挂起上下文。
     *
     * R6：不再单独调 `policy.touch()`——租约的 acquire / release 已各自
     * 刷新 [LeaseRegistry] 的使用时刻，心跳是租约契约的一部分，
     * 不该再有第二个心跳源。
     */
    suspend fun beginUse() {
        val handle = lease.acquire()
        // 句柄存起来供 endUse 归还。用队列而非集合：同一时刻可能有多个在途推理，
        // 归还时按「后进先出」对应即可——每次推理独立借还，句柄之间无区别。
        synchronized(handleQueue) { handleQueue.add(handle) }
    }

    /**
     * 在途 -1。装饰器在推理结束 / 取消时调用（finally 语义）。
     */
    suspend fun endUse() {
        // poll() 从头部取并移除（ConcurrentLinkedQueue 没有 removeAt）。
        // 句柄之间无区别，取哪个都一样——每次推理独立借还。
        val handle = handleQueue.poll()
        handle?.close()
    }

    /** 在途推理的租约句柄队列（先进先出地归还即可，句柄之间无区别）。 */
    private val handleQueue = java.util.concurrent.ConcurrentLinkedQueue<LeaseHandle>()

    // ———— 空闲释放 ————————————————————————————————————————————

    private fun releaseIfLoaded(reason: String) {
        if (!LocalLlamaEngine.isLoaded) return
        val model = LocalLlamaEngine.loadedModelName ?: "未知模型"
        runCatching { LocalLlamaEngine.unload() }
            .onSuccess {
                AgentLogger.i(
                    TAG,
                    "已释放本地模型 $model（$reason）。下次对话会自动重载，首字会慢十几秒",
                )
            }
            .onFailure { AgentLogger.w(TAG, "本地模型释放失败（$reason）", it) }
    }

    // ———— 初始化 ——————————————————————————————————————————————

    /**
     * Application.onCreate 调一次：建配置存储、登记租约，并把用户设置下发到租约。
     *
     * R3：原先这里调 `CapabilityHost.register(this)`——那是「通用能力注册表」，
     * 全仓只有这一处调用，其余方法零消费，已随通用能力框架一起删除。
     * 现在登记的是 [LeaseRegistry]（真有人用的统一回收器）。
     *
     * R6：这里**不再并存第二个节拍器**。空闲回收只有 [LeaseRegistry] 一个执行处，
     * 本类的设置通过 [applyIdlePolicy] 下发到租约自身的阈值上。
     */
    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.releaseIdle("租约空闲回收") }
        startWatch()
    }

    private fun timeoutMsFromPrefs(): Long =
        idleTimeoutMinutes.coerceAtLeast(1) * 60_000L

    private const val SP_NAME = "agentkit_runtime"
    private const val KEY_ENABLED = "local_llm_idle_unload_enabled"
    private const val KEY_IDLE_MINUTES = "local_llm_idle_timeout_minutes"

    /**
     * 空闲阈值默认值（分钟）。
     *
     * 为什么是 30 而不是 5：3B Q4 模型在本机冷加载（mmap + 建 context）实测约 14 秒
     * （2026-10-29 真机日志）。5 分钟就卸，意味着用户「看会儿别的再回来问一句」的
     * 常见节奏会被打断成 14 秒黑等——省下的是 2GB 内存，赔掉的是首字延迟。
     * 30 分钟覆盖「聊—查资料—回来继续聊」，同时仍能让长时间闲置的机器真正降温。
     */
    const val DEFAULT_IDLE_MINUTES = 30
}

/**
 * 把当轮的本地推理客户端包进生命周期记账。
 *
 * 轻装饰器：所有调用原样转发，仅在前后做「使用心跳 + 在途计数」。
 * - chat：try / finally 记账（异常与取消都正确归还）。
 * - 流式：onStart / onCompletion 记账（协程取消会触发 onCompletion）。
 * - listModels / testPing 不记账——它们不加载模型、不产生长任务。
 */
fun wrapLocalLlm(client: LLMClient): LLMClient = object : LLMClient by client {

    override val id: String = client.id

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        LocalLlmRuntime.beginUse()
        try {
            return client.chat(messages, tools)
        } finally {
            LocalLlmRuntime.endUse()
        }
    }

    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> =
        flow { emitAll(client.streamText(messages, tools)) }
            .onStart { LocalLlmRuntime.beginUse() }
            .onCompletion { LocalLlmRuntime.endUse() }

    override fun streamChat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<ChatStreamEvent> =
        flow { emitAll(client.streamChat(messages, tools)) }
            .onStart { LocalLlmRuntime.beginUse() }
            .onCompletion { LocalLlmRuntime.endUse() }
}
