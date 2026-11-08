import com.beichen.polaris.core.BackupArchive
import com.beichen.polaris.core.BackupManifest
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.skill.market.MarketIndex
import com.beichen.polaris.skill.market.MarketInstallState
import com.beichen.polaris.skill.market.MarketPlugin
import com.beichen.polaris.skill.market.MarketRules
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * W-47 / W-48 harness：安全与一致性 + 插件市场规则。
 *
 * 分两层：
 *  1. **行为测试**：市场规则（id/URL/版本/清洗）、JSON 软转换、备份解包限流——都是纯函数，
 *     直接构造输入断言输出，不依赖 Android；
 *  2. **结构断言**：备份不再带 PAT、消息写入统一加锁、全仓无硬 JSON 转换、市场路由已接——
 *     这些是「源码里必须有/必须没有某一行」的约束，源码扫描最可靠。
 *
 * 断言随产品变更同步更新：改上述实现时，先改这里再改代码。
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

private fun plugin(id: String, version: String = "1.0.0", url: String = "p.zip"): MarketPlugin =
    MarketPlugin(id = id, name = id, version = version, downloadUrl = url)

fun main() {
    println("== W-47/W-48 harness：安全一致性 + 插件市场 ==")

    /* ----------------------------- 市场规则 ----------------------------- */

    check("M1 技能 id 白名单：非法字符/空/超长一律拒绝") {
        assertTrue("合法 id 被拒", MarketRules.isValidId("weather.v2"))
        assertTrue("下划线被拒", MarketRules.isValidId("my_skill"))
        assertTrue("空格应拒", !MarketRules.isValidId("bad id"))
        assertTrue("路径穿越应拒", !MarketRules.isValidId("../etc"))
        assertTrue("空应拒", !MarketRules.isValidId(""))
        assertTrue("超长应拒", !MarketRules.isValidId("a".repeat(65)))
    }

    check("M2 只允许 http/https（挡 file: / content: / 无主机）") {
        assertTrue("https 被拒", MarketRules.validHttpUrl("https://example.com/a.json") != null)
        assertTrue("http 被拒", MarketRules.validHttpUrl("http://192.168.1.2:8080/i.json") != null)
        assertTrue("file: 未挡", MarketRules.validHttpUrl("file:///etc/passwd") == null)
        assertTrue("content: 未挡", MarketRules.validHttpUrl("content://media/1") == null)
        assertTrue("ftp 未挡", MarketRules.validHttpUrl("ftp://example.com/a") == null)
        assertTrue("无主机未挡", MarketRules.validHttpUrl("https:///a.json") == null)
    }

    check("M3 相对下载地址按索引地址解析成绝对地址") {
        assertEquals(
            "相对路径解析错",
            "https://cdn.example.com/market/plugins/weather.zip",
            MarketRules.resolveDownloadUrl("https://cdn.example.com/market/index.json", "plugins/weather.zip")
        )
        assertEquals(
            "根相对解析错",
            "https://cdn.example.com/x.zip",
            MarketRules.resolveDownloadUrl("https://cdn.example.com/market/index.json", "/x.zip")
        )
        assertTrue(
            "非法相对地址未拒",
            MarketRules.resolveDownloadUrl("https://a.com/i.json", "javascript:alert(1)") == null
        )
    }

    check("M4 版本比较：数值段按数值、缺段补零、忽略 v 前缀") {
        assertTrue("1.10 应大于 1.9", MarketRules.compareVersion("1.10.0", "1.9.9") > 0)
        assertEquals("补零应相等", 0, MarketRules.compareVersion("1.2", "1.2.0"))
        assertEquals("v 前缀应相等", 0, MarketRules.compareVersion("v1.2.3", "1.2.3"))
        assertTrue("低版本应小", MarketRules.compareVersion("1.0.0", "1.0.1") < 0)
        assertEquals("预发布视为同版本（有意简化）", 0, MarketRules.compareVersion("1.0.0-beta", "1.0.0"))
    }

    check("M5 安装态判定：未装 / 已装 / 可更新") {
        assertEquals("未装判定错", MarketInstallState.NOT_INSTALLED, MarketRules.stateOf("1.0.0", null))
        assertEquals("已装判定错", MarketInstallState.INSTALLED, MarketRules.stateOf("1.0.0", "1.0.0"))
        assertEquals("可更新判定错", MarketInstallState.UPDATE_AVAILABLE, MarketRules.stateOf("1.1.0", "1.0.9"))
    }

    check("M6 索引清洗：非法 id / 重复 id / 非法地址被丢弃，其余修正") {
        val raw = MarketIndex(
            name = "  测试市场  ",
            plugins = listOf(
                plugin("ok", "1.0.0", "a.zip"),
                plugin("ok", "2.0.0", "b.zip"),          // 重复 id → 丢
                plugin("bad id", "1.0.0", "c.zip"),      // 非法 id → 丢
                plugin("badurl", "1.0.0", "file:///x.zip"), // 非法协议 → 丢
                plugin("long", "1.0.0", "d.zip").copy(
                    description = "x".repeat(900),
                    sha256 = "NOT-A-HASH"
                )
            )
        )
        val clean = MarketRules.sanitizeIndex(raw, "https://example.com/market/index.json")
        assertEquals("条目数错", 2, clean.plugins.size)
        assertEquals("名字未 trim", "测试市场", clean.name)
        assertEquals("重名未保留首个", "1.0.0", clean.plugins.first { it.id == "ok" }.version)
        val long = clean.plugins.first { it.id == "long" }
        assertEquals("描述未截断", MarketRules.MAX_DESCRIPTION, long.description.length)
        assertEquals("非法 sha 未清空", "", long.sha256)
        assertEquals(
            "相对地址未补全",
            "https://example.com/market/d.zip",
            long.downloadUrl
        )
    }

    /* ----------------------------- JSON 软转换 ----------------------------- */

    check("M7 客户端与规则的包体上限一致（防止两处常量漂移）") {
        // 不引入 OkHttp 依赖，直接读源码确认常量值；规则侧用实际属性值断言
        val client = read("core/src/main/java/com/beichen/polaris/skill/market/MarketClient.kt")
        assertTrue("客户端未声明 8MB 上限", "const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024" in client)
        assertEquals("规则侧上限被改小/改大", 8L * 1024 * 1024, MarketRules.MAX_PACKAGE_BYTES)
    }

    check("J1 JsonNull 不再抛异常（hotfix33 同源）") {
        assertTrue("JsonNull 应为 null", JsonNull.objOrNull() == null)
        assertTrue("JsonNull 应为 null", JsonNull.arrOrNull() == null)
        assertTrue("JsonNull 应为 null", JsonNull.primOrNull() == null)
        val obj: JsonObject = buildJsonObject { put("a", "1") }
        assertEquals("正常对象取值错", "1", obj["a"].primOrNull()?.content)
    }

    /* ----------------------------- 备份安全 ----------------------------- */

    check("B1 备份解包：正常件可读、条数校验生效") {
        val manifest = BackupManifest(
            version = BackupArchive.VERSION,
            createdAt = 1L,
            conversationCount = 0,
            memoryCount = 0,
            hasConfig = false,
            promptCount = 0,
            hasPrompts = false
        )
        val bytes = BackupArchive.create(manifest, "[]", "[]", null)
        val read = BackupArchive.read(bytes)
        assertTrue("正常备份被拒：$read", read is BackupArchive.ReadResult.Ok)

        // 条数与清单不符 → 必须拒
        val badManifest = manifest.copy(conversationCount = 3)
        val bad = BackupArchive.create(badManifest, "[]", "[]", null)
        assertTrue("条数不符未拒", BackupArchive.read(bad) is BackupArchive.ReadResult.Invalid)
    }

    check("B2 备份解包：单条目超限直接拒（zip 炸弹防护）") {
        // 25MB 全 'a'（略高于 24MB 单条目上限）：压缩后只有几十 KB，靠总量检查发现不了，
        // 必须按「读的过程中计数」才会拒——这正是 W-47 要防的 zip 炸弹形态
        val huge = "\"" + "a".repeat(25 * 1024 * 1024) + "\""
        val manifest = BackupManifest(
            version = BackupArchive.VERSION,
            createdAt = 1L,
            conversationCount = 0,
            memoryCount = 0,
            hasConfig = false,
            promptCount = 0,
            hasPrompts = false
        )
        // 用正常打包路径造一个「只有 conversations 超大」的包：create 负责写 manifest，
        // 之后把该 entry 换成 9MB 文本（压缩后很小，正好绕过总量检查）
        val base = BackupArchive.create(manifest, huge, "[]", null)
        val bytes = base
        val read = BackupArchive.read(bytes)
        assertTrue("超大条目未被拒（实际 $read）", read is BackupArchive.ReadResult.Invalid)
    }

    /* ----------------------------- 源码结构 ----------------------------- */

    val vm = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
    val backup = read("core/src/main/java/com/beichen/polaris/core/BackupArchive.kt")
    val configStore = read("core/src/main/java/com/beichen/polaris/core/ConfigStore.kt")
    val scaffold = read("app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt")

    check("S1 备份不再导出 PAT；恢复时沿用本机 PAT") {
        // P4-3：导出流程在 BackupUseCase（VM 只转发），断言跟着所有权走
        val backupUseCase = read("app/src/main/java/com/beichen/polaris/ui/BackupUseCase.kt")
        assertTrue("仍在导出 PAT", "githubPat = null" in backupUseCase)
        assertTrue("恢复未兜底本机 PAT", "loadGithubConfig()?.pat" in configStore)
    }

    check("S2 备份解包按单条目限流（不是先读后验）") {
        assertTrue("缺单条目上限常量", "MAX_ENTRY_BYTES" in backup)
        assertTrue("缺限流读取函数", "readEntryLimited(" in backup)
        assertTrue("仍在用无上限 readBytes", "zip.readBytes()" !in backup)
    }

    check("S3 消息列表：读改写在锁内，无裸赋值残留") {
        assertTrue("缺统一追加入口", "private fun appendUiItem(item: UiItem)" in vm)
        assertTrue("缺整体替换入口", "private fun replaceUiItems(items: List<UiItem>)" in vm)
        // 允许**恰好一处**读改写：appendUiItem 内部那一行；且它必须被 synchronized(msgLock) 包住。
        // 注释里的示例不算（先剥注释行），其它任何裸写都视为回归。
        // P4-2：读改写本体在 ConversationProjection（唯一入口），VM 只转发；
        // 两边都要看：投影里必须恰好一处裸拼接且被 synchronized(lock) 包住，VM 里必须一处都没有。
        val proj = read("app/src/main/java/com/beichen/polaris/ui/ConversationProjection.kt")
        val bareLines = proj.lines().filter { line ->
            val t = line.trim()
            !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") &&
                "_items.value = _items.value +" in t
        }
        assertEquals("裸读改写处数（应只有 appendItem 内一处）", 1, bareLines.size)
        val pos = proj.indexOf(bareLines.first())
        val before = proj.substring(maxOf(0, pos - 400), pos)
        assertTrue("读改写没有包在 synchronized(lock) 里", "synchronized(lock)" in before)
        assertTrue("VM 里不得再有裸拼接", "_messages.value = _messages.value +" !in vm)
    }

    check("S4 全仓无 JSON 硬转换（JsonSafe 之外）") {
        val roots = listOf(
            "core/src/main/java/com/beichen/polaris/core",
            "app/src/main/java/com/beichen/polaris/tools",
            "app/src/main/java/com/beichen/polaris/automation",
            "core/src/main/java/com/beichen/polaris/skill",
            "app/src/main/java/com/beichen/polaris/memory",
            "app/src/main/java/com/beichen/polaris/ui"
        )
        val offenders = ArrayList<String>()
        roots.forEach { root ->
            File(root).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach forEachFile@{ f ->
                if (f.name == "JsonSafe.kt") return@forEachFile
                f.readLines().forEachIndexed { i, line ->
                    val t = line.trim()
                    if (t.startsWith("import ")) return@forEachIndexed
                    if (Regex("""\.(jsonObject|jsonArray|jsonPrimitive)\b""").containsMatchIn(t)) {
                        offenders += "${f.path}:${i + 1}: $t"
                    }
                }
            }
        }
        assertTrue("仍有硬转换：\n" + offenders.take(5).joinToString("\n"), offenders.isEmpty())
    }

    check("S5 插件市场已接入路由") {
        assertTrue("缺 MARKET 路由", "MARKET(\"market\"" in scaffold)
        assertTrue("缺市场页渲染", "AppDest.MARKET -> SkillMarketScreen(vm)" in scaffold)
        assertTrue("技能页缺入口", "onOpenMarket" in read("app/src/main/java/com/beichen/polaris/ui/SkillsScreen.kt"))
    }

    /* --------------------- A 批：信任层（能力表 / 白名单 / 审批） --------------------- */

    val security = read("core/src/main/java/com/beichen/polaris/skill/SkillSecurity.kt")
    val executor = read("core/src/main/java/com/beichen/polaris/skill/SkillExecutor.kt")

    // R-13：能力分级表从 `CapabilityRegistry` 搬到了 `core/tool/ToolCatalog.kt`
    // （组/能力/审批三张表合并成一张，见那边的 KDoc）。本用例的**判据不变**——
    // 仍是「默认 fail-closed」+「每个工具都有分级」——只是真源换了文件。
    // 读到的是数据文件而不是实现文件，这不是放宽：`capabilityOf` 的默认值就在
    // 同一个文件里，扫描它比扫描一个已经只剩委托的 `cap()` 更接近事实。
    val catalog = read("core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt")

    check("S6 能力分级 fail-closed：默认 DANGEROUS，且全仓工具 100% 登记") {
        // 旧实现是 `?: Capability.SAFE`，新工具忘登记就自动放行。
        assertTrue("capabilityOf() 默认不是 DANGEROUS", "?: Capability.DANGEROUS" in catalog)
        assertTrue("仍在默认 SAFE", "?: Capability.SAFE" !in catalog)

        val nameRe = Regex("override val name = \"([a-z0-9_]+)\"")
        val names = LinkedHashSet<String>()
        File("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { f ->
                nameRe.findAll(f.readText()).forEach { m -> names += m.groupValues[1] }
            }
        assertTrue("没有扫到任何工具名（扫描逻辑坏了）", names.size >= 50)
        val missing = names.filter { "CatalogTool(\"$it\"" !in catalog }
        assertTrue("未登记能力级的工具：${missing.joinToString()}", missing.isEmpty())
    }

    check("S7 技能白名单 fail-closed：空表不再等于全放行") {
        assertTrue("仍在用 isNotEmpty 旧判定", "tools.isNotEmpty() && name !in skill.tools" !in executor)
        assertTrue("未使用统一判定", "isToolAllowedByWhitelist(skill.tools" in executor)
        // R4 起审批判据从「工具级 needsApproval」细化为「参数级 needsApprovalFor(args)」
        // （shell_run 的 `ls` 无副作用不必拦，`rm -rf` 必须拦）。
        // 断言原先盯的是工具级字面量 `needsApproval == true`，
        // R4 之后它已不成立——这是**断言陈旧**而非产品缺陷：
        // 阻断行为由 needsApprovalFor 承担，且判据比工具级更细。
        assertTrue(
            "未用参数级审批判据阻断需确认工具",
            "needsApprovalFor(obj) == true" in executor
        )
        // 反向断言：不能退回工具级判据——那会把「参数级更细」的优势丢掉，
        // 且与主对话的审批口径不一致（两处判据必须同源）。
        assertTrue("退回了工具级审批判据", "target?.needsApproval == true" !in executor)
    }

    check("S8 高危工具必须挂人工审批（shell / 写文件 / 推仓库）") {
        val approvalSources = listOf(
            "app/src/main/java/com/beichen/polaris/tools/ShellTool.kt",
            "app/src/main/java/com/beichen/polaris/tools/ShellJobTools.kt",
            "app/src/main/java/com/beichen/polaris/tools/BuiltInTools.kt",
            "app/src/main/java/com/beichen/polaris/tools/GitHubGitTools.kt"
        )
        approvalSources.forEach { path ->
            assertTrue("$path 缺 needsApproval = true", "needsApproval = true" in read(path))
        }
    }

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
