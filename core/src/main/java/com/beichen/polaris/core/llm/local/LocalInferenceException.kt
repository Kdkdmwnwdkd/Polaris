package com.beichen.polaris.core.llm.local

/**
 * 本地推理失败（不是网络问题）。
 *
 * 为什么不复用 [java.io.IOException]：`ChatViewModel.friendlyMessage` 把所有
 * IOException 一律翻译成「📶 网络不可用或超时」。本地模型根本不出网，用户切到
 * 本地后收到一句「检查网络连接」，只会被带到完全错误的方向去排查。
 *
 * 也用于把这类错误标记为**不可重试**：本地失败是确定性的（同一段 prompt、
 * 同一个 native 库，重试一次就是再失败一次），走退避重试只会让用户干等 13 秒
 * 才看到一句「出错了」。
 */
class LocalInferenceException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
