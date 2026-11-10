/**
 * 备份件脱敏验证（BackupRedaction）。
 *
 * 验两件事，方向相反、都不能错：
 *   A/D/E **脱敏**：导出包里不许有密钥——猜不出就丢（fail-closed）；
 *   B/C   **合并**：恢复时本机已有的 Key 要留住——猜不出就保（fail-safe）。
 *
 * 这两条一旦写反，代价分别是「密钥随网盘外流」和「重装后 Key 全丢且用户不知道为什么」。
 * 纯 JSON 层实现，能在这台没有 Android SDK 的机器上真跑。
 */

import com.beichen.polaris.core.BackupRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

var pass = 0
var fail = 0

fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

fun assertTrue(what: String, cond: Boolean) {
    if (!cond) throw AssertionError(what)
}

fun assertEquals(what: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$what：期望 $expected，实际 $actual")
}

private val R = BackupRedaction
private val json = Json { ignoreUnknownKeys = true }

/** 取第 i 个档案的 `config` 对象。 */
private fun cfgAt(profilesJson: String, i: Int): JsonObject =
    (json.parseToJsonElement(profilesJson) as JsonArray)[i].jsonObject["config"]!!.jsonObject

private fun str(el: JsonElement?, fallback: String = "<缺>"): String =
    el?.jsonPrimitive?.content ?: fallback

private fun profiles(vararg items: String): String = "[${items.joinToString(",")}]"

private fun profile(id: String, name: String, key: String, headers: String = "{}"): String =
    """{"id":"$id","name":"$name","config":{"baseUrl":"https://api.x.com/v1","model":"m","providerId":"openai","apiKey":"$key","customHeaders":$headers}}"""

/* ------------------------------ A 档案脱敏 ------------------------------ */

