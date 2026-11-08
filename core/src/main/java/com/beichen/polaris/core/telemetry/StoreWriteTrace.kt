package com.beichen.polaris.core.telemetry

/**
 * 存储层写入计量（P5-1 的补充事件）。
 *
 * ## 为什么这个事件不在方案的「8 个事件」里
 *
 * 那 8 个是**回合事件**（`TurnEvent`），回答「这一回合卡在哪」；而度量表里
 * 「每回合会话写入量 16–36MB → <1MB」问的是**存储层**的事，回合事件里没有、
 * 也不该有落盘字节数——回合事件的载荷纪律明确排除「大块数据」。
 *
 * 所以它是同一份流水里的**另一族事件**，靠 `ev` 前缀区分（`store.*` 而不是
 * `turn.*` / `tool.*`）。`trace-metrics.py` 按 `ev` 分流，两类一起算。
 *
 * ## 只有会话接了这个
 *
 * 其他 store（草稿 / 收藏 / 任务 / 提示词）一次写入都在 KB 级，接进来只会让
 * 流水里 90% 是噪声。会话是唯一「一条消息就能写几十 MB」的存储，也是唯一
 * 被度量表点名的。将来要接别处，加一行 `StoreWriteTrace.write(...)` 即可。
 */
object StoreWriteTrace {

    /**
     * 记一次落盘。
     *
     * @param store 存储名（`conversations` / …）。
     * @param bytes 本次写出的字节数（UTF-8 长度，不是字符数——字符数会低估中文内容）。
     * @param durationMs 落盘耗时（含 fsync）。
     * @param coalesced 本次写合并了多少次变更（去抖窗口内累积的脏标记次数）。
     *   1 表示没合并（改一次写一次），>1 表示去抖生效。这个数字是「去抖到底省了多少次写」的
     *   唯一证据——没有它就分不清「写得少了」是因为去抖，还是因为根本没在写。
     */
    fun write(store: String, bytes: Int, durationMs: Long, coalesced: Int) {
        TraceSink.emit(
            buildString(128) {
                append("{\"ev\":\"store.write\",\"ts\":").append(System.currentTimeMillis())
                append(",\"store\":").append(TraceSink.quote(store))
                append(",\"bytes\":").append(bytes)
                append(",\"dur\":").append(durationMs)
                append(",\"coalesced\":").append(coalesced)
                append('}')
            }
        )
    }

    /**
     * 记一次崩溃恢复的修复动作。
     *
     * 度量表「崩溃后写操作重放次数：未知 → 0」。`restored` 是本次补回的调用数——
     * 每出现一次非零，就说明「先记录后生效」那条纪律真的救回了一次副作用。
     */
    fun repair(store: String, restored: Int, note: String? = null) {
        if (restored <= 0) return
        TraceSink.emit(
            buildString(128) {
                append("{\"ev\":\"store.repair\",\"ts\":").append(System.currentTimeMillis())
                append(",\"store\":").append(TraceSink.quote(store))
                append(",\"n\":").append(restored)
                if (note != null) append(",\"note\":").append(TraceSink.quote(note))
                append('}')
            }
        )
    }
}
