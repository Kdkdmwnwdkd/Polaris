import com.beichen.polaris.core.agent.AgentRunState
import com.beichen.polaris.core.agent.AgentRunStateStore
import com.beichen.polaris.core.agent.ApprovalGrouping
import com.beichen.polaris.core.capability.CapabilityManifest
import com.beichen.polaris.core.container.ToolchainInstaller
import com.beichen.polaris.core.workspace.FileBackupStore
import com.beichen.polaris.core.workspace.TextDiff
import com.beichen.polaris.skill.Capability
import com.beichen.polaris.skill.CapabilityRegistry
import com.beichen.polaris.skill.checkCapabilityGate
import com.beichen.polaris.skill.isToolAllowedByWhitelist
import com.beichen.polaris.skill.policyFor
import com.beichen.polaris.skill.trustLabel
import java.io.File

/**
 * B 批 harness：信任等级 fail-closed + 自动备份 + 前台服务/运行态 + 容器运行时安装。
 *
 * 四组断言：
 *  1. **信任等级**：未知/缺省 trust 必须按最严处理（这是 B 批修掉的 fail-open）；
 *  2. **自动备份**：写前备份 → 回滚能还原；新建文件回滚即删除；路径穿越被拒；
 *  3. **运行态**：进程被杀后残留 running 应对账成 interrupted 并给出恢复提示；
 *  4. **容器运行时**：包管理器识别、缺什么装什么、命令生成正确。
 *
 * 全部为纯逻辑断言，不依赖 Android 运行时。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 $expected，实际 $actual")
}

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

/** 建一个临时目录，块结束自动删。 */
private fun tmpDir(block: (File) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "agentkit-b-${System.nanoTime()}")
    dir.mkdirs()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

