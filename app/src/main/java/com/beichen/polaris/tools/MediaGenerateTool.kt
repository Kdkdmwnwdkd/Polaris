package com.beichen.polaris.tools

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolPart
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.media.MediaGateway
import com.beichen.polaris.core.media.MediaKind
import com.beichen.polaris.core.media.MediaRequest
import com.beichen.polaris.core.media.MediaTask
import com.beichen.polaris.core.media.Ratio
import com.beichen.polaris.core.media.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * `media_generate`：文生图 / 图生图 / 文生视频（2026-11-26）。
 *
 * ## 为什么需要它
 *
 * 生成能力本来就存在（`MediaRegistry` + 四家 Provider），但唯一入口是「创作工坊」那张
 * 表单：先选 kinds、再选比例、再选 Provider、再点按钮。用户真正想说的是
 * 「画一张 16:9 的雨夜街景」，而不是操作四个下拉框。表单把一句自然语言拆成了
 * 四次点击，这一步翻译本来该由模型做。
 *
 * 接上这个工具之后，那张表单的去留才有讨论空间——**工具在，界面才可以删**；
 * 反过来先删界面就是删功能（这是 11-26 那次判断里的漏洞）。
 *
 * ## 三个动作
 *
 * | action | 干什么 | 典型台词 |
 * |---|---|---|
 * | `generate`（默认） | 提交任务并等结果 | 「画一张猫」 |
 * | `status` | 查某个任务现在怎么样 | 「刚那张好了吗」 |
 * | `list` | 看最近生成过什么 | 「我之前让你画过哪些」 |
 *
 * ## 为什么要在工具里等
 *
 * 同步 Provider（OpenAI images）提交即出图，异步的（DashScope 视频 / 火山 / MiniMax）
 * 要几十秒到几分钟。等一会儿是值得的——模型拿到路径当轮就能回话；但**不能等到回合超时**，
 * 所以有 [pollBudgetMs] 上限：到点就如实说「还在跑，任务号 X」，让用户下一轮再问。
 * 这比伪装成功或假装失败都好。
 *
 * ## 测试面
 *
 * 工具只依赖 [MediaGateway] 与两个可注入的量（[clock] / [thumbnail]），
 * 没有任何 Android 类型——`app/src/test` 里能用一个假 gateway 跑完所有分支。
 * 缩略图是唯一的 Android 操作，做成函数参数注入（装配期传真实实现，测试传 null）。
 */
