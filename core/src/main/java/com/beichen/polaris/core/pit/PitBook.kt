package com.beichen.polaris.core.pit

import com.beichen.polaris.core.io.AtomicJsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一个「坑」：某个位置历史上失败过几次（总表 §11.21.2）。
 *
 * 总表给的三个属性是位置 / 频率 / 后果。这里落前两个：
 *  - **位置** = [key]（复用 `FailureGuard.fingerprint`：工具名 + 归一化参数，FNV-1a）。
 *    为什么不自己造一套指纹：同参数判定「是不是同一个地方」这件事，`FailureGuard`
 *    已经在回合内做了几百次；再造一套只会出现「熔断认为是同一个调用、坑图认为是两个」。
 *  - **频率** = [hits]，纯计数。**不让模型估**——模型估「大概错过几次」必然编。
 *  - **后果**留空：要判断「点了会扣款还是只是没反应」得看界面变化，今天拿不到。
 *    留空比填一个猜的强：填了猜的，用户会当真。
 *
 * @param code 最近一次失败的归一化结果码（如 `click_not_found` / `approval_denied`）。
 *   保留「最近一次」而不是「全部」：坑图是给下一次预警用的，最近一次最能说明现在长什么样。
 * @param sample 最近一次失败的参数摘要（截断）。给人看也给它看——`ui_click {"text":"确认"}`
 *   比一串指纹哈希有用得多。
 */
@Serializable
data class Pit(
    val key: String,
    val tool: String,
    val code: String,
    val sample: String,
    val hits: Int = 1,
    val firstAt: Long = 0,
    val lastAt: Long = 0
)

/**
 * 失败记录出口。由 [PitBook] 实现；`FailureGuard` 只认这个接口，
 * 因此 core 的熔断逻辑不依赖文件 IO，harness 里可以塞一个假的。
 */
fun interface PitSink {
    fun onFailed(tool: String, key: String, code: String, sample: String)

    /**
     * 读取该位置的历史记录——**只用于把「错在哪」告诉模型**，不参与判定。
     *
     * 为什么放在同一个接口里而不是另起一个：写和读是同一个数据的两个方向，
     * 分成两个接口会出现「只实现了写、读永远返回 null」的形态，而那个 null
     * 与「这里没错过」无法区分。
     *
     * 为什么给默认实现：它是 `fun interface`，多一个抽象方法会让 `PitSink { … }`
     * 的 lambda 构造失效——harness 里的假实现不该为了这个改动而重写。
     * 默认 null 的语义是「本实现不提供读」，与「查了但没有」在消费方同样安全
     * （都是不附言），所以不额外区分。
     */
    fun pitAt(key: String): Pit? = null
}

/**
 * 坑图：跨会话累积「哪个位置容易错」，用于**预警**，不用于熔断。
 *
 * ## 为什么必须与 FailureGuard 分开存
 *
 * `FailureGuard.resetForTurn()` 的注释记着 W-8 那次修复：失败指纹原来随 loop 活在
 * ViewModel 上、全仓无人 clear，**跨回合跨会话永久存活，只有杀 App 能清**，于是
 * 「服务没就绪」这种一次性故障把某个指纹永久毒化，后面每次调用都被挡死。
 *
 * 所以这一份数据**绝不能**回流进熔断判定，否则等于把 W-8 原样装回来。两者的区别是本质的：
 *
 * | | FailureGuard | PitBook |
 * |---|---|---|
 * | 作用域 | 一个回合 | 永久 |
 * | 用途 | **拦截**（挡掉重复送死） | **告知**（提醒这里容易错） |
 * | 清空 | 每回合必清 | 只有用户手动清 |
 * | 错了的代价 | 用户被无故拦住 | 提醒了但其实没事（可容忍） |
 *
 * 一句话：**熔断错了会挡死用户，预警错了只是多说一句话。** 前者必须保守，后者可以大胆。
 *
 * ## 为什么不做 BM25
 *
 * `MemoryStore` 那套 BM25 是为「用户几千条自然语言记忆」设计的。坑图规模是几百条、
 * 每条带结构化字段（tool / code），查询也基本是「这个工具 / 这个页面」——包含匹配
 * 加计数排序就够了。引入 BM25 会让「错得最多的那个坑」被文档长度和词频压下去，
 * 而这里**频率本身就该是主排序键**。
 */
