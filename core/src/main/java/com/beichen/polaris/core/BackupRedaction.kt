package com.beichen.polaris.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.beichen.polaris.core.llm.primOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 备份件脱敏：**密钥不写进导出包**，恢复时沿用本机已有的。
 *
 * ## 为什么
 *
 * 备份件是个 ZIP，用户存到网盘、发微信、拷到电脑——这是导出功能存在的意义。
 * W-47 已经因为同一个理由把 GitHub PAT 从包里剔掉了（「明文 token 存网盘等于送仓库权限」）。
 * 但同一句话对 API Key 一样成立，而 `profilesJson`（每个档案的 `apiKey`）、
 * `mediaKeys`、Key 池这三处一直是原样写进去的。
 *
 * ## 为什么在 JSON 层做，而不是先反序列化成对象
 *
 * 备份件本身就是 JSON。在**线上格式**这一层脱敏有两个实际好处：
 *
 * 1. 不依赖 `ModelProfile` / `ConfigStore`（它们 import 了 `android.content`），
 *    于是这套「哪些字段算密钥、恢复时怎么合并」的判定能在无 Android 环境里被逐条对拍——
 *    而合并逻辑一旦写错，代价是**用户重装后 Key 全丢**，这比实现难看重要得多；
 * 2. 老备份件（字段少几个、多几个）不会被重新编码时改写形状，`ignoreUnknownKeys`
 *    的容错语义保持原样。
 *
 * ## 三条语义约定
 *
 * - 脱敏**猜不出就丢**：输入解析不出来时返回空串，宁可这次备份没有配置，也不把
 *   可能含密钥的原文写进包。这是 fail-closed，方向与「恢复」相反。
 * - 合并**猜不出就保**：恢复时输入解析不出来就保留本机原值，不拿坏包清空好数据。
 * - 合并只在「新值确实为空」时回退到旧值。备份里带了非空 Key（老版本导出的包）
 *   仍然照旧覆盖——升级不该让老备份变得不可用。
 */
object BackupRedaction {

    private val json = Json { ignoreUnknownKeys = true }

    /** 写进备份件的空值。抽成常量，便于断言「比对的是它」。 */
    const val CLEARED = ""

    /* ------------------------------- 模型档案 ------------------------------- */

    /**
     * 把每个档案的 `config.apiKey` 置空。
     *
     * @param rawJson `List<ModelProfile>` 的 JSON；解析失败返回空串（fail-closed）。
     */
    fun redactProfiles(rawJson: String): String {
        val arr = parseArray(rawJson) ?: return CLEARED
        val out = arr.map { el ->
            val obj = el as? JsonObject ?: return@map el
            val cfg = obj["config"] as? JsonObject ?: return@map obj

            // 自定义头里同样可能有 Key：中转站常要求 X-Api-Key / api-key / Authorization，
            // 只清 apiKey 字段等于把它们留在备份里。按**头名**判断，值一律清空。
            val headers = cfg["customHeaders"] as? JsonObject
            val safeCfg = if (headers == null) cfg else {
                val cleaned = headers.mapValues { (name, value) ->
                    if (isSensitiveHeader(name)) JsonPrimitive(CLEARED) else value
                }
                JsonObject(cfg + ("customHeaders" to JsonObject(cleaned)))
            }
            val safeBody = redactBody(safeCfg["customBody"]?.primOrNull()?.contentOrNull)
            val outCfg = JsonObject(
                (if (safeBody == null) safeCfg else JsonObject(safeCfg + ("customBody" to JsonPrimitive(safeBody))))
                    + ("apiKey" to JsonPrimitive(CLEARED))
            )
            JsonObject(obj + ("config" to outCfg))
        }
        return json.encodeToString(JsonArray.serializer(), JsonArray(out))
    }

    /**
     * 自定义请求体里也可能放凭据（有的中转站用 `{"api_key":"..."}` 而不是请求头）。
     *
     * 只处理**顶层**键：请求体是用户自由填写的 JSON 片段，深层结构无法猜出哪一层是凭据；
     * 递归清空会把 `{"messages":[{"content":"..."}]}` 这类正常载荷也破坏掉。
     * 猜不到的部分保持原样——这是本实现已知的边界，写在这里而不是藏在代码里。
     *
     * @return 脱敏后的 JSON 文本；[rawBody] 为空或不是 JSON 对象时返回 null（表示不必改）。
     */
    private fun redactBody(rawBody: String?): String? {
        if (rawBody.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(rawBody) as? JsonObject }.getOrNull() ?: return null
        val cleaned = obj.mapValues { (name, value) ->
            if (isSensitiveHeader(name)) JsonPrimitive(CLEARED) else value
        }
        if (cleaned == obj) return null
        return json.encodeToString(JsonObject.serializer(), JsonObject(cleaned))
    }

