package com.beichen.polaris.automation

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.delay

/**
 * 设备上下文聚合（L4 主动感知层）。
 *
 * 由两个系统服务实时喂数据，再统一向对话层（LLM）暴露“当前手机在干什么”的快照：
 *  - [AgentAccessibilityService]：窗口切换时写入前台应用包名/应用名
 *  - [AgentNotificationListener]：收到新通知时写入最近通知摘要
 *
 * 设计原则（净室原创、最小权限）：
 *  - 只缓存轻量文本摘要，不保存通知正文之外的任何隐私内容；
 *  - 通知只保留最近 8 条，前台应用只保留最近一个；
 *  - 所有写操作都在系统回调线程之外做轻量只读（PackageManager 转应用名），失败静默降级。
 */
object DeviceContextStore {

    /**
     * 启动后前台确认的最长等待（毫秒，W-7 P0-2）。
     *
     * 取 3 秒的理由：
     * 3s 覆盖「慢启动」应用的窗口切换，又不让调用方干等过久。
     */
    private const val LAUNCH_CONFIRM_TIMEOUT_MS = 3_000L

    /** 前台确认的轮询间隔（毫秒）：250ms 足够快地发现窗口切换，开销可忽略。 */
    private const val LAUNCH_CONFIRM_POLL_MS = 250L

    @Volatile private var foregroundPackage: String = ""
    @Volatile private var foregroundLabel: String = ""
    private val recentNotifs = ConcurrentLinkedQueue<String>()
    private const val MAX_NOTIFS = 8

    /** 无障碍服务在窗口切换时调用：记录当前前台应用。 */
    fun updateForeground(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        foregroundPackage = packageName
        foregroundLabel = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Throwable) { packageName }
    }

    /** 通知监听服务在收到通知时调用：追加一条摘要（已含应用名，调用方负责转换）。 */
    fun addNotification(summary: String) {
        if (summary.isBlank()) return
        recentNotifs.add(summary)
        while (recentNotifs.size > MAX_NOTIFS) recentNotifs.poll()
    }

    /** 清空前台应用与通知摘要（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。 */
    fun reset() {
        foregroundPackage = ""
        foregroundLabel = ""
        recentNotifs.clear()
    }

    /** 取一份不可变快照，供 UI 与对话层使用。 */
    fun snapshot(): DeviceContext = DeviceContext(
        foregroundPackage = foregroundPackage,
        foregroundLabel = foregroundLabel,
        recentNotifications = recentNotifs.toList().asReversed(),
        updatedAt = System.currentTimeMillis()
    )

    /**
     * 轮询等待 [targetPackage] 成为前台应用（W-7 P0-2：app_launch 启动后的前台确认）。
     *
     * 背景：`startActivity` 成功只代表「意图已发出」，窗口切换需要时间；启动后不确认
     * 前台，`ui_tree`（rootInActiveWindow）会读到旧窗口，模型误判「没打开」而放弃。
     * 本方法把「等到目标到前台」收敛为单一可复用入口，供 app_launch 调用。
     *
     * 计时语义：以**轮询预算**计时（累计已请求的休眠量，上限 [timeoutMs]）——行为确定、
     * 不依赖宿主机时钟，JVM harness 可用假 sleeper 毫秒级断言全部时序分支。
     *
     * 可注入性（JVM harness 可测）：[foregroundProvider] / [sleeper] 默认走真实
     * [snapshot] 与 [kotlinx.coroutines.delay]；harness 注入假 provider（按序返回
     * 模拟窗口切换）+ 假 sleeper（收集时延参数、不真睡）即可驱动全部分支。
     *
     * @param targetPackage 目标包名；空串直接返回 false（防空串误配）。
     * @param timeoutMs 最长等待毫秒（默认 [LAUNCH_CONFIRM_TIMEOUT_MS]）。
     * @param pollIntervalMs 轮询间隔毫秒（默认 [LAUNCH_CONFIRM_POLL_MS]）；**必须 > 0**，
     *   否则入口抛 IllegalArgumentException（W-7 审核 F2：非正间隔会使「间隔与剩余预算的
     *   较小值」恒 ≤0、waitedMs 永不增长 → 无限忙等，看门狗都杀不死）。
     * @return true = 已在 [timeoutMs] 内确认到前台；false = 超时仍未确认（或入参为空）。
     */
    suspend fun awaitForegroundPackage(
        targetPackage: String,
        timeoutMs: Long = LAUNCH_CONFIRM_TIMEOUT_MS,
        pollIntervalMs: Long = LAUNCH_CONFIRM_POLL_MS,
        foregroundProvider: () -> String = { snapshot().foregroundPackage },
        sleeper: suspend (Long) -> Unit = { delay(it) }
    ): Boolean {
        if (targetPackage.isEmpty()) return false
        // W-7 审核 F2：pollIntervalMs ≤ 0 时 sleepMs = minOf(pollIntervalMs,
        // timeoutMs - waitedMs) 恒 ≤0、waitedMs 永不增长 → 无限忙等。入口 fail-fast
        // 把调用方配错当场炸出来（不静默钳制——钳制会掩盖配错）。
        require(pollIntervalMs > 0) {
            "awaitForegroundPackage: pollIntervalMs 必须 > 0（收到 $pollIntervalMs）；" +
                "非正轮询间隔会使轮询预算永不推进，造成无限循环"
        }
        var waitedMs = 0L
        while (true) {
            if (foregroundProvider() == targetPackage) return true
            if (waitedMs >= timeoutMs) return false
            // 休眠量取「间隔与剩余预算的较小值」：既保持 250ms 的轮询节奏，
            // 又保证总等待不超过 timeoutMs（收尾那次可能不足一个间隔）。
            val sleepMs = minOf(pollIntervalMs, timeoutMs - waitedMs)
            sleeper(sleepMs)
            waitedMs += sleepMs
        }
    }
}

