package com.beichen.polaris.ui.container

import com.beichen.polaris.core.AppScope
import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.container.ContainerCommandRunner
import com.beichen.polaris.core.container.ToolchainInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 容器运行时安装的**应用级**任务持有者。
 *
 * ## 为什么必须脱离 Compose 作用域（2026-10-27 真机 13:55）
 *
 * 原来安装跑在 `rememberCoroutineScope()` 里。这个作用域随页面组合销毁而取消，于是：
 * 1. 用户点「安装 Java」→ 协程在 IO 线程里跑，proot 起来了；
 * 2. 用户切到别的页面（或进容器终端）→ 组合销毁 → 协程取消、`installing` 状态丢失；
 * 3. 但 `ContainerCommandRunner.run` 是阻塞调用，**不响应取消**——proot 继续跑；
 * 4. 用户回到容器页 → 按钮又显示「安装 Java」可点 → 点了被 `EXIT_BUSY` 拒；
 * 5. 真机日志里 20 秒内出现 6 次「拒绝重复命令：已在执行 84s…99s」，
 *    用户看到的就是「点了没反应、一直失败」。
 *
 * 更糟的是第 2 步同时起了第二个 proot（进容器终端），系统 LMK 直接把 pty
 * SIGKILL 掉（`code=137`）。
 *
 * ## 现在的契约
 * - 任务只跑一份，进程级单例，与页面生死无关；
 * - 页面只**观察**状态（[running] / [report] / [finished]），重建后立刻恢复成
 *   「正在安装 Java… 120s」，而不是恢复成「可以再点一次」；
 * - 装完结果留在 [report] 里，跨页面可读（用户切走再回来仍能看到失败原因）。
 */
object ContainerInstallController {

