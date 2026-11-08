package com.beichen.polaris.core.io

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 原子写一个文本文件（JSON 快照为主，但不限于 JSON）。**全仓唯一实现**。
 *
 * ## 为什么必须统一
 * 改动前全仓有 15 处 `renameTo`，写法分四类，细节各不相同：
 *
 * | 写法 | 出现处 | 问题 |
 * |---|---|---|
 * | 先 `delete()` 主文件再 `renameTo` | `AgentRunStateStore` | rename 失败 = 数据全丢，没有退路 |
 * | 写 tmp → 备份 .bak → rename | `ConversationStore` / `PromptStore` | 对，但无 fsync |
 * | 写 tmp → rename，失败后直接覆盖主文件 | 同类若干 | 对，但失败分支把自己的 tmp 也留在磁盘上 |
 * | 直接 `writeText` | 少量 | 断电必半截 |
 *
 * 三种缺陷里最隐蔽的是**没有 fsync**：`writeText` 返回只代表数据进了页缓存，
 * `rename` 是元数据操作，两者落盘顺序不保证。断电后可能出现「rename 生效了、
 * 文件长度却是 0」——这不是概率极低的都市传说，是 ext4 上 `data=writeback`
 * 与多数 Android 设备的默认行为。表现是「用了三个月，某次重启后会话全空」，
 * 而复现不了，最难查。
 *
 * 所以顺序必须是：**写完 → flush → fd.sync() → rename**。
 * 数据先落盘，再做目录项替换；最坏情况是回到旧版本，不是拿到空文件。
 *
 * ## 为什么不做目录 fsync
 * 严格的原子替换还要对父目录 fsync，保证 rename 这个目录项本身落盘。
 * Android 上要对目录 fsync 得走 `android.system.Os.open/fsync`，而 `core` 的
 * harness 跑在纯 JVM 上（无 Android 类），这条路会把测试拦在门外。
 *
 * 权衡：不做目录 fsync 的后果是「断电后可能回到上一次成功写入的版本」，
 * 而不是「拿到半截或空文件」——因为数据已经 sync 过了。加上本文的 `.bak`
 * 回退，最差结果是丢最后一次写入，不丢可用性。这个代价明确小于
 * 「为了目录 fsync 把测试变成只能真机跑」。
 *
 * ## 并发
 * 按**目标文件路径**分锁：同一个文件的多线程写串行化（否则两个线程各自
 * tmp→rename 会互相覆盖，最后留下谁的不确定），不同文件互不阻塞。
 * 用的是 `ConcurrentHashMap.computeIfAbsent`，键是 `canonicalPath`。
 */
object AtomicJsonFile {

    /** 每个目标文件一把锁；进程内单例，跟随 JVM 生命周期。 */
    private val locks = ConcurrentHashMap<String, Any>()

    /** 写入失败时的日志前缀。 */
    private const val TAG = "AtomicJsonFile"

    /** 备份文件后缀。与历史实现保持一致（`.bak`），老数据能直接被新读路径认到。 */
    const val BACKUP_SUFFIX = ".bak"

