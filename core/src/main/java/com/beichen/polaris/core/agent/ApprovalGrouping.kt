package com.beichen.polaris.core.agent

import com.beichen.polaris.core.tool.ToolCatalog

/**
 * W-36：审批「同类操作」归组。
 *
 * 背景（真机日志）：一次「打开 Shizuku 点授权」的任务里，模型连续调用了
 * ui_tap / ui_click / ui_key / ui_input / ui_screenshot，每个工具都弹一次窗，
 * 用户被迫点了十几次「允许」，单次等待 28~37 秒，任务体验彻底崩掉。
 *
 * 根因：`sessionAllowed` 按**工具名**记账，弹窗上写的却是「本次会话不再询问**同类**操作」——
 * 勾了只放过 ui_key，ui_tap 照样再问。用户以为失效，其实是粒度错了。
 *
 * 本文件把「同类」定义成**动作语义**而非工具名：界面点按类、界面输入类、应用管理类、
 * 文件写入类、Git 写入类、设备输出类。纯函数、零 Android 依赖，可在 JVM harness 断言。
 */
object ApprovalGrouping {

    /** 归组键（持久化/比较用，不要直接展示给用户）。 */
    const val GROUP_UI_ACTION = ToolCatalog.Approval.UI_ACTION
    const val GROUP_UI_SCREENSHOT = ToolCatalog.Approval.UI_SCREENSHOT
    const val GROUP_APP_MANAGE = ToolCatalog.Approval.APP_MANAGE
    const val GROUP_FILE_WRITE = ToolCatalog.Approval.FILE_WRITE
    const val GROUP_GIT_WRITE = ToolCatalog.Approval.GIT_WRITE
    const val GROUP_GIT_READ = ToolCatalog.Approval.GIT_READ
    const val GROUP_DEVICE_OUTPUT = ToolCatalog.Approval.DEVICE_OUTPUT
    const val GROUP_SHELL = ToolCatalog.Approval.SHELL

    /**
     * R-08：shell 组里**不可逆**的那一档。它与 [GROUP_SHELL] 分开，且**永不进入会话白名单**——
     * 用户同意「不再询问 shell 命令」时，同意的是「跑个 npm/gradle」，不是「以后随便 rm -rf」。
     */
    const val GROUP_SHELL_DESTRUCTIVE = ToolCatalog.Approval.SHELL_DESTRUCTIVE

    /** 提权 shell（uid 2000）：权限变了，命令没变，同样**永不记忆**。 */
    const val GROUP_SHELL_ELEVATED = ToolCatalog.Approval.SHELL_ELEVATED

    /** 首词安全但整体不可记忆的 shell（包装器 / 命令替换 / 解释器 / 写文件）。 */
    const val GROUP_SHELL_UNMEMORIZABLE = ToolCatalog.Approval.SHELL_UNMEMORIZABLE

    /** 网络外发通道：按域名记忆，换域重问。 */
    const val GROUP_NETWORK = ToolCatalog.Approval.NETWORK

    /**
     * 工具名 → 归组键。
     *
     * **数据只有一个来源**：`CatalogTool.approval`（`core/tool/ToolCatalog.kt`）。
     * 修前这里是一张 20 行的 when 表，与工具类、能力清单、能力分级三处互不知道——
     * 加一个 `needsApproval` 的工具时忘了写这里，弹窗就会出现
     * 「本次会话不再询问 read_file」这种半句英文（[labelOf] 只能原样回退工具名）。
     *
     * 未声明归组的工具仍按自身名字单独成组——那是刻意的保守回退：宁可弹窗文案难看，
     * 也不要把一个没想到的工具悄悄并进某个已有组，扩大授权范围。
     */
    fun groupOf(toolName: String): String = ToolCatalog.approvalOf(toolName) ?: toolName

    /** 归组键 → 弹窗里给用户看的说明（「本次会话不再询问<label>」）。 */
    fun labelOf(group: String): String = when (group) {
        GROUP_UI_ACTION -> "界面点击与输入"
        GROUP_UI_SCREENSHOT -> "屏幕截图与界面读取"
        GROUP_APP_MANAGE -> "应用安装/卸载/停止"
        GROUP_FILE_WRITE -> "工作区文件写入"
        GROUP_GIT_WRITE -> "GitHub 提交"
        GROUP_GIT_READ -> "GitHub 状态查询"
        GROUP_DEVICE_OUTPUT -> "朗读与通知"
        GROUP_SHELL -> "shell 命令"
        GROUP_SHELL_ELEVATED -> "提权 shell（uid 2000）"
        GROUP_SHELL_UNMEMORIZABLE -> "shell 命令"
        GROUP_NETWORK -> "网络请求"
        else -> group
    }
}
