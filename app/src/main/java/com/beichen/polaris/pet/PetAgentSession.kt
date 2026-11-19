package com.beichen.polaris.pet

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.Consent
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.ProviderManager
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.agent.EgressGuard
import com.beichen.polaris.core.runtime.AgentLoopFactory
import com.beichen.polaris.core.runtime.RunSource
import com.beichen.polaris.core.runtime.RuntimeSessions
import com.beichen.polaris.core.agent.productionSeams
import com.beichen.polaris.core.workspace.WorkspaceStore
import com.beichen.polaris.memory.MemorySearchTool
import com.beichen.polaris.memory.MemoryStore
import com.beichen.polaris.skill.ModelConfig
import com.beichen.polaris.tools.AppLaunchTool
import com.beichen.polaris.tools.CalculatorTool
import com.beichen.polaris.tools.ReadFileTool
import com.beichen.polaris.tools.WebSearchTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌宠的独立轻量会话：一条 `RealAgentLoop` + 一份**内存内**历史。
 *
 * ## 为什么不能复用 `ChatViewModel`
 *
 * 那个类三千多行、绑 Activity 生命周期、二十来个 Contract。悬浮窗活在 Service 里，
 * 没有 Activity，也拿不到它的 ViewModel——就算硬塞进去，一个「随手问一句」的入口
 * 也不该把整个对话页的状态机拉起来。`SubAgentRunner` 早就证明过这条路的可行性：
 * 无 UI 场景也走 `AgentLoopFactory.create(...)` 即可（`RealAgentLoop` 的唯一生产构造点）。
 *
 * ## 工具集为什么这么小
 *
 * `ChatViewModel` 里有 45 个内联 `register(...)`，没有可复用的工厂。桌宠只接
 * **只读、无副作用**的四个工具：算数、联网搜索、读工作区、查记忆。
 *
 * 没有 `SpeakTool`：桌宠的「说」只有一个出口——[PetOverlayHost.speak]（每轮回答结束后
 * 朗读）。再挂一个 speak 工具，模型一旦主动调用，同一段话就会被念两遍。
 * 这和「音频单一持有者」是同一个原则：同一件事只留一个出口。
 *
 * 这条边界原来是「审批缺失的补偿」：桌宠没有界面弹审批卡片，所以工具表被锁在只读。
 * P1-3 第二阶段把确认卡做出来了（[PetApproval]），边界随之移动一格：
 * **写工具可以进，但必须带 [ConfirmRequiredTool]**。判断标准不是「有没有副作用」，
 * 而是「用户说完这句话是不是就走了」——app_launch 是（他去看微信了），
 * 写文件不是（那种事该回主界面等）。
 *
 * ## 历史为什么要落盘
 *
 * 定位是「随手问一句」的独立通道：主界面与桌宠各是各的会话，默认不同步
 * （见 `docs/specs/desktop-pet-spec-v1.md` §2.3）。这里**不**和主应用合并历史，
 * 但桌宠自己那点上下文必须活过进程死亡。
 *
 * 一期判断是「关掉即焚」，真机跑完站不住：用户问一句、收起气泡回微信、再点开追问
 * 「那第二条呢」，桌宠一脸茫然。`PetConversation` 只挡住了 Service 重建，挡不住
 * 「进程被杀 / 手动清后台 / 隔天再用」——而这三件事每天都会发生。
 *
 * 落盘只存 user/assistant 的文本（[PetHistoryStore]），工具轨迹不入档：那对「接着聊」
 * 没有价值，而残缺的 tool 配对会让下一轮请求直接 400。
 *
 * ## 并发
 *
 * 同一时刻只跑一轮（[lock]）。桌宠的输入框在等待期间是禁用的，但通知栏入口、
 * 语音入口将来可能重入——串行化比「两个回合同时往同一个 history 里写」便宜得多。
 * 落盘加载也在这把锁里：它要改 [history]，与 ask 是同一份可变状态。
 */
class PetAgentSession(context: Context) {

    private val appContext = context.applicationContext

    private val lock = Mutex()

    /** 内存里的往返历史；[MAX_HISTORY] 条封顶。首次提问时用磁盘内容填充。 */
    private val history = mutableListOf<ChatMessage>()

    /**
     * 落盘（见 [PetHistoryStore]）。
     *
     * 放 `filesDir` 不放 `cacheDir`：后者会被系统在空间不足时清掉，而「隔天打开
     * 历史没了」正是这次要修的那件事。文件是 App 私有的，不额外加密——与主界面的
     * 会话落盘同一档，`docs/specs/desktop-pet-spec-v1.md` §2.3 记了这个取舍。
     */
    private val store = PetHistoryStore(File(appContext.filesDir, "pet/history.json"))

