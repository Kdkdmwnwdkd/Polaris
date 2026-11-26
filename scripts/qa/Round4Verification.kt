// 真机第四轮实测发现的缺陷：验证（纯逻辑 + 源码结构）
//
// 本轮只修了一条：**空回复沉默失败**——模型返回 200 但 content 为空时，
// `ChatViewModel` 里「内容为空就不出气泡」的判断把最终轮也跳过了，
// 于是用户发了消息、界面一片空白，分不清是没发出去 / 模型抽风 / 还在等。
//
// 断言分三层：
//   1. 文案本身对不对（两种情形必须分开说，都要给下一步动作，都不能带技术噪音）
//   2. ChatViewModel 真的接了这个兜底（源码结构断言——ViewModel 起不来，测不了行为）
//   3. 兜底既上屏又落库（只上屏的话，切会话/重启就没了，等于没提示）

import com.beichen.polaris.ui.emptyReplyHint
import java.io.File

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        println("PASS  $name")
        pass++
    } catch (e: Throwable) {
        println("FAIL  $name → ${e.message}")
        fail++
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertContains(label: String, text: String, sub: String) {
    assertTrue("$label 应包含「$sub」，实际文案：$text", text.contains(sub))
}

private fun assertNotContains(label: String, text: String, sub: String) {
    assertTrue("$label 不应包含「$sub」，实际文案：$text", !text.contains(sub))
}

/* ------------------------------ 源码定位 ------------------------------ */

// 与 CrashLogVerification 一致：从 CWD 向上找含 app/src/main/java 的目录，
// 不依赖调用时的 CWD（CI 与本地调用位置不同）。
private val REPO_ROOT: File = run {
    var d: File? = File("").absoluteFile
    while (d != null) {
        if (File(d, "app/src/main/java").isDirectory) break
        d = d.parentFile
    }
    d ?: File("").absoluteFile
}

private val SRC_ROOT: File = File(REPO_ROOT, "app/src/main/java/com/beichen/polaris")

private fun read(rel: String): String {
    val f = File(SRC_ROOT, rel)
    if (!f.exists()) throw AssertionError("缺少源码文件：${f.path}")
    return f.readText()
}

/* ------------------------------ 断言 ------------------------------ */

fun main() {
    println("=== 真机第四轮 · 空回复兜底验证 ===")

    val noTool = emptyReplyHint(hasToolMessage = false)
    val hadTool = emptyReplyHint(hasToolMessage = true)

    check("R4-1 无工具时文案说明「空内容」（用户要知道到底发生了什么）") {
        assertContains("无工具文案", noTool, "空内容")
    }

    check("R4-2 无工具时不能说成「执行了工具」（会误导用户去找不存在的副作用）") {
        assertNotContains("无工具文案", noTool, "执行了工具")
    }

    check("R4-3 有工具时文案说明「执行了工具、但没文字总结」") {
        assertContains("有工具文案", hadTool, "执行了工具")
        assertContains("有工具文案", hadTool, "文字总结")
    }

    check("R4-4 有工具时不能说成「没有工具调用」（与事实相反）") {
        assertNotContains("有工具文案", hadTool, "没有工具调用")
    }

    check("R4-5 两种情形必须给不同文案（同一句话说明没区分，用户无从判断该做什么）") {
        assertTrue("两句文案相同：$noTool", noTool != hadTool)
    }

    check("R4-6 两句都要给出下一步动作，且**用界面上真实显示的按钮名**（「重新生成」）") {
        // 首版这里写的是「重试」——那是错误气泡上的标签，空回复气泡是普通助手气泡，
        // 显示的是「重新生成」。文案提控件就必须用控件上的真实文字，否则用户去找一个不存在的按钮。
        assertContains("无工具文案", noTool, "重新生成")
        assertContains("有工具文案", hadTool, "重新生成")
        assertNotContains("无工具文案", noTool, "「重试」")
        assertNotContains("有工具文案", hadTool, "「重试」")
    }

    check("R4-7 文案不带技术噪音（异常类名 / null / 状态码——用户看不懂也没法处理）") {
        listOf("Exception", "null", "HTTP", "stacktrace", "500").forEach { noise ->
            assertNotContains("无工具文案", noTool, noise)
            assertNotContains("有工具文案", hadTool, noise)
        }
    }

    val vm = read("ui/ChatViewModel.kt")

    check("R4-8 ChatViewModel 在 lastAnswer == null 时补提示气泡") {
        val at = vm.indexOf("if (lastAnswer == null)")
        assertTrue("ChatViewModel 找不到 `if (lastAnswer == null)` 兜底分支——空回复又会静默失败", at >= 0)
        val window = vm.substring(at, minOf(at + 900, vm.length))
        assertContains("兜底分支", window, "emptyReplyHint")
    }

    check("R4-9 兜底气泡既要上屏也要落库（只上屏的话切会话就消失）") {
        // 2026-11-26：六处「守卫 + 上屏 + 落盘」收敛成了 emitTo()（f0b2a0b），
        // 兜底分支不再直接出现 appendToStore——它调 emitTo，由那一个函数保证两件事都做。
        // 判据因此拆成两半，比原来更严：调用点必须走 emitTo，且 emitTo 体内两件事都在。
        val at = vm.indexOf("if (lastAnswer == null)")
        val window = vm.substring(at, minOf(at + 900, vm.length))
        assertContains("兜底分支", window, "emitTo(")
        val emitAt = vm.indexOf("private fun emitTo(")
        assertTrue("ChatViewModel 找不到 emitTo 定义——守卫点又散回调用处了", emitAt >= 0)
        val emitBody = vm.substring(emitAt, minOf(emitAt + 400, vm.length))
        assertContains("emitTo", emitBody, "appendUiItem")
        assertContains("emitTo", emitBody, "appendToStore")
    }

    check("R4-10 兜底要留日志（否则线上只知道「没回复」，查不到是空回还是被截断）") {
        val at = vm.indexOf("if (lastAnswer == null)")
        val window = vm.substring(at, minOf(at + 900, vm.length))
        assertContains("兜底分支", window, "AgentLogger")
    }

    check("R4-11 「内容为空不出气泡」的原判断仍在（中间轮只有 toolCalls 时不能出空气泡）") {
        assertContains("ChatViewModel", vm, "if (msg.content.isNotBlank())")
    }

    println()
    println("== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
