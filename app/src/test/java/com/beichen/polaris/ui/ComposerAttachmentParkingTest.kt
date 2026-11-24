package com.beichen.polaris.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待发附件的按会话暂存（[ComposerAttachmentParking]）与交接（[handoffAttachments]）。
 *
 * ## 为什么必须真跑
 *
 * 这一簇守的是真机自测 A3 的两半：「新会话里没有它」+「切回原会话它还在」。
 * 两条互为反面——只做前者就是「切走即丢」，只做后者就是「图跟着换话题跑」；
 * 任何一半退回去，界面都照常渲染，只是用户丢了一张图或看到一张不该在那儿的图。
 * grep 只能证明 park/take 这几个字存在，证明不了「切回 B 时 A 的图没跟过来」。
 *
 * ## 与 composer 组同包同测
 *
 * 本文件与 ComposerStateTest 一起由 mutation-gate 的 composer 组编译执行，
 * 用的就是生产代码（[handoffAttachments] 是 top-level 函数，不是 VM 里的拷贝）。
 */
class ComposerAttachmentParkingTest {

    private fun images(vararg ids: String) = ComposerState.Attachments(ids.toList(), emptyList())

    @Test
    fun `切走再切回：附件原样回来`() {
        val composer = ComposerState()
        val parking = ComposerAttachmentParking()

        composer.addImages(listOf("data:a"))
        composer.addFile(PendingFile("x.txt", "内容"))

        // A → B：附件存进 A 的槽位，输入区清空
        handoffAttachments(composer, parking, prev = "A", next = "B", keepPrevious = true)
        assertTrue("切到新会话后不该带着旧附件", composer.pendingImages.value.isEmpty())
        assertTrue(composer.pendingFiles.value.isEmpty())

        // B → A：取回来
        handoffAttachments(composer, parking, prev = "B", next = "A", keepPrevious = true)
        assertEquals(listOf("data:a"), composer.pendingImages.value)
        assertEquals(listOf("x.txt"), composer.pendingFiles.value.map { it.name })
        assertEquals("取走后槽位应为空", 0, parking.conversations)
    }

    @Test
    fun `切到第三条会话：只看得到自己那条的附件`() {
        val composer = ComposerState()
        val parking = ComposerAttachmentParking()

        composer.addImages(listOf("data:a"))
        handoffAttachments(composer, parking, "A", "B", true)

        // B 里选一张自己的图，再去 C
        composer.addImages(listOf("data:b"))
        handoffAttachments(composer, parking, "B", "C", true)

        assertTrue("C 里不该出现 A 或 B 的图", composer.pendingImages.value.isEmpty())

        // 回 B：只有 B 那张
        handoffAttachments(composer, parking, "C", "B", true)
        assertEquals(listOf("data:b"), composer.pendingImages.value)

        // 再回 A：A 那张仍在（三槽位上限内）
        handoffAttachments(composer, parking, "B", "A", true)
        assertEquals(listOf("data:a"), composer.pendingImages.value)
    }

    /**
     * 取出即移除。
     *
     * 不删的话「切回 A → 去 B → 再回 A」会看到两张一模一样的图（用户只选过一次），
     * 而发送时会把同一张图发两遍——看起来像「附件自己翻倍」。
     */
    @Test
    fun `取走后槽位为空，不会重复出现`() {
        val composer = ComposerState()
        val parking = ComposerAttachmentParking()

        composer.addImages(listOf("data:a"))
        handoffAttachments(composer, parking, "A", "B", true)
        handoffAttachments(composer, parking, "B", "A", true)
        assertEquals(listOf("data:a"), composer.pendingImages.value)

        // 再走一圈：A 的图已经被取走，第二次回来不该凭空多出来
        handoffAttachments(composer, parking, "A", "B", true)
        handoffAttachments(composer, parking, "B", "A", true)
        assertEquals(listOf("data:a"), composer.pendingImages.value)
        assertNull("同一个槽位不该被取出两次", parking.take("A"))
    }

