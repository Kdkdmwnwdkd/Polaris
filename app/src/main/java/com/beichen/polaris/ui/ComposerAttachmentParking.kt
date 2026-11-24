package com.beichen.polaris.ui

/**
 * 待发附件的「按会话暂存」。
 *
 * ## 为什么需要它
 *
 * 文本有 `DraftStore` 落盘，切走能存、切回能取；附件没有（base64 体积，见
 * [ComposerState.takeAttachments] 的注释）。上一步只做了「切会话就丢弃」，
 * 真机自测 A3 的期望却是：**新会话里没有它，切回原会话它还在**。
 * 一句话：附件要跟会话走，但不许跨会话露面。
 *
 * 不能落盘的原因不变（`drafts.json` 会从几 KB 涨到几十 MB，备份整份带走），
 * 所以这里只存内存：进程结束即失，与图片本身的生命周期一致，不假装持久化。
 *
 * ## 三条不变量
 *
 * ① **取出即移除**：切回某个会话时把它取走，之后不再有第二份拷贝。
 *   否则「切回 A → 去 B → 再回 A」会看到重复的图，而用户只选过一次。
 * ② **有上限**：会话数与总张数双限。8 张 base64 就可能十几 MB，多开会话叠起来
 *   足以把后台的 App 顶掉——而用户完全不知道是「几张没发的图」干的。
 * ③ **淘汰是显式的**：park 返回被淘汰的条数，调用方据此提示一次；不静默吞。
 *   静默吞的后果是「切回来图少了一张」，用户只会怀疑 App 坏了。
 *
 * 纯 JVM：零 Android / 零 Compose，[handoffAttachments] 也是 top-level 函数，
 * 能在 scripts/qa 里和 ComposerState 一起真跑（见 mutation-gate 的 composer 组）。
 */
internal class ComposerAttachmentParking(
    private val maxConversations: Int = DEFAULT_MAX_CONVERSATIONS,
    private val maxImages: Int = DEFAULT_MAX_IMAGES,
) {

    /** 插入序 = 最近一次 park 的先后；LinkedHashMap 的迭代序天然就是淘汰序。 */
    private val slots = LinkedHashMap<String, ComposerState.Attachments>()

    /** 暂存一条会话的附件；返回因上限被淘汰掉的**总条数**（0 = 没淘汰）。 */
    fun park(convId: String, attachments: ComposerState.Attachments): Int {
        if (attachments.isEmpty()) {
            slots.remove(convId)
            return 0
        }
        // 先删再放：LinkedHashMap 的 put 不改变已有键的顺序，不删会让「刚离开的会话」
        // 仍按它第一次进入的次序被优先淘汰——正好淘汰掉用户最可能切回去的那条。
        slots.remove(convId)
        slots[convId] = attachments

        var evicted = 0
        while (slots.size > maxConversations) evicted += dropOldest()
        // 张数超限时整条会话淘汰，不拆半个：只剩图片、文件被吞掉的半份附件
        // 比「这条会话的附件整体没了」更难解释。
        while (images > maxImages && slots.size > 1) evicted += dropOldest()
        return evicted
    }

    /** 取走某条会话的暂存（取完即移除，见不变量 ①）。 */
    fun take(convId: String): ComposerState.Attachments? = slots.remove(convId)

    /** 丢掉某条会话的暂存（会话被删除 / 整体被备份覆盖时）。 */
    fun drop(convId: String) {
        slots.remove(convId)
    }

    /** 清空全部暂存（进程级重置用）。 */
    fun clear() = slots.clear()

    /** 暂存中的会话数——上限判定的读法，也给断言用。 */
    val conversations: Int get() = slots.size

    /** 暂存中的图片总张数（文件不按张算）。 */
    val images: Int get() = slots.values.sumOf { it.images.size }

    private fun dropOldest(): Int {
        val oldest = slots.keys.firstOrNull() ?: return 0
        return slots.remove(oldest)?.count ?: 0
    }

    companion object {
        /** 同时记住几条会话。3 条覆盖「A → B → 回来」与「A → B → C → 回 A」两种真实走法。 */
        const val DEFAULT_MAX_CONVERSATIONS = 3

        /** 暂存图片总张数上限。单会话上限是 8（AttachmentIo.MAX_PENDING_IMAGES），这里留 2 条的余量。 */
        const val DEFAULT_MAX_IMAGES = 16
    }
}

/**
 * 输入区换主人时的附件交接：旧会话的存起来，新会话的取回来。
 *
 * 为什么要做成 top-level 函数而不是写在 `ChatViewModel` 里：VM 拖 Compose，沙箱编不动，
 * 而这段逻辑正是「附件会不会串会话」的全部——它必须能被 harness 真跑。VM 那边只剩
 * 一行调用与一次提示。
 *
 * @param keepPrevious false 用在「旧会话已经不存在」的路径（删除 / 备份恢复）：
 *   存下来也再没有人能切回去，只会白占内存。
 * @return 因暂存上限被淘汰的条数，供调用方决定要不要提示。
 */
internal fun handoffAttachments(
    composer: ComposerState,
    parking: ComposerAttachmentParking,
    prev: String,
    next: String,
    keepPrevious: Boolean,
): Int {
    if (prev == next) return 0
    val taken = composer.takeAttachments()
    var evicted = 0
    if (keepPrevious) {
        if (!taken.isEmpty()) evicted = parking.park(prev, taken)
    } else {
        // 旧会话没了，它的暂存也不会再被取走——顺手清掉，别让内存里留一份孤儿。
        parking.drop(prev)
    }
    // 目标会话有暂存就还给它。恢复用追加而非覆盖：极端时序下用户可能已经在
    // 新会话里选了一张，覆盖会把它吞掉（与 W-31 同一类错误：读改写不该整体赋值）。
    parking.take(next)?.let { composer.restoreAttachments(it) }
    return evicted
}
