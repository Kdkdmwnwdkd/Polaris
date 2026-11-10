package com.beichen.polaris.skill

import com.beichen.polaris.core.llm.primOrNull
import android.content.Context
import com.beichen.polaris.core.AgentTool
import java.io.File
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 注入系统提示时，单技能指令的最大字节数（64KB：再大就会挤掉对话历史）。 */
const val MAX_SKILL_CONTEXT_BYTES = 64 * 1024

/**
 * Skill 清单 frontmatter：对应一个 Skill 目录下的 skill.json。
 * 第一阶段只负责「解析 + use_skill 按需加载 + 工具白名单」，脚本执行（main.js）放到第二阶段。
 *
 * 元数据字段（keywords/file_types/tool_requirements/risks）供下面的 [SkillRouter] 打分路由，
 * 只把高分技能注入系统提示，而不是把全部技能一股脑塞进去。
 */
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val prompt: String? = null,
    val tools: List<String> = emptyList(),
    val entry: String? = null,
    val permissions: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val file_types: List<String> = emptyList(),
    val tool_requirements: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    /**
     * 信任等级：packaged（打包可信，全开）/ imported（导入，禁脚本）/ remote（远程，仅安全能力）。
     *
     * **B 批：默认值从 "packaged" 改为 ""（来源未知）。**
     *
     * 旧默认值是 "packaged"，意味着「manifest 没写 trust 字段」= 最高信任。
     * 而 [policyFor] 现在对未知/缺省 trust 一律按最严处理（fail-closed），
     * 两者必须一致，否则默认值会把「未声明」悄悄变成「已审计」。
     *
     * 内置（assets）技能由 [SkillLoader] 显式标为 packaged——它们确实随 APK 打包、
     * 经开发者审计。导入技能由 [com.beichen.polaris.skill.SkillImportRepository]
     * 依据本地授权记录写 packaged/imported，不采信 zip 自带声明。
     */
    val trust: String = "",

    /**
     * 本技能脚本**被允许访问的域名**（`http_request` 的唯一事先授权载体）。
     *
     * 写 `["api.example.com"]` 表示只放行该主机；写 `["*.example.com"]` 表示放行其
     * 任意子域（不含 `example.com` 本身）。裸 `"*"`、带协议、带路径的写法一律非法，
     * 见 [com.beichen.polaris.core.net.HostAllowlist.validateEntry]。
     *
     * **作用范围只有脚本桥**：主对话里模型调 `http_request` 不走这张表，
     * 仍按域名逐次审批。这条区别是有意的——脚本弹不出审批，只能事先授权；
     * 主对话弹得出，就该每次都问。
     *
     * 字段名用下划线是为了与 manifest 里其它字段（`file_types` / `tool_requirements`）
     * 保持同一风格，不引入第二种命名习惯。
     */
    val net_hosts: List<String> = emptyList(),

    /**
     * 资源目录名（assets/skills/<assetDir>），**不是** [id]。
     *
     * 内置技能的 [id] 是包名式唯一标识（`com.you.wordcount`），而资源目录用的是短名
     * （`wordcount`）。两者不一致，所以「读正文/读脚本」不能拿 [id] 拼路径——
     * 那样 `assets.open("skills/com.you.wordcount/main.js")` 必然落空，
     * 结果是脚本形态技能静默退化成提示词形态（R7 真机验证就是这么发现的：
     * `use_skill` 返回的是 manifest 里的短 prompt，`skill:` 作用域一条都没产生）。
     *
     * 导入技能从 `filesDir/skills/imported/<id>` 读，不填这个字段，
     * 走 [com.beichen.polaris.skill.SkillLoader.readImportedBody] 的另一条路径。
     */
    @kotlinx.serialization.Transient
    val assetDir: String = ""
)

/**
 * 技能资源所在的目录名：内置技能用 [SkillManifest.assetDir]，导入技能回退到 [SkillManifest.id]。
 *
 * 抽成函数是为了能被 QA harness 直接断言——它不依赖 Android `Context`，
 * 而真正的 assets 读取必须依赖，测不到。
 */
internal fun skillResourceDir(assetDir: String?, id: String): String =
    (assetDir ?: "").ifBlank { id }

/**
 * 项目（Project）= 一组 Skill + 工具白名单 + 模型配置 + 工作目录。
 * 切换 Project，Agent 的行为、可用工具、模型完全不同——正好对应「不同场景」。
 */
