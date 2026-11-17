package com.beichen.polaris.pet

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.io.AtomicJsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 桌宠对话历史的落盘（一个文件，序列化的「一问一答」）。
 *
 * ## 为什么现在要落盘
 *
 * 一期的判断是「随手问一句，关掉即焚」。真机上跑过一轮后这个判断站不住：
 * 用户问「这份合同里违约金多少」，看一眼答案、把气泡收起来回微信，再点开想追问
 * 「那第二条呢」——桌宠一脸茫然。它并没有忘记，是**整个进程级历史被上一次关闭带走了**。
 *
 * `PetConversation` 已经解决了「Service 重建不丢」，但解决不了「进程被杀 / 用户手动
 * 杀掉 App / 隔天再用」。而这两件事在手机上每天都会发生。
 *
 * ## 为什么不复用 `ConversationStore`
 *
 * 那个类是给主界面多会话用的：分片目录、轮次变体、置顶、token 账本、归档分页。
 * 桌宠只有**一条**会话、最多 [MAX_MESSAGES] 条消息、没有变体也没有标题。
 * 复用它要额外维护一个持久 convId、还要把 `UiItem` 与 `ChatMessage` 来回翻译两遍，
 * 换来的能力一条都用不上。这里只做「存一个数组，读一个数组」。
 *
 * 但落盘纪律照抄它：**原子写走 [AtomicJsonFile]**（tmp → fsync → .bak → rename）。
 * 桌宠文件小，写坏了一样会「打开就空白」，而用户没有任何修复手段。
 *
 * ## 存什么、不存什么
 *
 * 只存 user / assistant 的**文本**，不存 tool call 与工具结果：
 *  - 工具轨迹对「接着聊」没有价值，答案里已经包含结论；
 *  - 更重要的是**配对完整性**：OpenAI 协议要求 `assistant.tool_calls` 后面必须跟
 *    对应的 tool 消息，只存一半会让下一次请求直接 400。要么完整存（含 JSON 参数），
 *    要么彻底不存，中间态是最坏的。
 *
 * 失败回复也不入历史（`PetReply.failed` 那条根本没有进 history），所以重启后不会
 * 看到昨天那句「网络不太好」——那是当时的故障，不是对话内容。
 *
 * ## 并发与合并
 *
 * 单线程写、**最新快照胜出**：连续几轮只会在队列里留下一次真实写入，中间那些
 * 快照被 `getAndSet(null)` 吃掉。桌宠一轮只有一个写点（回合结束），本来也不会堆积，
 * 但主线程上不允许出现「排队等磁盘」的形态。
 *
 * [clear] 用 generation 作废在途快照：用户点「清空」之后，任何还没落盘的旧快照都
 * 不能再把内容写回磁盘——否则会出现「清空了，重启又回来了」这种最伤信任的 bug。
 */
class PetHistoryStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 待写快照。`null` = 没有待写内容（或刚被 [clear] 作废）。
     *
     * 存的是**已经映射好的落盘记录**而不是 ChatMessage：映射（剥掉 tool call、
     * 截断超长文本）在调用线程做一次就够，不该让写线程重复干。
     */
    private val pending = AtomicReference<List<PetTurnRecord>?>(null)

    /**
     * 代数。每次 [clear] +1。
     *
     * 只靠 `pending == null` 挡不住竞争：写线程可能已经把旧快照取走、正在落盘，
     * 这时 clear 把文件删了，写线程随后又把旧内容写回来。代数让「取快照」与
     * 「落盘」之间有一个可比较的版本号，过期的快照直接丢弃。
     */
    private val generation = AtomicLong(0)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pet-history-writer").apply { isDaemon = true }
    }

    /**
     * 读出历史（**阻塞 IO**，只在后台线程调用）。
     *
     * 容错口径与 `ConversationStore` 一致：主文件坏了回退 `.bak`；两者都坏、
     * 版本号不认识、JSON 半截——一律返回空表。桌宠的历史丢了只是「不记得刚才」，
     * 而在这里抛异常会让悬浮窗一打开就崩，代价完全不成比例。
     */
    fun load(): List<ChatMessage> {
        val raw = runCatching {
            AtomicJsonFile.readWithBackupOrNull(file) { parseOrNull(it) != null }
        }.getOrNull() ?: return emptyList()
        val rec = parseOrNull(raw) ?: return emptyList()
        return rec.turns.mapNotNull { t ->
            when (t.role) {
                ROLE_USER -> UserMessage(t.text)
                ROLE_ASSISTANT -> AssistantMessage(t.text)
                // 未知角色（未来版本的 role / 手工编辑）安静跳过：宁可少一条，
                // 也不要猜成 assistant 把一句用户的话当成自己说的
                else -> null
            }
        }
    }

    /**
     * 覆盖保存当前历史（异步；调用方不会被磁盘拖住）。
     *
     * 传的是内存 history 的**副本**（调用方 `toList()`）：写线程拿到快照后，
     * 下一轮 ask 会 `clear()` + `addAll()` 改写同一个 list，共享引用会写出撕裂内容。
     */
    fun save(history: List<ChatMessage>) {
        val records = history.mapNotNull(::toRecord).takeLast(MAX_MESSAGES)
        pending.set(records)
        val gen = generation.get()
        runCatching { worker.execute { drain(gen) } }
            .onFailure { AgentLogger.w(TAG, "历史落盘排队失败：${it.message}") }
    }

    /**
     * 清空磁盘上的历史（用户点「清空对话」）。
     *
     * 与 [save] 走同一个队列，保证「删除」不会与一次在途写入交叉执行成「删了又写回来」。
     */
    fun clear() {
        pending.set(null)
        generation.incrementAndGet()
        runCatching {
            worker.execute {
                runCatching { file.delete() }
                runCatching { AtomicJsonFile.backupOf(file).delete() }
            }
        }
    }

    /** 队列里的写任务跑完（诊断 / harness 用；生产路径不等待）。 */
    fun awaitIdle(timeoutMs: Long = 2_000): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        if (runCatching { worker.execute { latch.countDown() } }.isFailure) return false
        return runCatching {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    private fun drain(gen: Long) {
        val records = pending.getAndSet(null) ?: return
        // 已被 clear 作废：这条快照属于上一代，不能再写回磁盘
        if (gen != generation.get()) return
        val payload = runCatching {
            json.encodeToString(PetHistoryFile(turns = records))
        }.getOrNull() ?: return
        runCatching { file.parentFile?.mkdirs() }
        val ok = runCatching { AtomicJsonFile.write(file, payload) }.getOrDefault(false)
        if (!ok) AgentLogger.w(TAG, "历史落盘失败（${file.name}）")
    }

    private fun toRecord(msg: ChatMessage): PetTurnRecord? = when (msg) {
        is UserMessage -> PetTurnRecord(ROLE_USER, msg.content.take(MAX_TEXT_CHARS))
        is AssistantMessage -> PetTurnRecord(ROLE_ASSISTANT, msg.content.take(MAX_TEXT_CHARS))
        // system / tool 一律不落盘，理由见类注释
        else -> null
    }

    private fun parseOrNull(text: String): PetHistoryFile? =
        if (text.isBlank()) null
        else runCatching { json.decodeFromString<PetHistoryFile>(text) }
            .getOrNull()
            // 未来版本写的文件：不认识就不猜，宁可当作没有历史
            ?.takeIf { it.version <= SCHEMA_VERSION }

    companion object {
        private const val TAG = "PetHistory"

        /**
         * 落盘上限 = 内存上限（[PetAgentSession] 的 `MAX_HISTORY`）。
         *
         * 刻意不存更多：如果磁盘里留 60 条、模型只用 20 条，重启后用户会看到
         * 一段比模型「记得」更长的对话，接着问时模型答不上来——那种不一致比少显示
         * 几条更让人困惑。**看到的和它记得的必须是同一份。**
         */
        const val MAX_MESSAGES = 20

        /** 单条正文上限。桌宠一句话问答，4000 字符已经是「模型失控」的量级。 */
        private const val MAX_TEXT_CHARS = 4_000

        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        /** 文件格式版本。读的时候只认 ≤ 本值。 */
        private const val SCHEMA_VERSION = 1
    }
}

/** 落盘的一条消息。字段名刻意短：这是自己的文件格式，不需要和 core 的类型对齐。 */
@Serializable
internal data class PetTurnRecord(val role: String, val text: String)

@Serializable
internal data class PetHistoryFile(
    val version: Int = 1,
    val turns: List<PetTurnRecord> = emptyList()
)
