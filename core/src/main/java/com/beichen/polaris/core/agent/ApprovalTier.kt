package com.beichen.polaris.core.agent

/**
 * W-RT3：审批档位——危险操作「问多严」的统一口径。
 *
 * 背景：此前所有 needsApproval 的调用只有一种行为——弹窗等用户决策，
 * 「本次会话始终允许」是唯一的免打扰通道。连续界面操作的任务会被弹窗打断
 * 十几次（W-36 真机日志的延续问题），而想彻底清净又只能一刀切全放。
 * 本档位把「问多严」做成三档：
 *
 *  - 逐步确认 [STRICT]：连「本次会话始终允许」也失效——每个需要审批的调用都弹窗。
 *    关键时刻逐个把关用。
 *  - 常规 [STANDARD]：现行默认。需要审批就弹窗 + 会话白名单免打扰。
 *  - 全自动 [AUTO]：低风险组自动放行（见 [AUTO_ALLOW_GROUPS]）；高风险组
 *    （应用装卸 / GitHub 提交 / shell）维持原判，该弹还弹。
 *
 * 纯函数、零 Android 依赖，可在 JVM harness 真编真跑。
 *
 * 移植自 runtime/ssh-backend-w5 的 W-RT3（旧包名 com.example.agentkit）。
 * 归组常量与 main 的 [ApprovalGrouping] 完全一致，未做改名；本文件只做判定，
 * 不碰「谁能被记住」——那是 [ApprovalScope] 的职责，两者不要互相渗透：
 * 档位决定「问不问」，作用域决定「记住什么」。
 */
object ApprovalTier {

    const val STRICT = "STRICT"
    const val STANDARD = "STANDARD"
    const val AUTO = "AUTO"

    val ALL_MODES: List<String> = listOf(STRICT, STANDARD, AUTO)

    /**
     * 非法值（旧配置残留 / 手改数据）一律回 [STANDARD]——与 W-RT2 锁档
     * 「写坏回自动」同一纪律：坏配置退到保守安全侧，永不放大权限。
     */
    fun normalize(mode: String?): String = if (mode != null && mode in ALL_MODES) mode else STANDARD

    /**
     * [AUTO] 档下自动放行的归组（低风险语义，逐组给理由）：
     *  - 界面操作：系统提示钦定的「唯一可行通道」，且每步操作前后界面状态可见，可逆；
     *  - 屏幕截图：纯读，无副作用；
     *  - 朗读与通知：设备输出，不影响任何状态；
     *  - 文件写入：工作区沙箱内 + [com.beichen.polaris.core.workspace.FileBackupStore]
     *    逐文件自动备份兜底，可回滚。
     *
     * 刻意**不含** shell：命令危不危只有参数级（[ShellCommandRules]）能判断，
     * 档位不越权替它放行；`app_manage` / `git_write` 属不可逆外部副作用，绝不自动放行。
     *
     * 也不含 [ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE]——那个键根本不在归组里，
     * 它是 [ApprovalScope] 为不可逆命令单独造的，天然落在下面 [shouldSkipDialog] 的
     * 「不在集合内」一侧。
     */
    val AUTO_ALLOW_GROUPS: Set<String> = setOf(
        ApprovalGrouping.GROUP_UI_ACTION,
        ApprovalGrouping.GROUP_UI_SCREENSHOT,
        ApprovalGrouping.GROUP_DEVICE_OUTPUT,
        ApprovalGrouping.GROUP_FILE_WRITE
    )

    /**
     * 本档位下该调用是否跳过弹窗直接放行。
     *
     * @param mode 当前档位（调用方先经 [normalize]）。
     * @param group [ApprovalGrouping.groupOf] 的归组键。
     * @param sessionAllowed 本次会话内用户已对同组选过「始终允许」。
     */
    fun shouldSkipDialog(mode: String, group: String, sessionAllowed: Boolean): Boolean = when (mode) {
        // 逐步确认：会话白名单也失效——每次都问
        STRICT -> false
        // 全自动：低风险组免弹窗；高风险组落回常规语义
        AUTO -> sessionAllowed || group in AUTO_ALLOW_GROUPS
        // 常规（默认）：现行行为
        else -> sessionAllowed
    }

    /** 档位一句话说明（设置页选项副标题用，与 [ApprovalGrouping.labelOf] 同风格）。 */
    fun descriptionOf(mode: String): String = when (mode) {
        STRICT -> "逐步确认：危险操作每次都问，「始终允许」暂不生效"
        AUTO -> "全自动：界面操作、截图、朗读通知、文件写入不再弹窗（文件写入有自动备份可回滚）；应用装卸、GitHub 提交、shell 命令仍必问"
        else -> "常规：危险操作弹窗确认，勾选「始终允许」后同类不再问"
    }
}
