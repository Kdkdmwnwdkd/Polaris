package com.beichen.polaris.core.agent

/**
 * R-09：**已上屏的字符数**达到这个长度后，不再整次重试。
 *
 * 重试的粒度是「一整次 streamChat」。流式回答一旦开始往外吐，UI 就已经在追加渲染了；
 * 此时若整次重发，新内容会从头再来一遍——用户先看到「前半段」，再看到「同一段从头再长」。
 * 修前唯一的缓解是重试时清空草稿，但那是把用户已经读过的内容**抹掉**再重画，
 * 观感是闪没，不是修复。
 *
 * 阈值取 160：大约两三行。低于它的重试用户基本无感（清空重画也来不及读），
 * 高于它说明用户已经在读了——这时候「重来一遍」比「明确报错并保留残稿」更糟。
 * 与 `shell` 的审批阈值同理：代价不对称时，选不骗人的那一边。
 */
const val RETRY_VISIBLE_CHARS = 160

/**
 * R-09：这次失败之后还该不该再试一次。
 *
 * 两个条件必须同时成立：错误本身值得重试，且这次尝试**没有留下可见输出**。
 * 后者是修前缺的那一半——`isRetryable` 只看异常类型，看不见「用户已经读了半篇」。
 */
fun retryAllowed(retryable: Boolean, output: AttemptOutputTracker): Boolean =
    retryable && !output.visible

/**
 * 一次 attempt 的可见输出计数器。
 *
 * 为什么需要它：`callWithRetry` 只看得到异常，看不到「这次尝试已经让用户看到了多少字」。
 * 两者不在同一个作用域里，只能靠一个跨调用的小状态把事实传出去。
 */
class AttemptOutputTracker {
    var chars: Int = 0
        private set

    fun reset() {
        chars = 0
    }

    fun add(delta: String) {
        chars += delta.length
    }

    /** 已经足够多，重来一遍用户会看出来。 */
    val visible: Boolean get() = chars >= RETRY_VISIBLE_CHARS
}
