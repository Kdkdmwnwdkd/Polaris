package com.beichen.polaris.core.tool

/**
 * 工具能力分级：决定哪些工具能被哪一级信任的 skill 调用。
 *
 * 原定义在 `skill/SkillSecurity.kt`。R-13 搬到这里，因为它描述的是**工具自己的属性**，
 * 不是 skill 的策略——skill 包现在只负责「信任等级 → 能力上限」的换算。
 */
enum class Capability {
    /** 只读沙箱资源，几乎无副作用（如 read_file）。 */
    SAFE,
    /** 涉及网络/通知/剪贴板/写文件等，可能外发数据或产生副作用。 */
    PRIVILEGED,
    /** 触碰系统高危权限（无障碍操控、录屏、设备感知），极危险。 */
    DANGEROUS
}

/** 一个组的定义。[name] 即工具名首段前缀（`misc` 除外，它是兜底桶）。 */
data class CatalogGroup(
    val name: String,
    /** 给模型看的组索引说明（`__use_tools` 的组清单与此同源）。 */
    val note: String,
    /** 常驻组：不参与淘汰，也不该让用户关掉。 */
    val pinned: Boolean = false,
    /** 成员由运行时提供（MCP）。这类组在 [ToolCatalog.tools] 里没有任何条目。 */
    val dynamic: Boolean = false,
)

/**
 * 一个工具的元数据。
 *
 * 只放「四张表曾经各写一遍」的那几项：组、能力分级、审批归组。
 * `name` 必须与工具类里的 `override val name` 逐字节相同——能力清单审计与
 * [ToolCatalog.audit] 都会对账，写错就是启动告警 + harness 红。
 *
 * @param approval 审批归组键（见 [ToolCatalog.Approval]）。null = 该工具不参与审批分组。
 */
data class CatalogTool(
    val name: String,
    val group: String,
    val capability: Capability,
    val approval: String? = null,
)

/**
 * R-13：工具元数据的**单一真源**。
 *
 * ## 修的是什么
 *
 * 加一个工具，过去要在四个地方各写一遍：工具类（name/description/schema）、
 * `CapabilityManifest.capabilities`（组与顺序）、`SkillSecurity.CapabilityRegistry`
 * （能力分级）、`ApprovalGrouping.groupOf`（审批归组）。
 *
 * 四处之间没有任何强制关系——全靠人记得。真实后果不是「编译不过」，而是
 * **静默不同步**：漏了能力表，工具被 fail-closed 当成 DANGEROUS，imported 技能一调
 * 就报越权；漏了审批组，弹窗上写着「本次会话不再询问 read_file」这种半句英文。
 * 两种都要等用户先撞上才被发现。
 *
 * ## 现在长什么样
 *
 * | 表 | 现在 |
 * |---|---|
 * | 工具类 | 仍然拥有 `name` / `description` / `parameters`（它们只属于工具） |
 * | `CapabilityManifest.capabilities` | 派生视图（组成员按本表顺序切分） |
 * | `CapabilityRegistry` | 查询视图（`cap()` / `audit()` 都查本表） |
 * | `ApprovalGrouping.groupOf` | 查询视图（`approval` 字段） |
 *
 * 加工具 = 工具类 + 本表一行。**顺序仍是有语义的**：本表里工具的先后就是发给模型的
 * 工具表顺序（P3-5：顺序影响 KV 前缀，不能随注册顺序漂移）。所以按组排列、
 * 组内沿用历史顺序，不要随手插行——插在中间会让云端每轮 prefill 全失效。
 */
object ToolCatalog {

    /** 兜底组名。 */
    const val MISC: String = "misc"

    /** 组定义。顺序即组索引与 `__use_tools` 的展示顺序。 */
    val groups: List<CatalogGroup> = listOf(
        CatalogGroup("file", "读写改本地文件", pinned = true),
        CatalogGroup("ui", "看屏幕、点按、输入、问用户"),
        CatalogGroup("net", "HTTP 请求与网页抓取"),
        CatalogGroup("web", "浏览器前进后退、点击、填表"),
        CatalogGroup("app", "安装卸载启停应用"),
        CatalogGroup("shell", "命令行执行与会话管理"),
        CatalogGroup("memory", "跨会话记忆与本地资料的读写检索"),
        CatalogGroup("skill", "加载与使用技能"),
        CatalogGroup("workflow", "工作流的增删改查与开关"),
        CatalogGroup("device", "设备状态与通知"),
        CatalogGroup("media", "图片识别与语音播报"),
        CatalogGroup("agent", "计划、待办、子代理"),
        CatalogGroup("github", "GitHub 仓库操作（克隆、提交、日志）"),
        CatalogGroup("sys", "剪贴板、容器、通知等系统能力"),
        CatalogGroup("mcp", "外部 MCP 服务器提供的工具（每次调用都需确认）", dynamic = true),
        CatalogGroup(MISC, "未归类的其他工具", pinned = true),
    )

