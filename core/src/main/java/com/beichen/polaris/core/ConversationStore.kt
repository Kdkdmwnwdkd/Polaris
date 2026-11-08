package com.beichen.polaris.core

import com.beichen.polaris.core.io.AtomicJsonFile
import com.beichen.polaris.core.lifecycle.AppBackgroundHooks
import com.beichen.polaris.core.telemetry.StoreWriteTrace
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.UiItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 功能三：聊天记录（用户真机录屏反馈：「人家都有聊天记录对话记录，你这都没有，做好一点行吗」）。
 *
 * 持久化形态学 MemoryStore（app/src/main/java/com/beichen/polaris/memory/MemoryStore.kt，
 * 先读它学原子写模式）：
 *  - 原子写统一走 AtomicJsonFile（tmp → fsync → .bak → rename），`synchronized(lock)` 护 items；
 *  - 落盘走单线程执行器**异步串行 + 400ms 去抖**——工具事件（开始/结束）每次都追加消息，
 *    主线程同步写盘会 jank；串行执行器 FIFO 保序、不交叉。
 *
 * R-10 分片：主文件 conversations.json 只存 id 清单（[ConversationIndex]），
 * 每个会话单独落在 `conversations.d/<id>.json`（[ConversationShard]，只存 turn 真源）。
 * 追加一条消息只重写那**一个**分片，不再序列化全库、不再整库复制 .bak、不再
 * `messages`/`turns` 双写；旧整库文件在首次加载时一次性迁移（原文件由 .bak 兜住）。
 *
 * 跨包引用说明（UiItem 下沉在 core）：UiItem ↔ MsgRecord 映射与存储同文件，
 * 避免 VM 里散落「内存格式 / 存储格式 / 历史格式」三家转换；core 不反向依赖 ui 的其他东西。
 */

/**
 * role 常量：字符串散落各处必然拼写漂移，收成文件级常量。
 * `internal`（而非 `private`）：[ConversationBranch]（同包另一文件）迁移/展开时也复用同一常量，
 * 避免两处各写一份 "user"/"assistant" 字符串漂移。
 */
internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"
internal const val ROLE_TOOL = "tool"

/**
 * R-06：给「没跑完就被中断」的工具卡补的结果文案（唯一一处，UI 与落盘共用）。
 *
 * 措辞刻意不写「失败」：用户点停止时工具可能已经生效（写文件、发请求），
 * 说「失败」会让人以为没发生，说「已完成」更糟。只陈述可确认的事实——没有拿到结果。
 */
internal const val TOOL_INTERRUPTED_NOTE = "⏹ 该调用未完成：运行被中断，未取得真实结果（写操作可能已生效）。"

/** 新对话默认标题：首条 user 消息到达时按内容前 [TITLE_CHARS] 字自动命名。 */
private const val DEFAULT_TITLE = "新对话"

/**
 * 单条消息内容/入参的落盘上限。
 * 为什么截断：ui_tree 转储、read_file 大文件等工具结果可达数十 KB，200 条不截断的
 * 会话能把单个分片撑到 MB 级。分片后只重写这一个文件，但流式追加仍会反复 fsync 它，
 * 截断仍是必要的。UI 气泡仍显示完整结果——截断只影响落盘的那一份。
 */
private const val MAX_FIELD_CHARS = 4000

/**
 * 会话摘要（抽屉「最近对话」区直接消费，不加载消息正文——列表要轻）。
 *
 * @param updatedAt 最近一次消息/重命名的时间（列表倒序键）。
 * @param messageCount 消息条数（摘要里展示，用户判断「这个会话聊到哪了」）。
 * @param promptTokens / completionTokens 功能五（P1-5）：本会话累计 token 用量
 *   （对所有轮次的助手变体求和——重新生成产生的废弃变体也真实花过钱，一并计入）。
 */
@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    /** 功能五（P1-3）：置顶标记——置顶会话恒排在列表最前（对标 RikkaHub pinned_chats）。 */
    val pinned: Boolean = false,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /** W-28：绑定的项目目录（空 = 工作区根）。 */
    val workspaceDir: String = ""
)

/**
 * 一条落盘消息。
 *
 * @param role user / assistant / tool 三选一（见 [ROLE_USER] 等常量）。
 * @param id user/assistant 用 UUID；tool 用 ToolCall.id（与内存 _messages 的 Hotfix2 去重键一致）。
 * @param toolName / toolArgs 仅 tool 消息有值；arguments 本就是 JSON 字符串，直接存。
 * @param isError 终态是否失败（执行中态落盘时是空结果占位，终态到达后按 id 覆盖）。
 * @param promptTokens / completionTokens 功能五（P1-5）：assistant 消息的 token 用量
 *   （0 = 无数据——服务端没给 usage 或该消息非助手消息；旧文件反序列化自动补 0）。
 */
