package com.beichen.polaris.tools

import android.app.Application
import android.content.Context
import com.beichen.polaris.audio.VoiceArbiter
import com.beichen.polaris.automation.DeviceForegroundAppTool
import com.beichen.polaris.automation.DeviceNotificationsTool
import com.beichen.polaris.automation.OcrTool
import com.beichen.polaris.automation.UiClickTool
import com.beichen.polaris.automation.UiInputTool
import com.beichen.polaris.automation.UiKeyTool
import com.beichen.polaris.automation.UiLongPressTool
import com.beichen.polaris.automation.UiScreenshotTool
import com.beichen.polaris.automation.UiSwipeTool
import com.beichen.polaris.automation.UiTapTool
import com.beichen.polaris.automation.UiTreeTool
import com.beichen.polaris.automation.shizuku.ShizukuShell
import com.beichen.polaris.core.Consent
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.StartupMark
import com.beichen.polaris.core.ToolApproval
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.PlanBroker
import com.beichen.polaris.core.agent.SubAgentRunner
import com.beichen.polaris.core.agent.TodoStore
import com.beichen.polaris.core.job.JobRunner
import com.beichen.polaris.core.job.JobStore
import com.beichen.polaris.core.terminal.TerminalSessionPool
import com.beichen.polaris.core.workspace.FileBackupStore
import com.beichen.polaris.core.workspace.WorkspaceStore
import com.beichen.polaris.memory.MemoryForgetTool
import com.beichen.polaris.memory.MemoryListTool
import com.beichen.polaris.memory.MemorySearchTool
import com.beichen.polaris.memory.MemoryStore
import com.beichen.polaris.memory.MemoryWriteTool
import com.beichen.polaris.runtime.lease.TerminalLeases
import com.beichen.polaris.skill.SkillExecutor
import com.beichen.polaris.skill.SkillManifest
import com.beichen.polaris.skill.createUseSkillTool
import com.beichen.polaris.workflow.WorkflowStore
import java.io.File

/**
 * 工具装配的**输入面**（v4 §3.3，P1 第六轮）。
 *
 * 这里只装「装配一台工具表需要什么」，不装「谁构造这个表」。每一项都是宿主已经拥有的
 * 具体对象或函数，没有任何一样是装配器自己去 new 的——装配器一旦开始自己造依赖
 * （比如自己 `MemoryStore(...)`），桌宠与主界面就会各有一份记忆库，两边的写入互相看不见。
 *
 * 三类需要特殊说明的成员：
 * - **函数型**（[activeConversationId] / [onWorkflowsChanged] / [planModeEnabled] /
 *   [onPrivilegedChannelLost] / [llmProvider]）：这些值在运行期会变。写成函数而不是快照，
 *   是因为快照的失效是**静默**的——todo 工具会永远写进「应用启动时那个会话」，
 *   不报错，用户只看到待办莫名出现在别的对话里。
 * - **[approval]**：返回 [ToolApproval] 的函数（`() -> ToolApproval`），不是 `ToolApproval`。
 *   `approvalHandler` 在 ViewModel 里声明得比 registry 晚，装配期直接取值会读到 null；
 *   包一层函数，真正弹窗时才读。
 * - **[startup]**：装配期逐项计时（`ToolLoadProfiler` 挂在它上面），装配器只负责打点，
 *   不决定观测怎么存。
 */
class ToolDeps(
    val app: Application,
    val startup: StartupMark,
    val workspace: File,
    val fileBackupStore: FileBackupStore,
    val memoryStore: MemoryStore,
    val voiceArbiter: VoiceArbiter,
    val conversationStore: ConversationStore,
    val workflowStore: WorkflowStore,
    /** 增删/开关定时任务后刷新设置页列表。 */
    val onWorkflowsChanged: () -> Unit,
    val terminalPool: TerminalSessionPool,
    val terminalLeases: TerminalLeases,
    /** Shizuku 通道被判定为「未运行/未授权」时，让宿主重新探测一次状态。 */
    val onPrivilegedChannelLost: () -> Unit,
    val planModeEnabled: () -> Boolean,
    val todoStore: TodoStore,
    /** 当前会话 id（todo 工具按会话隔离清单）；每轮发送时才读。 */
    val activeConversationId: () -> String?,
    /** 主模型客户端（子代理要自己再开一条对话，用的是同一个供应商配置）。 */
    val llmProvider: () -> LLMClient,
    /** 见类注释：返回 `ToolApproval` 的函数，避免装配期读到未初始化的 handler。 */
    val approval: () -> ToolApproval,
    val jobStore: JobStore,
    val jobRunner: JobRunner,
)

