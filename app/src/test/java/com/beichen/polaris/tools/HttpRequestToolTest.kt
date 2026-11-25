package com.beichen.polaris.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * `http_request` 的成败判定（2026-11-24）。
 *
 * ## 修的是什么
 *
 * 修前 `execute` 的最后一步是 `ToolResult.ok(code = "http_${resp.code}")`——**任何**状态码
 * 都算成功。于是服务器回 403/404/500 时，工具卡写「已完成」，模型把错误页当数据继续编，
 * 用户看到的是「查到了」。真机截图里那两个「已完成」的 http_request 就可能是 500。
 *
 * 反过来「已失败」只出现一次：连响应都没拿到（DNS / 连接 / 读超时 / TLS）。
 * 这个区分本身是对的，问题是它没告诉模型「这个地址你已经撞了三回」。
 *
 * ## 为什么用拦截器而不是起本地 HTTP 服务
 *
 * 这些判据全在「拿到响应之后怎么解读」，与网络无关。拦截器直接造一个响应，测试不占端口、
 * 不依赖网络、也不会因为 runner 禁 loopback 而失败。
 */
class HttpRequestToolTest {

    /** 造一个固定返回 [code] 与 [body] 的客户端。 */
    private fun clientReturning(code: Int, body: String = ""): OkHttpClient =
        OkHttpClient.Builder()
            // 关掉连接层重试：拦截器里抛的异常不该被 OkHttp 再兜一遍，
            // 否则「调用一次」在工具眼里可能已经试了三回，计数就对不上了。
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("stub")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    /** 造一个每次都抛读超时的客户端。 */
    private fun clientTimingOut(): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor { throw SocketTimeoutException("timeout") }
            .build()

    /**
     * 可切换的客户端：同一个工具实例里先失败、再成功、再失败。
     *
     * 计数是工具实例上的状态，换成两个实例测「清零」等于什么都没验——
     * 这条要的就是**同一个实例**先撞墙再恢复。
     */
    private class FlakyClient {
        @Volatile
        var down = true

        val client: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                if (down) throw SocketTimeoutException("timeout")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("stub")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun args(
        url: String,
        method: String? = null,
        body: String? = null,
    ): JsonObject = JsonObject(
        buildMap {
            put("url", JsonPrimitive(url))
            method?.let { put("method", JsonPrimitive(it)) }
            body?.let { put("body", JsonPrimitive(it)) }
        }
    )

    /**
     * GET 配空 body 必须能发出去（2026-11-24 真机回归）。
     *
     * ## 为什么这条最重要
     *
     * schema 默认把所有 prop 标必填，模型看到 `body` 必填就给 GET 填了 `""`；
     * 而修前 `body0` 非 null 即视为「有请求体」，OkHttp 抛
     * `method GET must not have a request body`，请求一个字节都没发。
     *
     * 更糟的是它被归成「网络异常」并计入撞墙，于是第 4 次尝试后模型被告知
     * 「该地址已连续失败，可能超时/连不上」——一个纯参数问题伪装成了网络故障。
     * 真机上四次 GET 全部卡在这里，连 401 都没机会拿到。
     */
    @Test
    fun `GET 带空 body 照样能发出去`() = runBlocking {
        val r = HttpRequestTool(clientReturning(200, "{}"))
            .execute(args("https://a.com/x", method = "GET", body = ""))
        assertTrue("GET + 空 body 必须能发出去：${r.message}", r.ok)
        assertEquals("http_200", r.code)
    }

    /** 不传 method 时默认 GET，空 body 同样不该出问题。 */
    @Test
    fun `不传 method 时空 body 也不报错`() = runBlocking {
        val r = HttpRequestTool(clientReturning(200, "{}"))
            .execute(args("https://a.com/x", body = ""))
        assertTrue(r.ok)
    }

