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
            // 不写「删除/破坏类」：提权 shell、MCP 工具、重定向写文件都不是「删除类」，
            // 用一句通用文案，避免文案与实际判据再次漂移（R-08 的老毛病）。
            "该操作每次都会单独确认"
        }
}

/** 把「工具 + 本次入参」映射成一次授权。纯函数，无 Android 依赖。 */
object ApprovalScope {

    /** 走命令内容判定的工具。将来接新 shell 通道时加在这里，别在调用点 if-else。 */
    private val SHELL_TOOLS = setOf("shell_run", "shell_background")

    /** 网络外发通道（审计 F1）：唯一能把任意内容发到任意地址的工具。 */
    private const val NETWORK_TOOL = "http_request"

    /** MCP 工具命名前缀，见 [com.beichen.polaris.core.mcp.mcpWireName]。 */
    private const val MCP_PREFIX = "mcp_"

    /** `identity` 参数的默认值：应用自身 uid。其余取值都算提权。 */
    private const val IDENTITY_USER = "user"

    /**
     * 一次工具调用的授权语义。
     *
     * 分档顺序即风险顺序，先命中的先返回：
     *  1. 网络外发 → 按**域名**记忆（同域只问一次，换域重问）；
     *  2. MCP 工具 → 外部服务器提供，**永不记忆**（目录注释写的就是「每次调用都需确认」）；
     *  3. 提权 shell（`identity=shizuku`）→ **永不记忆**：命令没变、权限变了；
     *  4. 不可逆命令 → 永不记忆；
     *  5. 首词安全但整体不可记忆（包装器 / 命令替换 / 重定向 / 解释器 / `cp`）→ 永不记忆；
     *  6. 读工作区外的受保护目录（`cat /sdcard/DCIM/…`）→ 按**目录**记忆，换目录重问；
     *  7. 其余 → 可记忆（`npm install`、`gradle build`、`ls`…）。
     */
    fun of(toolName: String, args: JsonObject): ApprovalGrant {
        val group = ApprovalGrouping.groupOf(toolName)
        val label = ApprovalGrouping.labelOf(group)

        if (toolName == NETWORK_TOOL) {
            val host = hostOf(args)
            return if (host == null) {
                ApprovalGrant(ApprovalGrouping.GROUP_NETWORK, label, memorizable = true)
            } else {
                ApprovalGrant(
                    key = "${ApprovalGrouping.GROUP_NETWORK}:$host",
                    label = "网络请求（$host）",
                    memorizable = true
                )
            }
        }

        if (toolName.startsWith(MCP_PREFIX)) {
            return ApprovalGrant("mcp:$toolName", label, memorizable = false)
        }

        if (toolName !in SHELL_TOOLS) return ApprovalGrant(group, label, memorizable = true)

        val command = args["command"]?.primOrNull()?.contentOrNull.orEmpty()
        val prefix = ShellCommandRules.prefixOf(command).ifBlank { "命令" }

        // 审计 F1：只读白名单是给「应用自身 uid」定的。换个身份执行，`ls` 能看的范围
        // 完全不同——权限才是风险变量，命令文本不是。提权一律单独确认。
        val identity = args["identity"]?.primOrNull()?.contentOrNull?.trim()
        if (!identity.isNullOrEmpty() && identity != IDENTITY_USER) {
            return ApprovalGrant(
                key = "${ApprovalGrouping.GROUP_SHELL_ELEVATED}:$identity:$prefix",
                label = "提权 shell（$identity）",
                memorizable = false
            )
        }

        if (ShellCommandRules.isDestructive(command)) {
            return ApprovalGrant(
                key = "${ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE}:$prefix",
                label = "shell 命令（$prefix）",
                memorizable = false
            )
        }

        // 审计 F2：首词无害不代表可以被记住（`busybox rm`、`$(rm -rf)`、`cp`、`>`）。
        val memorizable = ShellCommandRules.isMemorizable(command)
        if (!memorizable) {
            return ApprovalGrant(
                key = "${ApprovalGrouping.GROUP_SHELL_UNMEMORIZABLE}:$prefix",
                label = "shell 命令（$prefix）",
                memorizable = false
            )
        }

        // F7 路径边界：只读命令不弹窗，但「读的是谁的文件」是另一件事——
        // `cat /sdcard/DCIM/x.jpg` 把文件工具的工作区沙箱整条绕开了。
        // 只在**本来可记忆**的命令上取这一档：带重定向 / 包装器的命令已在上一步
        // 落进「每次都问」，若在这里再给一个可记忆的读键，`echo x > /sdcard/a`
        // 就能复用「读 /sdcard/a」的授权，等于把写入洗白了。
        val readScope = ShellCommandRules.sensitiveReadRoot(command)
        if (readScope != null) {
            return ApprovalGrant(
                key = "${ApprovalGrouping.GROUP_SHELL_READ}:$readScope",
                // 文案带上具体目录：用户要知道自己同意的是「相册」还是「下载」。
                label = "读取 $readScope",
                memorizable = true
            )
        }

        return ApprovalGrant(group, label, memorizable = true)
    }

    /** 从 url 参数取主机名；解析失败返回 null（退化为整组记忆，仍然会弹窗）。 */
    private fun hostOf(args: JsonObject): String? {
        val url = args["url"]?.primOrNull()?.contentOrNull ?: return null
        return runCatching { java.net.URI(url).host?.lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 一次 granting 是否命中已有的会话白名单。不可记忆的档**永远不命中**（防御旧数据/别处写入）。 */
    fun allows(grant: ApprovalGrant, sessionAllowed: Set<String>): Boolean =
        grant.memorizable && grant.key in sessionAllowed
}
