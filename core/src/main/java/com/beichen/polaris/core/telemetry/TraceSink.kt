package com.beichen.polaris.core.telemetry

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 机器可读的遥测流水（NDJSON）。
 *
 * ## 与 `AgentLogger` 的分工（别合并）
 *
 * | | `AgentLogger` | `TraceSink` |
 * |---|---|---|
 * | 读者 | 人 | 脚本 |
 * | 一行是什么 | 给人看的句子 | 一个 JSON 对象 |
 * | 格式稳定性 | 随便改（改文案是常态） | **字段名与顺序是契约** |
 * | 保留期 | 7 天轮转 | 同（复用同一目录约定） |
 *
 * 合并的后果是可见的：`AgentLogger` 的 `TURN ...` 行今天长这样，改一次措辞
 * 所有解析它的脚本就断一次。所以这里刻意不复用它的格式化逻辑，只复用目录。
 *
 * ## 为什么度量表里三项指标至今是「未知」
 *
 * 「确定性错误平均白等」「每回合会话写入量」「崩溃后写操作重放次数」这三项，
 * 都需要**对同一批事实反复做算术**。从人读日志里算不出来不是因为日志不全，
 * 是因为字段不稳定——`dur=6003` 这种写法换个事件就变成 `duration=6003ms`，
 * 脚本得为每个事件写一个正则。流水把这件事一次做对。
 *
 * ## 纪律
 *
 * - **绝不阻塞调用点**：`emit` 只入队，写盘在单线程上做。观察者在并行 `async`
 *   与 `NonCancellable` 收尾里被调用，任何一次同步 IO 都会把尾巴拖长。
 * - **绝不抛**：观测不能有副作用能力。队列满时丢弃并计数，日志里只报一次。
 * - **绝不记录敏感内容**：调用方负责（见 [TurnEvent] 的载荷纪律与 [sanitizeValue]）。
 * - **未 init 即 no-op**：harness 与 JVM 单测不 init，就不产生任何文件。
 */
object TraceSink {

    /** 单个流水文件的大小上限。超过即轮转（`turns-<stamp>.1.ndjson`）。 */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** 队列容量：够吸收一整批并行工具事件的突发，又不会在进程将死时占太多内存。 */
    private const val QUEUE_CAPACITY = 4096

    /** 单行上限。超长行（比如被误塞进来的完整工具输出）截断，否则一条就能撑破文件。 */
    private const val MAX_LINE_CHARS = 4096

    /** 当前落盘目标；null = 未初始化 = 全部丢弃。 */
    @Volatile
    private var dir: File? = null

    /** 丢弃计数（队列满 / 写盘失败）。只在首次丢弃时打一条 WARN，避免刷屏。 */
    @Volatile
    private var dropped: Long = 0L

    @Volatile
    private var warned = false

    /** 已写入行数（供诊断页与 harness 读取；跨轮次不清零）。 */
    @Volatile
    var written: Long = 0L
        private set

    @Volatile
    private var queue: ArrayBlockingQueue<String>? = null

    @Volatile
    private var worker: Thread? = null