    /**
     * 是否已经尝试过从磁盘恢复。
     *
     * 只试一次：文件坏了就按新会话跑，不要每轮都去读一遍坏文件。`true` 在真正
     * 读完之前就置位是刻意的——读取过程中并发进来的另一个 ask 会等 [lock]，
     * 而不是也去读一遍磁盘。
     */
    private var restored = false

    /** 用户点过「清空对话」但内存还没清（真正清空在 [lock] 内做，见 [clearHistory]）。 */
    @Volatile
    private var clearRequested = false

    /**
     * 独立于 ViewModel 的 provider 工厂。
     *
     * `ProviderManager()` 自带 openai / claude / gemini 三条注册，其它厂商走
     * OpenAI 兼容兜底——这正好覆盖桌宠能用的全部云端供应商。
     * 唯一的缺口是 `local-llama`：它在 `ChatViewModel` 里由运行层包了一层生命周期壳
     * （心跳 / 在途计数 / 空闲卸载），那套壳依赖 ViewModel 的成员，这里拿不到。
     * 桌宠也不该用本地模型——端侧推理是分钟级，而桌宠的价值是「立刻出声」。
     */
    private val providers = ProviderManager()

    /**
     * 工具表与循环都延迟到**第一次提问**再建。
     *
     * 不是性能洁癖：[WorkspaceStore.dir] 首次调用会做一次目录迁移（真实文件 IO），
     * 而 [PetAgentSession] 是在 `Service.onCreate` 里构造的——主线程。用户在设置页
     * 点「开启」的那一帧不该去做文件搬迁。
     */
    private val registry by lazy {
        ToolRegistry().apply {
            register(CalculatorTool())
            register(WebSearchTool())
            register(ReadFileTool(WorkspaceStore.dir(appContext)))
            register(MemorySearchTool(MemoryStore(File(appContext.filesDir, "memory"))))
            // P1-3 第二阶段：桌宠的第一个写工具。
            //
            // 为什么是 app_launch 而不是写文件：桌宠的典型句子是「帮我打开微信」，
            // 这是它作为悬浮球唯一无可替代的场景——用户说完就去看别的了。
            // 写文件那种事用户不会在气泡里等，该回主界面。
            //
            // 包一层 [ConfirmRequiredTool] 而不是改 AppLaunchTool 本身：主界面的
            // app_launch 一直是免确认的（用户就在屏幕前，划一下返回就行），
            // 让全局跟着弹窗是一次没有论证过的行为扩大。
            register(ConfirmRequiredTool(AppLaunchTool(appContext)))
        }
    }

    private val loop by lazy {
        AgentLoopFactory.create(
            llmProvider = { createClient() },
            registry = registry,
            // 与主界面分开：两个说话人可以各跑一个回合（共用键会退化成过度串行）。
            session = RuntimeSessions.UI_PET,
            source = RunSource.PET,
            systemPrompt = SYSTEM_PROMPT,
            // 桌宠是「一句话问答」，不是长任务。步数上限压到 6：
            // 真需要十几步的事，用户在桌宠里也等不住，该回主应用。
            maxSteps = MAX_STEPS,
            maxHistoryMessages = MAX_HISTORY,
            // 工具表只有五个，不该被「小模型核心集」再裁一遍——裁掉 read_file 它就瞎了
            forceFullTools = true,
            // 瞬时抖动重试一次就够：桌宠是即时交互，等 30 秒重试不如早点说「没连上」
            providerRetry = 1,
            // P1-3 第二阶段：确认卡已经做出来了，桌宠从 Deny 升到 Ask。
            //
            // 等待逻辑在 [PetApproval]（超时 45 秒、界面销毁即撤销），这里只声明
            // 「本通道有审批能力」。写 Ask 而不是继续写 Deny 的前提是**卡片真的会显示**——
            // 气泡收起时宿主会自动展开（见 PetOverlayHost 订阅 pending 的那一段），
            // 否则 Ask 就成了一句谎话：声称能问，实际没人看得见。
            //
            // 超时与撤销都返回 false（按拒绝执行），但日志分开记。细节见 ApprovalGate。
            consent = Consent.ask { tool, args -> PetApproval.ask(tool, args) },
            // 「有审批通道」不等于「可以外发」：这是两道独立的闸门。
            //
            // 桌宠的工具表里有 web_search（免审）与 read_file（免审）。没有这条守卫，
            // 一次「读文件 → 照文件里的话搜索」就能把工作区内容静默发出去（P0-3），
            // 全程不需要用户点任何按钮。确认卡解决的是「用户看不看得见」，
            // 外发守卫解决的是「这段内容该不该出门」——后者仍然恒 false，
            // 用户真要外发，去主界面问。
            seams = productionSeams(
                toolGuards = listOf(EgressGuard(consent = { _, _ -> false }))
            ),
        )
    }