data class Project(
    val id: String,
    val name: String,
    val skillIds: List<String> = emptyList(),
    val toolWhitelist: List<String>? = null, // null 表示不限制
    val modelConfig: ModelConfig,
    val workingDir: String
)

@Serializable
data class ModelConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    /**
     * 供应商标识："openai" / "claude" / "gemini"（"local-llama" 第 3 步落地）。
     * 放末尾保源码兼容（旧构造点三参照常可用）；旧配置无此键时默认 "openai"。
     */
    val providerId: String = "openai",
    /**
     * W-29：自定义请求头（覆盖/追加到默认头）。空 = 不额外加。
     * 典型用途：某些中转站要求 `X-Api-Key`、`HTTP-Referer`、`X-Title` 等。
     */
    val customHeaders: Map<String, String> = emptyMap(),
    /**
     * W-29：自定义请求体片段（JSON 对象文本）。解析失败会被忽略（不阻断请求）。
     * 典型用途：`{"top_k": 40}`、`{"enable_thinking": false}` 等厂商私有参数。
     */
    val customBody: String = ""
)

/** 路由上下文：根据本次请求的特征给 Skill 打分。 */
data class SkillRouteContext(
    val query: String = "",
    val attachments: List<String> = emptyList(),
    val expectedTools: List<String> = emptyList(),
    val fileTypes: List<String> = emptyList(),
    val networkAllowed: Boolean = true,
    val destructiveAllowed: Boolean = true
)

/**
 * Skill 路由器：扫描已安装 Skill 的元数据，根据路由上下文打分，
 * 返回最匹配的候选列表，供 [buildSkillSystemPrompt] 只列出相关技能。
 */
class SkillRouter(private val skills: List<SkillManifest>) {
    fun route(ctx: SkillRouteContext): List<SkillManifest> =
        skills.map { it to score(it, ctx) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun score(skill: SkillManifest, ctx: SkillRouteContext): Int {
        var s = 0
        val q = ctx.query.lowercase()
        skill.keywords.forEach { if (it.lowercase() in q) s += 3 }
        skill.description?.lowercase()?.takeIf { q.isNotBlank() }?.let { if (it.contains(q)) s += 2 }
        ctx.fileTypes.forEach { if (it in skill.file_types) s += 2 }
        ctx.expectedTools.forEach { if (it in skill.tool_requirements) s += 2 }
        if (!ctx.networkAllowed && "network" in skill.risks) s -= 10
        if (!ctx.destructiveAllowed && "destructive" in skill.risks) s -= 10
        return s
    }
}

/** 注入系统提示：列出可用 Skill（XML 包裹，escapeXml 防注入，对齐 RikkaHub 的 use_skill 思路）。 */
fun buildSkillSystemPrompt(skills: List<SkillManifest>): String {
    if (skills.isEmpty()) return ""
    return buildString {
        appendLine("**已启用的技能（Skills）**")
        appendLine("当用户需求匹配下列技能时，调用 use_skill 加载其指令。")
        appendLine("<available_skills>")
        skills.forEach { sk ->
            appendLine("  <skill>")
            appendLine("    <name>${sk.name.escapeXml()}</name>")
            appendLine("    <description>${(sk.description ?: "").take(MAX_SKILL_CONTEXT_BYTES).escapeXml()}</description>")
            appendLine("  </skill>")
        }
        appendLine("</available_skills>")
    }
}

/**
 * 构造单一 use_skill 工具（RikkaHub 思路）：不是每个 skill 一个工具，而是统一一个
 * use_skill(name, path) 工具，模型按需调用以加载某 skill 的指令或执行其脚本。
 * 通过 [systemPrompt] 自动把可用技能列表注入系统提示（由 AgentLoop 合并）。
 *
 * @param skills 已启用的技能清单
 * @param executor Skill 执行器：prompt 形态返回指令正文，script 形态执行 main.js
 */
fun createUseSkillTool(
    skillsProvider: () -> List<SkillManifest>,
    executor: SkillExecutor
): AgentTool {
    return object : AgentTool {
        override val name = "use_skill"
        override val description =
            "加载并应用某个技能以获取专用指令或执行其脚本能力。当用户需求匹配某个可用技能时调用。"
        override val systemPrompt: String get() = buildSkillSystemPrompt(skillsProvider())

        /**
         * 写操作（T03）：script 形态技能会真实执行 main.js，可能产生副作用
         * （文件/网络/设备操作），含 use_skill 的批次整批串行。
         */
        override val readOnly = false

        override val parameters = JsonSchema.objectSchema(
            JsonSchema.prop("name", "string", "要使用的技能名称"),
            JsonSchema.prop(
                "path", "string",
                "技能目录内相对文件路径（可选）。省略则读取默认指令；仅可使用技能指令中给出的链接路径，不要猜测"
            )
        )

        override suspend fun execute(args: JsonObject): ToolResult {
            // 每次调用动态拉取技能快照，使导入/卸载/授权实时生效
            val skills = skillsProvider()
            val byName = skills.associateBy { it.name }
            val name = args["name"]?.primOrNull()?.contentOrNull
                ?: return ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 name",
                    hint = "use_skill 必须提供 name（可用技能名称，见系统提示中的 available_skills 列表）。"
                )
            val skill = byName[name] ?: byName[name.escapeXml()]
                ?: return ToolResult.fail(
                    code = "skill_not_found",
                    message = "未找到技能：$name。可用：${byName.keys.joinToString()}",
                    hint = "从 available_skills 列表中选择正确名称重新调用。"
                )
            // 把模型传入的 name/path 等参数整包 JSON 透传给脚本作为 input
            val text = executor.runSkill(skill, args.toString()).text
            return ToolResult.ok(
                code = "skill_applied",
                message = "技能「${skill.name}」已加载/执行",
                data = text.take(MAX_SKILL_CONTEXT_BYTES)
            )
        }
    }
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}