    /**
     * 原子写入。返回是否成功替换主文件。
     *
     * @param file      目标文件（父目录不存在会自动建）
     * @param payload   完整内容（调用方负责编码，本方法不碰 JSON 语义）
     * @param keepBackup 是否把上一版留为 `<file>.bak`（默认 true；高频小状态可关）
     * @param tmpSuffix 临时文件后缀。默认 `.tmp`；工作区写入用 `.akitmp`，
     *                 因为 `WorkspaceStore` 的文件列表按这个后缀跳过半成品
     */
    fun write(
        file: File,
        payload: String,
        keepBackup: Boolean = true,
        tmpSuffix: String = ".tmp",
    ): Boolean {
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val lock = locks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            try {
                writeLocked(file, payload, keepBackup, tmpSuffix)
            } catch (t: Throwable) {
                // 日志本身也可能失败（磁盘满时写日志同样写不进去），必须再包一层：
                // 否则「记录失败原因」这一步会把异常抛给调用方，而调用方拿到的语义
                // 本应是「这次写没成功」（false），不是崩溃。
                runCatching { warn("原子写失败（${file.name}）：${t.message?.take(120)}") }
                false
            }
        }
    }

    private fun writeLocked(file: File, payload: String, keepBackup: Boolean, tmpSuffix: String): Boolean {
        val dir = file.parentFile ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false

        // tmp 必须与目标**同目录**：跨文件系统的 rename 不是原子操作，会退化成拷贝
        val tmp = File(dir, file.name + tmpSuffix)

        // 1) 写 tmp 并落盘。fd.sync() 是这里唯一真正保证「数据先于目录项」的动作
        FileOutputStream(tmp).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }

        // 2) 备份上一版（rename 前做；rename 后主文件已经是新的，备份就没意义了）
        if (keepBackup && file.isFile) {
            val bak = File(dir, file.name + BACKUP_SUFFIX)
            runCatching {
                if (bak.exists()) bak.delete()
                file.copyTo(bak, overwrite = true)
            }
        }

        // 3) 替换。注意**不先 delete 主文件**：Linux 的 rename 覆盖是原子的，
        //    先删再 rename 会把「原子替换」降级成「先丢后写」，rename 一失败就没了
        if (tmp.renameTo(file)) return true

        // 4) 极少数文件系统（FAT/exFAT 外置存储）rename 失败：退化为直接覆盖，
        //    至少保证有一份可读数据，随后清掉 tmp 避免残留垃圾
        warn("rename 失败，退化为直接覆盖：${file.name}")
        val fallback = runCatching {
            FileOutputStream(file).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            true
        }.getOrDefault(false)
        runCatching { tmp.delete() }
        return fallback
    }

    /**
     * 读主文件；不存在或读失败返回 null。
     *
     * 不在这里判断「内容是否合法」——文本层面无从判断。合法性交给
     * [readWithBackupOrNull] 的 `isValid` 谓词。
     */
    fun readOrNull(file: File): String? =
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    /**
     * 读主文件，坏了就回退 `.bak`。
     *
     * 「坏」的定义交给调用方：JSON 存储传一个 `parseOrNull(it) != null`，
     * 文本存储传 `it.isNotBlank()`。这样本类不需要知道任何序列化格式。
     *
     * @param file    主文件
     * @param isValid 内容是否可用（注意：只应判断能否**完整使用**，不要判断业务是否最新）
     */
    fun readWithBackupOrNull(file: File, isValid: (String) -> Boolean): String? {
        val primary = readOrNull(file)
        if (primary != null && isValid(primary)) return primary

        val bak = File(file.parentFile, file.name + BACKUP_SUFFIX)
        val backup = readOrNull(bak)
        if (backup != null && isValid(backup)) {
            // 回退成功要留痕：这说明上次写入没走完，是排查「数据丢了」的第一现场。
            // 日志失败不能影响读路径的返回值——数据已经拿到了，只是没记下来
            runCatching { warn("主文件不可用，已回退备份：${file.name}") }
            return backup
        }
        return null
    }

    /** 备份文件路径（供调用方展示/清理）。 */
    fun backupOf(file: File): File = File(file.parentFile, file.name + BACKUP_SUFFIX)

    /**
     * 失败提示直接写 stderr，**不接 AgentLogger**。
     *
     * 不是图省事，是刻意的依赖取舍：`AgentLogger` → `ConfigSanitizer` → 供应商目录……
     * 接上它等于让「纯文件 IO 工具」拖进半个日志子系统，任何想单独编译本文件的
     * harness 都得把那串依赖一起带上——本项目已经有三个脚本因此编译失败过一次。
     *
     * Android 运行时会把 `System.err` 转发到 logcat（tag=`System.err`），
     * 所以真机上并不会丢：`adb logcat -s System.err` 就能看到。
     * 而「写失败」本身已经通过返回值告诉调用方了，这里只补一条原因。
     */
    private fun warn(msg: String) {
        runCatching { System.err.println("[$TAG] $msg") }
    }
}