    /**
     * GET 带**非空** body：明确报参数错误，且请求真的不发出。
     *
     * 判「没发出」靠 intercept 计数，不是靠看返回值——早先的写法里 r.ok 也可能是 false，
     * 但那是因为异常，不是因为有拦截。这两种在用户那里看起来一样，在排查时完全不同。
     */
    @Test
    fun `GET 带非空 body 直接报参数错误且不发请求`() = runBlocking {
        var hits = 0
        val counting = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                hits++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("stub")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val tool = HttpRequestTool(counting)

        val r = tool.execute(args("https://a.com/x", method = "GET", body = """{"a":1}"""))
        assertFalse("GET 带 body 必须判失败", r.ok)
        assertEquals("invalid_param", r.code)
        assertEquals("请求不该被发出去", 0, hits)

        // 参数错误不该计入「撞墙」：这个地址可能一次都没真正请求过，
        // 计入就会劝模型换掉一个其实正常的源。
        val again = tool.execute(args("https://a.com/x", method = "GET", body = """{"a":2}"""))
        assertFalse(
            "参数错误不该被记成网络失败：${again.message}",
            again.message.contains("已连续失败"),
        )
    }

    /** POST 没带 body：同样提前拦下，别让 OkHttp 抛一句看不懂的英文。 */
    @Test
    fun `POST 缺 body 报参数错误`() = runBlocking {
        val r = HttpRequestTool(clientReturning(200, "{}"))
            .execute(args("https://a.com/x", method = "POST"))
        assertFalse(r.ok)
        assertEquals("invalid_param", r.code)
        assertTrue("提示要告诉它补什么：${r.hint}", r.hint.orEmpty().contains("body"))
    }

    /** POST 带 body 正常，别把合法用法一起误伤。 */
    @Test
    fun `POST 带 body 正常`() = runBlocking {
        val r = HttpRequestTool(clientReturning(200, """{"ok":1}"""))
            .execute(args("https://a.com/x", method = "POST", body = """{"q":1}"""))
        assertTrue(r.ok)
        assertEquals("http_200", r.code)
    }

    /** 非法 URL 归「参数不合法」，不归「这个地址不通」。 */
    @Test
    fun `非法 url 归参数错误`() = runBlocking {
        val r = HttpRequestTool(clientReturning(200, "{}")).execute(args("这不是地址"))
        assertFalse(r.ok)
        assertEquals("invalid_param", r.code)
    }

    @Test
    fun `2xx 才算成功`() = runBlocking {
        val r = HttpRequestTool(clientReturning(200, """{"ok":true}""")).execute(args("https://a.com/x"))
        assertTrue("200 必须判成功", r.ok)
        assertEquals("http_200", r.code)
        assertTrue("响应体要带回来", r.data.orEmpty().contains("ok"))
    }

    /**
     * 5xx 判失败，但**响应体必须保住**。
     *
     * 不少接口把真正的诊断信息放在错误体里（`{"error":"invalid city"}`）。若为了「标红」
     * 而把 data 丢掉，模型只知道「500」，只能瞎猜原因——那是把一个显示问题换成一个诊断问题。
     */
    @Test
    fun `5xx 判失败但保留响应体`() = runBlocking {
        val r = HttpRequestTool(clientReturning(500, """{"error":"boom"}"""))
            .execute(args("https://a.com/x"))
        assertFalse("500 不能再算成功", r.ok)
        assertEquals("http_500", r.code)
        assertTrue("错误响应体必须保留给模型", r.data.orEmpty().contains("boom"))
        assertTrue("5xx 的提示应指向服务端", r.hint.orEmpty().contains("服务端"))
    }

    @Test
    fun `404 判失败并指向路径`() = runBlocking {
        val r = HttpRequestTool(clientReturning(404, "not found")).execute(args("https://a.com/x"))
        assertFalse("404 不能再算成功", r.ok)
        assertEquals("http_404", r.code)
        assertTrue("404 的 message 应说资源不存在", r.message.contains("不存在"))
        assertTrue("404 的提示应指向路径/参数", r.hint.orEmpty().contains("路径"))
    }

    /**
     * 401 必须说「缺凭据」，不能说「地址不存在」。
     *
     * 真机上就是这么错的一次：请求一个正常但未鉴权的接口拿到 401，message 写
     * 「地址被拒或资源不存在」，模型据此去改 URL 路径——而真正该做的是补
     * Authorization 头，或者换一个免鉴权源。模型先读 message、后读 data，
     * 一句措辞就能把整条推理带偏。
     */
    @Test
    fun `401 说缺凭据而不是地址不存在`() = runBlocking {
        val r = HttpRequestTool(
            clientReturning(401, """{"error":{"message":"Authentication Fails","type":"authentication_error"}}""")
        ).execute(args("https://a.com/v1/models"))
        assertFalse(r.ok)
        assertEquals("http_401", r.code)
        assertTrue("401 的 message 应说凭据", r.message.contains("凭据") || r.message.contains("Authorization"))
        assertFalse("401 不能说成地址不存在", r.message.contains("不存在"))
        assertTrue("401 的提示应指向鉴权", r.hint.orEmpty().contains("鉴权"))
        assertTrue("错误响应体必须保留", r.data.orEmpty().contains("authentication_error"))
    }

