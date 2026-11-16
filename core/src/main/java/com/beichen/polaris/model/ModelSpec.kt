package com.beichen.polaris.model

/**
 * 应用里所有「装得下、删得掉」的可选内容。
 *
 * ## 为什么做成一张表
 *
 * 识别、内置音色、声音克隆是三个引擎，但它们对用户是同一种东西：
 * **一段可以下载、也可以删掉的私有数据**。以前前两者随包装、第三个也随包装，
 * 安装包 361MB，其中 335MB 是「只想打字的人永远用不到」的权重。
 *
 * 收在一张表里的好处是界面能一屏列全（体积、状态、下载、删除都一样），
 * 加一套内容不用再写一遍下载器——只需要在这里加一行。
 *
 * 2026-11-16 晚：这张表从「语音模型」扩成「可选内容」。容器根文件系统（8MB）与
 * Shizuku 安装包（2.5MB）也搬了进来——它们和模型是同一类东西：**一小撮人会用，
 * 其余人一个字节都不该为此付安装包体积**。用户的原话是「你要什么就自己下，
 * 而不是全部下好再给你」。
 *
 * ## 为什么 zip 而不是逐文件
 *
 * 克隆模型的 `espeak-ng-data` 是几千个小文件。逐文件下意味着几千次请求、
 * 几千行清单，任何一次断掉都要重来一遍清单核对。整包一个 zip，清单一行、
 * 校验一次、解压一次。识别模型本来只有四个文件，也一并统一——少一套分支。
 *
 * 字段说明：
 * - [zipName] 发布在 latest Release 上的资产名
 * - [home] 落盘父目录（`filesDir/asr` / `filesDir/tts`）
 * - [dirName] 落盘子目录名；引擎读的就是这个路径，**不能随便改**
 * - [required] 解压后必须存在的相对路径（目录只判非空）。缺一个就判定失败，
 *   而不是等原生层报一句看不出原因的 `Invalid OfflineTtsConfig`
 * - [sha256] 该 zip 的**固定哈希**（D-7）。见下方「信任根」说明
 *
 * ## 信任根（D-7）
 *
 * 修前的校验链是：`model-manifest.txt` 说 sha256 是 X → 下载器核对下载结果是不是 X。
 * 这条链**自洽但不独立**：清单和 zip 放在同一个 Release 里，能换掉其中一个的人
 * 也能换掉另一个。也就是说它只防传输损坏，不防"服务端说这是对的"。
 *
 * 威胁模型是真实的：仓库账号被盗、CI 产物被替换、镜像站缓存污染——三种都允许
 * 攻击者同时改资产与清单，然后让用户把一份"校验通过"的权重装进设备。
 *
 * 所以这里加一个**内嵌的**哈希：与 manifest 交叉校验，两边不一致直接拒绝，
 * 而且**不换源重试**（内容本身就是错的，换源只会换个方式失败）。
 *
 * 为什么不用签名：manifest 签名需要一把私钥与一个发布流程，密钥一旦要存在 CI 里，
 * 它被同一次入侵拿走的概率与资产被换掉一样高。内嵌哈希的信任根在**已发布的 APK** 里
 * ——攻击者要一起改，得先让用户装上一个新的 APK，而 APK 有签名校验。
 *
 * 当前为 `null` 表示「还没固定」：行为退回修前（只信 manifest），但下载器会打一条
 * warn 日志，`ModelSpecVerification` 也会把未固定的条数列出来。填值是一次性运维动作：
 * 模型上传后跑 `scripts/tools/model-hashes.sh` 生成。**不要为了让日志安静而随便填。**
 */
enum class ModelSpec(
    val zipName: String,
    val home: String,
    val dirName: String,
    val label: String,
    val approxMb: Int,
    val kind: Kind,
    val required: List<String>,
    /**
     * 该 zip 的固定 sha256（小写十六进制）。null = 尚未固定，信任根仍在服务端。
     *
     * 见类注释「信任根」一节：填写它是**运维动作**，不是代码改动。
     */
    val sha256: String? = null,
) {

    /** 流式 zipformer 14M：边说边出字，24MB。 */
    AsrFast(
        zipName = "asr-fast.zip",
        home = "asr",
        dirName = "zh-streaming",
        label = "快速识别",
        approxMb = 24,
        kind = Kind.ASR,
        required = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
    ),

    /** 整段 paraformer-zh-small：说完再认，74MB，中文短句基本不用改字。 */
    AsrAccurate(
        zipName = "asr-accurate.zip",
        home = "asr",
        dirName = "zh-paraformer",
        label = "更准识别",
        approxMb = 74,
        kind = Kind.ASR,
        required = listOf("model.int8.onnx", "tokens.txt"),
    ),

    /** matcha-icefall-zh-baker + vocos 22kHz 声码器：一个固定的女声。 */
    TtsMatcha(
        zipName = "tts-matcha.zip",
        home = "tts",
        dirName = "matcha",
        label = "内置音色",
        approxMb = 139,
        kind = Kind.TTS,
        required = listOf(
            "model-steps-3.onnx",
            "vocos-22khz-univ.onnx",
            "lexicon.txt",
            "tokens.txt",
            "date.fst",
            "number.fst",
            "dict",
        ),
    ),

    /**
     * Alpine 根文件系统。
     *
     * 2026-11-16 晚从「每次都要下载」退回「**随包内置**」：8.7MB 的 tar 压进 APK 约 3MB，
     * 换的是「装完就能起容器、飞行模式也行」。它留在这张表里是为了裁剪包与老版本——
     * `RootfsManager.hasArchive` 先看磁盘、再看 assets，两条来源都能起容器。
     */
    RootfsAlpine(
        zipName = "rootfs-alpine.zip",
        home = "rootfs",
        dirName = "alpine",
        label = "Alpine 容器",
        approxMb = 3,
        kind = Kind.CONTAINER,
        // 归档是 tar.gz（3MB）而不是裸 tar（8.7MB）：gzip 这一层能省 5MB 流量，
        // 而 RootfsManager 本来就按魔数识别 gzip。这条只影响「下载兜底」那条路，
        // 内置走的是 assets 里的裸 tar。
        required = listOf("alpine.tar.gz"),
    ),

    /** Shizuku 安装包：只在用「靠 Shizuku 提权」的自动化功能时才需要，2.5MB。 */
    Shizuku(
        zipName = "shizuku.zip",
        home = "tools",
        dirName = "shizuku",
        label = "Shizuku",
        approxMb = 3,
        kind = Kind.TOOL,
        required = listOf("shizuku.apk"),
    ),

    /** ZipVoice distill int8 + vocos 24kHz：零样本克隆，必须带参考音频才出声。 */
    TtsZipVoice(
        zipName = "tts-zipvoice.zip",
        home = "tts",
        dirName = "zipvoice",
        label = "声音克隆",
        approxMb = 196,
        kind = Kind.TTS,
        required = listOf(
            "tokens.txt",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "vocos_24khz.onnx",
            "lexicon.txt",
            "espeak-ng-data",
        ),
    ),
    ;

    /** 界面上按用途分组，也决定「删之前该释放哪边」。 */
    enum class Kind {
        /** 语音识别：删前要释放两套识别器。 */
        ASR,

        /** 语音合成：删前要释放 TTS 引擎。 */
        TTS,

        /** 容器根文件系统：删前要停掉容器。 */
        CONTAINER,

        /** 外部工具包（Shizuku）：删了不影响运行中的应用。 */
        TOOL,
    }
}
