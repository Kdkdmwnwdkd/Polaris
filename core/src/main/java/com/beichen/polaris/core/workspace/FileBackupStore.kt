package com.beichen.polaris.core.workspace

import com.beichen.polaris.core.io.AtomicJsonFile
import java.io.File

/**
 * 单文件版本备份（B 批）：Agent 每次写文件前，自动把**将要被覆盖的那一个文件**的旧内容留一份。
 *
 * ## 为什么不用 CheckpointStore
 * [CheckpointStore] 是整目录快照——语义是「我手动点了个检查点」，用于人工回滚。
 * 但 Agent 的写路径是自动的、连续的、高频的：`write_file` / `edit_file` 可能一秒钟
 * 调用十几次。整目录快照每次都要复制整个工作区，开销和磁盘占用都不可接受。
 *
 * 所以这里做**粒度相反**的版本：只备份「本次要动的那个文件」。
 * - 写新文件 → 记录「此前不存在」，回滚即删除该文件；
 * - 改旧文件 → 记录旧内容，回滚即写回旧内容。
 *
 * 这是「编辑自动 checkpoint」的形态：**每次改动都有一个
 * 可撤销的落点**，而不是让用户自己记得手动拍快照。
 *
 * ## 目录布局
 * ```
 * <filesDir>/file_backups/
 *   ├── <id>.meta            # id / relPath / 时间 / 是否原本存在 / 字节数
 *   └── <id>/                # 旧内容副本（文件名与 relPath 一致，保留子目录结构）
 * ```
 *
 * 纯文件操作、无 Android 依赖，可被 JVM harness 直接断言。
 */
