package com.beichen.polaris.core.llm.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 *
 * ## 2026-11-19：三个「解析出来了一个错的东西」的缺陷
 *
 * 这一节记在这里，是因为下面每一道关的实现都带着这三次修正的痕迹。
 * 详细的复现与断言见 `LocalToolCallParserTest` 的类注释。
 *
 * 1. **OpenAI 的 `function` 形态丢参数**——名字认出来了，`arguments` 在下一层没取到，
 *    `Result.ok` 却是真，于是带着空参数执行（`write_file` 会用空内容覆盖文件）。
 *    修法是 [locate]：名字与参数一起定位，参数永远跟着名字所在的那一层走。
 * 2. **修复阶段改的是字符串内容**——`\bTrue\b`、`,\s*[}\]]` 全文无差别替换，
 *    把用户要写的文件内容一起改了。修法是 [replaceOutsideStrings]：
 *    先算「哪些字符在字符串里」，只改结构位置。
 * 3. **解说里的一对花括号让整次调用作废**——原来只抠全文第一个 `{…}`，
 *    「我读一下 {这个} 文件」之后真正的调用再也轮不到。修法是 [objectsIn]：每个位置取全部。
 *
 * 三次的共同点：**坏的形态都是「看起来解析成功了」**。所以断言不能只测「认得出来」，
 * 必须测「认出来的参数逐字对」。
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
     * 名字与参数被包在**下一层对象**里的形态。
     *
     * - `{"function":{"name":"read_file","arguments":{…}}}`（OpenAI chat completions）
     * - `{"function_call":{"name":"read_file","arguments":"{…}"}}`（OpenAI 旧版 / 部分本地模板）
     * - `{"tool_calls":[{"function":{…}}]}` 见 [locate]
     *
     * 这三种都不是「我们自己的格式」，但 3B 模型见过大量 OpenAI 风格的训练文本，
     * 照着写是常事。**认名字却丢参数**比认不出来更糟：认不出来会触发静默重试，
     * 丢参数会带着空 `arguments` 直接执行。
     */
    private val NEST_KEYS = listOf("function", "function_call")

    // 修复用的正则提到常量：每次 repair 都新建 Regex 是纯开销，而 repair 在
    // 「解析失败」路径上可能被每条候选各调一次。
    private val PY_TRUE = Regex("\\bTrue\\b")
    private val PY_FALSE = Regex("\\bFalse\\b")
    private val PY_NONE = Regex("\\bNone\\b")
    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    /**
     * 模型常把工具叫成 shell 命令或英文口语；能对上的直接换成本项目真名。
     *
     * **键必须是 `CapabilityManifest` 里真实存在的工具名**。这里曾有 `list_dir` /
     * `search_files`（把 `ls` / `grep` / `find` 往那两个名字上归一）——可那两个工具
     * 从来没实现过。它不会误命中（[nameIn] 只在 available 里找），但会让人误判
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
        var sawJson = false
        for (raw in candidates) {
            val obj = parseObject(raw) ?: continue
            sawJson = true
            val target = locate(obj, available) ?: continue
            return Result(target.name, argsOf(target))
        }
        // 一个都没成：给出最可能的原因（有 JSON 但认不出工具名 / 压根没有 JSON）
        val error = when {
            candidates.isEmpty() -> "没有找到 JSON"
            !sawJson -> "JSON 无法修复"
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
     *
     * **每个位置取的是「全部」配平对象，不是第一个**（2026-11-19 修）。原来
     * `objectIn` 只抠出全文中第一个 `{…}`，于是模型一句「我读一下 {这个} 文件」
     * 就能让整次调用作废——那个 `{这个}` 不是 JSON，`parseObject` 返回 null，
     * 而候选列表里再没有第二个。中文里用花括号强调是常见写法，代价却是整轮白跑。
     */
    private fun candidates(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // 1) <tool_call>…</tool_call> / tool_call:…（LFM 的写法）
        TAG_OPEN.findAll(text).forEach { m ->
            val rest = text.substring(m.range.last + 1)
            val close = TAG_CLOSE.find(rest)
            val body = if (close != null) rest.substring(0, close.range.first) else rest
            out.addAll(objectsIn(body))
        }
        // 2) ```tool / ```json / ``` 围栏
        FENCE.findAll(text).forEach { m ->
            val body = m.groupValues[2]
            if (body.contains('{')) out.addAll(objectsIn(body))
        }
        // 3) 裸 JSON（全文里每一个配平对象，按出现顺序）
        out.addAll(objectsIn(text))
        return out.toList()
    }

    /**
     * 从任意文本里抠出**每一个**括号配平的 JSON 对象。
     *
     * 不用 `indexOf('}')`：文件内容里出现 `}` 是常事（写 JSON 文件就是典型场景），
     * 必须按字符串状态机走到配平为止。
     *
     * 末尾没配平（被长度上限截断）时把剩下的全给它，交给 [closeUnbalanced] 补括号。
     */
    private fun objectsIn(text: String): List<String> {
        val out = ArrayList<String>(2)
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') {
                i++
                continue
            }
            val end = matchEnd(text, i)
            if (end < 0) {
                // 截断：剩下的一整段都是候选，补括号的活交给下游
                out.add(text.substring(i))
                break
            }
            out.add(text.substring(i, end + 1))
            i = end + 1
        }
        return out
    }

    /** 从 [start]（必然是 `{`）起找配平的结束下标；没配平返回 -1。 */
    private fun matchEnd(text: String, start: Int): Int {
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
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // —— 第二关：修 JSON ————————————————————————————————————————————

    /**
     * 依次尝试：原样 → 修一遍 → 补截断。全失败返回 null。
     *
     * **修复要排在宽松解析之前**（2026-11-19 修）。原来的顺序是
     * `strict → lenient(原文) → repair → lenient(修复后)`，而 kotlinx 的
     * `isLenient` 极宽容——裸换行、尾逗号、不带引号的键它全吃，所以第二条就返回了，
     * `repair` 基本是**死代码**。这让「单引号定界」这类必须靠 repair 才能正确的输入，
     * 被 lenient 以错误的样子收下：`{tool: 'read_file', arguments: {path: 'a.txt'}}`
     * 解析出的 `path` 是 `'a.txt'`（带单引号），`read_file` 拿着它打不开任何文件。
     *
     * 现在 repair 是**字符串感知**的（只改结构位置，不动字符串内容），
     * 先修再解，两种错法都能落到位。
     */
    private fun parseObject(raw: String): JsonObject? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        strict(t)?.let { return it }
        val fixed = repair(t)
        if (fixed != t) {
            strict(fixed)?.let { return it }
            lenient(fixed)?.let { return it }
        }
        lenient(t)?.let { return it }
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
     * 修掉小模型最常犯的几处语法错——**只改结构位置，绝不碰字符串内容**。
     *
     * ## 为什么现在强调「只在结构位置」（2026-11-19 修）
     *
     * 原来三条修正是全文无差别的正则替换：
     *
     * ```kotlin
     * t.replace(Regex("\\bTrue\\b"), "true")     // 文件内容里的 "True story" 变小写
     * t.replace(Regex(",\\s*([}\\]])"), "$1")    // 内容里的 "a,}" 变成 "a}"
     * ```
     *
     * 触发条件是「同一段 JSON 在结构层也有语法错」——一旦进 repair，扫描不分内外。
     * 净效果是 `write_file` 把**改过的内容**写进文件，日志里一切正常。这类损坏
     * 比解析失败危险得多：失败会被重试，损坏会被当成用户的文件。
     *
     * 现在每条替换前先算一张「这个字符在不在字符串字面量里」的表，只改表外的位置。
     *
     * 顺序有意：单引号必须最先统一（[stringMask] 只认双引号，先换完才有正确的内外之分），
     * 裸换行最后转义（它会改变文本长度，放最后不必重算）。
     */
    private fun repair(s: String): String {
        var t = s
        // 1) 整段没有一个双引号：模型用的是单引号定界（或压根没引号），先统一成双引号。
        //    此时文本里不可能有合法的 JSON 字符串，全替换是安全的。
        if (!t.contains('"')) t = t.replace('\'', '"')
        // 2) Python 字面量（只在结构位置——字符串里的 "True story" 是用户的文件内容）
        t = replaceOutsideStrings(t, PY_TRUE) { "true" }
        t = replaceOutsideStrings(t, PY_FALSE) { "false" }
        t = replaceOutsideStrings(t, PY_NONE) { "null" }
        // 3) 尾逗号：,} / ,]（只在结构位置——内容里的 "a,}" 是用户的文件内容）
        t = replaceOutsideStrings(t, TRAILING_COMMA) { it.groupValues[1] }
        // 4) 字符串里的裸换行 / 制表符 → 转义（本来就只看字符串内）
        t = escapeRawWhitespace(t)
        return t
    }

    /** 只在**字符串字面量外**做正则替换。 */
    private fun replaceOutsideStrings(s: String, re: Regex, repl: (MatchResult) -> String): String {
        val inStr = stringMask(s)
        val sb = StringBuilder(s.length)
        var last = 0
        for (m in re.findAll(s)) {
            if (m.range.first < inStr.size && inStr[m.range.first]) continue
            sb.append(s, last, m.range.first)
            sb.append(repl(m))
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /** 每个字符是否位于字符串字面量内部（定界引号本身也算「内」，它不该被当成结构符号）。 */
    private fun stringMask(s: String): BooleanArray {
        val mask = BooleanArray(s.length)
        var inStr = false
        var escaped = false
        for (i in s.indices) {
            val c = s[i]
            when {
                escaped -> { mask[i] = true; escaped = false }
                c == '\\' && inStr -> { mask[i] = true; escaped = true }
                c == '"' -> { mask[i] = true; inStr = !inStr }
                inStr -> mask[i] = true
            }
        }
        return mask
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
     * 一次解析的落点：**名字在哪个对象里、是哪个键给的、嵌套还是平铺**。
     *
     * 拆出这个结构是为了 [argsOf]：名字可能在顶层（`{"tool":"read_file","arguments":…}`），
     * 也可能在下一层（`{"function":{"name":"read_file","arguments":…}}`）。
     * 参数永远跟着**名字所在的那个对象**走，所以两者必须一起定位。
     *
     * @param holder 名字所在的对象（顶层对象，或嵌套的 `function` / `function_call`）
     * @param nameKey 在该对象里提供名字的键
     * @param nested holder 是否为嵌套对象（平铺兜底时要剔除的键不同：嵌套剔除 `name`，顶层剔除 nameKey）
     */
    private class Target(
        val holder: JsonObject,
        val nameKey: String,
        val name: String,
        val nested: Boolean,
    )

    /**
     * 定位「工具名 + 参数所在的那个对象」。
     *
     * 三种形态按「越明确越先」的顺序试：
     *
     * 1. `{"function":{…}}` / `{"function_call":{…}}` —— 名字与参数在下一层；
     * 2. `{"tool_calls":[{…,"function":{…}}]}` —— 取第一个能认出来的元素；
     * 3. 顶层扁平（`tool` / `name` / `function: "read_file"`）。
     *
     * 嵌套形态必须排在扁平之前：`{"function":{"name":"read_file",…}}` 若按扁平处理，
     * `NAME_KEYS` 里的 `function` 也能取到名字（它内部有 `name`），但 [argsOf]
     * 会去**顶层**找 `arguments`——找不到，于是返回空参数。这个顺序就是那个缺陷的修法。
     */
    private fun locate(obj: JsonObject, available: Set<String>): Target? {
        if (available.isEmpty()) return null
        // 1) 名字与参数在下一层
        for (k in NEST_KEYS) {
            val holder = obj[k] as? JsonObject ?: continue
            nameIn(holder, available)?.let { (nk, n) -> return Target(holder, nk, n, nested = true) }
        }
        // 2) tool_calls 数组（OpenAI chat completions 的响应形态）
        val calls = obj["tool_calls"] as? JsonArray
        if (calls != null) {
            for (e in calls) {
                val fn = (e as? JsonObject)?.get("function") as? JsonObject ?: continue
                nameIn(fn, available)?.let { (nk, n) -> return Target(fn, nk, n, nested = true) }
            }
        }
        // 3) 顶层扁平
        nameIn(obj, available)?.let { (k, n) -> return Target(obj, k, n, nested = false) }
        return null
    }

    /**
     * 在**这一层**对象里取工具名并归一化到 [available] 里的真名，同时返回是哪个键提供了名字。
     *
     * 返回键名是为了 [argsOf]：`use_skill` 自己就有一个 `name` 参数，如果无脑把
     * 「像工具名的键」都从参数里剔掉，`{"tool":"use_skill","name":"daily-news"}`
     * 这种平铺写法就会丢掉技能名。
     */
    private fun nameIn(obj: JsonObject, available: Set<String>): Pair<String, String>? {
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
     * 取参数对象——**从名字所在的那一层取**，不是固定从顶层取。
     *
     * 三种形态都要认：
     *  - `{"tool":"x","arguments":{...}}`；
     *  - `{"tool":"x","arguments":"{\"path\":\"a\"}"}`（把 JSON 又序列化成字符串，小模型常干）；
     *  - `{"tool":"x","path":"a"}`（干脆不带 arguments，参数平铺）。
     *
     * 嵌套形态（`{"function":{"name":"x","arguments":{...}}}`）同样适用：参数在
     * `function` 对象里，和名字同级。这一条曾经缺失——名字认出来了、参数却是空的，
     * 而 `Result.ok` 为真，于是下游拿着空参数直接执行。
     *
     * 兜底剔除哪个键要分情况：
     *  - 嵌套：剔除 `name`（`function` 对象里除了名字都是参数）；
     *  - 顶层：只剔除提供名字的那一个键——`{"tool":"use_skill","name":"daily-news"}`
     *    里的 `name` 是**技能名**，剔掉就丢了。
     */
    private fun argsOf(target: Target): JsonObject {
        argsIn(target.holder)?.let { return it }
        val dropKey = if (target.nested) "name" else target.nameKey
        return JsonObject(target.holder.filterKeys { it != dropKey })
    }

    /** 在给定对象里按 [ARG_KEYS] 找参数；`arguments` 是字符串时再解一层。 */
    private fun argsIn(obj: JsonObject): JsonObject? {
        for (k in ARG_KEYS) {
            when (val el: JsonElement? = obj[k]) {
                is JsonObject -> return el
                is JsonPrimitive -> {
                    val s = el.contentOrNull ?: continue
                    val inner = objectsIn(s).firstOrNull() ?: continue
                    parseObject(inner)?.let { return it }
                }
                else -> Unit
            }
        }
        return null
    }
}
