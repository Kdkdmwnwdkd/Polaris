package com.beichen.polaris.ui

import com.beichen.polaris.core.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 聊天外观域（hotfix32 / 3.1 第三批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：背景图只做「一个 SAF URI + 一个浓度，落 ConfigStore」，
 * 不读会话、不碰渲染——设置页写、对话页读，两侧都只经 StateFlow。
 *
 * 位图不落 App 存储，只存 SAF URI（原实现注释的约束原样保留）；
 * 浓度 clamp 在 ConfigStore 内，这里只透传。
 */
internal class AppearanceFacade(private val configStore: ConfigStore) {

    // 真机反馈「我可以用我照片的壁纸当成壁纸」——动态取色只染控件色，
    // 这里给的是照片本体当对话页背景。
    private val _chatBackgroundUri = MutableStateFlow(configStore.chatBackgroundUri())
    val chatBackgroundUri: StateFlow<String?> = _chatBackgroundUri.asStateFlow()

    private val _chatBackgroundAlpha = MutableStateFlow(configStore.chatBackgroundAlpha())
    val chatBackgroundAlpha: StateFlow<Float> = _chatBackgroundAlpha.asStateFlow()

    /** 设置/清除背景图（uri = SAF content:// 字符串；null = 清除）。 */
    fun setChatBackground(uri: String?) {
        configStore.setChatBackgroundUri(uri)
        _chatBackgroundUri.value = configStore.chatBackgroundUri()
    }

    /** 调背景图浓度（clamp 在 ConfigStore 内，这里只透传）。 */
    fun setChatBackgroundAlpha(alpha: Float) {
        configStore.setChatBackgroundAlpha(alpha)
        _chatBackgroundAlpha.value = configStore.chatBackgroundAlpha()
    }
}