    /**
     * 初始化（幂等）。[dir] 不可写时静默降级为 no-op——观测失败不该阻止启动。
     *
     * 与 `AgentLogger.init` 同一个目录调用即可（`filesDir/logs`）。
     */
    fun init(dir: File) {
        if (this.dir?.absolutePath == dir.absolutePath) return
        synchronized(this) {
            if (this.dir?.absolutePath == dir.absolutePath) return
            // 先探一次可写性：目录存在但只读（存储满 / 权限异常）时早点退化，
            // 别等到第一条事件才发现写不进去。
            val usable = runCatching {
                if (!dir.exists()) dir.mkdirs()
                val probe = File(dir, ".trace-probe")
                probe.writeText("")
                probe.delete()
                true
            }.getOrDefault(false)
            if (!usable) {
                this.dir = null
                return
            }
            this.dir = dir
            dropped = 0
            warned = false
            val q = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
            queue = q
            worker = Thread({ drainLoop(q) }, "trace-sink").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    /**
     * 记一行。调用方传**已序列化**的 JSON（不含换行）。
     *
     * 为什么不让这里做序列化：不同事件族的字段完全不同（回合事件有 step，
     * 存储事件没有），把序列化留在各自的 `toNdjson()` 里，才不用在核心埋一个
     * 万能 Map——那种写法三个月后没人知道哪个字段还有人在读。
     */
    fun emit(line: String) {
        val q = queue ?: return
        val safe = if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…\"}"
        if (!q.offer(safe)) noteDropped()
    }

    /**
     * 把队列里的内容写下去（导出诊断包 / App 切后台时调用）。
     *
     * 等待 [settleMs] 后返回：worker 每 400ms 或攒够 32KB 落一次盘，等过一个完整窗口
     * 就一定能覆盖「此刻队列里的最后一条」。
     *
     * 为什么不用栅栏（往队列塞一个哨兵、等 worker 应答）：那需要跨线程信号 + 超时处理，
     * 而这个函数一天最多被调用几次、每次调用方都在等一次导出。用固定的一个窗口换掉
     * 一整个同步原语，是这里最划算的取舍——但它**只适用于导出**，别拿它当通用的
     * 「写完了吗」判据。
     */
    fun flush(settleMs: Long = 450) {
        if (queue == null) return
        runCatching { Thread.sleep(settleMs) }
    }

    /** 停掉（仅测试用）：把队列排空后终止线程，并清空目标，回到 no-op 状态。 */
    internal fun shutdownForTest() {
        val w = worker
        runCatching { queue?.put(EOF) }
        runCatching { w?.join(1000) }
        worker = null
        queue = null
        dir = null
    }

    /**
     * 回到「未 init」状态（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 计数一并归零：[written] 是「跨轮次不清零」的累计值，语义上属于**进程**，
     * 而 reset 的语义是「当作这个进程还没开始」。留着旧数会让第二个用例的
     * 「写入了 N 行」断言从 N 变成 2N，且失败信息看起来像重复写入的 bug。
     */
    fun reset() {
        shutdownForTest()
        dropped = 0L
        warned = false
        written = 0L
    }

    // —— 内部 ——

    private val EOF = "\u0000EOF"

    private fun noteDropped() {
        dropped++
        if (!warned) {
            warned = true
            // 刻意走 System.err 不接 AgentLogger：后者依赖 ConfigSanitizer → 供应商目录，
            // 在 JVM harness 里会连带编译一大堆无关文件（R12 踩过，三个 harness 因此挂掉）。
            System.err.println("[trace] 队列已满，后续事件将被丢弃（只提示一次）")
        }
    }

    private fun drainLoop(q: ArrayBlockingQueue<String>) {
        val buf = StringBuilder(8 * 1024)
        // 0 = 「从没写过」。第一批数据立即落盘（首条事件的新鲜度比攒批重要：
        // 用户复现完一个 bug 就导出，等一个完整窗口可能刚好错过），之后按窗口攒。
        var lastFlush = 0L
        while (true) {
            // 200ms 而不是 500ms：这个超时决定了「最后一批数据最晚什么时候落盘」，
            // 也就是 [flush] 需要等多久。500ms 会逼得导出要等半秒多。
            val line = runCatching { q.poll(200, TimeUnit.MILLISECONDS) }.getOrNull()
            if (line == EOF) break
            if (line != null) buf.append(line).append('\n')
            val now = System.currentTimeMillis()
            // 攒批：每 400ms 或攒够 32KB 落一次盘。逐行 write 会让一个回合的上百条事件
            // 变成上百次系统调用。
            if (buf.isNotEmpty() && (buf.length >= 32 * 1024 || now - lastFlush >= 400)) {
                writeOut(buf)
                lastFlush = now
            }
        }
        if (buf.isNotEmpty()) writeOut(buf)
    }

    private fun writeOut(buf: StringBuilder) {
        val target = dir ?: run { buf.setLength(0); return }
        val text = buf.toString()
        buf.setLength(0)
        runCatching {
            val f = currentFile(target)
            if (f.length() > MAX_FILE_BYTES) rotate(f)
            f.appendText(text)
            written += text.count { it == '\n' }
        }.onFailure { noteDropped() }
    }

    private fun currentFile(dir: File): File = File(dir, "turns-${stamp()}.ndjson")

    private fun rotate(f: File) {
        val idx = generateSequence(1) { it + 1 }
            .map { File(f.parentFile, "${f.nameWithoutExtension}.$it.ndjson") }
            .first { !it.exists() }
        runCatching { f.renameTo(idx) }
    }

    private fun stamp(): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    /**
     * 转义成 JSON 字符串字面量（含首尾引号）。
     *
     * 手写而不引序列化库：这个函数只在 core 里被两处调用，而引入 `@Serializable`
     * 会给每个 harness 加上编译器插件依赖（R12 的假绿事故就出在这里）。
     * 22 行代码换「零依赖、字节稳定、可断言」是划算的。
     */
    fun quote(raw: String?): String {
        if (raw == null) return "null"
        val sb = StringBuilder(raw.length + 2).append('"')
        raw.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // 其余控制字符走 \u00XX：NDJSON 是行协议，原始控制字符会打断解析
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format(java.util.Locale.US, "%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }
}
