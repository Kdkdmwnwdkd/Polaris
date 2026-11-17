package com.beichen.polaris.core.agent

/**
 * 审批弹窗里的「将执行」预览。
 *
 * ## 修的是什么
 *
 * 原来只有一句 `args.toString().take(500)`。而审批的**判定**用的是全量参数
 * （[ApprovalScope.of] 解析 `command` / `url` / 路径）。两者不同源时，危险的那一半
 * 很可能正好在被砍掉的尾部：
 *
 * ```
 * {"command":"cd ~/work && ./gradlew assembleRelease && rm -rf build/cache && ..."}
 * ```
 *
 * 前 500 字符恰好停在 `rm -rf` 之前，用户看到的是「构建一下」，点「允许」，
 * 实际执行的是构建 + 删除。这不是文案瑕疵，是审批通道的信息差——
 * **用户同意的是他看到的东西，不是我们实际执行的东西**。
 *
 * ## 为什么还是截断
 *
 * 完全放开会把 `ui_screenshot` 的 base64 参数（几百 KB）塞进 Compose 文本排版，
 * 弹窗会卡住甚至 OOM。所以保留上限，但改成**保头保尾**：
 * 头部给上下文（哪个工具、什么参数），尾部给结局（`&& rm -rf` 藏在这里）。
 *
 * 中间被省略的部分显式写出来（省略了多少字符），用户至少知道「这里还有东西」，
 * 而不是以为看到的就是全部。
 */
object ApprovalPreview {

    /**
     * 预览上限。
     *
     * 2000 字符 ≈ 一整条复杂 shell 命令 + 若干参数，仍远小于一张截图的 base64。
     * 保留下来的尾部占 7 成：命令的结局（重定向、`&&` 串接、目标路径）都在末尾。
     */
    const val DEFAULT_LIMIT = 2_000

    /** 头部占比。3 成够看清「这是什么参数」，7 成留给尾部。 */
    private const val HEAD_RATIO = 0.3

    /** 省略标记的预留长度（实际文案随数字长度浮动，这里只保证总量不失控）。 */
    private const val MARKER_RESERVE = 48

    /**
     * 生成预览。长度不超过 [limit] 时原样返回——**不做任何改写**，
     * 让「短参数」的弹窗与判定输入逐字节一致。
     */
    fun of(args: String, limit: Int = DEFAULT_LIMIT): String {
        if (args.length <= limit) return args
        val headLen = (limit * HEAD_RATIO).toInt().coerceAtLeast(1)
        val tailLen = (limit - headLen - MARKER_RESERVE).coerceAtLeast(1)
        val omitted = args.length - headLen - tailLen
        if (omitted <= 0) return args
        return buildString(headLen + tailLen + MARKER_RESERVE) {
            append(args, 0, headLen)
            append("\n…（中间省略 ").append(omitted).append(" 字符，以下是结尾）…\n")
            append(args, args.length - tailLen, args.length)
        }
    }
}
