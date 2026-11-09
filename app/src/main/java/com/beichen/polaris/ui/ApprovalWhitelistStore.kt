package com.beichen.polaris.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * 「本次会话始终允许」的授权键存储，**按对话隔离**（审计 F3）。
 *
 * ## 为什么单独一个文件
 *
 * 修前它是 `ChatViewModel` 里的一个 `mutableSetOf<String>()`：进程级、跨对话共享。
 * 在会话 A 里为 `write_file` 勾过「不再询问」，切到会话 B 同样免问——而弹窗文案写的是
 * 「本次会话」。用户理解的「会话」是当前对话，实现里却是「App 进程存活期间」。
 *
 * 逻辑只有一行 `getOrPut`，但放在 ChatViewModel 里会让那个已经 3597 行的文件继续变长，
 * 而架构守卫 B 对存量超限文件的要求是**只减不增**。拆出来既守住守卫，也让这段语义
 * 有了自己的注释位置。
 *
 * ## 边界
 *
 * - 只存进程内存，App 重启即失效（与修前一致，不做长期授权持久化）；
 * - 被删除的对话会在内存里留下一个空桶，量级是「用户一生创建过的对话数」，
 *   不额外做回收——为这点内存加一条清理路径，比泄漏本身更容易出错。
 */
object ApprovalWhitelistStore {

    private val byConversation = ConcurrentHashMap<String, MutableSet<String>>()

    /** 取某个对话的白名单视图；同一对话多次调用返回同一个可变集合。 */
    fun of(conversationId: String): MutableSet<String> =
        // computeIfAbsent 而不是 getOrPut：后者是「查—建—写」三步，并发时可能建出两个
        // 集合、丢掉其中一边刚加进去的键（minSdk 26，computeIfAbsent 可用）。
        byConversation.computeIfAbsent(conversationId) { ConcurrentHashMap.newKeySet() }
}
