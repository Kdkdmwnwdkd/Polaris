package com.beichen.polaris.core.net

/**
 * 「同一个地址又失败了」的短时记忆。
 *
 * ## 解决的是什么
 *
 * [com.beichen.polaris.tools.HttpRequestTool] 失败时只把一句「检查 URL 是否可达」回喂给模型。
 * 模型看不懂「同一个地址已经栽了三回」，于是换一个参数再撞一次墙：真机上出现过连续 6 次
 * `http_request 已失败`，每次卡满读超时，用户干等三分多钟（2026-11-24 天气任务 94s 那段日志）。
 *
 * 这里记一件事：**按地址累计连续失败次数**。工具据此在提示里补一句「换源，别再撞」。
 *
 * ## 为什么是「连续」而不是「总共」
 *
 * 成功一次就清零。否则一个每天查十次天气的地址，一周后会一直挂着「已失败 40 次」，
 * 提示语从有用变成噪音——用户看到的是「它总在报怨」，而不是「这次卡住了」。
 *
 * ## 为什么不做容量淘汰
 *
 * key 是 host。正常使用里一天主动请求的 host 不会上百，字符串常驻的开销可以忽略；
 * 而「满了就清空」会让一个一直失败的地址在清理瞬间被洗白，提示反而漏掉。宁可简单。
 *
 * ## 线程安全
 *
 * 工具可能被并发调用（虽然 http_request 标了非只读、批次会串行，但不同轮次之间仍有交叠）。
 * 计数走 CAS 改写，不依赖 `ConcurrentHashMap.merge`——那个方法的 lambda 在旧编译器 +
 * 低 API 组合下会引出额外的 desugar 约束，手写循环没有这个问题。
 */
class HttpFailStreak {

    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 记一次失败，返回**累计连续失败次数**（含本次）。
     *
     * @param key 地址标识（host，或退化后的 URL 前缀）。空串表示连地址都没解析出来，
     *   调用方应跳过计数——没地址就没法给出「换掉哪个地址」的建议。
     */
    fun noteFailure(key: String): Int {
        if (key.isEmpty()) return 0
        while (true) {
            val cur = counts[key]
            if (cur == null) {
                if (counts.putIfAbsent(key, 1) == null) return 1
                continue
            }
            // 封顶只为防溢出；超过 [CAP] 之后一律按 CAP 报，语义没有差别。
            val next = minOf(cur + 1, CAP)
            if (counts.replace(key, cur, next)) return next
        }
    }

    /** 记一次成功（含「连上了但服务器返回 4xx」——网络是通的，不算撞墙）。 */
    fun noteSuccess(key: String) {
        if (key.isEmpty()) return
        counts.remove(key)
    }

    /** 当前连续失败次数，不产生副作用（测试与日志用）。 */
    fun count(key: String): Int = counts[key] ?: 0

    private companion object {
        /** 只用于防溢出的上限：到了这个数，再接一句「第 N 次」也没有更多信息量。 */
        const val CAP = 99
    }
}