/**
 * 工具装配的唯一入口（v4 §3.3，P1 第六轮）。
 *
 * 为什么要有这个文件：装配知识原来长在 `ChatViewModel` 的字段初始化里（52 个
 * `register(...)`），桌宠则在 `PetAgentSession` 里另写了一份 5 个工具的迷你版。
 * 后果不是「代码不好看」，而是三件事：
 *
 * 1. **加一个工具要改 ui 层**。工具是 core/automation 的能力，装配却写在界面层，
 *    于是「能否让模型用某个能力」由界面文件决定——`ui` 目录成了能力的实际边界。
 * 2. **桌宠与主界面的工具集**无从对照。桌宠少注册了某个只读工具，表现是「桌宠答不上来」，
 *    而没人能一眼看出它到底有哪些工具。
 * 3. 行数守卫（架构守卫 [B]）把 `ChatViewModel` 冻在 1500 行上限的 2.3 倍，
 *    这一片 130 行是这个文件里最能整体搬走、且搬走不改行为的一块。
 *
 * 收口之后，`ui` 与 `pet` 只**持有**工具表，不再**决定**它有哪些工具（不变量 2 的 D1b）。
 */
object ToolAssembler {

    /**
     * 主界面这一路的完整工具表。
     *
     * 计时口径照旧：`preRegistry` 与 `registry` 两个 mark 之间就是全部工具实例化的耗时。
     * `ToolRegistry()` 构造本身极轻，重的是这 52 个 `register`（每个都会 new 一个工具、
     * 触发一批类加载）。
     */
    fun assemble(deps: ToolDeps): ToolRegistry = ToolRegistry(deps.startup.toolProfiler).apply {
        deps.startup.mark("preRegistry") // 口径：ToolRegistry() 构造极轻，重的是下面 45 个 register
        register(HttpRequestTool())
        register(ReadFileTool(deps.workspace))
        register(WriteFileTool(deps.workspace, WriteBackupHook { deps.fileBackupStore.backupBeforeWrite(it) }))
        // W-20：精确编辑（局部替换，不整文件覆写）+ 网页搜索（实时信息入口）
        register(EditFileTool(deps.workspace, WriteBackupHook { deps.fileBackupStore.backupBeforeWrite(it) }))
        register(WebSearchTool())
        register(ClipboardTool(deps.app))
        register(NotificationTool(deps.app))
        // 手机自动化工具组（受总开关 + 无障碍服务连接双重保护）
        register(UiTreeTool())
        register(UiTapTool())
        register(UiLongPressTool())
        register(UiSwipeTool())
        register(UiInputTool())
        register(UiKeyTool())
        register(UiClickTool())
        // 截图（无障碍）：喂给视觉模型“看”屏幕
        register(UiScreenshotTool(deps.workspace))
        // 设备感知（L3）：通知监听 + 当前前台应用
        register(DeviceNotificationsTool())
        register(DeviceForegroundAppTool(deps.app))
        // 记忆库（L9）：让 Agent 拥有跨会话长期记忆
        register(MemoryWriteTool(deps.memoryStore))
        register(MemorySearchTool(deps.memoryStore))
        register(MemoryListTool(deps.memoryStore))
        register(MemoryForgetTool(deps.memoryStore))
        // W-16：系统 TTS 朗读（经仲裁器：用户说话时模型不能插播）
        register(SpeakTool(deps.voiceArbiter))
        // W-18：会话检索 + 计算器
        register(ConversationSearchTool(deps.conversationStore))
        // W-RT6：本地知识库检索。目录固定 files/knowledge/，用户把 .txt/.md 放进去即可；
        // 没有独立导入界面——Agent 本身有文件能力，「把这份资料加进知识库」就是一句工具调用。
        register(KnowledgeSearchTool(java.io.File(deps.app.filesDir, "knowledge")))
        register(CalculatorTool())
        // W-17：定时任务（模型可自建/查询/删除/开关）
        register(WorkflowCreateTool(deps.workflowStore) { deps.onWorkflowsChanged() })
        register(WorkflowListTool(deps.workflowStore))
        register(WorkflowDeleteTool(deps.workflowStore) { deps.onWorkflowsChanged() })
        register(WorkflowToggleTool(deps.workflowStore) { deps.onWorkflowsChanged() })
        // 终端 shell（T08）：会话池 + 短句柄；shell_run 执行命令、shell_close 释放会话
        register(
            ShellRunTool(
                pool = deps.terminalPool,
                leases = deps.terminalLeases,
                privilegedExec = { cmd ->
                    // W-37：优先 UserService（shell 进程内起本地进程，waitFor 语义正常，进程常驻）；
                    // 不可用时回落 W-36 的 newProcess 轮询通道；两者都不可用才回落本机 user shell。
                    val r = ShizukuShell.execViaUserService(cmd) ?: ShizukuShell.exec(cmd)
                    // 未运行/未授权（通道压根没起来）→ null 让上层回落本机 user shell。
                    // 其余启动失败（newProcess 抛异常等）必须如实上抛，不能伪装成超时。
                    if (r.startError != null && r.startError.contains("未运行或未授权")) {
                        deps.onPrivilegedChannelLost()
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
        register(ShellCloseTool(deps.terminalPool))
        // W-15：浏览器五件套（真实 WebView，保留登录态、能点能填）
        register(WebOpenTool())
        register(WebSnapshotTool())
        register(WebClickTool())
        register(WebTypeTool())
        register(WebEvalTool())
        // W-14：结构化追问 + 任务清单
        register(AskUserTool())
        // W-25：先计划后执行——提交多步计划并等待用户批准
        register(PlanTool(broker = PlanBroker, planModeEnabled = { deps.planModeEnabled() }))
        register(TodoWriteTool(deps.todoStore) { deps.activeConversationId() })
        register(TodoReadTool(deps.todoStore) { deps.activeConversationId() })
        // 应用操控（Phase 1 补口）：列出/启动/停止任意已安装应用——「叫它打开快手就能打开」。
        // app_launch 走 Intent（零权限）；app_stop 接**真实 shell 通道**（会话池适配，见下），
        // 通道不可用（池无可用后端）时仍走「诚实降级为需用户手动停止」，绝不静默假装成功。
        // apply 内传 this（不能传 registry 自身，会触发类型推断递归）
        registerAppControlTools(this, deps.app, terminalShellCommandRunner(deps.terminalPool))
        // W-24：离线 OCR（截图/读图 → 文字，任何模型都能读）+ 软件安装/卸载
        register(OcrTool(deps.workspace))
        register(AppInstallTool(deps.app, deps.workspace))
        register(AppUninstallTool(deps.app, terminalShellCommandRunner(deps.terminalPool)))
        // W-31：并行子代理——只读工具 + 独立上下文，只把结论带回主对话。
        // baseRegistry 传当前表（apply 内 this）：子代理表由白名单过滤，spawn_agents 自身被排除（防递归）。
        register(
            SubAgentTool(
                SubAgentRunner(
                    llmProvider = { deps.llmProvider() },
                    baseRegistry = { this },
                    // R4 + P1-3：同意策略穿子代理边界。用 lambda 间接引用而不是直接传值：
                    // registry 的初始化早于 approvalHandler，直接取值会捕到 null；lambda 在真正弹窗时才读。
                    consent = Consent.ask { tool, args -> deps.approval().invoke(tool, args) }
                )
            )
        )
        // W-32：GitHub 提交三件套——「改完能提交」闭环（复用「构建」页的仓库与 PAT）
        register(GitHubStatusTool(deps.app))
        register(GitHubCommitTool(deps.app))
        register(GitHubLogTool(deps.app))
        // W-34：行级 diff——提交前确认到底改了哪几行
        register(GitHubDiffTool(deps.app))
        // W-35：整仓克隆到工作区子目录（codeload zip，走镜像回退）
        register(GitHubCloneTool(deps.app))
        // W-33：后台命令——长命令不再阻塞整轮，跑完发通知
        register(ShellBackgroundTool(deps.app, deps.jobRunner))
        register(ShellJobsTool(deps.jobStore, deps.jobRunner))
        // B 批：容器运行时管理——让 Agent 自己把容器变成能跑 node/python/java 的环境
        register(ContainerToolchainTool(deps.app))
        deps.startup.mark("registry") // 工具注册表整段（40+ 工具实例化）：最可能随工具数线性增长的段
    }

    /**
     * 桌宠这一路的工具表（5 个）。
     *
     * 与 [assemble] 分开不是省内存，是**能力边界**：桌宠是「一句话问答」的悬浮球，
     * 用户说完就走了。给它 shell / 写文件 / 装应用这类工具，等于把一台无人看管的
     * 终端放到桌面上——而它的价值恰恰是「立刻出声」，不是「替你干重活」。
     *
     * 唯一的写操作是 `app_launch`，且包了 [ConfirmRequiredTool]：桌宠的典型句子是
     * 「帮我打开微信」，这是它作为悬浮球无可替代的场景；包一层确认而不是改
     * [AppLaunchTool] 本身，是因为主界面的 `app_launch` 一直免确认（用户就在屏幕前），
     * 让全局跟着弹窗是一次没有论证过的行为扩大。
     *
     * 五个工具显式列举、不复用 [assemble] 的白名单过滤：白名单在 core 里是「子代理可用的
     * 只读集」，与「桌宠可用集」是两个不同的判断依据，混用会让其中一边随另一边漂移。
     */
    fun pet(context: Context): ToolRegistry = ToolRegistry().apply {
        register(CalculatorTool())
        register(WebSearchTool())
        register(ReadFileTool(WorkspaceStore.dir(context)))
        register(MemorySearchTool(MemoryStore(File(context.filesDir, "memory"))))
        register(ConfirmRequiredTool(AppLaunchTool(context)))
    }

    /**
     * 第二阶段装配：技能工具（`use_skill` / `load_skill`）。
     *
     * 为什么要分两步：`SkillExecutor` 构造时需要**已经建好的** [ToolRegistry]（技能脚本
     * 通过它调工具），所以技能工具不可能和 52 个内建工具一起在 [assemble] 里注册。
     * 顺序是硬约束，不是风格选择——写反了就是「技能执行时找不到工具」，
     * 而那要等到用户第一次真正调技能才会暴露。
     *
     * [skills] 与 [importedIds] 都是函数而不是快照：技能可以在运行期导入/卸载，
     * 快照会让刚装的技能在本进程内一直「不存在」。
     */
    fun attachSkillTools(
        registry: ToolRegistry,
        skills: () -> List<SkillManifest>,
        executor: SkillExecutor,
        download: suspend (String) -> Result<ByteArray>,
        install: (ByteArray) -> Result<String>,
        uninstall: (String) -> Boolean,
        importedIds: () -> Set<String>,
        onChanged: () -> Unit,
    ) {
        // use_skill 的 systemPrompt 由 AgentLoop 自动拼进系统提示（工具自带提示特性）。
        // 一张技能都没有时不注册：一个自称「可以加载技能」但每次都报「没有可用技能」的
        // 工具，只会让模型浪费一轮去试。
        if (skills().isNotEmpty()) {
            registry.register(createUseSkillTool(skills, executor))
        }
        // W-23：load_skill——模型可自己安装/卸载技能包（装完热生效，立刻能用 use_skill）。
        registry.register(
            LoadSkillTool(
                download = download,
                install = install,
                uninstall = uninstall,
                installedSkills = { skills().filter { it.id in importedIds() }.map { it.id to it.name } },
                onChanged = onChanged
            )
        )
    }
}