@Serializable
data class MsgRecord(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /**
     * 功能五（P1-9）：assistant 消息的推理过程（思维链全文）。
     * 空 = 无思考（旧文件/非推理模型）；与 content 分字段存储，
     * 读回历史时不把自言自语当对话内容发给下一轮。
     */
    val reasoning: String = "",
    /**
     * W-30：消息时间（epoch 毫秒）。0 = 旧数据/未记录——UI 不显示时间行，而不是写个 1970。
     */
    val timeMs: Long = 0,
    /**
     * R-06：该 tool 记录**只有「执行中」的占位，终态还没来**。
     *
     * 为什么要落这个标记，而不是「结果为空就当没跑完」：工具正常返回空输出（静默命令、
     * grep 无匹配）同样是空结果，按空判断会把真终态误改成「已中断」。这个标记是
     * 「这一段写于 ToolStarted、ToolFinished 尚未到达」的唯一证据，取消 / 失败 /
     * 进程被杀三条路径都靠它区分「没跑完」与「跑完了没输出」。
     *
     * 旧文件没有该字段 → false（历史记录按终态看待，与改动前一致）。
     */
    val pending: Boolean = false
)

/**
 * 一个完整会话（含全部消息）。
 *
 * ## 双字段 = 向后兼容 + 分支能力（Phase 2 DoD②）
 * @param messages 平坦消息历史（**旧格式字段，保持不变**）：线性渲染与「未分叉会话」的权威来源。
 *   新版仍写它（值 = [turns] 的 [ConversationBranch.flatten]），故**旧版本 App 读新文件**时不认识
 *   `turns` 也能照常显示（ignoreUnknownKeys），新旧双向兼容。
 * @param turns 轮次变体结构（**新增字段，默认空**）：重新生成/左右切换的真源。旧文件缺此字段时
 *   `load()` 用 [ConversationBranch.fromMessages] 从 [messages] 迁移补齐（变体数=1、游标=0，历史不丢）。
 */
@Serializable
data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MsgRecord> = emptyList(),
    val turns: List<TurnRecord> = emptyList(),
    /**
     * 功能五（P1-3）：置顶标记（默认 false，旧文件反序列化自动补齐）。
     * 置顶只影响排序，不改变任何消息语义——取消置顶即回到按时间排序。
     */
    val pinned: Boolean = false,
    /**
     * W-28：本会话绑定的项目目录（工作区内的相对路径；空 = 工作区根）。
     * 绑定时会把该目录下的 AGENTS.md / README.md 作为项目规则注入系统提示。
     */
    val workspaceDir: String = ""
)

/**
 * UiItem → MsgRecord（写入侧映射）。
 *
 * tool 消息的 images（data URL）不落盘：截图动辄数百 KB，存进去文件会失控；
 * 重进会话时工具卡无图但结果文字还在——可接受的取舍（录屏反馈要的是「有记录」）。
 */
/**
 * 3.5 拆模块：从 internal 放宽为 public。
 *
 * internal 是**模块级**可见性，`:core` 独立成模块后 app 侧（ChatViewModel /
 * ConversationProjection）就看不见了。这两个映射函数是宿主写会话与渲染会话的必经之路，
 * 属于模块对外的稳定契约，放宽是结构变化带来的必要调整，不是封装被破坏。
 */
fun UiItem.toMsgRecord(): MsgRecord = when (this) {
    is UiItem.User -> MsgRecord(
        id = UUID.randomUUID().toString(),
        role = ROLE_USER,
        content = text.take(MAX_FIELD_CHARS),
        timeMs = timeMs
    )

    is UiItem.Assistant -> MsgRecord(
        id = UUID.randomUUID().toString(),
        role = ROLE_ASSISTANT,
        content = text.take(MAX_FIELD_CHARS),
        // 功能五（P1-5）：token 用量随气泡落盘（无 usage 存 0，读时按 0 还原为 null）
        promptTokens = usage?.promptTokens ?: 0L,
        completionTokens = usage?.completionTokens ?: 0L,
        // 功能五（P1-9）：推理过程随气泡落盘（截断纪律同正文）
        reasoning = reasoning.orEmpty().take(MAX_FIELD_CHARS),
        timeMs = timeMs
    )

    is UiItem.Tool -> MsgRecord(
        // tool 的 id = call.id：appendMessage 据此覆盖「执行中 → 终态」两次写入
        id = call.id,
        role = ROLE_TOOL,
        content = result.take(MAX_FIELD_CHARS),
        toolName = call.name,
        // arguments 是 JsonObject，toString() 即标准 JSON 文本，直接存
        toolArgs = call.arguments.toString().take(MAX_FIELD_CHARS),
        isError = isError,
        // R-06：只有「执行中」这一段落 pending=true；终态落盘时一并清掉
        pending = running && !interrupted
    )
}

/** MsgRecord → UiItem（读取侧映射）。 */
internal fun MsgRecord.toUiItem(): UiItem = when (role) {
    ROLE_TOOL -> UiItem.Tool(
        call = ToolCall(
            id = id,
            name = toolName.orEmpty(),
            arguments = parseToolArgs(toolArgs)
        ),
        result = content,
        isError = isError,
        // R-06：把「落盘时还在执行」还原成执行中。要不要显示成转圈由调用方裁决
        // （[com.beichen.polaris.ui.ConversationProjection.loadOf] 只在「这条会话确实
        // 正在跑」时保留 running，否则翻成「已中断」）——这里只管忠实还原事实。
        running = pending
    )

    ROLE_USER -> UiItem.User(content, timeMs = timeMs)

    // 功能五（P1-5）：还原 token 用量；两个计数都 0 = 无数据（旧文件/供应商没给）→ null
    ROLE_ASSISTANT -> UiItem.Assistant(
        content,
        usage = if (promptTokens > 0 || completionTokens > 0) {
            TokenUsage(promptTokens, completionTokens)
        } else {
            null
        },
        // 功能五（P1-9）：还原推理过程（空白 = 无思考，UI 不渲染折叠块）
        reasoning = reasoning.takeIf { it.isNotBlank() },
        timeMs = timeMs
    )
    // 未知 role 兜底按 assistant 渲染：宁可显示不错丢（历史数据兼容）
    else -> UiItem.Assistant(content)
}

