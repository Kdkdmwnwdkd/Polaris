package com.beichen.polaris.core.build

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * GitHub 构建服务（调研 07 号文档「GitHubBuildService.kt」直译）。
 *
 * 全链路六步，纯 GitHub REST API v3（OkHttp，与 LLM/MCP/媒体层同款 HTTP 栈）：
 *  ① pushWorkspace：工作区文件 → Git Data API（blobs→tree→commit→ref）推成 commit
 *  ② dispatch：POST workflow_dispatch 触发 CI
 *  ③ findRun / runStatus：轮询 run（queued → in_progress → completed）
 *  ④ downloadLatestApk：release/latest 资产直下（优先，不过期）；
 *     陈旧时回退 downloadArtifact（artifact zip，90 天过期）
 *  ⑤ SHA-256：下载后计算落档
 *  ⑥ 安装由 UI 层经 FileProvider 拉起系统安装器（本类不碰 Intent）
 *
 * PAT 只进 Authorization 头，**绝不进 URL、绝不进日志**（AgentLogger.sanitize 兜底）。
 */
class GitHubBuildService(
    private val cfg: GitHubConfig,
    // 默认值不能引用 companion 成员（Kotlin 语言限制），故用可空入参 + 属性初始化兜底
    http: OkHttpClient? = null
) {
    private val http: OkHttpClient = http ?: defaultHttp()

    private val json = Json { ignoreUnknownKeys = true }

    /** 测连通：GET /user（首次配置的引导步骤）。成功返回登录名。 */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = get("/user")
            root["login"]?.primOrNull()?.contentOrNull ?: "ok"
        }
    }

    /** 远端 main 当前 commit sha。 */
    suspend fun headSha(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = get("/repos/${cfg.fullName}/git/ref/heads/main")
            root["object"]?.objOrNull()?.get("sha")?.primOrNull()?.contentOrNull
                ?: error("响应缺 object.sha")
        }
    }

    /**
     * ① 把工作区文件推成 commit（路径 `polaris-workspace/<文件名>`）。
     *
     * 走 Git Data API 而非 JGit——零依赖，且与 CI 推送脚本同一套协议
     * （本仓库 L4-L8 的推送全部由此路径完成，久经实战）。工作区为空时
     * 不建空 commit，直接返回当前 HEAD。
     */
    suspend fun pushWorkspace(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // W-27：递归收集（保留子目录结构），与编辑器看到的文件树一致
            val wsRoot = WorkspaceStore.dir(context)
            val files = WorkspaceStore.listTree(context).filter { !it.isDir }
            if (files.isEmpty()) {
                AgentLogger.d(TAG, "工作区为空，跳过 commit，直接用 HEAD")
                return@runCatching headSha().getOrThrow()
            }
            val head = headSha().getOrThrow()
            val commitObj = get("/repos/${cfg.fullName}/git/commits/$head")
            val baseTree = commitObj["tree"]?.objOrNull()?.get("sha")?.primOrNull()?.contentOrNull
                ?: error("HEAD commit 缺 tree")

            val entries = files.map { entry ->
                val f = java.io.File(wsRoot, entry.path)
                val blob = post("/repos/${cfg.fullName}/git/blobs", buildJsonObject {
                    put("content", Base64.getEncoder().encodeToString(f.readBytes()))
                    put("encoding", "base64")
                })
                buildJsonObject {
                    put("path", "polaris-workspace/${entry.path}")
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", blob["sha"]?.primOrNull()?.contentOrNull ?: error("blob 缺 sha"))
                }
            }
            val tree = post("/repos/${cfg.fullName}/git/trees", buildJsonObject {
                put("base_tree", baseTree)
                put("tree", kotlinx.serialization.json.buildJsonArray {
                    entries.forEach { add(it) }
                })
            })
            val treeSha = tree["sha"]?.primOrNull()?.contentOrNull ?: error("tree 缺 sha")
            val commit = post("/repos/${cfg.fullName}/git/commits", buildJsonObject {
                put("message", "北辰构建：工作区 ${files.size} 个文件")
                put("tree", treeSha)
                put("parents", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(head))
                })
            })
            val sha = commit["sha"]?.primOrNull()?.contentOrNull ?: error("commit 缺 sha")
            patch("/repos/${cfg.fullName}/git/refs/heads/main", buildJsonObject { put("sha", sha) })
            AgentLogger.d(TAG, "已推送 commit ${sha.take(8)}（${files.size} 个工作区文件）")
            sha
        }
    }

    /** ② 触发 workflow_dispatch（build.yml 已声明该触发器）。 */
    suspend fun dispatch(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            post(
                "/repos/${cfg.fullName}/actions/workflows/${cfg.workflow}/dispatches",
                buildJsonObject { put("ref", "main") }
            )
            Unit
        }
    }

    /** ③ 找本次 commit 对应的 run（workflow_dispatch 事件，取最新匹配）。 */
    suspend fun findRun(commitSha: String): Result<RunInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val runs = get("/repos/${cfg.fullName}/actions/runs?event=workflow_dispatch&per_page=10")
            val arr = runs["workflow_runs"]?.arrOrNull() ?: error("响应缺 workflow_runs")
            val run = arr.firstOrNull {
                it.objOrNull()?.get("head_sha")?.primOrNull()?.contentOrNull == commitSha
            }?.objOrNull()
            run?.let { parseRun(it) }
        }
    }

    /** ③ 轮询 run 状态。 */
    suspend fun runStatus(runId: Long): Result<RunInfo> = withContext(Dispatchers.IO) {
        runCatching { parseRun(get("/repos/${cfg.fullName}/actions/runs/$runId")) }
    }

    /**
     * ④ 下载 release/latest 的 APK（优先路线：不过期、直链）。
     *
     * @param minUpdatedAt 新鲜度下限：release 资产更新时间早于此次构建开始时间的
     *   视为陈旧（上一次构建的残留），返回失败走 artifact 回退。
     */
    suspend fun downloadLatestApk(
        minUpdatedAt: Long,
        into: File,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val rel = get("/repos/${cfg.fullName}/releases/tags/latest")
            val assets = rel["assets"]?.arrOrNull() ?: error("release 缺 assets")
            val asset = assets.firstOrNull {
                it.objOrNull()?.get("name")?.primOrNull()?.contentOrNull?.endsWith(".apk") == true
            }?.objOrNull() ?: error("latest release 无 APK 资产")
            val updatedAt = asset["updated_at"]?.primOrNull()?.contentOrNull
            if (updatedAt != null && parseEpochMs(updatedAt) < minUpdatedAt) {
                error("latest release 陈旧（资产更新于本次构建开始前），回退 artifact")
            }
            val url = asset["url"]?.primOrNull()?.contentOrNull ?: error("资产缺 url")
            val size = asset["size"]?.primOrNull()?.long ?: 0L
            downloadTo(url, into, size, onProgress)
            into
        }
    }

    /**
     * ④ 回退路线：下载 run 的 artifact zip 并解出 APK。
     * GitHub 对大文件走 CDN 重定向，OkHttp 自动跟随；断流由上层重试策略兜。
     */
    suspend fun downloadArtifact(runId: Long, into: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val artsRoot = get("/repos/${cfg.fullName}/actions/runs/$runId/artifacts")
            val arts = artsRoot["artifacts"]?.arrOrNull() ?: error("run 缺 artifacts")
            val art = arts.firstOrNull {
                it.objOrNull()?.get("name")?.primOrNull()?.contentOrNull == "app-debug"
            }?.objOrNull() ?: error("run 无 app-debug artifact")
            val zipUrl = art["archive_download_url"]?.primOrNull()?.contentOrNull
                ?: error("artifact 缺下载地址")
            val zipFile = File(into.parentFile, "artifact.zip")
            downloadTo(zipUrl, zipFile, 0L) { }
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".apk")) {
                        into.outputStream().use { out -> zis.copyTo(out) }
                        zipFile.delete()
                        return@runCatching into
                    }
                    entry = zis.nextEntry
                }
            }
            zipFile.delete()
            error("artifact zip 内无 APK")
        }
    }

    /** ⑤ SHA-256（十六进制小写）。 */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n = input.read(buf)
            while (n != -1) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadTo(url: String, into: File, totalBytes: Long, onProgress: (Float) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("Authorization", "Bearer ${cfg.pat}")
            .get()
            .build()
        into.parentFile?.mkdirs()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("空响应体")
            val total = if (totalBytes > 0) totalBytes else body.contentLength()
            var read = 0L
            val sink = into.sink().buffer()
            val source = body.source()
            val buf = okio.Buffer()
            sink.use { s ->
                while (true) {
                    val n = source.read(buf, 64 * 1024)
                    if (n == -1L) break
                    read += n
                    s.write(buf, n)
                    if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
                s.flush()
            }
        }
    }

    private fun get(path: String): JsonObject {
        val request = Request.Builder()
            .url("$API$path")
            .header("Authorization", "Bearer ${cfg.pat}")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        return exec(request)
    }

    private fun post(path: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url("$API$path")
            .header("Authorization", "Bearer ${cfg.pat}")
            .header("Accept", "application/vnd.github+json")
            .post(
                json.encodeToString(JsonObject.serializer(), body)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
        return exec(request)
    }

    private fun patch(path: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url("$API$path")
            .header("Authorization", "Bearer ${cfg.pat}")
            .header("Accept", "application/vnd.github+json")
            .patch(
                json.encodeToString(JsonObject.serializer(), body)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
        return exec(request)
    }

    private fun exec(request: Request): JsonObject {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}: ${text.take(300)}")
            }
            if (text.isBlank()) return JsonObject(emptyMap()) // 204（dispatch 成功）
            return json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
        }
    }

    private fun parseRun(o: JsonObject): RunInfo = RunInfo(
        id = o["id"]?.primOrNull()?.long ?: error("run 缺 id"),
        number = o["run_number"]?.primOrNull()?.long ?: 0L,
        status = o["status"]?.primOrNull()?.contentOrNull ?: "unknown",
        conclusion = o["conclusion"]?.primOrNull()?.contentOrNull
    )

    /** ISO-8601（如 2025-01-01T00:00:00Z）→ epoch ms。 */
    private fun parseEpochMs(iso: String): Long = runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "GitHubBuildService"
        private const val API = "https://api.github.com"

        fun defaultHttp(): OkHttpClient = LlmHttp.llm
    }
}

/** run 关键字段（轮询只需要这四个）。 */
data class RunInfo(
    val id: Long,
    val number: Long,
    /** queued / in_progress / completed / waiting … */
    val status: String,
    /** success / failure / cancelled / startup_failure / null（未完成） */
    val conclusion: String?
) {
    val isCompleted: Boolean get() = status == "completed"
    val isSuccess: Boolean get() = isCompleted && conclusion == "success"
}
