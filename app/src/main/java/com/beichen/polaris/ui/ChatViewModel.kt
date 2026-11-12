package com.beichen.polaris.ui

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.ModelPricing
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
// R-14：窄接口都定义在 ui.contract 包，实现类要认全 30 个契约；
// 逐个显式 import 会占掉近 20 行（本项目对 >1500 行文件有行数冻结），故用通配符。
import com.beichen.polaris.ui.contract.*
import java.io.ByteArrayOutputStream
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beichen.polaris.automation.adb.AdbBackendProvider
import com.beichen.polaris.automation.shizuku.ShizukuBackendProvider
import com.beichen.polaris.automation.AutomationBridge
import com.beichen.polaris.automation.AutomationPrefs
import com.beichen.polaris.automation.DeviceForegroundAppTool
import com.beichen.polaris.automation.DeviceContext
import com.beichen.polaris.automation.DeviceContextStore
import com.beichen.polaris.automation.DeviceNotificationsTool
import com.beichen.polaris.automation.UiClickTool
import com.beichen.polaris.automation.UiInputTool
import com.beichen.polaris.automation.UiKeyTool
import com.beichen.polaris.automation.UiLongPressTool
import com.beichen.polaris.automation.OcrTool
import com.beichen.polaris.automation.UiScreenshotTool
import com.beichen.polaris.automation.UiSwipeTool
import com.beichen.polaris.automation.UiTapTool
import com.beichen.polaris.automation.UiTreeTool
import com.beichen.polaris.core.agent.RealAgentLoop
import com.beichen.polaris.core.agent.TurnObservers
import com.beichen.polaris.core.capability.CapabilityAuditReporter
import com.beichen.polaris.core.capability.ToolActivationStore
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.StartupMark
import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.agent.ConnectivityNetworkRecoveryWaiter
import com.beichen.polaris.core.ConfigSanitizer
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.CompletionNotifier
import com.beichen.polaris.core.BackupArchive
import com.beichen.polaris.core.BackupManifest
import com.beichen.polaris.core.ConfigBackup
import com.beichen.polaris.core.ConversationRecord
import com.beichen.polaris.core.DraftStore
import com.beichen.polaris.core.PromptDeleteResult
import com.beichen.polaris.core.llm.ProviderCatalog
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.FailureKind
import com.beichen.polaris.core.llm.Diagnosis
import com.beichen.polaris.core.llm.RecoveryAction
import com.beichen.polaris.core.llm.diagnoseException
import com.beichen.polaris.core.llm.diagnoseHttpFailure
import com.beichen.polaris.core.PromptItem
import com.beichen.polaris.core.PromptStore
import com.beichen.polaris.core.PromptUpsertResult
import com.beichen.polaris.core.conversationToMarkdown
import com.beichen.polaris.core.Bookmark
import com.beichen.polaris.core.BookmarkStore
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.ConversationSummary
import com.beichen.polaris.core.ModelProfile
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.LlmHttpException
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ProviderManager
import com.beichen.polaris.core.llm.KeyPoolRuntime
import com.beichen.polaris.core.llm.KeyPoolStore
import com.beichen.polaris.core.AgentEvent
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.UiItem
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.ToolApproval
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.TokenUsage
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.toMsgRecord
import com.beichen.polaris.core.toUiItems
import com.beichen.polaris.core.llm.local.AndroidDeviceProbe
import com.beichen.polaris.core.llm.local.DownloadedModel
import com.beichen.polaris.core.llm.local.GgufCatalogEntry
import com.beichen.polaris.core.llm.local.GgufModelStore
import com.beichen.polaris.core.llm.local.GpuMode
import com.beichen.polaris.core.llm.local.LlamaJni
import com.beichen.polaris.core.llm.local.LocalDeviceRules
import com.beichen.polaris.runtime.wrapLocalLlm
import com.beichen.polaris.runtime.lease.LeaseRegistry
import com.beichen.polaris.runtime.lease.TerminalLeases
import com.beichen.polaris.core.llm.local.LocalLlamaClient
import com.beichen.polaris.core.llm.local.LocalLlamaEngine
import com.beichen.polaris.core.llm.local.LocalLlamaSetting
import com.beichen.polaris.skill.JsEngine
import com.beichen.polaris.skill.ModelConfig
import com.beichen.polaris.skill.RhinoJsEngine
import com.beichen.polaris.skill.SkillExecutor
import com.beichen.polaris.skill.SkillDeclarations
import com.beichen.polaris.skill.SkillLoader
import com.beichen.polaris.skill.ImportedSkillRepository
import com.beichen.polaris.skill.SkillManifest
import com.beichen.polaris.skill.SkillPackage
import com.beichen.polaris.skill.createUseSkillTool
import com.beichen.polaris.tools.AppInstallTool
import com.beichen.polaris.tools.AppUninstallTool
import com.beichen.polaris.tools.ContainerToolchainTool
import com.beichen.polaris.core.job.JobNotifier
import com.beichen.polaris.core.job.JobRunner
import com.beichen.polaris.core.job.JobStore
import com.beichen.polaris.tools.ShellBackgroundTool
import com.beichen.polaris.tools.ShellJobsTool
import com.beichen.polaris.tools.GitHubCloneTool
import com.beichen.polaris.tools.GitHubCommitTool
import com.beichen.polaris.tools.GitHubDiffTool
import com.beichen.polaris.tools.GitHubLogTool
import com.beichen.polaris.tools.GitHubStatusTool
import com.beichen.polaris.tools.LoadSkillLogic
import com.beichen.polaris.tools.LoadSkillTool
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import com.beichen.polaris.skill.CapabilityRegistry
import com.beichen.polaris.skill.policyFor
import com.beichen.polaris.skill.market.MarketInstallPreview
import com.beichen.polaris.skill.market.MarketSource
import com.beichen.polaris.skill.market.MarketUiItem
import com.beichen.polaris.skill.checkCapabilityGate
import com.beichen.polaris.memory.MemoryItem
import com.beichen.polaris.audio.TextSpeaker
import com.beichen.polaris.memory.MemoryAutoCapture
import com.beichen.polaris.memory.MemoryStore
import com.beichen.polaris.memory.MemoryForgetTool
import com.beichen.polaris.memory.MemoryListTool
import com.beichen.polaris.memory.MemorySearchTool
import com.beichen.polaris.memory.MemoryWriteTool
import com.beichen.polaris.core.container.RootfsManager
import com.beichen.polaris.core.capability.Capability
import com.beichen.polaris.core.capability.CapabilityResolution
import com.beichen.polaris.automation.shizuku.ShizukuShell
import com.beichen.polaris.core.agent.AskUserBroker
import com.beichen.polaris.core.agent.AgentRunService
import com.beichen.polaris.core.agent.AgentRunState
import com.beichen.polaris.core.agent.AgentRunStateStore
import com.beichen.polaris.core.agent.ApprovalGrant
import com.beichen.polaris.core.agent.ApprovalGrouping
import com.beichen.polaris.core.agent.ApprovalTier
import com.beichen.polaris.core.agent.ApprovalScope
import com.beichen.polaris.core.agent.awaitApprovalDecision
import com.beichen.polaris.core.agent.PlanBroker
import com.beichen.polaris.tools.SubAgentTool
import com.beichen.polaris.core.agent.SubAgentRunner
import com.beichen.polaris.core.agent.TitleLogic
import com.beichen.polaris.core.agent.journal.RunJournal
import com.beichen.polaris.core.agent.journal.TurnJournal
import com.beichen.polaris.core.text.MentionLogic
import com.beichen.polaris.core.text.TranslateLogic
import com.beichen.polaris.core.workspace.FileBackupStore
import com.beichen.polaris.core.workspace.FileBackupStoreFactory
import com.beichen.polaris.core.workspace.WorkspacePaths
import com.beichen.polaris.core.workspace.WorkspaceStore
import com.beichen.polaris.core.agent.TodoStore
import com.beichen.polaris.core.mcp.ClientMcpToolLoader
import com.beichen.polaris.core.mcp.McpServerStore
import com.beichen.polaris.core.mcp.McpToolBinder
import com.beichen.polaris.tools.AskUserTool
import com.beichen.polaris.tools.PlanTool
import com.beichen.polaris.tools.CalculatorTool
import com.beichen.polaris.tools.ConversationSearchTool
import com.beichen.polaris.tools.KnowledgeSearchTool
import com.beichen.polaris.tools.EditFileTool
import com.beichen.polaris.tools.WriteBackupHook
import com.beichen.polaris.tools.SpeakTool
import com.beichen.polaris.tools.WorkflowCreateTool
import com.beichen.polaris.tools.WorkflowDeleteTool
import com.beichen.polaris.tools.WorkflowListTool
import com.beichen.polaris.tools.WorkflowToggleTool
import com.beichen.polaris.workflow.Workflow
import com.beichen.polaris.workflow.WorkflowScheduler
import com.beichen.polaris.workflow.WorkflowStore
import com.beichen.polaris.tools.WebClickTool
import com.beichen.polaris.tools.WebEvalTool
import com.beichen.polaris.tools.WebOpenTool
import com.beichen.polaris.tools.WebSearchTool
import com.beichen.polaris.tools.WebSnapshotTool
import com.beichen.polaris.tools.WebTypeTool
import com.beichen.polaris.tools.PrivilegedShellOut
import com.beichen.polaris.tools.TodoReadTool
import com.beichen.polaris.tools.TodoWriteTool
import com.beichen.polaris.core.capability.shellCapabilitySnapshot
import com.beichen.polaris.automation.screenCapabilitySnapshot
import com.beichen.polaris.core.terminal.TerminalSessionPool
import com.beichen.polaris.core.terminal.backend.TerminalBackendFactory
import com.beichen.polaris.core.terminal.backend.SshBackendProvider
import com.beichen.polaris.core.terminal.backend.SshBinaryLocator
import com.beichen.polaris.core.terminal.backend.SshProfile
import com.beichen.polaris.core.terminal.backend.SshProfileResult
import com.beichen.polaris.core.terminal.backend.normalizeSshProfile
import com.beichen.polaris.core.terminal.backend.createTerminalBackendFactory
import com.beichen.polaris.tools.ClipboardTool
import com.beichen.polaris.tools.HttpRequestTool
import com.beichen.polaris.tools.NotificationTool
import com.beichen.polaris.tools.ReadFileTool
import com.beichen.polaris.tools.ShellCloseTool
import com.beichen.polaris.tools.ShellRunTool
import com.beichen.polaris.tools.WriteFileTool
import com.beichen.polaris.tools.registerAppControlTools
import com.beichen.polaris.tools.terminalShellCommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

/** `SkillUiInfo` / `SkillToolInfo` 已搬到 SkillUi.kt（纯数据，不必占这个冻结文件的行数）。 */

/**
 * W-30：一条消息的翻译状态。
 *
 * @param text 译文（成功时非空）
 * @param error 失败原因（失败时非空，UI 用 error 色显示而不是假装翻出来了）
 */
data class TranslationUi(val text: String? = null, val error: String? = null)

/**
 * 一次待确认的危险工具调用（L10）：由 AgentLoop 发起、UI 弹窗、用户决策后回填。
 *
 * @param toolName 待执行的工具名（如 ui_tap）。
 * @param argsPreview 入参预览（截断到 500 字符，避免超长参数撑爆 Dialog）。
 * @param deferred UI 用它回复 AgentLoop：complete(true) 放行、complete(false) 拒绝。
 */
data class ApprovalRequest(
    val toolName: String,
    val argsPreview: String,
    val deferred: CompletableDeferred<Boolean>,
    /**
     * 本次授权的作用域（R-08）：键、展示名、能否记住。
     */
    val grant: ApprovalGrant = ApprovalGrant("", "", true)
)

/**
 * 供应商瞬时失败的「自动恢复」进行态（Phase 2 DoD③）。
 *
 * 由 [ChatViewModel.retryState] 暴露：非 null 时 UI 显示「正在恢复（n/N）· 原因」横幅，
 * 恢复成功 / 最终失败 / 用户取消时清回 null。**取消不算失败，但必须立即清状态**
 * （T05 不变量：点停止后不得残留「正在恢复」）。
 *
 * @param attempt 1-based 重试序号（第几次重试）。
 * @param max 最大重试次数（= providerRetry）。
 * @param reason 面向用户的可读原因（已脱敏，不含原始异常 message）。
 */
data class RetryState(
    val attempt: Int,
    val max: Int,
    val reason: String
)

/** 「测试连接」的诊断结果：配置页卡片直接消费。 */
data class TestResult(
    /** 是否连通（HTTP 2xx）。 */
    val ok: Boolean,
    /** HTTP 状态码；网络异常时为 -1。 */
    val code: Int,
    /** 实际发起请求的完整端点。 */
    val endpoint: String,
    /** Key 指纹：len=35 head=sk-a tail=d38d。 */
    val fingerprint: String,
    /** 响应体前 200 字（已脱敏）；异常时为异常文案。 */
    val bodyPreview: String,
    /** 针对状态码的修复建议（失败时有值）。 */
    val suggestion: String = "",
    /** 失败归因（P1，2026-10-21）：UI 据此决定给哪个补救按钮，不再只给一句建议。 */
    val kind: FailureKind = FailureKind.NONE,
    /** 可直接执行的补救动作（去拿 Key / 拉模型列表 / 重试）。 */
    val action: RecoveryAction = RecoveryAction.NONE
)

/**
 * UI 与业务的唯一桥梁：持有注册表、Agent 循环与配置，对外只暴露 StateFlow。
 * 业务规则（工具注册、循环策略、Skill 接入）都在这里组装，Compose 只负责渲染。
 */
/**
 * 功能五（P1-4）：待发文件——文件名 + 已提取的正文文本。
 *
 * 文本在读取时就抽好（读文件是 IO 重活），发送时只是字符串拼接；
 * UI 只负责展示文件名与移除，不碰解析细节。
 */
data class PendingFile(val name: String, val text: String)

/**
 * 功能五（P1-4）：文件附件处理结果——三条成功路径 + 一条失败路径。
 *
 * 之所以要区分 [Text] 与 [Images]：用户需要知道「这份 PDF 是被当图片读的」
 * （影响模型能否引用原文精确字句），而不是笼统一句「已添加」。
 */
sealed interface FileAttachResult {
    /** 文本类文件已直读（[chars] = 实际取用字符数）。 */
    data class Text(val name: String, val chars: Int) : FileAttachResult

    /** PDF 已渲染为 [pages] 张图片并入图片链路。 */
    data class Images(val name: String, val pages: Int) : FileAttachResult

    /** 不支持的类型 / 读取失败——[reason] 直接展示给用户。 */
    data class Failed(val reason: String) : FileAttachResult
}

/** W-14：单台 MCP 服务器最多接入的工具数（工具描述要进系统提示，不能无限扩张）。 */
private const val MCP_MAX_TOOLS_PER_SERVER = 30

/** W-14：全部 MCP 服务器合计的工具上限（超出部分不注册，日志会记录实际接入数）。 */
private const val MCP_MAX_TOOLS_TOTAL = 60

/** W-28：@提及文件的单文件/总量上限（字符）。 */
private const val MENTION_PER_FILE_LIMIT = 8_000
private const val MENTION_TOTAL_LIMIT = 24_000
/** W-28：项目规则文件（AGENTS.md/README）注入上限。 */
private const val PROJECT_RULES_LIMIT = 4_000

