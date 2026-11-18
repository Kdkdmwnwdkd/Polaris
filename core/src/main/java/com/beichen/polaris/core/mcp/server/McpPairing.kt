package com.beichen.polaris.core.mcp.server

import com.beichen.polaris.core.io.AtomicJsonFile
import com.beichen.polaris.core.llm.objOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * 一个已配对的 MCP 客户端。
 *
 * 只存 token 的 **SHA-256 摘要**，不存明文：`files/mcp/clients.json` 会被备份工具
 * （`adb backup`、第三方同步盘）整个拖走，明文 token 在那里等于长期有效的后门。
 */
data class McpClientRecord(
    val id: String,
    val name: String,
    val tier: String,
    val tokenHash: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastUsedAt: Long? = null,
    /** 连续鉴权失败次数。达到阈值由 [McpPairing] 熔断。 */
    val failedAttempts: Int = 0,
)

/**
 * 配对与令牌。
 *
 * ## 为什么每个客户端一个 token，而不是全局一个
 *
 * 撤销要能**只撤一个**。用户把手机借给同事、或者笔记本上的客户端不再用了，
 * 全局 token 只能「全撤」，撤完所有设备都要重配——现实中用户会因此干脆不撤。
 * 独立 token 让「撤销」变成一个成本为零的动作，安全策略才有被执行的可能。
 *
 * ## 为什么手写 JSON 而不是 @Serializable
 *
 * 这份文件是**跨版本可读的用户资产**：升级 App 后旧文件必须还能读。手写解析可以对
 * 缺字段、多字段、类型不对逐项兜底（`intOrNull() ?: 0`），而 `@Serializable` 遇到
 * 未知字段或缺失必填会直接抛，用户看到的是「已配对客户端全没了」。
 * 另外 core 其它部分不依赖序列化编译器插件，为这一处引入不值得。
 */