class PitBook(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis
) : PitSink {

    private val lock = Any()
    private val pits = LinkedHashMap<String, Pit>()

    init {
        load()
    }

    /**
     * 记一次失败（同位置累加）。[PitSink] 的实现——由 `FailureGuard` 在
     * 「真实执行且失败且非瞬时」时调用。
     *
     * 瞬时故障（`service_not_ready` 等）不会走到这里：`FailureGuard.onExecuted`
     * 在 transient 时直接 return。这不是遗漏——环境没就绪不算「这个位置有坑」，
     * 把它记进来会让坑图充满噪音，而噪音多了用户就不看了。
     */
    override fun onFailed(tool: String, key: String, code: String, sample: String) {
        record(tool, key, code, sample)
    }

    /** 同 [onFailed]，但可注入时刻（harness 用来构造确定的时间线）。 */
    fun record(
        tool: String,
        key: String,
        code: String,
        sample: String,
        at: Long = now()
    ): Pit {
        val trimmed = sample.trim().take(MAX_SAMPLE_CHARS)
        val updated = synchronized(lock) {
            val old = pits[key]
            val pit = if (old == null) {
                Pit(key = key, tool = tool, code = code, sample = trimmed, hits = 1, firstAt = at, lastAt = at)
            } else {
                // hits 只增不减：坑被填上之后计数不清零是有意的——「这个位置历史上错过 5 次」
                // 这件事不会因为第 6 次成功就变成假话。要清零只有用户手动清。
                old.copy(code = code, sample = trimmed, hits = old.hits + 1, lastAt = at)
            }
            pits[key] = pit
            trimToLimit()
            pit
        }
        persist()
        return updated
    }

    /**
     * 预警查询：这个位置是否值得在动手**之前**提一句。
     *
     * 阈值 [WARN_HITS] 取 3：错 1 次是偶然，错 2 次可能是参数问题，错到第 3 次
     * 才够得上「这里有问题」。阈值低了会变成每次都提醒（用户很快就不看），
     * 高了则永远等不到——而坑图的价值全在「提前说」。
     *
     * @return null = 不值得提醒（没记录或次数不够）。
     */
    fun warnFor(key: String, minHits: Int = WARN_HITS): Pit? {
        val pit = synchronized(lock) { pits[key] } ?: return null
        return if (pit.hits >= minHits) pit else null
    }

    /**
     * [PitSink] 的读口：给熔断拦截时的文案用。
     *
     * 与 [warnFor] 的区别只在阈值——这里**不设阈值**（`minHits = 1`）。
     * 理由：走到这个分支时熔断已经决定拦截了，此刻哪怕只错过 1 次，
     * 「上次错在 xxx」也是模型唯一能拿到的线索；再说，能触发
     * `requires_changed_call` 本身就意味着本轮已经错够两次了。
     */
    override fun pitAt(key: String): Pit? = warnFor(key, minHits = 1)

    /** 某个工具下的全部坑，错得多的在前。 */
    fun pitsOf(tool: String): List<Pit> =
        synchronized(lock) { pits.values.filter { it.tool == tool } }
            .sortedWith(compareByDescending<Pit> { it.hits }.thenByDescending { it.lastAt })

    /**
     * 关键词检索（工具名 / 结果码 / 参数摘要任一命中）。
     *
     * 排序主键是 [Pit.hits] 而不是匹配度：坑图的用途是「先看最坑的」，
     * 一个匹配度稍低但错过 50 次的位置，比匹配度高只错过 1 次的更该被看见。
     */
    fun search(query: String, topK: Int = 5): List<Pit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return top(topK)
        val hits = synchronized(lock) { pits.values.toList() }.filter { p ->
            p.tool.lowercase().contains(q) ||
                p.code.lowercase().contains(q) ||
                p.sample.lowercase().contains(q)
        }
        return hits
            .sortedWith(compareByDescending<Pit> { it.hits }.thenByDescending { it.lastAt })
            .take(topK.coerceAtLeast(1))
    }

    /** 最坑的 N 个（跨全部工具）。 */
    fun top(limit: Int = 10): List<Pit> =
        synchronized(lock) { pits.values.toList() }
            .sortedWith(compareByDescending<Pit> { it.hits }.thenByDescending { it.lastAt })
            .take(limit.coerceAtLeast(0))

    fun size(): Int = synchronized(lock) { pits.size }

    fun snapshot(): List<Pit> = synchronized(lock) { pits.values.toList() }

    /** 清空（用户在设置里「忘掉这些坑」时用）。 */
    fun clear() {
        synchronized(lock) { pits.clear() }
        persist()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 容量保护：淘汰「错得最少且最久没出现」的。
     *
     * 为什么不淘汰最老的：一个三年前错过 200 次的位置比上周错过 2 次的更值得留——
     * 坑图记的是界面，界面不常变，老坑往往还在。
     */
    private fun trimToLimit() {
        while (pits.size > MAX_PITS) {
            val victim = pits.values
                .minWithOrNull(compareBy<Pit> { it.hits }.thenBy { it.lastAt })
                ?.key
                ?: return
            pits.remove(victim)
        }
    }

    private fun load() {
        val parsed = runCatching {
            if (file.exists()) json.decodeFromString<List<Pit>>(file.readText()) else null
        }.getOrNull() ?: return
        synchronized(lock) {
            pits.clear()
            parsed.forEach { pits[it.key] = it }
        }
    }

    /**
     * 原子写。失败不让主流程崩：记不住一个坑，远没有「因为记坑把对话打挂」严重。
     */
    private fun persist() {
        val payload = runCatching {
            json.encodeToString(snapshot())
        }.getOrNull() ?: return
        runCatching { AtomicJsonFile.write(file, payload) }
    }

    companion object {
        /** 错到第 3 次才提；见 [warnFor]。 */
        const val WARN_HITS = 3

        /** 坑图上限。比记忆库（500）小：坑是高频复用的小集合，不是全量日志。 */
        const val MAX_PITS = 200

        /** 参数摘要上限。够看清「点的是哪个按钮」，又不至于把整棵 UI 树存进来。 */
        const val MAX_SAMPLE_CHARS = 160

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
