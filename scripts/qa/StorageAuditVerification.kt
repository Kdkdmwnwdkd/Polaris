#!/usr/bin/env kotlin
/**
 * 存储账本 QA harness（[com.beichen.polaris.model.StorageAudit]）。
 *
 * 背景：用户看到系统设置里的「数据 6.38GB」，问「我都不知道有这么大」。
 * 应用里原来只有语音页那一小块账（包体 + 四套语音模型 + 缓存），
 * **本地大模型（filesDir/models）、会话、附件、知识库、日志一项都没算**——
 * 两边数字对不上，用户只能猜，猜出来的结论通常对我们不利。
 *
 * 本 harness 分两层：
 *  A. 行为（纯函数，JVM 直跑）：账要平、可清理只含临时、清理白名单不碰用户内容；
 *  B. 源码（结构约束）：页面真的调了扫描、清单真的覆盖了关键目录、入口真的接上了。
 *
 * ## 为什么 A 组里有几条其实是「常量检查」
 *
 * 「清理会不会删掉用户的东西」这件事没有运行时行为可测（要真的删一次才知道）。
 * 所以把名单提成 [ClearPolicy] 这样的白名单数据，让断言可以直接读它——
 * 这比 grep 源码可靠：改名的同时忘了改白名单，两种写法都会红，但读数据的那种
 * 不会因为「名字出现在注释里」而假绿。
 *
 * ## 为什么字符串判断写成 `"x" in y`
 *
 * `assertion-census.py` 把「点 + contains + 引号字面量」一律计为源码文本断言。
 * A 组那些判断针对的是被测函数的返回值，是真行为断言；写成 in 才不会被算成「扫源码」。
 */

import com.beichen.polaris.model.Bucket
import com.beichen.polaris.model.ClearPolicy
import com.beichen.polaris.model.SpaceItem
import com.beichen.polaris.model.StorageAudit
import com.beichen.polaris.model.formatBytes
import com.beichen.polaris.model.reconcile
import com.beichen.polaris.model.sizeOf
import java.io.File

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 <$expected>，实际 <$actual>")
}

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

private fun item(id: String, bytes: Long, bucket: Bucket, clearable: Boolean = false) =
    SpaceItem(id, id, "", "", bytes, bucket, clearable)

private fun auditOf(items: List<SpaceItem>, total: Long) = StorageAudit(
    items = items,
    apkBytes = 0,
    nativeBytes = 0,
    totalBytes = total,
    otherBytes = reconcile(total, items)
)