    /**
     * 问一句，等一个回答。
     *
     * 失败不抛：桌宠的气泡没有 try/catch 的位置，错误必须变成一句能读出来的话。
     * 取消照常上抛（用户关掉气泡 / 服务销毁），那是控制流不是故障。
     */
    suspend fun ask(input: String): PetReply = lock.withLock {
        val text = input.trim()
        if (text.isEmpty()) return@withLock PetReply("", failed = false, silent = true)
        // 第一次提问才读盘：构造发生在 `Service.onCreate`（主线程），不该在那里做文件 IO
        ensureRestored()
        val started = System.currentTimeMillis()
        try {
            val prior = history.toList()
            // 时间戳贴在**这一条**用户消息前面，而不是写进 systemPrompt：
            // systemPrompt 只在构造时算一次，桌宠可能连续活几个小时，
            // 写死的时间会让「现在几点」答出几个小时前的数。
            val messages = loop.send(stampNow(text), emptyList(), prior)
            val answer = messages.lastOrNull { it is AssistantMessage }
                ?.let { (it as AssistantMessage).content }
                ?.trim()
                .orEmpty()

            history.clear()
            // 只留对话，不留 system：loop.send 返回的是整段上下文，开头是它自己
            // prepend 的 systemPrompt。原样存回去，下一轮它会作为 prior 再传一遍，
            // loop 又 prepend 一次——模型看到两份同样的指令，每次都多付这段 prompt。
            // （实测第三轮「看起来好了」，只是 takeLast 恰好把旧 system 挤出去了。）
            history.addAll(messages.filterNot { it is SystemMessage }.takeLast(MAX_HISTORY))

            // 每轮结束落一次盘。失败不抛也不重试：历史丢了只是「不记得刚才」，
            // 而把一次成功的回答变成一条错误气泡，代价完全不成比例。
            store.save(history.toList())

            AgentLogger.i(TAG, "桌宠回合完成：${System.currentTimeMillis() - started}ms，${answer.length} 字")
            if (answer.isBlank()) {
                PetReply("我没想出该说什么，再问一次？", failed = true)
            } else {
                PetReply(answer, failed = false)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AgentLogger.w(TAG, "桌宠回合失败：${t.message}")
            PetReply(describeFailure(t), failed = true)
        }
    }

    /**
     * 供界面回放的气泡（打开气泡时调用）。
     *
     * 与 [ask] 共用 [lock]：恢复要写 [history]，不能与一轮正在跑的 ask 交错。
     *
     * 气泡只显示对话本身。`[2026-11-13 22:50 周四] ` 那个前缀是贴给模型看的时间戳
     * （见 [stampNow]），给人看时剥掉——用户不关心自己那句话是几点打的，而且
     * 十几行 `[日期 时间 周几]` 会让小气泡变成日志。
     */
    suspend fun replay(): List<PetTurn> = lock.withLock {
        ensureRestored()
        history.mapNotNull { msg ->
            when (msg) {
                is UserMessage -> PetTurn(stripStamp(msg.content), mine = true)
                is AssistantMessage -> PetTurn(msg.content, mine = false)
                else -> null
            }
        }
    }

    /**
     * 清空上下文（用户点「清空对话」）。
     *
     * 这里**只**打标记 + 删磁盘：内存 `history` 由 [lock] 保护，而这个方法是给 UI
     * 直接调的（非 suspend、不能等锁）。真去 `clear()` 会和一轮正在跑的 ask 抢写，
     * 结果是「清空后刚问的那句话又出现在历史里」。真正的清空交给下一次
     * [ensureRestored] 在锁内做。
     *
     * `restored = true` 也要一起置位：删磁盘是异步的，若此刻立刻重新加载，
     * 很可能读到还没删掉的旧文件，等于没清。
     */
    fun clearHistory() {
        clearRequested = true
        store.clear()
    }

    /**
     * 从磁盘恢复（只做一次）。必须在 [lock] 内调用。
     *
     * `history.isNotEmpty()` 的守卫是给「已经聊过一轮、`restored` 还没置位」这种
     * 异常顺序兜底的：宁可丢掉磁盘里的旧内容，也不要把它拼到一段活对话前面。
     */
    private suspend fun ensureRestored() {
        if (clearRequested) {
            clearRequested = false
            history.clear()
            restored = true
        }
        if (restored) return
        restored = true
        val loaded = withContext(Dispatchers.IO) { store.load() }
        if (loaded.isEmpty() || history.isNotEmpty()) return
        history.addAll(loaded.takeLast(MAX_HISTORY))
        AgentLogger.i(TAG, "恢复桌宠历史 ${loaded.size} 条")
    }

    /** 剥掉 [stampNow] 贴的时间前缀；格式严格（日期+时间），不会误伤用户自己写的方括号。 */
    private fun stripStamp(text: String): String =
        text.replaceFirst(Regex("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}[^\\]]*\\]\\s*"), "")

    private fun createClient(): LLMClient {
        val cfg = currentConfig()
        if (cfg.providerId == LOCAL_PROVIDER) {
            // 与其让用户等 30 秒端侧推理，不如明确说清楚
            throw IllegalStateException("桌面助手暂不支持本地模型，请在设置里切换到云端供应商")
        }
        if (cfg.apiKey.isBlank() && cfg.baseUrl.isBlank()) {
            throw IllegalStateException("还没配置模型，先回主应用设置里填一个")
        }
        return providers.create(
            cfg.providerId,
            LLMConfig(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.model,
                customHeaders = cfg.customHeaders,
                customBody = parseCustomBody(cfg.customBody)
            )
        )
    }

    /**
     * 当前生效档案。
     *
     * 与 `ChatViewModel.ensureActiveConfig` 同源（同一份 SharedPreferences），
     * 但不共享实例：桌宠要能拿到用户**刚刚**在设置页改过的配置，而不是进程启动时
     * 缓存在 ViewModel 里的那份快照。
     */
    private fun currentConfig(): ModelConfig {
        val store = ConfigStore(appContext)
        val activeId = runCatching { store.loadActiveId() }.getOrNull()
        val profiles = runCatching { store.loadProfiles() }.getOrDefault(emptyList())
        return profiles.firstOrNull { it.id == activeId }?.config ?: store.load()
    }

    /** 与 `ChatViewModel.parseCustomBody` 同一套容错：解析失败就当没配。 */
    private fun parseCustomBody(raw: String): Map<String, JsonElement> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(t) as? kotlinx.serialization.json.JsonObject ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * 系统提示词。
     *
     * **不含具体时间**：桌面助手可能连续活几个小时，而 `RealAgentLoop` 的
     * systemPrompt 是构造参数、只在建 loop 时取一次。写死的时间会让「现在几点」
     * 答出启动那一刻的钟点——比不答更糟。时间由 [stampNow] 逐条注入。
     */
    private val SYSTEM_PROMPT = """
        你是「北辰」，一个常驻手机桌面角落的语音助手。

        每条用户消息开头方括号里是当前时间，回答时间相关问题时以它为准。

        说话方式：
        - 回答要短，一般一到三句话。你的话会被朗读出来，也会显示在一个很小的气泡里。
        - 不要用 Markdown（列表符号、表格、代码块）——朗读出来是噪音。
        - 不确定就直说，不要编。

        你能用这些工具：算数、联网搜索、读工作区文件、查长期记忆。
        你的回答会被自动朗读，不需要你去调什么朗读工具。
        没有写文件、没有执行命令、不能操作其他 App。用户要你做这类事时，
        直接说这边做不到，建议回主应用里做。
    """.trimIndent()

    /** `[2026-11-13 22:50 周四] 用户的话` —— 时间跟着每条消息走，不会过期。 */
    private fun stampNow(input: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.CHINA).format(Date())
        return "[$now] $input"
    }

