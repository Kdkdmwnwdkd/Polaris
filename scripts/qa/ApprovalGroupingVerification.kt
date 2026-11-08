import com.beichen.polaris.core.agent.ApprovalGrouping
import com.beichen.polaris.core.tool.ToolCatalog
import java.io.File

/**
 * W-36 harness：审批「同类操作」归组。
 *
 * 真机事故背景：一次授权任务里 ui_tap / ui_click / ui_key / ui_input / ui_screenshot
 * 各弹一次窗，用户点了十几次「允许」，单次等待 28~37 秒。弹窗写着「不再询问同类操作」，
 * 但实现按工具名记账，勾了也只放过那一个工具。
 *
 * 断言目标：同类动作必须归到同一组；不同风险级别不能混组（截图不能顺带放行点击）。
 */
fun main() {
    var pass = 0
    fun check(name: String, cond: Boolean) {
        if (cond) {
            pass++
        } else {
            println("FAIL: $name")
            kotlin.system.exitProcess(1)
        }
    }

    // 1. 界面点按/输入/按键/滑动 = 同一组（用户视角就是「它在动我的屏幕」）
    val uiActions = listOf("ui_tap", "ui_click", "ui_long_press", "ui_swipe", "ui_key", "ui_input")
    val uiGroups = uiActions.map { ApprovalGrouping.groupOf(it) }.distinct()
    check("界面动作同组", uiGroups.size == 1 && uiGroups[0] == ApprovalGrouping.GROUP_UI_ACTION)

    // 2. 读屏独立成组：允许点击不等于允许看屏幕（隐私边界）。
    //    ui_tree 与 ui_screenshot 拿到的是同一类东西（屏幕内容），必须同组——否则用户勾了
    //    「不再询问屏幕截图」之后仍会被 ui_tree 拦下，而他看不出这是「同一件事」。
    check(
        "截图与界面树同组，且不并入界面动作",
        ApprovalGrouping.groupOf("ui_screenshot") == ApprovalGrouping.GROUP_UI_SCREENSHOT &&
            ApprovalGrouping.groupOf("ui_tree") == ApprovalGrouping.GROUP_UI_SCREENSHOT &&
            ApprovalGrouping.GROUP_UI_SCREENSHOT != ApprovalGrouping.GROUP_UI_ACTION
    )

    // 3. 应用安装/卸载/停止 = 同一组
    check(
        "应用管理同组",
        listOf("app_install", "app_uninstall", "app_stop")
            .map { ApprovalGrouping.groupOf(it) }.distinct()
            .let { it.size == 1 && it[0] == ApprovalGrouping.GROUP_APP_MANAGE }
    )

    // 4. 文件写入与 Git 提交不同组（提交是外发，风险更高）
    check(
        "写文件≠提交",
        ApprovalGrouping.groupOf("write_file") == ApprovalGrouping.groupOf("edit_file") &&
            ApprovalGrouping.groupOf("github_commit") != ApprovalGrouping.groupOf("write_file")
    )

    // 5. 未知工具保持原粒度（不因为归组把陌生工具放大权限）
    check("未知工具单独成组", ApprovalGrouping.groupOf("some_future_tool") == "some_future_tool")

    // 6. 每组都有面向用户的说明，且不为空
    val groups = listOf(
        ApprovalGrouping.GROUP_UI_ACTION,
        ApprovalGrouping.GROUP_UI_SCREENSHOT,
        ApprovalGrouping.GROUP_APP_MANAGE,
        ApprovalGrouping.GROUP_FILE_WRITE,
        ApprovalGrouping.GROUP_GIT_WRITE,
        ApprovalGrouping.GROUP_GIT_READ,
        ApprovalGrouping.GROUP_DEVICE_OUTPUT,
        ApprovalGrouping.GROUP_SHELL
    )
    check("每组有中文说明", groups.all { ApprovalGrouping.labelOf(it).isNotBlank() && it != ApprovalGrouping.labelOf(it) })

    // 7. 凡是 needsApproval = true 的工具，弹窗都必须说人话。
    //
    // 这条是 6 的补集：6 只验「我在列表里的组都有说明」，7 验「真实世界里需要审批的工具
    // 都落在有说明的组里」。漏一个的后果是弹窗出现「本次会话不再询问 github_status」——
    // 半句英文混在中文界面里，用户不知道该不该勾。
    //
    // 扫的是源码不是运行期：工具注册表要 Application 才能构造，harness 是纯 JVM。
    // 按 `class` 切块（一个文件里常有好几个工具类，GitHubGitTools.kt 就是 3 个），
    // 只认块内同时出现 needsApproval=true 与 name 的那些。
    //
    // 扫的是**全部** main 源码，不是只有 tools/ —— ui_* 在 automation/ 包里。
    // 上一版只扫 tools/，于是 ui_tree 漏了 needsApproval 也照过不误。
    val srcRoot = File("app/src/main/java")
    // 3.5 拆模块：core / skill / terminal 已移到 :core，扫全仓要覆盖两个根
    val coreRoot = File("core/src/main/java")
    check("能读到源码根目录（否则第 7、8 条会空转通过）", srcRoot.isDirectory && coreRoot.isDirectory)
    val blockByName = HashMap<String, String>()
    for (f in listOf(srcRoot, coreRoot).asSequence().flatMap { it.walkTopDown() }
        .filter { it.extension == "kt" }) {
        val text = f.readText()
        val cuts = Regex("""\bclass\s+[A-Za-z0-9_]+""").findAll(text).map { it.range.first }.toList()
        for (i in cuts.indices) {
            val block = text.substring(cuts[i], cuts.getOrElse(i + 1) { text.length })
            val n = Regex("""override val name\s*=\s*"([a-z0-9_]+)"""").find(block)?.groupValues?.get(1) ?: continue
            blockByName[n] = block
        }
    }
    val needsApproval = blockByName
        .filterValues { Regex("""override val needsApproval\s*=\s*true""").containsMatchIn(it) }
        .keys.toList()
    // 自检：正则失效时集合会变空，那样「全部通过」就是假的。W-36 之后只有十几个，卡 8 足够。
    check("扫到足够多的需审批工具（当前 ${needsApproval.size} 个）", needsApproval.size >= 8)
    val nameless = needsApproval.filter { ApprovalGrouping.labelOf(ApprovalGrouping.groupOf(it)) == it }
    check("每个需审批工具都有中文说明（裸工具名：$nameless）", nameless.isEmpty())

    // 8. 反向一致：目录里写了审批归组的工具，工具类必须真的会弹窗。
    //
    // 7 查「会弹的工具都有中文组名」，8 查「有组名的工具都会弹」。两边同时成立，
    // `CatalogTool.approval` 才不是死元数据。
    //
    // 修前这条不成立：20 条带 approval 的条目里有 5 条永不弹窗。其中两条是真漏洞——
    // ui_tree 把整屏文本（含验证码、私聊、输入框内容）原样送进模型上下文，目录标着
    // 「读屏需审」，执行器却因为工具类没声明而静默放行；app_stop 强制停止应用会丢
    // 未保存草稿。另三条（speak / send_notification / github_status）本来就该不审，
    // 已从目录里去掉 approval，而不是给它们硬加弹窗。
    val dead = ToolCatalog.tools
        .filter { it.approval != null }
        .filter { t ->
            val block = blockByName[t.name] ?: return@filter true
            !Regex("""override val needsApproval\s*=\s*true""").containsMatchIn(block) &&
                !Regex("""override fun needsApprovalFor""").containsMatchIn(block)
        }
        .map { it.name }
    check("目录声明审批的工具必须真的弹窗（死元数据：$dead）", dead.isEmpty())

    println("KS_APPROVAL_GROUPING_OK $pass/10")
}
