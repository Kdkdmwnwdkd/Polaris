package com.beichen.polaris.core

import com.beichen.polaris.core.agent.TurnEvent
import com.beichen.polaris.core.agent.toNdjson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断包脱敏的行为断言（2026-11-19）。
 *
 * ## 这批断言交的第一份答卷
 *
 * 写之前 `DiagnosticsRedaction` 不存在，脱敏逻辑埋在 [DiagnosticsExporter] 里，
 * 而那个文件 import `android.content.Context`——JVM 上编不过，所以**这条判据
 * 一行断言都没有**。整个仓库对它只有一句 grep：「导出时调过 maskKey 吗」。
 *
 * 把判据拆出来、写断言的过程中确认了三件事：
 *
 * 1. **替换顺序会漏尾巴**。`listOf(主) + 备用` 的顺序下，主 Key 若是某把备用
 *    Key 的前缀，先替换短的会让长 Key 剩尾部明文。这是 [DiagnosticsRedaction.secretsOf]
 *    必须按长度降序的原因，也是 M35 变异守的东西。
 * 2. **诊断包里的流水是原样复制的**。`traceOf` 直接 `src.copyTo(...)`，
 *    而流水的 `tool.start` 行带 `"args"`——`shell_run` 的 `{"command":"curl -H
 *    'Authorization: Bearer sk-…'"}` 前 120 字符里有整把 Key。它的 KDoc 却写着
 *    「不落工具入参，因此不存在事件里夹了 Key 的可能」。
 * 3. **`baseUrl` 没过脱敏**。只在日志文本上做替换，而配置区是另行拼的字符串；
 *    用户把 Key 塞在代理 URL 的 query 里时，它照样被写进文件。
 *
 * ## 断言为什么长这样
 *
 * 「不含 Key」这种断言最容易被写成「不含完整 Key」——而泄露从来不是把整把 Key
 * 原样印出来，是**剩了 12 位**。所以与顺序相关的那几条断言，检查的是
 * **长 Key 的独有后缀**，不是长 Key 本身：短 Key 被替换后，长 Key 已经不完整了，
 * 断言 `!contains(长Key)` 会假绿。
 */
class DiagnosticsRedactionTest {

    // —— 单把 Key 的遮蔽 ————————————————————————————————————

    @Test
    fun `空 Key 显示为未配置`() {
        assertEquals(DiagnosticsRedaction.NOT_CONFIGURED, DiagnosticsRedaction.maskKey(""))
        assertEquals(DiagnosticsRedaction.NOT_CONFIGURED, DiagnosticsRedaction.maskKey("   "))
    }

    @Test
    fun `八位及以下整体抹掉`() {
        // 最容易写错的一行：take(4)+****+takeLast(4) 对 8 位等于原样输出
        assertEquals(DiagnosticsRedaction.MASK, DiagnosticsRedaction.maskKey("abcdefgh"))
        assertEquals(DiagnosticsRedaction.MASK, DiagnosticsRedaction.maskKey("sk-1234"))
    }

    @Test
    fun `九位起保留前四后四`() {
        assertEquals("sk-A****GHIJ", DiagnosticsRedaction.maskKey("sk-ABCDEFGHIJ"))
    }

    @Test
    fun `首尾空白不参与遮蔽`() {
        // Key 从配置里读出来可能带换行；不 trim 会让 takeLast(4) 取到空白，
        // 弹窗里显示成「sk-A****    」
        assertEquals("sk-A****GHIJ", DiagnosticsRedaction.maskKey("  sk-ABCDEFGHIJ\n"))
    }

    // —— 汇集与排序 ——————————————————————————————————————

    @Test
    fun `短值不当作凭据`() {
        val s = DiagnosticsRedaction.secretsOf("short", listOf("also", ""))
        assertTrue("短值应被过滤：$s", s.isEmpty())
    }

    @Test
    fun `重复 Key 只留一份`() {
        val s = DiagnosticsRedaction.secretsOf("sk-ABCDEFGHIJKLMNOP", listOf("sk-ABCDEFGHIJKLMNOP"))
        assertEquals(1, s.size)
    }

    @Test
    fun `按长度降序排列`() {
        val short = "sk-AAAABBBB"
        val long = "sk-AAAABBBBCCCCDDDD"
        val s = DiagnosticsRedaction.secretsOf(short, listOf(long))
        assertEquals("长的必须在前：$s", listOf(long, short), s)
    }