/** List<MsgRecord> → List<UiItem>（VM 载入会话用）。 */
internal fun List<MsgRecord>.toUiItems(): List<UiItem> = map { it.toUiItem() }

/**
 * ConversationRecord → List<UiItem>（**基于 [ConversationRecord.turns] 渲染**，Phase 2 DoD②）。
 *
 * 为什么不再用 `messages.toUiItems()`：变体切换后 UI 必须体现「当前选中变体」，而
 * `messages` 只是 flatten 缓存；逐轮渲染 [TurnRecord] 才能给助手气泡带上
 * `variantIndex / variantCount / turnIndex` 元数据，供切换器 `← 2/3 →` 消费。
 *
 * 单轮顺序：`user → trace（工具卡等）→ 选中变体`，与 [ConversationBranch.flatten] 一致。
 * trace 里的消息按 [MsgRecord.toUiItem] 还原（工具卡元数据默认，不参与切换）。
 */
fun ConversationRecord.toUiItems(): List<UiItem> = buildList {
    turns.forEachIndexed { turnIndex, turn ->
        add(UiItem.User(turn.user.content, turnIndex, timeMs = turn.user.timeMs))
        turn.trace.forEach { add(it.toUiItem()) }
        val sel = turn.variants.getOrNull(turn.selectedIndex)
        if (sel != null) {
            add(
                UiItem.Assistant(
                    sel.content,
                    variantIndex = turn.selectedIndex,
                    variantCount = turn.variants.size,
                    turnIndex = turnIndex,
                    // 功能五（P1-5）：选中变体的 token 用量一起还原（无数据 → null）
                    usage = if (sel.promptTokens > 0 || sel.completionTokens > 0) {
                        TokenUsage(sel.promptTokens, sel.completionTokens)
                    } else {
                        null
                    },
                    // 功能五（P1-9）：选中变体的推理过程一起还原（空白 → null）
                    reasoning = sel.reasoning.takeIf { it.isNotBlank() },
                    timeMs = sel.timeMs
                )
            )
        }
    }
}

/** 解析落盘的 toolArgs；损坏/为空时退回空 object（工具卡入参块显示空白而非崩溃）。 */
private fun parseToolArgs(raw: String?): JsonObject {
    if (raw.isNullOrBlank()) return JsonObject(emptyMap())
    // W-47：改用软转换——落盘里若出现 null/非对象（历史脏数据、手工编辑），
    // 硬转换会抛 IllegalArgumentException，工具卡整块渲染失败；这里退化为空对象。
    return Json.parseToJsonElement(raw).objOrNull() ?: JsonObject(emptyMap())
}

/**
 * 聊天记录存储：多会话 + 消息持久化（功能三）。
 *
 * 容量策略：单会话消息数封顶 [MAX_MESSAGES] 条，超出丢最旧——聊天记录要「有」，
 * 但不能让一个超长会话把文件撑到影响读写性能。
 */
class ConversationStore(file: File) {

    private val file: File = file

    /**
     * R-10：分片目录（`<主文件名去扩展名>.d/`）。一个 `<id>.json` 装一个会话，
     * 主文件退化成 [ConversationIndex]（只存 id 清单，不含任何消息正文）。
     */
    private val shardDir = File(file.parentFile, file.nameWithoutExtension + SHARD_DIR_SUFFIX)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val items = mutableListOf<ConversationRecord>()

    /** R-10：自上次落盘以来被改动的会话 id——只写这些分片，不再全库重写。 */
    private val dirtyIds = LinkedHashSet<String>()
    /** R-10：被删除的会话 id（落盘时清掉对应分片，避免孤儿文件）。 */
    private val removedIds = LinkedHashSet<String>()
    /**
     * R-10 诊断：最近一次落盘真正写出的分片 id 集合。
     * 「改 1 个会话只写 1 个分片」的硬证据——只数 [persistCount] 分不清
     * 「一次写只碰了一个分片」和「一次写又把全库重写了一遍」。
     */
    @Volatile
    var lastWrittenShardIds: Set<String> = emptySet()
        private set