    /**
     * 全部工具，按**工具表顺序**排列（组序 + 组内历史序）。
     *
     * `mcp` 与 `misc` 不在此表：前者成员运行时才知道，后者是兜底桶。
     */
    val tools: List<CatalogTool> = listOf(
        // ── file：读写改本地文件
        CatalogTool("read_file", "file", Capability.SAFE),
        CatalogTool("write_file", "file", Capability.PRIVILEGED, Approval.FILE_WRITE),
        CatalogTool("edit_file", "file", Capability.PRIVILEGED, Approval.FILE_WRITE),
        // ── ui：看屏幕、点按、输入、问用户
        CatalogTool("ui_tree", "ui", Capability.DANGEROUS, Approval.UI_SCREENSHOT),
        CatalogTool("ui_tap", "ui", Capability.DANGEROUS, Approval.UI_ACTION),
        CatalogTool("ui_long_press", "ui", Capability.DANGEROUS, Approval.UI_ACTION),
        CatalogTool("ui_swipe", "ui", Capability.DANGEROUS, Approval.UI_ACTION),
        CatalogTool("ui_input", "ui", Capability.DANGEROUS, Approval.UI_ACTION),
        CatalogTool("ui_key", "ui", Capability.DANGEROUS, Approval.UI_ACTION),
        CatalogTool("ui_click", "ui", Capability.DANGEROUS, Approval.UI_ACTION),
        CatalogTool("ui_screenshot", "ui", Capability.DANGEROUS, Approval.UI_SCREENSHOT),
        CatalogTool("ask_user", "ui", Capability.SAFE),
        // ── net：HTTP 请求与网页抓取
        // W-46 起它就不算只读（POST 与 GET 不能并发），但它仍是唯一一条「把任意内容
        // 发到任意地址」的外发通道——审计 F1：它与「只读 shell 免审批」组合起来就是
        // 一条零弹窗的取数 + 外发链。这里补审批归组，判定按**域名**粒度（见 ApprovalScope），
        // 同一个域名只问一次，不至于把正常联网查询拖成十几次弹窗。
        CatalogTool("http_request", "net", Capability.PRIVILEGED, Approval.NETWORK),
        // ── web：浏览器前进后退、点击、填表
        CatalogTool("web_open", "web", Capability.PRIVILEGED),
        CatalogTool("web_snapshot", "web", Capability.PRIVILEGED),
        CatalogTool("web_click", "web", Capability.DANGEROUS),
        CatalogTool("web_type", "web", Capability.DANGEROUS),
        CatalogTool("web_eval", "web", Capability.DANGEROUS),
        CatalogTool("web_search", "web", Capability.PRIVILEGED),
        // ── app：安装卸载启停应用
        CatalogTool("app_install", "app", Capability.DANGEROUS, Approval.APP_MANAGE),
        CatalogTool("app_uninstall", "app", Capability.DANGEROUS, Approval.APP_MANAGE),
        CatalogTool("app_launch", "app", Capability.PRIVILEGED),
        CatalogTool("app_list", "app", Capability.SAFE),
        CatalogTool("app_stop", "app", Capability.DANGEROUS, Approval.APP_MANAGE),
        // ── shell：命令行执行与会话管理
        CatalogTool("shell_run", "shell", Capability.DANGEROUS, Approval.SHELL),
        CatalogTool("shell_close", "shell", Capability.PRIVILEGED),
        CatalogTool("shell_background", "shell", Capability.DANGEROUS, Approval.SHELL),
        CatalogTool("shell_jobs", "shell", Capability.SAFE),
        // ── memory：跨会话记忆的读写与检索
        CatalogTool("memory_write", "memory", Capability.DANGEROUS),
        CatalogTool("memory_search", "memory", Capability.SAFE),
        CatalogTool("memory_list", "memory", Capability.SAFE),
        CatalogTool("memory_forget", "memory", Capability.DANGEROUS),
        CatalogTool("conversation_search", "memory", Capability.SAFE),
        // W-RT6：本地知识库检索。归 memory 组而不是新开一组：两者在用户眼里都是
        // 「让 Agent 去查东西」（一个查经历、一个查资料），分成两组只会让工具面板
        // 多一个「我该关哪个」的问题。fail-closed 表里必须登记，否则会被当 DANGEROUS
        // 整体不可用——旧分支 CI 第 4 轮就是栽在这（报告里写的「真漏网」）。
        CatalogTool("knowledge_search", "memory", Capability.SAFE),
        // ── skill：加载与使用技能
        CatalogTool("load_skill", "skill", Capability.SAFE),
        CatalogTool("use_skill", "skill", Capability.PRIVILEGED),
        // ── workflow：工作流的增删改查与开关
        CatalogTool("workflow_create", "workflow", Capability.PRIVILEGED),
        CatalogTool("workflow_list", "workflow", Capability.SAFE),
        CatalogTool("workflow_delete", "workflow", Capability.PRIVILEGED),
        CatalogTool("workflow_toggle", "workflow", Capability.PRIVILEGED),
        // ── device：设备状态与通知
        // 审计 F9：通知正文里有验证码、聊天内容、银行推送——读它等于读用户此刻的手机。
        CatalogTool("device_notifications", "device", Capability.DANGEROUS, Approval.PRIVATE_READ),
        // 前台应用只给包名，不含内容。隐私度与通知差一个量级，并进同一档只会多一次无意义弹窗。
        CatalogTool("device_foreground_app", "device", Capability.DANGEROUS),
        // ── media：图片识别与语音播报
        CatalogTool("ocr_image", "media", Capability.PRIVILEGED),
        // 不带 approval：朗读是用户明说「念给我听」才发生的动作，不是 Agent 自作主张的
        // 危险操作；每次弹窗只会训练用户闭眼点允许。归组键只服务于弹窗文案，
        // 不弹窗的工具填了就是死元数据（见 ApprovalGroupingVerification 的 C13）。
        CatalogTool("speak", "media", Capability.PRIVILEGED),
        // ── agent：计划、待办、子代理
        CatalogTool("plan_propose", "agent", Capability.SAFE),
        CatalogTool("spawn_agents", "agent", Capability.PRIVILEGED),
        CatalogTool("todo_write", "agent", Capability.PRIVILEGED),
        CatalogTool("todo_read", "agent", Capability.SAFE),
        // ── github：GitHub 仓库操作（克隆、提交、日志）
        // 只读查询（列仓库/分支），无副作用，同 read_file 不审。
        CatalogTool("github_status", "github", Capability.SAFE),
        CatalogTool("github_commit", "github", Capability.DANGEROUS, Approval.GIT_WRITE),
        CatalogTool("github_log", "github", Capability.SAFE),
        CatalogTool("github_diff", "github", Capability.SAFE),
        CatalogTool("github_clone", "github", Capability.PRIVILEGED),
        // ── sys：剪贴板、容器、通知等系统能力
        // 审计 F9：剪贴板里常有密码与验证码。`action=get` 把内容整个读进上下文，
        // 此后它随下一次模型请求离开本机——这一步必须过一次人（`action=set` 不问，见工具侧）。
        CatalogTool("clipboard", "sys", Capability.PRIVILEGED, Approval.PRIVATE_READ),
        // 通知本身是用户可见、可撤销的提示，弹审批比通知本身更打扰。
        CatalogTool("send_notification", "sys", Capability.PRIVILEGED),
        CatalogTool("calculator", "sys", Capability.SAFE),
        CatalogTool("container_toolchain", "sys", Capability.DANGEROUS, Approval.SHELL),
    )

