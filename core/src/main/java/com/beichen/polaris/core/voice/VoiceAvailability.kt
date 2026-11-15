package com.beichen.polaris.core.voice

/**
 * 「现在这一刻，各路音色到底能不能用」——回落链的输入事实。
 *
 * ## 为什么是一个结构体而不是几个布尔参数
 *
 * 三个布尔参数并排传，调用方几乎必然把顺序写错，而错了之后**不会编译失败**：
 * 都是 `false` 的时候链看起来「正常地」一路退到系统引擎，日志里写「模型未就绪」，
 * 而实际原因是没网。把名字写进字段，这类错误就不再可能发生。
 *
 * ## 为什么 `reference` 与 `cloneModel` 分开
 *
 * 克隆音色要两样东西同时在场：ZipVoice 模型（随包）和用户的参考音频（用户数据，
 * 可能被清理、可能录得太短）。合成时缺任何一个都发不出声，但用户该做的事完全不同：
 * 前者是「重装应用／检查存储」，后者是「重新录一遍」。
 */
data class VoiceAvailability(
    /** 当前有网。在线音色的唯一前提。 */
    val online: Boolean,
    /** 内置本地模型（matcha 22kHz）已就位。 */
    val localModel: Boolean,
    /** 克隆模型（ZipVoice）已就位。 */
    val cloneModel: Boolean,
    /** 选中那条克隆音色的参考音频可用且长度够。与「选了哪条」无关，由调用方按当前选择算。 */
    val reference: Boolean,
) {
    companion object {
        /**
         * 全部就绪。
         *
         * 不是给生产路径用的，是给「只想问回落顺序」的调用方（含 QA）用的：
         * 让它显式说出「我按全都可用来看链」，比默认参数悄悄猜一个值诚实。
         */
        val ALL_READY = VoiceAvailability(
            online = true,
            localModel = true,
            cloneModel = true,
            reference = true,
        )
    }
}
