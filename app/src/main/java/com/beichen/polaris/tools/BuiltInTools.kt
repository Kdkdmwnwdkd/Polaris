package com.beichen.polaris.tools

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.TextEncoding
import com.beichen.polaris.core.TextFileSlice
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.io.AtomicJsonFile
import com.beichen.polaris.core.net.RedirectPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/*
 * T02：全部工具改返回统一契约 ToolResult{ok, code, message, hint, data}——
 * 成功用 ToolResult.ok(...)，失败用 ToolResult.fail(code, message, hint)（hint 必填，
 * 教模型下一步该调什么）。回喂文本统一由 core/formatToolResult 生成，UI 与模型历史同源。
 *
 * T03：readOnly 逐项标注——本批任一工具非只读即整批串行，全批只读才并行
 * （coomi A2 has_mutation 思路）。5 个内置工具标注清单：
 *   http_request=true / read_file=true / write_file=false / clipboard=true / send_notification=false
 */

/* ------------------------------------------------------------------ */
/* 1. http_request                                                     */
/* ------------------------------------------------------------------ */
class HttpRequestTool(
    // R-13：原来裸构造（OkHttp 默认 10s 读超时），并入 short（10/30s，只放宽）
    //
    // W-54：**不再让 OkHttp 自己跟随重定向**。审批按域名记忆（`ApprovalScope.of`），
    // 而自动跟随会让「批过 api.example.com」变成「批过第一个域名」——302 一次就能
    // 把数据带到别处，用户全程只看见过一个域名。现在关掉自动跟随，逐跳交给
    // [RedirectPolicy] 判定：跨域 / 跨端口 / https 降级 / 超跳数一律停下并如实报告，
    // 由模型或脚本对**新地址**重新发起（那就是一次新的、用户可见的授权）。
    private val client: OkHttpClient = LlmHttp.short.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) : AgentTool {
    override val name = "http_request"
    override val description =
        "向指定 URL 发起 HTTP 请求，返回状态码与响应体（自动截断到前 8000 字符）。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("method", "string", "HTTP 方法", enum = listOf("GET", "POST", "PUT", "DELETE", "PATCH")),
        JsonSchema.prop("url", "string", "请求目标地址"),
        JsonSchema.prop("headers", "object", "请求头，键值均为字符串（可选）"),
        JsonSchema.prop("body", "string", "请求体，通常与 POST/PUT 配合使用（可选）")
    )

    /**
     * W-46：**不再标只读**。
     *
     * 原标注的理由是「GET 无副作用，POST 由调用方自负」，但 [readOnly] 不是给人看的注释，
     * 它是 `ToolRegistry.batchHasMutation` 的唯一判据：一批里只要全是 readOnly=true 就并发执行。
     * 而本工具支持 POST/PUT/PATCH/DELETE——模型同一批发 `[POST /order, GET /order]` 时，
     * 两个协程会真并发跑，读到写前还是写后完全随机；同时它也是无审批的外发通道，
     * 标成只读等于把它藏进了「安全工具」那一档。
     *
     * 代价：任何含 http_request 的批次改为串行。HTTP 往返本就不是本 App 的性能瓶颈，
     * 换取「副作用顺序确定」值得。
     */
    override val readOnly = false

    /**
     * 审计 F1：这条通道能把**任意内容**发到**任意地址**，而它此前完全免审批。
     * 单独看它不是漏洞（发请求是正常能力），但和「只读 shell 免审批」串起来，
     * 就是「读设备文件 → POST 到外部」的零弹窗外泄链。
     *
     * 审批粒度按域名记（见 `ApprovalScope.of`）：同一个域名只问一次，换域名重问——
     * 正常查资料不会被拖成十几次弹窗，把数据发去新域名则必须过一次人。
     */
    override val needsApproval = true

    /**
     * R6：HTTP 往返的网络等待，给 90 秒。
     *
     * 本工具因支持 POST/PUT 被标为**写操作**（见上方 W-46 说明），因而吃 600s 的写预算——
     * 但一次 HTTP 请求不管什么方法都不该跑十分钟。90 秒足够覆盖慢网络与重定向，
     * 再长就是黑洞（连不上却不断重试），早点超时早反馈。
     */
    override val timeoutBudgetMs: Long = 90_000L

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val method0 = args["method"]?.primOrNull()?.contentOrNull?.trim()?.uppercase().orEmpty().ifBlank { "GET" }
            val url = args["url"]?.primOrNull()?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 url",
                    hint = "http_request 必须提供 url 参数（如 https://example.com/api），请补充后重试。"
                )
            val body0 = args["body"]?.primOrNull()?.contentOrNull
            val headers = buildList {
                (args["headers"] as? JsonObject)?.forEach { (k, v) ->
                    v.primOrNull()?.contentOrNull?.let { add(k to it) }
                }
            }

            var currentUrl = url
            var method = method0
            var bodyText = body0
            var hop = 0

            while (true) {
                val builder = Request.Builder().url(currentUrl)
                headers.forEach { (k, v) -> builder.addHeader(k, v) }
                val request = if (bodyText != null) {
                    builder.method(method, bodyText.toRequestBody("application/json".toMediaType())).build()
                } else {
                    builder.method(method, null).build()
                }

                val resp = client.newCall(request).execute()
                try {
                    if (resp.isRedirect) {
                        when (val d = RedirectPolicy.decide(currentUrl, resp.header("Location"), hop, resp.code, method)) {
                            is RedirectPolicy.Redirect.Follow -> {
                                currentUrl = d.to
                                method = d.method
                                if (d.dropBody) bodyText = null
                                hop++
                                continue
                            }
                            is RedirectPolicy.Redirect.Stop -> return@withContext ToolResult.fail(
                                code = "http_redirect_stopped",
                                message = "⛔ ${d.reason}",
                                hint = if (d.target != null) {
                                    "若确实需要该地址，请对 ${d.target} 单独发起一次 http_request" +
                                        "（会重新走审批）。"
                                } else {
                                    "改用最终地址直接请求，或检查服务端重定向配置。"
                                }
                            )
                        }
                    }

                    val text = resp.body?.string().orEmpty()
                    val out = if (text.length > 8000) text.take(8000) + "\n...（已截断）" else text
                    return@withContext ToolResult.ok(
                        code = "http_${resp.code}",
                        message = if (hop > 0) "HTTP ${resp.code}（跟随 $hop 次同源跳转）" else "HTTP ${resp.code}",
                        data = out
                    )
                } finally {
                    resp.close()
                }
            }
            // 循环的每个出口都已经 return；这一行只为让 lambda 的类型是 ToolResult 而不是 Unit。
            error("unreachable: 请求循环必然以 return 结束")
        }.getOrElse {
            ToolResult.fail(
                code = "http_error",
                message = "HTTP 请求异常：${it.message}",
                hint = "检查 URL 是否可达、网络是否正常；必要时更换方法或稍后重试。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 2 & 3. read_file / write_file（限制在 workspace 沙箱内，防穿越）      */
/* ------------------------------------------------------------------ */
abstract class FileTool(protected val workspace: File) : AgentTool {
    init { if (!workspace.exists()) workspace.mkdirs() }

    protected fun resolve(rel: String): File {
        val f = File(workspace, rel)
        // 末尾必须带分隔符：只比前缀会把 /…/agent_workspace_evil 当成工作区内路径
        require(f.canonicalPath.startsWith(workspace.canonicalPath + File.separator)) { "非法路径：$rel" }
        return f
    }
}

/**
 * 写前自动备份钩子（B 批）。
 *
 * 抽成接口而不是直接依赖 [com.beichen.polaris.core.workspace.FileBackupStore]，
 * 是为了让工具层保持零 Android 依赖（core/tools 的工具都可在 JVM harness 里裸测），
 * 同时让「要不要备份」成为注入决策而非硬编码。
 */
fun interface WriteBackupHook {
    /** 写入 [relPath] 之前调用；返回备份 id（失败可为 null）。 */
    fun backupBeforeWrite(relPath: String): String?
}

/**
 * 原子写（W-20）：先写同目录临时文件再 rename，避免写一半崩溃把原文件写坏。
 *
 * 收敛到 [AtomicJsonFile] 后多了 `fd.sync()`：写用户文件同样怕「rename 了、内容还是 0」。
 * 保留 `.akitmp` 后缀——`WorkspaceStore` 的文件列表按它跳过半成品，换后缀会让
 * 崩溃残留的临时文件出现在用户工作区里。
 */
internal fun File.atomicWriteText(text: String) {
    AtomicJsonFile.write(this, text, keepBackup = false, tmpSuffix = ".akitmp")
}

/**
 * 分段读取（默认限制约 64KB，支持按 offset 分批续读，
 * 避免一次性把大文件灌爆上下文。
 *
 * T06：回喂文本最终还会经 AgentLoop 的 truncateToolResult 独立截断到 24K 并带分页提示，
 * 双保险。
 */
class ReadFileTool(workspace: File) : FileTool(workspace) {
    override val name = "read_file"
    // 描述与参数文案**一字不动**：默认工具面预算已经顶到 525/525（gate.sh 第 2 道），
    // 多写一句「按页读」就要从别处砍 token。它们是给模型看的说明书，不是变更日志——
    // 行为改在实现里（TextFileSlice），说明书不必跟着改。
    override val description =
        "读取工作区内（沙箱）的文本文件。支持分段读取：offset 跳过字符数，limit 限制读取长度（默认 65536，约 64KB），超长文件可分批续读。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "相对于工作区的文件路径"),
        JsonSchema.prop("offset", "integer", "跳过的字符数，默认 0，用于分批读取大文件"),
        JsonSchema.prop("limit", "integer", "本次读取的最大字符数（默认 65536），超过则截断")
    )

    /** 只读：仅读取沙箱内文件，无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = args["path"]?.primOrNull()?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 path",
                    hint = "read_file 必须提供 path（相对于工作区的文件路径），请补充后重试。"
                )
            val offset = args["offset"]?.primOrNull()?.contentOrNull?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = args["limit"]?.primOrNull()?.contentOrNull?.toIntOrNull()
                ?: TextFileSlice.DEFAULT_LIMIT

            val file = resolve(path)
            if (!file.exists()) {
                return@withContext ToolResult.fail(
                    code = "not_found",
                    message = "文件不存在：$path",
                    hint = "确认路径拼写（相对于工作区根目录）；可用 memory_search 或向用户确认文件名。"
                )
            }
            if (!file.isFile) {
                return@withContext ToolResult.fail(
                    code = "not_a_file",
                    message = "不是普通文件：$path",
                    hint = "该路径是目录或特殊文件，请换用具体文件路径。"
                )
            }

            // F19：按页流式读，内存只与 limit 有关，与文件大小无关。
            // 修前是先整份 readText() 再 substring——limit 对堆不生效。
            val slice = TextFileSlice.read(file, offset, limit)

            // F20：非 UTF-8 文件读出来是一片 U+FFFD。不提前说，模型会把它当成真实内容，
            // 再拿这段乱码去 edit_file（那条路已拒绝，见 EditFileTool）——但更常见的是
            // 它基于乱码继续推理，最后给用户一个「这文件里写的是……」的错误结论。
            // 只在第一页提示：续读时再说一遍纯属浪费上下文。
            val encodingNote = if (offset == 0 && !TextEncoding.looksUtf8(file)) {
                "⚠️ 这不是 UTF-8 文本（可能是 GBK / GB18030）：非 ASCII 字符会显示成 " +
                    "U+FFFD 占位符，原文并未损坏，但你看不到真实内容。" +
                    "要处理它请用 shell_run 走 iconv 转码；不要用 edit_file 修改" +
                    "（它按字符匹配，会把文件写坏）。\n\n"
            } else ""
            val tail = if (slice.hasMore) {
                "\n…（已截断：本次 ${slice.text.length} 字符，文件 ${file.length()} 字节；" +
                    "续读用 offset=${slice.nextOffset}）"
            } else ""
            ToolResult.ok(
                code = "read_ok",
                message = "已读取 $path（本次 ${slice.text.length} 字符" +
                    if (slice.hasMore) "，未完）" else "）",
                data = encodingNote + slice.text + tail
            )
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "读取失败：${it.message}",
                hint = "确认文件在工作区内且可读；大文件请用 offset/limit 分批读取。"
            )
        }
    }
}

/**
 * 精确编辑（W-20：字符串匹配 + 唯一性校验 + 原子写）。
 *
 * 解决 [WriteFileTool] 全量覆写的痛点：小改动也要把整个文件内容重新生成一遍，
 * 大文件既费 token 又容易把没读全的部分写坏。本工具只替换指定片段。
 *
 * 决策逻辑全部委托纯函数 [EditFileLogic.plan]（与 QA harness 共用一份真源），
 * 本类只负责 IO 与参数解析：唯一性/出现次数/行号的判定都在纯函数里可测。
 */
class EditFileTool(
    workspace: File,
    private val backupHook: WriteBackupHook? = null
) : FileTool(workspace) {
    override val name = "edit_file"
    override val description =
        "对工作区内文件做精确字符串替换（局部修改，不整文件覆写）。" +
            "old_string 必须与文件内容精确一致且默认只能出现一次；" +
            "replace_all=true 可替换全部出现处。适合小改动，整文件重写请改用 write_file。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "相对于工作区的文件路径"),
        JsonSchema.prop("old_string", "string", "要被替换的原文，须与文件内容精确一致（含缩进换行）"),
        JsonSchema.prop("new_string", "string", "替换后的新文本；传空字符串表示删除该片段"),
        JsonSchema.prop("replace_all", "boolean", "true=替换全部出现处；默认 false 要求唯一匹配")
    )

    /** 写操作：修改文件内容，必须串行执行。 */
    override val readOnly = false

    /** A 批：改文件前必须确认（会话内可按「工作区文件写入」整组放行）。 */
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = args["path"]?.primOrNull()?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 path",
                    hint = "edit_file 必须提供 path（相对于工作区的文件路径），请补充后重试。"
                )
            val oldString = args["old_string"]?.primOrNull()?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 old_string",
                    hint = "edit_file 必须提供 old_string（要被替换的原文）。" +
                        "请先 read_file 读取目标片段，再原样填入。"
                )
            val newString = args["new_string"]?.primOrNull()?.contentOrNull ?: ""
            val replaceAll = args["replace_all"]?.primOrNull()?.contentOrNull == "true"

            val file = resolve(path)
            if (!file.exists()) {
                return@withContext ToolResult.fail(
                    code = "not_found",
                    message = "文件不存在：$path",
                    hint = "确认路径拼写（相对于工作区根目录）；新文件请改用 write_file 创建。"
                )
            }
            if (!file.isFile) {
                return@withContext ToolResult.fail(
                    code = "not_a_file",
                    message = "不是普通文件：$path",
                    hint = "该路径是目录或特殊文件，edit_file 只能改普通文本文件。"
                )
            }

            // F20：改文件前先确认「读得回来」。这里不是防御性编程，是防数据损坏——
            // readText() 对非 UTF-8 文件做的是「替换成 U+FFFD」而不是报错，替换完再写回，
            // 原字节就没了。edit_file 必须看到全文才能做唯一性校验，所以它没法分页，
            // 只能用「超限就明说」把 OOM 换成一句人话。
            if (file.length() > TextEncoding.EDIT_MAX_BYTES) {
                return@withContext ToolResult.fail(
                    code = "file_too_large",
                    message = "文件过大（${file.length() / 1024 / 1024} MB），edit_file 不整份载入",
                    hint = "edit_file 要看到全文才能判断 old_string 是否唯一，超过 " +
                        "${TextEncoding.EDIT_MAX_BYTES / 1024 / 1024} MB 不再支持。" +
                        "大文件请用 shell_run（sed / perl -i）做替换，或用 write_file 整份重写。"
                )
            }
            val source = TextEncoding.readStrictUtf8(file)
                ?: return@withContext ToolResult.fail(
                    code = "not_utf8",
                    message = "文件不是 UTF-8 编码，edit_file 会把它改坏",
                    hint = "按字符匹配要求先完整解码，非 UTF-8 文件解码时会丢字且不可逆。" +
                        "先用 shell_run 转码（如 `iconv -f GB18030 -t UTF-8 原文件 > 新文件`），" +
                        "或改用 write_file 整份重写。"
                )

            when (val plan = EditFileLogic.plan(source, oldString, newString, replaceAll)) {
                is EditPlan.Reject -> return@withContext ToolResult.fail(
                    code = plan.code, message = plan.message, hint = plan.hint
                )
                is EditPlan.Replace -> {
                    // B 批：改文件前自动备份旧内容，出问题可一键回滚到上一步。
                    backupHook?.backupBeforeWrite(path)
                    file.atomicWriteText(plan.replaced)
                    ToolResult.ok(
                        code = "edit_ok",
                        message = "已在 $path 替换 ${plan.count} 处",
                        data = "修改位置附近（→ 为替换后新文本首行）：\n" +
                            EditFileLogic.contextAround(plan.replaced, plan.firstOffset)
                    )
                }
            }
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "编辑失败：${it.message}",
                hint = "确认路径在工作区内、文件可写；如报非法路径请检查是否包含 ../ 穿越。"
            )
        }
    }
}