    /**
     * 审批归组键词表。
     *
     * 放在这里而不是 `ApprovalGrouping`：它是 [CatalogTool.approval] 的取值域，
     * `ApprovalGrouping.GROUP_*` 现在是它的别名。依赖方向只有一条——
     * 审批逻辑读目录，目录不读审批逻辑。
     */
    object Approval {
        const val UI_ACTION = "ui_action"
        const val UI_SCREENSHOT = "ui_screenshot"
        const val APP_MANAGE = "app_manage"
        const val FILE_WRITE = "file_write"
        const val GIT_WRITE = "git_write"
        const val GIT_READ = "git_read"
        const val DEVICE_OUTPUT = "device_output"
        const val SHELL = "shell"

        /** shell 组里不可逆的那一档（R-08）：永不进入会话白名单。 */
        const val SHELL_DESTRUCTIVE = "shell_destructive"

        /**
         * 提权 shell（`identity=shizuku`，uid 2000）。与普通 shell 分开且**永不记忆**：
         * 同一条 `ls` 换个身份执行，能看到的范围完全不同——权限才是风险变量，
         * 命令文本不是。
         */
        const val SHELL_ELEVATED = "shell_elevated"

        /**
         * 首词安全、但从整体看不可记忆的 shell（`busybox rm`、`$(rm -rf)`、`cp`、重定向、
         * `python -c`）。同样永不进白名单。
         */
        const val SHELL_UNMEMORIZABLE = "shell_unmemorizable"

        /** 网络外发：按域名记忆，换一个域就要重新问。 */
        const val NETWORK = "net_request"

        /**
         * 工作区外的**只读**访问（`cat /sdcard/DCIM/…`）。只由
         * [com.beichen.polaris.core.agent.ApprovalScope] 产出，目录里没有成员。
         *
         * 与 [SHELL] 分开的理由：`ls`、`npm install` 的授权说的是「这条命令可以跑」，
         * 而读相册是**另一件事**——把两者并进同一个可记忆的键，等于用一次构建授权
         * 换掉了整个相册的读取权。粒度按目录记（`/sdcard/DCIM`），换目录重问。
         */
        const val SHELL_READ = "shell_read"

