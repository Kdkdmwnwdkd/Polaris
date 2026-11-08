package com.beichen.polaris.core.agent

import com.beichen.polaris.core.llm.primOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * R-08：审批放行的**作用域**——「本次会话不再询问」到底记住了什么。
 *
 * ## 修的是什么
 *
 * 修前 `approveAlways()` 把 `ApprovalGrouping.groupOf(toolName)` 写进 `sessionAllowed`，
 * `shell_run` 的组键只有一个 `shell`。于是：用户为了不再被 `npm install` 打断而勾选，
 * **之后所有 shell 命令都不再确认**，包括 `rm -rf`、`pm uninstall`、`git reset --hard`。
 *
 * 根因不是「少了一个判断」，而是审批键与风险的粒度对不上：组键描述的是**工具类别**，
 * 而用户同意的是**这一次的风险**。两者混在一起，勾选框就成了一个范围远大于字面意思的授权。
 *
 * ## 修法
 *
 * 把「键 / 展示名 / 能否记住」打包成一次授权，按命令内容分档：
 *  - 普通 shell（`npm install`、`gradle build`）→ 组键 `shell`，可记住；
 *  - 不可逆命令（`rm`、`dd`、`pm uninstall`…）→ 独立键，**永不可记住**，每次单独确认。
 *
 * 「永不可记住」是刻意的：一旦允许 `rm` 被记住，就等于允许了「之后任意路径的 rm」，
 * 而路径才是风险的主要变量。用户真要批量删除，多点两次的成本远低于误删一次。
 */
data class ApprovalGrant(
    /** 可记忆的授权键；不可记忆时只用于日志与去重，不会写进会话白名单。 */
    val key: String,
    /** 弹窗里的类别名，如「shell 命令」「界面点击与输入」。 */
    val label: String,
    /** 这一次能不能被「本次会话不再询问」记住。 */
    val memorizable: Boolean
) {
    /**
     * 勾选框那一行的文案。
     *
     * 不可记忆时不写「本次会话不再询问」——那会明确骗人：用户勾了，下一次照样问。
     */
    val memoLabel: String
        get() = if (memorizable) {
            "本次会话不再询问" + label.ifBlank { "同类操作" }
        } else {
            "删除/破坏类命令每次都会单独确认"
        }
}

/** 把「工具 + 本次入参」映射成一次授权。纯函数，无 Android 依赖。 */
object ApprovalScope {

    /** 走命令内容判定的工具。将来接新 shell 通道时加在这里，别在调用点 if-else。 */
    private val SHELL_TOOLS = setOf("shell_run", "shell_background")

    fun of(toolName: String, args: JsonObject): ApprovalGrant {
        val group = ApprovalGrouping.groupOf(toolName)
        val label = ApprovalGrouping.labelOf(group)
        if (toolName !in SHELL_TOOLS) return ApprovalGrant(group, label, memorizable = true)

        val command = args["command"]?.primOrNull()?.contentOrNull.orEmpty()
        if (!ShellCommandRules.isDestructive(command)) return ApprovalGrant(group, label, memorizable = true)

        val prefix = ShellCommandRules.prefixOf(command).ifBlank { "命令" }
        return ApprovalGrant(
            key = "${ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE}:$prefix",
            label = "shell 命令（$prefix）",
            memorizable = false
        )
    }

    /** 一次 granting 是否命中已有的会话白名单。不可记忆的档**永远不命中**（防御旧数据/别处写入）。 */
    fun allows(grant: ApprovalGrant, sessionAllowed: Set<String>): Boolean =
        grant.memorizable && grant.key in sessionAllowed
}