class WriteFileTool(
    workspace: File,
    private val backupHook: WriteBackupHook? = null
) : FileTool(workspace) {
    override val name = "write_file"
    override val description = "将文本写入工作区内的文件（覆盖写入）。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "相对于工作区的文件路径"),
        JsonSchema.prop("content", "string", "要写入的内容")
    )

    /** 写操作：覆盖文件内容，必须串行执行。 */
    override val readOnly = false

    /** A 批：写文件前必须确认（与 edit_file 同组）。 */
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = args["path"]?.primOrNull()?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 path",
                    hint = "write_file 必须提供 path（相对于工作区的文件路径），请补充后重试。"
                )
            val content = args["content"]?.primOrNull()?.contentOrNull ?: ""
            // B 批：覆盖写之前先备份旧内容（新文件则记录「此前不存在」），可一键回滚。
            backupHook?.backupBeforeWrite(path)
            resolve(path).writeText(content)
            ToolResult.ok(
                code = "write_ok",
                message = "已写入 ${content.length} 字符到 $path"
            )
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "写入失败：${it.message}",
                hint = "确认路径在工作区内、目录可写；内容过大请分段写入。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 4. clipboard                                                        */
/* ------------------------------------------------------------------ */
class ClipboardTool(private val context: Context) : AgentTool {
    override val name = "clipboard"
    override val description = "读取或写入系统剪贴板。action=get 返回当前内容，action=set 写入 text。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("action", "string", "操作类型", enum = listOf("get", "set")),
        JsonSchema.prop("text", "string", "action=set 时要写入的文本")
    )

    /**
     * 只读（按 roadmap 首批标注）：action=set 的剪贴板写入是本地、可逆、无破坏性的
     * 轻副作用，与文件写入不同级；如需更严格可改 false（整批含 set 时走串行）。
     *
     * 注意 `readOnly` 与审批是两件事：前者决定能不能并行，后者决定要不要问人。
     */
    override val readOnly = true

    /**
     * 审计 F9：`action=get` 把剪贴板内容整个读进上下文，此后它随下一次模型请求离开本机。
     * 剪贴板里常有密码、验证码、卡号——「读」本身是 Agent 的本职，拦不住也不该拦，
     * 能拦的只有**读的那一刻让用户看见**。
     *
     * `action=set` 不问：它把内容**放到**用户手上，没有把用户的秘密送出去；
     * 而且弹窗会出现在「模型帮你复制一段文本」这种完全无害的场景里。
     */
    override fun needsApprovalFor(args: JsonObject): Boolean {
        val action = args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase()
        return action != "set"
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val action = args["action"]?.primOrNull()?.contentOrNull ?: "get"
        if (action == "set") {
            val text = args["text"]?.primOrNull()?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "set 缺少 text",
                    hint = "clipboard action=set 必须提供 text 参数（要写入的文本）。"
                )
            cm.setPrimaryClip(ClipData.newPlainText("agent", text))
            ToolResult.ok(
                code = "clipboard_set",
                message = "已写入剪贴板：${text.take(50)}"
            )
        } else {
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
            if (text.isBlank()) {
                ToolResult.ok(code = "clipboard_empty", message = "剪贴板为空。", data = "")
            } else {
                ToolResult.ok(
                    code = "clipboard_get",
                    message = "已读取剪贴板（${text.length} 字符）",
                    data = text
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 5. send_notification                                                */
/* ------------------------------------------------------------------ */
class NotificationTool(private val context: Context) : AgentTool {
    override val name = "send_notification"
    override val description = "发送一条系统通知，用于提醒用户。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("title", "string", "通知标题"),
        JsonSchema.prop("body", "string", "通知内容")
    )

    /** 写操作：向系统发送用户可见通知，必须串行执行。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val title = args["title"]?.primOrNull()?.contentOrNull ?: "Agent 提醒"
            val body = args["body"]?.primOrNull()?.contentOrNull ?: ""
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "agent_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Agent 通知", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notification)
            ToolResult.ok(
                code = "notification_sent",
                message = "已发送通知：$title"
            )
        }.getOrElse {
            ToolResult.fail(
                code = "notification_error",
                message = "通知失败：${it.message}",
                hint = "确认通知权限已授予（系统设置 → 通知）；必要时重试或改用消息告知用户。"
            )
        }
    }
}