class McpPairing(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }
    private var records: MutableList<McpClientRecord> = load()

    /** 配对结果：明文 token 只在这里出现一次，之后无法再取回。 */
    data class Paired(val client: McpClientRecord, val token: String)

    /**
     * 新建一个客户端。返回的明文 token **不落盘**，调用方负责立刻展示给用户。
     *
     * @param ttlMs 有效期。默认 7 天——足够覆盖一次调试周期，又不至于长期裸奔。
     */
    fun pair(name: String, tier: McpTier, ttlMs: Long = DEFAULT_TTL_MS): Paired {
        val clean = name.trim().ifBlank { "未命名客户端" }.take(40)
        val now = clock()
        val token = newToken()
        val record = McpClientRecord(
            id = UUID.randomUUID().toString(),
            name = clean,
            tier = tier.wire,
            tokenHash = sha256(token),
            createdAt = now,
            expiresAt = now + ttlMs,
        )
        synchronized(lock) {
            records = records.filterNot { it.name == clean && it.expiresAt <= now }.toMutableList()
            records += record
            persistLocked()
        }
        return Paired(record, token)
    }

    /**
     * 校验 token，成功返回对应客户端。
     *
     * 失败计数由 [noteAuthFailure] 单独记——调用方（[McpServerHost]）在按 IP 熔断后
     * 统一降级，避免这里每次失败都重写整个文件。
     */
    fun authenticate(token: String): McpClientRecord? {
        if (token.isBlank()) return null
        val digest = sha256(token)
        synchronized(lock) {
            val now = clock()
            val hit = records.firstOrNull { constantTimeEquals(it.tokenHash, digest) } ?: return null
            if (hit.expiresAt <= now) return null
            if (hit.failedAttempts >= MAX_FAILED_ATTEMPTS) return null
            val touched = hit.copy(lastUsedAt = now)
            records = records.map { if (it.id == hit.id) touched else it }.toMutableList()
            // lastUsedAt 只服务两件事：爆破窗口（5 分钟）与 UI 上的「最后使用」。
            // 两者都不需要秒级精度，但每次调用都落盘会变成每次工具调用一次 fsync——
            // 一个多步任务几十次调用，就是几十次磁盘同步，全卡在 tool 路径上。
            // 所以内存里立刻更新，落盘最多一分钟一次。
            if (now - (hit.lastUsedAt ?: 0L) > LAST_USED_PERSIST_MS) persistLocked()
            return touched
        }
    }

    /** 把最近 5 分钟内活跃过的客户端各记一次失败。用于爆破后的整体降级。 */
    fun noteAuthFailure() {
        synchronized(lock) {
            val now = clock()
            var changed = false
            records = records.map { r ->
                val last = r.lastUsedAt
                if (last != null && now - last < FAILURE_WINDOW_MS) {
                    changed = true
                    r.copy(failedAttempts = r.failedAttempts + 1)
                } else {
                    r
                }
            }.toMutableList()
            if (changed) persistLocked()
        }
    }

    fun revoke(id: String): Boolean = synchronized(lock) {
        val before = records.size
        records = records.filterNot { it.id == id }.toMutableList()
        val changed = records.size != before
        if (changed) persistLocked()
        changed
    }

    fun revokeAll() = synchronized(lock) {
        records = mutableListOf()
        persistLocked()
    }

    /** 当前仍有效的客户端（过期的留在文件里但不再返回，便于用户看到「已过期」而不是凭空消失）。 */
    fun list(): List<McpClientRecord> = synchronized(lock) {
        val now = clock()
        records.filter { it.expiresAt > now }
    }

    fun findByTokenHash(hash: String): McpClientRecord? = synchronized(lock) {
        records.firstOrNull { constantTimeEquals(it.tokenHash, hash) }
    }

    // ------------------------------------------------------------------ io

    private fun load(): MutableList<McpClientRecord> = runCatching {
        val text = AtomicJsonFile.readWithBackupOrNull(file) { raw ->
            runCatching { json.parseToJsonElement(raw) }.isSuccess
        } ?: return mutableListOf()
        val root = json.parseToJsonElement(text).objOrNull() ?: return mutableListOf()
        val arr = root["clients"] as? JsonArray ?: return mutableListOf()
        arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val hash = o.str("tokenHash") ?: return@mapNotNull null
            McpClientRecord(
                id = id,
                name = o.str("name") ?: "未命名客户端",
                tier = o.str("tier") ?: McpTier.READONLY.wire,
                tokenHash = hash,
                createdAt = o.long("createdAt") ?: 0L,
                expiresAt = o.long("expiresAt") ?: 0L,
                lastUsedAt = o.long("lastUsedAt"),
                failedAttempts = o.long("failedAttempts")?.toInt() ?: 0,
            )
        }.toMutableList()
    }.getOrElse { mutableListOf() }

    private fun persistLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            val root: JsonElement = buildJsonObject {
                put("version", 1)
                put("clients", JsonArray(records.map { r ->
                    buildJsonObject {
                        put("id", r.id)
                        put("name", r.name)
                        put("tier", r.tier)
                        put("tokenHash", r.tokenHash)
                        put("createdAt", r.createdAt)
                        put("expiresAt", r.expiresAt)
                        r.lastUsedAt?.let { put("lastUsedAt", it) }
                        put("failedAttempts", r.failedAttempts)
                    }
                }))
            }
            val text = pretty.encodeToString(JsonElement.serializer(), root)
            // keepBackup = true：这份文件丢了，所有已配对客户端都要重配。
            AtomicJsonFile.write(file, text, keepBackup = true)
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    companion object {
        const val DEFAULT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val MAX_FAILED_ATTEMPTS = 10
        private const val FAILURE_WINDOW_MS = 5L * 60 * 1000
        /** `lastUsedAt` 的落盘节流：一分钟一次，够用且不把 fsync 塞进 tool 路径。 */
        private const val LAST_USED_PERSIST_MS = 60L * 1000

        private val random = SecureRandom()

        /** 256 bit，URL-safe，无 padding——放进二维码不会撞上 `+`/`/` 的转义问题。 */
        fun newToken(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun sha256(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