    /**
     * 头名是否可能承载凭据。按子串判断而非白名单：用户自己起的头名无法枚举，
     * `X-Api-Key` / `api-key` / `Authorization` / `x-goog-api-key` 都要覆盖到。
     */
    private fun isSensitiveHeader(name: String): Boolean {
        val n = name.lowercase()
        return SENSITIVE_HEADER_PARTS.any { n.contains(it) }
    }

    private val SENSITIVE_HEADER_PARTS = listOf("key", "token", "auth", "secret", "cookie", "signature")

    /**
     * 恢复时把备份件里的空 Key 换成本机同 id 档案的 Key。
     *
     * 匹配用 `id`：档案 id 是随机 UUID，跨设备相同 id 说明是同一份配置的延续，
     * 沿用它比让用户挨个重填更合理；跨设备（id 全不同）自然走不到这条路，用户重填。
     *
     * @param incomingJson 备份件里的档案 JSON。
     * @param existingJson 本机当前档案 JSON（可能为空）。
     * @return 合并后的 JSON；[incomingJson] 解析失败时原样返回 [existingJson]（不清空）。
     */
    fun mergeProfiles(incomingJson: String, existingJson: String): String {
        val incoming = parseArray(incomingJson) ?: return existingJson
        val existing = parseArray(existingJson) ?: JsonArray(emptyList())

        // id → 本机该档案的 config；Key 与敏感头的回填都查这张表
        val localConfigById = existing.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.primOrNull()?.contentOrNull ?: return@mapNotNull null
            val cfg = obj["config"] as? JsonObject ?: return@mapNotNull null
            id to cfg
        }.toMap()

