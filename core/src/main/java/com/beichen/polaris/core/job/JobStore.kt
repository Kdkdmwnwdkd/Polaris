package com.beichen.polaris.core.job

import com.beichen.polaris.core.io.AtomicJsonFile
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 后台任务的文件存储（W-33）：每个任务一个 `<id>.json` + `<id>.log`。
 *
 * 为什么用文件而不是内存：任务可能跑十几分钟，期间 App 可能被切后台甚至被杀；
 * 落盘后重启还能看到「这个任务的输出在哪、结果是什么」。
 */
class JobStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        runCatching { dir.mkdirs() }
    }

    /** 全部任务，按开始时间倒序（最新在前）。 */
    fun list(): List<JobInfo> =
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.mapNotNull { readFile(it) }
                ?.sortedByDescending { it.startedAt }
                ?: emptyList()
        }.getOrDefault(emptyList())

    fun get(id: String): JobInfo? = readFile(File(dir, "$id.json"))

    fun save(job: JobInfo) {
        // 不保留 .bak：任务目录里每个 id 一个文件，旧版任务记录价值低，
        // 而目录里多出一倍的 .bak 会让 listFiles 的扫描成本白白翻倍。
        AtomicJsonFile.write(File(dir, "${job.id}.json"), json.encodeToString(job), keepBackup = false)
    }

    /** 日志文件（可能还不存在）。 */
    fun logFile(id: String): File = File(dir, "$id.log")

    fun logText(id: String, maxBytes: Long = JobLogic.MAX_LOG_BYTES): String =
        runCatching {
            val f = logFile(id)
            if (!f.isFile) return@runCatching ""
            // 大日志只读尾部：读全量再截断会把几 MB 读进内存
            val length = f.length()
            if (length <= maxBytes) f.readText()
            else {
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(length - maxBytes)
                    val buf = ByteArray(maxBytes.toInt())
                    raf.readFully(buf)
                    "…（日志过大，只显示最后 ${maxBytes / 1024} KB）\n" +
                        String(buf, Charsets.UTF_8)
                }
            }
        }.getOrDefault("")

    /**
     * 启动时对账：把"标记运行中但不可能还在跑"的任务改成 interrupted。
     *
     * @param staleBeforeMs 早于此时刻开始的 running 任务视为中断
     * @return 被改写的任务
     */
    fun reconcile(staleBeforeMs: Long): List<JobInfo> {
        val fixed = ArrayList<JobInfo>()
        for (job in list()) {
            val updated = JobLogic.reconcileStatus(job, staleBeforeMs)
            if (updated != job) {
                save(updated)
                fixed += updated
            }
        }
        return fixed
    }

    /** 清理：删掉超过 [keep] 个的旧任务（含日志），防无限增长。 */
    fun prune(keep: Int = 50) {
        runCatching {
            list().drop(keep).forEach { job ->
                File(dir, "${job.id}.json").delete()
                logFile(job.id).delete()
            }
        }
    }

    private fun readFile(file: File): JobInfo? =
        runCatching {
            if (!file.isFile) null else json.decodeFromString<JobInfo>(file.readText())
        }.getOrNull()
}
