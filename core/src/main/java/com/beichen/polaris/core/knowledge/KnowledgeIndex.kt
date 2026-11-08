package com.beichen.polaris.core.knowledge

/**
 * W-RT6：本地知识库检索的纯函数层——分块、分词、BM25 打分，零依赖、可离线验证。
 *
 * 为什么不用 SQLite FTS / 第三方索引库：零新增依赖是硬纪律，而知识库的量级
 * （几十个本地文本文件、总量 MB 级）根本用不上倒排持久化——每次调用现扫现建
 * 内存索引，毫秒级完成，省掉一整套索引失效问题。
 *
 * 检索质量取舍：
 *  - 分词用「英文按词 + 中文二元组（bigram）」——不做词典分词，单字查询退化为
 *    一元匹配也能命中；召回不如真分词器，但对「查资料里的关键词」足够，且
 *    查询词与文档走同一套切法，天然对齐；
 *  - 打分用 BM25（k1=1.5, b=0.75）——长短文档天然公平，比裸关键词计数强得多。
 */
object KnowledgeIndex {

    /** 一个知识块：来源文件、块序号、原文。 */
    data class Chunk(val doc: String, val order: Int, val text: String)

    /** 一条检索结果：知识块 + BM25 得分（仅用于排序展示，不代表相似度百分比）。 */
    data class Scored(val chunk: Chunk, val score: Double)

    // ---------- 分块 ----------

    /** 块目标大小：太小碎、太大排（一行片段灌给模型太长），400 字上下是甜点位。 */
    private const val TARGET_CHUNK = 400

    /** 超长段落的硬上限：到 800 字必须切，不再等句读。 */
    private const val HARD_CHUNK = 800

    /**
     * 把一篇文档切成知识块：先按空行分段，小段向后合并到 [TARGET_CHUNK]，
     * 单段超 [HARD_CHUNK] 时按句读（。！？；换行）硬切。返回空列表当文档无有效内容。
     */
    fun chunkDocument(doc: String, content: String): List<Chunk> {
        val paragraphs = content.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val pieces = ArrayList<String>()
        for (p in paragraphs) {
            if (p.length <= HARD_CHUNK) {
                pieces.add(p)
            } else {
                // 句读硬切：宁可切在句号后，也不把半句话留给模型断章取义
                val sb = StringBuilder()
                for (sentence in p.split(Regex("(?<=[。！？；!?;])|(?=\n)"))) {
                    if (sb.length + sentence.length > HARD_CHUNK && sb.isNotBlank()) {
                        pieces.add(sb.toString().trim())
                        sb.setLength(0)
                    }
                    sb.append(sentence)
                    if (sb.length >= HARD_CHUNK) {
                        pieces.add(sb.toString().trim())
                        sb.setLength(0)
                    }
                }
                if (sb.isNotBlank()) pieces.add(sb.toString().trim())
            }
        }
        // 小块向后合并（最后一块可能落单，允许略超）
        val merged = ArrayList<String>()
        val sb = StringBuilder()
        for (piece in pieces) {
            if (sb.length + piece.length > TARGET_CHUNK && sb.isNotBlank()) {
                merged.add(sb.toString().trim())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(piece)
        }
        if (sb.isNotBlank()) merged.add(sb.toString().trim())

        return merged.mapIndexed { i, text -> Chunk(doc, i, text) }
    }

    // ---------- 分词 ----------

    /**
     * 查询与文档共用的切法：连续 ASCII 字母数字按词（小写）；CJK 连续串切二元组，
     * 孤字成串时保留一元。其余字符（标点/空白）丢弃。
     */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flushAscii() {
            if (sb.isNotEmpty()) { out.add(sb.toString().lowercase()); sb.setLength(0) }
        }
        var cjkBuffer: MutableList<String> = ArrayList()
        fun flushCjk() {
            if (cjkBuffer.size == 1) out.add(cjkBuffer[0])
            else for (i in 0 until cjkBuffer.size - 1) out.add(cjkBuffer[i] + cjkBuffer[i + 1])
            cjkBuffer = ArrayList()
        }
        for (ch in text) {
            when {
                ch.isLetterOrDigit() && ch.code < 0x80 -> { flushCjk(); sb.append(ch) }
                isCjk(ch) -> { flushAscii(); cjkBuffer.add(ch.toString()) }
                else -> { flushAscii(); flushCjk() }
            }
        }
        flushAscii(); flushCjk()
        return out
    }

    private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

    // ---------- BM25 ----------

    private const val K1 = 1.5
    private const val B = 0.75

    /** 建好的索引：现扫现建、用完即弃，不落盘不失效。 */
    class Built(private val entries: List<Entry>) {

        data class Entry(val chunk: Chunk, val tf: Map<String, Int>, val length: Int)

        private val avgLength = if (entries.isEmpty()) 0.0 else entries.sumOf { it.length }.toDouble() / entries.size
        private val df: Map<String, Int> = entries
            .flatMap { it.tf.keys }
            .groupingBy { it }
            .eachCount()

        /**
         * 检索：查询词与文档同一套分词，命中词条按 BM25 累计。
         * [topK] 上限 8——给模型的片段贵，多了灌水。
         */
        fun search(query: String, topK: Int = 4): List<Scored> {
            val qTokens = tokenize(query)
            if (qTokens.isEmpty() || entries.isEmpty()) return emptyList()
            val n = entries.size
            val scores = HashMap<Chunk, Double>()
            for (term in qTokens.toSet()) {
                val dft = df[term] ?: continue
                val idf = kotlin.math.ln(1.0 + (n - dft + 0.5) / (dft + 0.5))
                for (e in entries) {
                    val f = e.tf[term] ?: continue
                    val denom = f + K1 * (1 - B + B * e.length / avgLength)
                    val s = idf * f * (K1 + 1) / denom
                    scores[e.chunk] = (scores[e.chunk] ?: 0.0) + s
                }
            }
            return scores.entries
                .sortedByDescending { it.value }
                .take(topK.coerceIn(1, 8))
                .map { Scored(it.key, it.value) }
        }
    }

    /** 从多篇文档建索引：name 当块来源标签（展示给模型看），content 为全文。 */
    fun build(docs: List<Pair<String, String>>): Built = Built(
        docs.flatMap { (name, content) -> chunkDocument(name, content) }
            .map { c ->
                val tokens = tokenize(c.text)
                Built.Entry(c, tokens.groupingBy { it }.eachCount(), tokens.size)
            }
    )
}