fun main() {
    println("== B 批 harness：信任等级 / 自动备份 / 运行态 / 容器运行时 ==")

    /* ------------------------- B1 信任等级 fail-closed ------------------------- */

    check("B1-1 精确 packaged 仍给最高信任（不能矫枉过正）") {
        val p = policyFor("packaged")
        assertTrue("packaged 应允许脚本", p.allowScript)
        assertEquals("packaged 上限应为 DANGEROUS", Capability.DANGEROUS, p.maxCapability)
    }

    check("B1-2 imported / remote 收紧") {
        val imported = policyFor("imported")
        assertTrue("imported 不应允许脚本", !imported.allowScript)
        assertEquals("imported 上限应为 PRIVILEGED", Capability.PRIVILEGED, imported.maxCapability)
        val remote = policyFor("remote")
        assertTrue("remote 不应允许脚本", !remote.allowScript)
        assertEquals("remote 上限应为 SAFE", Capability.SAFE, remote.maxCapability)
    }

    check("B1-3 未知 trust 值按最严处理（旧的 else→packaged 是 fail-open）") {
        // 注意：大小写与首尾空格归一化后仍应识别为 packaged（同一个意图，不该被降级）
        listOf("trusted", "admin", "full", "god", "x", "packaged2", "imported2", "remote2")
            .forEach { bad ->
                val p = policyFor(bad)
                assertTrue("未知 trust「$bad」不应允许脚本", !p.allowScript)
                assertEquals("未知 trust「$bad」上限应为 SAFE", Capability.SAFE, p.maxCapability)
            }
    }

    check("B1-3b 大小写/空格归一化后仍识别为 packaged") {
        listOf("packaged", "Packaged", "PACKAGED", " packaged ").forEach { good ->
            val p = policyFor(good)
            assertTrue("「$good」应允许脚本", p.allowScript)
            assertEquals("「$good」上限应为 DANGEROUS", Capability.DANGEROUS, p.maxCapability)
        }
    }

    check("B1-4 null / 空串同样按最严处理") {
        listOf(null, "", "   ").forEach { bad ->
            val p = policyFor(bad)
            assertTrue("空 trust 不应允许脚本", !p.allowScript)
            assertEquals("空 trust 上限应为 SAFE", Capability.SAFE, p.maxCapability)
        }
    }

    check("B1-5 能力门对未知来源的 DANGEROUS 工具必须拦截") {
        val msg = checkCapabilityGate("shell_run", "trusted")
        assertTrue("未知来源调 shell_run 应被拦截", msg != null)
        val packaged = checkCapabilityGate("shell_run", "packaged")
        assertTrue("packaged 调 shell_run 不应被能力门拦（由人工审批兜底）", packaged == null)
    }

    check("B1-6 trustLabel 对未知值不谎称内置") {
        assertTrue("packaged 应显示打包可信", trustLabel("packaged").contains("打包可信"))
        assertTrue("Packaged 归一化后也应显示打包可信", trustLabel("Packaged").contains("打包可信"))
        assertTrue("未知值应标明来源未知", trustLabel("weird").contains("未知"))
    }

    check("B1-7 SkillManifest 默认 trust 不再是 packaged") {
        val src = read("core/src/main/java/com/beichen/polaris/skill/SkillProtocol.kt")
        assertTrue(
            "manifest 默认 trust 应为空（未知）而非 packaged",
            "val trust: String = \"\"" in src
        )
    }

    check("B1-8 assets 内置技能由 SkillLoader 显式标为 packaged") {
        val src = read("core/src/main/java/com/beichen/polaris/skill/SkillProtocol.kt")
        // R7：同时要带上资源目录名——内置技能的 id 是包名式，目录名才是短名，
        // 用 id 拼 assets 路径会让 entry 读不到（脚本形态静默退化成提示词形态）。
        assertTrue(
            "SkillLoader 应显式 copy(trust = \"packaged\", assetDir = id)",
            "copy(trust = \"packaged\", assetDir = id)" in src
        )
    }

    /* ------------------------- B2 自动备份 ------------------------- */

    check("B2-1 备份旧内容后可回滚还原") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val f = File(ws, "a.txt")
            f.writeText("v1")
            val id = store.backupBeforeWrite("a.txt")
            assertTrue("应产生备份 id", id != null)
            f.writeText("v2")
            assertEquals("回滚前内容应是 v2", "v2", f.readText())
            assertTrue("回滚应成功", store.restore(id!!))
            assertEquals("回滚后应还原为 v1", "v1", f.readText())
        }
    }

    check("B2-2 新建文件的备份回滚即删除该文件") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val id = store.backupBeforeWrite("new.txt")
            assertTrue("应产生备份 id", id != null)
            val f = File(ws, "new.txt")
            f.writeText("hello")
            assertTrue("回滚应成功", store.restore(id!!))
            assertTrue("回滚后文件应被删除", !f.exists())
        }
    }

    check("B2-3 路径穿越被拒绝（不产生备份）") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            assertTrue(".. 穿越应被拒", store.backupBeforeWrite("../evil.txt") == null)
            assertTrue("绝对路径应被拒", store.backupBeforeWrite("/etc/passwd") == null)
            assertTrue("空路径应被拒", store.backupBeforeWrite("  ") == null)
        }
    }

    check("B2-4 子目录文件可备份与回滚") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val sub = File(ws, "src/main").apply { mkdirs() }
            val f = File(sub, "k.kt")
            f.writeText("old")
            val id = store.backupBeforeWrite("src/main/k.kt")
            assertTrue("应产生备份 id", id != null)
            f.writeText("new")
            assertTrue("回滚应成功", store.restore(id!!))
            assertEquals("子目录内容应还原", "old", f.readText())
        }
    }

    check("B2-5 list 按时间倒序且 latestFor 命中最近一条") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val f = File(ws, "x.txt")
            f.writeText("a")
            val id1 = store.backupBeforeWrite("x.txt")
            Thread.sleep(5)
            f.writeText("b")
            val id2 = store.backupBeforeWrite("x.txt")
            val list = store.list()
            assertEquals("应有 2 条备份", 2, list.size)
            assertEquals("最新一条应是最后创建的", id2, list.first().id)
            assertEquals("latestFor 应返回最近一条", id2, store.latestFor("x.txt")?.id)
            assertTrue("两条 id 应不同", id1 != id2)
        }
    }

    check("B2-6 写工具确实调用了备份钩子") {
        val src = read("app/src/main/java/com/beichen/polaris/tools/BuiltInTools.kt")
        assertTrue("write_file 应调用 backupBeforeWrite", "backupHook?.backupBeforeWrite(path)" in src)
        assertTrue("edit_file 应调用 backupBeforeWrite", src.contains("backupHook?.backupBeforeWrite(path)"))
        assertTrue("应存在 WriteBackupHook 接口", "fun interface WriteBackupHook" in src)
    }

    check("B2-7 ChatViewModel 注入备份仓") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
        assertTrue("应注入 FileBackupStore", "FileBackupStoreFactory.default(app)" in src)
        assertTrue("WriteFileTool 应传入备份钩子", "WriteFileTool(workspace, WriteBackupHook" in src)
        assertTrue("EditFileTool 应传入备份钩子", "EditFileTool(workspace, WriteBackupHook" in src)
    }

    /* ------------------------- B3 前台服务与运行态 ------------------------- */

    check("B3-1 残留 running 运行态对账成 interrupted") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "帮我把项目构建出来",
                    stepIndex = 3,
                    status = AgentRunState.STATUS_RUNNING,
                    startedAt = 1000L
                )
            )
            val interrupted = store.reconcile()
            assertTrue("应检出中断", interrupted != null)
            assertEquals("状态应为 interrupted", AgentRunState.STATUS_INTERRUPTED, interrupted!!.status)
            assertTrue("恢复提示应含原文", interrupted.note.contains("帮我把项目构建出来"))
            assertTrue("恢复提示应提到步骤", interrupted.note.contains("第 3 步"))
            assertTrue("恢复提示应给出继续指引", interrupted.note.contains("继续"))
        }
    }

    check("B3-2 已完成的运行态不产生中断提示") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(AgentRunState(sessionId = "s1", status = AgentRunState.STATUS_DONE))
            assertTrue("已完成不应报中断", store.reconcile() == null)
        }
    }

    check("B3-3 对账只发生一次（幂等）") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(AgentRunState(sessionId = "s1", status = AgentRunState.STATUS_RUNNING))
            assertTrue("首次应检出", store.reconcile() != null)
            assertTrue("再次不应检出", store.reconcile() == null)
        }
    }

    check("B3-4 无运行态文件时不报错") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            assertTrue("无文件应返回 null", store.load() == null)
            assertTrue("无文件不应报中断", store.reconcile() == null)
        }
    }

    check("B3-5 前台服务已在 Manifest 注册且类型正确") {
        val src = read("app/src/main/AndroidManifest.xml")
        assertTrue("应注册 AgentRunService", ".core.agent.AgentRunService" in src)
        assertTrue("前台服务类型应为 dataSync", "foregroundServiceType=\"dataSync\"" in src)
        assertTrue("不应 exported", "android:exported=\"true\"\n            android:foregroundServiceType" !in src)
    }

    check("B3-6 send() 启保活、finally 停保活并写运行态") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
        assertTrue("应启动前台服务", "AgentRunService.start(app" in src)
        assertTrue("应停止前台服务", "AgentRunService.stop(app)" in src)
        assertTrue("应写入 running 运行态", "AgentRunState.STATUS_RUNNING" in src)
        assertTrue("结束时应标 done", "AgentRunState.STATUS_DONE" in src)
    }

    check("B3-7 中断提示在 UI 可见且可关闭") {
        val vm = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
        val screen = read("app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt")
        assertTrue("VM 应暴露 interruptedNotice", "interruptedNotice" in vm)
        assertTrue("应有 dismiss 方法", "dismissInterruptedNotice" in vm)
        assertTrue("聊天页应渲染横幅", "InterruptedBanner" in screen)
    }

    /* ------------------------- B4 容器运行时 ------------------------- */

    check("B4-1 识别 apk 包管理器") {
        tmpDir { dir ->
            File(dir, "sbin").mkdirs()
            File(dir, "sbin/apk").writeText("#!/bin/sh")
            assertEquals(
                "应识别为 APK",
                ToolchainInstaller.PkgManager.APK,
                ToolchainInstaller.detectPkgManager(dir)
            )
        }
    }

    check("B4-2 识别 apt 包管理器") {
        tmpDir { dir ->
            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/apt-get").writeText("#!/bin/sh")
            assertEquals(
                "应识别为 APT",
                ToolchainInstaller.PkgManager.APT,
                ToolchainInstaller.detectPkgManager(dir)
            )
        }
    }

    check("B4-3 无包管理器时明确说明原因，不瞎试") {
        tmpDir { dir ->
            val d = ToolchainInstaller.detect(dir)
            assertTrue("包管理器应为 null", d.pkgManager == null)
            assertTrue("应给出原因", d.reason != null && d.reason.isNotBlank())
        }
    }

    check("B4-4 探测已安装的运行时") {
        tmpDir { dir ->
            File(dir, "bin").mkdirs()
            File(dir, "bin/node").writeText("")
            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/python3").writeText("")
            val d = ToolchainInstaller.detect(dir)
            assertTrue("应识别 node", ToolchainInstaller.Lang.NODE in d.installed)
            assertTrue("应识别 python", ToolchainInstaller.Lang.PYTHON in d.installed)
            assertTrue("不应识别 java", ToolchainInstaller.Lang.JAVA !in d.installed)
        }
    }

    check("B4-5 apk 安装命令正确且带 --no-cache") {
        val cmd = ToolchainInstaller.installCommand(ToolchainInstaller.Lang.NODE, ToolchainInstaller.PkgManager.APK)
        assertTrue("应含 apk add", cmd!!.contains("apk add"))
        assertTrue("应带 --no-cache（避免 apk 缓存吃满手机存储）", "--no-cache" in cmd)
        assertTrue("应含 nodejs", "nodejs" in cmd)
        // npm 在 Alpine 的 community 仓库，必须先把源补齐，否则装完 node 没有 npm
        assertTrue("应先补 community 源", cmd.indexOf("/community") in 0 until cmd.indexOf("apk add"))
        assertTrue("补源必须幂等（已有 community 就不重复追加）", "grep -qs '/community'" in cmd)
    }

    check("B4-6 apt 安装命令必须先 update") {
        val cmd = ToolchainInstaller.installCommand(ToolchainInstaller.Lang.PYTHON, ToolchainInstaller.PkgManager.APT)
        assertTrue("应含 apt-get update", cmd!!.contains("apt-get update"))
        assertTrue("应含 install -y（非交互）", "apt-get install -y" in cmd)
        assertTrue("update 必须在 install 之前", cmd.indexOf("update") < cmd.indexOf("install"))
    }

    check("B4-7 一键装齐缺失项；全装好时返回 null") {
        tmpDir { dir ->
            File(dir, "sbin").mkdirs()
            File(dir, "sbin/apk").writeText("")
            val d = ToolchainInstaller.detect(dir)
            val cmd = ToolchainInstaller.installMissingCommand(d)
            assertTrue("应生成命令", cmd != null)
            assertTrue("应含 nodejs", cmd!!.contains("nodejs"))
            assertTrue("应含 python3", cmd.contains("python3"))
            assertTrue("应含 openjdk", cmd.contains("openjdk"))
            assertTrue("只应出现一条 apk add", cmd.split("apk add").size == 2)

            // 全部装好 → 不需要安装
            File(dir, "bin").mkdirs()
            File(dir, "bin/node").writeText("")
            File(dir, "bin/python3").writeText("")
            File(dir, "bin/java").writeText("")
            val d2 = ToolchainInstaller.detect(dir)
            assertTrue("全装好应返回 null", ToolchainInstaller.installMissingCommand(d2) == null)
        }
    }

    check("B4-8 新工具必须登记能力表且为 DANGEROUS") {
        assertEquals(
            "container_toolchain 应为 DANGEROUS",
            Capability.DANGEROUS,
            CapabilityRegistry.cap("container_toolchain")
        )
    }

    check("B4-9 新工具挂人工审批并归入 shell 组") {
        val tool = read("app/src/main/java/com/beichen/polaris/tools/ContainerToolchainTool.kt")
        assertTrue("应挂 needsApproval", "override val needsApproval = true" in tool)
        assertEquals(
            "应归入 shell 审批组",
            ApprovalGrouping.GROUP_SHELL,
            ApprovalGrouping.groupOf("container_toolchain")
        )
    }

    check("B4-10 容器工具已注册进 registry") {
        val vm = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
        assertTrue("应注册 ContainerToolchainTool", "register(ContainerToolchainTool(app))" in vm)
    }

    check("B4-11 一次性命令执行器存在且走独立进程通道") {
        val runner = read("core/src/main/java/com/beichen/polaris/core/container/ContainerCommandRunner.kt")
        assertTrue("应使用 ProcessBuilder（取退出码）", "ProcessBuilder" in runner)
        assertTrue("应等待退出", "waitFor" in runner)
        val screen = read("app/src/main/java/com/beichen/polaris/ui/ContainerScreen.kt")
        assertTrue("容器页应有运行时安装入口", "ToolchainInstaller" in screen)
        assertTrue("容器页应调用执行器", "ContainerCommandRunner" in screen)
    }

    /* --------------- B4-12..14：真机 exit 99（apk 并发抢锁）回归 --------------- */

    check("B4-12 锁冲突判据覆盖 apk 与 apt 的真实形态，且不误判普通失败") {
        // 真机原文：Alpine 3.20 上连点两次安装 Node.js 20
        assertTrue(
            "apk 退出码 99 应判为抢锁",
            ToolchainInstaller.isLockConflict(
                99,
                "ERROR: Unable to lock database: temporary error (try again later)\nERROR: Failed"
            )
        )
        // 只看文本也要能判（部分版本退出码被包装成 1）
        assertTrue(
            "apk 文本判据应生效",
            ToolchainInstaller.isLockConflict(1, "ERROR: Unable to lock database")
        )
        assertTrue(
            "apt 锁冲突应判为抢锁",
            ToolchainInstaller.isLockConflict(
                100,
                "E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 1234"
            )
        )
        // 反例：网络失败绝不能当成抢锁去重试（否则会把 15 分钟耗在一次下载重试上）
        assertTrue(
            "网络失败不应判为抢锁",
            !ToolchainInstaller.isLockConflict(1, "ERROR: https://dl-cdn.alpinelinux.org: temporary failure in name resolution")
        )
        assertTrue(
            "成功不应判为抢锁",
            !ToolchainInstaller.isLockConflict(0, "OK: 12 MiB in 8 packages")
        )
    }

    check("B4-13 执行器串行化：同一时刻只允许一个包管理器在跑") {
        val runner = read("core/src/main/java/com/beichen/polaris/core/container/ContainerCommandRunner.kt")
        assertTrue("应有进程级公平锁", "ReentrantLock(true)" in runner)
        assertTrue("执行必须持锁", "execLock.withLock" in runner)
        assertTrue("重复命令应有专用退出码", "EXIT_BUSY" in runner)
        assertTrue("应在 finally 里释放登记，异常也不留脏状态", "inFlight.remove(key)" in runner)
        assertTrue("抢锁应重试", "runProcessWithLockRetry" in runner)
    }

    check("B4-14 容器页安装按钮有运行态防重入（不能只靠底层串行）") {
        val screen = read("app/src/main/java/com/beichen/polaris/ui/ContainerScreen.kt")
        // W-62：运行态不能再是页面 remember 的局部变量。真机 13:55 的教训——
        // 用户切页后组合销毁、`installing` 归 null，但 ContainerCommandRunner 的阻塞
        // 调用不吃取消，proot 仍在跑；按钮"复活"成可点，点了被 EXIT_BUSY 拒，
        // 20 秒内连拒 6 次，用户以为按钮坏了。
        assertTrue(
            "运行态必须来自应用级持有者（ContainerInstallController）",
            "ContainerInstallController.running" in screen
        )
        assertTrue("按钮应据此禁用", "enabled = installCmd != null && running == null" in screen)
        assertTrue("结果应内联常驻（Toast 会截断错误尾行）", "installReport" in screen)
        assertTrue("进行中应显示秒数（15 分钟无反馈会被当成卡死）", "elapsedSeconds" in screen)
        // 防回归：不允许安装任务再跑在页面作用域里
        assertTrue(
            "安装任务不能跑在 rememberCoroutineScope 里（切页即取消，proot 仍在跑）",
            "installing = allKey" !in screen
        )
    }

    check("B4-15 执行器必须并发抽干输出（否则管道写满，父子互等到超时）") {
        val runner = read("core/src/main/java/com/beichen/polaris/core/container/ContainerCommandRunner.kt")
        assertTrue("应有独立读线程", "Thread({ sink.drain" in runner)
        val drainAt = runner.indexOf("sink.drain")
        val waitAt = runner.indexOf("process.waitFor")
        assertTrue(
            "读操作必须早于 waitFor（先 waitFor 再 readText 会让 64KB 管道写满后互锁）：drain=$drainAt wait=$waitAt",
            drainAt > 0 && waitAt > 0 && drainAt < waitAt
        )
        assertTrue("进程退出后应等读线程收尾", "reader.join" in runner)
        assertTrue("只在管道尾部保留输出", "if (buf.length > limit)" in runner)
        assertTrue("大包（JDK）应有更长超时", "estimateTimeoutMinutes" in runner)
    }

    check("B4-17 proot 必须 fake root，且退出时杀掉 tracee") {
        val proot = read("core/src/main/java/com/beichen/polaris/core/container/ProotRunner.kt")
        // 提示符是 "$" 而不是 "#" → 容器里不是 root → trigger 里的 chown 失败 → apk exit 1
        assertTrue("应有 --root-id（fake root）", "\"--root-id\"" in proot)
        // 超时后 destroyForcibly 只杀 proot，残留的 apk 会占着 /lib/apk/db/lock
        assertTrue("应有 --kill-on-exit", "\"--kill-on-exit\"" in proot)
        assertTrue("应有 --link2symlink", "\"--link2symlink\"" in proot)
    }

    check("B4-16 装完自检：以能力为准，而不是包管理器退出码") {
        val script = ToolchainInstaller.verifyScript(listOf(ToolchainInstaller.Lang.JAVA))
        assertTrue("应验 java", "java -version" in script)
        // 卡片标题是「Java (JRE + JDK)」：只验 java 会把「JRE 在、JDK 缺」当成功
        assertTrue("应同时验 javac", "javac -version" in script)
        assertTrue("应打 OK:<id> 标记", "OK:java" in script)
        assertEquals("空列表应返回空串（调用方据此跳过这次容器命令）", "", ToolchainInstaller.verifyScript(emptyList()))
        val out = "FAIL:node\nOK:java\n"
        assertEquals("应解析出 java 通过", setOf("java"), ToolchainInstaller.verifiedLangs(out))
        assertEquals("应解析出 node 失败", setOf("node"), ToolchainInstaller.failedLangs(out))
        assertEquals("无标记时应为空集", emptySet<String>(), ToolchainInstaller.verifiedLangs("noise\n"))
    }

    /* ------------------------- C1 备份分组与批量回滚 ------------------------- */

    check("C1-1 同一文件的多次改动聚成一组，组内按时间倒序") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val a = File(ws, "a.txt")
            val b = File(ws, "b.txt")
            a.writeText("a0"); b.writeText("b0")
            val a1 = store.backupBeforeWrite("a.txt")
            Thread.sleep(5)
            a.writeText("a1")
            val a2 = store.backupBeforeWrite("a.txt")
            Thread.sleep(5)
            b.writeText("b1")
            store.backupBeforeWrite("b.txt")
            Thread.sleep(5)
            a.writeText("a2")
            val a3 = store.backupBeforeWrite("a.txt")

            val groups = store.grouped()
            assertEquals("应有 2 组", 2, groups.size)
            val ga = groups.first { it.relPath == "a.txt" }
            val gb = groups.first { it.relPath == "b.txt" }
            assertEquals("a.txt 组内应有 3 条", 3, ga.changeCount)
            assertEquals("b.txt 组内应有 1 条", 1, gb.changeCount)
            assertEquals("组内最新在前", a3, ga.backups.first().id)
            assertEquals("组内次新在中间", a2, ga.backups[1].id)
            assertEquals("组内最旧在最后", a1, ga.backups[2].id)
        }
    }

    check("C1-2 批量回滚某文件必须退到最初状态（倒序恢复才退得干净）") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val f = File(ws, "x.txt")
            f.writeText("v0")
            store.backupBeforeWrite("x.txt")   // 备份 v0
            f.writeText("v1")
            store.backupBeforeWrite("x.txt")   // 备份 v1
            f.writeText("v2")
            store.backupBeforeWrite("x.txt")   // 备份 v2
            f.writeText("v3")
            assertEquals("当前应为 v3", "v3", f.readText())

            val n = store.restoreFile("x.txt")
            assertEquals("应恢复 3 条", 3, n)
            // 正序恢复会停在 v2（退不干净），倒序恢复才回到 v0
            assertEquals("批量回滚后应退到最初的 v0", "v0", f.readText())
        }
    }

    check("C1-3 批量回滚后不再改动过的文件不受影响") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val a = File(ws, "a.txt")
            val b = File(ws, "b.txt")
            a.writeText("a0"); b.writeText("b0")
            store.backupBeforeWrite("a.txt")
            a.writeText("a1")
            store.backupBeforeWrite("b.txt")
            b.writeText("b1")

            assertEquals("只回滚 a.txt 应恢复 1 条", 1, store.restoreFile("a.txt"))
            assertEquals("a.txt 应退到 a0", "a0", a.readText())
            assertEquals("b.txt 不应被影响", "b1", b.readText())
        }
    }

    check("C1-4 restoreAll 一次性退回所有文件") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val a = File(ws, "a.txt")
            val b = File(ws, "b.txt")
            a.writeText("a0"); b.writeText("b0")
            store.backupBeforeWrite("a.txt")
            a.writeText("a1")
            store.backupBeforeWrite("b.txt")
            b.writeText("b1")

            assertEquals("应恢复 2 条", 2, store.restoreAll())
            assertEquals("a.txt 应退到 a0", "a0", a.readText())
            assertEquals("b.txt 应退到 b0", "b0", b.readText())
        }
    }

    check("C1-5 无备份时批量回滚返回 0 而非报错") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            assertEquals("空仓应返回 0", 0, store.restoreFile("nope.txt"))
            assertEquals("空仓全量应返回 0", 0, store.restoreAll())
            assertEquals("空仓分组应为空", 0, store.grouped().size)
        }
    }

    check("C1-6 编辑器界面提供分组展示与批量回滚入口") {
        val screen = read("app/src/main/java/com/beichen/polaris/ui/EditorScreen.kt")
        assertTrue("应使用 grouped() 分组展示", "grouped()" in screen)
        assertTrue("应提供 restoreFile 批量回滚", "restoreFile(" in screen)
        assertTrue("应提供 restoreAll 全量回滚", "restoreAll(" in screen)
    }

    /* ------------------------- C1b 备份保留策略（交接文档 4.1-3） ------------------------- */

    check("C1b-1 prune(keep) 只保留最新 N 条") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bk"), ws)
            val f = File(ws, "x.txt")
            repeat(5) { i ->
                f.writeText("v$i")
                store.backupBeforeWrite("x.txt")
                Thread.sleep(2)
            }
            assertEquals("5 条删到 2 条 = 删掉 3 条", 3, store.prune(2))
            assertEquals("剩余条数", 2, store.list().size)
        }
    }

    check("C1b-2 prune(0) 表示不清理（不能把备份删光）") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bk"), ws)
            val f = File(ws, "y.txt")
            repeat(3) { i ->
                f.writeText("v$i")
                store.backupBeforeWrite("y.txt")
                Thread.sleep(2)
            }
            assertEquals("keep=0 应删 0 条", 0, store.prune(0))
            assertEquals("备份应全部保留", 3, store.list().size)
        }
    }

    check("C1b-3 写入路径会自动触发清理（不再需要手动调 prune）") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            // keep=5，每 20 次写触发一次清理 → 写 40 次必然触发
            val store = FileBackupStore(File(dir, "bk"), ws, {}, keep = 5)
            val f = File(ws, "z.txt")
            repeat(40) { i ->
                f.writeText("v$i")
                store.backupBeforeWrite("z.txt")
            }
            val n = store.list().size
            assertTrue("自动清理应把条数压到不超过 keep+余量（实际 $n）", n <= 5 + 20)
            assertTrue("不应一条不剩（$n）", n > 0)
        }
    }

    /* ------------------------- D1 备份差异视图 ------------------------- */

    check("D1-1 相同文本 → 无差异") {
        val s = TextDiff.stats("a\nb\nc", "a\nb\nc")
        assertTrue("应无变化", s.isEmpty)
        assertEquals("摘要", "无变化", s.label)
    }

    check("D1-2 纯新增：只算 added") {
        val s = TextDiff.stats("a\nb", "a\nb\nc\nd")
        assertEquals("新增 2 行", 2, s.added)
        assertEquals("删除 0 行", 0, s.removed)
        assertEquals("摘要", "+2", s.label)
    }

    check("D1-3 纯删除：只算 removed") {
        val s = TextDiff.stats("a\nb\nc", "a")
        assertEquals("新增 0", 0, s.added)
        assertEquals("删除 2", 2, s.removed)
        assertEquals("摘要", "−2", s.label)
    }

    check("D1-4 改一行 = 一删一增（不是整段替换）") {
        val lines = TextDiff.lines("a\nOLD\nc", "a\nNEW\nc")
        val del = lines.count { it.kind == TextDiff.Kind.DEL }
        val add = lines.count { it.kind == TextDiff.Kind.ADD }
        val same = lines.count { it.kind == TextDiff.Kind.SAME }
        assertEquals("删除 1 行", 1, del)
        assertEquals("新增 1 行", 1, add)
        assertEquals("未变 2 行（a 和 c）", 2, same)
    }

    check("D1-5 空文件 → 有内容：全部算新增，不产生虚假删除") {
        val lines = TextDiff.lines("", "x\ny")
        assertEquals("无删除", 0, lines.count { it.kind == TextDiff.Kind.DEL })
        assertEquals("新增 2", 2, lines.count { it.kind == TextDiff.Kind.ADD })
    }

    check("D1-6 末尾换行不影响结果（a 与 a\\n 视为同一份）") {
        assertTrue("应无差异", TextDiff.stats("a\n", "a").isEmpty)
    }

    check("D1-7 行号正确（新增行只有 newNo，删除行只有 oldNo）") {
        val lines = TextDiff.lines("a\nb", "a\nx\nb")
        val add = lines.first { it.kind == TextDiff.Kind.ADD }
        assertEquals("新增行 oldNo 为空", null, add.oldNo)
        assertEquals("新增行 newNo=2", 2, add.newNo)
        assertEquals("新增内容", "x", add.text)
    }

    check("D1-8 FileBackupStore.diffToCurrent 能算出「这次改了什么」") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bk"), ws)
            val f = File(ws, "d.txt")
            f.writeText("old line\nkeep\n")
            val id = store.backupBeforeWrite("d.txt")!!
            // 模拟 Agent 写入新内容
            f.writeText("new line\nkeep\nadded\n")
            val diff = store.diffToCurrent(id)
            assertTrue("应拿到 diff", diff != null)
            assertEquals("新增 2 行（new line + added）", 2, diff!!.stats.added)
            assertEquals("删除 1 行（old line）", 1, diff.stats.removed)
            assertTrue("当前文件还在", !diff.currentMissing)
        }
    }

    check("D1-9 编辑器提供「看改动」入口与差异弹层") {
        val screen = read("app/src/main/java/com/beichen/polaris/ui/EditorScreen.kt")
        assertTrue("应有看改动按钮", "\"看改动\"" in screen)
        assertTrue("应有差异弹层", "BackupDiffDialog" in screen)
        assertTrue("应调用 diffToCurrent", "diffToCurrent(" in screen)
    }

    /* ------------------------- D3 版本矩阵探测 ------------------------- */

    check("D3-1 Alpine 3.21 的 node 候选只有 22 可精确装（官方没有 nodejs18 分包）") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APK, "3.21.3")
        val vs = ToolchainInstaller.knownVersions(
            ToolchainInstaller.Lang.NODE, ToolchainInstaller.PkgManager.APK, d
        )
        val exact = vs.filter { it.installable && it.exact }.map { it.version }
        assertEquals("只有 22 能精确装", listOf("22"), exact)
        assertTrue("18/20 必须明确标装不到", vs.filter { it.version != "22" }.none { it.installable })
        assertTrue("装不到的项必须给原因", vs.filter { !it.installable }.all { it.note.isNotBlank() })
    }

    check("D3-2 Debian 12 的 node 是 18（bookworm 默认版），不是「一个都装不上」") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APT, "12.11")
        val vs = ToolchainInstaller.knownVersions(
            ToolchainInstaller.Lang.NODE, ToolchainInstaller.PkgManager.APT, d
        )
        val exact = vs.filter { it.installable && it.exact }.map { it.version }
        assertEquals("bookworm 官方源就是 18", listOf("18"), exact)
        assertTrue("每项都要有说明", vs.all { it.note.isNotBlank() })
    }

    check("D3-3 Alpine 的 java 给出 17/21/11") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APK, "3.20.3")
        val vs = ToolchainInstaller.knownVersions(
            ToolchainInstaller.Lang.JAVA, ToolchainInstaller.PkgManager.APK, d
        ).filter { it.installable }.map { it.version }
        assertEquals("java 候选", listOf("17", "21", "11"), vs)
    }

    check("D3-4 候选版本表与 resolveVersion 的结论一致（列出的版本都能解析出真实包名）") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APK, "3.20.3")
        val vs = ToolchainInstaller.knownVersions(
            ToolchainInstaller.Lang.JAVA, ToolchainInstaller.PkgManager.APK, d
        ).filter { it.installable && it.exact }
        vs.forEach { v ->
            val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.JAVA, v.version, d)
            assertTrue("${v.version} 应能解析出包名", spec != null && spec.apkPackages.isNotEmpty())
            assertTrue("${v.version} 解析结论应与候选表一致（可装）", spec!!.apkAvailable)
        }
    }

    check("D3-6 Debian 12 没有 openjdk-21-jdk（bookworm 只带 17）") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APT, "12.11")
        val vs = ToolchainInstaller.knownVersions(
            ToolchainInstaller.Lang.JAVA, ToolchainInstaller.PkgManager.APT, d
        )
        val exact = vs.filter { it.installable && it.exact }.map { it.version }
        assertEquals("bookworm 只有 17", listOf("17"), exact)
        assertTrue("21 必须标装不到", vs.first { it.version == "21" }.installable.not())
    }

    check("D3-5 工具暴露 versions 动作并声明「以实际为准」") {
        val tool = read("app/src/main/java/com/beichen/polaris/tools/ContainerToolchainTool.kt")
        assertTrue("应有 versions 分支", "\"versions\" ->" in tool)
        assertTrue("应给出候选列表方法", "knownVersions(" in tool)
        assertTrue("应说明不是实时查仓库", "不是实时查仓库" in tool)
    }

    /* ------------------------- C2 续跑与运行态推进 ------------------------- */

    check("C2-1 advance 真实推进步数与工具列表") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "改一下 README",
                    status = AgentRunState.STATUS_RUNNING
                )
            )
            store.advance("read_file")
            store.advance("write_file")
            val s = store.load()
            assertTrue("应能读到状态", s != null)
            assertEquals("步数应为 2", 2, s!!.stepIndex)
            assertEquals("工具列表应为 2 项", 2, s.doneTools.size)
            assertEquals("工具名应记录", "write_file", s.doneTools[1])
        }
    }

    check("C2-2 非 running 状态不推进（done/interrupted 保持原样）") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "x",
                    stepIndex = 5,
                    status = AgentRunState.STATUS_DONE
                )
            )
            assertTrue("done 状态不应推进", store.advance("read_file") == null)
            assertEquals("步数应不变", 5, store.load()!!.stepIndex)
        }
    }

    check("C2-3 reconcile 后状态为 interrupted 且提示含步数与工具") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "重构这个模块",
                    stepIndex = 3,
                    status = AgentRunState.STATUS_RUNNING,
                    doneTools = listOf("read_file", "grep", "write_file")
                )
            )
            val rec = store.reconcile()
            assertTrue("对账应返回中断状态", rec != null)
            assertEquals("状态应为 interrupted", AgentRunState.STATUS_INTERRUPTED, rec!!.status)
            assertTrue("提示应含步数", rec.note.contains("第 3 步"))
            assertTrue("提示应含工具名", rec.note.contains("write_file"))
            assertTrue("提示应提到可以说继续", rec.note.contains("继续"))
        }
    }

    check("C2-4 buildResumeContext 给出模型可用的进度摘要") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "把测试补齐",
                    stepIndex = 2,
                    status = AgentRunState.STATUS_RUNNING,
                    doneTools = listOf("read_file", "write_file")
                )
            )
            store.reconcile()
            val ctx = store.buildResumeContext()
            assertTrue("应能生成续跑上下文", ctx != null)
            assertTrue("应含原始请求", ctx!!.contains("把测试补齐"))
            assertTrue("应含已完成步数", ctx.contains("2"))
            assertTrue("应含工具链", ctx.contains("read_file → write_file"))
            assertTrue("应要求不要重复劳动", ctx.contains("不要重复"))
        }
    }

    check("C2-5 非 interrupted 状态不生成续跑上下文") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "x",
                    status = AgentRunState.STATUS_RUNNING
                )
            )
            assertTrue("running 不应生成续跑上下文", store.buildResumeContext() == null)
        }
    }

    check("C2-6 markResumed 消费掉中断状态") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "x",
                    status = AgentRunState.STATUS_RUNNING
                )
            )
            store.reconcile()
            assertTrue("应能标记续跑", store.markResumed())
            assertEquals("状态应变回 running", AgentRunState.STATUS_RUNNING, store.load()!!.status)
            assertTrue("续跑后不再生成上下文", store.buildResumeContext() == null)
            assertTrue("重复标记应失败", !store.markResumed())
        }
    }

    check("C2-7 ChatViewModel 接入续跑：识别意图 + 拼上下文 + 保留上次进度") {
        val vm = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
        assertTrue("应识别续跑意图", "isResumeIntent" in vm)
        assertTrue("应生成续跑上下文", "buildResumeContext" in vm)
        assertTrue("应在工具完成时推进运行态", "advanceRunState" in vm)
        assertTrue("续跑时应保留上次 prompt", "priorState" in vm)
        assertTrue("工具完成事件应推进步数", "advanceRunState(ev.message.name)" in vm)
    }

    /* ------------------------- C3 指定版本号安装 ------------------------- */

    check("C3-1 node 指定版本不再拼出不存在的 nodejs18 包") {
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18")
        assertTrue("应能解析", spec != null)
        assertTrue("包名必须是真实存在的 nodejs", spec!!.apkPackages.contains("nodejs"))
        assertTrue(
            "绝不能拼 nodejs18/nodejs20（官方仓库没有这种分包）",
            spec.apkPackages.none { it.matches(Regex("^nodejs[0-9]+$")) }
        )
        assertEquals("版本应记录", "18", spec.version)
    }

    check("C3-2 python 指定版本的包名真实存在（pip 不跟着小版本走）") {
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.PYTHON, "3.11")
        assertTrue("应能解析", spec != null)
        assertTrue("Alpine 包名应含 python3", spec!!.apkPackages.contains("python3"))
        assertTrue("Debian 包名应含 python3.11", spec.aptPackages.contains("python3.11"))
        assertTrue("pip 包名是 python3-pip", spec.aptPackages.contains("python3-pip"))
        assertTrue(
            "不存在 python3.11-pip（旧实现会拼出这个不存在的包）",
            spec.aptPackages.none { it.endsWith("-pip") && it != "python3-pip" }
        )
    }

    check("C3-3 java 指定版本映射到 openjdk-N") {
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.JAVA, "17")
        assertTrue("应能解析", spec != null)
        assertTrue("Alpine 包名应含 openjdk17", spec!!.apkPackages.contains("openjdk17"))
        assertTrue("Debian 包名应含 openjdk-17-jdk", spec.aptPackages.contains("openjdk-17-jdk"))
    }

    check("C3-4 非法版本号被拒（含字母/符号/空）") {
        assertTrue("带字母应拒", ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18; rm -rf /") == null)
        assertTrue("带空格应拒", ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "1 8") == null)
        assertTrue("空串应拒", ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "") == null)
        assertTrue("纯符号应拒", ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "..") == null)
    }

    check("C3-5 指定版本时安装命令用真实包名") {
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18")!!
        val apk = ToolchainInstaller.installCommand(ToolchainInstaller.Lang.NODE, ToolchainInstaller.PkgManager.APK, spec)
        assertTrue("apk 命令应含 nodejs", apk!!.contains("nodejs"))
        assertTrue("apk 命令不应含 nodejs18", !apk.contains("nodejs18"))
        val apt = ToolchainInstaller.installCommand(ToolchainInstaller.Lang.NODE, ToolchainInstaller.PkgManager.APT, spec)
        assertTrue("apt 命令应先 update", apt!!.startsWith("apt-get update"))
    }

    check("C3-6 不指定版本时行为与原来一致（默认包名）") {
        val apk = ToolchainInstaller.installCommand(ToolchainInstaller.Lang.NODE, ToolchainInstaller.PkgManager.APK)
        assertTrue("应含默认 nodejs", apk!!.contains("nodejs"))
        assertTrue("不应含版本后缀", !apk.contains("nodejs18"))
    }

    check("C3-7 工具暴露 version 参数且非法版本明确报错") {
        val tool = read("app/src/main/java/com/beichen/polaris/tools/ContainerToolchainTool.kt")
        assertTrue("参数表应含 version", "\"version\"" in tool)
        assertTrue("应调用 resolveVersion", "resolveVersion" in tool)
        assertTrue("非法版本应有专门错误码", "bad_version" in tool)
        assertTrue("应仍用软转换读取 version", "primOrNull" in tool)
    }

    /* ------------------------- E 发行版版本可得性 ------------------------- */

    check("E-1 从 rootfs 识别 Alpine 版本线") {
        tmpDir { dir ->
            File(dir, "sbin").mkdirs()
            File(dir, "sbin/apk").writeText("")
            File(dir, "etc").mkdirs()
            File(dir, "etc/alpine-release").writeText("3.21.3\n")
            val d = ToolchainInstaller.detect(dir)
            assertEquals("包管理器", ToolchainInstaller.PkgManager.APK, d.pkgManager)
            assertEquals("版本线", "3.21", d.distro?.line)
        }
    }

    check("E-2 从 rootfs 识别 Debian 主版本") {
        tmpDir { dir ->
            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/apt-get").writeText("")
            File(dir, "etc").mkdirs()
            File(dir, "etc/debian_version").writeText("12.11\n")
            val d = ToolchainInstaller.detect(dir)
            assertEquals("版本线", "12", d.distro?.line)
        }
    }

    check("E-3 Alpine 3.21 上要 node 18：判定装不到，并说清该线是 22") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APK, "3.21.3")
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18", d)!!
        assertTrue("应判定不可装", !spec.availableOn(ToolchainInstaller.PkgManager.APK))
        assertTrue("说明应指出实际是 22", "22" in spec.note)
    }

    check("E-4 Alpine 3.20 上要 node 18：同样装不到（该线是 20）") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APK, "3.20.3")
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18", d)!!
        assertTrue("应判定不可装", !spec.availableOn(ToolchainInstaller.PkgManager.APK))
        assertTrue("说明应指出实际是 20", "20" in spec.note)
    }

    check("E-5 Debian bookworm 上要 node 18：可装") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APT, "12.11")
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18", d)!!
        assertTrue("应判定可装", spec.availableOn(ToolchainInstaller.PkgManager.APT))
    }

    check("E-6 Debian bookworm 上要 java 21：装不到（该发行版只带 17）") {
        val d = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APT, "12.11")
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.JAVA, "21", d)!!
        assertTrue("应判定不可装", !spec.availableOn(ToolchainInstaller.PkgManager.APT))
        assertTrue("说明应指出只有 17", "17" in spec.note)
    }

    check("E-7 发行版版本未知时不误拦（只提示无法确认）") {
        val spec = ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18", null)!!
        assertTrue("不应硬判装不到", spec.availableOn(ToolchainInstaller.PkgManager.APK))
        assertTrue("必须提示无法确认", spec.note.isNotBlank())
    }

    check("E-8 工具在装不到时返回 version_unavailable，而不是硬装") {
        val tool = read("app/src/main/java/com/beichen/polaris/tools/ContainerToolchainTool.kt")
        assertTrue("应有 version_unavailable", "version_unavailable" in tool)
        assertTrue(
            "应把发行版传给 resolveVersion",
            "resolveVersion(lang, version, detection.distro)" in tool
        )
    }

    check("E-9 版本表覆盖 Alpine 3.18 / 3.23 的实际版本") {
        val a318 = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APK, "3.18.6")
        val a323 = ToolchainInstaller.Distro(ToolchainInstaller.PkgManager.APK, "3.23.0")
        assertTrue(
            "3.18 应可装 node 18",
            ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "18", a318)!!
                .availableOn(ToolchainInstaller.PkgManager.APK)
        )
        assertTrue(
            "3.23 应可装 node 24",
            ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "24", a323)!!
                .availableOn(ToolchainInstaller.PkgManager.APK)
        )
        assertTrue(
            "3.23 不该声称能装 node 22",
            !ToolchainInstaller.resolveVersion(ToolchainInstaller.Lang.NODE, "22", a323)!!
                .availableOn(ToolchainInstaller.PkgManager.APK)
        )
    }

    check("E-10 安装失败能识别「容器没网」并说人话") {
        val tool = read("app/src/main/java/com/beichen/polaris/tools/ContainerToolchainTool.kt")
        assertTrue("应有病因诊断函数", "diagnoseInstallFailure" in tool)
        assertTrue("应识别域名解析失败", "temporary failure resolving" in tool)
        assertTrue("应指出容器需要能访问软件源", "容器内没有可用网络" in tool)
        assertTrue("应覆盖「仓库里没这个包」", "has no installation candidate" in tool)
    }

    check("E-11 抽屉菜单与设置必须同处一个滚动区（拆开会把菜单挤成 0 高）") {
        val s = read("app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt")
        val fn = s.indexOf("private fun DrawerContent(")
        // W-66：滚动状态从 rememberScrollState() 换成 rememberSaveable 版，
        // 断言只锁「抽屉里有垂直滚动、且底部设置在滚动区内」，不锁具体构造。
        val scroll = s.indexOf("verticalScroll(", fn)
        val bottom = s.indexOf("BottomGroup.forEach", fn)
        assertTrue("抽屉应有滚动区", scroll > fn)
        assertTrue("设置项必须在同一个滚动区内", bottom > scroll)
        // 曾经试图把设置「固定到底部」，用 weight(1f) 拆成两层 Column，
        // 结果在真机上整段菜单测量成 0 高，抽屉里只剩一个设置项。
        // 小屏裁切的代价远小于菜单消失，这里锁死为单滚动区。
        assertTrue(
            "不要在抽屉里用 weight 拆两层（真机上会让菜单高度归零）",
            "weight(1f)\n            .verticalScroll" !in s
        )
    }

    // ---------- F. 技能能力表与工具清单对账（P3-6） ----------
    //
    // `CapabilityRegistry.cap()` 的注释一直写着「新增工具必须显式登记，harness 会扫描
    // 全部工具名守住这条」——但这句话此前**没有任何东西在守**：全套 harness 里只有
    // B4-8 断言了 `container_toolchain` 一个工具。
    //
    // 漏登记的危害不在安全侧（fail-closed，未知即 DANGEROUS），而在可解释性：
    // 一个只读的新工具忘了登记，表现是 imported 技能调它时收到「无权调用 xxx（DANGEROUS）」，
    // 而它明明只读。查这个问题要从「技能为什么被拒」倒推回这张表，中间没有线索。
    check("F-1 能力表覆盖清单里的全部工具（漏登记会静默降级成 DANGEROUS）") {
        val problems = CapabilityRegistry.audit(CapabilityManifest.toolNames)
        assertTrue(
            "清单 ${CapabilityManifest.toolNames.size} 个工具应全部登记，实际差异：$problems",
            problems.isEmpty()
        )
    }

    check("F-2 对账能发现漏登记（反向：给一个不存在的工具名必须报出来）") {
        val problems = CapabilityRegistry.audit(CapabilityManifest.toolNames + "ghost_tool")
        assertTrue("应报出 ghost_tool，实际：$problems", problems.any { "ghost_tool" in it })
    }

    check("F-3 对账能发现死条目（反向：工具从清单移除后必须报出来）") {
        val problems = CapabilityRegistry.audit(CapabilityManifest.toolNames - "read_file")
        assertTrue("应报出 read_file 成了死条目，实际：$problems", problems.any { "read_file" in it })
    }

    // 口径：元工具（__use_tools / __find_tools）与 MCP 动态工具不受技能白名单约束，
    // 既不进清单也不进能力表。写死这条，免得下次有人「顺手补齐」把噪声引进来。
    check("F-4 元工具不进清单也不进能力表（否则每次对账都有固定噪声）") {
        assertTrue("清单不该含元工具", CapabilityManifest.toolNames.none { it.startsWith("__") })
        assertTrue("能力表不该含元工具", CapabilityRegistry.knownTools.none { it.startsWith("__") })
    }

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
