package com.beichen.polaris.core.agent.journal

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.telemetry.StoreWriteTrace
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 回合日志的落盘实现（规格 M2 / M3）。
 *
 * 与项目里另外 15 处「tmp + rename」的原子写不同，这里**不是**原子替换，而是**追加**：
 * 日志的价值在于"崩溃时已经写进去的部分"，替换语义反而会把前半段弄丢。
 *
 * 三条与现有写法不同的地方，都是有意的：
 *  1. **每次写完 fsync**（`fd.sync()`）。项目现有 15 处原子写没有一处做这步，
 *     断电时 rename 已生效但数据还在页缓存里，文件会变成 0 字节或半截。
 *     日志是恢复的唯一依据，这里不能省。代价是每次写多 3–10ms，一个回合二十来条。
 *  2. **坏行丢弃而非整文件报错**。崩溃必然产生半行，读取方要能容忍尾部的半条记录。
 *  3. **格式是自定义 TSV 不是 JSON**。日志是内部格式，不需要跨语言消费；
 *     每行一次 split 比解析 JSON 便宜，也没有转义地狱。代价是字段必须自己保证不含分隔符。
 *
 * 写入失败**不吞**：抛 [IOException]，由调用方决定降级（见规格 M3.1）。
 * 这个类不负责"写失败了怎么办"，只负责"写失败要让你知道"。
 *
 * @param dir 日志目录，通常是 `context.filesDir/run-journal`。
 */