        val out = incoming.map { el ->
            val obj = el as? JsonObject ?: return@map el
            val cfg = obj["config"] as? JsonObject ?: return@map obj
            val id = obj["id"]?.primOrNull()?.contentOrNull.orEmpty()
            val local = localConfigById[id]

            var next = cfg

            // Key：包里为空才回填（包里的非空值来自老版本，照旧覆盖）
            val ownKey = cfg["apiKey"]?.primOrNull()?.contentOrNull.orEmpty()
            if (ownKey.isBlank()) {
                val localKey = local?.get("apiKey")?.primOrNull()?.contentOrNull.orEmpty()
                if (localKey.isNotBlank()) next = JsonObject(next + ("apiKey" to JsonPrimitive(localKey)))
            }

            // 敏感头：导出时被清空，同样按头名回填；包里的非空值照旧覆盖
            val headers = next["customHeaders"] as? JsonObject
            val localHeaders = local?.get("customHeaders") as? JsonObject
            if (headers != null && localHeaders != null) {
                val filled = headers.mapValues { (name, value) ->
                    val own = value.primOrNull()?.contentOrNull.orEmpty()
                    if (own.isNotBlank() || !isSensitiveHeader(name)) return@mapValues value
                    val localValue = localHeaders[name]?.primOrNull()?.contentOrNull.orEmpty()
                    if (localValue.isBlank()) value else JsonPrimitive(localValue)
                }
                next = JsonObject(next + ("customHeaders" to JsonObject(filled)))
            }

            // 请求体顶层的敏感键同样回填（与头同一套规则，见 redactBody 的边界说明）
            val localBody = local?.get("customBody")?.primOrNull()?.contentOrNull
            if (localBody != null) {
                val ownBody = next["customBody"]?.primOrNull()?.contentOrNull
                val filledBody = fillBody(ownBody, localBody)
                if (filledBody != null) {
                    next = JsonObject(next + ("customBody" to JsonPrimitive(filledBody)))
                }
            }

            if (next === cfg) obj else JsonObject(obj + ("config" to next))
        }
        return json.encodeToString(JsonArray.serializer(), JsonArray(out))
    }

    /**
     * 把备份里被清空的**顶层**敏感键换回本机请求体里的同名字段。
     *
     * @return 合并后的 JSON 文本；两边都解析不出对象、或没有需要回填的键时返回 null。
     */
    private fun fillBody(ownBody: String?, localBody: String): String? {
        val own = runCatching { json.parseToJsonElement(ownBody.orEmpty()) as? JsonObject }.getOrNull() ?: return null
        val local = runCatching { json.parseToJsonElement(localBody) as? JsonObject }.getOrNull() ?: return null

        var changed = false
        val out = own.mapValues { (name, value) ->
            val ownValue = value.primOrNull()?.contentOrNull.orEmpty()
            if (ownValue.isNotBlank() || !isSensitiveHeader(name)) return@mapValues value
            val localValue = local[name]?.primOrNull()?.contentOrNull.orEmpty()
            if (localValue.isBlank()) return@mapValues value
            changed = true
            JsonPrimitive(localValue)
        }
        return if (changed) json.encodeToString(JsonObject.serializer(), JsonObject(out)) else null
    }

    /* ------------------------------- 媒体 Key ------------------------------- */

    /** 媒体 Key 全部置空（保留 providerId 这一组键，恢复侧据此知道「备份里有哪些」）。 */
    fun redactMediaKeys(keys: Map<String, String>): Map<String, String> =
        keys.mapValues { CLEARED }

    /**
     * 恢复时把空值换回本机已有的媒体 Key。
     *
     * 注意只遍历 [incoming]：备份里没有的 provider 不会被本机值「补进去」，
     * 否则一次恢复就等于把本机全部媒体 Key 又写了一遍，语义上不该由备份决定。
     */
    fun mergeMediaKeys(incoming: Map<String, String>, existing: Map<String, String>): Map<String, String> =
        incoming.mapValues { (providerId, value) ->
            if (value.isNotBlank()) value else existing[providerId].orEmpty()
        }

    /* ------------------------------- 备用 Key 池 ------------------------------- */

    /**
     * 把 Key 池里每个槽位的 `key` 置空，保留 `id` / `label`。
     *
     * 保留槽位形状是有意的：恢复后用户能在「备用 Key」列表里看到自己配过几个槽位、
     * 分别叫什么名字，只需补 Key，而不是从零重建整套结构。
     */
    fun redactKeyPool(rawJson: String): String {
        val map = parseKeyPool(rawJson) ?: return CLEARED
        val out = map.mapValues { (_, slots) ->
            slots.map { el ->
                val slot = el as? JsonObject ?: return@map el
                JsonObject(slot + ("key" to JsonPrimitive(CLEARED)))
            }
        }
        return json.encodeToString(POOL_TYPE, out)
    }

    /**
     * 恢复时把空 Key 槽位换回本机同 id 槽位的 Key；本机没有的槽位保持空（等用户填）。
     *
     * @param incomingJson 备份件里的 Key 池 JSON。
     * @param existingJson 本机当前 Key 池 JSON。
     */
    fun mergeKeyPool(incomingJson: String, existingJson: String): String {
        val incoming = parseKeyPool(incomingJson) ?: return existingJson
        val existing = parseKeyPool(existingJson) ?: emptyMap()

        val out = incoming.mapValues { (profileId, slots) ->
            val localById = existing[profileId].orEmpty()
                .mapNotNull { el ->
                    val s = el as? JsonObject ?: return@mapNotNull null
                    val id = s["id"]?.primOrNull()?.contentOrNull ?: return@mapNotNull null
                    val k = s["key"]?.primOrNull()?.contentOrNull
                    if (k.isNullOrBlank()) null else id to k
                }.toMap()

            slots.map { el ->
                val slot = el as? JsonObject ?: return@map el
                val own = slot["key"]?.primOrNull()?.contentOrNull.orEmpty()
                if (own.isNotBlank()) return@map slot
                val id = slot["id"]?.primOrNull()?.contentOrNull.orEmpty()
                val inherited = localById[id] ?: return@map slot
                JsonObject(slot + ("key" to JsonPrimitive(inherited)))
            }
        }
        return json.encodeToString(POOL_TYPE, out)
    }

    /* --------------------------------- 内部 --------------------------------- */

    /**
     * Key 池的线上类型：`{profileId: [{id,label,key}, ...]}`。
     * 槽位按 [JsonElement] 收，避免依赖 `KeyPoolPolicy.KeySlot`（它在 llm 包里，
     * 反序列化需要它的 @Serializable 上下文），也让本文件保持零 Android / 零业务依赖。
     */
    private val POOL_TYPE = MapSerializer(
        String.serializer(),
        ListSerializer(JsonElement.serializer())
    )

    private fun parseArray(raw: String): JsonArray? {
        if (raw.isBlank()) return null
        return runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
    }

    private fun parseKeyPool(raw: String): Map<String, List<JsonElement>>? {
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString(POOL_TYPE, raw) }.getOrNull()
    }
}
