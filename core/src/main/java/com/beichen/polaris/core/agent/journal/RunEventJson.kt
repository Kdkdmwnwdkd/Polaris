package com.beichen.polaris.core.agent.journal

/**
 * 回合事件的 JSON 导出（R7）。
 *
 * ## 为什么日志本身不是 JSON
 *
 * [RunJournal] 落盘用的是自定义 TSV：每行一次 split，比解析 JSON 便宜，
 * 也没有转义地狱。代价是**字段必须自己保证不含分隔符**。
 *
 * TSV 适合「同一个进程反复读回」（崩溃恢复走的就是这条路），
 * 但不适合**跨工具消费**——诊断导出、日志分析、跨设备对照，
 * 都想要「一行一个 JSON 对象，字段自解释」。
 *
 * 所以这里做的是**导出**而不是**改格式**：TSV 继续做存储，JSON 只在需要时生成。
 *
 * ## 一行一对象的约定（NDJSON）
 *
 * 每行一个完整 JSON 对象，行内不含裸换行——这是 NDJSON 的标准约定，
 * 消费方可以逐行 `forEachLine` 解析，也可以整文件当 JSONL 读。
 *
 * 字段命名用小写短名（`seq` / `at` / `type`），与 TSV 的紧凑风格一致；
 * 但**不缩写语义**：`call` 是调用 id 而非工具名（工具名在 `assist` 事件的
 * `calls` 数组里，避免每个事件都重复一遍）。
 */
internal object RunEventJson {

    /**
     * 把一条事件序列化成一行 JSON（**不含**换行）。
     *
     * 字段顺序固定，便于人眼 diff 两份导出。
     */
    fun encode(event: RunEvent): String = buildString {
        append('{')
        append("\"seq\":").append(event.seq)
        append(",\"at\":").append(event.atMs)
        append(",\"turn\":").append(quote(event.turnId))
        append(",\"scope\":").append(quote(event.scope))
        when (event) {
            is RunEvent.TurnOpened -> {
                append(",\"type\":\"open\"")
            }
            is RunEvent.TurnClosed -> {
                append(",\"type\":\"close\"")
                append(",\"reason\":").append(quote(event.reason))
            }
            is RunEvent.StepClosed -> {
                append(",\"type\":\"step\"")
                append(",\"step\":").append(event.step)
            }
            is RunEvent.ToolDispatched -> {
                append(",\"type\":\"disp\"")
                append(",\"step\":").append(event.step)
                append(",\"call\":").append(quote(event.callId))
            }
            is RunEvent.ToolSettled -> {
                append(",\"type\":\"settle\"")
                append(",\"step\":").append(event.step)
                append(",\"call\":").append(quote(event.callId))
                append(",\"status\":").append(quote(event.status.name))
                append(",\"error\":").append(quote(event.errorCode ?: ""))
            }
            is RunEvent.AssistantCommitted -> {
                append(",\"type\":\"assist\"")
                append(",\"step\":").append(event.step)
                append(",\"calls\":[")
                event.calls.forEachIndexed { i, ref ->
                    if (i > 0) append(',')
                    append("{\"name\":").append(quote(ref.name))
                    append(",\"id\":").append(quote(ref.id)).append('}')
                }
                append(']')
            }
        }
        append('}')
    }

    /**
     * JSON 字符串转义。
     *
     * 手写而不是引 JSON 库：这里的转义规则很少变，且日志导出不在热路径上，
     * 引一个序列化器反而让这个纯 IO 层多一个依赖方向。
     *
     * 控制字符用 `\u00XX`——直接放原字符会让导出文件在某些查看器里错位。
     */
    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        sb.append('"')
        for (ch in raw) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u").append("%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
