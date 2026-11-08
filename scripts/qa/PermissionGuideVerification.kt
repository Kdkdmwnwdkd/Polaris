import com.beichen.polaris.core.capability.GuideItem
import com.beichen.polaris.core.capability.GuideKey
import com.beichen.polaris.core.capability.GuideSnapshot
import com.beichen.polaris.core.capability.ShizukuStage
import com.beichen.polaris.core.capability.buildGuideItems
import com.beichen.polaris.core.capability.capabilitySummary
import com.beichen.polaris.core.capability.hasBlockingGap
import com.beichen.polaris.core.capability.readyCount
import java.io.File

/**
 * P0（权限引导）harness：**行为断言**，不是源码扫描。
 *
 * 权限引导的判定逻辑已经抽成纯函数（core/capability/PermissionGuide.kt），
 * 所以可以直接在 JVM 里跑真实输入 → 真实输出，覆盖「五项检测的缺失/就绪矩阵」，
 * 不需要真机。这正是当初把它做成纯函数的目的。
 *
 * 另外两条源码级断言守住耦合边界（Android 侧无法在 JVM 里实例化）：
 *  1. `pendingGrantCount` 只收三项（无障碍/通知/使用情况）——电池与 Shizuku 是可选增强，
 *     不得进入抽屉红点，否则「引导页 5 项、红点 5 项」会制造不必要的焦虑；
 *  2. 引导页的状态 Refresh 必须挂在 ON_RESUME 上（回来自动复检），不能只靠手动点刷新。
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

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

private val allOff = GuideSnapshot()
private val allOn = GuideSnapshot(
    accessibility = true,
    notificationListener = true,
    usageAccess = true,
    batteryUnrestricted = true,
    shizuku = ShizukuStage.READY
)

fun main() {
    println("== P0 权限引导 harness：五项检测矩阵 / 分级口径 / 入口耦合 ==")

    check("五项齐全且顺序固定（教学顺序，不按缺失置顶）") {
        val items = buildGuideItems(allOff)
        assertTrue("项数应为 5，实际 ${items.size}", items.size == 5)
        val expected = listOf(
            GuideKey.ACCESSIBILITY, GuideKey.NOTIFICATION, GuideKey.USAGE,
            GuideKey.BATTERY, GuideKey.SHIZUKU
        )
        assertTrue("顺序应为 $expected，实际 ${items.map { it.key }}", items.map { it.key } == expected)
    }

    check("全缺：0/5、有硬门槛缺口、结论是「只能对话」") {
        val items = buildGuideItems(allOff)
        assertTrue("readyCount 应为 0，实际 ${readyCount(items)}", readyCount(items) == 0)
        assertTrue("应存在阻塞项", hasBlockingGap(items))
        val s = capabilitySummary(items)
        assertTrue("结论应说明只能对话，实际：$s", s.contains("只能对话"))
    }

    check("只开无障碍：1/5、不再阻塞、结论含「操作手机」") {
        val items = buildGuideItems(allOff.copy(accessibility = true))
        assertTrue("readyCount 应为 1，实际 ${readyCount(items)}", readyCount(items) == 1)
        assertTrue("无障碍开了就不该阻塞", !hasBlockingGap(items))
        val s = capabilitySummary(items)
        assertTrue("结论应含「操作手机」，实际：$s", s.contains("操作手机"))
    }

    check("只有感知类（通知 / 使用情况）：仍因缺无障碍而阻塞，但结论给出真实的「能感知」") {
        val items = buildGuideItems(
            allOff.copy(notificationListener = true, usageAccess = true)
        )
        // 无障碍是硬门槛：感知类全开也不能替代它——这是分级口径的核心，不能被写成「有任意权限就算就绪」
        assertTrue("缺无障碍必须阻塞", hasBlockingGap(items))
        val s = capabilitySummary(items)
        assertTrue("结论应含「感知」，实际：$s", s.contains("感知"))
        assertTrue("结论应说明还不能操作，实际：$s", s.contains("不能替你点屏幕"))
    }

    check("全开：5/5、无阻塞、结论含「后台跑命令」") {
        val items = buildGuideItems(allOn)
        assertTrue("readyCount 应为 5，实际 ${readyCount(items)}", readyCount(items) == 5)
        assertTrue("不该有阻塞项", !hasBlockingGap(items))
        val s = capabilitySummary(items)
        assertTrue("结论应含「后台跑命令」，实际：$s", s.contains("后台跑命令"))
    }

    check("Shizuku 四态：只有 READY 算就绪，四个动作文案互不相同") {
        val stages = listOf(
            ShizukuStage.NOT_INSTALLED,
            ShizukuStage.NOT_RUNNING,
            ShizukuStage.NO_PERMISSION,
            ShizukuStage.READY
        )
        val labels = stages.map { stage ->
            val item = buildGuideItems(allOff.copy(shizuku = stage))
                .first { it.key == GuideKey.SHIZUKU }
            assertTrue(
                "${stage} 的 granted 判定错误：${item.granted}",
                item.granted == (stage == ShizukuStage.READY)
            )
            item.actionLabel
        }
        assertTrue("四个动作文案应互不相同，实际 $labels", labels.toSet().size == 4)
        assertTrue("未安装应引导安装，实际 ${labels[0]}", labels[0].contains("安装"))
        assertTrue("未启动应引导打开，实际 ${labels[1]}", labels[1].contains("打开"))
        assertTrue("未授权应引导授权，实际 ${labels[2]}", labels[2].contains("授权"))
    }

    check("Shizuku 未安装时的 missing 文案明确「不装也能用」") {
        val item = buildGuideItems(allOff).first { it.key == GuideKey.SHIZUKU }
        assertTrue(
            "应说明不装也能用，实际：${item.missing}",
            item.missing.contains("不装也能用")
        )
    }

    check("快照 granted() 与产物 item.granted 逐项一致") {
        val snapshots = listOf(
            allOff,
            allOn,
            allOff.copy(accessibility = true),
            allOff.copy(batteryUnrestricted = true),
            allOff.copy(shizuku = ShizukuStage.NO_PERMISSION)
        )
        snapshots.forEach { snapshot ->
            buildGuideItems(snapshot).forEach { item ->
                assertTrue(
                    "$snapshot 下 ${item.key} 的 granted 不一致：snapshot=${snapshot.granted(item.key)} item=${item.granted}",
                    snapshot.granted(item.key) == item.granted
                )
            }
        }
    }

    check("纯函数幂等：同输入两次产出相等") {
        val a: List<GuideItem> = buildGuideItems(allOn.copy(shizuku = ShizukuStage.NOT_RUNNING))
        val b: List<GuideItem> = buildGuideItems(allOn.copy(shizuku = ShizukuStage.NOT_RUNNING))
        assertTrue("两次结果应相等", a == b)
    }

    check("可选能力（电池）单独开：不阻塞、但计入 readyCount") {
        val items = buildGuideItems(allOff.copy(batteryUnrestricted = true))
        assertTrue("电池不是硬门槛", hasBlockingGap(items))
        assertTrue("readyCount 应为 1，实际 ${readyCount(items)}", readyCount(items) == 1)
    }

    // —— 源码级耦合断言 ——

    check("pendingGrantCount 只算三项：电池/Shizuku 不进抽屉红点") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt")
        val sig = Regex(
            "internal fun pendingGrantCount\\(([\\s\\S]{0,200}?)\\): Int"
        ).find(src)?.groupValues?.get(1)
            ?: throw AssertionError("找不到 pendingGrantCount 定义")
        val params = sig.split(",").count { it.contains(":") }
        assertTrue("pendingGrantCount 应只收 3 个参数，实际 $params（$sig）", params == 3)
        assertTrue(
            "pendingGrantCount 实现应基于这三项计数",
            src.contains("listOf(serviceConnected, notificationGranted, usageGranted).count")
        )
    }

    check("引导页挂在 ON_RESUME 上自动复检") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/PermissionGuideScreen.kt")
        assertTrue("缺少 ON_RESUME 复检", src.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue("ON_RESUME 时未调用 refreshGrants", src.contains("vm.refreshGrants()"))
    }

    check("引导页与权限中心共用同一批探测源（不另算一套）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/PermissionGuideScreen.kt")
        listOf(
            "vm.serviceConnected",
            "vm.notificationGranted",
            "vm.usageGranted",
            "vm.batteryUnrestricted",
            "vm.shizukuUiState"
        ).forEach { flow ->
            assertTrue("引导页未使用 $flow", src.contains(flow))
        }
    }

    check("电池白名单探测走 PowerManager.isIgnoringBatteryOptimizations") {
        // 3.1 第四批起探测实现搬到 PermissionFacade（VM 只留转发），判据跟着实现走。
        val src = read("app/src/main/java/com/beichen/polaris/ui/PermissionFacade.kt")
        assertTrue(
            "未使用 isIgnoringBatteryOptimizations",
            src.contains("isIgnoringBatteryOptimizations")
        )
        assertTrue(
            "未申请 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            src.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        )
    }

    // —— P2：聚光灯引导（2026-10-21）——

    check("聚光灯三步：锚点顺序 = 输入框 → 发送 → 抽屉") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/SpotlightGuide.kt")
        val order = listOf(
            "anchor = GuideAnchor.INPUT_BAR",
            "anchor = GuideAnchor.SEND_BUTTON",
            "anchor = GuideAnchor.DRAWER"
        ).map { src.indexOf(it) }
        assertTrue("三个锚点未全部出现：$order", order.none { it < 0 })
        assertTrue("锚点顺序不对：$order", order == order.sorted())
    }

    check("三个锚点都真的挂到了组件上（不是只在枚举里声明）") {
        val components = read("app/src/main/java/com/beichen/polaris/ui/Components.kt")
        val chat = read("app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt")
        assertTrue("顶栏汉堡未挂 DRAWER 锚点", components.contains("guideAnchor(GuideAnchor.DRAWER)"))
        assertTrue("输入栏未挂 INPUT_BAR 锚点", chat.contains("guideAnchor(GuideAnchor.INPUT_BAR)"))
        assertTrue("发送键未挂 SEND_BUTTON 锚点", chat.contains("guideAnchor(GuideAnchor.SEND_BUTTON)"))
    }

    check("聚光灯触发条件：模型就绪 + 权限引导已走过 + 自己没被跳过") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt")
        assertTrue("缺少 guideSeen 条件", src.contains("guideSeen(ctx) && !spotlightSeen(ctx)"))
        // W-70：就绪判定收敛到 ChatViewModel.modelReady 单一口径（本地模式三要素本就为空，
        // 内联判定会把本地用户永远挡在引导外）。这里断言「走了那个口径」，不锁内联写法。
        assertTrue("模型就绪判定未走 vm.modelReady 单一口径", src.contains("vm.modelReady"))
        assertTrue(
            "只在对话页触发，且离开时收起覆盖层",
            src.contains("if (dest != AppDest.CHAT) {") && src.contains("showSpotlight = false")
        )
    }

    check("聚光灯结束落盘 + 设置页可重看（跳过 ≠ 永远错过）") {
        val app = read("app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt")
        val settings = read("app/src/main/java/com/beichen/polaris/ui/SettingsScreen.kt")
        assertTrue("结束时未落盘", app.contains("markSpotlightSeen(ctx)"))
        assertTrue("设置页缺少重看入口", settings.contains("重看上手引导"))
        assertTrue("设置页未重置标记", settings.contains("resetSpotlight(ctx)"))
    }

    check("洞的坐标换算到覆盖层本地坐标系（否则整体偏移一个状态栏高度）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/SpotlightGuide.kt")
        assertTrue("未记录覆盖层原点", src.contains("positionInRoot()"))
        assertTrue("未从 root 坐标减去原点", src.contains("rect.left - dx"))
    }

    check("锚点离开组合即注销（否则切页后留着旧矩形，洞开在空白处）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/SpotlightGuide.kt")
        assertTrue("未实现 forget", src.contains("fun forget(anchor: GuideAnchor)"))
        assertTrue("未在 onDispose 注销", src.contains("onDispose { registry.forget(anchor) }"))
    }

    check("首启引导点「去开启」不落「已看过」（回来还要继续讲配模型）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt")
        val start = src.indexOf("onOpenGuide = {")
        assertTrue("找不到 onOpenGuide 调用点", start > 0)
        val block = src.substring(start, minOf(start + 260, src.length))
        assertTrue("该分支不应调用 dismissOnboarding：$block", !block.contains("dismissOnboarding()"))
    }

    // —— 2026-10-22 模拟器实测后补的回归断言（四个真机可见的 UI 缺陷）——

    check("长页面避让系统导航栏（否则最后一个按钮看得见、点不动）") {
        listOf(
            "ui/PermissionGuideScreen.kt",
            "ui/PermissionsScreen.kt",
            "ui/SettingsScreen.kt",
            "ui/AppearanceScreen.kt",
            "ui/ModelConfigScreen.kt"
        ).forEach { rel ->
            val src = read("app/src/main/java/com/beichen/polaris/$rel")
            assertTrue("$rel 缺少 navigationBarsPadding", src.contains("navigationBarsPadding()"))
        }
    }

    check("首启弹窗小屏可滚动且按钮固定底部（720x1280 实测「先自己看看」被裁掉）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt")
        // 2026-10-31 在云端 720x1280 模拟器上复测：上一版写死 heightIn(max = 520.dp)，
        // 卡片居中后底部两个按钮落到屏幕外——「先自己看看」的 bounds 是 [0,0][0,0]，
        // 第一次打开的人只能按返回键关。上限必须由 BoxWithConstraints 的 maxHeight 推出来，
        // 且 Dialog 是独立窗口，得先让出系统栏，否则 maxHeight 仍是整屏 640dp。
        assertTrue("卡片高度上限未跟随可用高度", src.contains("maxHeight * 0.88f"))
        assertTrue("Dialog 未让出系统栏", src.contains("systemBarsPadding()"))
        assertTrue("上半区未可滚动", src.contains("weight(1f, fill = false).verticalScroll"))
        assertTrue("不得再写死卡片高度", !src.contains(".heightIn(max = 520.dp)"))
    }

    check("模型页三个 Tab 都在（下划线按文字宽度画，不能用 fillMaxWidth）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/ModelConfigScreen.kt")
        assertTrue("未按文字宽度画下划线", src.contains("drawWithContent"))
        assertTrue("下拉线仍用 fillMaxWidth 会挤掉后两个 Tab", !src.contains("Box(\n                    Modifier\n                        .fillMaxWidth()\n                        .height(2.dp)"))
    }

    check("自动进权限引导的 effect 以 route 为 key（否则保存后回对话页不触发）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/AppScaffold.kt")
        // 断言「某个 LaunchedEffect 的 key 里含 route」，不锁同行的其它 key——
        // W-70 把 config 三要素换成了 modelReady，写死整行就会误报。
        assertTrue(
            "LaunchedEffect 未包含 route",
            Regex("""LaunchedEffect\([^)]*\broute\b[^)]*\)""").containsMatchIn(src)
        )
    }

    check("遮罩用 EvenOdd 开洞（目标必须透亮，不能被压暗）") {
        val src = read("app/src/main/java/com/beichen/polaris/ui/SpotlightGuide.kt")
        assertTrue("未使用 PathFillType.EvenOdd", src.contains("PathFillType.EvenOdd"))
        assertTrue("未画高亮描边", src.contains("drawRoundRect("))
    }

    println()
    println("PASS=$pass FAIL=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
