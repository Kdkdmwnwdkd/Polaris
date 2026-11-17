package com.beichen.polaris.pet

import android.content.Context

/**
 * 桌宠会话的进程级持有者。
 *
 * ## 修的是什么
 *
 * 原来会话在 `PetOverlayService.onCreate` 里 new，`onDestroy` 里丢掉。于是
 * 「⋯ → 关闭桌面助手」不只是关窗口，是**把上下文一起烧掉**：用户刚问完「帮我看看这份
 * 文档」，转身关掉、再打开，桌宠不记得刚才说过什么。桌宠的会话本来就只有几十条、
 * 全部在内存里，保留它的成本接近零，丢掉它的代价是每一次关闭都在惩罚用户。
 *
 * ## 为什么是 object 而不是 Service 字段
 *
 * 服务会被 `stopSelf` 与系统回收来回重建（`START_STICKY`），字段活不过一次重建。
 * 而「用户这次打开桌宠想聊的事」不该跟着服务生命周期走——它跟着**用户**走。
 *
 * 进程死亡后照样丢，这是刻意的：桌宠的定位是随手问一句，不是长期记忆。
 * 真要长期保存，那是主界面的会话，不是这里。
 *
 * ## 线程
 *
 * `PetAgentSession.ask` 自带 Mutex，同一时刻只跑一轮。这里只保证实例唯一，
 * 双重检查用 `synchronized`——它只在 `onCreate` 与首次提问时被调用，不在热路径上。
 */
object PetConversation {

    private val lock = Any()

    private var session: PetAgentSession? = null

    fun of(context: Context): PetAgentSession = synchronized(lock) {
        session ?: PetAgentSession(context.applicationContext).also { session = it }
    }

    /** 仅供测试与「退出登录」类场景；正常关闭桌宠**不要**调它。 */
    fun clear() {
        synchronized(lock) { session = null }
    }
}
