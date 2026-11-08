package com.beichen.polaris.core

/**
 * 工具结果统一契约（T02）。
 *
 * 全仓所有工具（tools/ / automation/ / memory/ / skill）执行后一律返回本结构，
 * 失败时 [hint] 必填——告诉模型「下一步该调什么、怎么修」。模型越弱越依赖它。
 *
 * 字段语义：
 *  - [ok]：成败总开关。
 *  - [code]：机器可读稳定码（如 "ok" / "missing_param" / "unknown_tool"），
 *    便于日志聚合与 UI 分支，不随文案改动而漂移。
 *  - [message]：面向模型/用户的主文本（一行说清结果）。
 *  - [hint]：失败时的修复指引；成功时为 null。
 *  - [data]：可选结构化负载（JSON / 文件内容等），非空时由 [formatToolResult] 追加输出。
 *  - [parts]：多片段结果（文本/图片）。图片经 AgentLoop 回灌视觉模型；
 *    保留它是为了让 ui_screenshot 的既有图片链路零改动（对五项契约字段的扩展，默认空）。
 *
 * 注意：本类型取代了原先定义在 AgentContract.kt 内的 parts 版 ToolResult，
 * 旧调用点已全部迁移到 [ok] / [fail] 两个工厂（失败 hint 必填由 require 强制）。
 */
data class ToolResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val hint: String?,
    val data: String?,
    /** 多片段结果（文本/图片）；默认空，仅图片类工具（ui_screenshot）使用。 */
    val parts: List<ToolPart> = emptyList()
) {
    /** 便捷文本视图：data 拼在 message 后，供日志与 SkillExecutor 的 JS 侧消费。 */
    val content: String
        get() = if (data.isNullOrBlank()) message else "$message\n$data"

    /** 是否失败：与 [ok] 取反，兼容旧读取习惯。 */
    val isError: Boolean get() = !ok

    companion object {
        /**
         * 成功结果工厂。
         *
         * @param code 机器可读码，默认 "ok"。
         * @param message 主文本（说清做了什么/拿到了什么）。
         * @param data 可选结构化负载（JSON / 文件内容等）。
         * @param parts 可选多片段（图片工具用）。
         */
        fun ok(
            code: String = "ok",
            message: String,
            data: String? = null,
            parts: List<ToolPart> = emptyList()
        ): ToolResult =
            ToolResult(ok = true, code = code, message = message, hint = null, data = data, parts = parts)

        /**
         * 失败结果工厂：[hint] 必填（require 强制）——失败必须教模型下一步怎么修。
         *
         * @param code 机器可读失败码（如 "missing_param" / "io_error"）。
         * @param message 失败主文本。
         * @param hint 修复指引（必填，非空白）。
         * @param data 可选附加负载。
         */
        fun fail(code: String, message: String, hint: String, data: String? = null): ToolResult {
            require(hint.isNotBlank()) { "失败结果必须提供 hint（教模型下一步该调什么）：code=$code" }
            return ToolResult(ok = false, code = code, message = message, hint = hint, data = data)
        }
    }
}

/**
 * 工具结果 → 回喂文本的**单一函数**（T02：一份真源、两处复用）。
 *
 * UI 工具卡与模型历史共用本函数输出：AgentLoop 只调一次，两边拿到**完全同一份**文本。
 * 纯函数（只读 [result] 字段、无外部状态）——同一 result 调用两次结果必然完全相等。
 *
 * 输出形态（稳定、逐行追加，无时间戳/随机量）：
 * ```
 * [ok] code=read_ok
 * message: 已读取 a.txt（本次 120 字符）
 * data: <文件内容…>
 * ```
 * 失败时多一行 `hint:`，直接告诉模型下一步该调什么。
 */
fun formatToolResult(result: ToolResult): String = buildString {
    append(if (result.ok) "[ok]" else "[error]")
    append(" code=").append(result.code)
    append('\n')
    append("message: ").append(result.message)
    if (!result.hint.isNullOrBlank()) {
        append('\n')
        append("hint: ").append(result.hint)
    }
    if (!result.data.isNullOrBlank()) {
        append('\n')
        append("data: ").append(result.data)
    }
}
