package com.beichen.polaris.core.mcp.server

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP 调用的审计日志。
 *
 * ## 为什么要有它，而不是「出事了看 logcat」
 *
 * 外部客户端的调用**没有本地会话可回看**：用户只会在事后问「刚才它动我手机了吗」。
 * logcat 会被冲掉、会被用户清掉、Release 包默认也读不到。落一份用户能自己打开的
 * JSONL，是这个功能唯一可用的追责证据。
 *
 * ## 为什么参数只存摘要
 *
 * `ui_input` 的参数里是用户刚打的字，`write_file` 可能是一段密钥。存全文等于
 * 把隐私从一个盘子挪到另一个盘子，还更难删。所以：`argsHash`（可复算对账）+
 * 前 200 字符（够看出「调的是什么」）。
 *
 * ## 写失败不阻断调用
 *
 * 磁盘满、文件被占用都不该让用户的正常操作失败。但也不能静默——写失败会置
 * [lastWriteFailed]，由 UI 标红提示「审计已中断」，让用户知道证据链断了。
 */
class McpAudit(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionDays: Int = 7,
) {

    @Volatile
    var lastWriteFailed: Boolean = false
        private set

    private val lock = Any()
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    /**
     * 记一条调用。
     *
     * @param decision 只允许几个固定值：`allow` / `deny_tier` / `deny_approval`
     *   / `deny_throttle` / `deny_guard` / `error`。固定取值是为了让后续统计
     *   （「这个客户端到底被拒了多少次」）不依赖文案。
     */
    fun record(
        client: String,
        tier: McpTier,
        tool: String,
        args: JsonObject,
        decision: String,
        approvalKey: String?,
        durationMs: Long,
        status: String,
    ) {
        val now = clock()
        val line = buildString {
            append("{\"ts\":").append(now)
            append(",\"client\":").append(quote(client))
            append(",\"tier\":").append(quote(tier.wire))
            append(",\"tool\":").append(quote(tool))
            append(",\"argsHash\":").append(quote(sha256(args.toString())))
            append(",\"argsPreview\":").append(quote(preview(args)))
            append(",\"decision\":").append(quote(decision))
            append(",\"approvalKey\":").append(approvalKey?.let(::quote) ?: "null")
            append(",\"ms\":").append(durationMs)
            append(",\"status\":").append(quote(status))
            append("}\n")
        }
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                fileFor(now).appendText(line)
                lastWriteFailed = false
            }.onFailure { lastWriteFailed = true }
        }
    }

    /** 最近 N 条（新的在前），给设置页展示。读失败返回空表——不因为日志坏了让页面崩。 */
    fun readRecent(limit: Int = 200): List<String> = synchronized(lock) {
        runCatching {
            val today = fileFor(clock())
            val yesterday = fileFor(clock() - 24L * 60 * 60 * 1000)
            (listOf(today, yesterday).filter { it.exists() }.flatMap { it.readLines() })
                .takeLast(limit)
                .reversed()
        }.getOrElse { emptyList() }
    }

    fun clear() = synchronized(lock) {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
        lastWriteFailed = false
    }

    /** 滚动删除超过保留期的文件。由宿主服务每天调一次，不在写路径上。 */
    fun prune() = synchronized(lock) {
        runCatching {
            val cutoff = clock() - retentionDays * 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("audit-") && f.lastModified() < cutoff) f.delete()
            }
        }
        Unit
    }

    private fun fileFor(ts: Long): File = File(dir, "audit-${dayFormat.format(Date(ts))}.jsonl")

    private fun preview(args: JsonObject): String {
        val raw = args.toString()
        return if (raw.length <= PREVIEW_LIMIT) raw else raw.take(PREVIEW_LIMIT) + "…"
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun sha256(raw: String): String =
        McpPairing.sha256(raw)

    companion object {
        const val PREVIEW_LIMIT = 200
    }
}