        /**
         * 读用户私有数据（剪贴板 / 通知）。审计 F9。
         *
         * 与 [SHELL_READ] 同族但不同路：那条防的是「绕开工作区沙箱读文件」，这条防的是
         * 「两个工具本来就没有沙箱」——`clipboard get` 拿到的是用户刚复制的密码，
         * `device_notifications` 拿到的是验证码短信与聊天消息，两者都直接进上下文、
         * 再随下一次请求出去。它们不经过任何路径，所以 PathGuard 一类判据对它们无效。
         *
         * 粒度按**工具**记（`private_read:clipboard` / `private_read:device_notifications`）：
         * 同意读剪贴板不等于同意读通知，两者的敏感度不在一个量级。
         */
        const val PRIVATE_READ = "private_read"
    }

    private val byName: Map<String, CatalogTool> = tools.associateBy { it.name }
    private val groupNames: Set<String> = groups.mapTo(LinkedHashSet()) { it.name }

    /** 目录里的工具名，顺序即工具表顺序。 */
    val toolNames: List<String> = tools.map { it.name }

    fun find(name: String): CatalogTool? = byName[name]

    /** 工具的能力分级；未登记按 [Capability.DANGEROUS]（fail-closed，与旧表一致）。 */
    fun capabilityOf(name: String): Capability = byName[name]?.capability ?: Capability.DANGEROUS

    /** 工具的审批归组键；未登记或未声明返回 null。 */
    fun approvalOf(name: String): String? = byName[name]?.approval

    /**
     * 工具属于哪个组。
     *
     * 目录里有的直接取；不在目录里的（MCP 动态工具、插件）按首段前缀推，前缀是已声明组
     * 就用它，否则 [MISC]——**不认识的东西不能藏**，藏了模型永远调不到。
     */
    fun groupOf(name: String): String {
        byName[name]?.let { return it.group }
        val prefix = name.substringBefore('_', missingDelimiterValue = name)
        return if (prefix in groupNames) prefix else MISC
    }

    /** 按目录顺序切出某组的成员（`misc` / `mcp` 恒为空）。 */
    fun membersOf(group: String): List<String> = tools.filter { it.group == group }.map { it.name }

    /**
     * 目录自检。每条都对应一种「加了工具但只改了一半」的真实形态。
     *
     * @param implemented 当前真正注册进 ToolRegistry 的工具名（顺序无所谓）。
     */
    fun audit(implemented: Collection<String>): List<String> = buildList {
        val seen = HashSet<String>()
        tools.forEach { t ->
            if (!seen.add(t.name)) add("目录里 ${t.name} 重复登记")
            val g = groups.firstOrNull { it.name == t.group }
            if (g == null) {
                add("${t.name} 写进了未定义的组「${t.group}」")
            } else if (g.dynamic) {
                add("${t.name} 写进动态组「${t.group}」——动态组成员只能由运行时提供")
            }
        }
        groups.filter { it.dynamic && tools.any { t -> t.group == it.name } }.forEach {
            add("动态组「${it.name}」不该有静态成员")
        }
        groups.filter { it.name != MISC && !it.dynamic && tools.none { t -> t.group == it.name } }
            .forEach { add("组「${it.name}」没有任何成员（模型按索引开组会开到空组）") }

        // 组必须**成段**出现。CapabilityManifest.toolNames 是按组切分拼出来的，
        // 而发给模型的工具表按它排；如果同一组被插行拆成两段，这里拼出来的顺序
        // 就与目录里写的顺序不一致——工具表顺序悄悄变了，KV 前缀整段失效，
        // 而没有任何地方会响。所以「顺序稳定」这件事得有人看着。
        val commenced = HashSet<String>()
        var prev: String? = null
        tools.forEach { t ->
            if (t.group != prev) {
                if (!commenced.add(t.group)) {
                    add("${t.name} 让组「${t.group}」断成两段（工具表顺序会与书写顺序不一致）")
                }
                prev = t.group
            }
        }
        if (tools.any { it.group == MISC }) add("有工具被登记进兜底组「$MISC」——它该有个真正的组")

        // 目录 ↔ 实现：两边都必须有对方
        val catalog = tools.mapTo(HashSet()) { it.name }
        val impl = implemented.toSet()
        (impl - catalog).sorted().forEach {
            add("$it 已注册但目录没登记（组/能力/审批全都查不到）")
        }
        (catalog - impl).sorted().forEach {
            add("$it 在目录里但没有实现（模型照目录调用会拿到 unknown_tool）")
        }
    }
}
