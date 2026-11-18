package com.beichen.polaris.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * 「本次会话始终允许」的授权键存储，**按对话隔离**（审计 F3）且**带过期时间**（2026-11-18）。
 *
 * ## 为什么单独一个文件
 *
 * 修前它是 `ChatViewModel` 里的一个 `mutableSetOf<String>()`：进程级、跨对话共享。
 * 在会话 A 里为 `write_file` 勾过「不再询问」，切到会话 B 同样免问——而弹窗文案写的是
 * 「本次会话」。用户理解的「会话」是当前对话，实现里却是「App 进程存活期间」。
 *
 * 逻辑只有一行 `getOrPut`，但放在 ChatViewModel 里会让那个已经 3700 行的文件继续变长，
 * 而架构守卫 B 对存量超限文件的要求是**只减不增**。拆出来既守住守卫，也让这段语义
 * 有了自己的注释位置。
 *
 * ## 为什么加过期时间
 *
 * 「本次会话」在手机上的真实长度常常不是十分钟，而是**几天**：用户睡前发一句、第二天
 * 接着聊、Agent 后台续跑，同一个 conversationId 一直活着。于是「始终允许」实际上等于
 * 「永久允许」——包括 `github_commit`（可被记住的写通道）和按域名记住的 `http_request`。
 * 审批的本意是「我现在知道你在干什么」，昨天那个「是」不代表今天还成立。
 *
 * 所以键有了 30 分钟的有效期：到期后不是拒绝，是**重新问一次**。用户再点一次
 * 「始终允许」即续期，代价是一次点击，换来的是授权窗口不会被无限拉长。
 *
 * 30 分钟是拍的：要长于一次「装依赖 → 编译 → 测试」的连续操作（真机日志里长任务
 * 中位约 8 分钟），又要短于「今天先到这，明天再说」。真机标定后改 [TTL_MS] 即可。
 *
 * 工具升级失效不需要额外做：白名单只存进程内存，升级安装会让 App 重启，天然清空。
 *
 * ## 边界
 *
 * - 只存进程内存，App 重启即失效（与修前一致，不做长期授权持久化）；
 * - 被删除的对话会在内存里留下一个空桶，量级是「用户一生创建过的对话数」，
 *   不额外做回收——为这点内存加一条清理路径，比泄漏本身更容易出错。
 */
object ApprovalWhitelistStore {

    /**
     * 「始终允许」的有效期。到期后 `ApprovalScope.allows` 返回 false，
     * 调用点落回弹窗（= 重新确认），而不是直接拒绝。
     */
    internal const val TTL_MS = 30L * 60 * 1000

    /**
     * 时钟。生产环境是系统时间；测试替换它来快进，避免 `Thread.sleep` 让单测变慢变脆。
     *
     * 用属性而不是函数参数，是因为调用点是 `sessionAllowed += key` 这种集合操作——
     * 集合的 `add` 签名里塞不进时间。把时间变成存储自己的细节，调用方看到的仍是
     * 一个普通的 `MutableSet<String>`。
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val byConversation = ConcurrentHashMap<String, ExpiringKeySet>()

    /** 取某个对话的白名单视图；同一对话多次调用返回同一个可变集合。 */
    fun of(conversationId: String): MutableSet<String> =
        // computeIfAbsent 而不是 getOrPut：后者是「查—建—写」三步，并发时可能建出两个
        // 集合、丢掉其中一边刚加进去的键（minSdk 26，computeIfAbsent 可用）。
        byConversation.computeIfAbsent(conversationId) {
            ExpiringKeySet(TTL_MS) { clock() }
        }

    /** 测试用：清空全部对话的授权并复位时钟。生产代码不应调用。 */
    internal fun resetForTest() {
        byConversation.clear()
        clock = { System.currentTimeMillis() }
    }

    /**
     * 带过期的键集合。
     *
     * 实现成 [AbstractMutableSet] 而不是直接换 `Map`，是为了让调用点继续写
     * `sessionAllowed += grant.key` / `grant.key in sessionAllowed`——审批链在
     * `ChatViewModel.requestApproval` 里，那里每多一行都要跟架构守卫的行数上限打架，
     * 而且改签名会把「过期」这个新概念泄漏到业务代码里，变成每个调用点自己判时间。
     */
    private class ExpiringKeySet(
        private val ttlMs: Long,
        private val now: () -> Long
    ) : AbstractMutableSet<String>() {

        /** key → 到期时刻（epoch ms）。 */
        private val expiry = ConcurrentHashMap<String, Long>()

        override val size: Int
            get() {
                purgeExpired()
                return expiry.size
            }

        /**
         * 加入或**续期**。返回 true 表示此前不存在（集合语义），
         * 但无论如何到期时刻都被刷新——用户再次点「始终允许」就是重新开始计时。
         */
        override fun add(element: String): Boolean {
            val previous = expiry.put(element, now() + ttlMs)
            return previous == null
        }

        override fun contains(element: String): Boolean {
            val deadline = expiry[element] ?: return false
            if (now() >= deadline) {
                // remove(key, value) 而不是 remove(key)：并发下若另一线程刚续过期，
                // 这里删掉的必须是「我看到的那张过期票」，不能误删新票。
                expiry.remove(element, deadline)
                return false
            }
            return true
        }

        override fun remove(element: String): Boolean = expiry.remove(element) != null

        override fun iterator(): MutableIterator<String> {
            purgeExpired()
            val delegate = expiry.keys.iterator()
            return object : MutableIterator<String> {
                override fun hasNext(): Boolean = delegate.hasNext()
                override fun next(): String = delegate.next()
                override fun remove() = delegate.remove()
            }
        }

        private fun purgeExpired() {
            val cutoff = now()
            expiry.entries.removeIf { it.value <= cutoff }
        }
    }
}