    /** 正在进行的安装。 */
    data class Running(
        val distroId: String,
        val label: String,
        val startedAtMs: Long
    ) {
        fun elapsedSeconds(nowMs: Long = System.currentTimeMillis()): Long =
            ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L)
    }

    /**
     * 一次安装的结果（内联展示用）。
     *
     * 为什么不用 Toast 汇报失败：Toast 只能容纳两行，且马上消失；而 apk / apt 的失败
     * 原因几乎总在输出的**最后几行**（`ERROR: ...`），前 80 字截断恰好把它们切掉。
     * 真机上用户拿到的就是 `exit 99` 加一句被切一半的话，等于没给信息。
     *
     * @param targets  这次安装想装的语言 id
     * @param verified 安装命令失败后，用 `--version` 自检通过的语言 id
     */
    data class Report(
        val distroId: String,
        val label: String,
        val result: ContainerCommandRunner.Result,
        val targets: Set<String> = emptySet(),
        val verified: Set<String> = emptySet()
    ) {
        /**
         * 包管理器说失败，但目标运行时全部通过自检。
         *
         * 真机实例：`apk add openjdk17 openjdk17-jdk` 装完 77 个包后报 `1 error`
         * （退出码 1，某个 trigger 脚本在 proot 下失败），但 `java -version &&
         * javac -version` 正常。这时候告诉用户「安装失败」是错的——他明明能跑 javac。
         */
        val okByCapability: Boolean
            get() = !result.ok && targets.isNotEmpty() && verified.containsAll(targets)

        /** 最终结论：命令成功，或命令失败但能力已具备。 */
        val effectiveOk: Boolean get() = result.ok || okByCapability

        /** 失败时保留可诊断的行：错误行优先，再补尾部。 */
        val summary: String
            get() = when {
                result.ok -> "$label 安装完成。"
                result.busy -> "$label：同一条命令已在执行，等它结束再点。"
                // 真机 15:07：Alpine 装 nodejs 的退出码是 1，但 `node --version` 是 v20.15.1、
                // npm 是 10.9.1——报错来自包管理器收尾的 trigger 脚本，跟能不能用无关。
                // 这时候在卡片上写「已就位：包管理器报了错误」还是让人心里没底，
                // 改成直接说「安装完成」，把原因放在括号里。
                okByCapability -> "$label 安装完成。包管理器收尾时报了个错" +
                    "（多在 trigger 脚本，不影响使用），" +
                    "${targets.joinToString("、")} 已验证可运行。"
                else -> {
                    val lines = result.output.trim().lines().filter { it.isNotBlank() }
                    val errors = lines.filter {
                        it.contains("error", ignoreCase = true) || it.contains("fail", ignoreCase = true)
                    }.takeLast(5)
                    val shown = (errors + lines.takeLast(6)).distinct().takeLast(12).joinToString("\n")
                    "$label 安装失败（exit ${result.exitCode}）：\n" +
                        shown.ifBlank { "（无输出）" }
                }
            }
    }

    private const val TAG = "ContainerInstall"

    /**
     * 独立于任何页面的作用域。
     *
     * `SupervisorJob`：一次安装失败不影响后续安装。`Dispatchers.IO`：内部全是阻塞
     * 进程调用（`ProcessBuilder` + `waitFor`），放在默认调度器会占满 CPU 池。
     */
    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    private val _report = MutableStateFlow<Report?>(null)
    val report: StateFlow<Report?> = _report.asStateFlow()

    /** 完成次数：容器页收集它来重算 `ToolchainInstaller.detect`（已装/未装）。 */
    private val _finished = MutableStateFlow(0)
    val finished: StateFlow<Int> = _finished.asStateFlow()

    /**
     * 发起一次安装。已有任务在跑时返回 false（不排队、不叠加）。
     *
     * 注意：这里**不能**接受页面级 context 长期持有；统一转成 applicationContext。
     */
    fun start(
        context: Context,
        distroId: String,
        label: String,
        command: String,
        targets: List<ToolchainInstaller.Lang>
    ): Boolean {
        val app = context.applicationContext
        synchronized(this) {
            if (_running.value != null) return false
            _running.value = Running(distroId, label, System.currentTimeMillis())
        }
        _report.value = null
        AgentLogger.i(TAG, "开始安装：distro=$distroId label=$label")
        scope.launch {
            val (res, verified) = installAndVerify(app, distroId, command, targets)
            _report.value = Report(
                distroId = distroId,
                label = label,
                result = res,
                targets = targets.map { it.id }.toSet(),
                verified = verified
            )
            _running.value = null
            _finished.value += 1
            AgentLogger.i(
                TAG,
                "安装结束：distro=$distroId exit=${res.exitCode} 自检通过=$verified"
            )
        }
        return true
    }

    /** 仅供测试/诊断：清掉结果（下次进页面不显示上一次的红色报错）。 */
    fun clearReport() {
        _report.value = null
    }

    /**
     * 回到「从未安装过」的初始态（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 与 [clearReport] 的区别：这里连 [running] 和 [finished] 一起清。
     * 只清 report 的话，跨用例残留的 `finished` 计数会让「装完一次」的断言在
     * 第一个用例里就成立——那是假绿。
     *
     * **不取消正在跑的安装协程**：那个协程持有 proot 进程，硬停会把 pty 留在
     * 半开状态。harness 里的正确用法是等它结束再 reset。
     */
    fun reset() {
        _running.value = null
        _report.value = null
        _finished.value = 0
    }

    /**
     * 安装 + 失败后自检。
     *
     * 包管理器的退出码不能代表「能不能用」：真机上 `apk add openjdk17 openjdk17-jdk`
     * 装完 77 个包后报 `1 error`（退出码 1，某个 trigger 脚本在 proot 下失败），
     * 但 `java -version && javac -version` 是正常的。反过来，退出码 0 也不等于
     * `javac` 真在（JRE 包装上了、JDK 包被跳过）。
     *
     * 所以失败时补一次真实自检（一次容器命令，约 2 秒），用**能力**下结论。
     */
    private fun installAndVerify(
        context: Context,
        distroId: String,
        command: String,
        targets: List<ToolchainInstaller.Lang>
    ): Pair<ContainerCommandRunner.Result, Set<String>> {
        val res = ContainerCommandRunner.run(context, distroId, command)
        if (res.ok || res.busy) return res to emptySet()
        val script = ToolchainInstaller.verifyScript(targets)
        if (script.isBlank()) return res to emptySet()
        val verify = ContainerCommandRunner.run(context, distroId, script)
        return res to ToolchainInstaller.verifiedLangs(verify.output)
    }
}
