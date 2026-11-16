package com.beichen.polaris.tools

import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * load_skill（W-23，缺口清单 #59）：让 **Agent 自己安装/卸载技能包**。
 *
 * ## 与 use_skill 的分工
 *  - `use_skill`：**用**已装的技能（读指令 / 跑脚本）；
 *  - `load_skill`：**装**新技能（从 https 下载 zip 或自带 base64），装完立即可用，
 *    并支持列出/卸载。
 *
 * ## 为什么值得做
 * 硬编码 42 个工具永远追不上成熟产品的 180+；正确解法是让能力面可扩展——
 * 用户给一个技能包地址，Agent 自己装上，不用等下一次发版。
 *
 * ## 安全边界（全部在这一层与安装器两层拦截）
 *  - 只允许 **https** 公网地址（防中间人替换、防 SSRF 打内网）；
 *  - 体积上限 2 MB（防 zip 炸弹/耗流量）；
 *  - 安装器侧：Zip Slip、zip bomb、id 白名单、**强制降权为 imported**、
 *    重新导入撤销既有授权——装进来的脚本**默认不可执行**，需用户在技能中心显式授权。
 *
 * 所有校验在 [LoadSkillLogic]（纯函数，harness 可测）；本类只做参数解析与调用。
 */
class LoadSkillTool(
    /** 下载 zip（返回字节或失败原因）；由宿主注入，便于测试与替换传输层。 */
    private val download: suspend (url: String) -> Result<ByteArray>,
    /** 安装 zip，返回技能 id 或失败。 */
    private val install: (ByteArray) -> Result<String>,
    /** 卸载技能。 */
    private val uninstall: (String) -> Boolean,
    /** 已安装（导入来源）的技能：id to name。 */
    private val installedSkills: () -> List<Pair<String, String>>,
    /** 安装/卸载完成后刷新技能快照（热生效）。 */
    private val onChanged: () -> Unit
) : AgentTool {

    override val name = "load_skill"
    override val description =
        "安装 / 卸载 / 列出技能包。技能包含 skill.json 与可选脚本；" +
            "安装后可用 use_skill 立即调用。装进来的脚本默认不授权执行，需用户在技能中心确认。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop(
            "action", "string",
            "install_url=从 https 下载 zip 安装；install_base64=直接给 base64 的 zip；uninstall=卸载；list=列出已装",
            enum = listOf("install_url", "install_base64", "uninstall", "list")
        ),
        JsonSchema.prop("url", "string", "action=install_url 时的 https 地址（.zip）"),
        JsonSchema.prop("id", "string", "action=uninstall 时的技能 id"),
        JsonSchema.prop("data", "string", "action=install_base64 时的 zip base64 内容")
    )

    /** 写操作：会往设备写文件、可能替换既有技能。 */
    override val readOnly = false

    /**
     * D-1：安装技能包必须过一次人。
     *
     * 修前这里什么都没有——[AgentTool.needsApproval] 默认 false，于是模型可以自己
     * 从任意 https 地址装一个技能进来。这不是「多一个工具」的问题：技能包的指令正文
     * 会通过 [buildSkillSystemPrompt] 常驻系统提示，装上之后每一轮都在，是提示注入
     * **唯一能长期存活**的落地点（其余注入点都要每轮重新塞进对话）。
     *
     * `needsApproval = true` 是兜底（参数看不懂时一律问）；`list` 只读枚举已装技能、
     * 不产生任何写入，逐次弹窗只会训练用户闭眼点允许——由 [needsApprovalFor] 放行。
     */
    override val needsApproval = true

    override fun needsApprovalFor(args: JsonObject): Boolean {
        val action = args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase()
        // 缺 action / 未知 action 也算需要确认：工具侧会拒，但审批是独立的一层，
        // 不假设下游一定拦住（`null != "list"` → 问）。
        return action != ACTION_LIST
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val action = args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase()
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 action",
                    hint = "load_skill 必须提供 action：install_url / install_base64 / uninstall / list。"
                )

            when (action) {
                // 只给 list 提常量：它的取值直接决定「是否弹窗」（见 needsApprovalFor），
                // 漂移一次就是一次静默放行。其余动作的字符串漂移只会让参数校验报错，
                // 是可见的失败，不需要同一套防护。
                ACTION_LIST -> list()
                "install_url" -> installFromUrl(
                    args["url"]?.primOrNull()?.contentOrNull.orEmpty()
                )
                "install_base64" -> installFromBase64(
                    args["data"]?.primOrNull()?.contentOrNull.orEmpty()
                )
                "uninstall" -> uninstallSkill(
                    args["id"]?.primOrNull()?.contentOrNull.orEmpty()
                )
                else -> ToolResult.fail(
                    code = "bad_action",
                    message = "未知 action：$action",
                    hint = "可用：install_url / install_base64 / uninstall / list。"
                )
            }
        }.getOrElse {
            ToolResult.fail(
                code = "load_skill_error",
                message = "技能操作失败：${it.message}",
                hint = "检查参数与网络；若安装的是不可信来源，请让用户改在「技能中心」手动导入。"
            )
        }
    }

    private fun list(): ToolResult {
        val mine = installedSkills()
        val text = if (mine.isEmpty()) {
            "还没有导入来源的技能（内置技能随 App 提供，不需要安装）。"
        } else {
            mine.joinToString("\n") { (id, name) -> "· $name（id=$id）" }
        }
        return ToolResult.ok(
            code = "skill_list",
            message = "已安装技能 ${mine.size} 个",
            data = text
        )
    }

    private suspend fun installFromUrl(rawUrl: String): ToolResult {
        LoadSkillLogic.validateUrl(rawUrl)?.let {
            return ToolResult.fail(
                code = "bad_url", message = it,
                hint = "请给出 https 公网地址（例如 https://example.com/skills/demo.zip），" +
                    "内网地址与非 https 一律拒绝。"
            )
        }
        val bytes = download(rawUrl.trim()).getOrElse {
            return ToolResult.fail(
                code = "download_failed",
                message = "下载失败：${it.message}",
                hint = "确认地址可访问（返回 zip 而不是网页）；可改用 install_base64 或让用户在技能中心手动导入。"
            )
        }
        return finishInstall(bytes, "下载 ${bytes.size / 1024} KB → ")
    }

    private fun installFromBase64(raw: String): ToolResult {
        val bytes = LoadSkillLogic.decodeBase64(raw)
            ?: return ToolResult.fail(
                code = "bad_base64",
                message = "base64 内容无法解码",
                hint = "确认 data 是 zip 的 base64（可带 data: 前缀）；内容为空会被拒绝。"
            )
        return finishInstall(bytes, "base64 ${bytes.size / 1024} KB → ")
    }

    private fun finishInstall(bytes: ByteArray, prefix: String): ToolResult {
        LoadSkillLogic.validateZipSize(bytes.size.toLong())?.let {
            return ToolResult.fail(
                code = "zip_too_large", message = it,
                hint = "技能包应只含文本与少量脚本；超过 2 MB 请拆分或让用户手动导入。"
            )
        }
        val id = install(bytes).getOrElse {
            return ToolResult.fail(
                code = "install_failed",
                message = "安装失败：${it.message}",
                hint = "确认 zip 内含 skill.json（含 id/name）；id 只允许字母/数字/点/下划线/短横线。"
            )
        }
        // 热生效：让 use_skill 与技能中心立刻看到新技能
        onChanged()
        return ToolResult.ok(
            code = "skill_installed",
            message = "${prefix}技能「$id」已安装",
            data = "现在可以 use_skill(name) 使用它。\n" +
                "注意：技能里的脚本默认未授权执行；需要执行脚本时请在「技能中心」为它开启授权。"
        )
    }

    private fun uninstallSkill(rawId: String): ToolResult {
        LoadSkillLogic.validateId(rawId)?.let {
            return ToolResult.fail(
                code = "bad_id", message = it,
                hint = "先用 action=list 查看已安装技能的 id，再按 id 卸载。"
            )
        }
        val ok = uninstall(rawId.trim())
        if (!ok) {
            return ToolResult.fail(
                code = "not_found",
                message = "没有找到技能：$rawId",
                hint = "用 action=list 查看已安装列表；内置技能不能卸载。"
            )
        }
        onChanged()
        return ToolResult.ok(
            code = "skill_uninstalled",
            message = "已卸载技能「$rawId」（其授权也已撤销）"
        )
    }

    private companion object {
        /** 唯一免审批的动作：只列出已装技能，不写入任何东西。 */
        const val ACTION_LIST = "list"
    }
}