    private fun describeFailure(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("401") || msg.contains("403") -> "模型说 Key 不对，回主应用检查一下配置"
            msg.contains("timeout", ignoreCase = true) -> "等太久了，网络可能不太好"
            msg.isBlank() -> "出了点问题，再试一次"
            else -> msg.take(120)
        }
    }

    companion object {
        private const val TAG = "PetSession"
        private const val MAX_STEPS = 6

        /**
         * 上下文上限。**与落盘上限同源**（[PetHistoryStore.MAX_MESSAGES]）：
         * 磁盘里留得比模型记得多，重启后用户会看到一段模型「不认」的对话。
         */
        private const val MAX_HISTORY = PetHistoryStore.MAX_MESSAGES
        private const val LOCAL_PROVIDER = "local-llama"
    }
}

/**
 * 回放给气泡的一条消息。
 *
 * 刻意不带 `failed`：失败回复从来没进过 history，也就不在磁盘上。回放出现的每一条
 * 都是当时的真实对话，不会出现「昨天那条网络错误」。
 *
 * 必须是 public（不能用 internal）：[PetAgentSession.replay] 与 `PetPanel` 的
 * `loadHistory` 参数都是 public 签名，kt 会拒绝「public API 暴露 internal 类型」。
 */
data class PetTurn(val text: String, val mine: Boolean)

/**
 * 一回合的结果。
 *
 * @param text   要显示（并朗读）的文字
 * @param failed 是不是错误文案。错误也要显示，但不朗读——把「网络不太好」念出来
 *               比不念更让人烦躁，而用户已经在看气泡了。
 * @param silent 空输入（用户只按了回车），什么都不做
 */
data class PetReply(
    val text: String,
    val failed: Boolean = false,
    val silent: Boolean = false
)
