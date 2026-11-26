package com.beichen.polaris.tools

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.build.BuildGateway
import com.beichen.polaris.core.build.BuildStage
import com.beichen.polaris.core.build.BuildTask
import com.beichen.polaris.core.llm.primOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * `build_run`：触发一次构建发版，或查最近一次的进度（2026-11-26）。
 *
 * ## 为什么需要它
 *
 * 「构建」页做的事是三段：推工作区 → dispatch CI → 下载 APK。用户嘴里的说法是
 * 「把改动构建一版」——一句话。中间那些按钮（推哪个分支、要不要等）本来就不该由人一个个点。
 *
 * 接上之后，「构建」页可以退化成**只读的进度与历史**（那部分确实需要界面：APK 在哪、
 * 装不装、上一次为什么红），「发版」这个动作回对话。
 *
 * ## 边界：工具只触发，不下载也不安装
 *
 * - **不下载**：APK 几十 MB，工具里等 download 会占掉整轮预算，而且下载完也没法安装
 *   （安装要 FileProvider + 系统安装器，那是 UI 的事）。
 * - **不安装**：同上。
 * - **不取消**：取消云端 run 是「浪费已跑的算力」的语义，得人决定要不要。
 *
 * 所以 `status` 只报阶段，不催下载；出包之后让用户去构建页装。
 */
class BuildRunTool(
    private val gateway: BuildGateway
) : AgentTool {

    override val name = "build_run"

    override val description =
        "触发一次 App 构建发版（GitHub Actions：推工作区 → 跑 CI → 出 APK），或查询最近一次构建到哪一步了。" +
            "用户说「构建一版」「打个包」「发个版」「上次那个包好了吗」时用它，不要让他去点按钮。" +
            "需要先在设置里配好 GitHub 仓库与 PAT。"

    override val systemPrompt =
        "构建是慢操作（通常 15-25 分钟）：触发后立刻返回，别在同一轮里反复查。" +
            "用户问进度时用 action=status，把阶段说成人话（推送中/云端排队/构建中/下载中/成功/失败），" +
            "失败时把 error 原文带上，别自己猜原因。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop(
            "action", "string", "trigger=触发构建（默认）；status=查最近一次构建",
            enum = listOf("trigger", "status")
        )
    )

    override val readOnly = false

    /** 触发只是三次 HTTP（push 的那几次另说）：给 60 秒，超时说明网络有问题而不是构建慢。 */
    override val timeoutBudgetMs: Long = 60_000L

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase().orEmpty()
        return when (action.ifBlank { "trigger" }) {
            "trigger" -> trigger()
            "status" -> status()
            else -> ToolResult.fail(
                "bad_action",
                "未知 action：$action",
                "action 只接受 trigger / status 两者之一。"
            )
        }
    }

    private fun trigger(): ToolResult {
        gateway.missingConfig()?.let { missing ->
            return ToolResult.fail(
                "not_configured",
                "GitHub 还没配好：$missing",
                "让用户去「设置 → 构建」填 owner / repo / PAT（PAT 需要 repo 写权限），填完再触发。" +
                    "没有配好之前不要尝试别的办法绕过。"
            )
        }
        if (gateway.isRunning()) {
            val latest = gateway.latest()
            return ToolResult.ok(
                code = "build_running",
                message = "已有构建在跑，这次没有重复触发。",
                data = latest?.let { describe(it) } ?: "当前阶段未知，让用户去构建页看一眼。"
            )
        }
        val refused = gateway.trigger()
        if (refused != null) {
            return ToolResult.fail(
                "trigger_failed",
                "没能触发构建：$refused",
                "如果是「上一个构建还没收尾」，让用户去构建页点「停止跟踪」再触发（只停本地跟踪，云端 run 会自己跑完）。" +
                    "其他原因把原文告诉用户。"
            )
        }
        return ToolResult.ok(
            code = "build_started",
            message = "已触发构建，正在把工作区推成 commit。整个过程通常 15-25 分钟。",
            data = "下一步：过几分钟用 build_run(action=status) 查进度；出包后让用户去构建页下载安装。" +
                "不要在同一轮里连续查。"
        )
    }

    private fun status(): ToolResult {
        val task = gateway.latest()
            ?: return ToolResult.ok(
                code = "build_empty",
                message = "还没有构建记录。",
                data = "可以 build_run(action=trigger) 触发第一次构建。"
            )
        return ToolResult.ok(
            code = if (task.isTerminal) "build_done" else "build_active",
            message = describe(task).lineSequence().first(),
            data = describe(task)
        )
    }

    /** 阶段说成人话：`BUILDING` 对用户没有意义，「云端构建中」才有。 */
    private fun describe(t: BuildTask): String = buildString {
        appendLine("阶段：${stageLabel(t.stage)}")
        t.runNumber?.let { appendLine("构建号：#$it") }
        t.commitSha?.let { appendLine("commit：${it.take(8)}") }
        t.downloadProgress?.let { appendLine("下载进度：${(it * 100).toInt()}%") }
        t.error?.let { appendLine("失败原因：$it") }
        t.apkPath?.let { appendLine("APK：$it") }
        t.sizeBytes?.takeIf { it > 0 }?.let { appendLine("大小：${it / 1024 / 1024} MB") }
        append("开始于：").append(formatTime(t.createdAt))
    }

    private fun formatTime(ms: Long): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        return fmt.format(java.util.Date(ms))
    }

    companion object {
        fun stageLabel(stage: BuildStage): String = when (stage) {
            BuildStage.PUSHING -> "推送工作区中"
            BuildStage.QUEUED -> "云端排队中"
            BuildStage.BUILDING -> "云端构建中"
            BuildStage.DOWNLOADING -> "下载 APK 中"
            BuildStage.SUCCEEDED -> "成功，APK 已就绪"
            BuildStage.FAILED -> "失败"
            BuildStage.CANCELLED -> "已停止跟踪（云端可能仍在跑）"
        }
    }
}