    @Test
    fun `主 Key 与备用池合并后仍然降序`() {
        val s = DiagnosticsRedaction.secretsOf(
            "sk-1234567890",
            listOf("sk-1234567890ABCDEFGHIJ", "sk-1234567890ABC"),
        )
        assertEquals(
            listOf("sk-1234567890ABCDEFGHIJ", "sk-1234567890ABC", "sk-1234567890"),
            s,
        )
    }

    // —— 替换：前缀缺陷 ————————————————————————————————

    @Test
    fun `短 Key 是长 Key 前缀时不留尾巴`() {
        val short = "sk-AAAABBBB"            // 11 位
        val long = "sk-AAAABBBBCCCCDDDD"     // 19 位，前 11 位与 short 相同
        val secrets = DiagnosticsRedaction.secretsOf(short, listOf(long))
        val out = DiagnosticsRedaction.redact("key=$long", secrets)
        // 关键：断言的是长 Key 的**独有后缀**。若先替换 short，
        // long 会变成 "sk-A****BBBBCCCCDDDD"——完整长 Key 已不在，
        // 但 12 位明文还在文件里。
        assertFalse("尾部明文残留：$out", out.contains("CCCCDDDD"))
        assertFalse("长 Key 不应完整出现：$out", out.contains(long))
    }

    @Test
    fun `同一把 Key 出现多次全部替换`() {
        val k = "sk-ABCDEFGHIJKLMNOP"
        val out = DiagnosticsRedaction.redact("$k 和 $k 和 $k", DiagnosticsRedaction.secretsOf(k))
        assertFalse(out, out.contains(k))
        assertEquals(3, Regex("""sk-A\*{4}MNOP""").findAll(out).count())
    }

    @Test
    fun `没有任何 Key 时文本不变`() {
        val text = """{"command":"git log --oneline -20"}"""
        assertEquals(text, DiagnosticsRedaction.redact(text, emptyList()))
    }

    // —— 形状兜底：配置里没有的凭据 ————————————————————

    @Test
    fun `OpenAI 形状的 Key 被抹掉`() {
        val out = DiagnosticsRedaction.redact(
            """{"command":"curl -H 'Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz0123456789' https://api.x.com"}""",
            emptyList(),
        )
        assertFalse(out, out.contains("sk-abcdefghijklmnopqrstuvwxyz0123456789"))
        assertTrue("Bearer 方案名应保留（排障要看它往哪带）", out.contains("Bearer"))
    }

    @Test
    fun `GitHub PAT 被抹掉`() {
        val k = "ghp_" + "a".repeat(36)
        val out = DiagnosticsRedaction.redact("token: $k", emptyList())
        assertFalse(out, out.contains(k))
    }

    @Test
    fun `AWS Access Key 被抹掉`() {
        val out = DiagnosticsRedaction.redact("AKIAIOSFODNN7EXAMPLE", emptyList())
        assertFalse(out, out.contains("AKIAIOSFODNN7EXAMPLE"))
    }

    @Test
    fun `JSON 字段只抹值不抹名`() {
        val out = DiagnosticsRedaction.redact("""{"api_key":"abcdefgh12345678","model":"gpt-4"}""", emptyList())
        assertFalse(out, out.contains("abcdefgh12345678"))
        assertTrue("字段名是排障信息，不能一起抹：$out", out.contains("\"api_key\""))
        assertTrue("普通字段不能被误伤：$out", out.contains("gpt-4"))
    }

    @Test
    fun `URL query 里的 token 被抹掉`() {
        val out = DiagnosticsRedaction.redact("https://api.x.com/v1?key=abcdefgh12345678&model=x", emptyList())
        assertFalse(out, out.contains("abcdefgh12345678"))
        assertTrue("其余 query 参数要留下：$out", out.contains("model=x"))
    }

    @Test
    fun `shell 的 --token 参数被抹掉`() {
        val out = DiagnosticsRedaction.redact("curl --token abcdef123456 https://x.com", emptyList())
        assertFalse(out, out.contains("abcdef123456"))
    }

    // —— 不误伤（过度脱敏同样让诊断包失去价值）————————————