/** 从 assets/skills/<id>/skill.json 扫描已安装的 Skill 清单（Android 数据层）。 */
object SkillLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadAll(context: Context): List<SkillManifest> {
        val out = mutableListOf<SkillManifest>()
        runCatching {
            val roots = context.assets.list("skills") ?: return@runCatching
            for (id in roots) {
                val text = context.assets.open("skills/$id/skill.json")
                    .bufferedReader().use { it.readText() }
                // B 批：内置技能显式标为 packaged。
                // 它们随 APK 打包、经开发者审计，是「可信」这一判断的真正依据——
                // 而不是 manifest 里恰好没写 trust 字段。
                out += json.decodeFromString<SkillManifest>(text)
                    .copy(trust = "packaged", assetDir = id)
            }
        }
        return out
    }

    /** 读取技能指令正文：优先 SKILL.md，其次 prompt.md，再次 skill.json 的 prompt 字段。 */
    fun readSkillBody(context: Context, skill: SkillManifest): String {
        for (fileName in listOf("SKILL.md", "prompt.md")) {
            runCatching {
                context.assets.open("skills/${skillResourceDir(skill.assetDir, skill.id)}/$fileName")
                    .bufferedReader().use { it.readText() }
            }.getOrNull()?.let { return it }
        }
        return skill.prompt ?: ""
    }

    /** 读取技能脚本源码：assets/skills/<id>/<entry>。无 entry 或读不到时返回 null（回退 prompt 形态）。 */
    fun readEntry(context: Context, skill: SkillManifest): String? {
        val entry = skill.entry ?: return null
        return runCatching {
            context.assets.open("skills/${skillResourceDir(skill.assetDir, skill.id)}/$entry")
                .bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /** 读取导入技能指令正文：filesDir/skills/imported/<id>/ 下优先 SKILL.md，其次 prompt.md，最后 skill.json 的 prompt 字段。 */
    fun readImportedBody(context: Context, skill: SkillManifest): String {
        val dir = File(context.filesDir, "skills/imported/${skill.id}")
        for (fileName in listOf("SKILL.md", "prompt.md")) {
            val file = File(dir, fileName)
            if (file.isFile) return file.readText()
        }
        return skill.prompt ?: ""
    }

    /** 读取导入技能脚本源码：filesDir/skills/imported/<id>/<entry>。无 entry 或读不到时回退 null（prompt 形态）。 */
    fun readImportedEntry(context: Context, skill: SkillManifest): String? {
        val entry = skill.entry ?: return null
        val file = File(File(context.filesDir, "skills/imported/${skill.id}"), entry)
        return if (file.isFile) file.readText() else null
    }
}