class FileBackupStore(
    private val root: File,
    private val workspace: File,
    /** 错误回调（默认吞掉——备份是兜底，失败不该影响写入）。 */
    private val onError: (String) -> Unit = {},
    /**
     * 保留条数上限：超出后自动清理最旧的。
     *
     * 为什么做成构造参数：原来是 `prune(keep = 50)` 的默认值，但**全仓库没有任何地方调用它**
     * ——备份从写入那天起就没被清理过，一直无限增长。用户如果频繁让 Agent 改文件，
     * 备份目录能涨到几百 MB 且全是垃圾。
     *
     * 现在由 [FileBackupStoreFactory] 从设置读（默认 50），并在写入路径上定期触发。
     */
    private val keep: Int = DEFAULT_KEEP
) {

    /**
     * 一次备份的元信息。
     *
     * @param seq 同毫秒内的递增序号，用于在时间戳相同的备份之间**稳定**排序。
     *   没有它，[list] 对同毫秒备份的先后顺序就是不确定的，而 [restoreFile]
     *   恰恰依赖严格的「从新到旧」顺序（见该函数的说明）。
     */
    data class Backup(
        val id: String,
        val relPath: String,
        val timeMs: Long,
        val existedBefore: Boolean,
        val sizeBytes: Long,
        val seq: Int = 0
    )

    /** 进程内序号状态：[nextSeq] 的落点。见 companion object 中的全局计数。 */

    /** 距上次清理已写入的次数（见 [backupBeforeWrite] 末尾的定期清理）。 */
    private var writesSincePrune = 0

    init {
        runCatching { root.mkdirs() }
    }

    /**
     * 在写入 [relPath] 之前调用：备份该文件的当前内容。
     *
     * 幂等性：同一文件连续被写多次会产生多条备份（历史链条完整）。
     * 回滚时按时间倒序取最近一条即可回到「上一步」的状态。
     *
     * @param relPath 相对工作区的路径（与 write_file / edit_file 的 path 参数同源）
     * @return 新建的备份 id；失败返回 null（**不阻断写入**——备份是兜底，不是前置条件）
     */
    fun backupBeforeWrite(relPath: String): String? = runCatching {
        val safeRel = sanitizeRel(relPath) ?: return null
        val target = File(workspace, safeRel)
        // 目标逃出工作区 → 不备份（也不该发生，resolve() 已经拦过）
        if (!target.canonicalPath.startsWith(workspace.canonicalPath + File.separator)) return null

        val ts = System.currentTimeMillis()
        // id 必须**唯一**。
        //
        // 早期实现只用「时间戳 + 相对路径」，但同一毫秒内对同一文件的连续写入
        // （Agent 批量改文件时很常见）会算出**完全相同的 id**——后一条备份的 meta
        // 直接覆盖前一条，旧内容副本也被写进同一个目录，于是「改了三次只剩一条备份」，
        // 批量回滚退不干净、而且是静默的（少了一条备份，但界面看不出异常）。
        //
        // 加一个单调递增的序号兜底：同一毫秒内的多次备份也不会撞。
        val seq = nextSeq(ts)
        val id = "${ts}_${seq}_${safeRel.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}"
        val existed = target.isFile
        val size = if (existed) target.length() else 0L

        if (existed) {
            // 旧内容存进 <id>/ 目录，保持相对路径结构，避免同名文件互相覆盖
            val backupFile = File(File(root, id), safeRel)
            backupFile.parentFile?.mkdirs()
            target.copyTo(backupFile, overwrite = true)
        } else {
            File(root, id).mkdirs()
        }

        // 原子写：meta 写坏 = [list] 里少一条备份，而备份文件本身还在，
        // 用户看到的是「这条改动的备份没了」。备份是回滚的唯一依据，不能靠运气。
        AtomicJsonFile.write(
            File(root, "$id.meta"),
            buildString {
                appendLine("relPath=$safeRel")
                appendLine("timeMs=$ts")
                appendLine("seq=$seq")
                appendLine("existedBefore=$existed")
                appendLine("sizeBytes=$size")
            },
            keepBackup = false
        )
        id
    }.onFailure { onError("备份失败（不阻断写入）：${it.message}") }.getOrNull()
        .also {
            // 定期清理：每 [PRUNE_EVERY] 次写入扫一次目录。
            //
            // 不每次 prune 的原因：prune 要 listFiles 遍历 + 读每个 .meta，单次几十条还行，
            // 但 Agent 连续改文件时一秒能写十几次，每次都扫就是白白拖慢写入路径。
            // 每 20 次清理一次，最坏情况多留 19 条，完全可以接受。
            if (it != null && ++writesSincePrune >= PRUNE_EVERY) {
                writesSincePrune = 0
                runCatching { prune(keep) }
            }
        }

    /**
     * 回滚一次备份：把该备份记录的旧内容写回工作区。
     *
     * 备份时文件原本不存在 → 回滚即**删除**当前文件（撤销「新建」）。
     *
     * @return 成功返回 true
     */
    fun restore(id: String): Boolean = runCatching {
        val meta = File(root, "$id.meta")
        if (!meta.isFile) return false
        val props = meta.readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        val relPath = props["relPath"] ?: return false
        val existed = props["existedBefore"]?.toBooleanStrictOrNull() ?: return false
        val target = File(workspace, relPath)
        if (!target.canonicalPath.startsWith(workspace.canonicalPath + File.separator)) return false

        if (!existed) {
            // 原本不存在 → 撤销创建
            if (target.exists()) target.delete()
            return@runCatching true
        }
        val src = File(File(root, id), relPath)
        if (!src.isFile) return@runCatching false
        target.parentFile?.mkdirs()
        src.copyTo(target, overwrite = true)
        true
    }.getOrDefault(false)

    /** 列出全部备份，按时间倒序（最近的在最前）。 */
    fun list(): List<Backup> {
        val metas = root.listFiles { f -> f.name.endsWith(".meta") } ?: return emptyList()
        return metas.mapNotNull { mf ->
            runCatching {
                val id = mf.name.removeSuffix(".meta")
                val props = mf.readLines()
                    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0] to it[1] }
                Backup(
                    id = id,
                    relPath = props["relPath"] ?: "",
                    timeMs = props["timeMs"]?.toLongOrNull() ?: 0L,
                    existedBefore = props["existedBefore"]?.toBooleanStrictOrNull() ?: false,
                    sizeBytes = props["sizeBytes"]?.toLongOrNull() ?: 0L,
                    seq = props["seq"]?.toIntOrNull() ?: 0
                )
            }.getOrNull()
        }.filter { it.relPath.isNotEmpty() }
            // 复合排序：先时间倒序，时间相同再按 seq 倒序。
            //
            // 只按 timeMs 排是不够的——同一毫秒内的多条备份（Agent 连续改同一个文件
            // 很常见）时间戳完全相同，而 Kotlin 的 sortedBy 是**稳定**排序，
            // 相同 key 会保留 listFiles() 的原始顺序，而那个顺序是文件系统决定的、
            // 完全不确定的。于是同毫秒备份的「谁更新」就随机了，而 [restoreFile]
            // 要求严格倒序恢复，顺序一乱就会退不干净。
            .sortedWith(compareByDescending<Backup> { it.timeMs }.thenByDescending { it.seq })
    }

    /** 某文件的最近一条备份（回滚「上一步」用）。 */
    fun latestFor(relPath: String): Backup? =
        list().firstOrNull { it.relPath == relPath }

    /**
     * 同一文件的多个备份归为一组（C 批）。
     *
     * Agent 改一个文件常常连改好几轮，备份就是好几条。用户视角关心的是
     * 「这个文件被我改了几次、能一次性退回去吗」，而不是一长串平铺的条目。
     *
     * 组内按时间**倒序**（最近的在最前），与 [list] 的全局顺序一致。
     */
    data class FileGroup(
        val relPath: String,
        /** 该文件的全部备份，按时间倒序。 */
        val backups: List<Backup>
    ) {
        /** 最近一次改动的时间。 */
        val latestTimeMs: Long get() = backups.firstOrNull()?.timeMs ?: 0L

        /** 最近一次改动的序号（与 [latestTimeMs] 一起用于组的稳定排序）。 */
        val latestSeq: Int get() = backups.firstOrNull()?.seq ?: 0

        /** 改动次数（= 备份条数）。 */
        val changeCount: Int get() = backups.size
    }

    /**
     * 按文件分组列出备份，组间按最近改动时间倒序。
     *
     * 纯函数性质：同一 relPath 的所有备份必然聚到同一组。
     */
    fun grouped(): List<FileGroup> {
        val byPath = list()
            .groupBy { it.relPath }
            // 组内沿用 list() 已经排好的顺序（时间倒序 + seq 倒序）。
            // groupBy 保序，所以这里无需再排一次——再排反而可能丢掉 seq 的次序。
        return byPath.entries
            .map { (path, bs) -> FileGroup(path, bs) }
            .sortedWith(compareByDescending<FileGroup> { it.latestTimeMs }.thenByDescending { it.latestSeq })
    }

    /**
     * 批量回滚某文件的**全部**备份，把该文件退回到 Agent 第一次动它之前的状态。
     *
     * 关键：**必须按时间倒序逐条 restore**。
     *
     * 设文件原内容为 C0，Agent 依次写了 C1、C2、C3，对应备份 b1(before=C0)、
     * b2(before=C1)、b3(before=C2)。若正序恢复（b1→b2→b3），最终落盘的是 C2，
     * **退不干净**；倒序恢复（b3→b2→b1）最终落盘 C0，才真正「退到没动过」。
     *
     * @return 实际恢复的条数；0 表示该文件没有可回滚的备份
     */
    fun restoreFile(relPath: String): Int {
        val targets = list().filter { it.relPath == relPath }
        if (targets.isEmpty()) return 0
        // list() 已按时间倒序，天然满足「从新到旧」逐条恢复
        var n = 0
        targets.forEach { b ->
            if (restore(b.id)) n++
        }
        return n
    }

    /**
     * 批量回滚**所有**文件的全部备份（一次性把工作区退回到 Agent 动手之前）。
     *
     * 每个文件内部各自倒序恢复；文件之间互不影响（不同 relPath）。
     *
     * @return 实际恢复的条数
     */
    fun restoreAll(): Int {
        var n = 0
        grouped().forEach { g -> n += restoreFile(g.relPath) }
        return n
    }

    /**
     * 一次备份与**当前文件**的差异（D 批 · 备份差异视图）。
     *
     * 语义：备份记录的是「这次写入**之前**的内容」，所以
     * `diff(备份内容 → 当前内容)` 展示的正是 **Agent 那一次改了什么**。
     *
     * @return null 表示备份不存在或读不出旧内容（新建文件的备份没有旧内容，
     *   这时返回一个「全部新增」的 diff 更贴切——见下面的处理）
     */
    data class BackupDiff(
        val id: String,
        val relPath: String,
        val lines: List<TextDiff.Line>,
        val stats: TextDiff.Stats,
        /** 当前文件已不存在（Agent 后来删掉了） */
        val currentMissing: Boolean
    )

    fun diffToCurrent(id: String): BackupDiff? = runCatching {
        val meta = File(root, "$id.meta")
        if (!meta.isFile) return null
        val props = meta.readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        val relPath = props["relPath"] ?: return null
        val existed = props["existedBefore"]?.toBooleanStrictOrNull() ?: return null

        // 备份时文件原本不存在（新建）→ 旧内容视为空
        val before = if (!existed) "" else {
            val src = File(File(root, id), relPath)
            if (!src.isFile) return null
            runCatching { src.readText() }.getOrNull() ?: return null
        }
        val target = File(workspace, relPath)
        val missing = !target.isFile
        val after = if (missing) "" else runCatching { target.readText() }.getOrDefault("")

        val lines = TextDiff.lines(before, after)
        BackupDiff(id, relPath, lines, TextDiff.stats(before, after), missing)
    }.getOrNull()

    /** 删除一条备份。 */
    fun delete(id: String): Boolean = runCatching {
        File(root, id).deleteRecursively()
        File(root, "$id.meta").delete()
        true
    }.getOrDefault(false)

    /**
     * 清理超出上限的旧备份（保留最近 [keep] 条）。
     *
     * @param keep ≤ 0 表示**不清理**（用户明确要全留）。这里必须显式挡住，
     *   否则 `all.size <= 0` 恒不成立，会把所有备份删光——一个参数写错就清空全部历史。
     */
    fun prune(keep: Int = DEFAULT_KEEP): Int {
        if (keep <= 0) return 0
        val all = list()
        if (all.size <= keep) return 0
        var removed = 0
        all.drop(keep).forEach {
            if (delete(it.id)) removed++
        }
        return removed
    }

    /**
     * 相对路径安全化：拒绝绝对路径与 .. 穿越，返回 null 表示非法。 */
    private fun sanitizeRel(rel: String): String? {
        val t = rel.trim().replace('\\', '/')
        if (t.isEmpty()) return null
        if (t.startsWith("/")) return null
        val parts = t.split("/")
        if (parts.any { it == ".." }) return null
        return t
    }

    /**
     * 生成同一毫秒内的单调递增序号。
     *
     * 备份 id 里必须带一个不会重复的序号，否则同一毫秒内对同一文件的连续写入
     * 会算出完全相同的 id——后一条备份的 meta 直接覆盖前一条，旧内容副本也被写进
     * 同一个目录，于是「改了三次只剩一条备份」，批量回滚退不干净，**而且是静默的**
     * （界面上只是少了一条备份，看不出任何异常）。
     *
     * 规则：
     * - 时间戳前进 → 序号重置为 1（新的一毫秒从 1 开始）；
     * - 时间戳不变 → 序号 +1；
     * - 时间戳**倒退**（系统时钟被调整）→ 序号继续 +1，绝不复用。
     *
     * 第三条很关键：只按「相等就加一」判断，一旦时钟回拨就可能算出和历史重复的组合。
     */
    private fun nextSeq(ts: Long): Int = synchronized(SEQ_LOCK) {
        if (ts > lastSeqTs) {
            lastSeqTs = ts
            lastSeqValue = 1
        } else {
            lastSeqValue += 1
        }
        lastSeqValue
    }

    companion object {
        /** 默认目录名（备份仓落位）。 */
        const val DIR_NAME = "file_backups"

        /** 默认保留条数。 */
        const val DEFAULT_KEEP = 50

        /** 每写多少次触发一次清理（见 [backupBeforeWrite]）。 */
        private const val PRUNE_EVERY = 20

        /**
         * 全局序号状态。
         *
         * 放在 companion 里而不是实例字段里：备份目录可能被**多个 FileBackupStore
         * 实例**指向同一片磁盘（工厂缓存、界面重建、测试里另起实例都会发生），
         * 若每个实例各持一份计数器，两个实例在同一毫秒内各算一次 seq=1，
         * 照样会撞出相同的 id。放到全局就彻底堵死这条路。
         */
        private val SEQ_LOCK = Any()
        private var lastSeqTs = 0L
        private var lastSeqValue = 0
    }
}
