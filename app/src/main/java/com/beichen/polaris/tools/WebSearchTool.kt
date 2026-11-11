package com.beichen.polaris.tools

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.net.BoundedBody
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网页搜索（W-20，W-22 修引擎可用性）：缺口清单 #53，四家均有。
 *
 * ## 双引擎（为什么以 Bing 为主）
 * 原实现只走 DuckDuckGo HTML 端点。**沙箱实测 DDG 直连 20 秒超时**——用户设备在国内，
 * 大概率完全不可用，等于工具白做。改为：
 *  1. **Bing（cn.bing.com）**：实测 HTTP 200 / 61KB / 5 条结果，国内可达；
 *  2. **DuckDuckGo**：海外/代理环境兜底。
 * 两者解析逻辑都在 [WebSearchParsing]（纯函数，harness 用真实页面结构验证）。
 *
 * 不让模型自己用 http_request 抓：HTML 里 95% 是导航/脚本噪音，回灌上下文既浪费又易读错。
 *
 * 只读：搜索查询无副作用，可与其他只读工具并行。
 */
class WebSearchTool(
    private val client: OkHttpClient = LlmHttp.short
) : AgentTool {

    override val name = "web_search"
    override val description =
        "联网搜索公开网页，返回最多 8 条结果（标题 + 链接 + 摘要）。" +
            "需要查实时信息、新闻、价格、文档、陌生概念时使用；" +
            "搜索不到时换关键词再试，或改用 web_open 打开具体网页。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("query", "string", "搜索关键词（中文或英文均可，尽量具体）"),
        JsonSchema.prop("max_results", "integer", "返回结果条数，默认 5，最多 8")
    )

    /** 只读：搜索查询无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val query = args["query"]?.primOrNull()?.contentOrNull?.trim()
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 query",
                    hint = "web_search 必须提供 query（搜索关键词），请补充后重试。"
                )
            if (query.isEmpty()) {
                return@withContext ToolResult.fail(
                    code = "empty_query",
                    message = "query 不能为空",
                    hint = "请给出具体关键词，如「深圳天气」或「kotlin coroutines 教程」。"
                )
            }
            val max = (args["max_results"]?.primOrNull()?.contentOrNull?.toIntOrNull() ?: 5)
                .coerceIn(1, 8)

            // 引擎顺序：Bing 优先（国内可达），DDG 兜底（海外/代理）
            val failures = mutableListOf<String>()
            for (engine in ENGINES) {
                val items = runCatching { engine.search(query, max) }
                    .onFailure { failures += "${engine.name}：${it.message?.take(80) ?: "失败"}" }
                    .getOrDefault(emptyList())
                if (items.isNotEmpty()) {
                    return@withContext ToolResult.ok(
                        code = "search_ok",
                        message = "「$query」搜到 ${items.size} 条结果（${engine.name}）",
                        data = render(items)
                    )
                }
            }

            // 两个引擎都没拿到结果：区分「网络不通」与「确实没有」
            val allFailed = failures.size == ENGINES.size
            if (allFailed) {
                ToolResult.fail(
                    code = "network_error",
                    message = "搜索服务不可达（${failures.joinToString("；")}）",
                    hint = "检查网络连接；若在受限网络下可稍后重试，或改用 web_open 直接打开已知网址。"
                )
            } else {
                ToolResult.fail(
                    code = "no_results",
                    message = "没有搜到「$query」的相关结果",
                    hint = "换更短或更常用的关键词再试；查具体站点内容可用 web_open 直接打开网址。"
                )
            }
        }.getOrElse {
            ToolResult.fail(
                code = "network_error",
                message = "搜索请求异常：${it.message}",
                hint = "检查网络连接；持续失败可改用 http_request 抓取具体网页。"
            )
        }
    }

    private fun render(items: List<WebSearchParsing.SearchItem>): String = buildString {
        items.forEachIndexed { i, r ->
            append("${i + 1}. ${r.title}\n")
            append("   ${r.url}\n")
            if (r.snippet.isNotBlank()) append("   ${r.snippet}\n")
            append("\n")
        }
    }.trim()

    /** 一个搜索引擎：名称 + 检索函数（便于引擎顺序显式可读）。 */
    private class Engine(
        val name: String,
        val search: (String, Int) -> List<WebSearchParsing.SearchItem>
    )

    /** Bing：GET `cn.bing.com/search?q=…`（国内可达，实测稳定）。 */
    private fun searchBing(query: String, max: Int): List<WebSearchParsing.SearchItem> {
        val url = "$BING_ENDPOINT?q=${java.net.URLEncoder.encode(query, "UTF-8")}&setlang=zh-Hans"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("HTTP ${resp.code}")
            return WebSearchParsing.parseBing(htmlOf(resp), max)
        }
    }

    /** DuckDuckGo：POST `html.duckduckgo.com/html/`（海外/代理环境兜底）。 */
    private fun searchDdg(query: String, max: Int): List<WebSearchParsing.SearchItem> {
        val form = "q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url(DDG_ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .post(form)
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("HTTP ${resp.code}")
            return WebSearchParsing.parse(htmlOf(resp), max)
        }
    }

    /**
     * F21：响应体限读。
     *
     * 搜索页 HTML 通常 100~500KB，但 `body.string()` 会**整份**进堆——遇到一个 50MB 的
     * 错误页（网关返回的 HTML、被劫持的 DNS、故意喂大包的对端）就是一次 OOM。
     * 上限的作用点必须在读的过程里，这是 F19 / F21 同一句话。
     */
    private fun htmlOf(resp: Response): String =
        resp.body?.byteStream()?.let { BoundedBody.read(it) }?.text.orEmpty()

    private val ENGINES: List<Engine>
        get() = listOf(
            Engine("Bing") { q, m -> searchBing(q, m) },
            Engine("DuckDuckGo") { q, m -> searchDdg(q, m) }
        )

    companion object {
        private const val BING_ENDPOINT = "https://cn.bing.com/search"
        private const val DDG_ENDPOINT = "https://html.duckduckgo.com/html/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
