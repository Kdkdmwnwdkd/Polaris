#!/usr/bin/env kotlin
/**
 * 启动自救（StartupGuard）行为验证。
 *
 * ## 为什么这条必须真跑
 *
 * 它只在「用户已经被锁在门外」的那一刻执行——开发机上永远不会自然触发。
 * 靠 grep 只能证明函数存在，证明不了「连续三次没起来时它真的搬了、而且没把日志搬走」。
 * 所以这里直接编译真实的 [StartupGuardPolicy] / [StartupQuarantine] 跑一遍。
 *
 * ## 覆盖
 *  S1 计数：没崩 → 0；崩一次 → 1；连续崩 → 递增
 *  S2 同毫秒：两次启动落在同一毫秒仍算崩（这是 2026-11-26 修掉的真 bug）
 *  S3 时间窗：隔了 10 分钟再打开，不算「上次崩了」
 *  S4 成功过：markBooted 之后 booted=true，下一次不算崩
 *  S5 隔离范围：业务状态搬走，logs/ 与 crash/ **必须留在原地**
 *  S6 隔离结构：目录连内容一起搬、带 README
 *  S7 空目录：没有可搬的东西时返回 null（不是「建个空目录算成功」）
 *  S8 名单：MOVABLE 里不许出现 logs / crash
 *  S9 阈值：shouldQuarantine 的边界正好在 MAX_STRIKES
 *
 * 运行：scripts/qa/run-startup-guard-verification.sh
 */

import com.beichen.polaris.startup.StartupGuardPolicy
import com.beichen.polaris.startup.StartupQuarantine
import java.io.File

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name -> ${t.message}")
    }
}

private fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 $expected，实际 $actual")
}

/** 造一份「典型用户目录」：业务状态 + 日志 + 崩溃报告。 */
private fun fixture(name: String): File {
    val d = File("/tmp/qa-startup-guard/$name")
    d.deleteRecursively()
    d.mkdirs()
    File(d, "conversations.json").writeText("{}")
    File(d, "conversations.d").apply { mkdirs(); File(this, "abc.json").writeText("{}") }
    File(d, "agent_run_state.json").writeText("{}")
    File(d, "drafts.json").writeText("{}")
    File(d, "logs").apply { mkdirs(); File(this, "agent.log").writeText("x") }
    File(d, "crash").apply { mkdirs(); File(this, "crash-1.txt").writeText("stack") }
    return d
}

fun main() {
    println("=== 启动自救（StartupGuard）===")
    val now = 1_000_000_000_000L

    check("S1 计数：没崩归零、崩一次记 1、连续崩递增") {
        eq(StartupGuardPolicy.nextStrikes(0, 0L, false, now), 0, "首次启动（无历史）")
        eq(StartupGuardPolicy.nextStrikes(0, now - 1000, false, now), 1, "上次崩了")
        eq(StartupGuardPolicy.nextStrikes(1, now - 1000, false, now), 2, "连续第二次崩")
    }

    check("S2 同毫秒：两次启动落在同一毫秒仍算崩") {
        eq(StartupGuardPolicy.nextStrikes(0, now, false, now), 1, "同毫秒也不许漏判")
    }

    check("S3 时间窗：隔 10 分钟再打开不算上次崩了") {
        val old = now - 10 * 60_000L
        eq(StartupGuardPolicy.nextStrikes(1, old, false, now), 0, "超出窗口应重置")
        eq(StartupGuardPolicy.nextStrikes(1, now - StartupGuardPolicy.FRESH_MS, false, now), 2, "窗口边界内仍算（在 prev 基础上加一）")
        eq(StartupGuardPolicy.nextStrikes(1, now + 5000, false, now), 0, "时钟回拨不该判崩")
    }

    check("S4 成功过：booted=true 时下一次不算崩") {
        eq(StartupGuardPolicy.nextStrikes(1, now - 1000, true, now), 0, "上次界面出来了")
    }

    check("S5 隔离范围：业务状态搬走，logs/ 与 crash/ 留在原地") {
        val d = fixture("s5")
        val res = StartupQuarantine.move(d, now)
        expect(res != null, "应当返回隔离结果")
        eq(res!!.moved.size, 4, "应搬走 4 项业务状态")
        expect(!File(d, "conversations.json").exists(), "会话索引应被移走")
        expect(!File(d, "conversations.d").exists(), "会话分片应被移走")
        expect(!File(d, "agent_run_state.json").exists(), "运行态应被移走")
        expect(!File(d, "drafts.json").exists(), "草稿应被移走")
        expect(File(d, "logs/agent.log").exists(), "日志必须留在原地——那是排障证据")
        expect(File(d, "crash/crash-1.txt").exists(), "崩溃报告必须留在原地")
    }

    check("S6 隔离结构：目录连内容一起搬，且有 README") {
        val d = fixture("s6")
        val dir = StartupQuarantine.move(d, now)!!.dir
        expect(File(dir, "conversations.d/abc.json").exists(), "分片应连目录一起搬")
        expect(File(dir, "README.txt").exists(), "应有给人看的说明")
    }

    check("S7 空目录：没有可搬的东西时返回 null") {
        val d = File("/tmp/qa-startup-guard/s7").apply { deleteRecursively(); mkdirs() }
        eq(StartupQuarantine.move(d, now), null, "什么都不该搬")
    }

    check("S8 名单：MOVABLE 里不许出现日志与崩溃目录") {
        expect(StartupQuarantine.MOVABLE.none { it == "logs" || it == "crash" }, "名单里混进了日志/崩溃目录")
    }

    check("S9 阈值：边界正好在 MAX_STRIKES") {
        eq(StartupGuardPolicy.shouldQuarantine(StartupGuardPolicy.MAX_STRIKES - 1), false, "差一次不该动手")
        eq(StartupGuardPolicy.shouldQuarantine(StartupGuardPolicy.MAX_STRIKES), true, "到阈值应动手")
    }

    println()
    println("=== $pass 通过 / $fail 失败 ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
