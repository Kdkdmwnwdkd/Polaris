package com.beichen.polaris.audio.asr

/**
 * 两套可下载的离线识别模型。
 *
 * 为什么是「两套」而不是「一套最好的」：它们在两个方向上各有代价——
 * [Fast] 边说边出字、只占 24MB，但错字明显多；[Accurate] 要等说完、占 74MB，
 * 但中文短句基本不用改字。用户对「等一秒」和「多 50MB」的容忍度差得很远，
 * 不该由我们替他决定。
 *
 * 字段说明：
 * - [manifest] CI 传上去的清单文件名（含每个文件的 size 与 sha256）
 * - [assetPrefix] 资产名前缀；落盘名 = 去掉这个前缀，与引擎读的名字对齐
 * - [minEntries] 清单至少几行。少于这个数说明拿到的不是清单（多半是错误页）
 */
enum class AsrModel(
    val manifest: String,
    val dirName: String,
    val assetPrefix: String,
    val label: String,
    val approxMb: Int,
    val minEntries: Int,
) {

    /** 流式 zipformer 14M：边说边出字。 */
    Fast(
        manifest = "asr-manifest.txt",
        dirName = "zh-streaming",
        assetPrefix = "asr-",
        label = "快速识别",
        approxMb = 24,
        minEntries = 4,
    ),

    /** 整段 paraformer-zh-small：说完再认，错字少。 */
    Accurate(
        manifest = "asr-accurate-manifest.txt",
        dirName = "zh-paraformer",
        assetPrefix = "asr2-",
        label = "更准识别",
        approxMb = 74,
        minEntries = 2,
    ),
}