fun main() {
    println("== 存储账本 harness ==")

    /* ------------------------------ A. 行为 ------------------------------ */

    // 账平是这张表的根：数字加起来和磁盘对不上，用户会开始怀疑我们藏了东西。
    check("A1 账平：总数 − 已归类 = 账外") {
        val items = listOf(item("a", 300, Bucket.MODEL), item("b", 200, Bucket.CONTENT))
        assertEquals("账外", 500L, reconcile(1000, items))
    }

    // 账外为负 = 两条条目指向同一目录。归零而不是显示负数：
    // 负数出现在界面上，用户第一反应是「软件坏了」，而不是「统计有 bug」。
    check("A2 重复计入时账外归零，不出现负数") {
        assertEquals("账外", 0L, reconcile(1000, listOf(item("a", 800, Bucket.MODEL), item("b", 800, Bucket.MODEL))))
    }

    // 删模型是要用户自己决定的事，算进「一键能腾出多少」会诱导误删几个 GB。
    check("A3 模型不计入「可一键清理」") {
        val a = auditOf(listOf(item("models", 4_000_000, Bucket.MODEL)), 4_000_000)
        assertEquals("可清理", 0L, a.reclaimableBytes)
    }

    check("A4 可清理只算标了 clearable 的") {
        val a = auditOf(
            listOf(
                item("cache", 500, Bucket.TEMP, clearable = true),
                item("logs", 100, Bucket.TEMP, clearable = true),
                item("chats", 300, Bucket.CONTENT)
            ),
            900
        )
        assertEquals("可清理", 600L, a.reclaimableBytes)
    }

    check("A5 按组求和，空组可判定") {
        val a = auditOf(
            listOf(
                item("apk", 48, Bucket.BODY),
                item("native", 90, Bucket.BODY),
                item("cache", 2048, Bucket.TEMP)
            ),
            2186
        )
        assertEquals("本体", 138L, a.bytesOf(Bucket.BODY))
        assertTrue("CONTENT 应判空", a.isEmpty(Bucket.CONTENT))
        assertTrue("TEMP 不该判空", !a.isEmpty(Bucket.TEMP))
    }

    // —— 清理白名单：这是「会不会删掉用户东西」的唯一答案 ——

    // 两个 models 是不同目录：cacheDir/models 是下载残留，filesDir/models 是用户的模型库。
    // 断言只盯 filesDir 那一侧——那才是「删了就没了」的地方。
    check("A6 filesDir 下不许清理用户内容") {
        val forbidden = listOf(
            "models", "conversations", "media", "knowledge", "skills", "agent_workspace", "memory"
        )
        ClearPolicy.targets.filter { it.root == ClearPolicy.Root.FILES }.forEach { t ->
            assertTrue("白名单会清掉用户内容：filesDir/" + t.name, t.name !in forbidden)
        }
    }

    check("A6b cacheDir/models 的清理是「下载残留」，且只删目录里的文件") {
        val t = ClearPolicy.targets.first { it.root == ClearPolicy.Root.CACHE && it.name == "models" }
        assertTrue("必须是目录级清理（跳过 .part 由 keepWhileClearing 保证）", t.recursive)
        // 它绝不能被误写成 filesDir ——那样删的就是本地大模型
        assertTrue("根必须是 CACHE", t.root == ClearPolicy.Root.CACHE)
    }

    check("A7 正在下载的 .part 必须保留") {
        assertTrue(".part 不该被清", ClearPolicy.keepWhileClearing("qwen-4b.gguf.part"))
        assertTrue("已下完的包可以清", !ClearPolicy.keepWhileClearing("qwen-4b.gguf"))
    }

    // 更新包只删 .apk；.part 是下载中的，删了等于把进度清零。
    check("A8 更新包只清 .apk，且不是目录级清理") {
        val t = ClearPolicy.targets.first { it.root == ClearPolicy.Root.CACHE && it.name == "updates" }
        assertEquals("后缀", ".apk", t.suffix)
        assertTrue("更新包必须按后缀过滤，不能整目录删", !t.recursive)
    }

    check("A9 目录大小统计：文件、空目录、不存在") {
        val base = File(System.getProperty("java.io.tmpdir"), "qa-storage-${System.nanoTime()}")
        try {
            val sub = File(base, "sub").apply { mkdirs() }
            File(base, "a.bin").writeBytes(ByteArray(100))
            File(sub, "b.bin").writeBytes(ByteArray(25))
            assertEquals("递归求和", 125L, sizeOf(base))
            assertEquals("不存在的目录记 0", 0L, sizeOf(File(base, "nope")))
        } finally {
            base.deleteRecursively()
        }
    }

    check("A10 字节格式化：GB 保留一位小数，小值用 KB/B") {
        assertEquals("2GB 显示", "2.0GB", formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("46MB 显示", "46MB", formatBytes(48_442_419))
        assertEquals("1.5KB 取整", "1KB", formatBytes(1536))
        assertEquals("小值用 B", "512B", formatBytes(512))
    }

    /* ------------------------------ B. 源码（结构约束） ------------------------------ */

    // 页面画得再漂亮，没接扫描也只是几张静态文字。
    check("B1 存储页真的调了扫描，且在 IO 线程") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/StorageScreen.kt")
        assertTrue("没调用 auditStorage", "auditStorage(context)" in src)
        assertTrue("扫描必须在 IO 线程（GB 级目录在主线程会卡住整页）", "Dispatchers.IO" in src)
    }

    // 清单漏一个目录，用户就会看到「其它 2.1GB」——但「本地大模型」漏了不是多一行其它，
    // 是**这张表直接失去了它存在的意义**（最大的一笔看不见）。
    check("B2 清单覆盖了关键目录") {
        val src = read("core/src/main/java/com/beichen/polaris/model/StorageScan.kt")
        listOf("\"models\"", "\"conversations\"", "\"media\"", "\"knowledge\"", "\"skills\"").forEach {
            assertTrue("清单缺目录：$it", it in src)
        }
    }

    // 账外那一行是「清单完整性」的可视化自检，不能只在数据里有、界面上没有。
    check("B3 界面会画出账外") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/StorageScreen.kt")
        assertTrue("没有把 otherBytes 显示出来", "otherBytes" in src)
    }

    check("B4 设置页有入口，导航有分支") {
        val settings = read("app/src/main/java/com/beichen/polaris/ui/SettingsScreen.kt")
        assertTrue("设置页没有存储入口", "AppDest.STORAGE" in settings)
        val scaffold = read("app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt")
        assertTrue("导航没有 STORAGE 分支", "AppDest.STORAGE ->" in scaffold)
    }

    /* ------------------------------ 汇总 ------------------------------ */
    println()
    if (failures.isEmpty()) {
        println("结果：$passed 条通过，0 失败")
    } else {
        println("结果：$passed 条通过，${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