    /** 403 是「认出来了但权限不够」，与 401 的补法完全不同。 */
    @Test
    fun `403 说权限不足`() = runBlocking {
        val r = HttpRequestTool(clientReturning(403, "forbidden")).execute(args("https://a.com/x"))
        assertEquals("http_403", r.code)
        assertTrue("403 的 message 应说无权/权限", r.message.contains("无权") || r.message.contains("权限"))
        assertFalse("403 不能说成地址不存在", r.message.contains("不存在"))
    }

    /** 429 是限流，立即重试只会延长冷却，提示要说清「等」。 */
    @Test
    fun `429 说限流要等`() = runBlocking {
        val r = HttpRequestTool(clientReturning(429, "slow down")).execute(args("https://a.com/x"))
        assertEquals("http_429", r.code)
        assertTrue("429 的 message 应说限流/频繁", r.message.contains("频繁") || r.message.contains("限流"))
        assertTrue("429 的提示应说等待", r.hint.orEmpty().contains("等"))
    }

    /**
     * 连续两次网络异常后，提示里必须出现「已连续失败 N 次」。
     *
     * 第一次不出现是有意的：单次超时可能只是网络抖一下，第一次就喊「换源」会让模型
     * 过早放弃一个正常的接口。这条同时守住"阈值真的生效"和"第一次不该触发"。
     */
    @Test
    fun `连续网络异常第二次起劝换源`() = runBlocking {
        val tool = HttpRequestTool(clientTimingOut())
        val first = tool.execute(args("https://slow.example.com/a"))
        assertFalse(first.ok)
        assertEquals("http_error", first.code)
        assertFalse(
            "第一次失败不该劝换源（可能只是抖一下）",
            first.hint.orEmpty().contains("已连续失败"),
        )

        val second = tool.execute(args("https://slow.example.com/b"))
        assertTrue(
            "同 host 第二次失败必须劝换源，否则模型会继续撞：${second.hint}",
            second.hint.orEmpty().contains("已连续失败 2 次"),
        )
        assertTrue("还要给出替代做法", second.hint.orEmpty().contains("shell_run"))
    }

    /** host 换了就重新算：撞不通 A 不该连累 B。 */
    @Test
    fun `换 host 后重新计数`() = runBlocking {
        val tool = HttpRequestTool(clientTimingOut())
        tool.execute(args("https://a.example.com/x"))
        val r = tool.execute(args("https://b.example.com/x"))
        assertFalse("B 第一次失败不该带 A 的账", r.hint.orEmpty().contains("已连续失败"))
    }

    /**
     * 200 之后计数清零。
     *
     * 场景：这个源早上抖过两次，中午恢复了，下午又抖一次——那时只该说「这次超时」，
     * 不该说「已连续失败 3 次」。不清零就是把历史累计当成当前状态。
     */
    @Test
    fun `成功一次后清零`() = runBlocking {
        val flaky = FlakyClient()
        val tool = HttpRequestTool(flaky.client)

        tool.execute(args("https://flaky.example.com/x"))
        val second = tool.execute(args("https://flaky.example.com/x"))
        assertTrue(second.hint.orEmpty().contains("已连续失败 2 次"))

        // 这个源恢复了
        flaky.down = false
        assertTrue(tool.execute(args("https://flaky.example.com/x")).ok)

        // 再坏一次：只该说「这次超时」，不该说「已连续失败 3 次」
        flaky.down = true
        val again = tool.execute(args("https://flaky.example.com/x"))
        assertFalse(again.ok)
        assertFalse(
            "成功之后必须清零，否则会一直挂着历史累计：${again.hint}",
            again.hint.orEmpty().contains("已连续失败"),
        )
    }
}
