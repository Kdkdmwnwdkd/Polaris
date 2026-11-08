package com.beichen.polaris.core.llm

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 备用 Key 池持久化（W-21）：按配置档案（profileId）分组存槽位。
 *
 * 只存 id/label/key 三件套；**冷却状态不落盘**（内存态，见 [KeyPoolRuntime]）——
 * 重启即重置，避免一次限流把 Key 长期判死。
 *
 * Key 以明文存 SharedPreferences（与既有 apiKey 处理一致，加密不在本批范围）；
 * 任何对外展示/日志路径都必须先过脱敏（前后各留 4 位）。
 */
class KeyPoolStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** 读取某档案的备用 Key 列表。 */
    fun list(profileId: String): List<KeyPoolPolicy.KeySlot> = runCatching {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        json.decodeFromString<List<KeyPoolPolicy.KeySlot>>(raw)
    }.getOrDefault(emptyList())

    /** 覆盖写入某档案的备用 Key 列表。 */
    fun save(profileId: String, slots: List<KeyPoolPolicy.KeySlot>) {
        prefs.edit().putString(key(profileId), json.encodeToString(slots)).apply()
    }

    /** 追加一把 Key；同内容已存在则返回已有槽位（不重复添加）。 */
    fun add(profileId: String, label: String, rawKey: String): KeyPoolPolicy.KeySlot {
        val trimmed = rawKey.trim()
        val current = list(profileId)
        // 同 Key 内容已存在则视为重复，不重复添加
        current.firstOrNull { it.key == trimmed }?.let { return it }
        // W-22 修：用 UUID 而不是时间戳——同一毫秒连续添加两把会导致 id 相同、
        // 后一把在 UI/存储里覆盖前一把（用户会看到「加了两把只剩一把」）。
        val slot = KeyPoolPolicy.KeySlot(
            id = "k${java.util.UUID.randomUUID().toString().take(8)}",
            label = label.ifBlank { "备用 Key ${current.size + 1}" },
            key = trimmed
        )
        save(profileId, current + slot)
        return slot
    }

    fun remove(profileId: String, slotId: String) {
        save(profileId, list(profileId).filterNot { it.id == slotId })
    }

    fun clear(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    /**
     * W-26：导出全部档案的备用 Key 池（备份用）。
     * 结构：`{"profileId":[{"id","label","key"}...]}`；无池的档案不写。
     */
    fun exportAll(): String {
        val all = prefs.all
        val map = LinkedHashMap<String, List<KeyPoolPolicy.KeySlot>>()
        all.forEach { (k, v) ->
            if (!k.startsWith("pool_") || v !is String) return@forEach
            val profileId = k.removePrefix("pool_")
            runCatching {
                val slots = json.decodeFromString<List<KeyPoolPolicy.KeySlot>>(v)
                if (slots.isNotEmpty()) map[profileId] = slots
            }
        }
        return json.encodeToString(map)
    }

    /**
     * W-26：从备份恢复全部 Key 池（覆盖同档案的现有池）。
     * @return 恢复的档案数；JSON 非法返回 0（不抛，备份恢复不应因它失败）。
     */
    fun importAll(raw: String): Int {
        if (raw.isBlank()) return 0
        val map = runCatching {
            json.decodeFromString<Map<String, List<KeyPoolPolicy.KeySlot>>>(raw)
        }.getOrDefault(emptyMap())
        map.forEach { (profileId, slots) ->
            if (profileId.isBlank() || slots.isEmpty()) return@forEach
            prefs.edit().putString(key(profileId), json.encodeToString(slots)).apply()
        }
        return map.size
    }

    /** 展示用脱敏 Key：只留前 4 后 4，中间统一打码。 */
    fun masked(rawKey: String): String {
        val k = rawKey.trim()
        if (k.length <= 8) return "****"
        return "${k.take(4)}****${k.takeLast(4)}"
    }

    private fun key(profileId: String) = "pool_$profileId"

    private companion object {
        const val PREFS = "agentkit_keypool"
    }
}