class ChatViewModel(private val app: Application) : AndroidViewModel(app),
    BookmarksContract, LogsContract, MemoryBankContract, PromptLibraryContract,
    WorkflowContract, SkillLibraryContract, SkillMarketContract,
    ChatAppearanceContract, PermissionStatusContract, ConversationListContract, ConsoleContract,
    ChatScreenContract, ModelProfilesContract, LocalModelContract, ModelConfigContract,
    AppSettingsContract, ToolGroupContract, BackupContract, SettingsScreenContract {

    private val startup = StartupMark() // 启动分段计时：把「初始化慢」变成可归因的数字

    private val workspace = File(app.filesDir, "agent_workspace")

    /**
     * B 批：Agent 写文件的自动备份仓。
     *
     * 每次 write_file / edit_file 之前自动留一份旧内容，用户可在编辑器里一键回滚到上一步。
     * 与 [com.beichen.polaris.core.workspace.CheckpointStore]（整目录手动快照）互补：
     * 那是人工拍点，这个是自动逐次留底。
     */
    private val fileBackupStore = FileBackupStoreFactory.default(app)

    /**
     * B 批：Agent 长任务的运行态存储（用于进程被杀后的恢复提示）。
     */
    private val agentRunStateStore = AgentRunStateStore(File(app.filesDir, "agent_run_state.json"))

    /**
     * R1：回合日志。目录 `filesDir/run-journal`，只保留最近 20 个回合。
     *
     * 与 [agentRunStateStore] 的分工：那个记「整个任务跑到第几步」，给用户看进度；
     * 这个记「本回合每个工具调用越没越过执行边界」，给恢复逻辑判「能不能重放」。
     * 前者丢失只是提示不准，后者丢失会导致写操作被执行两次——所以它单独一行一条 fsync。
     */
    private val runJournal = RunJournal(File(app.filesDir, "run-journal"))

    /** 回合日志的运行时门面（写失败降级，不拖垮回合）。 */
    private val turnJournal = TurnJournal(runJournal)

    /**
     * 上次进程被杀留下的孤儿调用说明，**只注入下一轮请求一次**。
     *
     * 不落库：它是恢复期的临时上下文，写进会话会在用户切分支/导出时变成一段
     * 出现在错误位置的助手发言。用完即清（见 [send]）。
     */
    @Volatile
    private var pendingRecoveryNote: String? = null

    /**
     * B 批：上次任务被系统中断时的提示语（启动时对账得到，非 null 则展示一次）。
     */
    private val _interruptedNotice = MutableStateFlow<String?>(null)
    override val interruptedNotice: StateFlow<String?> = _interruptedNotice.asStateFlow()

    /** 用户点「知道了」后关闭恢复提示。 */
    override fun dismissInterruptedNotice() {
        _interruptedNotice.value = null
    }

    /**
     * 推进运行态：记一步工具完成（C 批）。
     *
     * 单独抽出来是为了让「工具事件处理」保持纯展示逻辑，落盘细节集中在一处。
     */
    private fun advanceRunState(toolName: String) {
        runCatching { agentRunStateStore.advance(toolName) }
    }

    /**
     * 用户这句话是否在要求「继续上次被中断的任务」（C 批）。
     *
     * 只认**很短**的续跑指令（继续 / 接着 / 接着做 / 继续做 / resume / continue），
     * 避免把「继续帮我写个函数」这种普通请求误判成续跑——那会把无关的
     * 上次任务摘要塞进新一轮上下文，反而干扰模型。
     */
    private fun isResumeIntent(text: String): Boolean {
        val t = text.trim().removeSuffix("。").removeSuffix(".").removeSuffix("！").removeSuffix("!").trim()
        if (t.isEmpty() || t.length > 12) return false
        return t in setOf(
            "继续", "接着", "接着做", "继续做", "继续吧", "接着吧",
            "继续刚才的", "继续刚才那个", "resume", "continue"
        )
    }

    /** 自动化总开关持久化。 */
    private val automationPrefs = AutomationPrefs(app)

    /**
     * 自动化状态流：供设置页实时反映开关与服务连接情况。
     * 3.1 第三批拆出，实现见 [SettingsFacade]（getter 惰性求值，构造期无人读它）。
     */
    override val automationEnabled: StateFlow<Boolean> get() = settingsFacade.automationEnabled

    private val _serviceConnected = MutableStateFlow(AutomationBridge.service != null)
    override val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    /**
     * 权限 / 调试通道域（3.1 第四批拆出，实现见 [PermissionFacade]）。
     *
     * 通知监听、使用情况访问、电池白名单、Shizuku 三态与设备上下文快照的**写点全在 facade**；
     * 这里只做转发，字段初值仍是静态值（探测在 IO 上由 [refreshGrants] 填）。
     *
     * 能力就绪度不在这里重算：facade 不持有任何回调，也不认识 [capabilityReadiness]，
     * 「状态变化 → 重算」由下面的转发层显式完成。构造期双向引用会让 K1 的类型推断
     * 直接判死（`Type checking has run into a recursive problem`），所以依赖只能单向。
     */
    private val permissionFacade = PermissionFacade(app, viewModelScope)

    /** 通知访问权限是否已授予（供感知层卡片展示）。 */
    override val notificationGranted: StateFlow<Boolean> get() = permissionFacade.notificationGranted

    /** 使用情况访问权限是否已授予（供感知层卡片展示）。 */
    override val usageGranted: StateFlow<Boolean> get() = permissionFacade.usageGranted

    /**
     * 是否已在电池优化白名单（P0 权限引导，2026-10-21）。
     *
     * 只影响「长任务会不会被系统掐断」，不影响任何工具能不能用——所以它不在
     * `pendingGrantCount` 的计数里，只有引导页单独展示。
     */
    override val batteryUnrestricted: StateFlow<Boolean> get() = permissionFacade.batteryUnrestricted

    /** 调试通道（Shizuku）状态（W-13 B）。 */
    val shizukuStatus: StateFlow<ShizukuShell.Status> get() = permissionFacade.shizukuStatus

    /** 界面四态（未安装 / 已装未启动 / 未授权 / 就绪）——文案与按钮动作都由它决定。 */
    override val shizukuUiState: StateFlow<ShizukuShell.UiState> get() = permissionFacade.shizukuUiState

    /** 重新探测调试通道状态（进入权限页 / 授权回调后调用）。 */
    override fun refreshShizukuStatus() {
        // 探测在 IO 上跑（binder IPC 不占主线程）；能力链必须在探测**完成之后**重算——
        // 跟着 facade 一起同步调的话，读到的还是上一轮的 shizukuReady。
        permissionFacade.refreshShizukuStatus { capabilityReadiness.refresh() }
    }

    /**
     * 调试通道入口动作（权限页「去开启/去授权」）：
     *  - 未运行 → 拉起 Shizuku（未安装则打开应用商店页）；
     *  - 未授权 → 发起授权请求（弹窗由 Shizuku 提供，结果回调后刷新）；
     *  - 就绪 → 仅刷新状态。
     */
    override fun handleShizukuAction(context: Context) {
        // 探测在 IO 上跑，完成后 facade 回调这里重算能力链。
        // 不能在这行之后同步 refresh——那一刻 uid/权限都还是旧值。
        permissionFacade.handleShizukuAction(context) { capabilityReadiness.refresh() }
    }

    /** 当前设备上下文快照（L4 主动感知）：供自动化页展示 Agent 实时感知到的手机状态。 */
    override val deviceContext: StateFlow<DeviceContext> get() = permissionFacade.deviceContext

    /** 导入技能仓库（L7）：从 URL / 本机 zip / 内置示例导入到 filesDir/skills/imported。 */
    private val importRepo = ImportedSkillRepository(app)

    /** 已导入技能 id 集合：用于按来源路由资源配置（imported 读 filesDir，否则读 assets）。 */
    private var importedIds: Set<String> = emptySet()

    /** 技能快照 = 内置（assets）+ 已导入（filesDir），随导入/卸载/授权动态刷新。 */
    private var skillsSnapshot: List<SkillManifest> = buildInitialSkills()

    /** 首次构建技能列表：合并 assets 内置技能与 filesDir 已导入技能。 */
    private fun buildInitialSkills(): List<SkillManifest> {
        startup.mark("fields") // 口径：切掉 368~552 行的字段初始化，别把它算进「技能装载」
        val imported = importRepo.loadImported(app)
        importedIds = imported.map { it.id }.toSet()
        return (SkillLoader.loadAll(app) + imported).also { startup.mark("skills") }
    }

    /** UI 用技能信息（含安全策略摘要）：让 L5 全局能力门「看得见」。 */
    private val _skillInfos = MutableStateFlow(computeSkillInfos())
    override val skillInfos: StateFlow<List<SkillUiInfo>> = _skillInfos.asStateFlow()

    /** 导入/卸载操作的状态提示（Toast 式文本）；reload 不改变它（保留上次提示）。 */
    private val _importStatus = MutableStateFlow<String?>(null)
    override val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    /* ------------------------- W-48 插件市场（3.1 拆出 MarketFacade） ------------------------- */

    /**
     * 市场域整块搬进 [MarketFacade]：字段（五条 StateFlow + 补刷标记）与方法
     * （刷新/增删源/装/卸）都在那边，这里只留 override 转发——契约一行没动，
     * 页面拿到的仍是同一个接口。
     *
     * 与 VM 的全部接触面就三个注入点：已装列表（importRepo）、装/卸后刷新技能快照
     * （::reloadSkills）、协程作用域。市场不知道会话与工具表的存在。
     */
    private val market = MarketFacade(app, importRepo, viewModelScope, ::reloadSkills, startup)

    /** 已配置的市场源（含顺序即用户添加顺序）。 */
    override val marketSources: StateFlow<List<MarketSource>> get() = market.marketSources

    /** 合并去重后的插件列表（含本机安装态）。 */
    override val marketItems: StateFlow<List<MarketUiItem>> get() = market.marketItems

    /** 正在刷新索引（按钮置灰 + 顶部进度提示的唯一判据）。 */
    override val marketLoading: StateFlow<Boolean> get() = market.marketLoading

    /** 正在安装/卸载的插件 id（同一时刻只允许一个，避免并发写同一目录）。 */
    override val marketBusyId: StateFlow<String?> get() = market.marketBusyId

    /** 面向用户的一行状态（成功/失败/源为空），UI 原样展示。 */
    override val marketStatus: StateFlow<String?> get() = market.marketStatus

    /** 已下载、待确认的安装（非空即弹确认框，见 facade 里「为什么下载完不直接落盘」）。 */
    override val marketPending: StateFlow<MarketInstallPreview?> get() = market.marketPending

    /** 刷新全部市场源（同一时刻只允许一次；多源串行拉取，见 facade）。 */
    override fun refreshMarket() = market.refreshMarket()

    /** 添加市场源（true = 已添加并开始刷新；false = 非法/重复）。 */
    override fun addMarketSource(raw: String): Boolean = market.addMarketSource(raw)

    /** 删除市场源（同时把它的条目从当前列表里移除，不等下次刷新）。 */
    override fun removeMarketSource(url: String) = market.removeMarketSource(url)

    /** 安装/更新一个插件（复用 SkillImportRepository，含 id 一致性校验）。 */
    override fun installMarketPlugin(id: String) = market.installMarketPlugin(id)

    /** 确认安装（只有此时才解压落盘）。 */
    override fun confirmMarketInstall() = market.confirmMarketInstall()

    /** 取消待确认的安装。 */
    override fun cancelMarketInstall() = market.cancelMarketInstall()

    /** 卸载一个已装插件（只删本地目录，市场列表保留该条目）。 */
    override fun uninstallMarketPlugin(id: String) = market.uninstallMarketPlugin(id)

    /** 由 skillsSnapshot 计算技能中心卡片数据（L5 安全策略摘要）。 */
    private fun computeSkillInfos(): List<SkillUiInfo> = skillsSnapshot.map { sk ->
        val policy = policyFor(sk.trust)
        SkillUiInfo(
            id = sk.id,
            name = sk.name,
            description = sk.description ?: "",
            trust = sk.trust,
            isImported = sk.id in importedIds,
            isScript = sk.entry != null,
            allowScript = policy.allowScript,
            maxCapability = policy.maxCapability.name,
            tools = sk.tools.map { t ->
                SkillToolInfo(
                    name = t,
                    capability = CapabilityRegistry.cap(t).name,
                    allowed = checkCapabilityGate(t, sk.trust) == null
                )
            },
            declaration = SkillDeclarations.of(sk.net_hosts, sk.entry, sk.tools)
        )
    }

    /** L8 运行日志落盘目录：filesDir/logs/agent-<yyyyMMdd>.log（core 内部异步写盘）。 */
    private val logDir = File(app.filesDir, "logs")

    /** L9 记忆库：跨会话长期记忆，持久化到 filesDir/memory/memory.json。 */
    private val memoryStore = MemoryStore(File(app.filesDir, "memory"))

    /** 系统 TTS（W-16）；onCleared 释放引擎。 */
    private val speaker = TextSpeaker(app)

    // —— 本地 llama.cpp 推理（九步第 3 步）————————————————————————

    /**
     * 本地模型这一摊子的所有者：参数、模型文件、下载进度、GPU 结论全在 [ModelSettings]。
     * 这里只转发流与动作，UI 的调用点与类型一个没变（名字都保留）。
     */
    private val modelSettings = ModelSettings(
        context = app,
        scope = viewModelScope,
        switchToLocalProvider = ::useLocalProvider,
        onStatus = { _importStatus.value = it },
    )

    /** 连接测试与日志要读这两个，故保留同名只读视图，避免把存储细节再抄一遍。 */
    internal val localSetting: LocalLlamaSetting get() = modelSettings.setting
    internal val modelStore: GgufModelStore get() = modelSettings.files

    override val localModels: StateFlow<List<DownloadedModel>> = modelSettings.models
    override val localActive: StateFlow<String> = modelSettings.active
    override val localLoaded: StateFlow<Boolean> = modelSettings.loaded
    override val localDownloading: StateFlow<Pair<String, Float?>?> = modelSettings.downloading
    override val localNCtx: StateFlow<Int> = modelSettings.nCtx
    override val localThreads: StateFlow<Int> = modelSettings.threads
    override val localMaxTokens: StateFlow<Int> = modelSettings.maxTokens
    override val localAgentMode: StateFlow<Boolean> = modelSettings.agentMode
    override val localGpuMode: StateFlow<GpuMode> = modelSettings.gpuMode
    override val localSafeMode: StateFlow<Boolean> = modelSettings.safeMode
    override val localResourceTierLock: StateFlow<String> = modelSettings.resourceTierLock
    override val localGpuCrashNote: StateFlow<String> = modelSettings.gpuCrashNote
    override val localGpuSummary: StateFlow<String> = modelSettings.gpuSummary

    /** 页签 onResume / 下载完成后调用。 */
    override fun refreshLocalModels() = modelSettings.refresh()

    override fun setActiveLocalModel(fileName: String) = modelSettings.setActive(fileName)

    override fun deleteLocalModel(fileName: String) = modelSettings.delete(fileName)

    override fun unloadLocalModel() = modelSettings.unload()

    override fun downloadLocalModel(entry: GgufCatalogEntry) = modelSettings.download(entry)

    override fun importLocalModel(uri: Uri) = modelSettings.import(uri)

    override fun setLocalNCtx(v: Int) = modelSettings.setCtx(v)

    override fun setLocalThreads(v: Int) = modelSettings.setThreads(v)

    override fun setLocalMaxTokens(v: Int) = modelSettings.setMaxTokens(v)

    override fun setLocalAgentMode(on: Boolean) = modelSettings.setAgentMode(on)

    override fun setLocalSafeMode(on: Boolean) = modelSettings.setSafeMode(on)

    override fun setLocalGpuMode(mode: GpuMode) = modelSettings.setGpuMode(mode)

    override fun setLocalResourceTierLock(name: String) = modelSettings.setResourceTierLock(name)

    /**
     * W-70：选中本地模型即把当前档案切到本地供应商。
     *
     * 真机反馈（2026-10-28 录屏 + 日志）：用户在配置页把供应商标成群「本地模型（内置）」、
     * 测试连接显示「连接正常」，但那是编辑区的临时值——没点「保存」就没写进 config，
     * 发消息时照旧打到 api.agnes-ai.cn，本地引擎一次都没启动。
     *
     * 只改 providerId，**不清 baseUrl/apiKey**：清空会让用户切回云端时得重填 Key，
     * 留着无害（本地客户端不读），且让「本地 ⇄ 云端」切换可逆。
     */
    private fun useLocalProvider() {
        val current = _config.value
        if (current.providerId == ProviderCatalog.STYLE_LOCAL_LLAMA) return
        updateConfig(current.copy(providerId = ProviderCatalog.STYLE_LOCAL_LLAMA))
        AgentLogger.i(
            "Chat",
            "选中本地模型 ${modelSettings.active.value} → 当前档案切到本地推理"
        )
    }

    // —— L8 运行日志 / L9 记忆库 —— 3.1 第二批拆出（实现见 LogsFacade / MemoryFacade）——

    /** 日志卡片域：零依赖，只读写 AgentLogger 的内存缓冲。 */
    private val logsFacade = LogsFacade()

    /** L8 最近日志（格式化后的文本，最新的在前），供自动化页「运行日志」卡片展示。 */
    override val logs: StateFlow<List<String>> get() = logsFacade.logs

    /**
     * 记忆卡片域（3.1 第二批拆出）：store 本体仍由本类持有——四个 Memory*Tool、
     * 会话恢复导入、prompt 注入共用同一实例，facade 接收引用。
     */
    private val memoryFacade = MemoryFacade(memoryStore)

    /** L9 记忆列表快照，随写入/检索/遗忘/清空刷新。 */
    override val memories: StateFlow<List<MemoryItem>> get() = memoryFacade.memories

    /**
     * 配置持久化（SharedPreferences 包装）：模型配置、开关、审批档位、SSH 档案都在这里，
     * 进程重启能还原，避免「明明填过却退回默认值」的不一致。
     *
     * 位置有讲究：**必须早于 [terminalBackendFactory]**。W-RT5 之后后端工厂的 SSH 探测
     * 要读「档案填了没有」（`sshSavedProfile()`），而能力链在任何一次 `refresh()` 里都会
     * 触发探测——包括构造期就可能被回调触发的那些。声明晚了，那次探测会读到未初始化的
     * `configStore` 并抛 NPE，而现场只是一次能力刷新，看起来与 SSH 毫无关系。
     */
    private val configStore = ConfigStore(app)

    /**
     * 终端后端工厂（T07）：proot 优先、不可用回落本机 user。
     * 单独持有引用，供能力链（DoD⑤）复用其带缓存的可用性判断，避免重复实现降级顺序与探测。
     */
    private val terminalBackendFactory: TerminalBackendFactory =
        createTerminalBackendFactory(
            context = app,
            distroProvider = { RootfsManager.listAvailable(app).firstOrNull() },
            // W-53：无线调试作为第三级（proot → adb → user）。provider 在 automation 包，
            // 由这里注入，避免 core 反向依赖 automation。W-56：Shizuku 也做成池后端
            // （uid 2000 + 持久 shell），所以默认会话链是 proot → shizuku → adb → user
            // W-RT5：SSH 也不进这条降级链（远端机器不能顶替本机 shell），
            // 只注册 provider 供会话池 obtainByKey(KEY_SSH) 显式路由。
            // ssh 命令的定位：Android 不预装，实际来源是容器 rootfs 的 /usr/bin（装了 openssh）。
            extraProviders = listOf(
                ShizukuBackendProvider(),
                AdbBackendProvider(),
                SshBackendProvider(
                    sshPathProvider = {
                        val rootfsBin = RootfsManager.listAvailable(app).firstOrNull()
                            ?.let { File(RootfsManager.rootfsDir(app, it.id), "usr/bin").absolutePath }
                        SshBinaryLocator.locate(SshBinaryLocator.defaultCandidates(rootfsBin))
                    },
                    profileProvider = { sshSavedProfile() }
                )
            )
        )

    /**
     * 终端会话池（T08）：shell 工具的运行时底座。后端工厂 proot 优先、不可用回落本机 user；
     * 上层（shell 工具）只认 4-hex 会话句柄，不存在「是容器还是本机」的分支。
     */
    private val terminalPool = TerminalSessionPool(terminalBackendFactory)

    /**
     * R3：终端/容器租约（按后端分池）。
     *
     * 租约的作用是防止**空闲回收关掉正在用的会话**：命令执行期间借出，
     * 执行完归还；之后若长时间没人用，回收器才会关掉该后端上的会话。
     */
    private val terminalLeases = TerminalLeases(terminalPool)

    /**
     * 能力就绪度快照（DoD⑤）：逐条能力给出「选中的执行路径 + 降级轨迹 + 最终引导」。
     * 3.1 第四批拆出，裁决逻辑见 [CapabilityReadiness]；两份 Android 侧快照由这里注入，
     * 因为「proot/rootfs 在不在、Shizuku 通不通」属于各自的域，不归裁决器管。
     *
     * 类型显式标注：lambda 反向引用 [permissionFacade]，交给推断会撞上 K1 的递归问题。
     */
    private val capabilityReadiness: CapabilityReadiness = CapabilityReadiness { capability: Capability ->
        when (capability) {
            Capability.RUN_SHELL -> terminalBackendFactory.shellCapabilitySnapshot(
                shizukuAvailable = permissionFacade.shizukuReady
            )
            Capability.CONTROL_SCREEN -> screenCapabilitySnapshot()
        }
    }
    override val capabilityPlans: StateFlow<List<CapabilityResolution>> get() = capabilityReadiness.plans

    /** 重算全部能力的就绪度（进入权限页 / 权限或自动化状态变化时调用）。 */
    fun refreshCapabilityPlans() = capabilityReadiness.refresh()

    /** MCP 服务器清单（W-14）：对话启动后按 enabled 列表批量连接并注册工具。 */
    private val mcpServerStore = McpServerStore(app)

    /**
     * MCP 注册完成信号（W-19）：启动是异步的，首条消息若抢在注册前发出，
     * 模型会看不到这些工具。send() 最多等 8 秒，超时照常跑（不卡用户）。
     */
    private val mcpReady = CompletableDeferred<Unit>()

    /** 任务清单存储（W-14）：按会话隔离。 */
    private val todoStore = TodoStore(File(app.filesDir, "todos.json"))

    /** 定时任务存储（W-17）。store 本体仍由本类持有：四个 Workflow*Tool 注册共用。 */
    private val workflowStore = WorkflowStore(File(app.filesDir, "workflow"))

    /**
     * 会话存储（W-18：会话检索工具要用）。
     *
     * 声明顺序必须早于 [registry]：Kotlin 属性按声明顺序初始化，
     * 若工具在 registry 里构造时 store 还没赋值，会拿到 null 并 NPE。
     */
    private val conversationStore = ConversationStore(File(app.filesDir, "conversations.json"))

    /**
     * W-30：消息收藏。独立文件（删会话不丢收藏），列表在内存缓存，
     * 由 [_bookmarks] 驱动收藏页与菜单勾选态。
     */
    private val bookmarkStore = BookmarkStore(File(app.filesDir, "bookmarks.json"))

    /**
     * W-33：后台命令。独立进程 + 日志落盘 + 完成通知，
     * 与会话池完全隔离（不抢 PTY、不排队）。
     */
    private val jobStore = JobStore(File(app.filesDir, "jobs"))
    private val jobRunner = JobRunner(jobStore) { job ->
        runCatching { JobNotifier.notify(app, job) }
    }
    private val _bookmarks = MutableStateFlow(bookmarkStore.list())
    override val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    /** W-30：翻译结果缓存（按消息下标），会话切换时清空。 */
    private val _translations = MutableStateFlow<Map<Int, TranslationUi>>(emptyMap())
    override val translations: StateFlow<Map<Int, TranslationUi>> = _translations.asStateFlow()

    /** W-30：正在翻译的消息下标（null = 空闲）。同一时刻只翻一条，避免并发打满 Key。 */
    private val _translatingIndex = MutableStateFlow<Int?>(null)
    override val translatingIndex: StateFlow<Int?> = _translatingIndex.asStateFlow()

    private val registry = ToolRegistry(startup.toolProfiler).apply {
        startup.mark("preRegistry") // 口径：ToolRegistry() 构造极轻，重的是下面 45 个 register
        register(HttpRequestTool())
        register(ReadFileTool(workspace))
        register(WriteFileTool(workspace, WriteBackupHook { fileBackupStore.backupBeforeWrite(it) }))
        // W-20：精确编辑（局部替换，不整文件覆写）+ 网页搜索（实时信息入口）
        register(EditFileTool(workspace, WriteBackupHook { fileBackupStore.backupBeforeWrite(it) }))
        register(WebSearchTool())
        register(ClipboardTool(app))
        register(NotificationTool(app))
        // 手机自动化工具组（受总开关 + 无障碍服务连接双重保护）
        register(UiTreeTool())
        register(UiTapTool())
        register(UiLongPressTool())
        register(UiSwipeTool())
        register(UiInputTool())
        register(UiKeyTool())
        register(UiClickTool())
        // 截图（无障碍）：喂给视觉模型“看”屏幕
        register(UiScreenshotTool(workspace))
        // 设备感知（L3）：通知监听 + 当前前台应用
        register(DeviceNotificationsTool())
        register(DeviceForegroundAppTool(app))
        // 记忆库（L9）：让 Agent 拥有跨会话长期记忆
        register(MemoryWriteTool(memoryStore))
        register(MemorySearchTool(memoryStore))
        register(MemoryListTool(memoryStore))
        register(MemoryForgetTool(memoryStore))
        // W-16：系统 TTS 朗读
        register(SpeakTool(speaker))
        // W-18：会话检索 + 计算器
        register(ConversationSearchTool(conversationStore))
        // W-RT6：本地知识库检索。目录固定 files/knowledge/，用户把 .txt/.md 放进去即可；
        // 没有独立导入界面——Agent 本身有文件能力，「把这份资料加进知识库」就是一句工具调用。
        register(KnowledgeSearchTool(java.io.File(app.filesDir, "knowledge")))
        register(CalculatorTool())
        // W-17：定时任务（模型可自建/查询/删除/开关）
        register(WorkflowCreateTool(workflowStore) { refreshWorkflows() })
        register(WorkflowListTool(workflowStore))
        register(WorkflowDeleteTool(workflowStore) { refreshWorkflows() })
        register(WorkflowToggleTool(workflowStore) { refreshWorkflows() })
        // 终端 shell（T08）：会话池 + 短句柄；shell_run 执行命令、shell_close 释放会话
        register(
            ShellRunTool(
                pool = terminalPool,
                leases = terminalLeases,
                privilegedExec = { cmd ->
                    // W-37：优先 UserService（shell 进程内起本地进程，waitFor 语义正常，进程常驻）；
                    // 不可用时回落 W-36 的 newProcess 轮询通道；两者都不可用才回落本机 user shell。
                    val r = ShizukuShell.execViaUserService(cmd) ?: ShizukuShell.exec(cmd)
                    // 未运行/未授权（通道压根没起来）→ null 让上层回落本机 user shell。
                    // 其余启动失败（newProcess 抛异常等）必须如实上抛，不能伪装成超时。
                    if (r.startError != null && r.startError.contains("未运行或未授权")) {
                        permissionFacade.reprobeShizukuStatus()
                        null
                    } else {
                        PrivilegedShellOut(
                            exitCode = r.exitCode,
                            stdout = r.stdout,
                            stderr = r.stderr,
                            timedOut = r.timedOut,
                            startError = r.startError,
                            elapsedMs = r.elapsedMs
                        )
                    }
                }
            )
        )
        register(ShellCloseTool(terminalPool))
        // W-15：浏览器五件套（真实 WebView，保留登录态、能点能填）
        register(WebOpenTool())
        register(WebSnapshotTool())
        register(WebClickTool())
        register(WebTypeTool())
        register(WebEvalTool())
        // W-14：结构化追问 + 任务清单
        register(AskUserTool())
        // W-25：先计划后执行——提交多步计划并等待用户批准
        register(PlanTool(broker = PlanBroker, planModeEnabled = { settingsFacade.planMode.value }))
        register(TodoWriteTool(todoStore) { _activeConversationId.value })
        register(TodoReadTool(todoStore) { _activeConversationId.value })
        // 应用操控（Phase 1 补口）：列出/启动/停止任意已安装应用——「叫它打开快手就能打开」。
        // app_launch 走 Intent（零权限）；app_stop 接**真实 shell 通道**（会话池适配，见下），
        // 通道不可用（池无可用后端）时仍走「诚实降级为需用户手动停止」，绝不静默假装成功。
        // apply 内传 this（不能传 registry 自身，会触发类型推断递归）
        registerAppControlTools(this, app, terminalShellCommandRunner(terminalPool))
        // W-24：离线 OCR（截图/读图 → 文字，任何模型都能读）+ 软件安装/卸载
        register(OcrTool(workspace))
        register(AppInstallTool(app, workspace))
        register(AppUninstallTool(app, terminalShellCommandRunner(terminalPool)))
        // W-31：并行子代理——只读工具 + 独立上下文，只把结论带回主对话。
        // baseRegistry 传当前表（apply 内 this）：子代理表由白名单过滤，spawn_agents 自身被排除（防递归）。
        register(
            SubAgentTool(
                SubAgentRunner(
                    llmProvider = { buildMainClient() },
                    baseRegistry = { this },
                    // R4：审批穿过子代理边界（白名单当前无副作用工具，但不能再依赖这个巧合）。
                    // 用 lambda 间接引用而不是直接传值：registry 的初始化早于 approvalHandler，
                    // 直接取值会捕到 null；lambda 在真正弹窗时才读。
                    approval = { tool, args -> approvalHandler(tool, args) }
                )
            )
        )
        // W-32：GitHub 提交三件套——「改完能提交」闭环（复用「构建」页的仓库与 PAT）
        register(GitHubStatusTool(app))
        register(GitHubCommitTool(app))
        register(GitHubLogTool(app))
        // W-34：行级 diff——提交前确认到底改了哪几行
        register(GitHubDiffTool(app))
        // W-35：整仓克隆到工作区子目录（codeload zip，走镜像回退）
        register(GitHubCloneTool(app))
        // W-33：后台命令——长命令不再阻塞整轮，跑完发通知
        register(ShellBackgroundTool(app, jobRunner))
        register(ShellJobsTool(jobStore, jobRunner))
        // B 批：容器运行时管理——让 Agent 自己把容器变成能跑 node/python/java 的环境
        register(ContainerToolchainTool(app))
        startup.mark("registry") // 工具注册表整段（40+ 工具实例化）：最可能随工具数线性增长的段
    }

    /** R-07：MCP 工具同步器（可回卷）。必须声明在 [registry] 之后：属性按声明序初始化。 */
    private val mcpBinder = McpToolBinder(
        registry = registry,
        loader = ClientMcpToolLoader(),
        maxToolsPerServer = MCP_MAX_TOOLS_PER_SERVER,
        maxToolsTotal = MCP_MAX_TOOLS_TOTAL
    )

    /** JS 执行引擎（Rhino，纯 Java 无需 NDK）；初始化失败则降级为仅 prompt 形态。 */
    private val jsEngine: JsEngine? = runCatching { RhinoJsEngine() }.getOrNull()

    /** Skill 执行器：把工具注册表、工作目录、脚本引擎、资源读取接到一起。 */
    private val skillExecutor = SkillExecutor(
        registry = registry,
        workspace = workspace,
        jsEngine = jsEngine,
        readBody = { sk -> if (sk.id in importedIds) SkillLoader.readImportedBody(app, sk) else SkillLoader.readSkillBody(app, sk) },
        readEntry = { sk -> if (sk.id in importedIds) SkillLoader.readImportedEntry(app, sk) else SkillLoader.readEntry(app, sk) }
    )

    init {
        // L8：日志目录就绪后才能记录后续任何一行；R3 起已挪到 Application.onCreate，此处幂等兜底。
        runCatching { AgentLogger.init(logDir) }
        // 【1.595】启动对账下沉到 IO：三件事都是磁盘扫描（残留任务 / 运行态 / 回合日志），
        // 结果只用于一条中断提示。原来同步跑在构造线程上，冷启动首帧要等这几份 JSON 读完。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { jobStore.reconcile(System.currentTimeMillis()) }
            runCatching {
                agentRunStateStore.reconcile()?.let { _interruptedNotice.value = it.note }
            }
            runCatching { recoverInterruptedJournal() }
        }
        if (skillsSnapshot.isNotEmpty()) {
            // use_skill 工具的 systemPrompt 会被 AgentLoop 自动拼进系统提示（利用工具自带提示特性）
            // 用 skillsProvider 动态感知导入/卸载/授权的技能变化
            registry.register(createUseSkillTool({ skillsSnapshot }, skillExecutor))
        }
        // W-23：load_skill——模型可自己安装/卸载技能包（装完热生效，立刻能用 use_skill）
        registry.register(
            LoadSkillTool(
                download = { url -> downloadSkillZip(url) },
                install = { bytes -> importRepo.installZip(bytes) },
                uninstall = { id -> importRepo.uninstall(id) },
                installedSkills = {
                    skillsSnapshot.filter { it.id in importedIds }.map { it.id to it.name }
                },
                onChanged = { reloadSkills() }
            )
        )
        // 同步总开关到桥；并把服务连接变化回写到 UI 状态流
        AutomationBridge.enabled = automationPrefs.enabled
        AutomationBridge.onConnectionChanged = { connected ->
            _serviceConnected.value = connected
            refreshCapabilityPlans() // 无障碍连接变化 → 能力就绪度随之刷新
        }
        // W-17：注册周期巡检（读磁盘，跟着对账一起下沉）。
        // 列表载入**不能**放这里：本 init 块在类体第 971 行，早于第 1331 行的 workflowFacade，
        // 慢设备上 IO 协程先于属性初始化抢到 CPU 就会读到未初始化的 facade（2026-11-09 模拟器复现 NPE）。
        // 它挪到类体末尾的 init 块里，那里所有字段已就绪。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WorkflowScheduler.ensureScheduled(app) }
        }
        // P3-1：能力清单自检（只在有偏差时 warn）。必须排在 MCP 注册之前——MCP 工具名是运行时来的，参与审计只会制造噪声。
        CapabilityAuditReporter.report(registry.list())
        // W-14/R-07：异步同步 MCP 工具（失败只记日志，不影响启动；停用的会在这里被撤销）
        refreshMcpTools()
        refreshGrants() // 已异步（内部 launch(IO)）
        // L8/L9：首屏即有内容可看。200 条日志的 format() 实测占 600ms+（SimpleDateFormat 加锁），挪到 IO
        viewModelScope.launch(Dispatchers.IO) { refreshLogs(); refreshMemories() }
        startup.finish("refresh", "ViewModel 初始化分段")
    }

    /**
     * R1：启动对账——扫描上次进程被杀留下的未闭合回合，把孤儿调用变成可读的提示。
     *
     * 判据来自日志事实而非猜测：`OUTCOME_UNKNOWN` = 已越过执行边界但没记到结果，
     * 说明工具**可能已经生效**；`NOT_DISPATCHED` = 声明了但没派发，重试绝对安全。
     * 这个两轴区分是本次改造的核心——只按「工具有没有副作用」判，会让一个根本没启动的
     * `rm` 也走一遍「先核对」，白烧一轮；只按「日志有没有记录」判，又会让已生效的写操作被重放。
     *
     * 全部包在 runCatching 里：启动阶段任何异常都不该阻止 App 起来（日志坏了就退化成旧行为）。
     */
    private fun recoverInterruptedJournal() {
        viewModelScope.launch {
            val results = turnJournal.scanAndRepair()
            if (results.isEmpty()) return@launch
            val orphans = results.flatMap { it.orphans }
            val anomalies = results.flatMap { it.anomalies }
            if (anomalies.isNotEmpty()) {
                AgentLogger.w(
                    "Recovery",
                    "回合日志不一致 ${anomalies.size} 条：" + anomalies.joinToString("；")
                )
            }
            if (orphans.isEmpty()) return@launch
            val unknown = orphans.filter { it.mayHaveTakenEffect }
            val safe = orphans.filterNot { it.mayHaveTakenEffect }
            AgentLogger.w(
                "Recovery",
                "上次回合被中止：${unknown.size} 个写操作结局未知，${safe.size} 个未派发。明细=" +
                    orphans.joinToString { "${it.toolName}:${it.status}" }
            )
            val note = buildString {
                append("上次执行被系统中止。")
                if (unknown.isNotEmpty()) {
                    append("其中 ${unknown.size} 个写操作（")
                    append(unknown.map { it.toolName }.distinct().joinToString("、"))
                    append("）可能已经生效——继续之前建议先核对结果，我不会自动重做。")
                }
                if (safe.isNotEmpty()) {
                    if (unknown.isNotEmpty()) append(" 另有 ")
                    append("${safe.size} 个调用还没开始执行，可以安全重试。")
                }
            }
            pendingRecoveryNote = note
            // 提示优先级：**有写操作结局未知时，以这一句为准**。实测的坑：运行态那句
            // 「上次任务已被系统中断」先到把这里挡住，用户只看到「可以直接说继续」——
            // 而这恰恰是最不能直接继续的情况；没有未知写操作时才让位给运行态那句。
            if (unknown.isNotEmpty() || _interruptedNotice.value == null) {
                _interruptedNotice.value = note
            }
        }
    }

    /**
     * 资源生命周期收口（QA 定级的 Major：资源泄漏修复）。
     *
     * [TerminalSessionPool] 持有一条条**真实子进程**（本机 `/system/bin/sh`、proot 会话），
     * 子进程**不随 GC 回收**，只有显式关闭才释放；Activity `finish()` 时若不收口，
     * 反复进出会累积泄漏 shell/proot 进程。
     *
     * 用非挂起的 [TerminalSessionPool.releaseAllAsync] 而非 `closeAll()`：[onCleared] 是**同步**
     * 钩子（主线程），`runBlocking` 会把主线程卡住。它**同步**清空会话表（旧句柄立即失效）+
     * **异步**关闭后端进程，既非阻塞又达成防泄漏。内部已对空池 / close 抛错做 `runCatching`，
     * 此处再包一层，保证生命周期回调绝不抛异常（回调抛错会拖垮进程收尾）。
     */
    override fun onCleared() {
        runCatching { terminalPool.releaseAllAsync() }
        // W-16：释放 TTS 引擎（不释放会一直占着音频焦点）
        runCatching { speaker.shutdown() }
        // W-25：唤醒挂起中的提问/计划，避免 deferred 悬着（协程随 ViewModel 取消）
        runCatching { AskUserBroker.cancel() }
        runCatching { PlanBroker.cancel() }
        super.onCleared()
    }

    /** 设置页：切换自动化总开关（同时持久化并同步到桥）。 */
    override fun setAutomationEnabled(v: Boolean) {
        settingsFacade.setAutomationEnabled(v)
        refreshCapabilityPlans() // 总开关变化 → 刷新「操作手机界面」能力就绪度
    }

    /** 设置页：跳转系统无障碍设置页，引导用户开启本应用的服务。 */
    override fun openAccessibilitySettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /**
     * 权限引导页：申请电池优化白名单（P0，2026-10-21）。实现见 [PermissionFacade]。
     */
    override fun openBatteryOptimizationSettings(ctx: Context) =
        permissionFacade.openBatteryOptimizationSettings(ctx)

    /** 刷新权限授予状态。**异步**：[PermissionFacade.refreshGrants] 在 IO 上探测 5 处状态。 */
    override fun refreshGrants(): Job = permissionFacade.refreshGrants {
        // DoD⑤：权限/环境变化后重算能力就绪度（能力链一次走完），逐级落降级轨迹日志由裁决器做
        capabilityReadiness.refresh()
    }

    // —— 功能三：聊天记录（用户真机录屏反馈：「人家都有聊天记录对话记录，你这都没有」）——
    // 会话持久化：conversations.json 放 filesDir，原子写 + .bak（形态学 MemoryStore）

    // —— 功能五（P1-8）：每会话独立草稿（drafts.json；切会话存旧取新，发送即清）——
    private val draftStore = DraftStore(File(app.filesDir, "drafts.json"))

    /** 当前会话 id；_messages 的语义即「当前会话的消息」。 */
    private val _activeConversationId = MutableStateFlow(conversationStore.ensureNotEmpty())
    override val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    /** 会话摘要列表（updatedAt 倒序；抽屉「最近对话」与对话页顶栏标题消费）。 */
    private val _conversations = MutableStateFlow(conversationStore.list())
    override val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    /** Hotfix2：过程事件回调来自工具并行执行线程，与主线程的 send() 竞争同一列表——加锁防丢更新。 */
    private val msgLock = Any()

    /** 一轮生成的运行态都在 [GenerationRunController]；这里只暴露流 + 收尾钩子（清横幅→落盘→重建→刷日志）。
     *  必须排在 [projection] 之前：它的 init 会读 `ownerId()`，本属性晚于它构造就只能读到 null，
     *  启动即崩「Cannot create an instance of ChatViewModel」——lambda 延迟求值，编译期毫无征兆；
     *  by lazy 也救不了（delegate 字段同样按声明序赋值）。类型显式标注是因为三个 lambda 反向
     *  引用本类成员，交给推断会撞上 K1 的 recursive problem，且报错指向无关调用。 */
    private val generation: GenerationRunController = GenerationRunController(
        scope = viewModelScope,
        activeId = { _activeConversationId.value },
        onStarted = { _truncationNotice.value = null },
        onSettling = { owner -> projection.settleInterrupted(owner) },
        onFinished = { rebuildMessages ->
            // Phase 2 DoD③：兜底清「正在恢复」——重试收尾事件之外的最后一道保险
            _retryState.value = null
            // W-102：粘性激活的落盘点。send 的成功/失败/取消与 regenerate 全都汇聚到这里，漏一处就是「这次开的组没记住」。
            persistToolGroups()
            if (rebuildMessages) reloadActiveMessages()
            refreshLogs()
        },
    )

    /**
     * 当前会话的消息；启动时从会话存储载入（功能三：重启 App 后对话还在）。
     * Phase 2 DoD②：由 [ConversationRecord.toUiItems] 逐轮渲染（带变体游标元数据），
     * 使变体切换后助手气泡能正确反映当前选中变体。
     * 显示态与落库合在 [ConversationProjection] 一处，本类只持流引用。
     * 声明序保证 [conversationStore] / [_activeConversationId] / [msgLock] 已就绪。
     */
    private val projection = ConversationProjection(
        store = conversationStore,
        activeId = { _activeConversationId.value },
        ownerId = { generation.owner },
        ownerActive = { isOwnerActive() },
        onStoreChanged = { refreshConversations() },
        lock = msgLock,
        onHistoryTruncated = { dropped -> noticeHistoryTruncated(dropped) },
        // W-RT7：延迟求值——投影是长生命周期单例，模型却可能在会话中途切换。
        currentModelId = { currentModelLabel() },
    )
    override val messages: StateFlow<List<UiItem>> = projection.items

    /**
     * W-RT7：这条回答该记成哪个模型。
     *
     * 本地推理统一记 `local-llama`：它在价格表里是 0，而且不会因为本地模型文件名
     * 恰好含 "gpt-4" 之类被云端价格表误命中——那种误算比不显示更糟。
     */
    private fun currentModelLabel(): String =
        if (_config.value.providerId == "local-llama") "local-llama" else _config.value.model

    /**
     * W-RT7：当前会话的费用下钻（按模型聚合，参考价）。
     *
     * 纯展示用即时计算：消息就在内存里，遍历一次的开销可以忽略，
     * 不落盘也不缓存——缓存反而会在「切会话 / 删消息」后给出过期数字。
     */
    override fun costDrillDown(): List<ModelPricing.ModelCost> =
        ModelPricing.drillDown(
            messages.value.mapNotNull { item ->
                (item as? UiItem.Assistant)?.usage?.let { usage ->
                    ModelPricing.UsageEntry(
                        modelId = item.modelId,
                        promptTokens = usage.promptTokens,
                        completionTokens = usage.completionTokens
                    )
                }
            }
        )

    // —— 3.1 第三批：设置开关 / 聊天外观 —— 实现见 SettingsFacade / AppearanceFacade ——

    // configStore 已上移到字段区前部（W-RT5：后端工厂的 SSH 探测依赖它，声明顺序有约束）。

    /**
     * 四个设置开关（自动化 / 通知 / 任务模式 / 任务模型）。
     * 声明必须晚于 [configStore] 与 [automationPrefs]——两者由 facade 构造时读取。
     */
    private val settingsFacade = SettingsFacade(configStore, automationPrefs)

    /** 聊天背景图（SAF URI + 浓度），见 [AppearanceFacade]。 */
    private val appearanceFacade = AppearanceFacade(configStore)

    /** 回答完成提醒开关（用户显式 opt-in，跨启动保持）。 */
    override val completionNotifyEnabled: StateFlow<Boolean> get() = settingsFacade.completionNotifyEnabled

    /** 设置页开关：写回 ConfigStore（即时生效 + 跨启动保持）。 */
    override fun setCompletionNotifyEnabled(enabled: Boolean) = settingsFacade.setCompletionNotifyEnabled(enabled)

    /** 对话页背景图（SAF content:// 字符串；null = 无背景）。 */
    override val chatBackgroundUri: StateFlow<String?> get() = appearanceFacade.chatBackgroundUri

    override val chatBackgroundAlpha: StateFlow<Float> get() = appearanceFacade.chatBackgroundAlpha

    /** 设置/清除背景图（uri = SAF content:// 字符串；null = 清除）。 */
    override fun setChatBackground(uri: String?) = appearanceFacade.setChatBackground(uri)

    /** 调背景图浓度（clamp 在 ConfigStore 内，这里只透传）。 */
    override fun setChatBackgroundAlpha(alpha: Float) = appearanceFacade.setChatBackgroundAlpha(alpha)

    // —— 功能二：多模型配置（用户真机录屏反馈：「别人的模型可以搞好几个，你就只能搞一个」）——
    // _profiles = 全部档案，_activeProfileId = 当前生效档案；config 同名同型保留
    // （= active profile 的 config），现有消费者（对话页 / 401 气泡 / 测试连接）零改动
    private val _profiles = MutableStateFlow(configStore.loadProfiles())
    private val _activeProfileId = MutableStateFlow(configStore.loadActiveId().orEmpty())

    /**
     * 取出当前生效配置；顺带做两道兜底（profiles 为空 / activeId 失效），
     * 保证 [config] 永远有值——老消费者零改动。
     */
    private fun ensureActiveConfig(): ModelConfig {
        if (_profiles.value.isEmpty()) {
            // 极端：profiles_json 损坏成空数组——建默认配置落盘，App 继续可用
            val id = UUID.randomUUID().toString()
            val profile = ModelProfile(id, "默认配置", ModelConfig())
            _profiles.value = listOf(profile)
            _activeProfileId.value = id
            configStore.saveProfiles(_profiles.value, id)
            return profile.config
        }
        val active = _profiles.value.firstOrNull { it.id == _activeProfileId.value }
            ?: _profiles.value.first()
        // activeId 指向不存在的档案（数据漂移）：回退第一个并纠正内存态
        if (active.id != _activeProfileId.value) _activeProfileId.value = active.id
        return active.config
    }

    /** 当前模型配置（已落盘，UI 直接消费）；= 当前生效 profile 的 config。 */
    private val _config = MutableStateFlow(ensureActiveConfig())
    override val config: StateFlow<ModelConfig> = _config.asStateFlow()

    /**
     * 「当前配置足以开始对话」——**唯一真源**。
     *
     * W-70：原先三处各写一遍 `baseUrl && apiKey && model` 都非空（对话页指示条、
     * 首启权限引导、聚焦提示），而本地模式这三项本来就该是空的：用户切到本地后
     * 顶栏立刻显示「模型未配置」，点一下还把人送回配置页——这就是「切了像没用」。
     *
     * 云端看三要素，本地看有没有选模型文件。以后再加判断点请复用这里，不要再内联。
     * 初值同步算一次（不走默认 false），否则首帧会闪一下「模型未配置」。
     */
    override val modelReady: StateFlow<Boolean> =
        combine(_config, localActive) { cfg, local -> readyOf(cfg, local) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                readyOf(_config.value, localActive.value)
            )

    private fun readyOf(cfg: ModelConfig, localModel: String): Boolean =
        if (cfg.providerId == ProviderCatalog.STYLE_LOCAL_LLAMA) {
            localModel.isNotBlank()
        } else {
            cfg.baseUrl.isNotBlank() && cfg.apiKey.isNotBlank() && cfg.model.isNotBlank()
        }

    /** 全部模型配置档案（配置页切换条消费）。 */
    override val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    /** 当前生效档案 id（配置页高亮 + 编辑器语义锚点）。 */
    override val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    /** 「测试连接」结果流：配置页卡片消费，null 表示尚未测试。 */
    private val _testResult = MutableStateFlow<TestResult?>(null)
    override val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    /**
     * A 批：输入变化时清空上一次测试结果。
     * 否则「测通旧配置 → 改 Base URL → 卡片仍绿色 → 保存并回对话」会把没测过的配置落盘。
     */
    override fun clearTestResult() {
        _testResult.value = null
    }

    /** 是否正在测试连接：UI 用它给按钮显示加载态，避免重复点击。 */
    private val _testing = MutableStateFlow(false)
    override val testing: StateFlow<Boolean> = _testing.asStateFlow()

    override val input = MutableStateFlow("")

    override val isRunning: StateFlow<Boolean> = generation.running
    override val generatingConvId: StateFlow<String?> = generation.generating
    override val streamDraft: StateFlow<String?> = generation.draft
    override val reasoningDraft: StateFlow<String?> = generation.reasoning

    /** owner 是否就是当前显示会话——在途 UI 更新（草稿/工具卡/气泡）的投影开关。 */
    private fun isOwnerActive(): Boolean = generation.isOwnerActive()

    /** 切会话/收尾后把草稿缓冲投影到显示态：active==owner 恢复，否则清（缓冲不丢）。 */
    private fun syncDraftDisplay() = generation.syncDisplay()

    /* ------------------------------ W-17 定时任务 ------------------------------ */

    /**
     * 定时任务域（3.1 第二批拆出，实现见 [WorkflowFacade]）。字段声明必须晚于
     * [input] / [isRunning]——两个回调在构造期捕获它们。
     */
    private val workflowFacade = WorkflowFacade(
        workflowStore,
        isRunning = { isRunning.value },
        runPrompt = { prompt -> input.value = prompt; send() },
    )

    override val workflows: StateFlow<List<Workflow>> get() = workflowFacade.workflows

    override fun refreshWorkflows() = workflowFacade.refreshWorkflows()

    override fun setWorkflowEnabled(id: String, enabled: Boolean) = workflowFacade.setWorkflowEnabled(id, enabled)

    override fun deleteWorkflow(id: String) = workflowFacade.deleteWorkflow(id)

    /** 立即运行定时任务：走前台对话（理由见 facade）。 */
    override fun runWorkflowNow(id: String) = workflowFacade.runWorkflowNow(id)

    /** 处理「点通知进入 App」携带的任务 id（MainActivity 调用）。 */
    fun consumeWorkflowIntent(id: String?) = workflowFacade.consumeWorkflowIntent(id)

    /** 待回答的用户提问（W-14）：非空时聊天页显示选择卡片。 */
    override val askUserPending: StateFlow<AskUserBroker.Question?> = AskUserBroker.pending

    // —— W-25：任务模式（PLAN / AUTO）与待批计划 ——
    // 开关状态见 [SettingsFacade]；待批计划仍在 PlanBroker。
    override val planMode: StateFlow<Boolean> get() = settingsFacade.planMode

    /** UI 观察：有可批准的计划时显示计划卡片。 */
    override val planPending: StateFlow<PlanBroker.Plan?> = PlanBroker.pending

    override fun setPlanMode(enabled: Boolean) = settingsFacade.setPlanMode(enabled)

    override val approvalTier: StateFlow<String> get() = settingsFacade.approvalTier

    override fun setApprovalTier(mode: String) = settingsFacade.setApprovalTier(mode)

    /* ------------------- W-RT5：SSH 连接档案（v1 单档案，仅密钥登录） ------------------- */

    /**
     * UI 回调：保存 SSH 连接档案。
     *
     * @return 校验不通过时返回给用户看的理由（UI 直接展示，**不落盘**）；成功返回 null。
     *
     * 两件事不能省：
     *  ① 校验交给 core 的纯函数（`normalizeSshProfile`）——它是唯一判据，UI 与后端装配共用；
     *  ② 保存后必须 [TerminalBackendFactory.clearCache]：探测结果依赖「有没有档案」，
     *     不清缓存的话，刚填好的配置要等下次进程启动才生效，用户会以为保存失败了。
     */
    override fun saveSshProfile(host: String, port: String, user: String, keyPath: String): String? {
        val profile = when (val r = normalizeSshProfile(host, port, user, keyPath)) {
            is SshProfileResult.Invalid -> return r.reason
            is SshProfileResult.Ok -> r.profile
        }
        configStore.setSshProfile(profile.host, profile.port.toString(), profile.user, profile.keyPath)
        terminalBackendFactory.clearCache()
        AgentLogger.i("Chat", "SSH 连接档案已保存：${profile.user}@${profile.host}:${profile.port}")
        return null
    }

    /** 当前 SSH 档案（设置页回显用；未配置或校验不过返回 null）。 */
    override fun sshSavedProfile(): SshProfile? =
        (normalizeSshProfile(
            configStore.sshHost(), configStore.sshPort(), configStore.sshUser(), configStore.sshKeyPath()
        ) as? SshProfileResult.Ok)?.profile

    /** UI 回调：批准计划。 */
    override fun approvePlan(planId: String) {
        PlanBroker.approve(planId)
        AgentLogger.i("Chat", "用户批准了计划 $planId")
    }

    /** UI 回调：拒绝/要求修改计划。 */
    override fun rejectPlan(planId: String, reason: String) {
        PlanBroker.reject(planId, reason)
        // 审计 F12：理由与回答都是用户手打的，可能含账号、地址；只记长度。
        AgentLogger.i("Chat", "用户拒绝了计划 $planId（理由 ${reason.length} 字）")
    }

    // —— W-26：任务模型（标题生成等轻量任务用便宜模型，省钱降延迟）——
    override val taskProfileId: StateFlow<String> get() = settingsFacade.taskProfileId

    /** 设置任务模型档案；传空串 = 跟随主配置。 */
    override fun setTaskProfile(id: String) = settingsFacade.setTaskProfile(id)

    /** UI 回调：用户点了某个选项 / 提交了自由输入。 */
    override fun answerAskUser(questionId: String, choice: String) {
        AskUserBroker.answer(questionId, choice)
        AgentLogger.i("Chat", "用户回答了提问（${choice.length} 字）")
    }

    /**
     * 自动记忆抽取（W-16）：只在用户消息命中显式信号时写入，宁漏不滥。
     *
     * 为什么不在模型回复里抽：模型输出可能包含幻觉或推测，写进长期记忆会自我强化；
     * 用户亲口说的（「记住…」「我习惯…」）才是可信事实。
     */
    private fun autoCaptureMemory(userText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = MemoryAutoCapture.extract(userText)
            if (candidates.isEmpty()) return@launch
            val existing = memoryStore.snapshot().map { it.content }
            var added = 0
            candidates.forEach { c ->
                if (MemoryAutoCapture.isDuplicate(c.content, existing)) return@forEach
                runCatching {
                    memoryStore.add(content = c.content, kind = c.kind, tags = emptyList(), salience = c.salience)
                    added++
                    // 记忆内容是从用户话里提炼的个人信息，最不该落盘。
                    AgentLogger.i("Memory", "自动记住（${c.kind}，重要度 ${c.salience}，${c.content.length} 字）")
                }
            }
            if (added > 0) refreshMemories()
        }
    }

    /**
     * R-07：把 MCP 配置的现状同步进工具表——新增的装上，被停用/删除的撤掉，改地址的重连。
     * 同步语义（该撤谁）在 `McpToolSync.kt`，那边是纯函数、有行为断言；这里只负责读配置 +
     * 起协程 + 记一条日志。异步且失败不抛：服务器挂了不能让 App 启动失败。
     * 每台最多 [MCP_MAX_TOOLS_PER_SERVER] 个、总量 [MCP_MAX_TOOLS_TOTAL] 个（工具描述要进
     * 系统提示）；只连 enabled 的服务器。调用时机：启动一次 + MCP 页增删改后回调一次；
     * binder 内有互斥锁，重复调用幂等。
     */
    fun refreshMcpTools() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val servers = runCatching { mcpServerStore.load() }.getOrDefault(emptyList())
                val r = mcpBinder.sync(servers)
                if (r.installed > 0 || r.revoked > 0 || r.failed.isNotEmpty()) {
                    AgentLogger.i(
                        "MCP",
                        "工具同步：+${r.installed} -${r.revoked}，在用 ${r.total}，失败 ${r.failed.size}"
                    )
                }
            } finally {
                // 无论成功/失败/没配置，都要放行首条消息的等待
                if (!mcpReady.isCompleted) mcpReady.complete(Unit)
            }
        }
    }

    /**
     * T05：停止当前运行（流式中点「停止」）。
     * 取消运行句柄 → AgentLoop 每轮边界 ensureActive 退出 + 树尾闭合补结果。
     * 未在运行时调用为 no-op。
     */
    override fun stop() {
        if (!generation.cancel()) return
        AgentLogger.i("Chat", "用户请求停止运行")
        // W-14：挂起中的 ask_user 一并唤醒（否则协程被取消后 deferred 悬着，卡片不消失）
        AskUserBroker.cancel()
        // W-25：待批计划同样唤醒（视为拒绝），否则计划卡片会一直挂在输入栏上方
        PlanBroker.cancel()
        // Phase 2 DoD③/T05：取消立即清「正在恢复」——不等协程收到取消再清，UI 即刻复原
        _retryState.value = null
    }

    // —— Phase 2 DoD②：消息分支（重新生成 / 左右切换 / 从中间轮重新发问）——

    /**
     * 重新生成**最后一轮**的助手回答：走 T01 流式路径（[com.beichen.polaris.core.LLMClient.streamChat]，
     * 经 [AgentLoop] 的 onTextDelta 打字机上屏到 [streamDraft]），完成后**追加**一个新变体并选中
     * （旧变体保留、可左右切回）——覆盖 DoD②「重新生成 3 次可左右切换」。
     *
     * 运行中忽略（UI 禁用只是双保险）。持久化经 [ConversationStore.appendVariant]，杀进程后仍在。
     */
    override fun regenerate() {
        if (isRunning.value) return
        val rec = conversationStore.load(_activeConversationId.value) ?: return
        val turns = rec.turns
        val turnIndex = turns.lastIndex
        if (turnIndex < 0) return
        val userText = turns[turnIndex].user.content
        if (userText.isBlank()) return

        // hotfix30：同 send——捕获在途归属会话，切走再切回不写串（appendVariant 按 owner）
        val owner = _activeConversationId.value
        AgentLogger.i("Chat", "重新生成：第 ${turnIndex + 1} 轮")
        // placeholderDraft=true 是因为这里没有新的用户气泡，需要一个空草稿行告诉用户
        // 「正在重做」；rebuildMessages=false——成功/失败分支各自 reload 过，
        // 取消分支草稿已清、列表未变，不必再重建一遍
        generation.start(owner, placeholderDraft = true, rebuildMessages = false) {
            // W-46：重新生成也要带历史，但要**排除最后一轮**——那正是要重做的一轮，
            // 把它自己的旧答案当历史会诱导模型复读原答案
            val outcome = runCatching {
                loop.send(userText, emptyList(), buildPriorMessages(owner, excludeLastTurn = true))
            }
            // 流式草稿使命结束：最终回答以整条变体落库，先清草稿避免与整条同时显示
            generation.clearDrafts()
            outcome.onSuccess { history ->
                // 过程事件已实时渲染；但用户中途切走过会话时，期间的 Tool 事件只落盘
                // 不上屏（`isOwnerActive()` 为 false），这里按 id 比对补投影
                history.forEach { msg ->
                    if (msg is ToolMessage) {
                        val shown = messages.value.any { it is UiItem.Tool && it.call.id == msg.id }
                        if (!shown) append(msg)
                    }
                }
                val final = history.filterIsInstance<AssistantMessage>()
                    .lastOrNull()?.content.orEmpty()
                // 功能五（P1-5）：新变体带本回合全部 LLM 往返的合计用量
                val variant = UiItem.Assistant(
                    text = if (final.isBlank()) "（模型未返回内容）" else final,
                    usage = TokenUsage.sum(
                        history.filterIsInstance<AssistantMessage>().map { it.usage }
                    ),
                    // W-RT7：重生成可能换了模型，归属按完成时的当前模型记
                    modelId = currentModelLabel()
                )
                // hotfix30：变体落盘按 owner（切走期间 regenerate 的后台结果仍写回原会话）
                conversationStore.appendVariant(
                    owner, turnIndex, variant.toMsgRecord()
                )
                reloadActiveMessages()
                refreshConversations()
            }
            outcome.onFailure {
                if (it is CancellationException) {
                    // 用户主动停止：不算失败，也不追加空变体（草稿已清）
                    AgentLogger.i("Chat", "重新生成已停止")
                } else {
                    AgentLogger.e("Chat", "重新生成失败", it)
                    val err = UiItem.Assistant(friendlyMessage(it), timeMs = System.currentTimeMillis())
                    // hotfix30：失败变体同样按 owner 落盘
                    conversationStore.appendVariant(
                        owner, turnIndex, err.toMsgRecord()
                    )
                    reloadActiveMessages()
                    refreshConversations()
                }
            }
        }
    }

    /**
     * 左右切换第 [turnIndex] 轮的选中变体（[delta] = -1 左 / +1 右）。
     * 委托 [ConversationStore.selectVariant]（边界 clamp：首个左移/末个右移原地不动）；
     * 切换后重建 [_messages]，保证 UI 与「下一次请求的历史」都用选中变体。运行中忽略。
     */
    override fun switchVariant(turnIndex: Int, delta: Int) {
        if (isRunning.value) return
        conversationStore.selectVariant(_activeConversationId.value, turnIndex, delta)
        reloadActiveMessages()
        refreshConversations()
    }

    /**
     * 从第 [turnIndex] 轮重新发问（UI：用户气泡长按 → 编辑对话框）。
     * 先截断该轮**之后**的所有轮次（[ConversationStore.truncateAfter]，保留该轮），再把新问题
     * 填入输入框走 [send]（与正常发问同一路径，新开一轮）——轮次下标天然连续。运行中忽略。
     */
    override fun reaskFromTurn(turnIndex: Int, text: String) {
        if (isRunning.value) return
        val t = text.trim()
        if (t.isBlank()) return
        conversationStore.truncateAfter(_activeConversationId.value, turnIndex)
        reloadActiveMessages()
        refreshConversations()
        input.value = t
        send()
    }

    /**
     * 功能五（P1-1）：从指定轮起删除（含该轮）——「清理从这里开始的内容」。
     *
     * 为什么不提供「只删这一条」：助手消息可能携带 tool_calls，删单条会让
     * 后续 tool 结果成为孤儿（协议层直接 400）。截断语义既安全，又正好命中
     * 用户真实需求（试错几轮后把尾巴一次性清掉重来）。
     *
     * [turnIndex] = 0 表示清空全部轮次（保留会话壳，标题不动）。
     */
    override fun deleteFromTurn(turnIndex: Int) {
        if (isRunning.value) return
        conversationStore.truncateAfter(_activeConversationId.value, turnIndex - 1)
        reloadActiveMessages()
        refreshConversations()
        AgentLogger.i("Chat", "已从第 ${turnIndex + 1} 轮起删除")
    }

    // —— L10 危险操作审批：Agent 想操控手机必须先过人 ——

    /** 当前待用户确认的危险工具调用；非 null 时 UI 弹出审批 Dialog。 */
    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    override val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    /** 「本次会话始终允许」的授权键，按对话隔离（审计 F3），实现与理由见 [ApprovalWhitelistStore]。 */
    private val sessionAllowed: MutableSet<String> get() = ApprovalWhitelistStore.of(_activeConversationId.value)

    /**
     * W-8 P1-C：ui_screenshot 的本回合批准缓存（见 [requestApproval]）。
     * 每个用户回合（send() 入口）重置——跨回合不继承，每次新任务首次截图仍会问一次。
     */
    @Volatile
    private var screenshotApprovedThisTurn = false

    /** 审批互斥锁：并发工具调用时串行排队，保证同一时刻只有一个审批弹窗。 */
    private val approvalMutex = Mutex()

    /**
     * 向用户征求一次危险操作授权，挂起直到用户决策。
     * 已在本次会话「始终允许」过的工具直接放行，不再打扰用户。
     */
    private suspend fun requestApproval(tool: AgentTool, args: JsonObject): Boolean = approvalMutex.withLock {
        // W-36/R-08：按「同类操作」放行而非工具名；shell 再看命令内容——不可逆命令永不记忆。
        val grant = ApprovalScope.of(tool.name, args)
        // W-RT3：档位判定统一走 ApprovalTier——「逐步确认」连会话白名单也失效；
        // 「全自动」对低风险组（界面操作 / 截图 / 朗读通知 / 文件写入）免弹窗，
        // 高风险组（应用装卸 / GitHub 提交 / shell）维持原判。
        // 注意传的是**归组键**而不是 grant.key：shell 的 key 可能带命令前缀，
        // 拿 key 去比集合永远不等，看着「更安全」，实际是让 AUTO 档对 shell 完全失效、
        // 而对其它组又碰巧对——那种「碰巧对」最难查。
        val mode = settingsFacade.approvalTier.value
        val group = ApprovalGrouping.groupOf(tool.name)
        if (ApprovalTier.shouldSkipDialog(mode, group, ApprovalScope.allows(grant, sessionAllowed))) {
            return@withLock true
        }
        // W-8 P1-C：截图按回合缓存批准（真机日志里「看一眼点一下」被对话框反复打断）；
        // 回合边界在 send() 入口重置 screenshotApprovedThisTurn。
        // 逐步确认档下不用这个缓存：用户点名要「每次都问」，回合缓存会让第二次静默放行。
        if (mode != ApprovalTier.STRICT &&
            grant.key == ApprovalGrouping.GROUP_UI_SCREENSHOT && screenshotApprovedThisTurn
        ) return@withLock true
        val deferred = CompletableDeferred<Boolean>()
        _pendingApproval.value = ApprovalRequest(
            toolName = tool.name,
            argsPreview = args.toString().take(500),
            deferred = deferred,
            grant = grant
        )
        // R-03：超时归 ViewModel（切页卸载弹窗后照样生效），不在 Composable 的倒计时里。
        // finally 保证无论用户怎么关掉弹窗，pending 都会清空，不会残留卡住下一个工具。
        val ok = try { awaitApprovalDecision(deferred) } finally { _pendingApproval.value = null }
        if (ok && grant.key == ApprovalGrouping.GROUP_UI_SCREENSHOT) screenshotApprovedThisTurn = true
        AgentLogger.w("Approval", "${tool.name} ${if (ok) "允许" else "拒绝"}")
        ok
    }

    /** 用户选择「允许一次」：仅放行当前这一次调用。 */
    override fun approveOnce() {
        _pendingApproval.value?.deferred?.complete(true)
    }

    /** 用户选择「本次会话始终允许」：记入会话白名单后放行；不可逆命令（R-08）只放行本次。 */
    override fun approveAlways() {
        val pending = _pendingApproval.value ?: return
        if (pending.grant.memorizable) sessionAllowed += pending.grant.key
        pending.deferred.complete(true)
    }

    /** 用户选择「拒绝」：AgentLoop 会把「用户拒绝了该危险操作」作为工具结果回填。 */
    override fun denyApproval() {
        _pendingApproval.value?.deferred?.complete(false)
    }

    // llmProvider 每次都按最新配置构造，支持运行时切换模型/Key/供应商
    // L10：ui_tap / ui_input / ui_swipe / ui_key / ui_click / ui_screenshot 等危险工具执行前先问用户
    private val approvalHandler: ToolApproval = { tool, args -> requestApproval(tool, args) }

    /**
     * 供应商工厂：openai / claude / gemini 由 ProviderManager 内置注册；
     * local-llama 在此补注册——工厂签名只有 LLMConfig，而本地客户端还需要
     * setting（模型文件/参数）与 modelStore（文件定位），这些是 ViewModel 的常驻成员，
     * 在注册处闭包捕获即可，不必改 ProviderManager 的接口。
     */
    private val providerManager = ProviderManager().apply {
        registerProvider("local-llama") { cfg ->
            // 运行层（Runtime）：包一层生命周期壳——使用心跳 + 在途计数 + 空闲自动卸载。
            // 推理本体零改动；空闲释放后下一轮 ensureModelLoaded 自动重载。
            wrapLocalLlm(LocalLlamaClient(LocalLlamaEngine, localSetting, modelStore, cfg))
        }
    }

    /**
     * 备用 Key 池（W-21）：按当前生效档案分组持久化；运行时冷却状态在内存。
     * 每次取 client 前刷新一次，用户增删备用 Key 后无需重启即生效。
     */
    private val keyPoolStore = KeyPoolStore(app)
    private val keyPoolRuntime = KeyPoolRuntime()

    private val keyPool = KeyPoolFacade(configStore, keyPoolStore, keyPoolRuntime, providerManager)

    /** 备用 Key（设置页增删）；实现见 [KeyPoolFacade]。 */
    override val backupKeys: StateFlow<List<BackupKeyUi>> get() = keyPool.backupKeys

    /** Key 池状态一行摘要（几把可用 / 几把冷却 / 最早多久恢复）。 */
    override val keyPoolSummary: StateFlow<String> get() = keyPool.keyPoolSummary

    /** 转发：buildMainClient 每次取 client 前调用（增删/换档案即时生效）。 */
    private fun refreshKeyPool() = keyPool.refreshKeyPool()

    /** 重新读取备用 Key 并刷新 UI 状态（设置页增删后调用）。 */
    override fun reloadBackupKeys() = keyPool.reloadBackupKeys()

    /** 新增一把备用 Key（空白/已存在则忽略）。 */
    override fun addBackupKey(rawKey: String, label: String) = keyPool.addBackupKey(rawKey, label)

    override fun removeBackupKey(id: String) = keyPool.removeBackupKey(id)

    /**
     * 主模型客户端（W-31 抽出，主循环与子代理共用）。
     *
     * 每次调用现建实例：Key 池、自定义头/体、档案切换都即时生效，
     * 不缓存旧 client（缓存会让「改了 Key 但还用旧的」这种问题复现）。
     */
    private fun buildMainClient(): LLMClient {
        val c = _config.value
        // W-70：把「这一次到底走谁」写进日志。真机排查「明明切了本地却还在跑云端」时，
        // 这是唯一能自证的一行——provider 是不是 local-llama、baseUrl 还残留着哪家。
        AgentLogger.i(
            "Chat",
            "主客户端 provider=${c.providerId.ifBlank { "—" }} " +
                "model=${c.model.ifBlank { "—" }} " +
                "base=${c.baseUrl.ifBlank { "—" }}"
        )
        // W-21：每次取 client 前刷新备用 Key 池（增删/换档案即时生效）
        refreshKeyPool()
        return providerManager.create(
            c.providerId,
            LLMConfig(
                baseUrl = c.baseUrl,
                apiKey = c.apiKey,
                model = c.model,
                customHeaders = c.customHeaders,
                customBody = parseCustomBody(c.customBody)
            )
        )
    }

    /**
     * W-102：粘性激活的工具组（LRU 序：最久未用在前）。
     *
     * 进程内唯一实例，由 loop 的元工具 `__use_tools` 就地修改；每次回合收尾
     * 经 [persistToolGroups] 落盘，杀进程后还能带回来。
     *
     * 为什么不放 loop 内部：loop 是「一次请求的执行器」，而「这次任务开过哪些组」
     * 要跨多次 send 保留——同一会话里第二次「帮我截图」不该再开一遍 ui 组。
     *
     * 作用域是**一次任务**，不是永久：新建会话时清空（见 [newConversation]）。
     * 上个任务开的 ui/shell 继续挂在每轮请求里，正是「不需要它的时候它还在付钱」。
     *
     * ⚠️ **必须声明在 [loop] 之前**。Kotlin 按声明顺序初始化属性，而 `RealAgentLoop` 的
     * `activeGroups` 是可空参数（null = 不粘性）。一旦把这段挪到 loop 后面，loop 构造时
     * 拿到的是 null，粘性**静默失效**——不报错、不崩溃，只是每次都退回「每回合新建」，
     * 而日志里看不出任何异常。改这段位置前先跑 `LocalModelVerification` 用例 8。
     */
    private val activeToolGroups: MutableSet<String> = ToolActivationStore.load(app)

    /** 把当前激活组落盘（失败只记日志，不打断收尾——见 ToolActivationStore 的失败模式）。 */
    private fun persistToolGroups() {
        // 快照后落盘：集合可能正被 agent 线程改（每轮 plan 会 seed/pin/淘汰），
        // 直接传进去会在 joinToString 里撞 ConcurrentModificationException。
        // 锁对象就是集合本身，与 KernelExposure.plan 用同一把（见那里的 KDoc）。
        val snapshot = synchronized(activeToolGroups) { activeToolGroups.toList() }
        ToolActivationStore.save(app, snapshot)
        refreshToolGroups()
    }

    private val _toolGroupsUi = MutableStateFlow<List<ToolGroupRow>>(emptyList())

    /**
     * 设置页要展示的组清单（已启用在前，同状态内按工具数降序）。
     *
     * 为什么是 StateFlow 而不是「进页面时算一次」：关掉一个组之后同屏的其它行要立刻
     * 重新排序/置灰，靠 `remember` 算一次会停在旧快照上，用户点第二下才发现已经关了。
     */
    override val toolGroupsUi: StateFlow<List<ToolGroupRow>> = _toolGroupsUi.asStateFlow()

    /**
     * 重算 [toolGroupsUi]。
     *
     * 调用点：设置页进入时（`LaunchedEffect`）、每次落盘激活组之后、以及用户手动关闭后。
     * 不做成「响应式监听注册表」：MCP 工具是异步注册的，真要做到实时得给 registry 加
     * 观察者，而这块 UI 的用途只是「看一眼、关掉一个」，下一次进页面刷新足够。
     */
    override fun refreshToolGroups() {
        val counts = KernelExposure.groupsOf(registry.list()).toMap()
        val snapshot = synchronized(activeToolGroups) { activeToolGroups.toSet() }
        _toolGroupsUi.value = counts.entries
            // 排序：常驻 → 其它已启用 → 未启用；同档按工具数从多到少。
            // 「常驻」必须排在「已启用」之前：file 永远是启用态，若只按启用与否排，
            // 用户勾上一个 9 个工具的组，那一行就会插到 file 上方，看起来像 file 掉了。
            .sortedWith(
                compareBy(
                    { it.key !in snapshot },
                    { !KernelExposure.isPinned(it.key) },
                    { -it.value },
                    { it.key }
                )
            )
            .map { (g, n) ->
                ToolGroupRow(
                    name = g,
                    note = KernelExposure.noteOf(g),
                    count = n,
                    active = g in snapshot,
                    pinned = KernelExposure.isPinned(g)
                )
            }
    }

    /**
     * 手动关掉一个工具组（W-103）——补上「粘性激活」缺的另一半。
     *
     * 之前只有「新建会话」能清空：用户在一个长会话里用完 `shell` 就再也关不掉，
     * 只能等下次新建会话。而 `shell` 组 4 个工具 2676 字符，每轮都在付。
     *
     * 常驻组（`file` / `misc`）直接忽略：它们在 [KernelExposure.plan] 里会被强制加回，
     * 关掉是假动作，UI 也不给入口（见 [ToolGroupRow.pinned]）。
     *
     * **不需要通知 loop**：组集合是同一个实例，下一轮 `plan()` 直接用新状态。
     */
    override fun dropToolGroup(group: String) {
        if (KernelExposure.isPinned(group)) return
        // 与 agent 线程共用同一把锁（集合本身）：关组时那一轮可能正在 plan 里遍历它。
        val removed = synchronized(activeToolGroups) { activeToolGroups.remove(group) }
        if (!removed) return
        AgentLogger.i(
            "ToolExposure",
            "用户手动关闭工具组 $group，剩余=" +
                synchronized(activeToolGroups) { activeToolGroups.joinToString(",") }
        )
        persistToolGroups()
    }

    /**
     * 手动开一个工具组（用户误关了想恢复）。
     *
     * 和 [dropToolGroup] 共用同一把锁；顺序语义与 `__use_tools` 一致：先 remove 再 add，
     * 让这组排到 LRU 末尾（刚用过）。超上限时 [KernelExposure.enforceCap] 会挤掉最久未用的，
     * UI 下一帧就会看到那一行变灰——不需要额外弹窗解释。
     */
    override fun enableToolGroup(group: String) {
        // 判存在要用 `in`，不能用 `!remove(...)`：
        // remove 返回 true 表示「原本就在、已经被我拿掉了」——依据它分支会把
        // 「不存在」当成「已在」，然后**一个组也加不进去**（设置页勾选框点了没反应）。
        val (changed, effective, remaining) = synchronized(activeToolGroups) {
            if (group in activeToolGroups) {
                // 已经开着：不改变 LRU 顺序，避免「点一下已勾选的框」把它顶成最新
                Triple(false, true, activeToolGroups.joinToString(","))
            } else {
                // remove + add 与 `__use_tools` 同形：让这组排到 LRU 末尾（刚用过）。
                // 本来就不在里面，remove 是空操作，保留只为两条路径写法一致。
                activeToolGroups.remove(group)
                activeToolGroups.add(group)
                KernelExposure.enforceCap(activeToolGroups)
                // 回执写**实际结果**：容量满时本次加入可能立刻被滞回淘汰掉。
                Triple(true, group in activeToolGroups, activeToolGroups.joinToString(","))
            }
        }
        if (!changed) return
        AgentLogger.i(
            "ToolExposure",
            "用户手动开启工具组 $group（" + (if (effective) "已生效" else "被上限挤掉") + "），当前=$remaining"
        )
        persistToolGroups()
    }

    /**
     * 清空粘性激活（新会话时调用）。
     *
     * 不清会怎样：用户做完一次屏幕任务，新开一个纯聊天会话，ui 组的 9 个工具依旧
     * 每轮随请求发出——约 4500 字符，一条都用不到。这正是「不需要它的时候它还在」。
     *
     * 清空不等于「一个工具都不给」：下一次 `plan()` 会自动重新种上 file 组与元工具，
     * 等于回到刚装好的状态。
     */
    private fun resetToolGroups() {
        // 同上：清空也是一次写，必须和 agent 线程的遍历互斥。
        val hadAny = synchronized(activeToolGroups) {
            if (activeToolGroups.isEmpty()) {
                false
            } else {
                activeToolGroups.clear()
                true
            }
        }
        if (!hadAny) return
        persistToolGroups()
    }

    private val loop = RealAgentLoop(
        llmProvider = { buildMainClient() },
        registry = registry,
        approval = approvalHandler,
        // R1：回合日志——崩溃恢复的唯一依据。原来 checkpoint 恒为 null（四个断点全是空调用），
        // 进程在「工具已执行、结果未落盘」时被杀，重进会重放写操作。
        journal = turnJournal,
        // L9：每轮运行都把当前记忆摘要注入系统提示
        memoryProvider = { memoryStore.summaryForPrompt(12, 1200) },
        // Hotfix2：工具调用过程实时上屏（执行中卡 → 终态卡），
        // 修掉「AI 干完什么都在黑盒里、跑完才甩结果」的体验缺陷
        onEvent = ::handleAgentEvent,
        // R5+P5-1：回合事件 → 文本行（给人看，定位未配对 end 的 tool.start）+ NDJSON 流水（给脚本算）
        observer = TurnObservers.production(),
        // Phase 2 DoD②：T01 流式文本增量 → 打字机草稿气泡（重新生成的新变体流式填入）
        onTextDelta = ::handleTextDelta,
        // 功能五（P1-9）：推理过程增量 → handleReasoningDelta（与正文分流，UI 折叠块）
        onReasoningDelta = ::handleReasoningDelta,
        // 真机第三轮 Bug A2：退避期间监听网络恢复——关掉飞行模式后「秒续」而不是干等满额
        networkRecovery = ConnectivityNetworkRecoveryWaiter(app),
        // R10：重试档位跟着当前供应商走。lambda 而不是构造时取值——
        // 用户在模型页切换供应商不必重启 App，下一次调用就该用新档位。
        retryProfileProvider = { ProviderCatalog.retryOf(_config.value.providerId) },
        // W-102：粘性激活——同一会话第二次问「帮我截图」不必再开一遍 ui 组
        activeGroups = activeToolGroups
    )

    /**
     * T01 流式文本增量 → 草稿缓冲（在 [GenerationRunController] 内，[streamDraft] 是其投影）。
     * TextDelta 不进历史，仅用于「正在生成」的实时气泡；[send] / [regenerate]
     * 结束时清空并落库最终整条。缓冲自带锁（与 [msgLock] 解耦，锁序 msgLock→草稿锁）。
     * owner!=active（用户切到别的会话）时缓冲继续攒，切回时 [syncDraftDisplay] 恢复。
     */
    private fun handleTextDelta(delta: String) {
        // hotfix30：先攒进内存缓冲（权威态），仅 owner==active 才投影到显示态
        generation.appendText(delta)
    }

    /**
     * 功能五（P1-9）：推理过程增量 → [reasoningDraft]（思考折叠块的实时内容）。
     * 与 [handleTextDelta] 同一把锁串行化：两条流来自同一 LLM 收集协程、
     * 同一 UI 气泡消费，锁共享保证「思考」与「正文」的相对时序不乱。
     */
    private fun handleReasoningDelta(delta: String) {
        // hotfix30：同 handleTextDelta——缓冲权威 + 条件投影
        generation.appendReasoning(delta)
    }

    /**
     * 功能五（P1-5）：当前会话累计 token 用量（对所有助手气泡的 usage 求和）。
     * 切会话时随 [_messages] 重载自动切换；UI 在对话页顶部常驻显示。
     */
    override val sessionUsage: StateFlow<TokenUsage> = messages
        .map { items ->
            TokenUsage.sum(items.filterIsInstance<UiItem.Assistant>().mapNotNull { it.usage })
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TokenUsage.EMPTY)

    /**
     * 供应商瞬时失败的「自动恢复」进行态（Phase 2 DoD③）：非 null 时 UI 显示
     * 「正在恢复（n/N）· 原因」横幅。由 [handleAgentEvent] 的 RetryStarted/RetryFinished
     * 驱动置位/清除；[send] / [regenerate] / [stop] 收尾处再兜底清空，杜绝残留。
     */
    private val _retryState = MutableStateFlow<RetryState?>(null)
    override val retryState: StateFlow<RetryState?> = _retryState.asStateFlow()

    /** 本轮回答因输出上限被截断（非 null 时 UI 显示提示条）。不落盘；每次开始生成时清空。 */
    private val _truncationNotice = MutableStateFlow<String?>(null)
    override val truncationNotice: StateFlow<String?> = _truncationNotice.asStateFlow()

    /** 「知道了」→ 收起截断提示（下一条消息发出前不再打扰）。 */
    override fun dismissTruncationNotice() { _truncationNotice.value = null }

    /**
     * 本地历史超过 [ConversationStore.MAX_TURNS] 被挤出工作集时给一次可见提示。
     *
     * 复用截断 banner（用户体验上是同一件事：屏幕上的对话变短了），但文案必须与
     * 「回答达到输出上限」区分开——一个是我们的工作集装不下、早期的移走了，
     * 一个是模型没写完。
     *
     * W-RT9 起措辞从「已删除」改成「已转入归档」：数据不再真丢，说「删除」是错的，
     * 会让用户白心疼一遍。但仍要提示——工作集确实变短了，不吭声才更可疑。
     *
     * 已有提示时不覆盖：同一会话连续追加会连着触发，覆盖只会让文案闪。
     */
    private fun noticeHistoryTruncated(dropped: Int) {
        // 日志留在这里而不是 ConversationStore：那个类被 harness 以「纯 JVM 自编译」
        // 的方式测（逐个列源文件），引日志设施会破坏「零依赖可跑」的前提。
        AgentLogger.w(
            "ChatViewModel",
            "会话历史超过 ${ConversationStore.MAX_TURNS} 轮，最早的 $dropped 轮已转入归档"
        )
        if (_truncationNotice.value == null) {
            _truncationNotice.value =
                "⚠️ 本会话已超过 ${ConversationStore.MAX_TURNS} 轮，最早的 $dropped 轮已转入归档" +
                    "（导出 Markdown 时仍会完整包含）"
        }
    }

    /** 过程事件 → UI 实时更新（Hotfix2）。工具并行执行时多线程回调，读写列表加锁防丢更新。
     * hotfix30：_messages 是 active 会话的显示态——owner!=active（用户切到别的会话）
     * 时只落盘（appendToStore 按 owner）不上屏，切回时 loadConversation 从 store 重建。 */
    private fun handleAgentEvent(ev: AgentEvent) = synchronized(msgLock) {
        when (ev) {
            is AgentEvent.ToolStarted -> {
                val item = UiItem.Tool(
                    call = ev.call,
                    result = "",
                    isError = false,
                    running = true
                )
                // hotfix30：显示态只在 owner==active 时更新（切走期间只落盘）
                if (isOwnerActive()) appendUiItem(item)
                // 功能三：执行中态先落一条占位（终态到达后按 call.id 覆盖，见 ConversationStore.appendMessage）
                appendToStore(item)
            }

            is AgentEvent.ToolFinished -> {
                val done = UiItem.Tool(
                    call = ToolCall(ev.message.id, ev.message.name, ev.message.arguments),
                    result = ev.message.content,
                    isError = ev.message.isError,
                    images = ev.message.images,
                    running = false
                )
                // R-06：显示态命中才替换；落库**无条件**（store 按 call.id upsert）。
                // 旧写法把两件事绑在一个 if 上：切走后终态既不替换也不落库，占位永久留在文件里。
                projection.finishTool(ev.message.id, done)
                appendToStore(done)
                // C2：推进运行态——记下「进行到第几步 + 做过哪些工具」。
                // B 批只把 stepIndex 初始化成 0 就再没动过 → 中断提示永远「第 0 步」、doneTools 永远空。
                advanceRunState(ev.message.name)
                // finishTool 返回 false（用户切走了）= 只落库不上屏；切回时从 store 重建
            }

            // Phase 2 DoD③：准备重试 → 置位「正在恢复」横幅（U 显示 n/N + 原因）
            is AgentEvent.RetryStarted -> {
                _retryState.value = RetryState(ev.attempt, ev.max, ev.reason)
                // W-49：重试是**整次重新请求**（见 RealAgentLoop.callWithRetry 的重试粒度），
                // 上一次已经通过 onTextDelta 推上屏的增量，重试后会从头发一遍。不清缓冲就会
                // 出现「前半段 → 重试后整段再来一次」的重复观感；而 429 退避是 10/30/60 秒，
                // 用户这时早就把前半段读完了，重复比丢内容更刺眼。
                // 锁序：msgLock → 草稿锁（反向不存在，handleTextDelta 不取 msgLock）。
                generation.clearDrafts()
            }

            // Phase 2 DoD③：重试收尾（成功 / 最终失败 / 取消）→ 清除横幅
            is AgentEvent.RetryFinished -> {
                _retryState.value = null
            }

            // 输出撞到长度上限：界面上「说到一半」与「说完了」长得一模一样，得明说一句
            is AgentEvent.OutputTruncated ->
                _truncationNotice.value = "⚠️ 回答达到输出上限，内容可能不完整——回复「继续」可接着写"
        }
    }

    /**
     * 更新模型配置：语义不变——改的是「当前生效档案」的 config（功能二）。
     * 先过 [ConfigSanitizer] 清洗（剔除不可见字符/规范化 URL），再落盘 + 更新内存状态流，
     * 保证「保存即生效、重启不丢失」。
     */
    override fun updateConfig(cfg: ModelConfig) {
        val clean = ConfigSanitizer.sanitized(cfg)
        val id = _activeProfileId.value
        val updated = _profiles.value.map { if (it.id == id) it.copy(config = clean) else it }
        _profiles.value = updated
        _config.value = clean
        // saveProfiles 内部同步镜像旧四键（媒体 Key 回退链不断，见 ConfigStore）
        configStore.saveProfiles(updated, id)
    }

    /**
     * 新增一个配置档案（功能二；配置页切换条「＋」→「新建模型配置」对话框确认后调用）。
     * 空白起步、字段自填、对标参照 App：API Key / Base URL / 模型名三个文本字段全部置空，
     * 由用户从头填写，不再继承复制当前生效配置的值；providerId 保留当前供应商
     * （它是下拉选择器而非「已填数据」，配置页的智能预填与默认值提示会引导用户补全）。
     * 命名并立即切换过去（编辑器同步显示空白配置）。
     *
     * @param name 用户在对话框里起的档案名；null / 空白回退自动命名「配置 N」
     *   （用户清空输入框时也走这条——绝不创建空白名档案）。
     * @return 新档案 id。
     */
    override fun addProfile(name: String?): String {
        // hotfix36：新建档案**不再继承当前供应商**——真机反馈「我创建新模型的时候，
        // 他自动变成那个 deepseek 了」（当时正在用 DeepSeek，新建档案就沿用了它，
        // 用户以为被锁死在 DeepSeek）。新建的语义是「从零配一家」，
        // 中性默认 = OpenAI 兼容（占市面绝大多数，自建/中转也走它），
        // 用户在「供应商」区点一下即可换成任意一家（含 30+ 家预设目录）。
        val blank = ModelConfig(
            baseUrl = "",
            apiKey = "",
            model = "",
            providerId = ProviderCatalog.STYLE_OPENAI
        )
        val id = UUID.randomUUID().toString()
        val trimmed = name?.trim()
        val profile = ModelProfile(
            id,
            if (trimmed.isNullOrBlank()) "配置 ${_profiles.value.size + 1}" else trimmed,
            blank
        )
        val updated = _profiles.value + profile
        _profiles.value = updated
        _activeProfileId.value = id
        _config.value = blank
        configStore.saveProfiles(updated, id)
        return id
    }

    /**
     * 切换生效档案（配置页切换条点击）。
     * 运行中切换对下一轮请求生效——[loop] 的 llmProvider lambda 每轮读 _config.value，天然支持。
     */
    override fun switchProfile(id: String) {
        val target = _profiles.value.firstOrNull { it.id == id } ?: return
        _activeProfileId.value = id
        _config.value = target.config
        configStore.saveProfiles(_profiles.value, id)
    }

    /** 重命名档案（空名忽略，防用户清空文本框后按确定）。 */
    override fun renameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val updated = _profiles.value.map { if (it.id == id) it.copy(name = trimmed) else it }
        _profiles.value = updated
        configStore.saveProfiles(updated, _activeProfileId.value)
    }

    /**
     * 删除档案；只剩 1 个时拒绝（至少保留一个，否则当前生效配置无处安放）。
     * 删的是当前生效档案则切换到剩下第一个。
     */
    override fun deleteProfile(id: String) {
        if (_profiles.value.size <= 1) return
        val updated = _profiles.value.filter { it.id != id }
        var activeId = _activeProfileId.value
        if (activeId == id) {
            activeId = updated.first().id
            _config.value = updated.first().config
        }
        _activeProfileId.value = activeId
        _profiles.value = updated
        configStore.saveProfiles(updated, activeId)
    }

    /** 由当前保存的 Key 生成「len=35 head=sk-a tail=d38d」指纹，供 401 气泡与测试卡片共用。 */
    private fun keyFingerprint(apiKey: String): String {
        val insp = ConfigSanitizer.inspectKey(apiKey)
        return "len=${insp.length} head=${insp.head} tail=${insp.tail}"
    }

    /**
     * 按供应商拼「测试连接」卡片展示的端点——与各 Provider 实现实际请求路径一致
     * （openai: /chat/completions；claude: /v1/messages；gemini: /v1beta/models/{m}:generateContent；
     * local-llama: 本地文件，展示「本地推理 + 文件名」）。
     */
    private fun endpointFor(cfg: ModelConfig): String {
        if (cfg.providerId == "local-llama") {
            val name = localSetting.activeModel.ifBlank { "未选择模型" }
            return "本地推理 · llama.cpp · $name"
        }
        val base = ConfigSanitizer.normalizeBaseUrl(cfg.baseUrl).trimEnd('/')
        return when (cfg.providerId) {
            "claude" -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            "gemini" -> "$base/v1beta/models/${ConfigSanitizer.cleanModel(cfg.model)}:generateContent"
            else -> "$base/chat/completions"
        }
    }

    /**
     * 「测试连接」：用配置页当前输入框的值（先实时清洗）发起一次最小请求，
     * 把连通性结果写入 [_testResult] 供卡片渲染。网络 IO 全部走 Dispatchers.IO，不阻塞主线程。
     *
     * 最小请求体：`messages=[{role:user,content:"ping"}]`、`max_tokens=1`、不带 tools，
     * 既不污染对话历史，也几乎不产生 token 消耗。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值）。
     * @param apiKey 输入框当前的 API Key（原始值）。
     * @param model 输入框当前的模型名（原始值）。
     */
    /**
     * 「测试连接」：用配置页当前输入框的值（先实时清洗）发起一次最小请求，
     * 把连通性结果写入 [_testResult] 供卡片渲染。网络 IO 全部走 Dispatchers.IO，不阻塞主线程。
     *
     * 最小请求体按供应商各自协议构造（Provider 实现内聚），
     * 既不污染对话历史，也几乎不产生 token 消耗。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值）。
     * @param apiKey 输入框当前的 API Key（原始值）。
     * @param model 输入框当前的模型名（原始值）。
     * @param providerId 当前选中的供应商（"openai" / "claude" / "gemini"）。
     */
    override fun testConnection(baseUrl: String, apiKey: String, model: String, providerId: String) {
        if (_testing.value) return
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            val cfg = ConfigSanitizer.sanitized(baseUrl, apiKey, model, providerId)
            val endpoint = endpointFor(cfg)
            // 本地模式没有 Key：指纹位展示模型文件名，比 "len=0" 更有信息量
            val finger = if (cfg.providerId == "local-llama") {
                "模型文件=${localSetting.activeModel.ifBlank { "未选择" }}"
            } else {
                keyFingerprint(cfg.apiKey)
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    providerManager
                        .create(cfg.providerId, LLMConfig(cfg.baseUrl, cfg.apiKey, cfg.model))
                        .testPing()
                }
            }
            _testResult.value = when {
                outcome.isFailure -> {
                    val t = outcome.exceptionOrNull()!!
                    // 异常文案离开日志系统前先脱敏（防御：异常里可能夹带 url 中的 key 片段）
                    val msg = (t.message?.let { AgentLogger.sanitize(it) } ?: "未知错误").take(200)
                    // P1：本地模型加载失败不是网络问题，单独归因（沿用原有排查提示）
                    val isLocalModel = providerId == "local-llama"
                    val diagnosis = if (isLocalModel) {
                        Diagnosis(
                            FailureKind.MODEL,
                            "本地推理失败：确认模型文件已下载完整、设备为 arm64；" +
                                "加载日志可在「日志」页查看（标签 llama-jni）",
                            RecoveryAction.NONE
                        )
                    } else {
                        diagnoseException(t)
                    }
                    TestResult(
                        ok = false,
                        code = -1,
                        endpoint = endpoint,
                        fingerprint = finger,
                        bodyPreview = msg,
                        suggestion = diagnosis.suggestion,
                        kind = diagnosis.kind,
                        action = diagnosis.action
                    )
                }

                else -> {
                    val ping = outcome.getOrThrow()
                    if (ping.code in 200..299) {
                        TestResult(
                            ok = true,
                            code = ping.code,
                            endpoint = endpoint,
                            fingerprint = finger,
                            bodyPreview = ping.bodyPreview
                        )
                    } else {
                        // P1：按「谁的问题 + 下一步点哪」归因（401/402→Key，429→限流，
                        // 404/400(model)→模型名，4xx→端点，5xx→服务端），不再一律「客户端错误」。
                        val diagnosis = diagnoseHttpFailure(ping.code, ping.bodyPreview)
                        TestResult(
                            ok = false,
                            code = ping.code,
                            endpoint = endpoint,
                            fingerprint = finger,
                            bodyPreview = ping.bodyPreview,
                            suggestion = diagnosis.suggestion,
                            kind = diagnosis.kind,
                            action = diagnosis.action
                        )
                    }
                }
            }
            _testing.value = false
        }
    }

    // —— 模型列表拉取（模型配置页「选择模型」弹窗）—— 3.1 拆出 ModelListFacade ——

    /**
     * 列表拉取域整块搬进 [ModelListFacade]：它只依赖 configStore（收藏落盘）与一个
     * 协程作用域，与生成链零交集。这里保留 override 转发，契约一行没动。
     */
    private val modelListFacade = ModelListFacade(configStore, viewModelScope)

    /** 「选择模型」弹窗的模型 id 列表；emptyList = 未拉取或拉到空。 */
    override val modelList: StateFlow<List<String>> get() = modelListFacade.modelList

    /** 收藏的模型名（收藏区恒在列表最前，跨端点共用）。 */
    override val favoriteModels: StateFlow<List<String>> get() = modelListFacade.favoriteModels

    /** 是否正在拉取模型列表：UI 显示 loading，并作并发守卫。 */
    override val modelListLoading: StateFlow<Boolean> get() = modelListFacade.modelListLoading

    /** 拉取失败文案（已脱敏后截断）；null = 无错误。 */
    override val modelListError: StateFlow<String?> get() = modelListFacade.modelListError

    /** 收藏 / 取消收藏（即时落盘，重启不丢）。 */
    override fun toggleFavoriteModel(name: String) = modelListFacade.toggleFavoriteModel(name)

    /** 拉取模型列表（并发守卫 + Dispatchers.IO + 失败脱敏，见 facade）。 */
    override fun fetchModelList(baseUrl: String, apiKey: String, providerId: String) =
        modelListFacade.fetchModelList(baseUrl, apiKey, providerId)

    /** 清空模型列表三状态（弹窗关闭时调用：cancel 在途任务 + 三态归零）。 */
    override fun clearModelList() = modelListFacade.clearModelList()

    // —— L8 运行日志（日记）：出错可定位 ——

    // —— L8/L9 转发：实现见 LogsFacade / MemoryFacade（3.1 第二批拆出）——

    /** 拉取最近 200 条日志文本（最新的在前）刷新 UI。 */
    override fun refreshLogs() = logsFacade.refreshLogs()

    /** 清空日志（内存缓冲 + 当日文件），随后刷新列表。 */
    override fun clearLogs() = logsFacade.clearLogs()

    /** 导出日志全文，供用户复制到剪贴板排查问题。 */
    override fun exportLogs(): String = logsFacade.exportLogs()

    /** 当前日志文件绝对路径（卡片底部展示，方便 adb pull）。 */
    override fun logFilePath(): String = logsFacade.logFilePath()

    /** 刷新记忆列表（最近 50 条，按更新时间倒序）。 */
    override fun refreshMemories() = memoryFacade.refreshMemories()

    /** 手动新增一条记忆（UI 直接录入，默认 note / 重要度 5）。 */
    override fun addMemory(content: String, kind: String, tags: List<String>) =
        memoryFacade.addMemory(content, kind, tags)

    /** 关键词检索记忆；结果直接替换列表，便于查看命中情况。 */
    override fun searchMemory(query: String) = memoryFacade.searchMemory(query)

    /** 遗忘指定记忆并重刷新列表。 */
    override fun forgetMemory(id: String) = memoryFacade.forgetMemory(id)

    /** 编辑一条记忆；失败返回 false（对话框保持打开），见 facade。 */
    override fun updateMemory(id: String, content: String, salience: Int, tags: List<String>): Boolean =
        memoryFacade.updateMemory(id, content, salience, tags)

    /** 清空全部记忆（UI 需二次确认）并重刷新列表。 */
    override fun clearMemory() = memoryFacade.clearMemory()

    /** 复用 importStatus 展示一次性提示（如“已复制到剪贴板”）。 */
    fun setImportStatus(msg: String) { _importStatus.value = msg }

    /**
     * 功能四（P0-2）：等待发送的图片（data URL 列表）。
     *
     * 选图在 UI 线程拿 Uri，转 base64 是重活——放 IO 线程由 [attachImageFromUri] 处理，
     * 完成后统一落到这里，发送时一次性带走。UI 只读这个流渲染缩略图，不碰编码细节。
     */
    private val _pendingImages = MutableStateFlow<List<String>>(emptyList())
    override val pendingImages: StateFlow<List<String>> = _pendingImages.asStateFlow()

    /**
     * 功能四（P0-2）：把相册选中的图片读成 data URL 挂到待发列表。
     *
     * 压缩到长边 ≤ [MAX_EDGE]、JPEG 质量 80——1080×2214 原始截图 base64 约好几 MB，
     * 直接塞进请求会超上下文且慢。失败（权限撤销/文件被删）返回 false，由调用方提示。
     */
    override suspend fun attachImageFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // 审计 F27：待发图片此前无数量上限。每张 base64 约 200~400KB，
            // 连选 30 张就是 10MB 常驻 StateFlow，且整个拼进请求体——供应商多半直接拒。
            if (_pendingImages.value.size >= AttachmentIo.MAX_PENDING_IMAGES) return@runCatching false
            // 审计 F26：修前是 decodeStream 直接解原图。一张 108MP 的照片是
            // 12000×9000×4B ≈ 432MB 的位图，decode 当场 OOM——scaleDown 根本没机会执行
            // （它注释里写的「两阶段解码在调用方已完成」其实并不存在）。
            val bitmap = AttachmentIo.decodeSampled(app.contentResolver, uri, MAX_EDGE) ?: return@runCatching false
            val scaled = AttachmentIo.scaleDown(bitmap, MAX_EDGE)
            val bytes = try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    out.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            _pendingImages.value = _pendingImages.value + "data:image/jpeg;base64,$encoded"
            true
        }.getOrDefault(false)
    }


    /** 功能四（P0-2）：附件图片长边上限——兼顾视觉模型识别效果与请求体积。 */
    private companion object {
        const val MAX_EDGE = 1024

        /**
         * W-46：喂给模型的历史最多回看多少轮。
         *
         * 只是**构造阶段的粗暴上限**，真正的体积控制交给 `ContextBudget`（按 token 预算整段丢）。
         * 20 轮 ≈ 40 条消息，覆盖住常见追问场景，又不会让摊平本身变慢。
         */
        const val PRIOR_TURNS_MAX = 20

        /** W-8 P1-C：截图工具名——本回合批准过一次后后续截图免弹窗（见 [requestApproval]）。 */
        const val SCREENSHOT_TOOL = "ui_screenshot"

        /** 功能五（P1-4）：PDF 最多渲染页数（首页几页通常已够模型读懂主旨）。 */
        const val MAX_PDF_PAGES = 5

        /** 功能五（P1-4）：单个文本附件最多取用的字符数（防超长文件撑爆上下文）。 */
        const val MAX_FILE_CHARS = 60_000

        /** 功能五（P1-4）：按扩展名识别的文本类文件（直读文本路径）。 */
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "html", "htm",
            "yaml", "yml", "ini", "conf", "cfg", "properties", "toml", "sql",
            "kt", "java", "py", "js", "ts", "sh", "gradle", "c", "cpp", "h", "rs", "go",
            "rb", "php", "bat"
        )
    }


    /** 功能四（P0-2）：移除待发图片。 */
    override fun removePendingImage(url: String) {
        _pendingImages.value = _pendingImages.value.filter { it != url }
    }

    /**
     * 功能五（P1-4）：待发**文件**——「＋」里除了图片还能发文档。
     *
     * 三种处理路径（不新增任何依赖，全走系统 API）：
     *  ① 文本类（txt/md/json/csv/代码…）→ 直读文本，发送时作为附件块拼进消息；
     *  ② PDF → `PdfRenderer` 渲染前 ≤[MAX_PDF_PAGES] 页为图片，并入图片链路（视觉得看）；
     *  ③ DOCX → 解 zip 抽 `word/document.xml` 里的 `<w:t>` 文本（纯系统 zip + 正则）。
     * 其它二进制格式明确拒绝并给出可读原因——**不做「静默忽略」**，
     * 用户点了「＋」却什么都没发生是最差的反馈。
     */
    private val _pendingFiles = MutableStateFlow<List<PendingFile>>(emptyList())
    override val pendingFiles: StateFlow<List<PendingFile>> = _pendingFiles.asStateFlow()

    /** 功能五（P1-4）：读取文件并按其类型挂到待发清单；返回精确结果供 UI 反馈。 */
    override suspend fun attachFileFromUri(uri: Uri): FileAttachResult = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri) ?: "未命名文件"
            val ext = name.substringAfterLast('.', "").lowercase()
            when {
                // ② PDF：渲染成图片走多模态（视觉模型读页面）
                ext == "pdf" -> {
                    // 审计 F27：PDF 一次产出最多 5 张，必须与已有待发图共用同一个额度，
                    // 否则「先选 8 张图，再发一个 5 页 PDF」就能绕过上限。
                    val room = (AttachmentIo.MAX_PENDING_IMAGES - _pendingImages.value.size).coerceAtLeast(0)
                    if (room == 0) {
                        FileAttachResult.Failed("最多同时发 $AttachmentIo.MAX_PENDING_IMAGES 张图片，请先移除几张")
                    } else {
                        val images = AttachmentIo.renderPdf(app.contentResolver, uri, minOf(MAX_PDF_PAGES, room), MAX_EDGE)
                        if (images.isEmpty()) {
                            FileAttachResult.Failed("PDF 打开失败或没有可渲染页面")
                        } else {
                            _pendingImages.value = _pendingImages.value + images
                            FileAttachResult.Images(name, images.size)
                        }
                    }
                }

                // ③ DOCX：解包抽文本
                ext == "docx" -> {
                    val text = AttachmentIo.readDocx(app.contentResolver, uri)
                    if (text.isBlank()) {
                        FileAttachResult.Failed("DOCX 里没有提取到文字（可能是纯图片文档）")
                    } else {
                        _pendingFiles.value = _pendingFiles.value + PendingFile(name, text.take(MAX_FILE_CHARS))
                        FileAttachResult.Text(name, text.length)
                    }
                }

                // ① 文本类：直读
                ext in TEXT_EXTENSIONS -> {
                    val text = AttachmentIo.readText(app.contentResolver, uri)?.take(MAX_FILE_CHARS)
                    if (text.isNullOrBlank()) {
                        FileAttachResult.Failed("文件为空或无法读取")
                    } else {
                        _pendingFiles.value = _pendingFiles.value + PendingFile(name, text)
                        FileAttachResult.Text(name, text.length)
                    }
                }

                // 其它：明确拒绝（含建议），不静默吞掉
                else -> FileAttachResult.Failed(
                    "暂不支持 .$ext 文件；当前可发：图片、PDF、DOCX、" +
                        "以及 txt/md/json/csv 等文本类文件"
                )
            }
        }.getOrElse { FileAttachResult.Failed("读取失败：${it.message ?: "未知错误"}") }
    }

    /** 功能五（P1-4）：移除待发文件。 */
    override fun removePendingFile(name: String) {
        _pendingFiles.value = _pendingFiles.value.filterNot { it.name == name }
    }

    /** ContentResolver 查显示名（拿不到就回落 null，由调用方兜底命名）。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()





    /**
     * W-46：把已发生的轮次摊平成「本回合之前的历史」，交给 AgentLoop 一起发。
     *
     * 为什么必须补这一块：loop 每回合只造「系统提示 + 当前输入」两条消息，会话历史虽然
     * 完整落盘，却从未进入请求——模型在多点追问里没有任何指代对象（「它」「继续」
     * 「第二个方案」一律失忆）。这里按轮次取 `user.content` + **当前选中变体**的
     * assistant 正文，按时间正序交给 loop，体积由 `ContextBudget` 按 token 预算裁剪。
     *
     * 只取正文、**不带工具 trace**：把历史 `tool` 消息塞回去必须与对应
     * `assistant.tool_calls` 严格配对，跨回合重建极易让严格供应商直接 400；而工具过程
     * 属于上一回合的执行细节，对「刚才说的那件事」的指代没有帮助。
     *
     * @param convId 目标会话（null / 不存在 → 空历史，等价旧行为）
     * @param excludeLastTurn 重新生成时用：最后一轮正要重做，不能把它自己的旧答案当历史
     */
    private fun buildPriorMessages(
        convId: String?,
        excludeLastTurn: Boolean = false
    ): List<ChatMessage> {
        val rec = convId?.let { runCatching { conversationStore.load(it) }.getOrNull() }
            ?: return emptyList()
        val turns = if (excludeLastTurn) rec.turns.dropLast(1) else rec.turns
        return buildList {
            turns.takeLast(PRIOR_TURNS_MAX).forEach { turn ->
                val question = turn.user.content.trim()
                if (question.isBlank()) return@forEach
                add(UserMessage(question))
                // 变体列表为空（异常数据）或游标越界时安静跳过，绝不让历史构造把本轮搞崩
                val variant = turn.variants.getOrNull(
                    turn.selectedIndex.coerceIn(0, (turn.variants.size - 1).coerceAtLeast(0))
                )
                val answer = variant?.content?.trim().orEmpty()
                if (answer.isNotBlank()) add(AssistantMessage(answer))
            }
        }
    }

    override fun send() {
        val text = input.value.trim()
        if (text.isBlank() || isRunning.value) return
        // W-8 P1-C：新回合重置截图批准缓存——上个回合批准过不算数，本回合首次截图仍询问
        screenshotApprovedThisTurn = false
        // 功能四（P0-2）/ 功能五（P1-4）：先摘走待发附件并清空，避免本轮发出后仍留在输入区
        val images = _pendingImages.value
        _pendingImages.value = emptyList()
        val files = _pendingFiles.value
        _pendingFiles.value = emptyList()
        input.value = ""
        // 功能五（P1-8）：发送成功即草稿使命结束——清掉该会话草稿，
        // 否则切走再回来输入框会复活刚发过的原文（用户视角：见鬼了）
        draftStore.remove(_activeConversationId.value)
        // hotfix30：捕获在途归属会话——此后所有在途写（appendToStore/handleAgentEvent/
        // 流式草稿）按它落盘，用户切走再切回不写串；finally 清
        val owner = _activeConversationId.value
        // W-46：先把历史摊平（此时当前这句还没落库，天然不含本轮）——loop 每回合只造
        // 「系统提示 + 当前输入」，不带历史就会出现「它 / 继续 / 第二个方案」全失忆
        // （放在 begin 之前：摊平是纯读 store，不需要运行态已置位）
        // R1：上次被中止的孤儿调用说明，只注入下一轮请求一次。
        // 为什么要注入而不是只弹提示：历史里那条 assistant 说了「我来写这个文件」就断了，
        // 模型下一轮看到这段会自然地说「那我重新执行」——而那次写可能已经生效了。
        // 不落库是因为它属于恢复期的临时上下文，写进会话会在分支/导出里变成一段错位的发言。
        val recoveryNote = pendingRecoveryNote?.also { pendingRecoveryNote = null }
        val priorMessages = if (recoveryNote.isNullOrBlank()) {
            buildPriorMessages(owner)
        } else {
            buildPriorMessages(owner) + AssistantMessage(recoveryNote)
        }
        // W-54：开场仪式（置 owner / 生成中标识 / 清缓冲 / 开运行态）由控制器在 start 里做。
        // placeholderDraft=false——用户消息气泡本身就是「在生成」的占位，不必再造空草稿行。
        // 审计 F12：只记长度不记内容——这行进磁盘日志，导出诊断包会整段带走。
        AgentLogger.i("Chat", "用户发送 ${text.length} 字" +
            (if (images.isNotEmpty()) "（含 ${images.size} 张图片）" else "") +
            (if (files.isNotEmpty()) "（含 ${files.size} 个文件）" else ""))

        // L4 主动感知：把当前设备上下文自动拼进用户输入，让模型“知道”手机在干什么
        val ctxPrompt = DeviceContextStore.snapshot().toPrompt()
        // 功能五（P1-4）：文件正文以带文件名的附件块拼在「当前请求」之前——
        // 模型先看到材料、再看问题，符合人的阅读顺序；文件名保留，
        // 模型回答时能引用「在 xx.txt 里...」
        val fileBlock = files.joinToString("\n\n") { "[附件：${it.name}]\n${it.text}" }
        // W-28：@提及的文件内容（按预算截断），让模型直接看到被点名的文件
        val mentionBlock = resolveMentionBlock(text)
        // W-28：会话绑定的项目目录下的 AGENTS.md / README.md（项目规则）
        val projectRules = projectRulesBlock()
        val prefix = listOfNotNull(
            projectRules.takeIf { it.isNotBlank() },
            ctxPrompt.takeIf { it.isNotBlank() },
            mentionBlock.takeIf { it.isNotBlank() },
            fileBlock.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")

        // C 批：续跑。用户说「继续」且上次任务处于 interrupted 时，把进度摘要
        // 拼进本轮上下文，让模型知道「上次做到哪、已经做过什么」，从断点接着做
        // 而不是从头再来或直接说完成。
        //
        // 必须在下面 save() 覆盖运行态**之前**取——save 会把状态改写成本轮的新任务。
        val resumeContext = if (isResumeIntent(text)) {
            agentRunStateStore.buildResumeContext()
        } else {
            null
        }
        val fullInput = buildString {
            if (prefix.isNotBlank()) {
                append(prefix)
                append("\n\n")
            }
            if (resumeContext != null) {
                append(resumeContext)
                append("\n\n")
            }
            if (prefix.isBlank() && resumeContext == null) {
                append(text)
            } else {
                append("用户当前请求：")
                append(text)
            }
        }

        // W-16：从用户这句话里抽取「明确值得长期记住」的内容（异步、失败不影响本轮）
        autoCaptureMemory(text)

        // T05：运行句柄由控制器持有（stop() 取消它）；收尾由 start 的 finally 兜底，
        // 取消路径也会走到——否则 UI 永远停在「运行中」、发送键禁用。
        // rebuildMessages=true：Phase 2 DoD② 要求收尾用轮次变体结构重建消息列表，
        // 补上助手气泡的变体游标元数据（首条回答变体数=1，切换器不显示；重新生成后 >1）
        generation.start(owner, placeholderDraft = false, rebuildMessages = true) {
            try {
                // B 批：长任务保活 + 运行态落盘。
                // 前台服务把进程提到前台，避免切后台被系统回收；
                // 运行态让进程真被杀时，下次启动知道「上次到哪一步」。
                AgentRunService.start(app, text.take(40))
                // C 批：续跑时保留上次的 prompt 与已完成步数——
                // 否则运行态会被「继续」两个字覆盖，再中断一次就彻底丢了上下文。
                val priorState = if (resumeContext != null) agentRunStateStore.load() else null
                agentRunStateStore.save(
                    AgentRunState(
                        sessionId = owner,
                        prompt = priorState?.prompt?.takeIf { it.isNotBlank() } ?: text,
                        stepIndex = priorState?.stepIndex ?: 0,
                        status = AgentRunState.STATUS_RUNNING,
                        startedAt = priorState?.startedAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        doneTools = priorState?.doneTools ?: emptyList()
                    )
                )
                // W-19：MCP 工具启动后异步注册；首条消息最多等 8 秒，避免模型看不到 MCP 工具。
                if (!mcpReady.isCompleted) {
                    withTimeoutOrNull(8_000) { mcpReady.await() }
                }
                // Hotfix2：用户消息立即上屏；工具调用过程由 handleAgentEvent 实时渲染
                // （执行中卡 → 终态卡）；最终历史只补文本回答，工具卡按 call.id 去重
                val userItem = UiItem.User(text, timeMs = System.currentTimeMillis())
                appendUiItem(userItem)
                appendToStore(userItem) // 功能三：聊天记录同步落盘
                // W-26：首轮对话后异步生成标题。
                // W-93：**必须放到本轮结束之后**——原来在这里发起，本地模型下会和主回答
                // 抢同一个引擎：15:33:06 那轮两个 prefill 交错（60 token 3873ms + 85 token
                // 5374ms），首字被拖到 9709ms。标题是锦上添花，不值得让用户多等 9 秒。
                // 调用点见本轮结束后（finalAnswer 落库之后）。
                // 先拿到 Result，再在同一个挂起体里做后续 IO：Result.onSuccess 的 lambda 不是 suspend
                val outcome = runCatching { loop.send(fullInput, images, priorMessages) }
                // 流式草稿使命结束：最终回答以整条消息落库（防草稿与整条同时显示）。
                // hotfix30：清内存缓冲；显示态经 syncDraftDisplay 投影（owner!=active 时本就是 null）
                //
                // 【真机第三轮修复】清空前先把已流式生成的内容**捞出来**。
                // 用户点停止时，模型其实已经吐了十几片文本，但原实现直接把它连同缓冲一起丢掉，
                // 界面上只剩一句「已停止」——十几秒的产出白等。捞出来落成一条消息，
                // 用户至少能接着读/复制已经生成的部分。
                val partialDraft = generation.takeDraft()
                outcome.onSuccess { history ->
                    // 功能五（P1-5）：一个回合可能多次 LLM 往返（每次工具调用后都要再问一次模型），
                    // 每条 AssistantMessage 各带自己那次的 usage——合计后挂到最终回答气泡上，
                    // 中间轮（只有 toolCalls）不出气泡，其用量不至于丢
                    val turnUsage = TokenUsage.sum(
                        history.filterIsInstance<AssistantMessage>().map { it.usage }
                    )
                    val lastAnswer = history.filterIsInstance<AssistantMessage>()
                        .lastOrNull { it.content.isNotBlank() }
                    history.forEach { msg ->
                        when (msg) {
                            // 发送时已实时上屏，跳过防重复
                            is UserMessage -> Unit
                            // 过程事件已渲染过同 id 的工具卡；未渲染的才兜底补上
                            is ToolMessage -> {
                                val shown = messages.value.any { it is UiItem.Tool && it.call.id == msg.id }
                                if (!shown) append(msg)
                            }
                            // 中间轮 Assistant 可能只有 toolCalls 无文本，空内容不出气泡
                            is AssistantMessage -> if (msg.content.isNotBlank()) {
                                append(msg, if (msg === lastAnswer) turnUsage else null)
                            }
                            else -> Unit
                        }
                    }
                    // 【真机第四轮修复】空回复不能沉默失败。
                    //
                    // 模型返回 200 但 content 为空时（供应商偶发、内容被安全策略清空、回复被截断），
                    // 上面的 `if (msg.content.isNotBlank())` 会把这条 AssistantMessage 整个跳过——
                    // 那个空判断本意是挡住「中间轮只有 toolCalls 没有文本」的空气泡，
                    // 但对**最终轮也空**的情况同样生效，于是整个回合一个气泡都不产生。
                    //
                    // 表现（真机实测）：用户消息带着时间戳落下去了，下面一片空白，
                    // 发送键早已恢复正常。用户分不清是消息没发出去、模型抽风、还是仍在等待，
                    // 只会反复重发。这比报错更糟——报错至少说明了发生了什么。
                    //
                    // lastAnswer 已经是「本轮最后一条有文本的 Assistant」，它为 null 就说明
                    // 本轮没有任何文字输出（无论有没有工具调用），必须补一条明确提示。
                    if (lastAnswer == null) {
                        val hadTool = history.any { it is ToolMessage }
                        val emptyItem = UiItem.Assistant(
                            emptyReplyHint(hadTool),
                            timeMs = System.currentTimeMillis()
                        )
                        if (isOwnerActive()) appendUiItem(emptyItem)
                        appendToStore(emptyItem)
                        AgentLogger.w(
                            "Chat",
                            "本轮无任何文字回答（工具调用=${if (hadTool) "有" else "无"}），已补提示气泡"
                        )
                    }
                    // 功能五（P1-10）：回答完成提醒——开了开关且 App 在后台才发通知
                    // （前台提醒是噪音；权限被拒/被关由 CompletionNotifier 内部静默降级）。
                    // 只挂 send 路径：regenerate 是「重做这题」的主动操作，用户盯着概率极高
                    if (settingsFacade.completionNotifyEnabled.value) {
                        CompletionNotifier.notifyAnswerComplete(
                            app,
                            lastAnswer?.content.orEmpty()
                        )
                    }
                }
                outcome.onFailure {
                    // T05：取消是用户主动停止，不是错误——树尾闭合后历史已完整，给停止提示
                    if (it is CancellationException) {
                        AgentLogger.i("Chat", "运行已停止（历史已闭合，可继续提问）")
                        // 【真机第三轮修复】先落「已生成的部分」，再给停止提示。
                        // 文案也必须与事实一致：原来写「已完成的历史保留」，但实际连
                        // 已流式生成的正文本都被丢了——用户看到"保留"却找不到内容，比丢内容更糟。
                        val partial = partialDraft?.trim()
                        val stopText = if (!partial.isNullOrBlank()) {
                            val partialItem = UiItem.Assistant(
                                partial,
                                timeMs = System.currentTimeMillis()
                            )
                            if (isOwnerActive()) appendUiItem(partialItem)
                            appendToStore(partialItem)
                            "⏹ 已停止。以上是停止前已生成的部分，可基于它继续提问。"
                        } else {
                            "⏹ 已停止。本次没有生成任何内容。"
                        }
                        val stopItem = UiItem.Assistant(
                            stopText,
                            timeMs = System.currentTimeMillis()
                        )
                        // hotfix30：停止气泡只上屏当前显示的正是 owner 会话时（切走期间只落盘）
                        if (isOwnerActive()) appendUiItem(stopItem)
                        appendToStore(stopItem)
                        return@onFailure
                    }
                    AgentLogger.e("Chat", "运行失败", it)
                    // R-09：已生成的部分不能丢——重试被放弃时，它可能就是用户等的全部内容。
                    // 修前只有「用户点停止」这条路会抢救草稿，网络失败这条路直接连残稿一起丢。
                    partialDraft?.trim()?.takeIf { p -> p.isNotBlank() }?.let { p ->
                        val kept = UiItem.Assistant(p, timeMs = System.currentTimeMillis())
                        if (isOwnerActive()) appendUiItem(kept)
                        appendToStore(kept)
                    }
                    // 气泡面向用户：技术异常在此映射成可读文案（完整堆栈已在日志里落盘）
                    val errItem = UiItem.Assistant(friendlyMessage(it), timeMs = System.currentTimeMillis())
                    if (isOwnerActive()) appendUiItem(errItem)
                    appendToStore(errItem) // 功能三：失败气泡也进聊天记录
                }
                // L9：把本轮对话沉淀成低权重 event 记忆，下次会话也能回忆起来
                // （取消时 finalAnswer 为空，自然跳过）
                val finalAnswer = outcome.getOrNull()
                    ?.filterIsInstance<AssistantMessage>()
                    ?.lastOrNull()?.content
                    .orEmpty()
                if (finalAnswer.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            memoryStore.add(
                                "用户请求：${text.take(80)}｜结果：${finalAnswer.take(120)}",
                                "event",
                                emptyList(),
                                3
                            )
                        }
                    }
                    refreshMemories()
                }
                // W-93：主回答已落库、引擎空闲，只补算十几 token。
                // R-02：传 owner（发起会话）；读当前会话会在用户切走后把标题写错对象。
                maybeGenerateTitle(owner, text)
            } finally {
                // 本层 finally 只做「本轮专属」的收尾（前台服务、运行态落盘）；
                // 运行态复位与消息重建由控制器的 finally 统一做（取消路径同样覆盖）
                // B 批：任务结束（无论成功/失败/取消）都停掉前台服务并把运行态标记为 done。
                // 若是取消（用户主动停），同样算「正常结束」——下次不该提示「被系统中断」。
                AgentRunService.stop(app)
                runCatching {
                    agentRunStateStore.save(
                        agentRunStateStore.load()?.copy(
                            status = AgentRunState.STATUS_DONE,
                            updatedAt = System.currentTimeMillis(),
                            note = ""
                        ) ?: return@runCatching
                    )
                }
            }
        }
    }

    /**
     * 从服务端错误体里只提取「人话」部分：`message`（+ `code`）。
     *
     * 为什么不直接回显整段 JSON：真机实测 401 卡片里那串
     * `{"error":{"message":"Incorrect API key provided","type":"invalid_request_error",
     * "code":"invalid_api_key"}}` 占了大半屏，而 429/500 都只有一句话——
     * 同一类错误三种风格，且 JSON 对普通用户完全不可读。
     *
     * 解析失败（上游返回 HTML、空体、非 JSON）时返回空串：宁可不显示，也不把脏数据铺给用户。
     */
    private fun summarizeServerError(body: String): String {
        if (body.isBlank()) return ""
        val obj = runCatching { Json.parseToJsonElement(body).objOrNull() }.getOrNull() ?: return ""
        val err = obj["error"]?.objOrNull()
        val msg = err?.get("message")?.primOrNull()?.contentOrNull
            ?: obj["message"]?.primOrNull()?.contentOrNull
        if (msg.isNullOrBlank()) return ""
        val code = err?.get("code")?.primOrNull()?.contentOrNull
        val clean = AgentLogger.sanitize(msg).take(120)
        return if (code.isNullOrBlank()) clean else "$clean（$code）"
    }

    /**
     * 把底层技术异常翻译成面向用户的中文提示。
     *
     * 分级依据：[LlmHttpException] 的 HTTP 状态码（鉴权/参数类不可重试，直接给修复建议）；
     * 其余 IOException 归为网络问题；兜底才回显脱敏后的原始消息，避免泄露 Key 与堆栈。
     */
    private fun friendlyMessage(e: Throwable): String {
        // 本地推理失败：必须先于下面「IOException = 网络问题」的分支。
        // 本地模型根本不出网，把它翻译成「检查网络连接」只会让人往错方向排查。
        (e as? com.beichen.polaris.core.llm.local.LocalInferenceException)?.let {
            return "🧠 ${it.message ?: "本地模型没有返回内容"}"
        }
        // LlmHttpException 也是 IOException，必须先于网络分支判断
        val http = e as? LlmHttpException
        if (http != null) {
            return when {
                http.code == 401 || http.code == 403 -> {
                    // 401/403：附上能直接定位的信息——实际请求端点、Key 指纹、服务端原因，
                    // 让用户凭气泡就能判断是「Key 粘贴不全」还是「端点错了」，而不是再去翻日志。
                    // 指纹只取 head/tail，绝不回显完整 Key。
                    // 服务端原文只取 message + code（整段 JSON 占半屏且不可读）；URL 在 `/`
                    // 后插零宽空格，否则窄气泡会把 chat/completions 硬折成两半。
                    val endpoint = "${ConfigSanitizer.normalizeBaseUrl(_config.value.baseUrl)}/chat/completions"
                    val breakableEndpoint = endpoint.replace("/", "/\u200B")
                    val finger = keyFingerprint(_config.value.apiKey)
                    val server = summarizeServerError(http.serverBody)
                    buildString {
                        append("🔑 API Key 无效或已过期（${http.code}）：请到「模型」页检查 Key 是否正确、是否还有额度")
                        append("\n\n端点：$breakableEndpoint")
                        append("\nKey 指纹：$finger")
                        if (server.isNotBlank()) append("\n服务端：$server")
                    }
                }
                http.code == 429 ->
                    "⏳ 请求过于频繁（429）：请稍后再试"
                http.code >= 500 ->
                    "🔧 模型服务暂时不可用（服务端错误 ${http.code}）：请稍后重试"
                http.code in 400..499 ->
                    "⚠️ 请求被拒绝（${http.code}）：请检查 Base URL 与模型名是否填写正确"
                else ->
                    "⚠️ 请求失败（${http.code}）：${AgentLogger.sanitize(e.message ?: "未知错误")}"
            }
        }
        if (e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.io.IOException
        ) {
            return "📶 网络不可用或超时：请检查网络连接"
        }
        val raw = e.message?.let { AgentLogger.sanitize(it) }?.trim().orEmpty()
        return if (raw.isBlank()) "⚠️ 出错了：未知错误" else "⚠️ 出错了：$raw"
    }

    /**
     * W-47：消息列表的唯一追加入口（见 [ConversationProjection.appendItem]）。
     * 读改写都在 [msgLock] 内完成——工具事件来自最多 5 路并行协程，无锁拼接会丢卡。
     */
    private fun appendUiItem(item: UiItem) = projection.appendItem(item)

    /** 整体替换（切会话/重建）。同样入锁，避免与在途工具回调交错后丢事件。 */
    private fun replaceUiItems(items: List<UiItem>) = projection.replace(items)

    private fun append(msg: ChatMessage, usage: TokenUsage? = null) =
        projection.appendMessage(msg, usage)

    /** 同步一条消息到会话存储（按 owner 落库，见 [ConversationProjection.appendToStore]）。 */
    private fun appendToStore(item: UiItem) = projection.appendToStore(item)

    /** 刷新会话摘要列表（每次增删改后调用；抽屉最近对话区与顶栏标题消费）。 */
    private fun refreshConversations() {
        _conversations.value = conversationStore.list()
    }

    /**
     * 功能五（P1-4a）：导出指定会话为 Markdown 文本（抽屉长按菜单「导出 Markdown」）。
     * 会话不存在或为空返回空串——调用方（UI）据此不弹分享面板。
     * 纯读不改状态：导出是只读动作，不碰 updatedAt、不触发落盘。
     *
     * W-RT9：导出带上归档轮——「导出」在用户心里就是「把这对话完整拿走」，
     * 只给工作集那 200 轮等于悄悄少了一段。归档读取失败按空表处理（导出照样成立）。
     */
    override fun conversationMarkdown(convId: String): String {
        val rec = conversationStore.load(convId) ?: return ""
        if (rec.messages.isEmpty()) return ""
        return conversationToMarkdown(rec, conversationStore.archivedTurns(convId))
    }

    /* -------------------- 功能五（P1-7）：提示词库（快捷指令） -------------------- */

    /**
     * 提示词库存储（prompts.json 放 filesDir，原子写 + .bak，形态学 MemoryStore）。
     * 与 ConversationStore/MemoryStore 同一套持久化纪律，首次启动种内置 6 条。
     */
    private val promptStore = PromptStore(File(app.filesDir, "prompts.json"))

    private val _prompts = MutableStateFlow(promptStore.list())
    override val prompts: StateFlow<List<PromptItem>> = _prompts.asStateFlow()

    /** 新建/更新自定义提示词（内置会被拒收，UI 展示 [PromptUpsertResult.Rejected.reason]）。 */
    override fun savePrompt(draft: PromptItem): PromptUpsertResult {
        val result = promptStore.save(draft)
        if (result is PromptUpsertResult.Ok) _prompts.value = result.items
        return result
    }

    /** 删除提示词（内置受保护）。 */
    override fun deletePrompt(id: String): PromptDeleteResult {
        val result = promptStore.delete(id)
        if (result is PromptDeleteResult.Ok) _prompts.value = result.items
        return result
    }

    /** 「另存为自定义」：返回新 id（UI 随即切入该条编辑态）；源不存在返 null。 */
    override fun duplicatePromptAsCustom(id: String): String? {
        val newId = promptStore.duplicateAsCustom(id) ?: return null
        _prompts.value = promptStore.list()
        return newId
    }

    /**
     * 把提示词内容填进输入框（快捷指令的「一键唤起」终态）。
     *
     * 追加而非覆盖：输入框已有内容时换行续写——用户经常是「写了一半思路，
     * 选个提示词补个尾巴」；覆盖会静默毁掉他已打的字。空框则直接填入。
     */
    override fun insertPromptIntoInput(content: String) {
        val current = input.value
        input.value = if (current.isBlank()) content else "$current\n$content"
    }

    /* -------------------- 功能五（P1-4b）：全局备份 / 恢复 -------------------- */

    /**
     * 备份/恢复的四态机与两条不可退让的约束（不写 PAT、坏包先拒）都在 [BackupUseCase]，
     * 这里只转发与提供「恢复完重载 UI」的出口。
     */
    private val backup = BackupUseCase(
        app = app,
        scope = viewModelScope,
        conversations = conversationStore,
        memories = memoryStore,
        prompts = promptStore,
        config = configStore,
        keyPool = keyPoolStore,
        drafts = draftStore,
        onRestored = ::onBackupRestored,
    )

    override val backupState: StateFlow<BackupUiState> = backup.state

    /** 复位一次性状态（UI 消费完 Done/Failed 后调用）。 */
    override fun consumeBackupState() = backup.consume()

    /** 导出全局备份到用户选定的 SAF 位置（见 [BackupUseCase.exportTo]）。 */
    override fun exportBackupTo(uri: Uri) = backup.exportTo(uri)

    /** 从备份文件恢复（破坏性，调用方须先二次确认；见 [BackupUseCase.restoreFrom]）。 */
    override fun restoreBackupFrom(uri: Uri) = backup.restoreFrom(uri)

    /** 恢复完成后的 UI 重载：会话列表 / 当前会话 / 记忆 / 档案流 / 提示词。 */
    private fun onBackupRestored(items: List<PromptItem>) {
        _prompts.value = items
        refreshConversations()
        loadConversation(conversationStore.ensureNotEmpty())
        refreshMemories()
        _profiles.value = configStore.loadProfiles()
        _activeProfileId.value = configStore.loadActiveId().orEmpty()
    }

    /**
     * 载入指定会话为当前会话（设 id + 读消息进 _messages + 刷新摘要）。
     *
     * 功能五（P1-8）：草稿随会话走——切走前把输入框内容存进旧会话的草稿，
     * 载入后把新会话的草稿填回输入框。用户「在 A 会话打了半段话，去 B 会话聊完，
     * 回 A 话还在」的预期由此满足（此前输入框是全局单草稿，切走即丢）。
     * 新建会话的草稿恒为空（[DraftStore.get] 返空串），语义天然正确。
     */
    private fun loadConversation(id: String) {
        draftStore.set(_activeConversationId.value, input.value)
        _activeConversationId.value = id
        replaceUiItems(projection.loadOf(id))
        // W-30：会话切换后消息下标全变，旧译文清掉（否则会显示到别的消息下面）
        _translations.value = emptyMap()
        input.value = draftStore.get(id)
        refreshConversations()
    }

    /** 用当前会话的轮次变体结构重建消息列表（变体切换/重新生成/重新发问后调用）。 */
    private fun reloadActiveMessages() {
        projection.reloadActive()
        // W-30：消息下标全变，旧译文清掉（否则会显示到别的消息下面）
        _translations.value = emptyMap()
    }

    /**
     * 新建对话并切过去（顶栏 / 抽屉「＋ 新对话」）。
     * 运行中拒绝（VM 兜底，UI 禁用只是双保险）：新建即切 activeConversationId，
     * 在途事件（ToolStarted/ToolFinished、history 兜底、onFailure）会按新 id 落盘，
     * 把旧会话的尾巴写进新会话——与 [switchConversation] 同一理由。
     *
     * hotfix30：该风险已由 owner 捕获消除，但**仍保守拦着**——放开后用户在新空会话
     * 发消息会被 send() 的 isRunning 守卫拒（无并行发送），「能进不能发」比
     * 「不能进」更割裂；并行发送（运行句柄变多）风险大，不在本 hotfix 范围。
     */
    override fun newConversation() {
        if (isRunning.value) return
        // W-102：新会话 = 新任务。上一个任务开过的组（ui / shell / memory…）如果继续
        // 挂着，就是「不需要它的时候它还在每轮付钱」——正是这轮改造要消掉的东西。
        // 粘性作用域到此为止：跨会话从 file 组重新开始，要用再开一次。
        resetToolGroups()
        loadConversation(conversationStore.create())
    }

    /**
     * 切换到指定会话（抽屉「最近对话」点击）。
     *
     * hotfix30：运行中允许切换（真机反馈「我不能跳，不能过其他对话」）——在途任务按
     * [runOwnerConvId] 继续后台生成（只落盘不上屏），本方法只换显示态：
     * [loadConversation] 重载目标会话 + [syncDraftDisplay] 按需恢复/清实时草稿
     * （切到在途会话 → 恢复打字机；切到无关会话 → 清空，缓冲不丢）。
     * 写串风险由 owner 捕获机制消除（见 [runOwnerConvId] 注释）。
     */
    fun switchConversation(id: String) {
        if (id == _activeConversationId.value) return
        loadConversation(id)
        syncDraftDisplay()
    }

    /**
     * 删除会话；删的是当前会话则顺手切到剩下的最近一个（_messages 不空指）。
     * 删 active 会话 = 变相切 active（ensureNotEmpty 会载入另一个会话），运行中同样拒绝；
     * 删非 active 会话不碰 activeConversationId，在途事件不受影响，不拦。
     *
     * hotfix30 补禁删：在途会话（[runOwnerConvId]，含切走后后台生成的非 active 会话）
     * 一律禁删——删了 store 记录，在途收尾的 appendToStore 会把消息写回已删 id
     * （ConversationStore 按需重建记录），「删掉的会话复活」成脏数据。UI 同步禁用并 Toast。
     */
    override fun deleteConversation(id: String) {
        if (id == generation.owner) return
        if (id == _activeConversationId.value && isRunning.value) return
        conversationStore.delete(id)
        // 功能五（P1-8）：会话没了，它的草稿是垃圾数据，一并清
        draftStore.remove(id)
        if (id == _activeConversationId.value) {
            loadConversation(conversationStore.ensureNotEmpty())
        } else {
            refreshConversations()
        }
    }

    /**
     * 重命名会话（抽屉长按菜单 → 对话框）。
     * 不需要 isRunning 守卫：只改标题、不切 activeConversationId、不动 _messages，
     * 在途事件仍按原 id 正常落盘（rename 同步更新该会话的 title 字段，无写串风险）。
     */
    override fun renameConversation(id: String, title: String) {
        conversationStore.rename(id, title)
        refreshConversations()
    }

    /**
     * 功能五（P1-3）：置顶 / 取消置顶会话（抽屉长按菜单）。
     *
     * 与 rename 一样不需要 isRunning 守卫：只改排序键，不切 activeConversationId、
     * 不动 _messages，在途事件仍按原 id 落盘。
     */
    override fun setConversationPinned(id: String, pinned: Boolean) {
        conversationStore.setPinned(id, pinned)
        refreshConversations()
    }

    // —— L7 技能导入 / 卸载 / 授权 ——

    /** 从 URL 下载 zip 并导入；网络 IO 放 Dispatchers.IO。 */
    override fun importFromUrl(url: String) {
        viewModelScope.launch {
            val result = runCatching {
                // 审计 F10：原先 `URL(url).openStream().readBytes()` 无上限（OOM 抓不住、直接崩进程）、
                // 无超时、且绕开统一 HTTP 客户端。改走市场那条：协议白名单 + 8MB + 20s/60s。
                val bytes = market.fetchPackage(url).getOrThrow()
                importRepo.installZip(bytes).getOrThrow()
            }.onFailure { if (it is CancellationException) throw it }
            // onFailure 里重抛取消：`withContext` 是挂起点，ViewModel 销毁/用户离开时抛的是
            // CancellationException。旧写法把它吞进 result.isFailure，于是「取消」被写成
            // 「⚠️ 导入失败」，且协程以成功态结束（结构上也不算取消）。
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /** 载入内置示例技能（无网络演示）。 */
    override fun importDemo() {
        viewModelScope.launch {
            val result = runCatching {
                importRepo.installZip(ImportedSkillRepository.demoZipBytes()).getOrThrow()
            }
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /** 从本机选择的 zip 文件字节导入。 */
    fun importFromFile(bytes: ByteArray) {
        viewModelScope.launch {
            val result = runCatching {
                importRepo.installZip(bytes).getOrThrow()
            }
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /**
     * 从 SAF 选择的 Uri 导入 zip：文件读取放 Dispatchers.IO，绝不在主线程读字节。
     * @param uri 来自 ActivityResultContracts.GetContent() 的内容 Uri。
     */
    override fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                // 审计 F10：content:// 常无 Content-Length，必须边读边计数；`readBytes()` 会整份铺进内存。
                val stream = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("无法读取所选文件")
                val bytes = withContext(Dispatchers.IO) { stream.use { SkillPackage.readCapped(it, "所选文件") } }
                importRepo.installZip(bytes).getOrThrow()
            }.onFailure { if (it is CancellationException) throw it }
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /** 卸载指定技能（仅导入来源）；失败时给出明确提示，不谎报成功。 */
    override fun uninstallSkill(id: String) {
        viewModelScope.launch {
            val ok = importRepo.uninstall(id)
            if (!ok) {
                _importStatus.value = "⚠️ 卸载失败：$id"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "🗑️ 已卸载：$id"
        }
    }

    /**
     * 设置某技能的脚本授权（allow=true → 运行时 packaged，false → imported）。
     * 授权状态由仓库落 SharedPreferences，不再改写 skill.json。
     */
    override fun setScriptAuthorization(id: String, allow: Boolean) {
        viewModelScope.launch {
            val ok = importRepo.setScriptAuthorization(id, allow)
            if (!ok) {
                _importStatus.value = "⚠️ 操作失败：$id"
                return@launch
            }
            reloadSkills()
            _importStatus.value = if (allow) "✅ 已授权脚本：$id" else "✅ 已撤销脚本授权：$id"
        }
    }

    /** 重新加载技能快照并刷新 skillInfos；不改变 importStatus。 */
    private fun reloadSkills() {
        skillsSnapshot = buildInitialSkills()
        _skillInfos.value = computeSkillInfos()
    }

    /** W-29：把自定义 body 文本（JSON 对象）解析成 map；非法/空返回空（不阻断请求）。 */
    private fun parseCustomBody(raw: String): Map<String, JsonElement> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyMap()
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(t)
                as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
            obj
        }.getOrDefault(emptyMap())
    }

    /** W-28：绑定/解绑当前会话的项目目录（空串 = 解绑）。 */
    fun setConversationWorkspace(convId: String, dir: String) {
        conversationStore.setWorkspaceDir(convId, dir)
        refreshConversations()
        AgentLogger.i("Chat", "会话 $convId 绑定目录：${dir.ifBlank { "工作区根" }}")
    }

    /** W-28：工作区内可绑定的目录列表（相对路径，含根目录的空串）。 */
    fun workspaceDirs(): List<String> =
        runCatching {
            WorkspaceStore.listTree(app).asSequence()
                .filter { it.isDir }
                .map { it.path }
                .take(200)
                .toList()
        }.getOrDefault(emptyList())

    /**
     * W-28：项目规则块——读会话绑定目录下的 AGENTS.md / README.md。
     *
     * 为什么不放进每轮 user 消息：项目规则是**稳定上下文**，放进前缀会被
     * ContextBudget 当作普通内容压缩；以「项目规则」段喂给模型更容易被遵守。
     * 上限 4KB（规则文件通常很短，超长说明该拆分）。
     */
    private fun projectRulesBlock(): String {
        val convId = _activeConversationId.value
        val dir = runCatching { conversationStore.load(convId)?.workspaceDir }.getOrNull().orEmpty()
        if (dir.isBlank()) return ""
        val candidates = listOf("AGENTS.md", "README.md", "README.txt")
        for (name in candidates) {
            val path = if (dir.isBlank()) name else "$dir/$name"
            val content = WorkspaceStore.read(app, path) ?: continue
            val trimmed = content.take(PROJECT_RULES_LIMIT)
            val note = if (trimmed.length < content.length) "\n…（已截断）" else ""
            return "[项目规则：$path]\n$trimmed$note"
        }
        return ""
    }

    // —— W-30：消息收藏 ——

    /** 当前会话里这条内容是否已收藏（长按菜单据此显示「收藏」或「取消收藏」）。 */
    override fun isBookmarked(role: String, text: String): Boolean =
        bookmarkStore.contains(_activeConversationId.value, role, text)

    /**
     * 收藏 / 取消收藏一条消息；返回操作后是否处于「已收藏」。
     * 会话标题一并快照，收藏页在会话被删后仍能看出它来自哪里。
     */
    override fun toggleBookmark(role: String, text: String, timeMs: Long): Boolean {
        val convId = _activeConversationId.value
        val existed = bookmarkStore.contains(convId, role, text)
        if (existed) {
            bookmarkStore.removeByContent(convId, role, text)
        } else {
            val title = runCatching { conversationStore.load(convId)?.title }.getOrNull().orEmpty()
            bookmarkStore.add(convId, title.ifBlank { "未命名对话" }, role, text, timeMs)
        }
        _bookmarks.value = bookmarkStore.list()
        AgentLogger.i("Chat", if (existed) "取消收藏" else "收藏一条消息")
        return !existed
    }

    override fun removeBookmark(id: String) {
        bookmarkStore.remove(id)
        _bookmarks.value = bookmarkStore.list()
    }

    override fun clearBookmarks() {
        bookmarkStore.clear()
        _bookmarks.value = emptyList()
        AgentLogger.i("Chat", "已清空收藏")
    }

    // —— W-30：翻译 ——

    /**
     * 翻译一条消息（长按菜单调用）。
     *
     * 三条纪律：
     *  1. 同一时刻只允许一条在翻（[translatingIndex] 非空即忽略）——避免连点几次打满配额；
     *  2. 走**任务模型**（便宜），失败把原因写进状态而不是静默——用户点了就该有反馈；
     *  3. 结果按消息下标缓存在内存，不落盘（译文是临时视图，不是会话内容）。
     */
    override fun translateMessage(index: Int, text: String, target: String) {
        if (text.isBlank()) return
        if (_translations.value.containsKey(index)) return
        if (_translatingIndex.value != null) return
        _translatingIndex.value = index
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val client = buildTaskClient()
                val reply = client.chat(
                    listOf(
                        SystemMessage(TranslateLogic.systemPrompt(target)),
                        UserMessage(TranslateLogic.userPrompt(text))
                    ),
                    emptyList()
                )
                TranslateLogic.clean(reply.content, text)
                    ?: throw IllegalStateException("模型没有返回译文")
            }
            _translations.value = _translations.value + (
                index to outcome.fold(
                    onSuccess = { TranslationUi(text = it) },
                    onFailure = { TranslationUi(error = it.message?.take(160) ?: "翻译失败") }
                )
                )
            _translatingIndex.value = null
        }
    }

    /** 收起某条消息的翻译（UI 上的「收起」）。 */
    override fun clearTranslation(index: Int) {
        _translations.value = _translations.value - index
    }

    /** W-28：工作区文件路径列表（供 @ 提及建议；只列文本类文件）。 */
    override fun workspacePaths(): List<String> =
        runCatching {
            WorkspaceStore.listTree(app).asSequence()
                .filter { !it.isDir }
                .map { it.path }
                .filter { WorkspacePaths.isTextLikely(it) }
                .take(500)
                .toList()
        }.getOrDefault(emptyList())

    /**
     * W-28：把消息里 `@path` 提到的文件内容拼成上下文块。
     *
     * 预算：单文件 ≤ 8KB、总量 ≤ 24KB（与上下文预算同一量级），
     * 超出的截断并注明——不让一次 @ 把整轮上下文吃满。
     * 读取失败的文件跳过（不因一个文件名写错就阻断发送）。
     */
    private fun resolveMentionBlock(text: String): String {
        val mentioned = MentionLogic.extract(text)
        if (mentioned.isEmpty()) return ""
        val sb = StringBuilder()
        var total = 0
        for (path in mentioned) {
            if (total >= MENTION_TOTAL_LIMIT) break
            val content = WorkspaceStore.read(app, path) ?: continue
            val perFile = content.take(MENTION_PER_FILE_LIMIT)
            val clipped = if (perFile.length < content.length) {
                "$perFile\n…（文件较长，已截断到 ${MENTION_PER_FILE_LIMIT} 字符）"
            } else perFile
            val block = "[被提及的文件：$path]\n$clipped"
            if (total + block.length > MENTION_TOTAL_LIMIT) break
            sb.append(block).append("\n\n")
            total += block.length
        }
        return sb.toString().trim()
    }

    /**
     * W-26：构造「任务模型」客户端——标题生成等轻量任务用它。
     *
     * 优先用设置里指定的档案；未指定或档案已删 → 回退当前主配置。
     * 注意：任务请求**不**走 Key 池切换（轻量任务失败就放弃，不占用备用 Key 的冷却额度）。
     */
    private fun buildTaskClient(): LLMClient {
        val taskId = settingsFacade.taskProfileId.value
        val profile = if (taskId.isNotBlank()) _profiles.value.firstOrNull { it.id == taskId } else null
        val cfg = profile?.config ?: _config.value
        return providerManager.create(
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
     * W-26：首轮对话后异步生成 AI 标题（补缺口 #33）。
     *
     * 触发条件（全部满足才生成，避免浪费调用）：
     *  1. 用户消息有信息量（[TitleLogic.worthGenerating]：不是"你好"、不是纯表情）；
     *  2. 标题仍是默认值 / 早期截取的首条消息文本（用户手动改过就不覆盖）。
     * 失败静默：标题生成不是主流程，失败保持原标题即可。
     *
     * 本地档走截取分支、不花模型调用（理由见下方注释）。
     */
    private fun maybeGenerateTitle(convId: String, userText: String) {
        if (!TitleLogic.worthGenerating(userText)) return
        val current = runCatching { conversationStore.load(convId)?.title }.getOrNull().orEmpty()
        val autoTitle = userText.trim().take(20)
        if (current.isNotBlank() && current != "新对话" && current != autoTitle) return

        // 本地档不调模型，直接截用户第一句。
        //
        // 真机 2026-10-29 16:08 生成的标题是「我能回答问题、解释概念、写作、翻译和闲聊」——
        // **那是助手回答的第一句，不是标题**。原因在链路里：buildTaskClient() 落到的还是
        // 轻聊档，而 lightMessages() 会把调用方传进来的 SystemMessage（「你是会话标题生成器…」）
        // 整条丢掉、换成 LocalChatPrompt，模型收到的只剩「用户问：你能干嘛呢」，自然就开始
        // 介绍自己。一次 1.5 秒的 forward，换来一个错标题。
        //
        // 标题不为它花一次模型调用（取首行截 24 字）：省一次前向、省约 1.5 秒，
        // 而且标题一定对得上用户的问法。真机 15:02 那两个 prefill 互相抢 CPU 的问题
        // （RSS 4078 MB、整轮 46 秒里标题占 34 秒）也一并消失。
        if (_config.value.providerId == "local-llama") {
            val quick = TitleLogic.clean(userText) ?: return
            if (quick == current) return
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    conversationStore.rename(convId, quick)
                    refreshConversations()
                }
                AgentLogger.i("Chat", "标题（本地截取，未调用模型）：$quick")
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val client = buildTaskClient()
                val reply = client.chat(
                    listOf(
                        SystemMessage(
                            "你是会话标题生成器。根据用户的第一句话生成 6~12 个字的中文标题，" +
                                "概括意图即可；只输出标题本身，不要引号、不要标点、不要解释。"
                        ),
                        UserMessage(userText.take(1000))
                    ),
                    emptyList()
                )
                val title = TitleLogic.clean(reply.content) ?: return@launch
                conversationStore.rename(convId, title)
                refreshConversations()
                AgentLogger.i("Chat", "AI 标题生成：$title")
            }.onFailure {
                AgentLogger.w("Chat", "AI 标题生成失败（保持原标题）：${it.message}")
            }
        }
    }

    /**
     * W-23：下载技能包 zip（load_skill 的传输层）。
     *
     * 三重限制：只允许 https（由 LoadSkillLogic 先校验）、体积上限 2 MB（流式读取随时中止，
     * 不信任 Content-Length）、请求超时 20 秒。返回字节供安装器做 Zip Slip / 炸弹检查。
     */
    private suspend fun downloadSkillZip(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            // R-13：技能包下载并入共享预设（原 10/20s，short 为 10/30s，只放宽）
            val client = LlmHttp.short
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Polaris")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("响应体为空")
                if (body.contentLength() > LoadSkillLogic.MAX_ZIP_BYTES) {
                    throw IOException("技能包过大（上限 2 MB）")
                }
                val out = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > LoadSkillLogic.MAX_ZIP_BYTES) {
                            throw IOException("技能包超过 2 MB 上限，已中止")
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
        }
    }

    /**
     * W-17 列表载入。**必须留在类体最末**：Kotlin 按书写顺序执行属性初始化器与 init 块，
     * 而 [refreshWorkflows] 依赖第 1331 行才初始化的 [workflowFacade]。放在上面第 971 行的
     * init 块里时，慢设备上 IO 协程会先于属性初始化执行，直接 NPE（2026-11-09 模拟器复现）。
     */
    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { refreshWorkflows() } }
    }
}
