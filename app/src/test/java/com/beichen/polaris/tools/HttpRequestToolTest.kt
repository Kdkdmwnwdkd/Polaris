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

    private fun args(url: String) = JsonObject(mapOf("url" to JsonPrimitive(url)))

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
    fun `4xx 判失败并指向地址或参数`() = runBlocking {
        val r = HttpRequestTool(clientReturning(404, "not found")).execute(args("https://a.com/x"))
        assertFalse("404 不能再算成功", r.ok)
        assertEquals("http_404", r.code)
        assertTrue("4xx 的提示应指向路径/参数", r.hint.orEmpty().contains("路径"))
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
