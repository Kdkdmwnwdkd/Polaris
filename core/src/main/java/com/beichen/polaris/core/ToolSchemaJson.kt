package com.beichen.polaris.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具参数 schema → 各家 API 的 JSON 形态。**全仓唯一序列化点**。
 *
 * ## 为什么必须抽出来
 * 原先 OpenAI / Claude / Gemini 三家客户端各有一份 `toApiTool`，三段代码逐字相同
 * （只有外层键名 `parameters` vs `input_schema` 不同），而且**三份都只写了
 * `type` / `description` / `enum` 三个字段**：
 *
 * ```
 * put("type", p.type)
 * if (p.description.isNotBlank()) put("description", p.description)
 * p.enum?.let { ... }
 * ```
 *
 * `JsonSchema.Property` 里 W-31 加的 `items` / `properties` / `required` 一个都没进 JSON。
 * 后果不是「少点味道」，是**声明残缺**：
 *
 * - `spawn_agents` 的 `tasks` 是对象数组，声明里只剩 `{"type":"array"}`。
 *   严格校验的实现（Gemini 尤其）会直接拒掉整个工具声明；
 *   宽松的实现则让模型看不见元素结构，只能靠描述文字猜字段名。
 * - 该工具源码注释里写着「严格实现要求 type=array 必须带 items」——
 *   数据层按这个要求写了，序列化层没接上，等于白写。
 *
 * 三份拷贝的另一个问题是修不全：这次补齐 items 得改三处，漏一处就是
 * 「同一个工具在三家供应商上能力不同」，而这种差异在日志里看起来像模型不听话。
 *
 * ## 键顺序
 * `type` → `description` → `enum` → `items` → `properties` → `required`。
 * kotlinx 的 `JsonObject` 保插入顺序，所以扁平工具的产出与改动前**逐字节相同**
 * （新增字段都是 null 时不写），既有断言不会因键序变化而误报。
 */
object ToolSchemaJson {

    /**
     * 单层属性。递归下降：`items` 里的元素、`properties` 里的子字段都走这里。
     *
     * @param property 待序列化的属性描述
     */
    fun property(property: JsonSchema.Property): JsonObject = buildJsonObject {
        put("type", property.type)
        if (property.description.isNotBlank()) put("description", property.description)
        property.enum?.let { values ->
            // 空枚举不写：`"enum": []` 在部分严格实现里等于「没有合法取值」，比不写更糟
            if (values.isNotEmpty()) putJsonArray("enum") { values.forEach { add(it) } }
        }
        // type=array 才写 items：有的供应商对非数组类型带 items 会报 schema 不合法
        if (property.type == "array") {
            property.items?.let { put("items", property(it)) }
        }
        property.properties?.let { children ->
            if (children.isNotEmpty()) {
                putJsonObject("properties") {
                    children.forEach { (name, child) -> put(name, property(child)) }
                }
            }
        }
        property.required?.let { names ->
            if (names.isNotEmpty()) putJsonArray("required") { names.forEach { add(it) } }
        }
    }

    /**
     * 顶层对象 schema（工具参数的根）。
     *
     * `type` 恒为 `"object"`：三家 API 都只接受对象型参数，这不是可配项。
     */
    fun objectSchema(schema: JsonSchema): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            schema.properties.forEach { (name, property) -> put(name, property(property)) }
        }
        // 空 required 也写出空数组：与改动前逐字节一致，三家 API 都接受这种形态
        putJsonArray("required") { schema.required.forEach { add(it) } }
    }
}