    /**
     * 落盘执行器：**单线程 + 延迟调度**（去抖），daemon。
     *
     * 为什么异步：工具事件（开始/结束）每次都会追加消息，主线程同步重写整个
     * conversations.json 会 jank；单线程保证写序不乱、不会两个 persist 交叉。
     *
     * 为什么要去抖（R12 补）：流式输出时每个 token 都会追加一条 assistant 消息，
     * 旧实现是「每次变更排一次全量写」。会话到几万字时，一次全量序列化 + 写盘
     * 要几十毫秒，而 token 每秒来几十个——写盘直接把写线程压满，越聊越卡。
     * 加了 fsync 之后这个代价更明显（每次都要等磁盘确认）。
     *
     * 现在：变更只置脏标记并把「400ms 后写」的任务往后推；窗口内的 N 次变更
     * 合并成一次写。进程被杀最多丢最后 400ms 的增量（流式输出中断本来就会丢当前
     * 那段没写完的回复，不会额外损失）。
     */
    private val persistScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "conversation-store-writer").apply { isDaemon = true }
    }

    /**
     * 已执行的全量写次数（诊断用）。
     *
     * 为什么要暴露：用户报「聊久了变卡」时，先看这个数是每秒几次。没有它只能
     * 凭感觉猜「是不是写盘太频繁」，而写盘是最容易被误当成嫌疑人的一项。
     */
    @Volatile
    var persistCount: Long = 0L
        private set

    /** 有未落盘的变更。 */
    private val persistDirty = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 自上次落盘以来累积的变更次数（P5-1）。
     *
     * 去抖到底省了多少次写，只能靠它算：一次 flush 里 `coalesced=7` 说明
     * 7 次流式追加被并成 1 次 fsync。没有这个数，「写入量降下来了」到底是
     * 去抖的功劳还是根本就没有写，分不清。
     */
    private val pendingMutations = java.util.concurrent.atomic.AtomicInteger(0)

    /** 已排的延迟任务，用来在下次变更时取消旧的（这就是「去抖」本身）。 */
    private val pendingPersist = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<*>?>(null)

    init {
        runCatching { file.parentFile?.mkdirs() }
        load()
        // R-19：`flush()` 写了很久，但全仓没有调用者——「切后台补一次落盘」这条注释
        // 承诺的行为从未发生。这里把存储层挂到进程级后台广播上：切后台即刻止血，
        // 不再赌 400ms 去抖窗口。注册是弱引用（见 AppBackgroundHooks 的注释），
        // 本实例被回收后该 hook 自动失效，不会去写一个没人用的 store。
        AppBackgroundHooks.register(this) { owner -> (owner as ConversationStore).flush() }
    }

    /**
     * 会话摘要列表（功能五 P1-3：**置顶优先**，组内按 updatedAt 倒序）。
     *
     * 排序契约：`pinned` 降序 → `updatedAt` 降序。用两级比较而非单键，是因为
     * 置顶语义必须是「永远在最前」，不能被新消息顶下去（RikkaHub pinned_chats 同语义）。
     */
    fun list(): List<ConversationSummary> = synchronized(lock) {
        items.map { rec ->
            // 功能五（P1-5）：对**所有轮次的全部变体**求和——重新生成产生的废弃变体
            // 也真实发生过计费，计入比只算选中变体更贴近用户的钱包
            val usage = TokenUsage.sum(
                rec.turns.flatMap { it.variants }
                    .filter { it.role == ROLE_ASSISTANT }
                    .map { TokenUsage(it.promptTokens, it.completionTokens) }
            )
            ConversationSummary(
                id = rec.id,
                title = rec.title,
                updatedAt = rec.updatedAt,
                messageCount = rec.messages.size,
                pinned = rec.pinned,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                workspaceDir = rec.workspaceDir
            )
        }
            .sortedWith(compareByDescending<ConversationSummary> { it.pinned }
                .thenByDescending { it.updatedAt })
    }

    /**
     * 功能五（P1-3）：置顶 / 取消置顶。
     *
     * 只置排序键，不动 updatedAt——否则「置顶」会把会话伪装成「刚聊过」，
     * 取消置顶后它还会赖在列表顶部，用户会以为没生效。
     */
    fun setPinned(convId: String, pinned: Boolean) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            items[idx] = items[idx].copy(pinned = pinned)
            dirtyIds.add(convId)
        }
        schedulePersist()
    }

    /** 加载完整会话；不存在返回 null。返回拷贝，防调用方改内部状态。 */
    fun load(id: String): ConversationRecord? = synchronized(lock) {
        items.firstOrNull { it.id == id }?.let { it.copy(messages = it.messages.toList()) }
    }

    /** 新建会话（标题「新对话」，首条 user 消息到达时自动命名），返回 id。 */
    fun create(): String {
        val now = System.currentTimeMillis()
        val rec = ConversationRecord(
            id = UUID.randomUUID().toString(),
            title = DEFAULT_TITLE,
            createdAt = now,
            updatedAt = now,
            messages = emptyList()
        )
        synchronized(lock) {
            items.add(rec)
            dirtyIds.add(rec.id)
        }
        schedulePersist(urgent = true)
        return rec.id
    }

    /**
     * 追加一条消息（同步更新内存 + 预约落盘）。
     *
     * 两条规则：
     * ① tool 消息按 id 覆盖——ToolStarted（空结果占位）与 ToolFinished（终态）是同一
     *    call.id 的两次写入，后者必须替换前者，与内存 _messages 的 replace 语义一致；
     * ② 首条 user 消息且标题仍是「新对话」时，title = 内容前 [TITLE_CHARS] 字符
     *    （和别家聊天记录一样自动命名，省用户手打）。
     */
    fun appendMessage(convId: String, rec: MsgRecord) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            // 会话已被删：静默丢弃，不崩溃（VM 有运行中守卫，双保险）
            if (idx < 0) return
            val conv = items[idx]
            // Phase 2 DoD②：先并入轮次变体结构，再据其展开为平坦缓存 messages
            val turns = appendToTurns(conv.turns, rec)
            val messages = ConversationBranch.flatten(turns)
            var title = conv.title
            if (title == DEFAULT_TITLE && rec.role == ROLE_USER && rec.content.isNotBlank()) {
                title = rec.content.trim().take(TITLE_CHARS)
            }
            items[idx] = conv.copy(
                title = title,
                updatedAt = System.currentTimeMillis(),
                messages = messages,
                turns = turns
            )
            dirtyIds.add(convId)
        }
        schedulePersist()
    }

    /**
     * 把一条新消息并入轮次变体结构（纯函数，Phase 2 DoD②）：
     *  - user → 新开一轮（[TurnRecord.user]）；
     *  - assistant → 作为**当前轮**的助手变体追加并把游标指向它（重新生成也走这条：追加=新变体，
     *    原变体保留可切回）；
     *  - tool → 按 id upsert 进**当前轮的 trace**（ToolStarted 空结果占位 → ToolFinished 终态覆盖，
     *    语义与旧 `messages` 的 replace 一致）。
     * 轮数超 [MAX_MESSAGES] 丢最旧轮；无当前轮（异常数据）时安静忽略，绝不崩溃。
     */
    private fun appendToTurns(turns: List<TurnRecord>, rec: MsgRecord): List<TurnRecord> {
        val out = turns.toMutableList()
        when (rec.role) {
            ROLE_USER -> out.add(TurnRecord(user = rec))
            ROLE_ASSISTANT -> {
                val i = out.lastIndex
                if (i >= 0) {
                    val t = out[i]
                    val variants = t.variants + rec
                    out[i] = t.copy(variants = variants, selectedIndex = variants.lastIndex)
                }
            }
            ROLE_TOOL -> {
                val i = out.lastIndex
                if (i >= 0) {
                    val t = out[i]
                    val trace = t.trace.toMutableList()
                    val e = trace.indexOfFirst { it.role == ROLE_TOOL && it.id == rec.id }
                    if (e >= 0) trace[e] = rec else trace.add(rec)
                    out[i] = t.copy(trace = trace)
                }
            }
            else -> Unit
        }
        while (out.size > MAX_MESSAGES) out.removeAt(0)
        return out
    }

    /**
     * 把某会话里仍是「执行中」占位的工具记录补成终态（R-06）。幂等。
     *
     * ## 为什么必须补
     * 工具卡是**两段写**：`ToolStarted` 先落一条空结果占位，`ToolFinished` 按 id 覆盖成终态。
     * 取消 / 失败 / 进程被杀时第二段永远不来，占位就留在文件里——重新载入后它既不是失败
     * 也不是成功（改动前按空结果渲染成「已完成」，等于对着一条没跑完的调用说「已完成」），
     * 而且下一次运行也没有人认领它。
     *
     * 判据只看 [MsgRecord.pending]，**不看「结果为空」**：静默命令、grep 无匹配这类
     * 正常返回的空输出同样是空结果，按空判会把真终态改成「已中断」。
     *
     * @return 补了几条；0 = 无需补（不动内存、不触发落盘）
     */
    fun settlePendingTools(convId: String): Int {
        var settled = 0
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return 0
            val conv = items[idx]
            val turns = conv.turns.map { turn ->
                if (turn.trace.none { it.role == ROLE_TOOL && it.pending }) turn
                else turn.copy(
                    trace = turn.trace.map { rec ->
                        if (rec.role != ROLE_TOOL || !rec.pending) rec
                        else {
                            settled++
                            rec.copy(
                                // 占位的内容恒为空；真有半截内容时保留它，只补状态
                                content = rec.content.ifBlank { TOOL_INTERRUPTED_NOTE },
                                isError = true,
                                pending = false
                            )
                        }
                    }
                )
            }
            if (settled > 0) {
                items[idx] = conv.copy(
                    updatedAt = System.currentTimeMillis(),
                    turns = turns,
                    messages = ConversationBranch.flatten(turns)
                )
                dirtyIds.add(convId)
            }
        }
        if (settled > 0) schedulePersist(urgent = true)
        return settled
    }

    /**
     * 左右切换某轮的选中变体（Phase 2 DoD②），[delta] = -1 左 / +1 右。
     * 委托 [ConversationBranch.select]（边界 clamp：首个左移、末个右移原地不动；单变体轮不响应）。
     * 切换后同步写回 `messages = flatten(turns)`，保证「下一次请求的历史」用的是选中变体。
     * 会话不存在或轮次越界时静默 no-op（不崩）。
     *
     * @return 切换后该轮选中的变体下标（无法定位时为 0）。
     */
    fun selectVariant(convId: String, turnIndex: Int, delta: Int): Int {
        var selected = 0
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return 0
            val conv = items[idx]
            val turns = ConversationBranch.select(conv.turns, turnIndex, delta)
            selected = turns.getOrNull(turnIndex)?.selectedIndex ?: 0
            items[idx] = conv.copy(
                updatedAt = System.currentTimeMillis(),
                turns = turns,
                messages = ConversationBranch.flatten(turns)
            )
            dirtyIds.add(convId)
        }
        schedulePersist()
        return selected
    }

    /**
     * 为第 [turnIndex] 轮追加一个助手变体并选中（重新生成的持久化写入口）。
     * 轮次越界时 no-op（[ConversationBranch.appendVariant] 原样返回），不崩。
     */
    fun appendVariant(convId: String, turnIndex: Int, variant: MsgRecord) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            val conv = items[idx]
            val turns = ConversationBranch.appendVariant(conv.turns, turnIndex, variant)
            items[idx] = conv.copy(
                updatedAt = System.currentTimeMillis(),
                turns = turns,
                messages = ConversationBranch.flatten(turns)
            )
            dirtyIds.add(convId)
        }
        schedulePersist()
    }

    /**
     * 「从中间轮重新发问」的截断半程：保留 `turns[0..keepThroughIndex]`（含该轮），丢弃其后所有轮。
     * 新问题由调用方（VM）随后以 user 消息开一轮（与 send 路径一致）。
     * KDoc 裁决见 [ConversationBranch.reaskAfter]。
     *
     * @return 截断后剩余的轮次数（会话不存在时为 0）。
     */
    fun truncateAfter(convId: String, keepThroughIndex: Int): Int {
        var remaining = 0
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return 0
            val conv = items[idx]
            val end = (keepThroughIndex + 1).coerceIn(0, conv.turns.size)
            val turns = conv.turns.take(end)
            remaining = turns.size
            items[idx] = conv.copy(
                updatedAt = System.currentTimeMillis(),
                turns = turns,
                messages = ConversationBranch.flatten(turns)
            )
            dirtyIds.add(convId)
        }
        schedulePersist()
        return remaining
    }

    /** 重命名（空名回落默认标题，防用户清空文本框后按确定）。 */
    fun rename(convId: String, title: String) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            items[idx] = items[idx].copy(
                title = title.trim().ifBlank { DEFAULT_TITLE },
                updatedAt = System.currentTimeMillis()
            )
            dirtyIds.add(convId)
        }
        schedulePersist()
    }

    /**
     * W-28：绑定/解绑项目目录（空串 = 解绑，回到工作区根）。
     * 只改记录字段，不影响消息内容。
     */
    fun setWorkspaceDir(convId: String, dir: String) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            items[idx] = items[idx].copy(
                workspaceDir = dir.trim().trim('/'),
                updatedAt = System.currentTimeMillis()
            )
            dirtyIds.add(convId)
        }
        schedulePersist()
    }

    /** 删除会话（含其全部消息，不可恢复——UI 层已有二次确认）。 */
    fun delete(convId: String) {
        synchronized(lock) {
            items.removeAll { it.id == convId }
            dirtyIds.remove(convId)
            removedIds.add(convId)
        }
        schedulePersist(urgent = true)
    }

    /**
     * 保证至少有一个会话：列表空则 create 一个；否则返回最近更新的会话 id。
     * VM 初始化用（首启即有一个可写会话，聊天记录不会「无处可写」）。
     */
    fun ensureNotEmpty(): String {
        synchronized(lock) {
            items.maxByOrNull { it.updatedAt }?.let { return it.id }
        }
        return create()
    }

    /**
     * 功能五（P1-4b）：全量快照（备份用）。返回不可变拷贝——调用方拿到的是
     * records 的浅拷贝列表，record 内部 list 均为不可变 List，外部改不动。
     */
    fun snapshot(): List<ConversationRecord> = synchronized(lock) { items.toList() }

    /**
     * 功能五（P1-4b）：整库重灌（恢复用，**破坏性**——调用方 UI 必须先拿到用户确认）。
     *
     * 三条保证：
     *  ① 每条都过 [migrate]：恢复老备份件（无 turns 字段）时按新格式迁移，不丢历史；
     *  ② 同步替换内存 + 预约落盘：恢复完成后即使进程被杀，落盘内容也是恢复后的数据；
     *  ③ 与 [load] 同语义的 items 替换，无并发窗口（synchronized）。
     * 调用方负责随后重载 UI 状态（会话列表/当前会话/消息），见 ChatViewModel.restoreBackup。
     */
    fun replaceAll(list: List<ConversationRecord>) {
        synchronized(lock) {
            val oldIds = items.map { it.id }.toSet()
            items.clear()
            items.addAll(list.map { migrate(it) })
            val newIds = items.map { it.id }.toSet()
            dirtyIds.clear()
            dirtyIds.addAll(newIds)
            removedIds.clear()
            removedIds.addAll(oldIds - newIds)
        }
        schedulePersist(urgent = true)
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 读盘（R-10）：主文件是 [ConversationIndex] 时按 id 逐个读分片；
     * 主文件还是旧「整库 JSON 数组」时一次性迁移为分片——旧数组由
     * [AtomicJsonFile.write] 自动留成 `.bak`，可回退，不删用户数据。
     *
     * 主文件损坏 / 半截 JSON 由 [AtomicJsonFile.readWithBackupOrNull] 回退 `.bak`；
     * 单个分片损坏由该分片自己的 `.bak` 兜底——容错粒度从「整库」细到「单会话」。
     * 读入的每条会话都过 [migrate]（旧格式 → 轮次变体，向后兼容）。
     */
    private fun load() {
        val raw = AtomicJsonFile.readWithBackupOrNull(file) { text ->
            parseIndexOrNull(text) != null || parseOrNull(text) != null
        }
        if (raw == null) {
            // 主 index 与 .bak 都不可用：分片才是数据真源，扫描目录重建索引。
            // 旧实现「整库单文件」在这里只能认输，分片后每个会话各自完好。
            recoverFromShards()
            return
        }
        val index = parseIndexOrNull(raw)
        if (index != null) {
            val records = index.ids.mapNotNull { id -> readShard(id) }
            synchronized(lock) {
                items.clear()
                items.addAll(records.map { migrate(it) })
            }
            return
        }
        val legacy = parseOrNull(raw) ?: return
        val migrated = legacy.map { migrate(it) }
        synchronized(lock) {
            items.clear()
            items.addAll(migrated)
        }
        migrateLegacyToShards(migrated)
    }

    /** 主文件新格式判定：JSON 对象 + schemaVersion ≥ 2。旧整库是数组，天然不匹配。 */
    private fun parseIndexOrNull(text: String): ConversationIndex? =
        if (!text.trimStart().startsWith("{")) null
        else runCatching { json.decodeFromString<ConversationIndex>(text) }
            .getOrNull()?.takeIf { it.schemaVersion >= ConversationIndex.SCHEMA_VERSION }

    /** 分片解析（单个会话）。 */
    private fun parseShardOrNull(text: String): ConversationShard? =
        if (text.isBlank()) null
        else runCatching { json.decodeFromString<ConversationShard>(text) }.getOrNull()

    /**
     * 分片文件名：合法 id 原样；其它（备份里可能夹带的历史脏 id）按 UTF-8 hex 编码，
     * 杜绝 `../` 之类的路径穿越，且保证同一 id 的加载与写入落到同一个文件。
     */
    private fun shardFile(id: String): File {
        val safe = if (SAFE_ID.matches(id)) id
        else "h-" + id.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(shardDir, "$safe.json")
    }

    /** 读一个分片：主分片坏了回退该分片的 .bak；两者都不可用返回 null（该会话跳过，不崩）。 */
    private fun readShard(id: String): ConversationRecord? {
        val raw = AtomicJsonFile.readWithBackupOrNull(shardFile(id)) { text ->
            parseShardOrNull(text) != null
        } ?: return null
        return parseShardOrNull(raw)?.toRecord()
    }

    /** 写一个分片：只编码 turn 真源，`messages` 不落盘（R-10 消除双写）。返回写出的字节数。 */
    private fun writeShard(rec: ConversationRecord): Int {
        val payload = runCatching { json.encodeToString(ConversationShard.of(rec)) }.getOrNull() ?: return 0
        return if (AtomicJsonFile.write(shardFile(rec.id), payload)) payload.toByteArray(Charsets.UTF_8).size else 0
    }

    /** 旧整库数组 → 分片 + 新 index（一次性，启动时最多跑一次）。 */
    private fun migrateLegacyToShards(records: List<ConversationRecord>) {
        runCatching { shardDir.mkdirs() }
        for (rec in records) writeShard(rec)
        val payload = json.encodeToString(ConversationIndex(ids = records.map { it.id }))
        // file 此刻仍是旧整库数组：AtomicJsonFile 会先把它复制成 .bak，再 rename 成 index。
        AtomicJsonFile.write(file, payload)
    }

    /**
     * 主索引彻底损坏（含 .bak 也坏）时按分片目录重建 index。
     * 只认 `*.json` 分片，天然排除 `*.json.bak`；文件名是安全编码后的 id stem，
     * 与 [shardFile] 的映射一一对应，读回的 record 仍用分片内容里的真实 id。
     */
    private fun recoverFromShards() {
        val ids = runCatching {
            shardDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.map { it.name.removeSuffix(".json") }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        val records = ids.mapNotNull { id -> readShard(id) }
        synchronized(lock) {
            items.clear()
            items.addAll(records.map { migrate(it) })
        }
        val payload = runCatching { json.encodeToString(ConversationIndex(ids = ids)) }.getOrNull() ?: return
        runCatching { AtomicJsonFile.write(file, payload) }
    }

    /**
     * 单条会话的旧格式迁移（纯函数，Phase 2 DoD②）：
     *  - [ConversationRecord.turns] 非空（新格式）→ 只做 [ConversationBranch.normalize]（夹游标）；
     *  - turns 为空、messages 非空（**旧格式，无 variants 字段**）→ [ConversationBranch.fromMessages]
     *    迁移：每个旧会话变体数=1、selectedIndex=0，历史内容进 trace/variants，**不丢**；
     *  - messages 也为空 → 空会话，turns 仍为空。
     *
     * messages 权威保留（不重算）——良构旧数据 `flatten(fromMessages(messages)) == messages`，
     * 故迁移后再落盘内容零变化；仅当新格式文件 messages 意外为空时用 flatten 回填做兜底。
     */
    private fun migrate(rec: ConversationRecord): ConversationRecord {
        val turns = if (rec.turns.isNotEmpty()) {
            ConversationBranch.normalize(rec.turns)
        } else if (rec.messages.isNotEmpty()) {
            ConversationBranch.normalize(ConversationBranch.fromMessages(rec.messages))
        } else {
            emptyList()
        }
        val messages = if (rec.messages.isEmpty() && turns.isNotEmpty()) {
            ConversationBranch.flatten(turns)
        } else {
            rec.messages
        }
        return rec.copy(messages = messages, turns = turns)
    }

    /**
     * 预约一次异步落盘。
     *
     * @param urgent true = 立即写（结构性变更：新建/删除/整库恢复）。
     *   这类操作 400ms 内被杀会「看起来没生效」——删掉的会话又回来了，
     *   比丢一条流式增量难解释得多，所以不吃去抖窗口。
     */
    private fun schedulePersist(urgent: Boolean = false) {
        persistDirty.set(true)
        pendingMutations.incrementAndGet()
        if (urgent) {
            runCatching { pendingPersist.getAndSet(null)?.cancel(false) }
            runCatching { persistScheduler.execute { flushNow() } }
            return
        }
        runCatching {
            val next = persistScheduler.schedule(
                { flushNow() },
                PERSIST_DEBOUNCE_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            pendingPersist.getAndSet(next)?.cancel(false)
        }
    }

    /** 一次落盘要处理的脏集快照：在锁内一次取齐，锁外只碰文件，不读内存态。 */
    private data class PersistBatch(
        val dirty: Set<String>,
        val removed: Set<String>,
        val records: List<ConversationRecord>
    )

    /**
     * 把脏数据写下去（幂等：没有脏标记就直接返回）。
     *
     * 标志在写之前清除——写的过程中产生的新变更会重新置脏并排下一轮，
     * 不会因为「清得太晚」被吞掉。只有真的写出了内容才计 [persistCount]：
     * 写方法「锁内改脏集 / 锁外置标记」之间有一个极窄窗口，可能排出一次空任务，
     * 把空任务计进去会让去抖判据（R13-3）失真。
     */
    private fun flushNow() {
        if (!persistDirty.getAndSet(false)) return
        // 取走累积计数（与清脏标记同一处），保证「合并了几次」与「这次写」一一对应
        val mutations = pendingMutations.getAndSet(0)
        runCatching {
            if (persist(mutations)) persistCount++
        }
    }

    /**
     * 立即落盘（App 切后台 / 进程即将退出 / 用户点了「导出」时调用）。
     *
     * 不去抖窗口里干等：把写任务直接插到调度器队列，会在 400ms 内完成。
     * 不再先判 persistDirty——无条件排任务，由 [flushNow] 的幂等挡住空写，
     * 顺带堵住「锁内已改脏集、锁外还没置标记」这个窗口里漏掉的一次 flush。
     */
    fun flush() {
        runCatching { persistScheduler.execute { flushNow() } }
    }

    /**
     * R-10：只把**脏会话**写成各自的分片；会话集合变了才顺带重写轻量 index。
     *
     * 与旧实现的三点差别：① 不再 `encodeToString(items)` 序列化全库；
     * ② `.bak` 由 [AtomicJsonFile] 落在**单个分片**上，不再复制整库；
     * ③ 每个分片只存 turn 真源，`messages` 读时现算，双写消失。
     *
     * @return 是否真的写出了内容（供 [flushNow] 决定计不计 [persistCount]）。
     */
    private fun persist(mutations: Int = 1): Boolean {
        val batch = synchronized(lock) {
            val b = PersistBatch(
                dirty = dirtyIds.toSet(),
                removed = removedIds.toSet(),
                records = items.toList()
            )
            dirtyIds.clear()
            removedIds.clear()
            b
        }
        if (batch.dirty.isEmpty() && batch.removed.isEmpty()) return false

        val started = System.currentTimeMillis()
        runCatching { shardDir.mkdirs() }
        // 删除会话：清掉分片与它的 .bak，避免孤儿文件越攒越多
        for (id in batch.removed) {
            val shard = shardFile(id)
            runCatching { shard.delete() }
            runCatching { AtomicJsonFile.backupOf(shard).delete() }
        }
        val byId = batch.records.associateBy { it.id }
        var bytes = 0
        var written = 0
        for (id in batch.dirty) {
            val rec = byId[id] ?: continue
            val n = writeShard(rec)
            if (n > 0) { bytes += n; written++ }
        }
        // index 每次落盘都刷新：它只有 id 清单（O(会话数) 字节、不含消息正文），
        // 换来两点硬保证：① 主文件的 .bak 始终是新鲜的上一版，index 坏了能回退；
        // ② ids 与分片目录始终同步。这仍远不是「整库全量重写」。
        val indexPayload = runCatching {
            json.encodeToString(ConversationIndex(ids = batch.records.map { it.id }))
        }.getOrNull()
        var indexWritten = false
        if (indexPayload != null && AtomicJsonFile.write(file, indexPayload)) {
            bytes += indexPayload.toByteArray(Charsets.UTF_8).size
            indexWritten = true
        }
        lastWrittenShardIds = batch.dirty
        // P5-1：落盘计量（字节数按 UTF-8 算）。bytes 是这一次真正写出的总量——
        // 分片后它随「改了几个会话」变，而不是恒等于整库大小。
        StoreWriteTrace.write(
            store = "conversations",
            bytes = bytes,
            durationMs = System.currentTimeMillis() - started,
            coalesced = mutations,
        )
        return written > 0 || batch.removed.isNotEmpty() || indexWritten
    }

    private fun parseOrNull(text: String): List<ConversationRecord>? =
        if (text.isBlank()) null
        else runCatching { json.decodeFromString<List<ConversationRecord>>(text) }.getOrNull()

    private companion object {
        /** 去抖窗口：足够合并一次流式输出的高频追加，又短到用户感知不到「刚发的话没存」。 */
        const val PERSIST_DEBOUNCE_MS = 400L

        /** 单会话消息数封顶（超出丢最旧）。 */
        const val MAX_MESSAGES = 200

        /** 自动命名时取用户首条消息的前 N 个字符。 */
        const val TITLE_CHARS = 12

        /** R-10：分片目录后缀（`conversations.json` → `conversations.d/`）。 */
        const val SHARD_DIR_SUFFIX = ".d"

        /** R-10：可直接当文件名的会话 id（UUID 与备份数据均落在此集合内）。 */
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