/**
 * 设备上下文是否已被无障碍服务喂过数据（W-7 P0-2 短路判据，**纯函数**）。
 *
 * 入参为 [DeviceContextStore.snapshot] 的 foregroundPackage：空白说明
 * [AgentAccessibilityService] 从未喂过（此时 ui_tree 同样不可用），启动后的
 * 沉降 + 前台确认无从谈起，调用方应短路返回、避免白等。抽成纯函数是为 JVM
 * harness 可对空白/非空白两种输入直接断言（无 Android 依赖）。
 */
internal fun deviceContextEverUpdated(foregroundPackage: String): Boolean =
    foregroundPackage.isNotBlank()

/**
 * 设备上下文快照（值对象）。[toPrompt] 输出可直接拼进 LLM 系统提示/用户输入的文本段落。
 */
data class DeviceContext(
    val foregroundPackage: String,
    val foregroundLabel: String,
    val recentNotifications: List<String>,
    val updatedAt: Long
) {
    /** 尚无任何感知数据时返回 true，表示上下文为空、无需注入。 */
    val isEmpty: Boolean
        get() = foregroundPackage.isBlank() && recentNotifications.isEmpty()

    /** 生成 "[当前设备上下文]" 段落；为空则返回空串，调用方据此决定是否注入。 */
    fun toPrompt(): String {
        if (isEmpty) return ""
        val sb = StringBuilder("[当前设备上下文]\n")
        if (foregroundPackage.isNotBlank()) {
            if (foregroundLabel.isNotBlank() && foregroundLabel != foregroundPackage) {
                sb.append("- 前台应用：$foregroundLabel ($foregroundPackage)\n")
            } else {
                sb.append("- 前台应用：$foregroundPackage\n")
            }
        }
        if (recentNotifications.isNotEmpty()) {
            sb.append("- 最近通知：\n")
            recentNotifications.forEachIndexed { i, n -> sb.append("  ${i + 1}. $n\n") }
        }
        return sb.toString().trimEnd()
    }
}
