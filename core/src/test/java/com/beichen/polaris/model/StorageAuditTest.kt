package com.beichen.polaris.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间账本（数据层）。
 *
 * ## 为什么这几条必须钉死
 *
 * 「我的 6GB 是什么」这件事，错的方式是**静默的**：数字照样显示，只是漏了一块或者
 * 把两块算了两遍。用户看到的是一张自信的表格，和系统设置里的数字对不上，
 * 于是开始怀疑「你们是不是偷偷下了东西」——比不给这张表更糟。
 *
 * 所以这里只守两件最容易被改坏的事：
 *  1. 账要平（总数 − 已归类 = 账外，且不许出现负数）；
 *  2. 归类要按「能不能退」分，而不是按技术类型——「可清理」这个数是给用户点按钮用的，
 *     算错了他会删掉不该删的东西。
 */
class StorageAuditTest {

    private fun item(
        id: String,
        bytes: Long,
        bucket: Bucket,
        clearable: Boolean = false
    ) = SpaceItem(
        id = id,
        label = id,
        detail = "",
        note = "",
        bytes = bytes,
        bucket = bucket,
        clearable = clearable
    )

    private fun audit(items: List<SpaceItem>, total: Long, apk: Long = 0, native: Long = 0) =
        StorageAudit(
            items = items,
            apkBytes = apk,
            nativeBytes = native,
            totalBytes = total,
            otherBytes = reconcile(total, items)
        )

    @Test
    fun `账平：总数减已归类等于账外`() {
        val items = listOf(item("a", 300, Bucket.MODEL), item("b", 200, Bucket.CONTENT))
        assertEquals(500L, reconcile(1000, items))
    }

    /**
     * 账外为负 = 两条条目指向了同一个目录（重复计入）。
     *
     * 归零而不是让它显示成 -200MB：负数出现在界面上，用户第一反应是「这软件坏了」，
     * 而不是「你们的统计有 bug」。真正的信号应该由日志承担。
     */
    @Test
    fun `重复计入时账外归零，不出现负数`() {
        val items = listOf(item("a", 800, Bucket.MODEL), item("b", 800, Bucket.MODEL))
        assertEquals(0L, reconcile(1000, items))
    }

    @Test
    fun `没有条目时账外等于总数`() {
        assertEquals(4096L, reconcile(4096, emptyList()))
    }

    @Test
    fun `可清理只算标了 clearable 的`() {
        val items = listOf(
            item("cache", 500, Bucket.TEMP, clearable = true),
            item("logs", 100, Bucket.TEMP, clearable = true),
            item("models", 4_000, Bucket.MODEL),
            item("chats", 300, Bucket.CONTENT)
        )
        val a = audit(items, items.sumOf { it.bytes })
        assertEquals(600L, a.reclaimableBytes)
    }

    /** 删模型是要用户做决定的事，不能被算进「一键能腾出多少」——那会诱导他误删。 */
    @Test
    fun `模型不计入可清理`() {
        val items = listOf(item("models", 4_000, Bucket.MODEL))
        assertEquals(0L, audit(items, 4_000).reclaimableBytes)
    }

    @Test
    fun `按组求和`() {
        val items = listOf(
            item("apk", 48, Bucket.BODY),
            item("native", 90, Bucket.BODY),
            item("asr", 24, Bucket.MODEL),
            item("cache", 8, Bucket.TEMP)
        )
        val a = audit(items, 170)
        assertEquals(138L, a.bytesOf(Bucket.BODY))
        assertEquals(24L, a.bytesOf(Bucket.MODEL))
        assertEquals(8L, a.bytesOf(Bucket.TEMP))
        assertEquals(0L, a.bytesOf(Bucket.CONTENT))
    }

    /** 一个字节都没算出来的组不该画在界面上（空组只会让人以为「这里本来该有东西」）。 */
    @Test
    fun `空组判定`() {
        val a = audit(listOf(item("cache", 2048, Bucket.TEMP)), 2048)
        assertTrue("CONTENT 组应判空", a.isEmpty(Bucket.CONTENT))
        assertTrue("TEMP 组不该判空", !a.isEmpty(Bucket.TEMP))
    }

    /** 不足 1KB 的残留不值得占一行（几十个空目录各占 4KB 会把列表撑满）。 */
    @Test
    fun `小于 1KB 视为空`() {
        val a = audit(listOf(item("tiny", 512, Bucket.CONTENT)), 512)
        assertTrue(a.isEmpty(Bucket.CONTENT))
    }

    @Test
    fun `分组合并时不丢条目`() {
        val items = listOf(
            item("a", 1, Bucket.TEMP),
            item("b", 2, Bucket.TEMP),
            item("c", 3, Bucket.TEMP)
        )
        assertEquals(3, audit(items, 6).of(Bucket.TEMP).size)
    }
}
