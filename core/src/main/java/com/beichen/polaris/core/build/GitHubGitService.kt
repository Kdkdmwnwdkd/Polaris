package com.beichen.polaris.core.build

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.BodySlice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 一条提交记录（`github_log` 用）。 */
data class GitCommitInfo(
    val sha: String,
    val message: String,
    val author: String,
    val date: String
)

/**
 * GitHub 提交服务（W-32）：用 Git Data API 把工作区文件提交到指定分支。
 *
 * ## 为什么不用本地 git
 * 手机端没有 `git` 可执行文件（除非 proot 里装），引 JGit 又要多 3MB + java.nio desugar 风险。
 * Git Data API 的 blobs→tree→commit→ref 四步能完整表达"提交"这个语义，
 * 且与已有的 [GitHubBuildService] 共用同一套 PAT 配置。
 *
 * ## 为什么不支持删除
 * 只提交「新增 + 修改」。远端有、本地没有的文件保持不动——
 * 工作区是用户随手放文件的地方，把「本地没有」当成「要删」会误删仓库文件。
 *
 * ## 安全
 * PAT 只进 Authorization 头；日志只记 owner/repo/branch 与变更数，绝不记 token。
 */
class GitHubGitService(
    private val cfg: GitHubConfig,
    http: OkHttpClient? = null
) {
    private val http: OkHttpClient = http ?: LlmHttp.short

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 比对工作区与远端分支，返回待提交的变更。
     * 分支不存在（404）时给出「可能是 master」的提示。
     */
    suspend fun status(
        branch: String,
        local: Map<String, ByteArray>
    ): Result<List<FileChange>> = withContext(Dispatchers.IO) {
        runCatching {
            val remote = remoteBlobs(branch)
            GitHubGitLogic.diff(local, remote)
        }.recoverCatching { e ->
            throw IOException(friendly(e, branch))
        }
    }

    /**
     * 提交变更到分支。
     *
     * @param changes 由 [status] 得出的变更（调用方负责只传变化的文件）
     * @param local 这些文件的内容（按路径取）
     * @return 新 commit 的 sha
     */
    suspend fun commit(
        branch: String,
        message: String,
        changes: List<FileChange>,
        local: Map<String, ByteArray>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (changes.isEmpty()) throw IOException("没有需要提交的变更")
            val ref = get("/repos/${cfg.fullName}/git/ref/heads/$branch")
            val headSha = ref["object"]?.objOrNull()?.get("sha")?.primOrNull()?.contentOrNull
                ?: throw IOException("读不到分支 $branch 的最新提交")
            val headCommit = get("/repos/${cfg.fullName}/git/commits/$headSha")
            val baseTree = headCommit["tree"]?.objOrNull()?.get("sha")?.primOrNull()?.contentOrNull
                ?: throw IOException("读不到最新提交的 tree")

            // 逐个上传 blob：只传变更的文件（sha 已在本地算过，未变的不上传）
            val treeEntries = buildJsonArray {
                for (change in changes) {
                    val bytes = local[change.path]
                        ?: local.entries.firstOrNull {
                            GitHubGitLogic.normalizePath(it.key) == change.path
                        }?.value
                        ?: throw IOException("缺少文件内容：${change.path}")
                    val blobSha = post(
                        "/repos/${cfg.fullName}/git/blobs",
                        buildJsonObject {
                            put("content", Base64.getEncoder().encodeToString(bytes))
                            put("encoding", "base64")
                        }
                    )["sha"]?.primOrNull()?.contentOrNull
                        ?: throw IOException("上传 ${change.path} 失败")
                    add(
                        buildJsonObject {
                            put("path", change.path)
                            put("mode", "100644")
                            put("type", "blob")
                            put("sha", blobSha)
                        }
                    )
                }
            }

            val newTree = post(
                "/repos/${cfg.fullName}/git/trees",
                buildJsonObject {
                    put("base_tree", baseTree)
                    put("tree", treeEntries)
                }
            )["sha"]?.primOrNull()?.contentOrNull
                ?: throw IOException("创建 tree 失败")

            val newCommit = post(
                "/repos/${cfg.fullName}/git/commits",
                buildJsonObject {
                    put("message", message)
                    put("tree", newTree)
                    putJsonArray("parents") { add(headSha) }
                }
            )
            val newSha = newCommit["sha"]?.primOrNull()?.contentOrNull
                ?: throw IOException("创建 commit 失败")

            // 快进更新分支：force=false，远端在此期间有新提交会失败（不覆盖别人的改动）
            patch(
                "/repos/${cfg.fullName}/git/refs/heads/$branch",
                buildJsonObject {
                    put("sha", newSha)
                    put("force", false)
                }
            )
            AgentLogger.i(
                "GitHubGit",
                "已提交 ${changes.size} 个文件到 ${cfg.fullName}@$branch：${newSha.take(8)}"
            )
            newSha
        }.recoverCatching { e ->
            throw IOException(friendly(e, branch))
        }
    }

    /** 最近提交（`github_log` 用）。列表接口返回 JSON 数组，故走 [getArray]。 */
    suspend fun log(branch: String, limit: Int): Result<List<GitCommitInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = getArray(
                    "/repos/${cfg.fullName}/commits?sha=$branch&per_page=${limit.coerceIn(1, 30)}"
                )
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val sha = obj["sha"]?.primOrNull()?.contentOrNull ?: return@mapNotNull null
                    val commit = obj["commit"]?.objOrNull()
                    val authorObj = commit?.get("author")?.objOrNull()
                    GitCommitInfo(
                        sha = sha,
                        message = commit?.get("message")?.primOrNull()?.contentOrNull
                            .orEmpty().lineSequence().firstOrNull().orEmpty(),
                        author = authorObj?.get("name")?.primOrNull()?.contentOrNull.orEmpty(),
                        date = authorObj?.get("date")?.primOrNull()?.contentOrNull.orEmpty()
                    )
                }
            }.recoverCatching { e -> throw IOException(friendly(e, branch)) }
        }

    /**
     * 取远端某个文件的文本内容（`github_diff` 用）。
     *
     * @return 成功时 Result.success(内容)；**远端没有该文件时返回 success(null)**——
     *   "新增文件"是正常情况，不该当成错误；只有网络/权限问题才 failure。
     */
    suspend fun fileContent(branch: String, path: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = path.split('/').joinToString("/") {
                    java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                val obj = try {
                    get("/repos/${cfg.fullName}/contents/$encoded?ref=$branch")
                } catch (e: IOException) {
                    val msg = e.message.orEmpty()
                    // 404 = 远端没有这个文件（新增），不是错误
                    if (msg.contains("404")) return@runCatching null
                    throw e
                }
                val encodedContent = obj["content"]?.primOrNull()?.contentOrNull ?: return@runCatching null
                if (obj["encoding"]?.primOrNull()?.contentOrNull != "base64") {
                    // 超过 1MB 的文件 contents API 不返回内容
                    throw IOException("文件过大，GitHub 不返回内容（>1MB）")
                }
                String(Base64.getMimeDecoder().decode(encodedContent), Charsets.UTF_8)
            }.recoverCatching { e -> throw IOException(friendly(e, branch)) }
        }

    /**
     * 下载仓库 zip（`github_clone` 用）。
     *
     * 走 codeload 而不是 API：**不消耗 API 配额**（zip 是几百 KB 到几十 MB 的文件，
     * 走 contents API 逐文件拉会瞬间把 5000 次/小时的额度打光）。
     * 失败时回退 gh-proxy 镜像——国内网络下 codeload 时通时不通，与 HF 镜像同一套纪律。
     *
     * 流式读取并随时检查体积：不信任 Content-Length，超 [CloneLogic.MAX_ZIP_BYTES] 立即中止。
     */
    suspend fun downloadZip(branch: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val primary = "https://codeload.github.com/${cfg.fullName}/zip/refs/heads/" +
                java.net.URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
            val mirror = "https://gh-proxy.com/$primary"
            val bytes = fetchRaw(primary) ?: fetchRaw(mirror)
                ?: throw IOException("下载失败（codeload 与镜像都不可用）")
            if (bytes.isEmpty()) throw IOException("下载到空文件（分支 $branch 可能不存在）")
            bytes
        }
    }

    /** 原始下载（不走 API，不带 Authorization；失败返回 null）。 */
    private fun fetchRaw(url: String): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/zip")
            .get()
            .build()
        val out = java.io.ByteArrayOutputStream()
        val result = runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body ?: return@runCatching null
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > CloneLogic.MAX_ZIP_BYTES) {
                            throw IOException(
                                "仓库压缩包超过 ${CloneLogic.MAX_ZIP_BYTES / 1024 / 1024} MB，已中止"
                            )
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
        }
        // 超上限要让用户看到具体原因，不能被当成普通的"下载失败"
        val error = result.exceptionOrNull()
        if (error is IOException && error.message?.contains("超过") == true) throw error
        return result.getOrNull()
    }

    /** 统一取远端分支的 path → blob sha 映射（递归 tree）。 */
    private fun remoteBlobs(branch: String): Map<String, String> {
        val ref = get("/repos/${cfg.fullName}/git/ref/heads/$branch")
        val headSha = ref["object"]?.objOrNull()?.get("sha")?.primOrNull()?.contentOrNull
            ?: throw IOException("读不到分支 $branch 的最新提交")
        val headCommit = get("/repos/${cfg.fullName}/git/commits/$headSha")
        val treeSha = headCommit["tree"]?.objOrNull()?.get("sha")?.primOrNull()?.contentOrNull
            ?: throw IOException("读不到最新提交的 tree")
        val tree = get("/repos/${cfg.fullName}/git/trees/$treeSha?recursive=1")
        val out = HashMap<String, String>()
        tree["tree"]?.arrOrNull()?.forEach { el ->
            // W-47：远端数组里若混入 null（API 变更/中转改写），硬转换会中断整次同步
            val obj = el.objOrNull() ?: return@forEach
            if (obj["type"]?.primOrNull()?.contentOrNull != "blob") return@forEach
            val path = obj["path"]?.primOrNull()?.contentOrNull ?: return@forEach
            val sha = obj["sha"]?.primOrNull()?.contentOrNull ?: return@forEach
            out[path] = sha
        }
        return out
    }

    /** 把 HTTP 异常翻译成用户能行动的文案。 */
    private fun friendly(e: Throwable, branch: String): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("401") -> "PAT 无效或已过期：请到「构建」页重新填"
            raw.contains("403") -> "PAT 权限不足：需要该仓库的 Contents 读写权限"
            raw.contains("404") ->
                "找不到仓库 ${cfg.fullName} 或分支 $branch：" +
                    "检查 owner/repo 拼写、分支名（可能是 master）、PAT 是否有该仓库权限"
            raw.contains("422") -> "提交被拒绝（可能是分支在此期间有新提交，重试即可）"
            raw.isBlank() -> e::class.simpleName ?: "GitHub 请求失败"
            else -> raw.take(200)
        }
    }

    // —————————————————— HTTP ——————————————————

    private fun get(path: String): JsonObject = exec(
        Request.Builder().url(BASE + path).get().build()
    )

    private fun post(path: String, body: JsonObject): JsonObject = exec(
        Request.Builder()
            .url(BASE + path)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    )

    private fun patch(path: String, body: JsonObject): JsonObject = exec(
        Request.Builder()
            .url(BASE + path)
            .patch(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    )

    private fun getArray(path: String): kotlinx.serialization.json.JsonArray {
        val text = execRaw(Request.Builder().url(BASE + path).get().build())
        return json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            ?: throw IOException("GitHub 返回了非数组响应")
    }

    private fun exec(request: Request): JsonObject {
        val text = execRaw(request)
        if (text.isBlank()) return JsonObject(emptyMap())
        return json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    }

    /** 发请求并返回原始响应体；非 2xx 抛 IOException（带状态码与服务端消息）。 */
    private fun execRaw(request: Request): String {
        val authed = request.newBuilder()
            .header("Authorization", "Bearer ${cfg.pat}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(authed).execute().use { resp ->
            // 审计 F40：`body?.string()` 整份进堆。GitHub 的 trees 接口在仓库文件极多时
            // 会返回几 MB JSON，加上网络中间层异常响应，没有上限就没有兜底。
            val slice = resp.body?.byteStream()
                ?.let { BoundedBody.read(it, BoundedBody.MAX_READ_BYTES) }
                ?: BodySlice.EMPTY
            val text = slice.text
            if (!resp.isSuccessful) {
                // 只带状态码与截断后的服务端消息：响应体里不会出现我们的 PAT
                throw IOException("HTTP ${resp.code}：${text.take(300)}")
            }
            if (slice.truncated) {
                throw IOException("GitHub 响应超过 ${BoundedBody.MAX_READ_BYTES / 1024 / 1024} MB，已中止")
            }
            return text
        }
    }

    private companion object {
        const val BASE = "https://api.github.com"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
