package com.beichen.polaris.model

import java.io.File

/**
 * 一笔能说清「删了会损失什么」的账。
 *
 * ## 为什么不用 [StorageReport]
 *
 * 那个类只回答「模型占多少」，四个字段（包体 / 运行时 / 六套固化模型 / 缓存）。
 * 它在语音页够用，用来回答「我这 6GB 是什么」则完全不够——`filesDir/models`
 * （本地大模型，单个文件就是几个 GB）、会话记录、附件、知识库、日志，一项都没算。
 * 于是用户看到「安装包 46MB + 模型 200MB」，而系统设置写着 6.38GB，两边对不上，
 * 只能靠猜。
 *
 * 这里不追求「算得准」，追求**算得平**：所有条目加起来必须等于磁盘上的总数，
 * 对不上的部分显式列成「其它」。漏掉一个目录会立刻显示出来，而不是悄悄消失。
 */
data class SpaceItem(
    val id: String,
    /** 界面上的名字，尽量用用户能懂的说法（「聊过的对话」而不是「conversations 分片」）。 */
    val label: String,
    /** 这是什么。一句，不要技术术语。 */
    val detail: String,
    /** 删了会怎样。这是用户真正要看的。 */
    val note: String,
    val bytes: Long,
    val bucket: Bucket,
    /** 能一键清掉且不影响使用。只有缓存与日志为 true。 */
    val clearable: Boolean = false
)

/** 分组：按「能不能退」分，不按技术类型分。 */
enum class Bucket(val title: String, val subtitle: String) {
    BODY("应用本体", "装完就固定，删模型也不会变小"),
    MODEL("模型与运行时", "随下随删；不想要语音的人可以一分不付"),
    CONTENT("我的内容", "聊天、附件、知识库——删了不可恢复"),
    TEMP("临时与日志", "可以直接清，不影响正常使用")
}

/**
 * 一次完整的空间体检。
 *
 * [otherBytes] 是「账外」：磁盘总数减去所有已归类条目。
 * **它不该长期大于几 MB**——大了就说明有目录没被清单覆盖。
 * 界面上如实显示，不藏。
 */
data class StorageAudit(
    val items: List<SpaceItem>,
    val apkBytes: Long,
    val nativeBytes: Long,
    /** `filesDir` + `cacheDir` + APK + 运行时库。 */
    val totalBytes: Long,
    val otherBytes: Long
) {
    fun of(bucket: Bucket): List<SpaceItem> = items.filter { it.bucket == bucket }

    fun bytesOf(bucket: Bucket): Long = of(bucket).sumOf { it.bytes }

    /** 「现在能立刻腾出多少」。只算真正可清的，不含模型——删模型是要用户做决定的。 */
    val reclaimableBytes: Long get() = items.filter { it.clearable }.sumOf { it.bytes }

    /** 有没有一项都没算出来的组（界面据此决定要不要画这一组）。 */
    fun isEmpty(bucket: Bucket): Boolean = of(bucket).all { it.bytes < 1024 }
}

/** 把字节写成给人看的单位。小于 1MB 用 KB，避免「0MB 却占着」的怪相。 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1fGB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)}MB"
    bytes >= 1024 -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}

/**
 * 账平：磁盘总数 − 已归类条目之和 = 账外。
 *
 * 抽成函数是为了能测。这条减法有两个相反方向的错误：
 *  - **清单漏目录** → 结果是大的正数（界面上多出一行「其它 2.1GB」，能看见，会去修）；
 *  - **两条条目指向同一目录** → 结果是负数（被 [coerceAtLeast] 归零，**看不见**）。
 *
 * 前者自证，后者必须靠人写对清单。所以这里不静默：负数一律归零返回，
 * 由调用方在日志里记一笔（见 auditStorage 的调用方）。归零而不是抛，
 * 是因为「统计错了」不该让整个页面打不开——用户是来查空间的，不是来收异常的。
 */
internal fun reconcile(total: Long, items: List<SpaceItem>): Long =
    (total - items.sumOf { it.bytes }).coerceAtLeast(0)

