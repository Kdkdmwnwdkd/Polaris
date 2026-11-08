package com.beichen.polaris.skill.market

import com.beichen.polaris.core.UiItem
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * 插件市场的数据模型与**纯函数规则**（W-48）。
 *
 * 设计约束（对齐仓库纪律）：
 *  - 零新增依赖：只依赖 kotlinx.serialization + java.net.URI；
 *  - 纯函数可测：索引清洗、URL 解析、版本比较全部无 IO，JVM harness 直接断言；
 *  - **默认不信任远端**：索引里的 id / 地址 / 哈希一律先校验再使用，非法条目**静默丢弃**
 *    （一个坏条目不该让整个市场打不开，但也不能被它牵进任意路径或协议）。
 *
 * 索引 JSON 示例（放在任意静态托管上即可）：
 * ```json
 * {
 *   "schema": 1,
 *   "name": "AgentKit 官方市场",
 *   "plugins": [
 *     {
 *       "id": "weather",
 *       "name": "天气查询",
 *       "version": "1.2.0",
 *       "description": "查询实时天气与未来三天预报",
 *       "author": "agentkit",
 *       "homepage": "https://example.com/weather",
 *       "downloadUrl": "plugins/weather-1.2.0.zip",
 *       "sha256": "……64 位十六进制……",
 *       "sizeBytes": 20480,
 *       "tags": ["生活"],
 *       "permissions": ["network"]
 *     }
 *   ]
 * }
 * ```
 *
 * `downloadUrl` 支持相对路径，按索引地址解析（`URI.resolve`），因此市场可以整体搬家。
 */

/** 市场里的一个插件（技能包）。字段全部给了默认值，老索引缺字段也能读。 */
@Serializable
data class MarketPlugin(
    val id: String,
    val name: String = "",
    val version: String = "0",
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    val downloadUrl: String,
    /** 可选：zip 的 SHA-256（64 位小写十六进制）。填了就必须匹配，防止索引与包被分别替换。 */
    val sha256: String = "",
    /** 可选：包体积（仅用于 UI 展示与提前拒绝超大包，不参与校验）。 */
    val sizeBytes: Long = 0,
    val tags: List<String> = emptyList(),
    /** 展示用：该插件声明会用到的能力（如 network / shell / ui），不参与安装决策。 */
    val permissions: List<String> = emptyList()
)

/** 市场索引（一个源一个索引文件）。 */
@Serializable
data class MarketIndex(
    val schema: Int = 1,
    val name: String = "",
    val plugins: List<MarketPlugin> = emptyList()
)

/**
 * 一个市场源。
 *
 * 只存地址：名字由索引里的 [MarketIndex.name] 提供，避免「源列表」与「索引」两处名字打架。
 */
@Serializable
data class MarketSource(val url: String)

/** 插件在当前设备上的安装状态（UI 与按钮文案的唯一判据）。 */
enum class MarketInstallState {
    /** 没装过。 */
    NOT_INSTALLED,

    /** 已装且不落后于市场版本。 */
    INSTALLED,

    /** 已装但市场版本更新。 */
    UPDATE_AVAILABLE
}

/**
 * 市场条目 + 本机安装态（UI 直接消费）。
 *
 * 放在模型层而不是 ViewModel 里：状态计算（[MarketRules.stateOf]）与展示数据同源，
 * UI 不需要自己判断「这个版本算不算更新」。
 */
data class MarketUiItem(
    val plugin: MarketPlugin,
    /** 本机已装版本；null = 没装。 */
    val installedVersion: String?,
    val state: MarketInstallState
)

/**
 * 索引清洗与版本比较规则（纯函数）。
 *
 * 这些规则是**市场安全边界**：远端 JSON 属于不可信输入，能否进列表、能否被下载，
 * 全部由这里决定——UI 与下载器不再各自做一遍判断。
 */
object MarketRules {

    /** 技能 id 白名单：与 `SkillImportRepository.installZip` 的校验保持一致。 */
    private val ID_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** 单个字段长度上限（防止一条索引把界面撑坏）。 */
    const val MAX_NAME = 80
    const val MAX_DESCRIPTION = 500
    const val MAX_URL = 2048

    /** 包体积硬上限：与 MarketClient 的下载上限一致（8MB）。 */
    const val MAX_PACKAGE_BYTES = 8L * 1024 * 1024

