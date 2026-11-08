package com.beichen.polaris.core

/**
 * 功能五（P1-7）：提示词库的纯函数层（零 IO/Android 依赖，harness 直接断言）。
 *
 * 三条不变量（读写都按这三条收口，任一条破了就会污染列表渲染）：
 *  ① 读入净化：损坏/缺字段/重复 id 一律丢弃，坏数据不进内存（宁可少一条不崩）；
 *  ② 内置保护：builtin 项不可改不可删——用户只能「另存为自定义」
 *     （用显式 [PromptItem.builtin] 字段，不用 id 字符串前缀：
 *     前缀猜语义会在用户自定义 id 撞上前缀时出错）；
 *  ③ 标签净化：trim + 去空白 + 去重（防手打标签里混进空串污染列表渲染）。
 */

/** 内置提示词（首次启动种入；针对「手机 Agent」场景接地气，不是泛泛的代码助手腔）。 */
val BUILTIN_PROMPTS: List<PromptItem> = listOf(
    PromptItem(
        id = "builtin:explain",
        title = "解释代码",
        content = "请解释这段代码的作用、执行流程和关键设计，并用一个简单例子说明。",
        tags = listOf("理解"),
        builtin = true
    ),
    PromptItem(
        id = "builtin:review",
        title = "审查改动",
        content = "请审查当前工作区的改动，重点检查正确性、边界条件和回归风险，按严重程度列出问题及文件位置。",
        tags = listOf("开发"),
        builtin = true
    ),
    PromptItem(
        id = "builtin:debug",
        title = "排查问题",
        content = "请先复现问题并追踪根因，提出最小修复，再运行有针对性的验证。问题描述：",
        tags = listOf("开发"),
        builtin = true
    ),
    PromptItem(
        id = "builtin:phone-task",
        title = "规划手机任务",
        content = "请把下面的目标拆解成手机上的可执行步骤，说明每步要打开哪个应用、点击哪里、预期看到什么。目标：",
        tags = listOf("自动化"),
        builtin = true
    ),
    PromptItem(
        id = "builtin:summary",
        title = "总结进展",
        content = "请总结当前会话的目标、已完成工作、关键决策、待办事项和验证结果。",
        tags = listOf("整理"),
        builtin = true
    ),
    PromptItem(
        id = "builtin:translate",
        title = "翻译润色",
        content = "请翻译并润色以下内容，保留原意、专业术语和格式，使表达自然简洁：",
        tags = listOf("写作"),
        builtin = true
    )
)

/** 内置 id 前缀（旧数据兼容识别用；新代码一律看 [PromptItem.builtin] 字段）。 */
const val BUILTIN_ID_PREFIX = "builtin:"

/** upsert 结果：成功（带新列表）或拒收（带原因，UI 直接展示）。 */
sealed class PromptUpsertResult {
    data class Ok(val items: List<PromptItem>) : PromptUpsertResult()
    data class Rejected(val reason: String) : PromptUpsertResult()
}

/** 删除结果：成功（带新列表）/ 拒收（内置受保护）/ 未找到。 */
sealed class PromptDeleteResult {
    data class Ok(val items: List<PromptItem>) : PromptDeleteResult()
    data object Protected : PromptDeleteResult()
    data object NotFound : PromptDeleteResult()
}

/**
 * 读入净化（纯函数）：逐条校验 + 按 id 去重（先到先得，重复的后续丢弃）。
 * 用于文件读取与备份恢复两条路径——坏数据在边界上就被挡住，不进 UI。
 */
fun sanitizePrompts(raw: List<PromptItem>): List<PromptItem> {
    val seen = HashSet<String>()
    return raw.mapNotNull { item ->
        val id = item.id.trim()
        val title = item.title.trim()
        val content = item.content.trim()
        if (id.isEmpty() || title.isEmpty() || content.isEmpty()) return@mapNotNull null
        // 内置语义以字段为准；但 id 带前缀而字段为 false 的旧数据按内置恢复（防呆）
        val builtin = item.builtin || id.startsWith(BUILTIN_ID_PREFIX)
        if (!seen.add(id)) return@mapNotNull null
        PromptItem(
            id = id,
            title = title,
            content = content,
            tags = item.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            builtin = builtin
        )
    }
}

/**
 * 新建/更新一条自定义提示词（纯函数）。
 *
 * 规则：
 *  - title/content 必填（空 → Rejected：空标题在列表里无法辨认）；
 *  - draft.builtin = true → Rejected「内置提示词请另存为自定义」——编辑内置的
 *    正确路径是 UI 先复制成自定义项（新 id）再调本函数；
 *  - id 已存在 → 整条替换（保留位置）；否则追加到尾部。
 */
fun upsertPrompt(items: List<PromptItem>, draft: PromptItem): PromptUpsertResult {
    if (draft.builtin) return PromptUpsertResult.Rejected("内置提示词不可修改，请另存为自定义")
    val title = draft.title.trim()
    val content = draft.content.trim()
    if (title.isEmpty() || content.isEmpty()) {
        return PromptUpsertResult.Rejected("请填写名称和提示词内容")
    }
    val id = draft.id.trim()
    if (id.isEmpty()) return PromptUpsertResult.Rejected("提示词 id 不能为空")
    val entry = PromptItem(
        id = id,
        title = title,
        content = content,
        tags = draft.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        builtin = false
    )
    val exists = items.any { it.id == id }
    val next = if (exists) {
        items.map { if (it.id == id) entry else it }
    } else {
        items + entry
    }
    return PromptUpsertResult.Ok(sanitizePrompts(next))
}

/**
 * 删除一条提示词（纯函数）。内置受保护（[PromptDeleteResult.Protected]）；
 * 自定义项不存在 → [PromptDeleteResult.NotFound]（UI 静默或提示均可，不崩）。
 */
fun deletePrompt(items: List<PromptItem>, id: String): PromptDeleteResult {
    val target = items.firstOrNull { it.id == id } ?: return PromptDeleteResult.NotFound
    if (target.builtin) return PromptDeleteResult.Protected
    return PromptDeleteResult.Ok(items.filterNot { it.id == id })
}

/**
 * 「另存为自定义」：把任意项（含内置）复制成一条新的自定义项（调用方负责用
 * [newPromptId] 生成不碰撞的新 id）。返回 null 仅当源不存在。
 */
fun duplicateAsCustom(items: List<PromptItem>, id: String, newId: String): PromptItem? {
    val src = items.firstOrNull { it.id == id } ?: return null
    return PromptItem(
        id = newId,
        title = src.title,
        content = src.content,
        tags = src.tags,
        builtin = false
    )
}

/** 生成自定义提示词 id（`p-<epochMs>-<随机6位>`；碰撞概率可忽略，upsert 前 UI 不校验重复）。 */
fun newPromptId(now: Long, random: Int): String = "p-$now-${random.toString(16).padStart(6, '0')}"