    /**
     * keepPrevious=false：旧会话已经不在了（删除 / 备份恢复），不存。
     *
     * 存下来的后果是内存里留一份永远取不回来的 base64，又恰好占着 3 个槽位之一，
     * 把真正还能回去的会话挤掉。
     */
    @Test
    fun `keepPrevious 为 false 时不暂存`() {
        val composer = ComposerState()
        val parking = ComposerAttachmentParking()

        composer.addImages(listOf("data:a"))
        handoffAttachments(composer, parking, "已删除的会话", "别的会话", keepPrevious = false)

        assertTrue(composer.pendingImages.value.isEmpty())
        assertEquals(0, parking.conversations)
        assertNull(parking.take("已删除的会话"))
    }

    /** 空附件不该占一个槽位——否则「什么也没选」的会话也会挤掉真正有图的。 */
    @Test
    fun `空附件不占槽位`() {
        val composer = ComposerState()
        val parking = ComposerAttachmentParking()

        handoffAttachments(composer, parking, "A", "B", true)
        assertEquals(0, parking.conversations)
    }

    @Test
    fun `会话数超限淘汰最早的一条并返回条数`() {
        val parking = ComposerAttachmentParking(maxConversations = 2, maxImages = 100)

        assertEquals(0, parking.park("A", images("a1", "a2")))
        assertEquals(0, parking.park("B", images("b1")))
        // 第三条进来：A（最早）被淘汰，返回它那 2 条
        assertEquals(2, parking.park("C", images("c1")))
        assertEquals(2, parking.conversations)
        assertNull("A 应已被淘汰", parking.take("A"))
        assertEquals(listOf("b1"), parking.take("B")?.images)
    }

    /**
     * 重新 park 同一会话要刷新它的位置。
     *
     * LinkedHashMap 的 put 不会把已有键移到末尾。不先 remove 的话，用户刚离开的 A
     * 仍按第一次进入的次序排在最前，下一个会话进来时被淘汰的正好是它——
     * 「我刚从 A 切走，回头就发现 A 的图没了」。
     */
    @Test
    fun `重新 park 刷新顺序，淘汰真正最久没碰的那条`() {
        val parking = ComposerAttachmentParking(maxConversations = 2, maxImages = 100)

        parking.park("A", images("a1"))
        parking.park("B", images("b1"))
        // 又把 A 存了一次（A → C → A → D 这种走法里会出现）
        parking.park("A", images("a2"))
        // C 进来：应淘汰 B（最久没碰），A 留下且是新的那份
        assertEquals(1, parking.park("C", images("c1")))
        assertEquals(listOf("a2"), parking.take("A")?.images)
        assertNull(parking.take("B"))
    }

    @Test
    fun `图片总张数超限时整条淘汰`() {
        val parking = ComposerAttachmentParking(maxConversations = 5, maxImages = 3)

        parking.park("A", images("a1", "a2"))
        // 再来 2 张：总数 4 > 3，淘汰最早整条 A（返回 2 条，不是只丢一张）
        assertEquals(2, parking.park("B", images("b1", "b2")))
        assertEquals(1, parking.conversations)
        assertEquals(listOf("b1", "b2"), parking.take("B")?.images)
    }

    /** 单条会话自己就超过总上限时不能把它淘汰掉——那等于「允许的上限比一条还小」。 */
    @Test
    fun `唯一一条时不因张数超限被淘汰`() {
        val parking = ComposerAttachmentParking(maxConversations = 2, maxImages = 2)
        assertEquals(0, parking.park("A", images("a1", "a2", "a3")))
        assertEquals(listOf("a1", "a2", "a3"), parking.take("A")?.images)
    }

    /**
     * 大批量往返不丢、不重复。
     *
     * 上限是 8 张，但暂存层不该假设调用方守规矩：条数在「取 → 存 → 取」之后
     * 必须逐一相等，否则淘汰逻辑会把「丢了几条」算错，提示文案跟着错。
     */
    @Test
    fun `大批量往返不丢不重`() {
        val composer = ComposerState()
        val parking = ComposerAttachmentParking(maxConversations = 3, maxImages = 200)
        repeat(50) { composer.addImages(listOf("img-$it")) }
        handoffAttachments(composer, parking, "A", "B", true)
        handoffAttachments(composer, parking, "B", "A", true)
        assertEquals(50, composer.imageCount)
        assertEquals("不该有重复", 50, composer.pendingImages.value.distinct().size)
    }
}