class MediaGenerateTool(
    private val gateway: MediaGateway,
    private val pollIntervalMs: Long = 3_000L,
    private val pollBudgetMs: Long = DEFAULT_POLL_BUDGET_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 本地图片 → data URL；返回 null 表示不做缩略图（测试默认如此）。 */
    private val thumbnail: (File) -> String? = { null },
) : AgentTool {

    override val name = "media_generate"

    override val description =
        "生成图片或视频：文生图、图生图（带参考图）、文生视频。用户说「画一张…」「生成个视频」" +
            "「把这张图改成…」时用它，不要让他去填表单。需要已配置媒体 Provider 的 API Key。" +
            "另可查任务状态（action=status）和最近生成记录（action=list）。"

    override val systemPrompt =
        "生成媒体的三条经验：① prompt 要具体（主体、场景、光线、风格），别把用户原话原样丢进去；" +
            "② 用户没说比例时默认 1:1，横图说 16:9、竖图说 9:16；" +
            "③ 异步任务可能几分钟才出结果，工具返回 media_pending 时**如实告诉用户还在生成**，" +
            "不要重试提交（会重复计费），用户可以稍后让你 action=status 查。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop(
            "action", "string", "generate=生成（默认）；status=查任务；list=看最近记录",
            enum = listOf("generate", "status", "list")
        ),
        JsonSchema.prop("prompt", "string", "画面描述（action=generate 必填）。写具体：主体 + 场景 + 光线 + 风格"),
        JsonSchema.prop(
            "kind", "string", "image=文生图（默认）；image_edit=图生图；video=文生视频",
            enum = listOf("image", "image_edit", "video")
        ),
        JsonSchema.prop(
            "ratio", "string", "画面比例，默认 1:1",
            enum = listOf("1:1", "16:9", "9:16", "4:3")
        ),
        JsonSchema.prop("provider", "string", "指定 Provider（dashscope / openai / volc / minimax）；不填则用已配 Key 的第一个"),
        JsonSchema.prop("ref_image", "string", "参考图（kind=image_edit 必填）：http(s) 地址或 data:image/...;base64,..."),
        JsonSchema.prop("task_id", "string", "任务 id（action=status 必填）"),
        JsonSchema.prop("limit", "integer", "action=list 返回几条，默认 5，上限 20")
    )

    /** 提交会花钱，但不需要弹窗——用户自己说的「画一张」，再问一遍就成障碍了。 */
    override val readOnly = false

    /** 等出图：比只读的 90 秒宽，但仍短于回合软截止，避免整轮被一个视频拖死。 */
    override val timeoutBudgetMs: Long = pollBudgetMs + 30_000L

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase().orEmpty()
        return when (action.ifBlank { "generate" }) {
            "generate" -> generate(args)
            "status" -> status(args)
            "list" -> list(args)
            else -> ToolResult.fail(
                "bad_action",
                "未知 action：$action",
                "action 只接受 generate / status / list 三者之一。"
            )
        }
    }

    private suspend fun generate(args: JsonObject): ToolResult {
        val prompt = args["prompt"]?.primOrNull()?.contentOrNull?.trim().orEmpty()
        if (prompt.isBlank()) {
            return ToolResult.fail(
                "missing_param",
                "缺少 prompt。",
                "把用户要的画面写成一句具体描述再调，例如「雨夜街头，霓虹倒影，16:9」。"
            )
        }
        val kindRaw = args["kind"]?.primOrNull()?.contentOrNull
        val kind = parseMediaKind(kindRaw) ?: return ToolResult.fail(
            "bad_kind",
            "kind 只接受 image / image_edit / video，收到「${kindRaw.orEmpty()}」。",
            "用户说「画图」用 image，「改这张图」用 image_edit，「生成视频」用 video。"
        )
        val ratioRaw = args["ratio"]?.primOrNull()?.contentOrNull
        val ratio = parseMediaRatio(ratioRaw) ?: return ToolResult.fail(
            "bad_ratio",
            "ratio 只接受 1:1 / 16:9 / 9:16 / 4:3，收到「${ratioRaw.orEmpty()}」。",
            "用户没说比例就用 1:1；横版 16:9、竖版 9:16。"
        )
        val refImage = args["ref_image"]?.primOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        if (kind == MediaKind.IMAGE_TO_IMAGE && refImage == null) {
            return ToolResult.fail(
                "missing_ref",
                "图生图需要参考图。",
                "ref_image 传 http(s) 地址，或 data:image/...;base64,... 的 data URI。" +
                    "用户只给了本地路径时，先用 file_read 确认文件存在，读成 base64 再传；做不到就改用文生图。"
            )
        }

        val available = gateway.providers(kind)
        if (available.isEmpty()) {
            return ToolResult.fail(
                "no_provider",
                "没有支持${kindLabel(kind)}的 Provider。",
                "让用户去「设置 → 创作工坊」填一个媒体 Provider 的 API Key（DashScope / OpenAI / 火山 / MiniMax 任一），" +
                    "填完再重试。不要改用别的 kind 硬凑。"
            )
        }
        val wantId = args["provider"]?.primOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val chosen = if (wantId != null) {
            available.firstOrNull { it.id == wantId } ?: return ToolResult.fail(
                "unknown_provider",
                "Provider「$wantId」不支持${kindLabel(kind)}。",
                "可用的有：${available.joinToString { "${it.id}（${it.displayName}）" }}。"
            )
        } else {
            available.firstOrNull { gateway.hasKey(it.id) } ?: return ToolResult.fail(
                "no_key",
                "支持${kindLabel(kind)}的 Provider 都还没配 API Key。",
                "让用户去「设置 → 创作工坊」任填一个的 Key：${available.joinToString { it.displayName }}。"
            )
        }
        if (!gateway.hasKey(chosen.id)) {
            val ready = available.filter { gateway.hasKey(it.id) }
            return ToolResult.fail(
                "no_key",
                "${chosen.displayName} 还没填 API Key。",
                if (ready.isEmpty()) "让用户去「设置 → 创作工坊」填 ${chosen.displayName} 的 Key。"
                else "改用已配 Key 的：${ready.joinToString { it.id }}，或让用户去填 ${chosen.displayName} 的 Key。"
            )
        }

        val request = MediaRequest(
            kind = kind,
            prompt = prompt,
            ratio = ratio,
            refImageUris = listOfNotNull(refImage)
        )
        var task = try {
            gateway.submit(chosen.id, request)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            return ToolResult.fail(
                "submit_failed",
                "提交失败：${e.message ?: e::class.simpleName ?: "未知原因"}",
                "常见原因是 Key 失效、余额不足或参数不被该 Provider 接受。" +
                    "把失败原文告诉用户，不要自己反复重试（每次都算一次调用）。"
            )
        }

        // 等结果：同步 Provider 提交即终态，这里不进循环
        val deadline = clock() + pollBudgetMs
        while (task.isActive && clock() < deadline) {
            delay(pollIntervalMs)
            task = try {
                gateway.poll(task)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // 轮询失败（网络抖动）不是任务失败：保持原状态，继续等
                task
            }
        }

        return when (task.status) {
            TaskStatus.SUCCEEDED -> successResult(task, chosen.displayName)
            TaskStatus.FAILED -> ToolResult.fail(
                "generate_failed",
                "生成失败：${task.error ?: "Provider 没有给出原因"}",
                "把原因原样告诉用户。别立刻重试同一句 prompt——先判断是内容被拒（改描述）、" +
                    "Key/余额问题（引导去设置）、还是超时（稍后再试）。"
            )
            else -> ToolResult.ok(
                code = "media_pending",
                message = "已提交（任务 ${task.id}，${chosen.displayName}，${kindLabel(kind)}），" +
                    "${pollBudgetMs / 1000} 秒内还没出结果——这类任务常常要几分钟。",
                data = "task_id=${task.id}\nstatus=${task.status.name.lowercase()}\n" +
                    (task.progress?.let { "progress=${(it * 100).toInt()}%\n" } ?: "") +
                    "下一步：稍后用 media_generate(action=status, task_id=${task.id}) 查结果。"
            )
        }
    }

    private fun successResult(task: MediaTask, providerName: String): ToolResult {
        if (task.resultPaths.isEmpty()) {
            return ToolResult.fail(
                "no_asset",
                "生成成功但没有落盘文件（任务 ${task.id}）。",
                "这是下载环节的问题，不是生成失败。让用户去作品列表看一眼；仍未出现就把这条任务 id 报给用户，别再自动重试。"
            )
        }
        val data = buildString {
            appendLine("task_id=${task.id}")
            appendLine("provider=$providerName")
            appendLine("kind=${kindLabel(task.request.kind)}")
            appendLine("ratio=${task.request.ratio.label}")
            task.resultPaths.forEachIndexed { i, p -> appendLine("file${i + 1}=$p") }
        }.trimEnd()
        val parts = task.resultPaths.firstOrNull()
            ?.let { thumbnail(File(it)) }
            ?.let { listOf(ToolPart.Image(it, "生成结果")) }
            .orEmpty()
        return ToolResult.ok(
            code = "media_ok",
            message = "已生成 ${task.resultPaths.size} 个文件（${providerName}）：${task.resultPaths.first()}",
            data = data,
            parts = parts
        )
    }

    private suspend fun status(args: JsonObject): ToolResult {
        val id = args["task_id"]?.primOrNull()?.contentOrNull?.trim().orEmpty()
        if (id.isBlank()) {
            return ToolResult.fail(
                "missing_param",
                "缺少 task_id。",
                "用 media_generate(action=list) 看最近的任务 id，再按 id 查。"
            )
        }
        val known = gateway.recent(MAX_HISTORY).firstOrNull { it.id == id }
            ?: return ToolResult.fail(
                "unknown_task",
                "找不到任务 $id。",
                "可能 id 写错了或记录已被清理。用 action=list 看现有任务。"
            )
        // 未终态就顺手推一把：用户问「好了吗」的时候正是该查的时候
        val task = if (known.isActive) {
            try {
                gateway.poll(known)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                known
            }
        } else {
            known
        }
        if (task.status == TaskStatus.SUCCEEDED) return successResult(task, task.providerId)
        val text = "task_id=${task.id}\nstatus=${task.status.name.lowercase()}\n" +
            (task.progress?.let { "progress=${(it * 100).toInt()}%\n" } ?: "") +
            (task.error?.let { "error=$it\n" } ?: "") +
            "prompt=${task.request.prompt}"
        return ToolResult.ok(
            code = if (task.isActive) "media_pending" else "media_failed",
            message = if (task.isActive) "任务 ${task.id} 还在生成中。"
            else "任务 ${task.id} 已失败：${task.error ?: "未给原因"}",
            data = text.trimEnd()
        )
    }

    private fun list(args: JsonObject): ToolResult {
        val limit = (args["limit"]?.primOrNull()?.contentOrNull?.toIntOrNull() ?: 5).coerceIn(1, MAX_HISTORY)
        val tasks = gateway.recent(limit)
        if (tasks.isEmpty()) {
            return ToolResult.ok(
                code = "media_empty",
                message = "还没有生成过任何媒体。",
                data = "可以直接 media_generate(action=generate) 生成第一张。"
            )
        }
        val lines = tasks.mapIndexed { i, t ->
            val state = when (t.status) {
                TaskStatus.SUCCEEDED -> "完成 ${t.resultPaths.firstOrNull() ?: "（无文件）"}"
                TaskStatus.FAILED -> "失败 ${t.error ?: ""}"
                TaskStatus.RUNNING -> "生成中 ${t.progress?.let { "${(it * 100).toInt()}%" } ?: ""}"
                TaskStatus.SUBMITTED -> "已提交"
            }
            "${i + 1}. ${t.id} · ${kindLabel(t.request.kind)} · ${t.request.prompt.take(28)} · $state"
        }
        return ToolResult.ok(
            code = "media_list",
            message = "最近 ${tasks.size} 条生成记录：",
            data = lines.joinToString("\n")
        )
    }

    companion object {
        /** 轮询预算：图片通常 10-40 秒，视频常常 2-5 分钟。90 秒是「够等到图、不等瞎视频」的分界。 */
        const val DEFAULT_POLL_BUDGET_MS = 90_000L
        private const val MAX_HISTORY = 20

        fun kindLabel(kind: MediaKind): String = when (kind) {
            MediaKind.TEXT_TO_IMAGE -> "文生图"
            MediaKind.IMAGE_TO_IMAGE -> "图生图"
            MediaKind.TEXT_TO_VIDEO -> "文生视频"
        }
    }
}

