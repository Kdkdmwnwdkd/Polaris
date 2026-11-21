import com.beichen.polaris.core.text.ConversationFilter
import java.io.File

/**
 * W-69「全部对话」harness。
 *
 * 测两层，各测各的：
 *
 *  1. **纯函数行为**（真跑）：[ConversationFilter] 的两条硬约定——空白词等于不过滤、过滤不重排。
 *     这两条都是「看起来显然、改起来容易踩」的类型：写成 `if (keyword.isEmpty())` 就漏掉空格，
 *     顺手 `sortedBy` 一下就让「置顶优先」失效。只能靠断言钉。
 *
 *  2. **UI 结构**（源码扫描）：页面必须自带搜索框、键盘弹出要把列表抬起来（imePadding）、
 *     长按要能进管理菜单、抽屉必须有入口。Compose 的布局行为没法在纯 JVM 里渲染，
 *     但这几条「有没有接上」是能被文本证伪的——漏了任何一条，用户当场就会撞上。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        pass++
        println("  PASS  $name")
    } catch (t: Throwable) {
        fail++
        println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertContains(haystack: String, needle: String) {
    if (!haystack.contains(needle)) throw AssertionError("缺少 <$needle>")
}

// W-42：路径相对仓库根（run-all 会把 CWD 定在仓库根）。写死绝对路径只在本机成立。
private const val APP_SRC = "app/src/main/java/com/beichen/polaris"

private fun readSource(rel: String): String = File(APP_SRC, rel).readText()

/** 骨架 + 路由表：AppDest 枚举 2026-11-21 搬到 ui/AppDest.kt，入口检查要看两处。 */
private val ROUTES: String by lazy {
    readSource("ui/AppScaffold.kt") + "\n" + readSource("ui/AppDest.kt")
}

/** 测试用的最小数据形状：过滤只关心标题，不需要真的 ConversationSummary。 */
private data class Row(val id: String, val title: String)

