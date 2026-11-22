package com.beichen.polaris.ui

/**
 * 语音入口点下去之后该走哪条路。
 *
 * 四条出路一一对应 [VoiceInputGate] 的四个副作用：告诉用户去下模型、开面板、
 * 跳应用详情页开权限、弹系统授权框。
 */
internal enum class VoiceInputNext {
    /** 离线模型没装：这时要权限也没用，先让用户去下模型。 */
    TellInstallModel,

    /** 已授权：直接开面板。 */
    OpenPanel,

    /** 没授权且已经问过一次：系统不会再弹，只能把人送到应用详情页手开。 */
    GoSettings,

    /** 没授权、还没问过：弹一次系统授权框。 */
    RequestPermission,
}

/**
 * 语音入口的分支判定（纯函数，零 `android` / 零 Compose）。
 *
 * ## 为什么单独抽出来
 *
 * 这段判定原来写在 Composable 的 `when {}` 里，而守它的判据是
 * `grep -A3 'if (granted) {' | grep 'open()'`——**只认字面顺序，不认语义**：
 * 把 `open()` 挪进 `else` 分支，六条 grep 全绿，而真机上「授权了也不开面板」。
 * 这个洞是我实测复现过的，不是推测。
 *
 * 抽成纯函数之后，判定能被 JUnit 逐条打靶，也能进变异门禁；Composable 只剩
 * 「按结论调用副作用」的搬运，`when` 不写 `else`，枚举漏一支编译就红。
 *
 * ## 三条顺序不能换
 *
 * 1. **未装模型优先于授权**：没模型时开面板只能得到一行红字，先要权限毫无意义；
 * 2. **已授权优先于「问过没」**：用户可能去设置里手开了权限再回来点，
 *    这时若先看 `asked` 会把人又推回设置页；
 * 3. **`asked` 只在「弹过框」之后为真**：第一次必须弹框，之后才谈「系统不再弹」。
 *
 * @param installed 离线识别模型是否已下载（`AsrDirectory.isInstalled()`）。
 * @param granted 是否已持有 `RECORD_AUDIO`。
 * @param asked 本进程内是否已经弹过一次系统授权框。
 */
internal object VoiceInputDecision {
    fun next(installed: Boolean, granted: Boolean, asked: Boolean): VoiceInputNext = when {
        !installed -> VoiceInputNext.TellInstallModel
        granted -> VoiceInputNext.OpenPanel
        asked -> VoiceInputNext.GoSettings
        else -> VoiceInputNext.RequestPermission
    }
}