    @Test
    fun `UUID 与时间戳不被误伤`() {
        val text = "id=550e8400-e29b-41d4-a716-446655440000 at 2026-11-19T10:00:22.123Z"
        assertEquals(text, DiagnosticsRedaction.redact(text, emptyList()))
    }

    @Test
    fun `普通 URL 与文件路径不被误伤`() {
        val text = "GET https://api.openai.com/v1/chat/completions /sdcard/DCIM/IMG_2026.jpg"
        assertEquals(text, DiagnosticsRedaction.redact(text, emptyList()))
    }

    @Test
    fun `普通 JSON 参数不被误伤`() {
        val text = """{"path":"a.txt","lines":120,"recursive":true}"""
        assertEquals(text, DiagnosticsRedaction.redact(text, emptyList()))
    }

    @Test
    fun `含 token 字样的普通句子不整句消失`() {
        val text = "the token count is 1024, estimated tokens: 900"
        assertEquals(text, DiagnosticsRedaction.redact(text, emptyList()))
    }

    // —— 幂等 ——————————————————————————————————————

    @Test
    fun `重复脱敏结果不变`() {
        val k = "sk-ABCDEFGHIJKLMNOP"
        val once = DiagnosticsRedaction.redact("k=$k u=https://x.com?token=abcdefgh1234", DiagnosticsRedaction.secretsOf(k))
        val twice = DiagnosticsRedaction.redact(once, DiagnosticsRedaction.secretsOf(k))
        assertEquals(once, twice)
    }

    // —— 端到端：流水里的 tool_start ————————————————————

    @Test
    fun `流水 tool_start 行里的 Key 被抹掉`() {
        // 这一条对的是真缺陷：traceOf 原来直接把 turns-*.ndjson 复制进诊断包，
        // 而 tool.start 行带 "args"，RealAgentLoop 传的是
        // `call.arguments.toString().take(120)`——没有脱敏。
        val key = "sk-abcdefghijklmnopqrstuvwxyz0123456789"
        val call = ToolCall(
            id = "call_1",
            name = "shell_run",
            arguments = JsonObject(mapOf("command" to JsonPrimitive("curl -H 'Authorization: Bearer $key' https://api.x.com"))),
        )
        val line = TurnEvent.ToolCallStarted(
            turnId = "T1", step = 0, elapsedMs = 12,
            tool = call.name, argsSummary = call.arguments.toString().take(120), callId = call.id,
        ).toNdjson(ts = 1_767_000_000_000L)

        assertTrue("前提：未脱敏的流水里确实带着 Key（否则这条断言没意义）", line.contains(key))

        val out = DiagnosticsRedaction.redactLines(sequenceOf(line), DiagnosticsRedaction.secretsOf(key)).joinToString("\n")
        assertFalse("脱敏后的流水仍含 Key：$out", out.contains(key))
        assertTrue("事件结构不能被破坏：$out", out.contains("\"ev\":\"tool.start\""))
        assertTrue("工具名要留下：$out", out.contains("\"tool\":\"shell_run\""))
    }

    @Test
    fun `流水多行逐行脱敏且行数不变`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz0123456789"
        val lines = listOf(
            """{"ev":"turn.start","args":"$key"}""",
            """{"ev":"tool.end","tool":"read_file"}""",
            """{"ev":"turn.end","status":"ok"}""",
        )
        val out = DiagnosticsRedaction.redactLines(lines.asSequence(), DiagnosticsRedaction.secretsOf(key)).toList()
        assertEquals(3, out.size)
        assertFalse(out.joinToString(), out.joinToString().contains(key))
    }

    @Test
    fun `空文本与空行不炸`() {
        assertEquals("", DiagnosticsRedaction.redact("", listOf("sk-ABCDEFGHIJKLMNOP")))
        assertEquals(listOf("", ""), DiagnosticsRedaction.redactLines(sequenceOf("", ""), emptyList()).toList())
    }

    @Test
    fun `备用池里的 Key 也被抹掉`() {
        val main = "sk-mainmainmainmain"
        val backup = "sk-backupbackupbackup"
        val secrets = DiagnosticsRedaction.secretsOf(main, listOf(backup))
        val out = DiagnosticsRedaction.redact("main=$main backup=$backup", secrets)
        assertFalse(out, out.contains(main))
        assertFalse("备用池漏了：$out", out.contains(backup))
    }
}
