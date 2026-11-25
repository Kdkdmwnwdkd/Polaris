package com.beichen.polaris.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数「必填 / 可选」的判定（2026-11-25）。
 *
 * ## 为什么会有这组断言
 *
 * `required` 的旧默认值是「所有参数」，于是全仓 112 个参数里有 37 个是
 * 「描述写着（可选）或 默认 X，schema 却说不传不行」。严格按 function-calling
 * 契约生成的模型只能硬塞一个值，最省事的是空串。
 *
 * 具体炸点：真机上请求 `https://api.deepseek.com/v1/models`，模型给 `body` 塞了
 * `""`，OkHttp 拒绝 GET 带请求体，报 `GET must not have a request body`，
 * 请求一个字节都没发出去，却被归成「网络异常」，还提示「已连续失败 N 次
 * （超时/连不上）」——一句真话都没有。
 *
 * 修法是让「描述说可选」自动等于「required 里没有它」。这组测试守的就是这条规则，
 * 以及它的边界（显式 required 不被过滤、空描述仍算必填）。
 */
class JsonSchemaOptionalTest {

    /** 描述里写「（可选）」的参数不该出现在 required 里。 */
    @Test
    fun `描述写可选的不进 required`() {
        val schema = JsonSchema.objectSchema(
            JsonSchema.prop("url", "string", "请求目标地址"),
            JsonSchema.prop("body", "string", "请求体，通常与 POST/PUT 配合使用（可选）"),
            JsonSchema.prop("headers", "object", "请求头，键值均为字符串（可选）"),
        )
        assertEquals(listOf("url"), schema.required)
        // 属性本身不能少：可选 ≠ 不声明，模型仍要看得见这个字段
        assertEquals(3, schema.properties.size)
    }

    /** 「默认 X」等价于「可以不传」，同样不该必填。 */
    @Test
    fun `描述写默认值的不进 required`() {
        val schema = JsonSchema.objectSchema(
            JsonSchema.prop("query", "string", "检索关键词"),
            JsonSchema.prop("limit", "integer", "返回条数，默认 20"),
            JsonSchema.prop("offset", "integer", "跳过的条数，默认 0"),
        )
        assertEquals(listOf("query"), schema.required)
    }

    /** 「留空 = ...」「省略则...」是同一件事的另外两种说法。 */
    @Test
    fun `留空或省略的说法进 required`() {
        val schema = JsonSchema.objectSchema(
            JsonSchema.prop("id", "string", "任务 id；留空 = 列出最近 5 个"),
            JsonSchema.prop("cwd", "string", "工作目录；省略则用工作区根"),
            JsonSchema.prop("target", "string", "要停止的应用名称"),
        )
        assertEquals(listOf("target"), schema.required)
    }

    /** 什么标记都没有的参数，仍然必填——这是绝大多数工具的常态，不能被规则误伤。 */
    @Test
    fun `无标记的仍然必填`() {
        val schema = JsonSchema.objectSchema(
            JsonSchema.prop("x", "integer", "点击的 X 坐标（像素）"),
            JsonSchema.prop("y", "integer", "点击的 Y 坐标（像素）"),
        )
        assertEquals(listOf("x", "y"), schema.required)
    }

    /** 空描述不当成可选：宁可可必填，也不要让模型以为它随便漏。 */
    @Test
    fun `空描述算必填`() {
        val schema = JsonSchema.objectSchema(
            JsonSchema.prop("a", "string", ""),
            JsonSchema.prop("b", "string", "说明里有默认这个词"),
        )
        assertEquals(listOf("a"), schema.required)
    }

    /**
     * 显式传 required 时**原样使用**，不做过滤。
     *
     * 这是留给「描述说可选、实际必填」这种反常情况的唯一出口：想覆盖规则就得
     * 显式写，此时描述应当同步改掉，不要留下自相矛盾的两句话。
     */
    @Test
    fun `显式 required 不被过滤`() {
        val schema = JsonSchema.objectSchema(
            JsonSchema.prop("content", "string", "要记住的内容"),
            JsonSchema.prop("salience", "string", "重要度 1-10，默认 5（可选）"),
            required = listOf("content", "salience"),
        )
        assertEquals(listOf("content", "salience"), schema.required)
    }

    /** 一个参数都没有的工具（如 ui_tree）不该崩，required 为空。 */
    @Test
    fun `零参数工具`() {
        val schema = JsonSchema.objectSchema()
        assertTrue(schema.required.isEmpty())
        assertTrue(schema.properties.isEmpty())
    }

    /**
     * 序列化出来的 JSON 里，optional 参数只出现在 properties、不出现在 required。
     *
     * 这条盯的是「改完 Kotlin 对象对，但发出去的那份不对」——模型读的是序列化结果。
     */
    @Test
    fun `序列化后 required 不含可选参数`() {
        val schema = JsonSchema.objectSchema(
            JsonSchema.prop("url", "string", "请求目标地址"),
            JsonSchema.prop("body", "string", "请求体（可选）"),
        )
        val json = kotlinx.serialization.json.Json.encodeToString(JsonSchema.serializer(), schema)
        val req = Regex("\"required\":\\[(.*?)]").find(json)?.groupValues?.get(1).orEmpty()
        assertEquals("\"url\"", req)
        assertTrue("properties 里仍要有 body", json.contains("\"body\""))
    }
}
