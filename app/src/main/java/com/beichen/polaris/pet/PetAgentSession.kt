package com.beichen.polaris.pet

import android.content.Context
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.ProviderManager
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.RealAgentLoop
import com.beichen.polaris.core.workspace.WorkspaceStore
import com.beichen.polaris.memory.MemorySearchTool
import com.beichen.polaris.memory.MemoryStore
import com.beichen.polaris.skill.ModelConfig
import com.beichen.polaris.tools.CalculatorTool
import com.beichen.polaris.tools.ReadFileTool
import com.beichen.polaris.tools.SpeakTool
import com.beichen.polaris.tools.WebSearchTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 无 UI 场景直接 `RealAgentLoop(...)` 即可。
 *
 * ## 工具集为什么这么小
 *
 * `ChatViewModel` 里有 45 个内联 `register(...)`，没有可复用的工厂。桌宠一期只接
 * **只读、无副作用**的五个工具：算数、联网搜索、读工作区、查记忆、朗读。
 *
 * 这条边界不只是「省事」，它是**审批缺失的补偿**：桌宠没有界面弹审批卡片，
 * [RealAgentLoop] 的 `approval` 传 null（等于关闭审批）。一旦这里出现写文件、
 * 执行命令这类工具，「无审批 + 有副作用」就是数据损坏与误操作的入口。
 * 将来要加写工具，**必须先给桌宠做一套审批通道**，不能只往 registry 里补一行。
 *
 * ## 历史为什么不落盘
 *
 * 定位是「随手问一句」的独立通道：主界面与桌宠各是各的会话，默认不同步
 * （见 `docs/specs/desktop-pet-spec-v1.md` §2.3）。落盘会引出「两份历史谁是真」
 * 的问题，而那个问题在当前阶段没有收益——用户真正需要长期保留的对话在主应用里。
 * 进程被杀，这段历史就没了，这是**刻意的**。
 *
 * ## 并发
 *
 * 同一时刻只跑一轮（[lock]）。桌宠的输入框在等待期间是禁用的，但通知栏入口、
 * 语音入口将来可能重入——串行化比「两个回合同时往同一个 history 里写」便宜得多。
 */
class PetAgentSession(context: Context) {

    private val appContext = context.applicationContext

    private val lock = Mutex()

    /** 仅内存的往返历史；[MAX_HISTORY] 条封顶。 */
    private val history = mutableListOf<ChatMessage>()

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
            register(SpeakTool(AudioGraph.arbiter(appContext)))
        }
    }

    private val loop by lazy {
        RealAgentLoop(
            llmProvider = { createClient() },
            registry = registry,
            systemPrompt = SYSTEM_PROMPT,
            // 桌宠是「一句话问答」，不是长任务。步数上限压到 6：
            // 真需要十几步的事，用户在桌宠里也等不住，该回主应用。
            maxSteps = MAX_STEPS,
            maxHistoryMessages = MAX_HISTORY,
            // 工具表只有五个，不该被「小模型核心集」再裁一遍——裁掉 read_file 它就瞎了
            forceFullTools = true,
            // 瞬时抖动重试一次就够：桌宠是即时交互，等 30 秒重试不如早点说「没连上」
            providerRetry = 1
            // approval 保持 null：见类注释「工具集为什么这么小」。
            // 加任何有副作用的工具之前，先把审批通道补上。
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
            history.addAll(messages.takeLast(MAX_HISTORY))

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

    /** 清空上下文（用户点「新对话」）。 */
    fun clearHistory() {
        history.clear()
    }

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

        你能用这些工具：算数、联网搜索、读工作区文件、查长期记忆、朗读。
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
        private const val MAX_HISTORY = 20
        private const val LOCAL_PROVIDER = "local-llama"
    }
}

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