fun testRedactProfiles() {
    println("[A] 档案脱敏")

    check("A1 两个档案的 Key 都被清空，其余字段原样") {
        val out = R.redactProfiles(
            profiles(
                profile("p1", "主配置", "sk-aaa111", """{"HTTP-Referer":"https://me.dev"}"""),
                profile("p2", "备用", "sk-bbb222")
            )
        )
        val arr = json.parseToJsonElement(out) as JsonArray
        assertEquals("档案数不变", 2, arr.size)
        assertEquals("p1 Key 清空", "", str(cfgAt(out, 0)["apiKey"]))
        assertEquals("p2 Key 清空", "", str(cfgAt(out, 1)["apiKey"]))
        assertEquals("p1 baseUrl 保留", "https://api.x.com/v1", str(cfgAt(out, 0)["baseUrl"]))
        assertEquals("p1 名字保留", "主配置", str(arr[0].jsonObject["name"]))
        assertEquals("p1 id 保留", "p1", str(arr[0].jsonObject["id"]))
    }

    check("A2 自定义头里可能夹带 Key：按头名清空，普通头保留") {
        val out = R.redactProfiles(
            profiles(
                profile(
                    "p1", "中转站", "sk-aaa",
                    """{"X-Api-Key":"sk-inside","api-key":"sk-inside2","Authorization":"Bearer sk-in3","HTTP-Referer":"https://me.dev","X-Title":"Polaris"}"""
                )
            )
        )
        val headers = cfgAt(out, 0)["customHeaders"]!!.jsonObject
        assertEquals("X-Api-Key 清空", "", str(headers["X-Api-Key"]))
        assertEquals("api-key 清空", "", str(headers["api-key"]))
        assertEquals("Authorization 清空", "", str(headers["Authorization"]))
        assertEquals("Referer 保留", "https://me.dev", str(headers["HTTP-Referer"]))
        assertEquals("X-Title 保留", "Polaris", str(headers["X-Title"]))
    }

    check("A3 畸形输入 → 空串（宁可这次备份没配置，也不把可能含 Key 的原文写进包）") {
        assertEquals("截断的 JSON", "", R.redactProfiles("""[{"id":"p1","config":{"apiKey":"sk-x""""))
        assertEquals("不是数组", "", R.redactProfiles("""{"id":"p1"}"""))
        assertEquals("空串", "", R.redactProfiles(""))
        assertEquals("纯文本", "", R.redactProfiles("hello"))
    }

    check("A4 老备份件缺 apiKey 字段也不崩，且补出来是空") {
        val old = """[{"id":"p1","name":"老","config":{"baseUrl":"https://a/b","model":"m"}}]"""
        val out = R.redactProfiles(old)
        assertEquals("Key 为空", "", str(cfgAt(out, 0)["apiKey"]))
        assertEquals("baseUrl 保留", "https://a/b", str(cfgAt(out, 0)["baseUrl"]))
    }

    check("A5 空档案列表 → 仍是合法空数组") {
        val out = R.redactProfiles("[]")
        assertEquals("空数组", 0, (json.parseToJsonElement(out) as JsonArray).size)
    }
}

/* ------------------------------ B 档案合并 ------------------------------ */

fun testMergeProfiles() {
    println("[B] 档案合并")

    check("B1 备份 Key 为空 + 本机同 id 有 Key → 继承本机") {
        val backup = R.redactProfiles(profiles(profile("p1", "主配置", "sk-local")))
        val out = R.mergeProfiles(backup, profiles(profile("p1", "主配置", "sk-local")))
        assertEquals("应继承", "sk-local", str(cfgAt(out, 0)["apiKey"]))
    }

    check("B2 备份里带非空 Key（老版本导出的包）→ 照旧覆盖，不被本机顶掉") {
        val backup = profiles(profile("p1", "主配置", "sk-from-backup"))
        val out = R.mergeProfiles(backup, profiles(profile("p1", "主配置", "sk-local")))
        assertEquals("应保留备份值", "sk-from-backup", str(cfgAt(out, 0)["apiKey"]))
    }

    check("B3 跨设备（id 全不同）→ 保持空，等用户重填") {
        val backup = R.redactProfiles(profiles(profile("p1", "主配置", "")))
        val out = R.mergeProfiles(backup, profiles(profile("other-id", "主配置", "sk-local")))
        assertEquals("不该借用别的档案的 Key", "", str(cfgAt(out, 0)["apiKey"]))
    }

    check("B4 本机也没有 Key → 仍为空，不崩") {
        val out = R.mergeProfiles(profiles(profile("p1", "x", "")), "")
        assertEquals("空", "", str(cfgAt(out, 0)["apiKey"]))
    }

    check("B5 备份件畸形 → 原样返回本机配置（坏包不许清空好数据）") {
        val local = profiles(profile("p1", "主配置", "sk-local"))
        assertEquals("应返回本机", local, R.mergeProfiles("""[{"id":"p1","config":{""", local))
        assertEquals("空备份", local, R.mergeProfiles("", local))
    }

    check("B6 本机畸形 → 备份原样（Key 仍空，但结构不丢）") {
        val backup = R.redactProfiles(profiles(profile("p1", "主配置", "")))
        val out = R.mergeProfiles(backup, "not-json")
        assertEquals("档案数不变", 1, (json.parseToJsonElement(out) as JsonArray).size)
        assertEquals("Key 为空", "", str(cfgAt(out, 0)["apiKey"]))
    }

    check("B8 敏感头也按名字回填（只清 apiKey 等于把 X-Api-Key 留在包里）") {
        val backup = R.redactProfiles(
            profiles(profile("p1", "中转站", "sk-x", """{"X-Api-Key":"sk-hdr","HTTP-Referer":"https://me.dev"}"""))
        )
        val local = profiles(profile("p1", "中转站", "sk-local", """{"X-Api-Key":"sk-hdr","HTTP-Referer":"https://me.dev"}"""))
        val out = R.mergeProfiles(backup, local)
        val headers = cfgAt(out, 0)["customHeaders"]!!.jsonObject
        assertEquals("X-Api-Key 回填", "sk-hdr", str(headers["X-Api-Key"]))
        assertEquals("Referer 不受影响", "https://me.dev", str(headers["HTTP-Referer"]))
    }

    check("B9 包里带了非空头值（老包）→ 照旧覆盖，不被本机顶掉") {
        val backup = profiles(profile("p1", "中转站", "", """{"X-Api-Key":"sk-from-backup"}"""))
        val local = profiles(profile("p1", "中转站", "sk-local", """{"X-Api-Key":"sk-local-hdr"}"""))
        val out = R.mergeProfiles(backup, local)
        val headers = cfgAt(out, 0)["customHeaders"]!!.jsonObject
        assertEquals("保留备份值", "sk-from-backup", str(headers["X-Api-Key"]))
    }

    check("B7 多档案按 id 各自继承，不串号") {
        val backup = R.redactProfiles(
            profiles(profile("p1", "A", ""), profile("p2", "B", ""))
        )
        val local = profiles(profile("p1", "A", "sk-1"), profile("p2", "B", "sk-2"))
        val out = R.mergeProfiles(backup, local)
        assertEquals("p1 拿 sk-1", "sk-1", str(cfgAt(out, 0)["apiKey"]))
        assertEquals("p2 拿 sk-2", "sk-2", str(cfgAt(out, 1)["apiKey"]))
    }
}

/* ------------------------------ C 媒体 Key ------------------------------ */

fun testMediaKeys() {
    println("[C] 媒体 Key")

    check("C1 脱敏：值全空，键集合不变") {
        val out = R.redactMediaKeys(mapOf("openai" to "sk-a", "dashscope" to "sk-b"))
        assertEquals("provider 集合不变", setOf("openai", "dashscope"), out.keys)
        assertEquals("openai 清空", "", out["openai"])
        assertEquals("dashscope 清空", "", out["dashscope"])
    }

    check("C2 合并：空值继承本机，非空保留备份值") {
        val merged = R.mergeMediaKeys(
            incoming = mapOf("openai" to "", "dashscope" to "sk-from-backup"),
            existing = mapOf("openai" to "sk-local", "dashscope" to "sk-local2")
        )
        assertEquals("继承本机", "sk-local", merged["openai"])
        assertEquals("保留备份", "sk-from-backup", merged["dashscope"])
    }

    check("C3 备份没提到的 provider 不会被本机值补进来") {
        val merged = R.mergeMediaKeys(
            incoming = mapOf("openai" to ""),
            existing = mapOf("openai" to "sk-local", "minimax" to "sk-mm")
        )
        assertEquals("只处理备份里的键", setOf("openai"), merged.keys)
    }

    check("C4 本机没有对应 provider → 空值保持空") {
        val merged = R.mergeMediaKeys(mapOf("volc" to ""), mapOf("openai" to "sk-x"))
        assertEquals("空", "", merged["volc"])
    }
}

/* ------------------------------ D Key 池 ------------------------------ */

private fun pool(vararg entries: String): String = "{${entries.joinToString(",")}}"

private fun poolEntry(profileId: String, vararg slots: String): String =
    """"$profileId":[${slots.joinToString(",")}]"""

private fun slot(id: String, label: String, key: String): String =
    """{"id":"$id","label":"$label","key":"$key"}"""

fun testKeyPool() {
    println("[D] 备用 Key 池")

    check("D1 脱敏：key 清空，id/label 保留（用户恢复后看得到自己配过哪几个）") {
        val out = R.redactKeyPool(pool(poolEntry("p1", slot("s1", "主 Key", "sk-a"), slot("s2", "备用", "sk-b"))))
        val obj = json.parseToJsonElement(out).jsonObject["p1"]!!.jsonArray
        assertEquals("槽位数", 2, obj.size)
        assertEquals("s1 key 空", "", str(obj[0].jsonObject["key"]))
        assertEquals("s1 label 保留", "主 Key", str(obj[0].jsonObject["label"]))
        assertEquals("s2 id 保留", "s2", str(obj[1].jsonObject["id"]))
    }

    check("D2 合并：同 id 继承本机 Key") {
        val backup = R.redactKeyPool(pool(poolEntry("p1", slot("s1", "主", "x"))))
        val local = pool(poolEntry("p1", slot("s1", "主", "sk-local")))
        val out = R.mergeKeyPool(backup, local)
        val slots = json.parseToJsonElement(out).jsonObject["p1"]!!.jsonArray
        assertEquals("继承", "sk-local", str(slots[0].jsonObject["key"]))
    }

    check("D3 合并：id 不同 → 保持空") {
        val backup = R.redactKeyPool(pool(poolEntry("p1", slot("s9", "别人的", "x"))))
        val local = pool(poolEntry("p1", slot("s1", "主", "sk-local")))
        val out = R.mergeKeyPool(backup, local)
        val slots = json.parseToJsonElement(out).jsonObject["p1"]!!.jsonArray
        assertEquals("不该借用", "", str(slots[0].jsonObject["key"]))
    }

    check("D4 畸形 → 返回本机（不清空）") {
        val local = pool(poolEntry("p1", slot("s1", "主", "sk-local")))
        assertEquals("坏包", local, R.mergeKeyPool("{{{", local))
        assertEquals("空串", local, R.mergeKeyPool("", local))
        assertEquals("脱敏也 fail-closed", "", R.redactKeyPool("{{{").trim())
    }

    check("D5 合并保留备份里的非空 Key（老包）") {
        val backup = pool(poolEntry("p1", slot("s1", "主", "sk-old")))
        val local = pool(poolEntry("p1", slot("s1", "主", "sk-local")))
        val out = R.mergeKeyPool(backup, local)
        assertEquals("保留备份值", "sk-old", str(json.parseToJsonElement(out).jsonObject["p1"]!!.jsonArray[0].jsonObject["key"]))
    }
}

/* ------------------------------ E 自定义请求体 ------------------------------ */

private fun profileWithBody(id: String, key: String, body: String): String =
    """{"id":"$id","name":"中转站","config":{"baseUrl":"https://api.x.com/v1","model":"m","apiKey":"$key","customBody":${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(body))}}}"""

fun testCustomBody() {
    println("[E] 自定义请求体")

    check("E1 请求体顶层的敏感键被清空，普通键保留") {
        val out = R.redactProfiles(
            profiles(profileWithBody("p1", "sk-x", """{"api_key":"sk-body","enable_thinking":false,"top_k":40}"""))
        )
        val body = str(cfgAt(out, 0)["customBody"])
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("api_key 清空", "", str(obj["api_key"]))
        assertEquals("enable_thinking 保留", "false", str(obj["enable_thinking"]))
        assertEquals("top_k 保留", "40", str(obj["top_k"]))
    }

    check("E2 请求体不是 JSON 对象时不改（宁可留原文，也不猜）") {
        val out = R.redactProfiles(profiles(profileWithBody("p1", "sk-x", "not-json")))
        assertEquals("原文保留", "not-json", str(cfgAt(out, 0)["customBody"]))
    }

    check("E3 恢复时按同名键回填本机请求体里的凭据") {
        val backup = R.redactProfiles(
            profiles(profileWithBody("p1", "", """{"api_key":"sk-body","top_k":40}"""))
        )
        val local = profiles(profileWithBody("p1", "sk-local", """{"api_key":"sk-body","top_k":99}"""))
        val out = R.mergeProfiles(backup, local)
        val obj = json.parseToJsonElement(str(cfgAt(out, 0)["customBody"])).jsonObject
        assertEquals("api_key 回填", "sk-body", str(obj["api_key"]))
        assertEquals("非敏感键用备份值", "40", str(obj["top_k"]))
    }
}

fun main() {
    println("=== 备份件脱敏验证（BackupRedaction）===")
    testRedactProfiles()
    testMergeProfiles()
    testMediaKeys()
    testKeyPool()
    testCustomBody()
    println()
    println("$pass 通过 / $fail 失败")
    if (fail > 0) kotlin.system.exitProcess(1)
}
