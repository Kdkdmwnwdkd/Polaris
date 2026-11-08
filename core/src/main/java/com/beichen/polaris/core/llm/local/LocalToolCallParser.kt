package com.beichen.polaris.core.llm.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 本地小模型的工具调用解析器——**宽容版**。
 *
 * ## 为什么不能只写「解析 JSON，失败就当文本」
 *
 * 3B 级别的模型输出工具调用时，几乎不会一次就把 JSON 写对。实测常见的有：
 *
 * ```
 * {"tool": "read_file", "arguments": {"path": "a.txt",}}      ← 尾逗号
 * ```json                                                       ← 自己加了代码块
 * {"tool": "read_file", "arguments": {"path": "a.txt"}}
 * ```
 * <tool_call>{"name": "read_file", "arguments": {"path": "a.txt"}}</tool_call>  ← 训练时的原生格式
 * {"tool": "readfile", ...}                                     ← 工具名少了下划线
 * {"tool": "cat", ...}                                          ← 用 shell 习惯叫工具
 * {"tool": "write_file", "arguments": {"content": "第一行
 * 第二行"}}                                                     ← 字符串里裸换行
 * {"tool": "read_file", "arguments": {"path": "a.txt"           ← 被长度上限截断
 * ```
 *
 * 这些**全都是想调工具**，只要读得出来就该执行；一律按普通文本处理，用户看到的是
 * 「模型回了一段乱码」，体验比不调工具更差。
 *
 * ## 四道关
 *
 * 1. **取候选**：`<tool_call>` 标签 / ``` 围栏 / 裸 JSON，三处都找；
 * 2. **修 JSON**：尾逗号、裸换行、单引号、Python 的 True/False/None、被截断的括号；
 * 3. **认名字**：精确 → 忽略大小写 → 去分隔符 → 别名表 → 编辑距离 ≤2；
 * 4. **补参数**：`arguments` 没写就把剩下的顶层键当参数；参数值是 JSON 字符串就再解一层。
 *
 * 只有四道关都过不了才返回失败——失败时带上原因，让上层能决定「静默重试一次」还是
 * 「当文本显示」。
 */
internal object LocalToolCallParser {

    /**
     * 解析结果。
     *
     * @param name 归一化后的工具名（null = 没认出来）
     * @param args 解析出的参数（解析失败时为空对象）
     * @param error 认出来了但读不动时的原因（给重试提示用）
     */
    data class Result(val name: String?, val args: JsonObject, val error: String? = null) {
        val ok: Boolean get() = name != null
    }

    /** 严格解析器：先按标准 JSON 试，失败再上宽松模式（宽松模式会放过不带引号的键）。 */
    private val json = Json { ignoreUnknownKeys = true }

    private val THINK = Regex("(?is)<think>.*?</think>")

    /** 各家的原生标签。Qwen 系在训练里见过 `<tool_call>`，不认会白白丢掉一次调用。 */
    private val TAG_OPEN = Regex("(?i)<tool_call>|<\\|tool_call>|tool_call\\s*:")
    private val TAG_CLOSE = Regex("(?i)</tool_call>|<tool_call\\|>")

    private val FENCE = Regex("```[ \\t]*([A-Za-z_]*)[ \\t]*\\r?\\n?(.*?)(?:```|$)", RegexOption.DOT_MATCHES_ALL)

    private val NAME_KEYS = listOf("tool", "name", "tool_name", "toolName", "function")
    private val ARG_KEYS = listOf("arguments", "args", "parameters", "params", "input")

    /**
     * 模型常把工具叫成 shell 命令或英文口语；能对上的直接换成本项目真名。
     *
     * **键必须是 `CapabilityManifest` 里真实存在的工具名**。这里曾有 `list_dir` /
     * `search_files`（把 `ls` / `grep` / `find` 往那两个名字上归一）——可那两个工具
     * 从来没实现过。它不会误命中（[nameOf] 只在 available 里找），但会让人误判
     * 「模型说 ls 我们能接住」，实际是继续落到「工具名不在可用列表」。
     * 现在 `ls` / `grep` 这类不再映射：模型要列目录就走 `shell_run`，
     * 那条路参数形态不同，不该由归一化硬塞。
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "read_file" to listOf("read", "cat", "open", "view", "readfile", "getfile"),
        "write_file" to listOf("write", "save", "create", "writefile", "newfile"),
        "edit_file" to listOf("edit", "patch", "replace", "modify", "editfile"),
        "http_request" to listOf("http", "fetch", "curl", "request", "webrequest", "get"),
        "ui_tree" to listOf("uitree", "dumpui", "screen", "uiscan", "readscreen"),
        "ui_tap" to listOf("tap", "click", "uitap", "touch", "press"),
        "clipboard" to listOf("clip", "copy", "paste", "clipboardread"),
        "use_skill" to listOf("skill", "useskill", "runskill", "loadskill"),
        "shell_run" to listOf("sh", "bash", "shell", "exec", "run", "terminal", "command"),
    )

    /**
     * 解析一段模型输出。
     *
     * @param available 本轮真正可用的工具名（归一化只在这张表里找，**绝不返回表外的名字**，
     *   避免模型瞎编一个工具名后我们照单全收）
     */
    fun parse(text: String, available: Set<String>): Result {
        val clean = THINK.replace(text, "").trim()
        if (clean.isEmpty()) return Result(null, JsonObject(emptyMap()), "空回复")

        val candidates = candidates(clean)
        for (raw in candidates) {
            val obj = parseObject(raw) ?: continue
            val (key, name) = nameOf(obj, available) ?: continue
            return Result(name, argsOf(obj, key))
        }
        // 一个都没成：给出最可能的原因（有 JSON 但认不出工具名 / 压根没有 JSON）
        val error = when {
            candidates.isEmpty() -> "没有找到 JSON"
            candidates.all { parseObject(it) == null } -> "JSON 无法修复"
            else -> "工具名不在可用列表"
        }
        return Result(null, JsonObject(emptyMap()), error)
    }

    // —— 第一关：取候选 ————————————————————————————————————————————

    /**
     * 按「越像工具调用越靠前」列出待解析的片段。
     *
     * 顺序很重要：模型有时候先写一段解释再写调用（"好的，我读一下" + JSON），
     * 从标签/围栏里取到的才是真正的调用。
     */
    private fun candidates(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // 1) <tool_call>…</tool_call> / tool_call:…（LFM 的写法）
        TAG_OPEN.findAll(text).forEach { m ->
            val rest = text.substring(m.range.last + 1)
            val close = TAG_CLOSE.find(rest)
            val body = if (close != null) rest.substring(0, close.range.first) else rest
            objectIn(body)?.let { out.add(it) }
        }
        // 2) ```tool / ```json / ``` 围栏
        FENCE.findAll(text).forEach { m ->
            val body = m.groupValues[2]
            if (body.contains('{')) objectIn(body)?.let { out.add(it) }
        }
        // 3) 裸 JSON（整段或最后一段）
        objectIn(text)?.let { out.add(it) }
        return out.toList()
    }

    /**
     * 从任意文本里抠出第一个**括号配平**的 JSON 对象。
     *
     * 不用 `indexOf('}')`：文件内容里出现 `}` 是常事（写 JSON 文件就是典型场景），
     * 必须按字符串状态机走到配平为止。
     */
    private fun objectIn(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inStr && c == '\\' -> escaped = true
                c == '"' -> inStr = !inStr
                inStr -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        // 没配平 = 被截断了；把剩下的全给它，交给 repair 去补括号
        return text.substring(start)
    }

    // —— 第二关：修 JSON ————————————————————————————————————————————

    /** 依次尝试：原样 → 宽松解析 → 修一遍 → 补截断。全失败返回 null。 */
    private fun parseObject(raw: String): JsonObject? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        strict(t)?.let { return it }
        lenient(t)?.let { return it }
        val fixed = repair(t)
        lenient(fixed)?.let { return it }
        return lenient(closeUnbalanced(fixed))
    }

    private fun strict(s: String): JsonObject? = runCatching {
        json.parseToJsonElement(s) as? JsonObject
    }.getOrNull()

    /** 宽松解析（允许不带引号的键）；仍要求结构完整。 */
    private fun lenient(s: String): JsonObject? = runCatching {
        Json { isLenient = true; ignoreUnknownKeys = true }.parseToJsonElement(s) as? JsonObject
    }.getOrNull()

    /**
     * 修掉小模型最常犯的几处语法错。
     *
     * 顺序有意：先处理「字符串内部」的问题（裸换行、单引号），再处理结构问题（尾逗号），
     * 否则会把文件内容里的逗号也当成尾逗号删掉。
     */
    private fun repair(s: String): String {
        var t = s
        // 1) Python 字面量（只在严格/宽松都失败后才走到这里，误伤风险已被隔离在「反正也解析不了」的分支）
        t = t.replace(Regex("\\bTrue\\b"), "true")
            .replace(Regex("\\bFalse\\b"), "false")
            .replace(Regex("\\bNone\\b"), "null")
        // 2) 字符串里的裸换行 / 制表符 → 转义
        t = escapeRawWhitespace(t)
        // 3) 尾逗号：,} / ,]
        t = t.replace(Regex(",\\s*([}\\]])"), "$1")
        // 4) JSON 里不允许单引号字符串：只有在整段没有双引号时才整体替换，否则会毁掉内容
        if (!t.contains('"')) t = t.replace('\'', '"')
        return t
    }

    /** 把字符串字面量内部的换行/制表符转义（JSON 不允许裸换行）。 */
    private fun escapeRawWhitespace(s: String): String {
        val out = StringBuilder(s.length + 16)
        var inStr = false
        var escaped = false
        for (c in s) {
            when {
                escaped -> { out.append(c); escaped = false }
                c == '\\' -> { out.append(c); escaped = true }
                c == '"' -> { out.append(c); inStr = !inStr }
                inStr && c == '\n' -> out.append("\\n")
                inStr && c == '\r' -> Unit
                inStr && c == '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** 被长度上限截断的 JSON：补上没关的引号与括号。 */
    private fun closeUnbalanced(s: String): String {
        val out = StringBuilder(s)
        var inStr = false
        var escaped = false
        var braces = 0
        for (c in s) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inStr = !inStr
                inStr -> Unit
                c == '{' -> braces++
                c == '}' -> braces--
            }
        }
        if (inStr) out.append('"')
        // 截断常停在 `"key":` 或 `"key": "a",` 这种位置，补 null 比报错好
        var tail = out.toString().trimEnd()
        if (tail.endsWith(',') || tail.endsWith(':')) {
            out.append("null")
        }
        repeat(braces.coerceAtLeast(0)) { out.append('}') }
        return out.toString()
    }

    // —— 第三关：认名字 ————————————————————————————————————————————

    /**
     * 从对象里取工具名并归一化到 [available] 里的真名，同时返回**是哪个键**提供了名字。
     *
     * 返回键名是为了 [argsOf]：`use_skill` 自己就有一个 `name` 参数，如果无脑把
     * 「像工具名的键」都从参数里剔掉，`{"tool":"use_skill","name":"daily-news"}`
     * 这种平铺写法就会丢掉技能名。
     *
     * `function` 键有两种形态：`{"function": "read_file"}` 和 OpenAI 的
     * `{"function": {"name": "read_file"}}`，两种都认。
     */
    private fun nameOf(obj: JsonObject, available: Set<String>): Pair<String, String>? {
        if (available.isEmpty()) return null
        for (k in NAME_KEYS) {
            val el = obj[k] ?: continue
            val raw = when {
                el is JsonPrimitive && el !is JsonNull -> el.contentOrNull
                el is JsonObject -> el["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
            if (raw.isNullOrBlank()) continue
            // 模型有时写 "read_file(path=...)" 这种混格式，括号后面的丢掉
            val cleaned = raw.substringBefore('(').substringBefore(' ').trim()
            if (cleaned.isEmpty()) continue
            canonicalize(cleaned, available)?.let { return k to it }
        }
        return null
    }

    /**
     * 名字归一化：精确 → 忽略大小写 → 去分隔符 → 别名 → 编辑距离 ≤2。
     *
     * 最后一档（编辑距离）是有意的：`readflle` / `read_fle` 这种拼错，如果不救，
     * 整次调用就废了，而模型下一轮未必改得对。
     */
    fun canonicalize(raw: String, available: Set<String>): String? {
        if (raw in available) return raw
        val lower = raw.lowercase()
        available.firstOrNull { it.lowercase() == lower }?.let { return it }
        val key = lower.filter { it.isLetterOrDigit() }
        if (key.isEmpty()) return null
        available.firstOrNull { it.lowercase().filter { c -> c.isLetterOrDigit() } == key }?.let { return it }
        ALIASES.entries.firstOrNull { (real, alias) ->
            real in available && (key == real.filter { it.isLetterOrDigit() } || alias.any { it == key })
        }?.let { return it.key }
        // 前缀匹配：`read` → `read_file`（只在唯一命中时采用，避免 read/write 混淆）
        val prefixed = available.filter { it.lowercase().startsWith(lower) && lower.length >= 3 }
        if (prefixed.size == 1) return prefixed.first()
        if (raw.length >= 4) {
            available.firstOrNull { editDistanceLe2(key, it.lowercase().filter { c -> c.isLetterOrDigit() }) }
                ?.let { return it }
        }
        return null
    }

    private fun editDistanceLe2(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 2) return false
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            if (cur.min() > 2) return false
            prev = cur
        }
        return prev[b.length] <= 2
    }

    // —— 第四关：补参数 ————————————————————————————————————————————

    /**
     * 取参数对象。
     *
     * 三种形态都要认：
     *  - `{"tool":"x","arguments":{...}}`；
     *  - `{"tool":"x","arguments":"{\"path\":\"a\"}"}`（把 JSON 又序列化成字符串，小模型常干）；
     *  - `{"tool":"x","path":"a"}`（干脆不带 arguments，参数平铺）。
     *
     * @param nameKey 提供工具名的那个键（`tool`/`name`/`function`…），只在平铺兜底时剔除，
     *   其他键一律当作参数——`name` 同时也是 `use_skill` 的合法参数。
     */
    private fun argsOf(obj: JsonObject, nameKey: String): JsonObject {
        for (k in ARG_KEYS) {
            when (val el: JsonElement? = obj[k]) {
                is JsonObject -> return el
                is JsonPrimitive -> {
                    val s = el.contentOrNull ?: continue
                    val inner = objectIn(s) ?: continue
                    parseObject(inner)?.let { return it }
                }
                else -> Unit
            }
        }
        return JsonObject(obj.filterKeys { it != nameKey })
    }
}
