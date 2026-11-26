package com.beichen.polaris.tools

import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.media.MediaGateway
import com.beichen.polaris.core.media.MediaKind
import com.beichen.polaris.core.media.MediaProviderInfo
import com.beichen.polaris.core.media.MediaRequest
import com.beichen.polaris.core.media.MediaTask
import com.beichen.polaris.core.media.Ratio
import com.beichen.polaris.core.media.TaskStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `media_generate` 的分支判定（2026-11-26）。
 *
 * ## 为什么能这样测
 *
 * 工具只依赖 [MediaGateway] 这一个接口，没有任何 Android 类型——包括缩略图，它被做成
 * 函数参数注入（默认不做）。所以这里用假 Gateway 就能走完「选 Provider / 报错 / 等结果 /
 * 超时」的全部分支，不需要真机、不需要真 Key、不会花一分钱。
 *
 * ## 守的是什么
 *
 * 不是「代码长什么样」，是**模型拿到什么话**。每条断言都看 `code`：模型就是靠它决定
 * 下一步（换个 kind、去配 Key、还是等一会儿再查）。`code` 错了，模型的行为就错了。
 */
class MediaGenerateToolTest {

    // ——— 假 Gateway ———

    private class FakeGateway(
        private val byKind: Map<MediaKind, List<MediaProviderInfo>> = mapOf(
            MediaKind.TEXT_TO_IMAGE to listOf(MediaProviderInfo("dashscope", "通义万相"))
        ),
        private val keys: Set<String> = setOf("dashscope"),
        /** 依次返回的任务状态：第一个是 submit 的返回，后续是 poll 的返回。 */
        private val script: List<MediaTask> = emptyList(),
        private val submitThrows: Throwable? = null,
        private val history: List<MediaTask> = emptyList(),
    ) : MediaGateway {
        var submits = 0
            private set
        var polls = 0
            private set

        override fun providers(kind: MediaKind) = byKind[kind].orEmpty()
        override fun hasKey(providerId: String) = providerId in keys

        override suspend fun submit(providerId: String, request: MediaRequest): MediaTask {
            submits++
            submitThrows?.let { throw it }
            return script.firstOrNull() ?: task(status = TaskStatus.SUCCEEDED, request = request)
        }

        override suspend fun poll(task: MediaTask): MediaTask {
            polls++
            // 第 n 次 poll 取 script[n]（script[0] 是提交返回），用尽则停在最后一个
            return script.getOrNull(polls) ?: script.lastOrNull() ?: task(status = TaskStatus.SUCCEEDED)
        }

        override fun recent(limit: Int) = history.take(limit)
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    /** 时钟每次读都往前走 [step] 毫秒，让「预算耗尽」在两个 tick 内发生，测试不真等。 */
    private fun tickingClock(step: Long = 1000L): () -> Long {
        var t = 0L
        return { t += step; t }
    }

    /**
     * 默认时钟每次都往前走 1 秒。
     *
     * **不能用固定 `{ 0L }`**：那会让 `clock() < deadline` 永远成立，一个假 Gateway
     * 返回 RUNNING 的用例就会死循环。递增时钟保证任何未终态任务都会在
     * `budgetMs / 1000` 次之后退出，测试照样不真等（pollIntervalMs = 1ms）。
     */
    private fun tool(
        gateway: MediaGateway,
        budgetMs: Long = 90_000L,
        clock: () -> Long = tickingClock()
    ) = MediaGenerateTool(
        gateway = gateway,
        pollIntervalMs = 1L,
        pollBudgetMs = budgetMs,
        clock = clock
    )

    // ——— 参数校验：每条都要告诉模型下一步怎么改 ———

    @Test
    fun `缺 prompt 时给出 missing_param 而不是提交空描述`() = runBlocking {
        val r = tool(FakeGateway()).execute(args("kind" to "image"))
        assertEquals("missing_param", r.code)
        assertTrue("失败必须带 hint 教模型怎么修", !r.hint.isNullOrBlank())
    }

    @Test
    fun `kind 写错时返回 bad_kind 并列出可选值`() = runBlocking {
        val r = tool(FakeGateway()).execute(args("prompt" to "猫", "kind" to "gif"))
        assertEquals("bad_kind", r.code)
        assertTrue(r.hint!!.contains("image"))
    }

    @Test
    fun `ratio 写错时返回 bad_ratio 不偷偷换成 16-9`() = runBlocking {
        val r = tool(FakeGateway()).execute(args("prompt" to "猫", "ratio" to "21:9"))
        assertEquals("bad_ratio", r.code)
    }

    @Test
    fun `图生图不带参考图时返回 missing_ref`() = runBlocking {
        val gateway = FakeGateway(
            byKind = mapOf(MediaKind.IMAGE_TO_IMAGE to listOf(MediaProviderInfo("dashscope", "通义万相")))
        )
        val r = tool(gateway).execute(args("prompt" to "改成水彩", "kind" to "image_edit"))
        assertEquals("missing_ref", r.code)
        assertEquals("不该提交", 0, gateway.submits)
    }

    @Test
    fun `没有任何 Provider 支持该类型时返回 no_provider 并指向设置页`() = runBlocking {
        val r = tool(FakeGateway(byKind = emptyMap()))
            .execute(args("prompt" to "猫", "kind" to "video"))
        assertEquals("no_provider", r.code)
        assertTrue(r.hint!!.contains("设置"))
    }

    @Test
    fun `全部 Provider 都没配 Key 时返回 no_key`() = runBlocking {
        val gateway = FakeGateway(keys = emptySet())
        val r = tool(gateway).execute(args("prompt" to "猫"))
        assertEquals("no_key", r.code)
        assertEquals("没有 Key 就不该提交", 0, gateway.submits)
    }

    @Test
    fun `指定的 Provider 不支持该类型时返回 unknown_provider 并列出可用项`() = runBlocking {
        val r = tool(FakeGateway()).execute(args("prompt" to "猫", "provider" to "volc"))
        assertEquals("unknown_provider", r.code)
        assertTrue(r.hint!!.contains("dashscope"))
    }

    @Test
    fun `指定了没配 Key 的 Provider 时返回 no_key 而不是硬提交`() = runBlocking {
        val gateway = FakeGateway(
            byKind = mapOf(
                MediaKind.TEXT_TO_IMAGE to listOf(
                    MediaProviderInfo("dashscope", "通义万相"),
                    MediaProviderInfo("openai", "OpenAI")
                )
            ),
            keys = setOf("dashscope")
        )
        val r = tool(gateway).execute(args("prompt" to "猫", "provider" to "openai"))
        assertEquals("no_key", r.code)
        assertEquals(0, gateway.submits)
    }

    // ——— 正常路径 ———

    @Test
    fun `同步 Provider 提交即成功时直接回媒体路径`() = runBlocking {
        val done = task(TaskStatus.SUCCEEDED, paths = listOf("/data/media/outputs/t1/0.png"))
        val r = tool(FakeGateway(script = listOf(done))).execute(args("prompt" to "雨夜街景", "ratio" to "16:9"))
        assertEquals("media_ok", r.code)
        assertTrue("模型要拿到路径才能回话", r.data!!.contains("/data/media/outputs/t1/0.png"))
        assertTrue(r.message.contains("通义万相"))
    }

    @Test
    fun `异步任务在预算内完成时也会等到结果`() = runBlocking {
        val running = task(TaskStatus.RUNNING, progress = 0.5f)
        val done = task(TaskStatus.SUCCEEDED, paths = listOf("/p/1.png"))
        val gateway = FakeGateway(script = listOf(running, done))
        val r = tool(gateway, clock = tickingClock()).execute(args("prompt" to "猫"))
        assertEquals("media_ok", r.code)
        assertTrue("应当真的轮询过", gateway.polls >= 1)
    }

    @Test
    fun `超出预算仍在生成时如实回 media_pending 并给任务号`() = runBlocking {
        val running = task(TaskStatus.RUNNING, id = "tx", progress = 0.2f)
        val gateway = FakeGateway(script = listOf(running))
        // 预算 10ms、每次时钟 +1s → 第一次循环前就已过期，不真等
        val r = tool(gateway, budgetMs = 10L, clock = tickingClock()).execute(args("prompt" to "猫"))
        assertEquals("media_pending", r.code)
        assertTrue(r.data!!.contains("tx"))
        assertTrue("要告诉模型别重复提交（会重复计费）", r.data.orEmpty().contains("status"))
    }

    @Test
    fun `任务失败时把 Provider 的原因原文交回模型`() = runBlocking {
        val failed = task(TaskStatus.FAILED, error = "内容审核未通过")
        val r = tool(FakeGateway(script = listOf(failed))).execute(args("prompt" to "猫"))
        assertEquals("generate_failed", r.code)
        assertTrue(r.message.contains("内容审核未通过"))
    }

    @Test
    fun `提交抛异常时返回 submit_failed 且提示不要反复重试`() = runBlocking {
        val gateway = FakeGateway(submitThrows = IllegalStateException("HTTP 401"))
        val r = tool(gateway).execute(args("prompt" to "猫"))
        assertEquals("submit_failed", r.code)
        assertTrue(r.message.contains("401"))
        assertTrue(r.hint!!.contains("不要"))
    }

    @Test
    fun `成功但没落盘文件时返回 no_asset 而不是假装成功`() = runBlocking {
        val done = task(TaskStatus.SUCCEEDED, paths = emptyList())
        val r = tool(FakeGateway(script = listOf(done))).execute(args("prompt" to "猫"))
        assertEquals("no_asset", r.code)
    }

    // ——— status / list ———

    @Test
    fun `status 查不存在的任务时返回 unknown_task 并指路 list`() = runBlocking {
        val r = tool(FakeGateway()).execute(args("action" to "status", "task_id" to "nope"))
        assertEquals("unknown_task", r.code)
        assertTrue(r.hint!!.contains("list"))
    }

    @Test
    fun `status 查未终态任务会顺手推进一次`() = runBlocking {
        val running = task(TaskStatus.RUNNING, id = "t9")
        val done = task(TaskStatus.SUCCEEDED, id = "t9", paths = listOf("/p/9.png"))
        val gateway = FakeGateway(script = listOf(running, done), history = listOf(running))
        val r = tool(gateway).execute(args("action" to "status", "task_id" to "t9"))
        assertEquals("media_ok", r.code)
        assertEquals(1, gateway.polls)
    }

    @Test
    fun `没有任何记录时 list 返回 media_empty 而不是空字符串`() = runBlocking {
        val r = tool(FakeGateway()).execute(args("action" to "list"))
        assertEquals("media_empty", r.code)
    }

    @Test
    fun `list 会把每条记录的状态说成人话`() = runBlocking {
        val gateway = FakeGateway(
            history = listOf(
                task(TaskStatus.SUCCEEDED, id = "a", paths = listOf("/p/a.png")),
                task(TaskStatus.FAILED, id = "b", error = "余额不足")
            )
        )
        val r = tool(gateway).execute(args("action" to "list"))
        assertEquals("media_list", r.code)
        assertTrue(r.data!!.contains("完成"))
        assertTrue(r.data.orEmpty().contains("余额不足"))
    }

    // ——— 参数解析（纯函数）———

    @Test
    fun `kind 与 ratio 的别名解析`() {
        assertEquals(MediaKind.TEXT_TO_IMAGE, parseMediaKind(null))
        assertEquals(MediaKind.TEXT_TO_IMAGE, parseMediaKind("t2i"))
        assertEquals(MediaKind.IMAGE_TO_IMAGE, parseMediaKind("img2img"))
        assertEquals(MediaKind.TEXT_TO_VIDEO, parseMediaKind("video"))
        assertEquals(null, parseMediaKind("gif"))

        assertEquals(Ratio.SQUARE, parseMediaRatio(null))
        assertEquals(Ratio.WIDE, parseMediaRatio("16:9"))
        assertEquals(Ratio.WIDE, parseMediaRatio("横图"))
        assertEquals(Ratio.TALL, parseMediaRatio("9：16"))
        assertEquals(null, parseMediaRatio("2:1"))
    }

    @Test
    fun `工具自声明预算比轮询预算更宽但要短于回合`() {
        val t = MediaGenerateTool(FakeGateway(), pollBudgetMs = 90_000L)
        assertEquals(120_000L, t.timeoutBudgetMs)
        assertTrue("提交会花钱，但用户已经开口了，不该再弹窗", !t.needsApproval)
    }
}

/**
 * 造一条任务记录。放顶层是为了让嵌套的假 Gateway 也能用——
 * 类内私有方法在嵌套类里调不到（Kotlin 没有隐式外部实例）。
 */
private fun task(
    status: TaskStatus,
    request: MediaRequest = MediaRequest(MediaKind.TEXT_TO_IMAGE, "猫"),
    id: String = "t1",
    paths: List<String> = emptyList(),
    error: String? = null,
    progress: Float? = null,
) = MediaTask(
    id = id,
    providerId = "dashscope",
    request = request,
    status = status,
    progress = progress,
    resultPaths = paths,
    error = error,
    createdAt = 0L,
    updatedAt = 0L
)
