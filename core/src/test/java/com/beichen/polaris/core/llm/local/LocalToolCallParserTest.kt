package com.beichen.polaris.core.llm.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地模型工具调用解析的行为断言（2026-11-19）。
 *
 * ## 为什么先写这批
 *
 * `LocalToolCallParser` 是「模型说的话」到「我们执行什么」之间的唯一一道转换。
 * 它解析错一个字符，下游执行的就是**另一个动作**——而且不报错。
 * 它是「零引用类」名单里价值最高的一个：360 行、四种格式、五档归一化，
 * 全仓库对它的检查只有两点—「编译过」和「调用点存在」。
 *
 * 写断言的过程中确认了两类真缺陷，都不是「解析不出来」，而是**解析出来了一个错的东西**：
 *
 * ### 一、OpenAI 的 `function` 形态丢参数
 *
 * ```
 * {"function":{"name":"read_file","arguments":{"path":"a.txt"}}}
 * ```
 *
 * `nameOf` 认出了 `read_file`（它的 KDoc 甚至写着「两种形态都认」），
 * 但 `argsOf(obj, "function")` 只在**顶层**找 `arguments`——它在 `function` 对象里。
 * 找不到就落到「平铺兜底」：`obj.filterKeys { it != "function" }` = `{}`。
 *
 * 结果是 `Result(name="read_file", args={}, error=null)`，`ok == true`。
 * 上层 `parseAssistant` 于是构造 `ToolCall("read_file", {})` 并执行：
 * 读文件变成读个没路径的文件，`write_file` 变成**用空内容覆盖**。
 * 它连「解析失败 → 静默重试一次」都不会触发，因为从 `Result` 的角度看一切正常。
 *
 * ### 二、修复阶段改的是字符串内容
 *
 * `repair` 里三条正则都不区分「结构位置」与「字符串内部」：
 *
 * | 修复 | 正则 / 动作 | 误伤 |
 * |---|---|---|
 * | Python 字面量 | `\bTrue\b` → `true` | 文件内容里的 "True story" 变小写 |
 * | 尾逗号 | `,\s*([}\]])` → `$1` | 内容 `"a,}"` 变成 `"a}"` |
 * | 裸换行 | 只在字符串内，无害 | — |
 *
 * 触发条件是「同一段 JSON 在结构层也有语法错」——一旦进 `repair`，全文无差别扫描。
 * 用户看到的是「文件写进去了，但少了个逗号」，而日志里一切正常。
 *
 * ## 这两条为什么一直没被发现
 *
 * 因为**没有断言，而且坏的时候看起来是对的**。第一种情况 `ok=true`；
 * 第二种情况返回值类型、键名、大部分字节都对。任何「能解析出工具名」的粗检都放行。
 */
class LocalToolCallParserTest {

    private val avail = setOf(
        "read_file", "write_file", "edit_file", "shell_run",
        "use_skill", "ui_tap", "http_request",
    )

    private fun parse(text: String, available: Set<String> = avail) =
        LocalToolCallParser.parse(text, available)

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    // —— 第一组：正常形态必须照旧（防「修 A 修坏了 B」）——————————————