fun main() {
    println("== W-69 全部对话 ==")

    // —————————— 一、纯函数行为 ——————————
    println("— A 区：标题过滤（真跑 ConversationFilter） —")

    val seed = listOf(
        Row("1", "记忆库星图重做"),
        Row("2", "Tool surface 成本测算"),
        Row("3", "抽屉底栏修复"),
        Row("4", "记忆库检索 BM25")
    )

    check("A1 空关键词返回全部，且是同一个对象（不复制、不重排）") {
        assertTrue("空词应原样返回", ConversationFilter.apply(seed, "") { it.title } === seed)
    }

    check("A2 纯空白关键词等于不过滤（漏了这条，敲个空格列表就空）") {
        for (k in listOf(" ", "   ", "\t", "\n", " \t ")) {
            assertTrue("空白词 <$k> 应原样返回", ConversationFilter.apply(seed, k) { it.title } === seed)
            assertTrue("空白词 <$k> 计数应为「共 N」",
                ConversationFilter.countText(seed.size, seed.size, k) == "共 4 个会话")
        }
    }

    check("A3 中文子串命中（用户记得的常是中间那段）") {
        val hit = ConversationFilter.apply(seed, "记忆") { it.title }
        assertTrue("应命中 2 条，实际 ${hit.map { it.title }}", hit.map { it.id } == listOf("1", "4"))
        assertTrue("「星图」只命中第一条", ConversationFilter.apply(seed, "星图") { it.title }.map { it.id } == listOf("1"))
    }

    check("A4 大小写不敏感") {
        assertTrue("tool 应命中 Tool surface",
            ConversationFilter.apply(seed, "tool") { it.title }.map { it.id } == listOf("2"))
        assertTrue("TOOL 也应命中",
            ConversationFilter.apply(seed, "TOOL") { it.title }.map { it.id } == listOf("2"))
    }

    check("A5 前后空白被剥掉（用户粘贴时经常带空格）") {
        assertTrue("『 星图 』应等同『星图』",
            ConversationFilter.apply(seed, " 星图 ") { it.title }.map { it.id } == listOf("1"))
    }

    check("A6 过滤保持原有相对顺序（置顶优先的承诺靠它）") {
        // 故意给一个「倒序看着更合理」的输入：过滤后顺序必须与输入一致，不能自己排
        val ordered = listOf(Row("pinned", "记忆-置顶"), Row("new", "记忆-最新"), Row("old", "记忆-最旧"))
        val hit = ConversationFilter.apply(ordered, "记忆") { it.title }
        assertTrue("顺序应原样保留，实际 ${hit.map { it.id }}",
            hit.map { it.id } == listOf("pinned", "new", "old"))
    }

    check("A7 无匹配返回空表（不是返回全部——那是「没筛」的语义）") {
        assertTrue("不存在的词应为空",
            ConversationFilter.apply(seed, "不存在的关键词") { it.title }.isEmpty())
    }

    check("A8 计数文案：有关键词给「匹配 M / N」，让人知道是被筛掉而不是没了") {
        val hit = ConversationFilter.apply(seed, "记忆") { it.title }
        assertTrue("文案应为『匹配 2 / 4 个会话』",
            ConversationFilter.countText(seed.size, hit.size, "记忆") == "匹配 2 / 4 个会话")
        // 空白词不能显示成「匹配 4 / 4」——用户会以为列表被筛过
        assertTrue("空白词应为『共 4 个会话』",
            ConversationFilter.countText(seed.size, 4, "  ") == "共 4 个会话")
    }

    // —————————— 二、UI 结构 ——————————
    println("— B 区：页面与入口（源码扫描） —")

    val screen = readSource("ui/ConversationsScreen.kt")
    val scaffold = ROUTES

    check("B1 页面骨架：顶栏 + 搜索框 + 列表 + 空态") {
        assertContains(screen, "fun ConversationsScreen(")
        assertContains(screen, "PageTopBar(")
        assertContains(screen, "AkTextField(")
        assertContains(screen, "LazyColumn(")
        assertContains(screen, "EmptyHint(")
    }

    check("B2 键盘弹起时列表要让位（imePadding），否则最后几条被 IME 盖住") {
        assertContains(screen, ".imePadding()")
    }

    check("B3 行高用 heightIn(min) 不用固定 height（fontScale 放大不能裁字）") {
        assertContains(screen, ".heightIn(min = 64.dp)")
        assertTrue("不得给行写死 height(...)", !screen.contains(".height(64.dp)"))
    }

    check("B4 行内容器必须 matchParentSize 而非 fillMaxSize（W-68 那个撑满整屏的坑）") {
        assertContains(screen, ".matchParentSize()")
        val rowBody = screen.substringAfter("private fun ConversationRow(")
        assertTrue("会话行里不得用 fillMaxSize 去争父容器的最大高度", !rowBody.contains(".fillMaxSize()"))
    }

    check("B5 长按进管理菜单（置顶/重命名/导出/删除与抽屉同一套对话框）") {
        assertContains(screen, "onLongClick = { menuConvId = conv.id }")
        assertContains(screen, "combinedClickable(")
        assertContains(screen, "ConversationMenuDialog(")
        assertContains(screen, "RenameConversationDialog(")
        assertContains(screen, "AkConfirmDialog(")
        // 在途会话删不掉，必须给一句人话（与抽屉一致）
        assertContains(screen, "该对话正在生成，请先停止或等待完成")
    }

    check("B6 抽屉有入口 + 路由接上（否则页面存在也进不去）") {
        assertContains(scaffold, "CONVERSATIONS(\"conversations\"")
        assertContains(scaffold, "AppDest.CONVERSATIONS -> ConversationsScreen(")
        // 入口挂在「最近对话」标题行右侧（720x1280 上抽屉只有 ~451dp 高，
        // 放列表尾部会被固定底栏永久压住），TalkBack 描述写作「查看全部对话，共 N 个」。
        assertContains(scaffold, "DrawerGroupTitle(\"最近对话\") {")
        assertContains(scaffold, "查看全部对话，共")
        assertContains(scaffold, "onOpenAllConversations")
        // 抽屉本身仍然不许有输入框——搜索挪走了，别又搬回来
        val drawer = scaffold.substringAfter("private fun DrawerContent(")
            .substringBefore("private fun DrawerConversationRow(")
        assertTrue("抽屉不得重新长出输入框", !drawer.contains("AkTextField("))
    }

    println()
    println("== 汇总：PASS=$pass FAIL=$fail ==")
    if (fail > 0) {
        throw AssertionError("CONVERSATIONS_SCREEN_VERIFICATION: FAILED")
    }
}