    /** id 是否合法（同时用于「市场 id」与「安装后目录名」）。 */
    fun isValidId(id: String): Boolean = ID_RE.matches(id.trim())

    /**
     * 规范化一个 http(s) 地址；不合法返回 null。
     *
     * 为什么必须挡协议：`downloadUrl` 来自远端，若放行 `file:`，一个恶意索引就能让 App
     * 读取本机文件并「安装」成技能；`content:` 同理可绕开网络校验。
     */
    fun validHttpUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > MAX_URL) return null
        val u = runCatching { URI(t) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.host.isNullOrBlank()) return null
        return t
    }

    /** 把可能相对的下载地址按索引地址解析为绝对地址；非法返回 null。 */
    fun resolveDownloadUrl(indexUrl: String, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val abs = runCatching { URI(indexUrl).resolve(t).toString() }.getOrNull() ?: return null
        return validHttpUrl(abs)
    }

    /**
     * 清洗索引：逐条校验、补全、去重。
     *
     * 丢弃规则（静默，不报错）：id 非法 / id 重复（保留先出现的）/ 下载地址非法。
     * 修正规则：名字空则用 id、超长截断、homepage 非法清空、sha256 非 64 位十六进制清空
     * （清空而不是丢弃——没有哈希仍可安装，只是少了完整性校验）。
     */
    fun sanitizeIndex(index: MarketIndex, indexUrl: String): MarketIndex {
        val seen = HashSet<String>()
        val out = ArrayList<MarketPlugin>(index.plugins.size)
        index.plugins.forEach { p ->
            val id = p.id.trim()
            if (!ID_RE.matches(id) || !seen.add(id)) return@forEach
            val download = resolveDownloadUrl(indexUrl, p.downloadUrl) ?: return@forEach
            out += p.copy(
                id = id,
                name = p.name.trim().ifBlank { id }.take(MAX_NAME),
                version = p.version.trim().ifBlank { "0" }.take(32),
                description = p.description.trim().take(MAX_DESCRIPTION),
                author = p.author.trim().take(MAX_NAME),
                homepage = validHttpUrl(p.homepage) ?: "",
                downloadUrl = download,
                sha256 = p.sha256.trim().lowercase()
                    .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) } ?: "",
                // 超过上限或为负 → 记为 0（未知）；不要夹到 8MB（那会把 100MB 的包伪装成 8.0MB）。
                sizeBytes = p.sizeBytes.takeIf { it in 1L..MAX_PACKAGE_BYTES } ?: 0L,
                tags = p.tags.map { it.trim().take(16) }.filter { it.isNotBlank() }.distinct().take(8),
                permissions = p.permissions.map { it.trim().take(24) }
                    .filter { it.isNotBlank() }.distinct().take(8)
            )
        }
        return index.copy(name = index.name.trim().take(MAX_NAME), plugins = out)
    }

    /**
     * 语义化版本比较（只比较主版本段）。
     *
     * 规则：去 `v` 前缀 → 按 `.` 分段 → 两边都像数字就按数值比（`1.10 > 1.9`），
     * 否则按字符串比 → 缺段补 `0`。预发布后缀（`-beta`）与构建号（`+12`）在比较时忽略，
     * 即 `1.0.0-beta` 与 `1.0.0` 视为同版本——这是**有意的简化**：技能包不做灰度，
     * 比错一个 beta 的代价远小于把版本比较写出第三种语义。
     *
     * @return 负数 / 0 / 正数，与 `compareTo` 语义一致。
     */
    fun compareVersion(a: String, b: String): Int {
        fun segments(v: String): List<String> = v.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('+').substringBefore('-')
            .split('.')
            .map { it.trim() }

        val x = segments(a)
        val y = segments(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { "0" }
            val q = y.getOrElse(i) { "0" }
            val pi = p.toIntOrNull()
            val qi = q.toIntOrNull()
            val cmp = if (pi != null && qi != null) pi.compareTo(qi) else p.compareTo(q)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** 依据「市场版本 / 本地已装版本」判定安装状态。 */
    fun stateOf(marketVersion: String, installedVersion: String?): MarketInstallState = when {
        installedVersion.isNullOrBlank() -> MarketInstallState.NOT_INSTALLED
        compareVersion(marketVersion, installedVersion) > 0 -> MarketInstallState.UPDATE_AVAILABLE
        else -> MarketInstallState.INSTALLED
    }
}