    @Test
    fun `标准 arguments 形态`() {
        val r = parse("""{"tool":"read_file","arguments":{"path":"a.txt"}}""")
        assertEquals("read_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
        assertNull(r.error)
    }

    @Test
    fun `arguments 被序列化成字符串时再解一层`() {
        val r = parse("""{"tool":"write_file","arguments":"{\"path\":\"b.txt\",\"content\":\"hi\"}"}""")
        assertEquals("write_file", r.name)
        assertEquals("b.txt", r.args.str("path"))
        assertEquals("hi", r.args.str("content"))
    }

    @Test
    fun `参数平铺时其余顶层键都算参数`() {
        val r = parse("""{"tool":"use_skill","name":"daily-news"}""")
        assertEquals("use_skill", r.name)
        assertEquals("daily-news", r.args.str("name"))
    }

    @Test
    fun `tool_call 标签`() {
        val r = parse("""<tool_call>{"name":"read_file","arguments":{"path":"a.txt"}}</tool_call>""")
        assertEquals("read_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
    }

    @Test
    fun `代码围栏`() {
        val text = "```json\n{\"tool\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}\n```"
        assertEquals("read_file", parse(text).name)
    }

    @Test
    fun `先解释再调用时取到的是 JSON 而不是解说里的括号`() {
        val text = "好的，我读一下 {这个} 文件：\n{\"tool\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}"
        val r = parse(text)
        assertEquals("read_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
    }

    // —— 第二组：名字归一化 ——————————————————————————————————

    @Test
    fun `名字忽略大小写`() {
        assertEquals("read_file", parse("""{"tool":"READ_FILE","arguments":{}}""").name)
    }

    @Test
    fun `名字去分隔符`() {
        assertEquals("read_file", parse("""{"tool":"readfile","arguments":{}}""").name)
    }

    @Test
    fun `shell 习惯的别名归一到真名`() {
        assertEquals("read_file", parse("""{"tool":"cat","arguments":{}}""").name)
        assertEquals("shell_run", parse("""{"tool":"bash","arguments":{}}""").name)
    }

    @Test
    fun `拼错一两个字母仍能认回`() {
        assertEquals("read_file", parse("""{"tool":"readflle","arguments":{}}""").name)
    }

    @Test
    fun `表外的名字一律不返回`() {
        assertNull(parse("""{"tool":"rm_rf","arguments":{}}""").name)
        assertNull(parse("""{"tool":"delete_everything","arguments":{}}""").name)
    }

    @Test
    fun `可用列表为空时不认任何名字`() {
        assertNull(parse("""{"tool":"read_file","arguments":{}}""", emptySet()).name)
    }

    @Test
    fun `混写 名字带括号 时括号后面的丢掉`() {
        val r = parse("""{"tool":"read_file(path=a.txt)","arguments":{"path":"a.txt"}}""")
        assertEquals("read_file", r.name)
    }

    // —— 第三组：修复能力必须保留（这些是宽容版存在的理由）———————————————

    @Test
    fun `结构层尾逗号仍能修掉`() {
        val r = parse("""{"tool":"read_file","arguments":{"path":"a.txt",},}""")
        assertEquals("read_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
    }

    @Test
    fun `字符串里的裸换行`() {
        val r = parse("{\"tool\":\"write_file\",\"arguments\":{\"content\":\"第一行\n第二行\"}}")
        assertEquals("write_file", r.name)
        assertEquals("第一行\n第二行", r.args.str("content"))
    }

    @Test
    fun `被长度上限截断的 JSON 补括号`() {
        val r = parse("""{"tool":"write_file","arguments":{"path":"a.txt","content":"写到一半""")
        assertEquals("write_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
    }

    @Test
    fun `值位置的 Python 字面量转成 JSON`() {
        val r = parse("""{"tool":"read_file","arguments":{"path":"a.txt","flag":True,}}""")
        assertEquals("read_file", r.name)
        assertEquals("true", r.args.str("flag"))
    }

    @Test
    fun `整段用单引号定界时换成双引号`() {
        val r = parse("{tool: 'read_file', arguments: {path: 'a.txt'}}")
        assertEquals("read_file", r.name)
        // 值必须是干净的 a.txt，不能把单引号一起留在值里
        assertEquals("a.txt", r.args.str("path"))
    }

    // —— 第四组：内容保真（缺陷一：修复不能改字符串内容）—————————————

    @Test
    fun `内容里的 逗号加右花括号 不被当成尾逗号`() {
        // 结构层有尾逗号（迫使走修复），内容是 JSON 片段本身
        val r = parse("""{"tool":"write_file","arguments":{"content":"a,}","x":1,}}""")
        assertEquals("write_file", r.name)
        assertEquals("a,}", r.args.str("content"))
    }

    @Test
    fun `内容里的 逗号加右方括号 不被当成尾逗号`() {
        val r = parse("""{"tool":"write_file","arguments":{"content":"a,]","x":[1,2,]}}""")
        assertEquals("write_file", r.name)
        assertEquals("a,]", r.args.str("content"))
    }

    @Test
    fun `内容里的 True 不被改成小写`() {
        val r = parse("""{"tool":"write_file","arguments":{"content":"True story",},"x":1,}""")
        assertEquals("write_file", r.name)
        assertEquals("True story", r.args.str("content"))
    }

    @Test
    fun `内容里的 None 与 False 也不被改`() {
        val r = parse("""{"tool":"write_file","arguments":{"content":"None of False",},"x":1,}""")
        assertEquals("write_file", r.name)
        assertEquals("None of False", r.args.str("content"))
    }

    @Test
    fun `内容里成对的引号与花括号不影响配平`() {
        val r = parse("""{"tool":"write_file","arguments":{"content":"fn f() { return \"a,b\" }"}}""")
        assertEquals("write_file", r.name)
        assertEquals("fn f() { return \"a,b\" }", r.args.str("content"))
    }

    // —— 第五组：OpenAI 形态（缺陷二：认了名字却丢了参数）—————————————

    @Test
    fun `function 形态要带出 arguments`() {
        val r = parse("""{"function":{"name":"read_file","arguments":{"path":"a.txt"}}}""")
        assertEquals("read_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
    }

    @Test
    fun `function 形态的 arguments 是字符串时也要解`() {
        val r = parse(
            """{"function":{"name":"write_file","arguments":"{\"path\":\"b.txt\",\"content\":\"hi\"}"}}"""
        )
        assertEquals("write_file", r.name)
        assertEquals("b.txt", r.args.str("path"))
        assertEquals("hi", r.args.str("content"))
    }

    @Test
    fun `function 形态的参数平铺在 function 里`() {
        val r = parse("""{"function":{"name":"use_skill","skill":"daily-news"}}""")
        assertEquals("use_skill", r.name)
        assertEquals("daily-news", r.args.str("skill"))
    }

    @Test
    fun `function_call 形态`() {
        val r = parse("""{"function_call":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}}""")
        assertEquals("read_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
    }

    @Test
    fun `tool_calls 数组取第一个`() {
        val text = """{"tool_calls":[{"id":"c1","type":"function",""" +
            """"function":{"name":"read_file","arguments":{"path":"a.txt"}}}]}"""
        val r = parse(text)
        assertEquals("read_file", r.name)
        assertEquals("a.txt", r.args.str("path"))
    }

    @Test
    fun `function 形态里不能把 name 当参数带走`() {
        // use_skill 自己有一个 name 参数，但那是「技能名」，不是「工具名」
        val r = parse("""{"function":{"name":"read_file","arguments":{"path":"a.txt"}}}""")
        assertNull(r.args.str("name"))
    }

    // —— 第六组：失败路径要说清原因 ————————————————————————————————

    @Test
    fun `空回复`() {
        val r = parse("   ")
        assertNull(r.name)
        assertEquals("空回复", r.error)
    }

    @Test
    fun `think 块被剥掉`() {
        val r = parse("""<think>我要读文件</think>{"tool":"read_file","arguments":{"path":"a.txt"}}""")
        assertEquals("read_file", r.name)
    }

    @Test
    fun `只有 think 块时算空回复`() {
        val r = parse("<think>想一下</think>")
        assertNull(r.name)
        assertEquals("空回复", r.error)
    }

    @Test
    fun `没有 JSON 时说明原因`() {
        val r = parse("我不太确定该怎么做。")
        assertNull(r.name)
        assertEquals("没有找到 JSON", r.error)
    }

    @Test
    fun `有 JSON 但名字不在可用列表时说明原因`() {
        val r = parse("""{"tool":"rm_rf","arguments":{}}""")
        assertNull(r.name)
        assertEquals("工具名不在可用列表", r.error)
    }

    @Test
    fun `ok 只在认出名字时为真`() {
        assertTrue(parse("""{"tool":"read_file","arguments":{}}""").ok)
        assertFalse(parse("我不确定").ok)
    }

    @Test
    fun `双引号内容加单引号定界无法解析时明确失败而不是给出错值`() {
        // 已知边界：不支持「单引号定界 + 内容里有双引号」。断言它失败，
        // 而不是解析出一个把引号一起带进值的字符串。
        val r = parse("""{'tool': 'write_file', 'arguments': {'content': 'say "hi"'}}""")
        if (r.name != null) {
            // 若将来支持了，值也必须是干净的
            assertEquals("say \"hi\"", r.args.str("content"))
        } else {
            assertNotNull(r.error)
        }
    }
}
