package com.beichen.polaris.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入区状态（ComposerState）的行为判据。
 *
 * ## 为什么这些必须真跑，不能 grep
 *
 * 批 1 把 `input` / `pendingImages` / `pendingFiles` 从三份公开的 `MutableStateFlow`
 * 收敛成一个 owner。能否收敛成功，取决于三件事：
 *
 * ① [ComposerState.edit] 是不是**原子**读改写；
 * ② [ComposerState.takeAttachments] 是不是**取走即清空**；
 * ③ 附件增删的边界（空追加、同名移除）。
 *
 * 这三条都只能靠运行验证。尤其 ①：写成 `_input.value = transform(_input.value)`
 * 在单线程下一模一样，代码评审看不出区别——只有并发才暴露。而它一旦退回去，
 * W-31（界面本地副本与真实值不同步）就会以「刚说的话没了」的形式复发。
 *
 * ## 依赖
 *
 * 零 Android：本类不 import android.*，所以这些用例能在 JVM 上直接跑
 * （scripts/qa/mutation-gate.py 的 composer 组会编译并执行它）。
 */
class ComposerStateTest {

    @Test
    fun `set 与 clear 同步到公开流`() {
        val composer = ComposerState()
        composer.set("你好")
        assertEquals("你好", composer.text)
        assertEquals("你好", composer.input.value)

        composer.clear()
        assertEquals("", composer.text)
        assertEquals("", composer.input.value)
    }

    /**
     * 并发 edit 不丢更新。
     *
     * 判据是「最终长度 == 总调用次数」。非原子实现（读一次 value、算、整体赋值）
     * 在并发下必然丢掉一部分——交错的写者互相覆盖。
     */
    @Test(timeout = 20_000)
    fun `并发 edit 不丢更新`() = runBlocking {
        val composer = ComposerState()
        val workers = 50
        val perWorker = 100
        (1..workers).map {
            launch(Dispatchers.Default) {
                repeat(perWorker) { composer.edit { s -> s + "x" } }
            }
        }.forEach { it.join() }

        assertEquals(workers * perWorker, composer.text.length)
    }

    @Test
    fun `edit 在空文本上直接填入`() {
        val composer = ComposerState()
        composer.edit { if (it.isBlank()) "提示词正文" else "$it\n提示词正文" }
        assertEquals("提示词正文", composer.text)
        composer.edit { if (it.isBlank()) "下一段" else "$it\n下一段" }
        assertEquals("提示词正文\n下一段", composer.text)
    }

    @Test
    fun `takeAttachments 取走并清空`() {
        val composer = ComposerState()
        composer.addImages(listOf("data:a", "data:b"))
        composer.addFile(PendingFile("a.txt", "aaa"))

        val taken = composer.takeAttachments()
        assertEquals(listOf("data:a", "data:b"), taken.images)
        assertEquals(listOf("a.txt"), taken.files.map { it.name })
        assertTrue("图片必须被清空", composer.pendingImages.value.isEmpty())
        assertTrue("文件必须被清空", composer.pendingFiles.value.isEmpty())

        val again = composer.takeAttachments()
        assertTrue("第二次摘取必须为空（第一次真的清空了）", again.images.isEmpty() && again.files.isEmpty())
    }

    /**
     * 并发 addImages 不丢图片。
     *
     * 与 [并发 edit 不丢更新] 同构，但走的是另一条路径：PDF 转图（一次多张）与相册选图
     * 可能同时在跑，若用 `_pendingImages.value = value + urls` 两步写，后写者会覆盖先写者——
     * 用户看到的是「选了三张，只进去两张」，且没有任何报错。
     */
    @Test(timeout = 20_000)
    fun `并发 addImages 不丢图片`() = runBlocking {
        val composer = ComposerState()
        val workers = 50
        val perWorker = 100
        (1..workers).map { w ->
            launch(Dispatchers.Default) {
                repeat(perWorker) { i -> composer.addImages(listOf("img-$w-$i")) }
            }
        }.forEach { it.join() }

        assertEquals(workers * perWorker, composer.imageCount)
    }

    /** 空追加不该改变内容（`addImages(emptyList())` 在 PDF 转图失败时会被调到）。 */
    @Test
    fun `空列表追加不改变内容`() {
        val composer = ComposerState()
        composer.addImages(listOf("a"))
        composer.addImages(emptyList())
        assertEquals(listOf("a"), composer.pendingImages.value)
    }

    @Test
    fun `移除图片按值全删`() {
        val composer = ComposerState()
        composer.addImages(listOf("a", "b", "a"))
        composer.removeImage("a")
        assertEquals(listOf("b"), composer.pendingImages.value)
    }

    @Test
    fun `移除文件按名全删`() {
        val composer = ComposerState()
        composer.addFile(PendingFile("x.txt", "1"))
        composer.addFile(PendingFile("y.txt", "2"))
        composer.addFile(PendingFile("x.txt", "3"))
        composer.removeFile("x.txt")
        assertEquals(listOf("y.txt"), composer.pendingFiles.value.map { it.name })
    }

    /**
     * 能不能发：四条组合。
     *
     * 真机反馈「单纯的文件或图片不让发」——旧实现两处都只看文字，于是选了图之后
     * 发送键一直是灰的。这不是样式问题，是「有没有内容可发」的判定缺了附件那一半。
     * 判据必须四条全覆盖：只补「有图就能发」而漏掉「空的时候仍不能发」，
     * 会让一个空输入框也能发出空气泡。
     */
    @Test
    fun `canSend 纯文字可发`() {
        val composer = ComposerState()
        composer.set("你好")
        assertTrue(composer.canSend())
    }

    @Test
    fun `canSend 只有图片可发`() {
        val composer = ComposerState()
        composer.addImages(listOf("data:image/jpeg;base64,AAA"))
        assertTrue(composer.canSend())
    }

    @Test
    fun `canSend 只有文件可发`() {
        val composer = ComposerState()
        composer.addFile(PendingFile("a.txt", "aaa"))
        assertTrue(composer.canSend())
    }

    @Test
    fun `canSend 全空不可发`() {
        val composer = ComposerState()
        assertTrue("空输入框不该能发", !composer.canSend())
        composer.set("   ")
        assertTrue("纯空白也算空", !composer.canSend())
    }
}
