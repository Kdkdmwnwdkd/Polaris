package com.beichen.polaris.audio.asr

import com.beichen.polaris.model.ModelSpec

/**
 * 两套可下载的离线识别模型。
 *
 * 为什么是「两套」而不是「一套最好的」：它们在两个方向上各有代价——
 * [Fast] 边说边出字、只占 24MB，但错字明显多；[Accurate] 要等说完、占 74MB，
 * 但中文短句基本不用改字。用户对「等一秒」和「多 50MB」的容忍度差得很远，
 * 不该由我们替他决定。
 *
 * 2026-11-16 起两者都**不进安装包**，由 [com.beichen.polaris.model.ModelDownloader]
 * 按需下载、可删。落盘路径不变（`filesDir/asr/<dirName>`），所以从 746 升级上来的
 * 人不用重下——下载器按「必需文件在不在」判定，与当初怎么装进去的无关。
 */
enum class AsrModel(val spec: ModelSpec) {

    /** 流式 zipformer 14M：边说边出字。 */
    Fast(ModelSpec.AsrFast),

    /** 整段 paraformer-zh-small：说完再认，错字少。 */
    Accurate(ModelSpec.AsrAccurate),
    ;

    val dirName: String get() = spec.dirName
    val label: String get() = spec.label
    val approxMb: Int get() = spec.approxMb
}
