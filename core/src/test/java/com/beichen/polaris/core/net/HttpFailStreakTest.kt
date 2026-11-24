package com.beichen.polaris.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「同一地址连续失败」的记忆（2026-11-24）。
 *
 * ## 为什么需要它
 *
 * 真机上出现过连续 6 个 `http_request 已失败`：模型看不到「这个地址刚才已经栽过」，
 * 只看到「这次超时」，于是换个参数继续撞，每次白等一个读超时。这个类就是那句
 * 「别再撞了」的依据，所以三件事都不能错：
 *
 *  1. 数得准（连续次数不对，提示该出现的时候不出现）；
 *  2. 成功要清零（不清的话一个正常地址会永远挂着「已失败」）；
 *  3. 不同 host 不能串（串了就会劝模型放弃一个其实好好的源）。
 */
class HttpFailStreakTest {

    @Test
    fun `连续失败逐个累加`() {
        val s = HttpFailStreak()
        assertEquals("第一次", 1, s.noteFailure("api.example.com"))
        assertEquals("第二次", 2, s.noteFailure("api.example.com"))
        assertEquals("第三次", 3, s.noteFailure("api.example.com"))
        assertEquals("查询不产生副作用", 3, s.count("api.example.com"))
    }

    /**
     * 成功必须清零。
     *
     * 不做清零的话：一个每天查十次天气的地址，某个下午网络抖了两次之后，
     * 之后每次失败都会直接触发「已连续失败 N 次，请换源」——提示还在，但已经不代表
     * 「现在这个源不行」，只是历史累计，模型会被误导着过早放弃。
     */
    @Test
    fun `成功之后重新计数`() {
        val s = HttpFailStreak()
        s.noteFailure("a.com")
        s.noteFailure("a.com")
        s.noteSuccess("a.com")
        assertEquals("成功后清零", 0, s.count("a.com"))
        assertEquals("再失败从 1 开始", 1, s.noteFailure("a.com"))
    }

    @Test
    fun `不同 host 互不影响`() {
        val s = HttpFailStreak()
        s.noteFailure("a.com")
        s.noteFailure("a.com")
        assertEquals("a 的计数不该被 b 带动", 0, s.count("b.com"))
        assertEquals("b 是第一次", 1, s.noteFailure("b.com"))
        assertEquals("a 保持 2", 2, s.count("a.com"))
        s.noteSuccess("b.com")
        assertEquals("清 b 不动 a", 2, s.count("a.com"))
    }

    /**
     * 空 key（地址根本没解析出 host）跳过计数。
     *
     * 这种失败通常是「模型把 URL 写错了」，该改的是地址本身；此时若累计起来并回喂
     * 「换个数据源」，模型会去换一个根本没问题的地方，真正的问题（少写了 https://）反而留着。
     */
    @Test
    fun `空 key 不计数`() {
        val s = HttpFailStreak()
        assertEquals(0, s.noteFailure(""))
        assertEquals(0, s.count(""))
        s.noteSuccess("") // 不该抛
    }

    /**
     * 并发下不丢计数。
     *
     * 工具本身在批次里串行，但不同轮次之间、以及未来放开并发时，多个协程可能同时落到
     * 同一个 host 上。这里只判「总数对」——顺序不可预测，但 200 次失败之后计数必须是 200
     * 以内的最大值（封顶 CAP 时也一样），而不是因为读改写竞争少记了几十次。
     */
    @Test
    fun `并发累加不丢`() {
        val s = HttpFailStreak()
        val threads = 8
        val per = 25
        val started = AtomicInteger()
        val done = AtomicInteger()
        val ts = (1..threads).map {
            Thread {
                started.incrementAndGet()
                repeat(per) { s.noteFailure("race.com") }
                done.incrementAndGet()
            }.apply { start() }
        }
        ts.forEach { it.join() }
        assertEquals(threads, started.get())
        assertEquals(threads, done.get())
        // 8 × 25 = 200，超过封顶值就按封顶算；无论哪种，都不能小于封顶前的实际次数。
        assertTrue("并发下计数被吃掉：${s.count("race.com")}", s.count("race.com") >= 99)
    }
}