class RunJournal(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 单回合日志文件。文件名即 turnId，便于按时间清理。 */
    fun fileOf(turnId: String): File = File(dir, "${sanitize(turnId)}.$EXT")

    /**
     * 打开一个回合的写入句柄。
     *
     * seq 从已有日志的最大值续上——同一回合被重新打开（如修复后继续）时不能重号。
     *
     * @throws IOException 目录不可写时立即失败，不要等到第一次 append 才炸。
     */
    fun open(turnId: String): Writer {
        val file = fileOf(turnId)
        if (!dir.canWrite()) throw IOException("日志目录不可写：${dir.absolutePath}")
        val lastSeq = if (file.exists()) load(turnId).lastOrNull()?.seq ?: 0L else 0L
        return Writer(file, turnId, lastSeq)
    }

    /**
     * 读出某个回合的全部事件。
     *
     * 坏行（崩溃产生的半条）直接跳过，不抛异常——恢复流程的前提就是"文件可能不完整"。
     * 跳过的行数记一条 WARN，用于诊断导出时判断是"偶发截断"还是"磁盘在坏"。
     */
    fun load(turnId: String): List<RunEvent> {
        val file = fileOf(turnId)
        if (!file.exists()) return emptyList()
        val out = ArrayList<RunEvent>()
        var skipped = 0
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val event = RunEventCodec.decode(line)
            if (event == null) skipped++ else out.add(event)
        }
        if (skipped > 0) {
            AgentLogger.w(TAG, "日志 $turnId 有 $skipped 行无法解析（崩溃截断），已跳过")
        }
        return out.sortedBy { it.seq }
    }

    /**
     * 启动时扫描未闭合的回合，**并把补全事件写回文件**。
     *
     * 只扫最近 [lookback] 个文件：更早的日志即使未闭合，也早已被后续回合覆盖了上下文，
     * 补它只会让模型看到一段莫名其妙的"上一次执行"。
     *
     * 写回这一步不能省：修复器是幂等的（已平衡的日志返回空），但它不碰 IO，
     * 光算不写的话，文件每晚都"未闭合"一次——诊断里永远有一条假告警，
     * 而且下次启动会重新解析同一份半截日志。
     *
     * @return 每个需要修复的回合一项，按文件修改时间从新到旧。
     */
    fun scanUnclosed(lookback: Int = 3): List<RunJournalRepair.RepairResult> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".$EXT") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
            .take(lookback)
            .mapNotNull { f ->
                val turnId = f.name.removeSuffix(".$EXT")
                val result = RunJournalRepair.repair(load(turnId))
                if (!result.repaired) return@mapNotNull null
                // seq 起点接在最后一条真实事件之后（closers 已按此排好），appendAll 不改写序号。
                val startSeq = (result.closers.first().seq - 1).coerceAtLeast(0L)
                runCatching { Writer(f, turnId, startSeq).appendAll(result.closers) }
                    .onFailure { AgentLogger.w(TAG, "回合 $turnId 修复事件写回失败：${it.message}") }
                // P5-1：度量表「崩溃后写操作重放次数」的唯一数据来源。
                // 记的是**补回的事件数**与其中「可能已生效」的调用数——后者非零才说明
                // 真的有一次副作用被这条纪律救回来了（或需要用户核对）。
                StoreWriteTrace.repair(
                    store = "journal",
                    restored = result.closers.size,
                    note = "turn=$turnId odd=${result.orphans.size} " +
                        "mayEffect=${result.orphans.count { it.mayHaveTakenEffect }}"
                )
                result
            }
    }

    /** 只保留最近 [keep] 个回合的日志文件，其余删除。回合结束时调用。 */
    fun prune(keep: Int = 20) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".$EXT") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    /**
     * 导出某个回合的事件为 NDJSON（R7）：一行一个 JSON 对象。
     *
     * 存储格式仍是 TSV —— 崩溃恢复走的是「同进程反复读回」这条路，
     * split 比解析 JSON 便宜。NDJSON 是**导出格式**，给跨工具消费用：
     * 诊断打包、日志分析、跨设备对照，都想要「一行一个自解释对象」。
     *
     * @return NDJSON 文本；回合不存在时返回空串。
     */
    fun exportNdjson(turnId: String): String {
        val events = load(turnId)
        if (events.isEmpty()) return ""
        return buildString {
            events.forEach { append(RunEventJson.encode(it)).append('\n') }
        }
    }

    /**
     * 导出全部回合为 NDJSON（R7），按文件修改时间**从旧到新**排列。
     *
     * 排序方向与 [prune] 相反：prune 关心「谁最新」，导出关心「事件的时间顺序」。
     * 跨回合对照时，顺序错乱比缺字段更误导人。
     */
    fun exportAllNdjson(): String {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".$EXT") } ?: return ""
        val builder = StringBuilder()
        files.sortedBy { it.lastModified() }.forEach { f ->
            // 复用 [exportNdjson]：编码只有一处实现，两份输出不可能分叉。
            //
            // 修前这里另抄了一遍 `load + RunEventJson.encode`，是同一件事的两份代码。
            // 这种重复不会立刻出错，但改格式时只要漏掉一边，就会得到「单回合导出有
            // 某个字段、全量导出没有」——而两份文件都叫 NDJSON，肉眼比对时最容易被
            // 当成「本来就是这样的」放过去。
            builder.append(exportNdjson(f.name.removeSuffix(".$EXT")))
        }
        return builder.toString()
    }

    /**
     * 单个回合的写入句柄。
     *
     * 非线程安全：一个回合只应有一个写入者。跨回合并发不冲突（不同文件）。
     *
     * R7：写入器带**作用域**（[RunScope]），同一文件可以有多个作用域的写入器
     * （主代理 / 技能脚本 / 子代理）。它们**共享同一个序号计数器**——
     * 序号必须全局唯一，否则恢复器按 seq 排序时会把两个作用域的事件交错错乱。
     */
    inner class Writer internal constructor(
        private val file: File,
        private val turnId: String,
        startSeq: Long,
        /** 本写入器产生的事件的作用域（R7）。 */
        val scope: String = RunScope.MAIN,
        /** 共享序号状态（R7）：派生写入器与本写入器共用，保证同一文件内 seq 唯一。 */
        private val seqState: java.util.concurrent.atomic.AtomicLong =
            java.util.concurrent.atomic.AtomicLong(startSeq)
    ) {
        /**
         * 派生一个同文件、不同作用域的写入器。
         *
         * 共享序号 → 两个作用域的事件在文件里仍是一条单调递增的序列，
         * 读回时按 seq 排序即可还原真实先后顺序。
         */
        fun scoped(scope: String): Writer =
            Writer(file, turnId, seqState.get(), scope, seqState)

        /** 当前已分配的最大序号，调用方拼装后续事件时用。 */
        val lastSeq: Long get() = seqState.get()

        /**
         * 追加一条事件并落盘。
         *
         * @param atMs 事件时间，由调用方注入（测试可控，不依赖系统时钟）。
         * @throws IOException 写盘或 fsync 失败——**故意不吞**，见类注释。
         */
        fun append(build: (seq: Long, atMs: Long) -> RunEvent, atMs: Long): RunEvent {
            val event = build(seqState.incrementAndGet(), atMs)
            val line = RunEventCodec.encode(event)
            FileOutputStream(file, true).use { fos ->
                fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            return event
        }

        // —— 六个便捷方法，避免调用点反复写 build lambda ——

        fun turnOpened(atMs: Long): RunEvent =
            append({ s, t -> RunEvent.TurnOpened(s, t, turnId, scope) }, atMs)

        fun assistantCommitted(atMs: Long, step: Int, calls: List<ToolCallRef>): RunEvent =
            append({ s, t -> RunEvent.AssistantCommitted(s, t, turnId, step, calls, scope) }, atMs)

        fun toolDispatched(atMs: Long, step: Int, callId: String): RunEvent =
            append({ s, t -> RunEvent.ToolDispatched(s, t, turnId, step, callId, scope) }, atMs)

        fun toolSettled(
            atMs: Long,
            step: Int,
            callId: String,
            status: ToolSettleStatus,
            errorCode: String? = null
        ): RunEvent = append({ s, t ->
            RunEvent.ToolSettled(s, t, turnId, step, callId, status, errorCode, scope)
        }, atMs)

        fun stepClosed(atMs: Long, step: Int): RunEvent =
            append({ s, t -> RunEvent.StepClosed(s, t, turnId, step, scope) }, atMs)

        fun turnClosed(atMs: Long, reason: String): RunEvent =
            append({ s, t -> RunEvent.TurnClosed(s, t, turnId, reason, scope) }, atMs)

        /**
         * 批量追加**已经算好 seq** 的事件（修复器合成的补全事件走这条）。
         *
         * 一次 `open/write/flush/sync` 落完：修复一个回合通常要补 3–5 条，
         * 逐条 fsync 会让启动多花几十毫秒，而这些事件本来就是同时产生的。
         */
        fun appendAll(events: List<RunEvent>) {
            if (events.isEmpty()) return
            val sb = StringBuilder()
            events.forEach { sb.append(RunEventCodec.encode(it)).append('\n') }
            FileOutputStream(file, true).use { fos ->
                fos.write(sb.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val maxSeq = events.maxOf { it.seq }
            seqState.updateAndGet { current -> maxOf(current, maxSeq) }
        }
    }

    companion object {
        const val EXT = "rjl"
        private const val TAG = "RunJournal"

        /** turnId 来自 UUID，理论上安全；仍净化一次，避免路径穿越（`../`）。 */
        internal fun sanitize(id: String): String =
            id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    }
}

/**
 * 日志行编解码。
 *
 * ## 格式版本
 *
 * - `v1`：`v1 \t seq \t atMs \t turnId \t TYPE \t 字段…`
 * - `v2`：`v2 \t seq \t atMs \t turnId \t scope \t TYPE \t 字段…`（R7：增加作用域列）
 *
 * v2 只在 v1 的头部多插一列 `scope`，其余字段位置不变——所以读的时候把
 * 版本头剥掉，两种格式的 TYPE 与字段是同一套相对布局，读侧代码不用分叉。
 *
 * v1 文件（改造前产生）必须仍能读：崩溃恢复的依据是历史日志，
 * 读不懂等于让旧设备上的未闭合回合永远补不上尾。scope 缺省为 [RunScope.MAIN]。
 *
 * 行内字段一律净化掉 `\t` 与 `\n`，写入侧保证，读取侧不再做容错解析。
 */
internal object RunEventCodec {

    private const val VERSION_V1 = "v1"
    private const val VERSION_V2 = "v2"
    private const val SEP = "\t"

    fun encode(event: RunEvent): String {
        val head = "$VERSION_V2$SEP${event.seq}$SEP${event.atMs}$SEP${clean(event.turnId)}" +
            "$SEP${clean(event.scope)}"
        return when (event) {
            is RunEvent.TurnOpened -> "$head${SEP}OPEN"
            is RunEvent.TurnClosed -> "$head${SEP}CLOSE${SEP}${clean(event.reason)}"
            is RunEvent.StepClosed -> "$head${SEP}STEP${SEP}${event.step}"
            is RunEvent.ToolDispatched -> "$head${SEP}DISP${SEP}${event.step}${SEP}${clean(event.callId)}"
            is RunEvent.ToolSettled -> buildString {
                append(head).append(SEP).append("SETTLE").append(SEP).append(event.step)
                append(SEP).append(clean(event.callId))
                append(SEP).append(event.status.name)
                append(SEP).append(clean(event.errorCode ?: ""))
            }
            is RunEvent.AssistantCommitted -> buildString {
                append(head).append(SEP).append("ASSIST").append(SEP).append(event.step)
                append(SEP).append(
                    event.calls.joinToString(",") { "${clean(it.name)}:${clean(it.id)}" }
                )
            }
        }
    }

    /** 解析失败返回 null（坏行），不抛——读取方按"丢弃"处理。 */
    fun decode(line: String): RunEvent? {
        val f = line.split(SEP)
        if (f.size < 5) return null
        // 剥掉版本头：rest[0] 是 TYPE，其后是各事件自己的字段（v1/v2 布局一致）。
        val scope: String
        val rest: List<String>
        when (f[0]) {
            VERSION_V1 -> {
                scope = RunScope.MAIN
                rest = f.subList(4, f.size)
            }
            VERSION_V2 -> {
                if (f.size < 6) return null
                scope = f[4]
                rest = f.subList(5, f.size)
            }
            else -> return null
        }
        val seq = f[1].toLongOrNull() ?: return null
        val atMs = f[2].toLongOrNull() ?: return null
        val turnId = f[3]
        return try {
            when (rest[0]) {
                "OPEN" -> RunEvent.TurnOpened(seq, atMs, turnId, scope)
                "CLOSE" -> RunEvent.TurnClosed(seq, atMs, turnId, rest[1], scope)
                "STEP" -> RunEvent.StepClosed(seq, atMs, turnId, rest[1].toInt(), scope)
                "DISP" -> RunEvent.ToolDispatched(seq, atMs, turnId, rest[1].toInt(), rest[2], scope)
                "SETTLE" -> RunEvent.ToolSettled(
                    seq, atMs, turnId, rest[1].toInt(), rest[2],
                    ToolSettleStatus.valueOf(rest[3]),
                    rest[4].ifEmpty { null },
                    scope
                )
                "ASSIST" -> RunEvent.AssistantCommitted(
                    seq, atMs, turnId, rest[1].toInt(), decodeCalls(rest[2]), scope
                )
                else -> null
            }
        } catch (e: Exception) {
            // 字段数不足 / 枚举值不认识 / 数字越界，一律当坏行。
            null
        }
    }

    private fun decodeCalls(raw: String): List<ToolCallRef> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { pair ->
            val i = pair.lastIndexOf(':')
            if (i <= 0) null else ToolCallRef(pair.substring(i + 1), pair.substring(0, i))
        }
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
