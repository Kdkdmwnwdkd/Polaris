package com.beichen.polaris.tools

import com.beichen.polaris.core.build.BuildGateway
import com.beichen.polaris.core.build.BuildStage
import com.beichen.polaris.core.build.BuildTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `build_run` 的分支判定（2026-11-26）。
 *
 * 与 `MediaGenerateToolTest` 同一套路：假 [BuildGateway]，零 Android 依赖。
 *
 * 这里守的是一条**不能重试**的边界：构建会推 commit、消耗 CI 分钟数。所以
 * 「已有构建在跑」「没配好」这两种情况必须在**调用 trigger 之前**拦下——
 * 断言直接看 gateway 的 `triggerCalls`，而不是看返回码。返回码对了但已经触发了，
 * 等于用户点一次出两个包。
 */
class BuildRunToolTest {

    private class FakeBuildGateway(
        private val missing: String? = null,
        private val running: Boolean = false,
        private val triggerRefusal: String? = null,
        private val latestTask: BuildTask? = null,
    ) : BuildGateway {
        var triggerCalls = 0
            private set

        override fun missingConfig() = missing
        override fun isRunning() = running
        override fun latest() = latestTask
        override fun trigger(): String? {
            triggerCalls++
            return triggerRefusal
        }
    }

    private fun task(
        stage: BuildStage,
        runNumber: Long? = 42L,
        error: String? = null,
        apkPath: String? = null,
        sha: String? = "abcdef1234567890",
    ) = BuildTask(
        id = "b1",
        stage = stage,
        commitSha = sha,
        runNumber = runNumber,
        error = error,
        apkPath = apkPath,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun args(action: String) = JsonObject(mapOf("action" to JsonPrimitive(action)))

    @Test
    fun `没配 GitHub 时直接拒绝并说清缺哪一项`() = runBlocking {
        val gateway = FakeBuildGateway(missing = "缺 PAT")
        val r = BuildRunTool(gateway).execute(args("trigger"))
        assertEquals("not_configured", r.code)
        assertTrue("要让模型能原话说给用户", r.message.contains("缺 PAT"))
        assertEquals("不能白推一次 commit", 0, gateway.triggerCalls)
    }

    @Test
    fun `已有构建在跑时不再触发第二个包`() = runBlocking {
        val gateway = FakeBuildGateway(running = true, latestTask = task(BuildStage.BUILDING))
        val r = BuildRunTool(gateway).execute(args("trigger"))
        assertEquals("build_running", r.code)
        assertEquals(0, gateway.triggerCalls)
        assertTrue("要说清现在到哪一步", r.data!!.contains("云端构建中"))
    }

    @Test
    fun `引擎拒绝触发时把原文交回模型`() = runBlocking {
        val gateway = FakeBuildGateway(triggerRefusal = "上一个构建还没收尾，请稍候")
        val r = BuildRunTool(gateway).execute(args("trigger"))
        assertEquals("trigger_failed", r.code)
        assertTrue(r.message.contains("还没收尾"))
        assertTrue("要教模型下一步：让用户去点停止跟踪", r.hint!!.contains("停止跟踪"))
    }

    @Test
    fun `触发成功时提示这是慢操作、别在同一轮反复查`() = runBlocking {
        val gateway = FakeBuildGateway()
        val r = BuildRunTool(gateway).execute(args("trigger"))
        assertEquals("build_started", r.code)
        assertEquals(1, gateway.triggerCalls)
        assertTrue("必须告诉模型别马上查（15-25 分钟）", r.data!!.contains("不要在同一轮"))
    }

    @Test
    fun `默认动作是 trigger 而不是 status`() = runBlocking {
        val gateway = FakeBuildGateway()
        val r = BuildRunTool(gateway).execute(JsonObject(emptyMap()))
        assertEquals("build_started", r.code)
        assertEquals(1, gateway.triggerCalls)
    }

    @Test
    fun `未知 action 时返回 bad_action 不误触发构建`() = runBlocking {
        val gateway = FakeBuildGateway()
        val r = BuildRunTool(gateway).execute(args("rebuild"))
        assertEquals("bad_action", r.code)
        assertEquals(0, gateway.triggerCalls)
    }

    @Test
    fun `没有构建历史时 status 返回 build_empty`() = runBlocking {
        val r = BuildRunTool(FakeBuildGateway(latestTask = null)).execute(args("status"))
        assertEquals("build_empty", r.code)
    }

    @Test
    fun `查进度时阶段说成人话并带上构建号与短 sha`() = runBlocking {
        val r = BuildRunTool(FakeBuildGateway(latestTask = task(BuildStage.BUILDING)))
            .execute(args("status"))
        assertEquals("build_active", r.code)
        val data = r.data!!
        assertTrue(data.contains("云端构建中"))
        assertTrue(data.contains("#42"))
        assertTrue("sha 取前 8 位即可", data.contains("abcdef12"))
        assertTrue("不该把完整 sha 摊给用户", !data.contains("abcdef1234567890"))
    }

    @Test
    fun `终态时返回 build_done 并给出失败原因或 APK 路径`() = runBlocking {
        val failed = BuildRunTool(FakeBuildGateway(latestTask = task(BuildStage.FAILED, error = "QA gate 红")))
            .execute(args("status"))
        assertEquals("build_done", failed.code)
        assertTrue(failed.data!!.contains("QA gate 红"))

        val ok = BuildRunTool(
            FakeBuildGateway(latestTask = task(BuildStage.SUCCEEDED, apkPath = "/data/apk/x.apk"))
        ).execute(args("status"))
        assertEquals("build_done", ok.code)
        assertTrue(ok.data.orEmpty().contains("/data/apk/x.apk"))
    }

    @Test
    fun `每个阶段都有中文说法（模型不该把枚举名念给用户）`() {
        BuildStage.entries.forEach { stage ->
            val label = BuildRunTool.stageLabel(stage)
            assertTrue("$stage 缺中文文案", label.isNotBlank())
            assertTrue("$stage 的文案还是枚举名", label != stage.name)
        }
    }
}