/**
 * `kind` 参数解析。别名收得宽一点：模型偶尔会说 `text2image` / `t2i` / `img2img`，
 * 为几个别名多一轮往返不值得。
 *
 * 空值 → 默认文生图（不返回 null），这是最常见的场景。
 */
fun parseMediaKind(raw: String?): MediaKind? {
    val v = raw?.trim()?.lowercase().orEmpty()
    if (v.isBlank() || v == "image" || v == "text_to_image" || v == "text2image" || v == "t2i") {
        return MediaKind.TEXT_TO_IMAGE
    }
    return when (v) {
        "image_edit", "image_to_image", "img2img", "edit" -> MediaKind.IMAGE_TO_IMAGE
        "video", "text_to_video", "text2video", "t2v" -> MediaKind.TEXT_TO_VIDEO
        else -> null
    }
}

/** `ratio` 参数解析。空值 → 1:1；认不出来的返回 null（让模型自己纠正，不偷偷换成 16:9）。 */
fun parseMediaRatio(raw: String?): Ratio? {
    val v = raw?.trim()?.lowercase().orEmpty().replace("：", ":").replace(" ", "")
    if (v.isBlank()) return Ratio.SQUARE
    return when (v) {
        "1:1", "square", "方图" -> Ratio.SQUARE
        "16:9", "wide", "横", "横图" -> Ratio.WIDE
        "9:16", "tall", "竖", "竖图" -> Ratio.TALL
        "4:3", "classic" -> Ratio.CLASSIC
        else -> null
    }
}