/** 「本地大模型」那一行的说明：有几个文件、最大那个多大——比一个总数有用。 */
/** 只在扫描时用，但放在纯侧——它只读文件名与长度，可在 JVM 上测。 */
internal fun localModelDetail(dir: File): String {
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty()
    if (files.isEmpty()) return "还没下。要用本地模型离线对话时才需要"
    val biggest = files.maxByOrNull { it.length() }
    return "${files.size} 个模型" + if (biggest != null) "，最大的是 ${biggest.name}（${formatBytes(biggest.length())}）" else ""
}

/**
 * 递归求目录字节数。
 *
 * 不用 `walkTopDown().sumOf { it.length() }`：那会给每个文件建一个 File 对象；
 * 模型目录动辄上千文件，在低端机上这一层额外分配能看出卡顿。直接 listFiles 递归，
 * 失败（权限/竞态删除）当作 0——统计不该因为一个读不到的文件整块失败。
 */
internal fun sizeOf(dir: File): Long {
    if (!dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    val children = dir.listFiles() ?: return 0L
    var sum = 0L
    for (child in children) {
        sum += if (child.isDirectory) sizeOf(child) else child.length()
    }
    return sum
}

/**
 * 清掉「临时与日志」这一组，返回释放的字节数。
 *
 * ## 只动这四处，整目录一个都不动
 *
 * - `cacheDir/models`：下载中的 `.part` 与解压中间态；
 * - `cacheDir/updates` 里**已下完的 .apk**：装完就没用了，但每次更新都会静静躺着
 *   一个等于安装包大小的包，这是「怎么又多了几十 MB」的一个真实来源；
 * - `cacheDir/exports`：分享出去时生成的临时 Markdown；
 * - `filesDir/logs` + `filesDir/run-journal`：日志与回合现场。
 *
 * **不动 `cacheDir` 本身**：WebView 与别的模块各有各的缓存，一个叫「清理」的按钮
 * 不该替所有人做决定——那会让下次打开网页重新加载，用户以为变慢了。
 *
 * `.part` 一律跳过：那可能正是此刻在下的包，删掉等于把进度清零。
 */

/**
 * 「清理」会动到哪些东西——**白名单，写死**。
 *
 * ## 为什么用 (根, 名) 而不是一串裸目录名
 *
 * 因为有两个 `models`：
 *
 *  - `cacheDir/models`：下载中的 `.part` 与解压中间态，**可以清**；
 *  - `filesDir/models`：用户的本地大模型，几个 GB，**绝不能清**。
 *
 * 裸名字分不出这两者。按字符串匹配写清理逻辑，某天有人加一行 `"models"`，
 * 代码看着像在清缓存，实际把用户的模型删了——而这类 bug 只在用户重下几个 GB
 * 之后才被发现，且他不会想到是我们干的。把「根」写进类型里，这种混淆就写不出来。
 *
 * 判据 [run-storage-audit-verification.sh] 逐条比对：`Root.FILES` 下出现的每一个名字，
 * 都必须在「删了不影响使用」的名单里。
 */
internal object ClearPolicy {

    enum class Root { CACHE, FILES }

    /**
     * 一个清理目标。
     *
     * @param recursive 目录级清理（删目录下所有文件）。false 表示只删符合 [suffix] 的文件。
     * @param suffix 仅 [recursive] 为 false 时生效。
     */
    data class Target(
        val root: Root,
        val name: String,
        val recursive: Boolean = true,
        val suffix: String? = null
    )

    val targets = listOf(
        // cacheDir/models 是「下载残留」，与 filesDir/models（本地大模型）毫无关系。
        // 这一行的存在完全依赖上面那句注释被读到——所以根写进了类型。
        Target(Root.CACHE, "models"),
        Target(Root.CACHE, "exports"),
        // 更新包：装完就没用了。只删 .apk，留着 .part（可能正在下）。
        Target(Root.CACHE, "updates", recursive = false, suffix = ".apk"),
        Target(Root.FILES, "logs"),
        Target(Root.FILES, "run-journal"),
    )

    /**
     * 清理时必须保留的文件：正在下载的 `.part`。
     *
     * 不跳过它，用户看到的是「下到 80% 突然从头开始」——而且他不会觉得是清理导致的，
     * 只会觉得「这破网」。这是最难自证的一类 bug：错误发生在用户的期待里。
     */
    fun keepWhileClearing(fileName: String): Boolean = fileName.endsWith(".part")
}
