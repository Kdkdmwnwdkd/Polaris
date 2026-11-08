package com.beichen.polaris.skill.market

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 市场源列表的持久化（W-48）。
 *
 * 只存**用户添加的源地址**：插件列表每次刷新都从网络重新拉，不做本地镜像——
 * 市场内容的权威在远端，缓存反而会让「已下架/已修复」的条目长期赖在本地。
 *
 * 存储用 SharedPreferences（与 ConfigStore / AutomationPrefs 一致，不引新依赖）；
 * 解码失败一律退回空列表：宁可让用户重填一次地址，也不能让一个坏值把市场页搞崩。
 */
class MarketStore(context: Context) {

    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** 读取全部源（已去重、已校验协议）。 */
    fun loadSources(): List<MarketSource> {
        val raw = sp.getString(KEY_SOURCES, null) ?: return emptyList()
        val parsed = runCatching { json.decodeFromString<List<MarketSource>>(raw) }.getOrNull()
            ?: return emptyList()
        // 二次校验：老版本可能写进过非法地址；同时去重保持顺序
        val seen = HashSet<String>()
        return parsed.mapNotNull { src ->
            val url = MarketRules.validHttpUrl(src.url) ?: return@mapNotNull null
            if (!seen.add(url)) null else MarketSource(url)
        }
    }

    /** 覆盖保存（调用方保证已校验）。 */
    fun saveSources(sources: List<MarketSource>) {
        val clean = sources.map { it.copy(url = it.url.trim()) }
            .filter { MarketRules.validHttpUrl(it.url) != null }
            .distinctBy { it.url }
        sp.edit().putString(KEY_SOURCES, json.encodeToString(clean)).apply()
    }

    /**
     * 添加一个源。
     *
     * @return true = 新增成功；false = 地址非法或已存在（调用方据此提示，而不是静默吞掉）。
     */
    fun addSource(raw: String): Boolean {
        val url = MarketRules.validHttpUrl(raw) ?: return false
        val current = loadSources()
        if (current.any { it.url == url }) return false
        saveSources(current + MarketSource(url))
        return true
    }

    /** 删除一个源（按规范化后的地址精确匹配）。 */
    fun removeSource(url: String): Boolean {
        val target = MarketRules.validHttpUrl(url) ?: return false
        val current = loadSources()
        val next = current.filterNot { it.url == target }
        if (next.size == current.size) return false
        saveSources(next)
        return true
    }

    private companion object {
        const val PREFS_NAME = "agentkit_market"
        const val KEY_SOURCES = "market_sources"
    }
}
