/**
 * hotfix11「对话页模型快速切换」QA 源码级验证 harness
 * （离线，无 JUnit，纯 main() + 手写断言；形态学 CapturePathVerification 的 C 区）。
 *
 * 背景（用户真机录屏反馈）：「保存了好多个模型，要换的时候不给换，都不知道现在用哪个」。
 * 根因：对话页无当前模型展示、无切换入口——唯一途径是深埋三层的横向档案 chip 条
 * （抽屉 → 模型配置 → 顶部切换条），多了还被横向滚动挤出屏，且切换无任何反馈。
 *
 * G 区断言（全部源码扫描，不加载 Android 类）：
 *  - G1-G5：对话页常驻「当前模型」指示条（配置名 + 模型名 · 域名）、未配置引导态；
 *  - G6-G9：切换弹窗（AkModalHost 退场 + 全档案列表 + 当前项对勾 + 点选即时生效 +
 *    Toast 反馈 + 「管理模型配置」入口）；
 *  - G10-G12：VM 侧根基——switchProfile 同步 _config（即时生效）+ 落盘（重启不丢失），
 *    hostOf 域名提取存在。
 *
 * 运行方式见 scripts/qa/run-hotfix11-verification.sh。
 */

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

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertContains(name: String, haystack: String, needle: String) {
    if (!haystack.contains(needle)) {
        throw AssertionError("$name: 缺少 <$needle>")
    }
}

/* ------------------------------ 源码读取（纯文本扫描） ------------------------------ */

// W-42：路径相对仓库根（run-all 会把 CWD 定在仓库根）。写死 /workspace/AgentKit 只在本机成立。
private const val APP_SRC = "app/src/main/java/com/beichen/polaris"

// 3.5 拆模块：core / skill / terminal 已移到 :core，同一个相对路径要两边找
private const val CORE_SRC = "core/src/main/java/com/beichen/polaris"

private fun readSource(rel: String): String {
    val app = File(APP_SRC, rel)
    return (if (app.exists()) app else File(CORE_SRC, rel)).readText()
}

/* =============================== G 区：模型快速切换 =============================== */

private fun testModelQuickSwitch() {
    println("— G 区：对话页当前模型指示条 + 快速切换（hotfix11） —")
    val chat = readSource("ui/ChatScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")

    check("G1 ChatScreen 定义 ModelIndicatorBar（当前模型指示条）") {
        assertContains("G1", chat, "private fun ModelIndicatorBar(")
    }
    check("G2 指示条在 ChatScreen 主体接线（PageTopBar 之后常驻）") {
        // 主体接线点：onAction 块之后紧跟 ModelIndicatorBar(
        assertContains("G2", chat, "onAction = { vm.newConversation() }")
        val anchor = chat.indexOf("onAction = { vm.newConversation() }")
        val after = chat.substring(anchor)
        assertContains("G2", after, "ModelIndicatorBar(")
    }
    check("G3 对话页消费 profiles / activeProfileId 状态流") {
        assertContains("G3", chat, "val profiles by vm.profiles.collectAsStateWithLifecycle()")
        assertContains("G3", chat, "val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()")
    }
    check("G4 指示条展示当前配置名 + 模型名 + 域名") {
        assertContains("G4", chat, "profileName = activeProfile?.name")
        assertContains("G4", chat, "hostOf(config.baseUrl)")
        // W-70：本地模式不再显示上个云端模型的 model 名，改显示模型文件 + 「设备内推理」。
        // 断言锁「两条分支都存在」，不锁具体写法（原来写死 modelName = config.model 会误报）。
        assertContains("G4", chat, "设备内推理")
        assertContains("G4", chat, "config.model")
    }
    check("G5 未配置态点击走引导（onOpenModel），已配置态开切换弹窗") {
        assertContains("G5", chat, "if (modelReady) showModelSwitch = true else onOpenModel()")
    }
    check("G5b 未配置文案可见（模型未配置 / 点此前往配置模型）") {
        assertContains("G5b", chat, "模型未配置")
        assertContains("G5b", chat, "点此前往配置模型")
    }
    check("G6 ModelSwitchDialog 定义 + AkModalHost 承载退场") {
        assertContains("G6", chat, "private fun ModelSwitchDialog(")
        assertContains("G6", chat, "AkModalHost(target = if (showModelSwitch) true else null)")
    }
    check("G7 点选即 vm.switchProfile 即时生效 + Toast 可见反馈") {
        assertContains("G7", chat, "vm.switchProfile(id)")
        assertContains("G7", chat, "Toast.makeText(ctx, \"已切换到「\$name」\", Toast.LENGTH_SHORT).show()")
    }
    check("G8 弹窗列出全部档案，当前项 selected 高亮 + Check 对勾") {
        assertContains("G8", chat, "items(profiles, key = { it.id })")
        assertContains("G8", chat, "selected = isActive")
        assertContains("G8", chat, "Icons.Outlined.Check")
    }
    check("G9 「管理模型配置」入口进模型配置页") {
        assertContains("G9", chat, "text = \"管理模型配置\"")
        assertContains("G9", chat, "onManage = {")
        // onManage 必须先关弹窗再跳配置页（否则弹窗退场动画期间跳页，视觉闪断）
        assertContains("G9", chat, "showModelSwitch = false")
    }
    check("G10 VM.switchProfile 同步 _config（切换即生效的根基）") {
        val fn = vm.substringAfter("fun switchProfile(id: String)")
        assertContains("G10", fn, "_config.value = target.config")
    }
    check("G11 VM.switchProfile 落盘 saveProfiles（重启不丢失）") {
        val fn = vm.substringAfter("fun switchProfile(id: String)")
        assertContains("G11", fn, "configStore.saveProfiles(_profiles.value, id)")
    }
    check("G12 hostOf 域名提取函数存在（去 scheme / 去路径）") {
        assertContains("G12", chat, "internal fun hostOf(baseUrl: String): String")
        assertContains("G12", chat, "removePrefix(\"https://\")")
    }
}

/**
 * H 区：功能四 P0-1「消息一键复制」——四家参照（RikkaHub ChatMessageActions /
 * Operit ChatArea）均把「复制」作为消息级一等操作；AgentKit 此前只有
 * SelectionContainer（需手动选字），气泡上没有复制入口。
 */
private fun testMessageCopy() {
    println("— H 区：消息一键复制（功能四 P0-1） —")
    val chat = readSource("ui/ChatScreen.kt")

    check("H1 助手气泡操作行带复制入口（showCopy 参数贯通）") {
        assertContains("H1", chat, "showCopy = showCopy")
        assertContains("H1", chat, "onCopy = { copyMessage(ctx, item.text) }")
    }
    check("H2 助手行复制位次在「重新生成」之前（对齐 RikkaHub 顺序）") {
        val block = chat.substringAfter("private fun AssistantActions(")
        val copyAt = block.indexOf("text = \"复制\"")
        // 第三轮起「重新生成」的文案改成参数 regenLabel（错误气泡要显示成「重试」），
        // 源码里不再有裸字面量 text = "重新生成"。改为定位它的渲染点 `text = regenLabel`——
        // 这才是「这一行的位置」，比盯着会被改写的文案稳。
        val regenAt = block.indexOf("text = regenLabel")
        assertTrue(
            "H2 复制应排在重新生成之前（copy=$copyAt regen=$regenAt）",
            copyAt >= 0 && regenAt > copyAt
        )
    }
    check("H3 用户气泡操作行（UserMessageActions）带复制 + 重新发问") {
        assertContains("H3", chat, "private fun UserMessageActions(")
        assertContains("H3", chat, "UserMessageActions(")
        assertContains("H3", chat, "onCopy = { copyMessage(ctx, item.text) }")
    }
    check("H4 复制工具函数：写剪贴板 + Toast 反馈（静默成功不可接受）") {
        assertContains("H4", chat, "private fun copyMessage(context: Context, text: String)")
        assertContains("H4", chat, "ClipData.newPlainText")
        assertContains("H4", chat, "Toast.makeText")
    }
    check("H5 运行中隐藏操作行（避免点到旧轮）") {
        assertContains("H5", chat, "val showCopy = !running")
        assertContains("H5", chat, "if (item is UiItem.User && !running)")
    }
}

/**
 * I 区：功能四 P0-2「输入栏图片附件」——多模态请求侧早已支持
 * （LLMClient.toApiMessage: UserMessage.images → image_url part），缺的是输入端通路。
 */
private fun testImageAttachment() {
    println("— I 区：输入栏图片附件（功能四 P0-2） —")
    val chat = readSource("ui/ChatScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val llm = readSource("core/LLMClient.kt")

    check("I1 待发图片状态流存在（pendingImages）") {
        assertContains("I1", vm, "val pendingImages: StateFlow<List<String>>")
    }
    check("I2 选图落盘走 IO 线程（不在主线程编码）") {
        assertContains("I2", vm, "suspend fun attachImageFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO)")
    }
    check("I3 附件压缩：长边 ≤ 1024 + JPEG 质量 80（控制请求体积）") {
        assertContains("I3", vm, "const val MAX_EDGE = 1024")
        assertContains("I3", vm, "Bitmap.CompressFormat.JPEG, 80")
    }
    check("I4 输入栏渲染缩略图行 + 可移除（UI 在 ChatScreen，API 在 ViewModel）") {
        assertContains("I4", chat, "AttachedImagesRow(images, onRemoveImage)")
        assertContains("I4", vm, "fun removePendingImage(url: String)")
    }
    check("I5 发送时带走附件并清空输入区") {
        val body = vm.substringAfter("fun send()")
        assertContains("I5", body, "val images = _pendingImages.value")
        assertContains("I5", body, "_pendingImages.value = emptyList()")
        // W-46 起 send 把历史摊平后作为第三参传入（多轮对话），断言随之更新
        assertContains("I5", body, "loop.send(fullInput, images, priorMessages)")
    }
    check("I6 多模态请求侧原本就支持（本次不是新造轮子）") {
        assertContains("I6", llm, "put(\"type\", \"image_url\")")
    }
}

/**
 * J 区：功能四 P0-3「提供商列表一级入口」——对标 Coomi `ProvidersView.vue` /
 * RikkaHub `SettingPage`：把所有配置档案列成可点选的清单，当前生效项高亮，
 * 点一下即切换生效；AgentKit 此前只有一行横向芯片条（多档案被挤出屏幕）。
 */
private fun testProviderListEntry() {
    println("— J 区：切换配置的唯一入口（v4 去重后重写） —")
    val cfg = readSource("ui/ModelConfigScreen.kt")
    // 真机反馈：「模型有两套触碰系统，上面也有，下面那个也是，留一套就行了」。
    // 原 J1~J5 断言的是 ProviderPickerSection（页面中部那份档案列表）——
    // 它和顶部 ProfileSwitcher 的 chips 列的是同一批档案，v4 已删除。
    // 断言随之改为「验证它确实不在了，且唯一入口仍完整」，防止将来被加回来。
    val cfgCode = cfg.lines().filter {
        val t = it.trim()
        !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
    }

    check("J1 重复的档案列表已删除，切配置只剩顶部 chips 一个入口") {
        assertTrue(
            "J1 ProviderPickerSection 又被加回来了",
            cfgCode.none { "ProviderPickerSection(" in it }
        )
        assertContains("J1", cfg, "ProfileSwitcher(")
    }
    check("J2 chips 列出全部档案，可选中并可新建") {
        assertContains("J2", cfg, "profiles.forEach { profile ->")
        assertContains("J2", cfg, "AkChip(")
        assertContains("J2", cfg, "AddProfileChip(onClick = onAdd)")
    }
    check("J3 端点与模型信息仍可查（信息没丢，只是不重复两遍）") {
        // 列表里的「模型名 · 域名」随列表一起删了，但同一条信息在
        // 「当前生效配置」里仍完整展示（端点 + 模型），并不需要每行再列一次。
        assertContains("J3", cfg, "端点：")
        assertContains("J3", cfg, "当前生效配置")
    }
    check("J4 点选 chip 即 switchProfile 生效") {
        val body = cfg.substringAfter("ProfileSwitcher(")
        assertContains("J4", body, "vm.switchProfile(it)")
    }
    check("J5 新建走对话框（不再经过已删除的列表入口）") {
        assertContains("J5", cfg, "onAdd = { showAdd = true }")
        assertContains("J5", cfg, "AddProfileDialog(")
        assertContains("J5", cfg, "新建模型配置")
    }
}

/**
 * K 区：功能五 P1-3「会话置顶 + 会话搜索」——对标 RikkaHub `ConversationList.kt`
 * （pin/unpin 菜单 + pinned_chats 分组）与 HistoryPage 搜索。
 */
private fun testConversationPinAndSearch() {
    println("— K 区：会话置顶 + 会话搜索（功能五 P1-3） —")
    val store = readSource("core/ConversationStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val scaffold = readSource("ui/AppScaffold.kt")

    check("K1 存储层：Record/Summary 带 pinned 字段（默认 false，旧文件兼容）") {
        assertContains("K1", store, "val pinned: Boolean = false")
        // list() 自 hotfix21（P1-5 用量统计）起改为命名参数构造（顺带汇总 token），
        // 置顶键的传递断言随之更新为命名形式
        assertContains("K1", store, "pinned = rec.pinned")
    }
    check("K2 list() 排序契约：置顶优先 → 组内按时间倒序") {
        assertContains("K2", store, "compareByDescending<ConversationSummary> { it.pinned }")
    }
    check("K3 setPinned 只改排序键，不动 updatedAt（防伪装成刚聊过）") {
        val fn = store.substringAfter("fun setPinned(")
        assertContains("K3", fn, "copy(pinned = pinned)")
        assertTrue(
            "K3 不应更新 updatedAt",
            !fn.substringBefore("schedulePersist").contains("updatedAt =")
        )
    }
    check("K4 VM 暴露 setConversationPinned 并刷新摘要列表") {
        val fn = vm.substringAfter("fun setConversationPinned(")
        assertContains("K4", fn, "conversationStore.setPinned(id, pinned)")
        assertContains("K4", fn, "refreshConversations()")
    }
    // W-68 把抽屉里的搜索框整块删掉：一有输入框，点一下键盘就弹起来，抽屉被 IME
    // 压到 400 多 px，固定在底栏的「设置」贴到键盘上沿（用户原话「感觉怪怪的」）。
    // 所以这条从「搜索能过滤」反过来守「抽屉里不许再出现输入框」，并把完整列表交给
    // W-69 的独立页面。写成正向断言（含某段代码）守不住删除类改动——删掉的那一刻它就红了。
    check("K5 抽屉不设输入框：搜索挪到独立页，抽屉永不弹键盘") {
        assertTrue("K5 抽屉不得残留会话搜索输入框", !scaffold.contains("convQuery"))
        assertTrue("K5 抽屉不得残留「搜索对话」标题", !scaffold.contains("搜索对话"))
        assertContains("K5", scaffold, "故意不放搜索框")
        assertContains("K5", scaffold, "onOpenAllConversations")
        assertContains("K5", scaffold, "AppDest.CONVERSATIONS -> ConversationsScreen(")
    }
    check("K6 长按菜单有置顶项，文案随状态反转 + 说明后果") {
        assertContains("K6", scaffold, "onTogglePin = {")
        assertContains("K6", scaffold, "if (pinned) \"取消置顶\" else \"置顶对话\"")
        assertContains("K6", scaffold, "onSetPinned(id, conv?.pinned != true)")
    }
    check("K7 会话行有置顶可视标记（实心书签）") {
        assertContains("K7", scaffold, "if (conversation.pinned)")
        assertContains("K7", scaffold, "Icons.Filled.Bookmark")
    }
}

/**
 * L 区：功能五 P1-1「消息更多操作（分享 / 从此处删除）」——对标 RikkaHub
 * MessageSheetAction（Share/Delete）与 Operit ChatArea 消息菜单。
 */
private fun testMessageMoreActions() {
    println("— L 区：消息更多操作（功能五 P1-1） —")
    val chat = readSource("ui/ChatScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")

    check("L1 操作行有「⋯」更多入口（助手/用户两行都有）") {
        assertContains("L1", chat, "showMore = showMore")
        assertContains("L1", chat, "text = \"⋯\"")
        // 重构后 onMore 不再直接塞 Pair，改构造 MenuTarget（含 turnIndex/消息来源）
        assertContains("L1", chat, "onMore = {")
        assertContains("L1", chat, "menuTarget = MenuTarget(")
    }
    check("L2 分享走系统分享面板（ACTION_SEND + createChooser，零额外权限）") {
        // hotfix22 起分享实现收敛到 ShareActions.kt，ChatScreen 只留委托（Q6 再验共用）
        assertContains("L2", chat, "private fun shareMessage(context: Context, text: String) = shareText(context, text, \"分享消息\")")
        val share = readSource("ui/ShareActions.kt")
        assertContains("L2", share, "Intent.ACTION_SEND")
        assertContains("L2", share, "Intent.createChooser")
        assertContains("L2", share, "type = \"text/plain\"")
    }
    check("L3 消息操作弹窗存在，删除项文案写明「及之后的全部内容」") {
        assertContains("L3", chat, "private fun MessageActionsDialog(")
        assertContains("L3", chat, "删除这条及之后的全部内容")
    }
    check("L4 「从此处删除」有 danger 二次确认（不可逆操作统一拦截）") {
        assertContains("L4", chat, "title = \"从此处删除\"")
        assertContains("L4", chat, "danger = true")
    }
    check("L5 VM deleteFromTurn：截断语义（保留到上一轮），非删单条") {
        val fn = vm.substringAfter("fun deleteFromTurn(")
        assertContains("L5", fn, "conversationStore.truncateAfter(_activeConversationId.value, turnIndex - 1)")
        assertContains("L5", fn, "refreshConversations()")
    }
    check("L6 VM 删除有运行中守卫（在跑的时候不许改历史）") {
        val fn = vm.substringAfter("fun deleteFromTurn(")
        assertTrue("L6 应有 isRunning 守卫", fn.substringBefore("truncateAfter").contains("isRunning.value"))
    }
    check("L7 草稿/失败气泡不给更多入口（无轮次可操作）") {
        assertContains("L7", chat, "val showMore = !running && item.turnIndex >= 0")
    }
}

/**
 * M 区：功能五 P1-2「TTS 朗读」——对标 RikkaHub ChatMessageActions(tts) /
 * Operit ChatArea onSpeakMessage；AgentKit 用系统 TextToSpeech，零新增依赖。
 */
private fun testTtsReading() {
    println("— M 区：TTS 朗读（功能五 P1-2） —")
    val chat = readSource("ui/ChatScreen.kt")

    check("M1 控制器用系统 TextToSpeech（零新增依赖）") {
        assertContains("M1", chat, "internal class TtsController(context: Context)")
        assertContains("M1", chat, "TextToSpeech(appContext)")
    }
    check("M2 异步初始化有 ready 守卫，speak 返回布尔（未就绪可提示，不静默失败）") {
        assertContains("M2", chat, "private var ready = false")
        assertContains("M2", chat, "fun speak(text: String): Boolean")
        assertContains("M2", chat, "if (!ready || text.isBlank()) return false")
    }
    check("M3 生命周期纪律：DisposableEffect 离开即 shutdown（防系统绑定泄漏）") {
        assertContains("M3", chat, "DisposableEffect(Unit) { onDispose { tts.shutdown() } }")
        assertContains("M3", chat, "fun shutdown()")
    }
    check("M4 中文优先且回落不阻断（无中文引擎也不炸）") {
        assertContains("M4", chat, "runCatching { tts?.language = Locale.CHINA }")
    }
    check("M5 朗读/停止同按钮切换，文案与颜色随状态反转") {
        assertContains("M5", chat, "if (speaking) \"停止朗读\" else \"朗读\"")
        assertContains("M5", chat, "speakingIndex == index")
    }
    check("M6 未就绪给可读提示（不是点了没反应）") {
        assertContains("M6", chat, "朗读器未就绪，请稍后再试")
    }
    check("M7 运行中不给朗读入口（避免与流式输出抢注意力）") {
        assertContains("M7", chat, "val showSpeak = !running && item.text.isNotBlank()")
    }
}

/**
 * N 区：功能五 P1-4「输入栏文件附件（文本 / PDF / DOCX）」——对标 RikkaHub
 * 多模态输入（README 明示 images, text, PDF, Docx）。此前 AgentKit 只做了图片，
 * 用户点名「别跟我说只能发照片，不能发文件」。
 */
private fun testFileAttachment() {
    println("— N 区：文件附件（功能五 P1-4） —")
    val vm = readSource("ui/ChatViewModel.kt")
    val chat = readSource("ui/ChatScreen.kt")

    check("N1 文件附件类型与结果类型齐备（三成功路径 + 一失败路径）") {
        assertContains("N1", vm, "data class PendingFile(val name: String, val text: String)")
        assertContains("N1", vm, "sealed interface FileAttachResult")
        assertContains("N1", vm, "data class Text(val name: String, val chars: Int)")
        assertContains("N1", vm, "data class Images(val name: String, val pages: Int)")
        assertContains("N1", vm, "data class Failed(val reason: String)")
    }
    check("N2 按类型分派：PDF 转图 / DOCX 抽文本 / 文本类直读 / 其它明确拒绝") {
        assertContains("N2", vm, "ext == \"pdf\" -> {")
        assertContains("N2", vm, "ext == \"docx\" -> {")
        assertContains("N2", vm, "ext in TEXT_EXTENSIONS -> {")
        assertContains("N2", vm, "暂不支持 .\$ext 文件")
    }
    check("N3 PDF 渲染：前 N 页上限 + 铺白底（透明页不铺白会成黑图）") {
        assertContains("N3", vm, "const val MAX_PDF_PAGES = 5")
        assertContains("N3", vm, "PdfRenderer(parcel)")
        assertContains("N3", vm, "bmp.eraseColor(0xFFFFFFFF.toInt())")
    }
    check("N4 DOCX 解包抽 <w:t> 文本 + 段落换行 + 实体解码（纯系统 zip，零依赖）") {
        assertContains("N4", vm, "word/document.xml")
        assertContains("N4", vm, "Regex(\"<w:t[^>]*>(.*?)</w:t>\"")
        assertContains("N4", vm, "replace(\"</w:p>\", \"\\n\")")
        assertContains("N4", vm, "decodeXmlEntities")
    }
    check("N5 发送时文件正文以「[附件：文件名]」块拼进消息（模型能引用来源）") {
        assertContains("N5", vm, "val fileBlock = files.joinToString")
        assertContains("N5", vm, "[附件：")
    }
    check("N6 文件有大小上限（防超长文件撑爆上下文）") {
        assertContains("N6", vm, "const val MAX_FILE_CHARS = 60_000")
        assertContains("N6", vm, "text.take(MAX_FILE_CHARS)")
    }
    check("N7 UI：「＋」改为「图片 / 文件」二选一（不再直通相册）") {
        assertContains("N7", chat, "private fun AttachPickerDialog(")
        assertContains("N7", chat, "onPickAttach = { showAttachMenu = true }")
        assertContains("N7", chat, "pickFile.launch(arrayOf(\"*/*\"))")
        assertContains("N7", chat, "ActivityResultContracts.OpenDocument()")
    }
    check("N8 文件 chip 行可移除，且文件名截断留出「×」位置") {
        assertContains("N8", chat, "private fun AttachedFilesRow(")
        assertContains("N8", chat, "widthIn(max = 180.dp)")
        assertContains("N8", chat, "onRemoveFile = { vm.removePendingFile(it) }")
    }
}

/**
 * O 区：功能五 P1-6「模型选择器增强」——对标 RikkaHub `ModelListSheet`
 * （收藏区 / 打开定位当前项 / 能力标签）。
 */
private fun testModelPickerEnhancements() {
    println("— O 区：模型选择器增强（功能五 P1-6） —")
    val cfg = readSource("ui/ModelConfigScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    // 3.1 第一批：列表拉取域（含收藏落盘）搬进 ModelListFacade，VM 只留 override 转发。
    val listFacade = readSource("ui/ModelListFacade.kt")
    val store = readSource("core/ConfigStore.kt")

    check("O1 收藏持久化：ConfigStore 存取 + 逗号分隔单键（不为一处引入 JSON）") {
        assertContains("O1", store, "fun loadFavoriteModels()")
        assertContains("O1", store, "fun saveFavoriteModels(list: List<String>)")
        assertContains("O1", store, "KEY_FAVORITE_MODELS = \"favorite_models\"")
    }
    check("O2 VM 暴露收藏流 + 切换即落盘") {
        assertContains("O2", vm, "val favoriteModels: StateFlow<List<String>>")
        assertContains("O2", vm, "fun toggleFavoriteModel(name: String)")
        // 落盘动作跟着实现走：现在在 facade 里，VM 的转发不含它。
        assertContains("O2", listFacade, "configStore.saveFavoriteModels(next)")
    }
    check("O3 收藏优先排序（稳定排序，收藏组内保持端点原序）") {
        assertContains("O3", cfg, "base.sortedByDescending { it in favorites }")
    }
    check("O4 打开即定位当前模型（列表拉取完成后滚动过去）") {
        assertContains("O4", cfg, "listState.scrollToItem(idx)")
        assertContains("O4", cfg, "visible.indexOf(config.model)")
    }
    check("O5 能力标签是规则推断（新模型命名规律一致即自动命中，不发版维护）") {
        assertContains("O5", cfg, "private fun modelTags(id: String): List<String>")
        assertContains("O5", cfg, "\"视觉\"")
        assertContains("O5", cfg, "\"推理\"")
    }
    check("O6 星标与整行点击是两个独立热区（点星不会顺带选中模型）") {
        val row = cfg.substringAfter("private fun ModelPickerRow(")
        assertContains("O6", row, "onToggleFavorite")
        assertContains("O6", row, ".clickable(onClick = onPick)")
    }
    check("O7 计数文案含收藏数（用户一眼知道收藏了几个）") {
        assertContains("O7", cfg, "收藏 \${favorites.count { it in models }} 个")
    }
}

/**
 * P 区：功能五 P1-5「Token 用量统计」——对标 RikkaHub / Coomi 的用量可见性。
 *
 * 断言的链路完整性：客户端解析（三家）→ commit 事件上传（AssistantMessage.usage）
 * → 落盘（MsgRecord / 摘要）→ VM 汇总（整轮挂最终气泡 + 会话累计流）→ UI（顶部条 +
 * 气泡小字 + 抽屉行）。
 */
private fun testTokenUsage() {
    println("— P 区：Token 用量统计（功能五 P1-5） —")
    val contract = readSource("core/AgentContract.kt")
    val parsers = readSource("core/llm/TokenUsageParsers.kt")
    val openai = readSource("core/LLMClient.kt")
    val claude = readSource("core/llm/ClaudeClient.kt")
    val gemini = readSource("core/llm/GeminiClient.kt")
    val conv = readSource("core/ConversationStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val chat = readSource("ui/ChatScreen.kt")
    val scaffold = readSource("ui/AppScaffold.kt")

    check("P1 核心层 TokenUsage（输入/输出/合计 + sum 汇总）") {
        assertContains("P1", contract, "data class TokenUsage(")
        assertContains("P1", contract, "val promptTokens: Long = 0")
        assertContains("P1", contract, "val completionTokens: Long = 0")
        assertContains("P1", contract, "fun sum(usages: List<TokenUsage?>): TokenUsage")
    }
    check("P2 AssistantMessage 挂 usage 字段（默认 null，旧构造点零改动）") {
        assertContains("P2", contract, "val usage: TokenUsage? = null")
    }
    check("P3 三家 usage 纯解析函数（零依赖，harness 可断言）") {
        assertContains("P3", parsers, "fun parseOpenAiUsage(root: JsonObject): TokenUsage?")
        assertContains("P3", parsers, "fun parseClaudeUsage(root: JsonObject): TokenUsage?")
        assertContains("P3", parsers, "fun parseGeminiUsage(root: JsonObject): TokenUsage?")
        // 缺失返回 null 而非 0：「服务端没给」与「服务端说 0」是两件事
        assertContains("P3", parsers, "?: return null")
    }
    check("P4 OpenAI 非流式响应解析 usage") {
        val fn = openai.substringAfter("private fun parseResponse(text: String): AssistantMessage")
        // hotfix35 起同一解析链同时承载「流式降级」（非 SSE 整段 JSON），
        // 返回结构扩为多行（含 reasoning 第四参），usage 仍在第三参
        assertContains("P4", fn, "parseOpenAiUsage(root),")
        assertContains("P4", fn, "reasoning?.takeIf { it.isNotBlank() }")
    }
    check("P5 OpenAI 流式：请求带 include_usage + 末片 usage 捕获") {
        assertContains("P5", openai, "putJsonObject(\"stream_options\") { put(\"include_usage\", true) }")
        val fn = openai.substringAfter("override fun streamChat(")
        assertContains("P5", fn, "var usage: com.beichen.polaris.core.TokenUsage? = null")
        assertContains("P5", fn, "parseOpenAiUsage(obj)?.let { usage = it }")
        // hotfix26 起 Completed 带第四参 reasoning（P1-9）；usage 仍第三参不变
        assertContains("P5", fn, "accumulator.build(),")
        assertContains("P5", fn, "usage,")
        assertContains("P5", fn, "reasoning.toString().takeIf { it.isNotBlank() }")
    }
    check("P6 Claude 非流式响应解析 usage（input/output_tokens）") {
        val fn = claude.substringAfter("private fun parseResponse(text: String): AssistantMessage")
        assertContains("P6", fn, "AssistantMessage(")
        assertContains("P6", fn, "parseClaudeUsage(root),")
        // hotfix26：第四参 reasoning（thinking 块分流，P1-9）
        assertContains("P6", fn, "reasoning.toString().takeIf { it.isNotBlank() }")
    }
    check("P7 Gemini 非流式响应解析 usage（usageMetadata）") {
        val fn = gemini.substringAfter("private fun parseResponse(text: String): AssistantMessage")
        assertContains("P7", fn, "AssistantMessage(")
        assertContains("P7", fn, "parseGeminiUsage(root),")
        // hotfix26：第四参 reasoning（thought part 分流，P1-9）
        assertContains("P7", fn, "reasoning.toString().takeIf { it.isNotBlank() }")
    }
    check("P8 落盘字段带默认值（旧 conversations.json 反序列化不炸）") {
        assertContains("P8", conv, "val promptTokens: Long = 0")
        assertContains("P8", conv, "val completionTokens: Long = 0")
        // 摘要与记录两处都有（抽屉行 + 会话记录）
        assertTrue("P8", conv.split("val promptTokens: Long = 0").size >= 3)
    }
    check("P9 映射双向保留 usage（写入 toMsgRecord / 读取 toUiItem + 轮次渲染）") {
        val write = conv.substringAfter("internal fun UiItem.toMsgRecord()")
        assertContains("P9", write, "promptTokens = usage?.promptTokens ?: 0L")
        val read = conv.substringAfter("internal fun MsgRecord.toUiItem()")
        assertContains("P9", read, "TokenUsage(promptTokens, completionTokens)")
        val turns = conv.substringAfter("internal fun ConversationRecord.toUiItems()")
        assertContains("P9", turns, "usage = if (sel.promptTokens > 0 || sel.completionTokens > 0)")
    }
    check("P10 摘要统计对全部变体求和（废弃变体也真实花过钱）") {
        val fn = conv.substringAfter("fun list(): List<ConversationSummary>")
        assertContains("P10", fn, "rec.turns.flatMap { it.variants }")
        assertContains("P10", fn, "promptTokens = usage.promptTokens")
        assertContains("P10", fn, "completionTokens = usage.completionTokens")
    }
    check("P11 VM：整轮 LLM 往返用量汇总挂最终回答（中间轮用量不丢）") {
        // 不按 outcome.onSuccess 切块——send/regenerate 两个同名块，取串会错位；
        // 直接断言 send 路径独有的三行
        assertContains("P11", vm, "val turnUsage = TokenUsage.sum(")
        assertContains("P11", vm, "val lastAnswer = history.filterIsInstance<AssistantMessage>()")
        assertContains("P11", vm, "append(msg, if (msg === lastAnswer) turnUsage else null)")
    }
    check("P12 VM：regenerate 新变体带用量 + append 支持 usage 参数") {
        // P4-2 起投影搬进 ConversationProjection：VM 只留 `append(msg, usage)` 这层转发，
        // 「usage 怎么进气泡」的实现在投影里——断言跟着所有权走，不再锚在宿主文件上。
        val proj = readSource("ui/ConversationProjection.kt")
        assertContains("P12", vm, "private fun append(msg: ChatMessage, usage: TokenUsage? = null)")
        assertContains("P12", proj, "fun appendMessage(msg: ChatMessage, usage: TokenUsage? = null)")
        // hotfix26 起 append 透传 reasoning（P1-9）；助手分支是命名参数（timeMs 加入后），用稳定片段
        assertContains("P12", proj, "is AssistantMessage -> UiItem.Assistant(")
        assertContains("P12", proj, "usage = usage,")
        val regen = vm.substringAfter("fun regenerate()")
        assertContains("P12", regen, "usage = TokenUsage.sum(")
    }
    check("P13 VM：会话累计用量派生流（切会话随 _messages 重载自动切换）") {
        // 消息流改由 ConversationProjection 持有，VM 这里读的是同名只读视图
        assertContains("P13", vm, "val sessionUsage: StateFlow<TokenUsage> = messages")
        assertContains("P13", vm, "TokenUsage.sum(items.filterIsInstance<UiItem.Assistant>().mapNotNull { it.usage })")
        assertContains("P13", vm, ".stateIn(viewModelScope, SharingStarted.Eagerly, TokenUsage.EMPTY)")
    }
    check("P14 ChatScreen：会话用量条 + 气泡用量小字 + formatTokens") {
        assertContains("P14", chat, "val sessionUsage by vm.sessionUsage.collectAsStateWithLifecycle()")
        assertContains("P14", chat, "if (sessionUsage.totalTokens > 0) {")
        assertContains("P14", chat, "UsageSummaryBar(usage = sessionUsage)")
        assertContains("P14", chat, "private fun UsageSummaryBar(usage: TokenUsage)")
        assertContains("P14", chat, "internal fun formatTokens(n: Long): String")
        assertContains("P14", chat, "usage = item.usage")
        assertContains("P14", chat, "\"↑ \${formatTokens(usage.promptTokens)} · \"")
    }
    check("P15 抽屉会话行缀 tokens（有数据才显示，旧会话不摆 0）") {
        val row = scaffold.substringAfter("fun ConversationRow(")
        assertContains("P15", row, "formatTokens(conversation.promptTokens + conversation.completionTokens)")
        assertContains("P15", row, "} tok\"")
    }
}

/**
 * Q 区：功能五 P1-4a「会话导出 Markdown」——对标 RikkaHub Export.kt / Coomi 导出。
 *
 * 断言的链路完整性：纯函数序列化（core）→ VM 只读入口 → 抽屉长按菜单 →
 * 系统分享面板（与 hotfix17 消息分享共用 shareText）。
 */
private fun testConversationExport() {
    println("— Q 区：会话导出 Markdown（功能五 P1-4a） —")
    val md = readSource("core/ConversationMarkdown.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val share = readSource("ui/ShareActions.kt")
    val chat = readSource("ui/ChatScreen.kt")
    val scaffold = readSource("ui/AppScaffold.kt")

    check("Q1 core 纯函数 conversationToMarkdown（零 IO，harness 可断言）") {
        assertContains("Q1", md, "fun conversationToMarkdown(record: ConversationRecord): String")
        assertContains("Q1", md, "private const val MAX_CONTENT_CHARS = 20_000")
    }
    check("Q2 导出件自描述：标题 + 元信息头（时间/条数/token 用量）") {
        assertContains("Q2", md, "appendLine(\"# \${record.title}\")")
        assertContains("Q2", md, "导出时间：")
        assertContains("Q2", md, "消息条数：")
        assertContains("Q2", md, "Token 用量：")
    }
    check("Q3 按轮组织 + 只导选中变体 + 多变体标注（废弃变体不进导出件）") {
        assertContains("Q3", md, "## 第 \${index + 1} 轮")
        assertContains("Q3", md, "turn.variants.getOrNull(turn.selectedIndex)")
        assertContains("Q3", md, "*（变体 \${turn.selectedIndex + 1}/\${turn.variants.size}）*")
    }
    check("Q4 工具卡折叠为引用块 + 代码块，超长截断带注记") {
        assertContains("Q4", md, "> 🔧 工具 `\${rec.toolName")
        assertContains("Q4", md, "appendLine(\"```\")")
        assertContains("Q4", md, "…（已截断，原文共 \$length 字）")
    }
    check("Q5 VM 只读导出入口（空会话返空串，UI 据此不弹面板）") {
        assertContains("Q5", vm, "fun conversationMarkdown(convId: String): String")
        assertContains("Q5", vm, "if (rec.messages.isEmpty()) return \"\"")
        assertContains("Q5", vm, "import com.beichen.polaris.core.conversationToMarkdown")
    }
    check("Q6 分享机制共用（消息分享与会话导出同一 shareText，不复制粘贴）") {
        assertContains("Q6", share, "internal fun shareText(context: Context, text: String, chooserTitle: String)")
        assertContains("Q6", share, "Intent.createChooser(send, chooserTitle)")
        assertContains("Q6", chat, "private fun shareMessage(context: Context, text: String) = shareText(context, text, \"分享消息\")")
    }
    check("Q7 抽屉长按菜单导出项 + AppScaffold 两处接线（宽屏/窄屏不漂移）") {
        assertContains("Q7", scaffold, "title = \"导出 Markdown\"")
        assertContains("Q7", scaffold, "onExport = { menuConvId = null; onExportConversation(id) }")
        assertContains("Q7", scaffold, "onExportConversation = { id ->")
        // hotfix29：导出从 shareText 纯文本切换为 shareMarkdownFile .md 真文件
        assertContains("Q7", scaffold, "shareMarkdownFile(ctx, title, vm.conversationMarkdown(id), \"导出会话\")")
        assertTrue("Q7", scaffold.split("onExportConversation = { id ->").size >= 3)
        assertContains("Q7", scaffold, "val ctx = LocalContext.current")
    }
}

/**
 * R 区：功能五 P1-4b「全局备份/恢复」——对标 RikkaHub Backup.kt（ZIP + manifest 信封）
 * 与 Coomi data.ts 导出；净室重写。
 *
 * 断言的链路完整性：core 数据契约 + ZIP 纯函数（版本门槛/条数校验/白名单）→
 * 三个 store 的公开重灌 API（不搬 raw prefs）→ VM 导出/恢复入口（SAF Uri）→
 * 设置页双闸门（确认对话框 + 文件选择器）。
 */
private fun testBackupRestore() {
    println("— R 区：全局备份/恢复（功能五 P1-4b） —")
    val models = readSource("core/BackupModels.kt")
    val archive = readSource("core/BackupArchive.kt")
    val convStore = readSource("core/ConversationStore.kt")
    val memStore = readSource("memory/MemoryStore.kt")
    val cfgStore = readSource("core/ConfigStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val settings = readSource("ui/SettingsScreen.kt")

    check("R1 core 数据契约：manifest 信封 + ConfigBackup（profilesJson 存原文，恢复与 loadProfiles 共用解码）") {
        assertContains("R1", models, "data class BackupManifest(")
        assertContains("R1", models, "val version: Int")
        assertContains("R1", models, "val conversationCount: Int")
        assertContains("R1", models, "data class ConfigBackup(")
        assertContains("R1", models, "val profilesJson: String? = null")
        assertContains("R1", models, "@Serializable")
    }
    check("R2 ZIP 纯函数：白名单 entry + 版本门槛 + 条数一致性 + zip 炸弹上限") {
        assertContains("R2", archive, "object BackupArchive")
        assertContains("R2", archive, "const val VERSION = 1")
        assertContains("R2", archive, "manifest.version > VERSION")
        assertContains("R2", archive, "entry.name in ENTRY_WHITELIST")
        assertContains("R2", archive, "MAX_TOTAL_BYTES")
        assertContains("R2", archive, "备份条目数与清单不符")
        // 纯函数零 Android 依赖：harness 断言 file 里无 android import
        assertTrue("R2", !archive.contains("import android."))
    }
    check("R3 ConversationStore 重灌：replaceAll 过 migrate（老备份件迁移不丢历史）+ 预约落盘") {
        assertContains("R3", convStore, "fun replaceAll(list: List<ConversationRecord>)")
        assertContains("R3", convStore, "items.addAll(list.map { migrate(it) })")
        assertContains("R3", convStore, "fun snapshot(): List<ConversationRecord>")
    }
    check("R4 MemoryStore 重灌：restoreAll = replaceAll + persist（恢复后进程被杀也不丢）") {
        assertContains("R4", memStore, "fun restoreAll(list: List<MemoryItem>)")
        assertContains("R4", memStore, "fun snapshot(): List<MemoryItem>")
    }
    check("R5 ConfigStore 恢复经公开 API（saveProfiles/saveFavoriteModels/media key/github，不搬 raw prefs）") {
        assertContains("R5", cfgStore, "fun restoreFrom(backup: ConfigBackup)")
        assertContains("R5", cfgStore, "fun loadAllMediaKeys(): Map<String, String>")
        assertContains("R5", cfgStore, "saveProfiles(profiles, backup.activeProfileId")
        assertContains("R5", cfgStore, "saveFavoriteModels(backup.favoriteModels)")
        assertContains("R5", cfgStore, "saveGithubConfig(")
    }
    check("R6 VM 导出入口：快照 → BackupArchive.create → SAF OutputStream，IO 全离主线程") {
        // P4-3：备份流程整体搬进 BackupUseCase，VM 只保留同名入口转发。
        // 断言仍要求「入口在 VM、实现在用例」，两边都锚住——只锚一边的话，
        // 谁把流程又抄回 VM 都不会被发现。
        val backup = readSource("ui/BackupUseCase.kt")
        assertContains("R6", vm, "fun exportBackupTo(uri: Uri) = backup.exportTo(uri)")
        assertContains("R6", backup, "fun exportTo(uri: Uri)")
        assertContains("R6", backup, "conversations.snapshot()")
        assertContains("R6", backup, "memories.snapshot()")
        assertContains("R6", backup, "BackupArchive.create(")
        assertContains("R6", backup, "app.contentResolver.openOutputStream(uri)")
        assertContains("R6", backup, "scope.launch(Dispatchers.IO)")
    }
    check("R7 VM 恢复入口：信封校验先行（坏包不碰生产数据）→ 重灌 → UI 状态全量重载") {
        val backup = readSource("ui/BackupUseCase.kt")
        assertContains("R7", vm, "fun restoreBackupFrom(uri: Uri) = backup.restoreFrom(uri)")
        assertContains("R7", backup, "BackupArchive.read(bytes)")
        assertContains("R7", backup, "conversations.replaceAll(convs)")
        assertContains("R7", backup, "memories.restoreAll(mems)")
        assertContains("R7", backup, "config.restoreFrom(it)")
        // UI 重载留在宿主（它是 ViewModel 的职责），但必须由用例回调触发
        assertContains("R7", vm, "loadConversation(conversationStore.ensureNotEmpty())")
        assertContains("R7", vm, "fun consumeBackupState()")
    }
    check("R8 设置页双闸门：恢复先 danger 确认对话框，再开 SAF 文件选择器") {
        assertContains("R8", settings, "ActivityResultContracts.CreateDocument(\"application/zip\")")
        assertContains("R8", settings, "ActivityResultContracts.OpenDocument()")
        assertContains("R8", settings, "var confirmRestore by remember { mutableStateOf(false) }")
        assertContains("R8", settings, "title = \"恢复备份\"")
        assertContains("R8", settings, "danger = true")
        assertContains("R8", settings, "importLauncher.launch(arrayOf(\"application/zip\"))")
        // W-68：「备份与恢复」不再单独成组，并进「数据」组。断言改查导出入口仍在设置页，
        // 不锁组标题——分组会随信息架构调整，导出/恢复这两个动作不会消失。
        assertContains("R8", settings, "title = \"导出备份\"")
    }
}

/**
 * S 区：功能五 P1-7「输入栏快捷指令/提示词库」——对标 Coomi PromptLibrary.vue
 * （SavedPrompt + BUILTIN 另存为语义）与 QuickCommands（一键填入）；RikkaHub 的
 * PromptLibrary 实为注入位点、Operit 无此功能，故以 Coomi 为唯一参照，净室重写。
 *
 * 链路：core 纯函数（净化/内置保护）→ PromptStore 持久化 → VM CRUD + 填入输入框 →
 * 输入栏闪电按钮 + 选择器 → 管理页/设置页入口/路由。
 */
private fun testPromptLibrary() {
    println("— S 区：输入栏快捷指令/提示词库（功能五 P1-7） —")
    val models = readSource("core/PromptModels.kt")
    val lib = readSource("core/PromptLibrary.kt")
    val store = readSource("core/PromptStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val chat = readSource("ui/ChatScreen.kt")
    val picker = readSource("ui/PromptLibraryScreen.kt")
    val scaffold = readSource("ui/AppScaffold.kt")
    val settings = readSource("ui/SettingsScreen.kt")

    check("S1 数据契约 + 6 条内置（builtin 字段而非字符串前缀猜语义）") {
        assertContains("S1", models, "data class PromptItem(")
        assertContains("S1", models, "val builtin: Boolean = false")
        assertContains("S1", lib, "val BUILTIN_PROMPTS: List<PromptItem> = listOf(")
        assertTrue("S1", lib.split("PromptItem(").size - 1 >= 6)
        assertContains("S1", lib, "id = \"builtin:explain\"")
        assertContains("S1", lib, "id = \"builtin:phone-task\"")
    }
    check("S2 读入净化：空字段/重复 id 丢弃，标签 trim+去重（坏数据不进内存）") {
        assertContains("S2", lib, "fun sanitizePrompts(raw: List<PromptItem>): List<PromptItem>")
        assertContains("S2", lib, "if (id.isEmpty() || title.isEmpty() || content.isEmpty()) return@mapNotNull null")
        assertContains("S2", lib, "if (!seen.add(id)) return@mapNotNull null")
        assertContains("S2", lib, "item.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()")
    }
    check("S3 upsert 规则：内置拒收（另存为自定义）/ 空内容拒收 / 同 id 替换否则追加") {
        assertContains("S3", lib, "fun upsertPrompt(items: List<PromptItem>, draft: PromptItem): PromptUpsertResult")
        assertContains("S3", lib, "if (draft.builtin) return PromptUpsertResult.Rejected(\"内置提示词不可修改，请另存为自定义\")")
        assertContains("S3", lib, "items.map { if (it.id == id) entry else it }")
        assertContains("S3", lib, "items + entry")
    }
    check("S4 删除内置保护 + 另存为复制（新 id，内容继承）") {
        assertContains("S4", lib, "fun deletePrompt(items: List<PromptItem>, id: String): PromptDeleteResult")
        assertContains("S4", lib, "if (target.builtin) return PromptDeleteResult.Protected")
        assertContains("S4", lib, "fun duplicateAsCustom(items: List<PromptItem>, id: String, newId: String): PromptItem?")
        assertContains("S4", lib, "fun newPromptId(now: Long, random: Int): String = \"p-\$now-\${random.toString(16).padStart(6, '0')}\"")
    }
    check("S5 PromptStore 持久化纪律：原子写 tmp+bak + 首次种内置 + 补种不覆盖用户数据") {
        assertContains("S5", store, "class PromptStore(file: File)")
        // R12 起原子写收敛到 AtomicJsonFile（tmp → fsync → rename，默认留 .bak）。
        // 原先断言 `tmpFile.renameTo(file)` 是把「怎么实现」写死：换实现就必须改断言，
        // 而它真正要守的是「不得直接 writeText 覆盖主文件」这条纪律。
        assertContains("S5", store, "AtomicJsonFile.write(file, payload)")
        assertTrue(
            "S5: PromptStore 不得绕过原子写直接覆盖主文件",
            !store.contains("file.writeText(payload)")
        )
        assertContains("S5", store, "val merged = mergeWithBuiltins(parsed ?: emptyList())")
        assertContains("S5", store, "val missing = BUILTIN_PROMPTS.filter { it.id !in have }")
        assertContains("S5", store, "return missing + cleaned")
    }
    check("S6 VM 接线：CRUD + 填入输入框（追加语义不毁用户已输入）") {
        assertContains("S6", vm, "private val promptStore = PromptStore(File(app.filesDir, \"prompts.json\"))")
        assertContains("S6", vm, "val prompts: StateFlow<List<PromptItem>>")
        assertContains("S6", vm, "fun savePrompt(draft: PromptItem): PromptUpsertResult")
        assertContains("S6", vm, "fun deletePrompt(id: String): PromptDeleteResult")
        assertContains("S6", vm, "fun duplicatePromptAsCustom(id: String): String?")
        assertContains("S6", vm, "fun insertPromptIntoInput(content: String)")
        assertContains("S6", vm, "input.value = if (current.isBlank()) content else \"\$current\\n\$content\"")
    }
    check("S7 输入栏唤起：闪电按钮 + 选择器（点选填入/管理进全功能页）") {
        assertContains("S7", chat, "onOpenPrompts = { showPromptPicker = true }")
        assertContains("S7", chat, "private fun PromptButton(enabled: Boolean, onClick: () -> Unit)")
        assertContains("S7", chat, "contentDescription = \"快捷指令\"")
        assertContains("S7", chat, "vm.insertPromptIntoInput(item.content)")
        assertContains("S7", picker, "fun PromptPickerDialog(")
        assertContains("S7", picker, "onPick: (PromptItem) -> Unit")
        assertContains("S7", picker, "onManage: () -> Unit")
    }
    check("S8 管理页 + 路由 + 设置页入口（三处齐活）") {
        assertContains("S8", picker, "fun PromptLibraryScreen(vm: PromptLibraryContract)")
        assertContains("S8", picker, "title = \"内置提示词\"")
        assertContains("S8", picker, "danger = true")
        assertContains("S8", scaffold, "PROMPTS(\"prompts\", \"提示词库\", \"提示词库\", Icons.Outlined.AutoAwesome)")
        assertContains("S8", scaffold, "AppDest.PROMPTS -> PromptLibraryScreen(vm)")
        assertContains("S8", scaffold, "onManagePrompts = { onNavigate(AppDest.PROMPTS) }")
        assertContains("S8", settings, "title = \"提示词库\"")
        assertContains("S8", settings, "onClick = { onNavigate(AppDest.PROMPTS) }")
    }
}

/**
 * T 区：功能五 P1-8「会话草稿独立保存」——对标 RikkaHub 每会话草稿 /
 * Coomi README 明示；AgentKit 原为「输入框全局单草稿」，切会话即丢。
 *
 * 链路：DraftStore（Map<convId, 草稿> 落盘，不进 conversations.json——
 * 草稿是易失中间态，与消息持久化生命周期不同）→ loadConversation 切走存旧/
 * 载入取新 → send 后清 → 删会话清 → 备份恢复清（id 对不上，防错配复活）。
 */
private fun testConversationDrafts() {
    println("— T 区：会话草稿独立保存（功能五 P1-8） —")
    val store = readSource("core/DraftStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")

    check("T1 DraftStore：Map<convId, 草稿> 单文件，空串=删键，clear 全清") {
        assertContains("T1", store, "class DraftStore(file: File)")
        assertContains("T1", store, "json.encodeToString(synchronized(lock) { drafts.toMap() })")
        assertContains("T1", store, "if (text.isBlank()) drafts.remove(convId) else drafts[convId] = text")
        assertContains("T1", store, "fun clear()")
        // 读入净化：手改文件/旧版本可能留空串
        assertContains("T1", store, "if (k.isNotBlank() && v.isNotBlank()) drafts[k] = v")
        // 不进 conversations.json：草稿与消息生命周期不同（注释即契约）
        assertContains("T1", store, "为什么不并入 ConversationRecord")
    }
    check("T2 切会话草稿随人走：切走前存旧会话，载入后取新会话填回输入框") {
        assertContains("T2", vm, "private val draftStore = DraftStore(File(app.filesDir, \"drafts.json\"))")
        assertContains("T2", vm, "draftStore.set(_activeConversationId.value, input.value)")
        assertContains("T2", vm, "input.value = draftStore.get(id)")
    }
    check("T3 发送后清草稿（切走再回来不复活刚发过的原文）") {
        assertContains("T3", vm, "draftStore.remove(_activeConversationId.value)")
        val sendBlock = vm.substringAfter("fun send() {").substringBefore("fun stop()")
        assertContains("T3", sendBlock, "草稿使命结束")
    }
    check("T4 删会话连带清草稿（会话没了草稿是垃圾数据）") {
        val delBlock = vm.substringAfter("fun deleteConversation(id: String) {").substringBefore("fun rename(")
        assertContains("T4", delBlock, "draftStore.remove(id)")
    }
    check("T5 备份恢复清草稿（旧 id 与新数据对不上，防陌生草稿错配复活）") {
        // P4-3：恢复流程在 BackupUseCase，草稿清理必须与重灌同处一个事务里
        val restoreBlock = readSource("ui/BackupUseCase.kt").substringAfter("fun restoreFrom(uri: Uri)")
        assertContains("T5", restoreBlock, "drafts.clear()")
        assertContains("T5", restoreBlock, "草稿不随备份走")
    }
    check("T6 草稿不泄密进备份件（备份只含 conversations/memories/config 三成员 + manifest）") {
        val exportBlock = vm.substringAfter("fun exportBackupTo(uri: Uri)").substringBefore("fun restoreBackupFrom")
        assertTrue("T6", !exportBlock.contains("draftStore"))
    }
}

/**
 * U 区：功能五 P1-9「推理过程折叠」——对标 Coomi/RikkaHub 的「思考过程」交互
 * （流式中默认展开让用户围观模型思考、完成态折叠可点开复盘）。
 *
 * 链路：三家解析（OpenAI reasoning_content/reasoning、Claude thinking block、
 * Gemini thought:true part）→ ChatStreamEvent.ReasoningDelta 与 TextDelta 分流
 * → AssistantMessage.reasoning 与 content 分字段存储（防自言自语进下一轮历史）
 * → ConversationStore 落盘/还原 → ChatViewModel.reasoningDraft → ChatScreen
 * ReasoningBlock（streaming=true 展开 / false 折叠）。
 */
private fun testReasoningFold() {
    println("— U 区：推理过程折叠（功能五 P1-9） —")
    val event = readSource("core/llm/ChatStreamEvent.kt")
    val parsers = readSource("core/llm/ReasoningParsers.kt")
    val client = readSource("core/LLMClient.kt")
    val claude = readSource("core/llm/ClaudeClient.kt")
    val gemini = readSource("core/llm/GeminiClient.kt")
    val realLoop = readSource("core/agent/RealAgentLoop.kt")
    val contract = readSource("core/AgentContract.kt")
    val store = readSource("core/ConversationStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val screen = readSource("ui/ChatScreen.kt")

    check("U1 事件分流：ReasoningDelta 与 TextDelta 平级，思考不进正文打字机") {
        assertContains("U1", event, "data class ReasoningDelta(val text: String) : ChatStreamEvent")
        // 注释即契约：分流理由写进事件定义
        assertContains("U1", event, "与 [TextDelta] 分流：思考内容不是最终答案")
        assertContains("U1", event, "混进正文会让「回答」里多出一段模型的自言自语")
    }
    check("U2 三家解析纯函数：缺失返 null（没给思考 ≠ 给了空思考）") {
        assertContains("U2", parsers, "fun parseOpenAiReasoning(delta: JsonObject): String?")
        assertContains("U2", parsers, "delta[\"reasoning_content\"]?.primOrNull()?.contentOrNull?.let { return it }")
        assertContains("U2", parsers, "return delta[\"reasoning\"]?.primOrNull()?.contentOrNull")
        // Claude 三重门槛：content_block_delta + delta.type==thinking_delta + delta.thinking
        assertContains("U2", parsers, "if (obj[\"type\"]?.primOrNull()?.contentOrNull != \"content_block_delta\") return null")
        assertContains("U2", parsers, "if (delta[\"type\"]?.primOrNull()?.contentOrNull != \"thinking_delta\") return null")
        assertContains("U2", parsers, "return delta[\"thinking\"]?.primOrNull()?.contentOrNull")
        // Gemini：只认 thought=="true" 的 part
        assertContains("U2", parsers, "if (part[\"thought\"]?.primOrNull()?.contentOrNull != \"true\") return null")
        assertContains("U2", parsers, "return part[\"text\"]?.primOrNull()?.contentOrNull")
    }
    check("U3 OpenAI 兼容系：思考增量发 ReasoningDelta，Completed 第四参带 reasoning") {
        assertContains("U3", client, "import com.beichen.polaris.core.llm.parseOpenAiReasoning")
        assertContains("U3", client, "val reasoning = StringBuilder()")
        assertContains("U3", client, "parseOpenAiReasoning(delta)?.let {")
        assertContains("U3", client, "send(ChatStreamEvent.ReasoningDelta(it))")
        assertContains("U3", client, "reasoning.toString().takeIf { it.isNotBlank() }")
    }
    check("U4 Claude：thinking 块进 reasoning，与 text 块分流") {
        assertContains("U4", claude, "val reasoning = StringBuilder()")
        assertContains("U4", claude, "\"thinking\" -> obj[\"thinking\"]?.primOrNull()?.contentOrNull?.let { reasoning.append(it) }")
        assertContains("U4", claude, "reasoning.toString().takeIf { it.isNotBlank() }")
    }
    check("U5 Gemini：thought part 进 reasoning，正式回答 part 才进 content") {
        assertContains("U5", gemini, "if (parseGeminiThought(obj) != null) {")
        assertContains("U5", gemini, "obj[\"text\"]?.primOrNull()?.contentOrNull?.let { reasoning.append(it) }")
        assertContains("U5", gemini, "reasoning.toString().takeIf { it.isNotBlank() }")
        // 注释即契约：分流防「自言自语进下一轮历史」
        assertContains("U5", gemini, "思考灌进正文会让下一轮历史带上自言自语")
    }
    check("U6 agent 层：ReasoningDelta 事件转回调，UI 直连传入") {
        assertContains("U6", realLoop, "is ChatStreamEvent.ReasoningDelta -> onReasoningDelta?.invoke(event.text)")
        assertContains("U6", realLoop, "private val onReasoningDelta: ((String) -> Unit)? = null,")
        // 适配层已删除：回调由 ChatViewModel 直接传给 RealAgentLoop，不再有中间透传层
        assertContains("U6", vm, "onReasoningDelta = ::handleReasoningDelta,")
    }
    check("U7 消息模型：AssistantMessage.reasoning 与 content 分字段（默认 null）") {
        assertContains("U7", contract, "val reasoning: String? = null")
    }
    check("U8 落盘：MsgRecord.reasoning 截断存储，还原时空白不还原") {
        assertContains("U8", store, "val reasoning: String = \"\"")
        assertContains("U8", store, "reasoning = reasoning.orEmpty().take(MAX_FIELD_CHARS)")
        assertContains("U8", store, "reasoning = reasoning.takeIf { it.isNotBlank() }")
        // 多版本会话记录还原同样带 reasoning
        assertContains("U8", store, "reasoning = sel.reasoning.takeIf { it.isNotBlank() }")
    }
    check("U9 VM：reasoningDraft 状态 + handleReasoningDelta 共享 streamDraftLock") {
        // P4-4：草稿锁随状态搬进 GenerationRunController；两条增量必须落在**同一把**锁上，
        // 否则「思考」与「正文」的相对时序会乱。
        val gen = readSource("ui/GenerationRunController.kt")
        assertContains("U9", vm, "private fun handleReasoningDelta(delta: String) {")
        assertContains("U9", vm, "generation.appendReasoning(delta)")
        assertContains("U9", vm, "onReasoningDelta = ::handleReasoningDelta,")
        assertContains("U9", gen, "fun appendText(delta: String) = synchronized(draftLock) {")
        assertContains("U9", gen, "fun appendReasoning(delta: String) = synchronized(draftLock) {")
        assertContains("U9", gen, "private val _reasoning = MutableStateFlow<String?>(null)")
        assertContains("U9", vm, "val reasoningDraft: StateFlow<String?> = generation.reasoning")
        // append 落库路径透传 reasoning（send/regenerate 的历史消息也能折叠展示）
        // 同上：多行构造后按片段断言（reasoning 仍在、usage 仍在）
        assertContains("U9", readSource("ui/ConversationProjection.kt"), "reasoning = msg.reasoning")
        // 流结束/失败等复位点清 reasoning 草稿（hotfix30 起：清的是 reasoningBuffer，
        // 显示态经 syncDraftDisplay 投影，不能留着上一条的思考）
        assertTrue("U9", gen.split("reasoningBuffer = null").size - 1 >= 3)
        // 4.3：缓冲改 StringBuilder 后投影要 toString；守的意图不变（切走清显示、切回恢复）
        assertContains("U9", gen, "_reasoning.value = if (isOwnerActive()) reasoningBuffer?.toString() else null")
    }
    check("U10 UI：ReasoningBlock 流式展开/完成折叠，null 不渲染") {
        assertContains("U10", screen, "reasoning: String? = null")
        assertContains("U10", screen, "if (reasoning != null) {\n                            ReasoningBlock(reasoning = reasoning, expandedInitially = true)")
        assertContains("U10", screen, "if (reasoning != null) {\n                            ReasoningBlock(reasoning = reasoning, expandedInitially = false)")
        assertContains("U10", screen, "private fun ReasoningBlock(reasoning: String, expandedInitially: Boolean) {")
        // 折叠头常驻可反复开合；展开图标随状态旋转
        assertContains("U10", screen, "var expanded by remember { mutableStateOf(expandedInitially) }")
        assertContains("U10", screen, ".clickable { expanded = !expanded }")
        assertContains("U10", screen, ".rotate(if (expanded) 180f else 0f)")
        assertContains("U10", screen, "reasoning = item.reasoning")
    }
}

/**
 * V 区：功能五 P1-10「外观增强」——Material You 动态取色 + 回答完成提醒通知，
 * 对标 RikkaHub dynamic theme / Coomi 完成提醒。
 *
 * 纪律：动态取色与主题模式同纪律（进程内有效不落盘，默认关——「暖纸」品牌色是
 * deliberate 设计，壁纸取色是 opt-in 覆盖，且只覆盖 M3 标准色角色，语义色不跟随）；
 * 完成提醒是**落盘**开关（用户显式 opt-in 的侵入式能力，跨启动保持），
 * 且只在 App 退到后台时发（前台提醒是噪音），权限被拒/被关静默降级。
 */
private fun testAppearanceEnhancements() {
    println("— V 区：外观增强：动态取色 + 完成提醒（功能五 P1-10） —")
    val theme = readSource("ui/Theme.kt")
    val activity = readSource("AgentKitApplication.kt")
    val notifier = readSource("core/CompletionNotifier.kt")
    val store = readSource("core/ConfigStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val settings = readSource("ui/SettingsScreen.kt")
    // 3.1 第三批：设置开关状态随 SettingsFacade 搬出 VM，判据跟着读 facade
    val settingsFacadeSrc = readSource("ui/SettingsFacade.kt")
    // W-67：动态取色开关从设置页搬到「壁纸与颜色」子页，断言跟着搬
    val appearance = readSource("ui/AppearanceScreen.kt")
    val scaffold = readSource("ui/AppScaffold.kt")

    check("V1 动态取色：默认关 + API 31 门禁 + 只覆盖 M3 标准色角色") {
        assertContains("V1", theme, "dynamicColor: Boolean = false,")
        assertContains("V1", theme, "Build.VERSION.SDK_INT >= Build.VERSION_CODES.S")
        assertContains("V1", theme, "dynamicDarkColorScheme(context)")
        assertContains("V1", theme, "dynamicLightColorScheme(context)")
        // 注释即契约：语义色（暖纸品牌色）不跟随壁纸取色
        assertContains("V1", theme, "语义色（LocalAgentKitColors 的暖纸 surface-1/2 等品牌色）")
    }
    check("V2 根组合：外观偏好由 Activity 作用域持有并下发（W-64 起落盘）") {
        // W-64：明暗 / 取色 / 强调色三项统一交给 AppearanceState 持有并写盘。
        // 原来用 rememberSaveable 局部变量，进程一死就回默认——自定义强调色等于白设。
        // 断言查的是「状态在 Activity 作用域、且真的下发到主题与设置页」，不是变量名。
        val main = readSource("MainActivity.kt")
        assertContains("V2", main, "AppearanceState.load(")
        assertContains("V2", main, "val dynamicColor = AppearanceState.dynamicColor")
        assertContains("V2", main, "accent = accent")
        assertContains("V2", main, "onDynamicColorChange = { AppearanceState.setDynamicColor(ctx, it) }")
    }
    check("V3 前台判定：Application ActivityLifecycleCallbacks started/stopped 计数") {
        assertContains("V3", activity, "registerActivityLifecycleCallbacks")
        assertContains("V3", activity, "override fun onActivityStarted(activity: Activity) {")
        assertContains("V3", activity, "override fun onActivityStopped(activity: Activity) {")
        // 计数必须原子读改写：@Volatile 只保证可见性，`x = x - 1` 仍会丢计数
        assertContains("V3", activity, "private val foregroundActivities = AtomicInteger(0)")
        assertContains("V3", activity, "fun isAppInForeground(): Boolean = foregroundActivities.get() > 0")
        // started/stopped 计数而非 resumed/paused：半透明权限对话框不误判为后台
        assertContains("V3", activity, "已 started 未 stopped 的 Activity 数")
    }
    check("V4 CompletionNotifier：后台才发 + 通知总开关检查 + 静默降级") {
        assertContains("V4", notifier, "private const val CHANNEL_ID = \"answer_complete\"")
        // 3.2：前台判定改为 app 层注入的探针，notifier 不再 import 宿主 Application
        assertContains("V4", notifier, "if (foregroundProbe()) return")
        assertContains("V4", notifier, "if (!nm.areNotificationsEnabled()) return")
        assertContains("V4", notifier, "runCatching {")
        // 通知正文只放预览（截断 60 字），不发全文
        assertContains("V4", notifier, "preview.take(60)")
        // 渠道与 agent 业务通知渠道刻意分开
        assertContains("V4", notifier, "与 [com.beichen.polaris.tools.BuiltInTools] 的 agent 通知渠道**刻意分开**")
    }
    check("V5 ConfigStore：完成提醒开关落盘，默认 false（opt-in）") {
        assertContains("V5", store, "private const val KEY_COMPLETION_NOTIFY = \"completion_notify\"")
        assertContains("V5", store, "sp.getBoolean(KEY_COMPLETION_NOTIFY, false)")
        assertContains("V5", store, "sp.edit().putBoolean(KEY_COMPLETION_NOTIFY, enabled).apply()")
    }
    check("V6 VM：开关 StateFlow + 通知钩子只挂 send 成功路径") {
        assertContains("V6", settingsFacadeSrc, "private val _completionNotifyEnabled = MutableStateFlow(configStore.isCompletionNotifyEnabled())")
        assertContains("V6", settingsFacadeSrc, "val completionNotifyEnabled: StateFlow<Boolean> = _completionNotifyEnabled.asStateFlow()")
        assertContains("V6", vm, "fun setCompletionNotifyEnabled(enabled: Boolean)")
        assertContains("V6", vm, "CompletionNotifier.notifyAnswerComplete(")
        // 只挂 send：regenerate 是「重做这题」的主动操作，用户盯着概率极高，不通知
        val sendBlock = vm.substringAfter("fun send() {")
        assertContains("V6", sendBlock, "CompletionNotifier.notifyAnswerComplete(")
        val regenBlock = vm.substringAfter("fun regenerate()").substringBefore("fun send()")
        assertTrue("V6", !regenBlock.contains("CompletionNotifier"))
    }
    check("V7 设置页：动态取色行（API 31 门禁）+ 提醒卡 + 权限按需申请") {
        assertContains("V7", appearance, "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {")
        assertContains("V7", appearance, "title = \"动态取色\"")
        assertContains("V7", settings, "title = \"回答完成提醒\"")
        assertContains("V7", settings, "ActivityResultContracts.RequestPermission()")
        assertContains("V7", settings, "notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)")
        assertContains("V7", settings, "Build.VERSION_CODES.TIRAMISU")
        assertContains("V7", settings, "ContextCompat.checkSelfPermission(")
        // 关开关不需要权限直接落盘；开开关才走权限流程
        assertContains("V7", settings, "vm.setCompletionNotifyEnabled(false)")
        assertContains("V7", settings, "vm.setCompletionNotifyEnabled(true)")
    }
    check("V8 AppScaffold：动态取色参数穿透到设置页（宽窄两路 ScreenHost 同参）") {
        assertContains("V8", scaffold, "dynamicColor: Boolean = false,")
        assertContains("V8", scaffold, "onDynamicColorChange: (Boolean) -> Unit = {}")
        assertTrue("V8", scaffold.split("dynamicColor, onDynamicColorChange,").size - 1 >= 2)
        assertContains("V8", scaffold, "dynamicColor,")
        assertContains("V8", scaffold, "onDynamicColorChange,")
    }
}

/**
 * W 区：hotfix28「提示词库进备份」——真机反馈「备份只备聊天记录」，
 * 根因：hotfix24 提示词库做得比 hotfix23 备份晚，PromptStore 漏挂挡。
 *
 * 设计：与 config 成员同款前向兼容纪律——manifest 加 promptCount（默认 0）/
 * hasPrompts（默认 false）；老备份件没这两个字段 → 反序列化吃默认值 → 恢复时
 * 跳过该成员不动现有提示词；新备份件声明了就必需 prompts.json 且条数相符。
 */
private fun testPromptBackup() {
    println("— W 区：提示词库进备份（hotfix28） —")
    val models = readSource("core/BackupModels.kt")
    val archive = readSource("core/BackupArchive.kt")
    val promptStore = readSource("core/PromptStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")

    check("W1 manifest：promptCount/hasPrompts 带默认值（老备份件前向兼容）") {
        assertContains("W1", models, "val promptCount: Int = 0")
        assertContains("W1", models, "val hasPrompts: Boolean = false")
        // 注释即契约：默认值 = 老备份件没带，恢复时不动现有提示词
        assertContains("W1", models, "默认 0 = 老备份件没带提示词库")
    }
    check("W2 归档层：prompts.json 成员 + 白名单 + 条数校验") {
        assertContains("W2", archive, "internal const val ENTRY_PROMPTS = \"prompts.json\"")
        assertContains("W2", archive, "promptsJson: String? = null")
        assertContains("W2", archive, "if (promptsJson != null) zip.putText(ENTRY_PROMPTS, promptsJson)")
        assertContains("W2", archive, "val promptsJson = texts[ENTRY_PROMPTS]")
        // 声明带了就必须有且条数相符；没声明（老包）跳过
        assertContains("W2", archive, "if (manifest.hasPrompts) {")
        assertContains("W2", archive, "return ReadResult.Invalid(\"缺少提示词库数据\")")
        assertContains("W2", archive, "return ReadResult.Invalid(\"提示词条数与清单不符（清单 \${manifest.promptCount}，实际 \$promptCount）\")")
        assertTrue("W2", archive.contains("ENTRY_PROMPTS\n    )") || archive.contains("ENTRY_PROMPTS,\n"))
    }
    check("W3 PromptStore：restoreAll 走公开 API + mergeWithBuiltins 净化") {
        assertContains("W3", promptStore, "fun restoreAll(restored: List<PromptItem>)")
        assertContains("W3", promptStore, "val merged = mergeWithBuiltins(restored)")
        assertContains("W3", promptStore, "persist()")
        // 注释即契约：与 MemoryStore.restoreAll 同纪律
        assertContains("W3", promptStore, "与 [MemoryStore.restoreAll] 同纪律")
    }
    check("W4 VM 导出：prompts 快照进 manifest 与 zip") {
        val export = readSource("ui/BackupUseCase.kt").substringAfter("fun exportTo(uri: Uri)")
        assertContains("W4", export, "val promptItems = prompts.list()")
        assertContains("W4", export, "promptCount = promptItems.size,")
        assertContains("W4", export, "hasPrompts = true")
        assertContains("W4", export, "json.encodeToString(promptItems)")
        assertContains("W4", export, "条提示词")
    }
    check("W5 VM 恢复：老包跳过、新包重灌 + UI 刷新 + 提示文案") {
        val restore = readSource("ui/BackupUseCase.kt").substringAfter("fun restoreFrom(uri: Uri)")
        assertContains("W5", restore, "read.promptsJson?.let { promptText ->")
        assertContains("W5", restore, "json.decodeFromString<List<PromptItem>>(promptText)")
        assertContains("W5", restore, "prompts.restoreAll(it)")
        // UI 刷新在宿主回调里：onRestored(prompts.list()) → VM 设 _prompts.value
        assertContains("W5", restore, "onRestored(prompts.list())")
        assertContains("W5", vm, "_prompts.value = items")
        assertContains("W5", restore, "if (read.manifest.hasPrompts) \"、\${read.manifest.promptCount} 条提示词\" else \"\"")
    }
}

/**
 * X 区：hotfix29「会话导出变 .md 真文件」——真机反馈「我分享的话也是一段话
 * 分享，我不是说变成文件啥的」，根因：hotfix22 导出走 text/plain 的
 * EXTRA_TEXT，到微信/邮件里就是一坨纯文本：无文件名、不能存文件管理器、
 * Markdown 格式特征全丢。
 *
 * 设计：写 cacheDir/exports/<净化标题>.md → FileProvider 换 content://
 * （与 L8 作品库/构建产物安装同一 provider 通道）→ ACTION_SEND +
 * type=text/markdown + EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION。
 * 落 cacheDir 而非 filesDir：导出件是易重建的临时产物，系统清缓存自动
 * 回收，不占用户存储配额。失败三级降级（写盘/取 URI/startActivity）
 * 一律回退 shareText 纯文本——分享动作永远有出口，不崩。
 */
private fun testMarkdownFileExport() {
    println("— X 区：会话导出 .md 真文件（hotfix29） —")
    val share = readSource("ui/ShareActions.kt")
    val scaffold = readSource("ui/AppScaffold.kt")
    val paths = File("app/src/main/res/xml/file_paths.xml").readText()

    check("X1 FileProvider：cacheDir/exports 路径声明（导出 .md 的落点）") {
        assertContains("X1", paths, "<cache-path name=\"exports\" path=\"exports/\" />")
    }
    check("X2 ShareActions：shareMarkdownFile 走 content:// + text/markdown + EXTRA_STREAM") {
        assertContains("X2", share, "internal fun shareMarkdownFile(")
        assertContains("X2", share, "type = \"text/markdown\"")
        assertContains("X2", share, "putExtra(Intent.EXTRA_STREAM, uri)")
        assertContains("X2", share, "addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)")
        assertContains("X2", share, "FileProvider.getUriForFile(context, \"\${context.packageName}.fileprovider\", file)")
        assertContains("X2", share, "File(context.cacheDir, \"exports\")")
    }
    check("X3 降级链：写盘/取 URI/startActivity 三级失败都回退 shareText") {
        // 源码里 shareText(context, content, chooserTitle) 出现 3 次（两级 getOrNull + onFailure）
        assertTrue("X3", share.split("shareText(context, content, chooserTitle)").size - 1 >= 3)
        assertContains("X3", share, ".onFailure {")
    }
    check("X4 文件名净化：非法字符/控制字符/超长/空标题四类全覆盖") {
        assertContains("X4", share, "internal fun sanitizeFileName(raw: String): String")
        assertContains("X4", share, "ILLEGAL_FILE_CHARS")
        assertContains("X4", share, "ch.code < 0x20")
        assertContains("X4", share, ".take(60)")
        assertContains("X4", share, "ifEmpty { \"conversation\" }")
    }
    check("X5 AppScaffold：两处导出入口（移动抽屉+宽屏侧栏）同切文件分享") {
        assertTrue("X5", scaffold.split("shareMarkdownFile(ctx, title, vm.conversationMarkdown(id), \"导出会话\")").size - 1 >= 2)
        assertContains("X5", scaffold, "val title = conversations.firstOrNull { it.id == id }?.title ?: \"会话\"")
    }
    check("X6 shareText 保留：消息分享仍走纯文本（反馈未涉及，不殃及）") {
        val chat = readSource("ui/ChatScreen.kt")
        assertContains("X6", chat, "shareText(context, text, \"分享消息\")")
    }
}

/**
 * Y 区：hotfix30「生成中可切换会话」——真机反馈「我在一个对话发消息之后，
 * 要去那那个对话之间的对话的话，他是得让那个对话回完才行，我不能跳，
 * 不能过其他对话」。
 *
 * 根因：switchConversation 有 isRunning 硬守卫 + 所有在途写读
 * _activeConversationId.value——放开切换必把旧会话的生成结果写进新会话。
 *
 * 解法：owner 捕获（send/regenerate 启动即存 runOwnerConvId，finally 清）+
 * 流式草稿内存缓冲（draftBuffer/reasoningBuffer 为权威态，_streamDraft 只是
 * owner==active 时的投影）+ 显示态全部带 isOwnerActive() 守卫。
 * 切走 → 后台续跑只落盘；切回 → syncDraftDisplay 恢复打字机无缝续看。
 * 保守边界：不放开并行发送（newConversation/send 仍受 isRunning 拦），
 * 在途会话禁删（防删掉的会话被在途写盘复活）。
 */
private fun testConversationSwitchWhileRunning() {
    println("— Y 区：生成中可切换会话（hotfix30） —")
    val vm = readSource("ui/ChatViewModel.kt")
    val scaffold = readSource("ui/AppScaffold.kt")
    val chat = readSource("ui/ChatScreen.kt")

    check("Y1 VM：owner 捕获机制（runOwnerConvId + 草稿缓冲 + 投影开关 + 生成中标识流）") {
        // P4-4：运行态整体搬进 GenerationRunController，VM 只剩两个转发与四个流
        val gen = readSource("ui/GenerationRunController.kt")
        assertContains("Y1", gen, "var owner: String? = null")
        // 4.3 起缓冲是 StringBuilder（追加摊还 O(1)）；仍是「私有可变字段」，意图不变
        assertContains("Y1", gen, "private var draftBuffer: StringBuilder? = null")
        assertContains("Y1", gen, "private var reasoningBuffer: StringBuilder? = null")
        assertContains("Y1", gen, "fun isOwnerActive(): Boolean")
        assertContains("Y1", vm, "private fun isOwnerActive(): Boolean = generation.isOwnerActive()")
        assertContains("Y1", vm, "private fun syncDraftDisplay() = generation.syncDisplay()")
        assertContains("Y1", vm, "val generatingConvId: StateFlow<String?> = generation.generating")
    }
    check("Y2 send/regenerate：启动捕获 owner + finally 全套收尾（W-54 起由 TurnRunner 唯一实现）") {
        // W-54：这两段原各写一份，靠 6 条「与 send 同策略」注释同步；现合并为 TurnRunner。
        // 断言随之改为「两处都调用」+「TurnRunner 内确有全套复位」——
        // 比原来的「同一字符串出现 ≥2 次」更贴意图：一处定义、两处调用。
        // 若将来有人把 begin/finish 拆回两份，这两组断言会同时失效。
        // P4-4：开场/收尾合并成一次 start(...)，调用方只给 body 与三个开关。
        // 断言两头都锚：调用点必须传对参数，控制器内部必须确有全套复位。
        val gen = readSource("ui/GenerationRunController.kt")
        assertContains("Y2 send.begin", vm, "generation.start(owner, placeholderDraft = false, rebuildMessages = true)")
        assertContains("Y2 regen.begin", vm, "generation.start(owner, placeholderDraft = true, rebuildMessages = false)")
        assertContains("Y2 begin 捕获 owner", gen, "this.owner = owner")
        assertContains("Y2 finish 清 owner", gen, "owner = null")
        assertContains("Y2 finish 清 generating", gen, "_generating.value = null")
        assertContains("Y2 finish 清草稿", gen, "draftBuffer = null")
        assertContains("Y2 finish 清 retry", vm, "_retryState.value = null")
        // 收尾只有一处出口：控制器的 finally → onFinished
        assertContains("Y2 单一出口", gen, "onFinished(rebuildMessages)")
        // 收尾入口唯一：旧的两份 finally 模板（turnRunner.finish + 各自 persistToolGroups）已删
        assertTrue("Y2 旧收尾模板已删", !vm.contains("turnRunner."))
    }
    check("Y3 在途写按 owner：appendToStore/appendVariant/流式草稿三通道") {
        // P4-2：落库目标由投影统一算（owner ?: active），三条通道共用同一个入口
        val proj = readSource("ui/ConversationProjection.kt")
        val appendStore = proj.substringAfter("fun appendToStore(item: UiItem)")
        assertContains("Y3", appendStore, "store.appendMessage(ownerId() ?: activeId(), item.toMsgRecord())")
        // regenerate 成功/失败两条 appendVariant 都按 owner（切走期间结果仍写回原会话）
        assertTrue("Y3", vm.split("appendVariant(\n").size >= 2)
        assertTrue("Y3", vm.contains("owner, turnIndex, variant.toMsgRecord()"))
        assertTrue("Y3", vm.contains("owner, turnIndex, err.toMsgRecord()"))
        // 流式增量先攒缓冲再条件投影（切走续攒、切回恢复）
        val gen = readSource("ui/GenerationRunController.kt")
        // 4.3：`buf = (buf ?: "") + delta` 改成 StringBuilder 追加；仍守「先攒缓冲再条件投影」
        assertContains("Y3", gen, "val buf = draftBuffer ?: StringBuilder().also { draftBuffer = it }")
        assertContains("Y3", gen, "val buf = reasoningBuffer ?: StringBuilder().also { reasoningBuffer = it }")
    }
    check("Y4 显示态守卫：_messages 追加全部带 isOwnerActive()（后台只落盘不上屏）") {
        // W-47 起追加统一走 appendUiItem()（读改写进 msgLock），守卫改在调用处：
        // ToolStarted / append() / send 的 stopItem / send 的 errItem 四处。
        assertTrue("Y4", vm.split("if (isOwnerActive()) appendUiItem(").size - 1 >= 3)
        assertContains("Y4", vm, "private fun appendUiItem(item: UiItem)")
    }
    check("Y5 switchConversation：去掉 isRunning 硬守卫 + 切后同步草稿投影") {
        val sw = vm.substringAfter("fun switchConversation(id: String)")
            .substringBefore("fun deleteConversation")
        assertTrue("Y5", !sw.contains("if (isRunning.value) return"))
        assertContains("Y5", sw, "loadConversation(id)")
        assertContains("Y5", sw, "syncDraftDisplay()")
    }
    check("Y6 deleteConversation：在途会话（含非 active 的后台生成会话）禁删防复活") {
        val del = vm.substringAfter("fun deleteConversation(id: String)")
        assertContains("Y6", del, "if (id == generation.owner) return")
    }
    check("Y7 UI：抽屉生成中标识 + 删除在途 Toast + 空闲会话输入说明行") {
        assertContains("Y7", scaffold, "generatingConvId: String? = null")
        assertContains("Y7", scaffold, "isGenerating = conv.id == generatingConvId")
        assertContains("Y7", scaffold, "CircularProgressIndicator(")
        assertContains("Y7", scaffold, "正在生成")
        assertContains("Y7", scaffold, "该对话正在生成，请先停止或等待完成")
        assertContains("Y7", chat, "val generatingConvId by vm.generatingConvId.collectAsStateWithLifecycle()")
        assertContains("Y7", chat, "runningNotice = if (generatingConvId != null && generatingConvId != activeConversationId)")
        assertContains("Y7", chat, "有其他对话正在生成，本对话需等待或停止后才能发送")
    }
}

/**
 * Z 区：hotfix31「记忆库交互补强」——真机反馈「记忆库是说搞个和 OPeritAI
 * 差不多的，但是你现在只学到了样子，人家可以移动，可以点开，可以看，
 * 但是你这个不行，这只学到一个布局的样子而已」。
 *
 * 三块补强（零新依赖）：
 *  ① 列表/图谱双模式（默认列表）：Canvas 节点真机小而难戳，LazyColumn 好扫好点；
 *  ② 记忆可编辑：MemoryStore.update + 详情卡「编辑」对话框（content/salience/tags）；
 *  ③ 图谱节点可拖动：dragOffsets 会话内偏移，边线/标签/命中全部经动态 centerOf。
 */
private fun testMemoryInteraction() {
    println("— Z 区：记忆库交互补强（hotfix31） —")
    val screen = readSource("ui/MemoryScreen.kt")
    val store = readSource("memory/MemoryStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val memFacade = readSource("ui/MemoryFacade.kt")

    // W-68 起默认回到图谱：上一版默认列表的前提是「图谱只是摆好的环、不能动」，
    // 现在图谱是真力导向（可拖可缩、邻居会跟着走），视觉与交互都成立，没必要再藏起来。
    check("Z1 双模式：默认图谱 + 模式 chip 行 + LazyColumn 列表") {
        assertContains("Z1", screen, "var listMode by rememberSaveable { mutableStateOf(false) }")
        assertContains("Z1", screen, "text = \"列表\",")
        assertContains("Z1", screen, "text = \"图谱\",")
        assertContains("Z1", screen, "private fun MemoryList(")
        assertContains("Z1", screen, "items(memories, key = { it.id })")
        assertContains("Z1", screen, "LazyColumn(")
    }
    check("Z2 编辑链路：Store.update 净化纪律 + VM 透传 + 详情卡入口") {
        assertContains("Z2", store, "fun update(id: String, content: String, salience: Int, tags: List<String>): Boolean")
        // 与 add 同款净化：trim + 截断 + clamp + tags 去空白去重；空白拒收
        assertContains("Z2", store, "val normalized = content.trim().take(MAX_CONTENT_CHARS)")
        assertContains("Z2", store, "salience.coerceIn(MIN_SALIENCE, MAX_SALIENCE)")
        assertContains("Z2", store, "tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()")
        assertContains("Z2", store, "if (normalized.isBlank()) return false")
        assertContains("Z2", vm, "fun updateMemory(id: String, content: String, salience: Int, tags: List<String>): Boolean")
        assertContains("Z2", memFacade, "val ok = memoryStore.update(id, content, salience, tags)")
        assertContains("Z2", screen, "onEdit = {")
    }
    check("Z3 编辑对话框：content/salience 滑杆/tags 三件套 + 失败保持不关") {
        assertContains("Z3", screen, "private fun MemoryEditDialog(")
        assertContains("Z3", screen, "Slider(")
        assertContains("Z3", screen, "valueRange = 1f..10f")
        assertContains("Z3", screen, "label = \"标签\"")
        // 拒收（空白内容/目标不在）时 Toast 且不关对话框
        assertContains("Z3", screen, "Toast.makeText(context, \"内容不能为空\", Toast.LENGTH_SHORT).show()")
        assertContains("Z3", screen, "vm.updateMemory(")
    }
    // W-68：拖动不再靠「给节点加一个偏移量」——那样边不知道点被搬走了，看着像贴纸。
    // 现在是布局引擎持有坐标：拖动直接写世界坐标并重新加热，邻居被弹簧拉着动。
    check("Z4 图谱拖动：布局引擎持有坐标（dragTo）+ 邻居被弹簧带动 + 收敛后停帧") {
        assertContains("Z4", screen, "val layout = remember(nodes) { MemoryForceLayout(nodes.size, edges, radiusPx) }")
        assertContains("Z4", screen, "var draggingIndex by remember { mutableIntStateOf(-1) }")
        // 命中与拖动都问引擎要坐标，不再有第二份「偏移量」
        assertContains("Z4", screen, "layout.nearest(")
        assertContains("Z4", screen, "layout.dragTo(")
        assertContains("Z4", screen, "layout.reheat()")
        // 每帧推进布局，且**静止时挂起**：snapshotFlow 等 wake，不空转 60fps
        assertContains("Z4", screen, "layout.step()")
        assertContains("Z4", screen, "snapshotFlow { wake }.first { it != seen }")
        // 双指缩放/平移：以双指中心为锚
        assertContains("Z4", screen, "calculateZoom()")
        assertContains("Z4", screen, "calculatePan()")
        assertTrue("Z4 不得残留 dragOffsets（旧的手动偏移模型）", !screen.contains("dragOffsets"))
    }
    check("Z5 详情卡：编辑按钮 alongside 忘记（error 色只留给危险动作）") {
        assertContains("Z5", screen, "AkTextButton(text = \"编辑\", onClick = onEdit)")
        assertContains("Z5", screen, "onEdit: () -> Unit = {}")
    }
}

/**
 * AA 区：hotfix32「聊天背景图」——真机反馈「我不是说我可以用我照片的壁纸
 * 当成壁纸」：hotfix27 动态取色只染控件色，用户要的是照片本体当对话页背景。
 *
 * 设计：SAF 选图（ACTION_OPEN_DOCUMENT + FLAG_GRANT_PERSISTABLE_URI_PERMISSION
 * + takePersistableUriPermission）→ URI 轻量字符串落 ConfigStore（位图不占
 * App 存储）→ ChatScreen Box 背景层（Image Crop + alpha + surface scrim 保
 * 深浅两主题可读）→ 浓度滑杆 0.05..0.60。位图零依赖两遍解码（inSampleSize），
 * 不引 Coil/Picasso（全 App 外部依赖零新增纪律）。
 */
private fun testChatBackground() {
    println("— AA 区：聊天背景图（hotfix32） —")
    val config = readSource("core/ConfigStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val screen = readSource("ui/ChatScreen.kt")
    // W-67：背景图分区从设置页搬到「壁纸与颜色」子页（ChatBackgroundSection → BackgroundSection）
    val appearance = readSource("ui/AppearanceScreen.kt")
    val loader = File("app/src/main/java/com/beichen/polaris/ui/BackgroundLoader.kt").readText()
    // 3.1 第三批：背景图状态随 AppearanceFacade 搬出 VM，判据跟着读 facade
    val bgFacade = readSource("ui/AppearanceFacade.kt")

    check("AA1 ConfigStore：URI + 浓度两键（URI 存轻量字符串，位图不占存储）") {
        assertContains("AA1", config, "private const val KEY_CHAT_BG_URI = \"chat_bg_uri\"")
        assertContains("AA1", config, "private const val KEY_CHAT_BG_ALPHA = \"chat_bg_alpha\"")
        assertContains("AA1", config, "fun chatBackgroundUri(): String?")
        assertContains("AA1", config, "fun setChatBackgroundUri(uri: String?)")
        assertContains("AA1", config, "fun chatBackgroundAlpha(): Float = sp.getFloat(KEY_CHAT_BG_ALPHA, 0.30f)")
        assertContains("AA1", config, "alpha.coerceIn(0.05f, 1.0f)")
    }
    check("AA2 VM：chatBackgroundUri/Alpha 状态 + setter（落盘即时生效）") {
        assertContains("AA2", bgFacade, "private val _chatBackgroundUri = MutableStateFlow(configStore.chatBackgroundUri())")
        assertContains("AA2", bgFacade, "val chatBackgroundUri: StateFlow<String?> = _chatBackgroundUri.asStateFlow()")
        assertContains("AA2", bgFacade, "val chatBackgroundAlpha: StateFlow<Float> = _chatBackgroundAlpha.asStateFlow()")
        assertContains("AA2", vm, "fun setChatBackground(uri: String?)")
        assertContains("AA2", vm, "fun setChatBackgroundAlpha(alpha: Float)")
    }
    check("AA3 零依赖位图加载：两遍解码 + inSampleSize + 失败安静降级") {
        assertContains("AA3", loader, "suspend fun loadChatBackground(")
        assertContains("AA3", loader, "inJustDecodeBounds = true")
        assertContains("AA3", loader, "inSampleSize = calculateInSampleSize(")
        assertContains("AA3", loader, "withContext(Dispatchers.IO)")
        assertContains("AA3", loader, ".getOrNull()")
        // 纪律注释即契约：不引 Coil/Picasso
        assertContains("AA3", loader, "不引 Coil/Picasso")
    }
    check("AA4 全局背景层 Image + scrim（hotfix33 起挂 App 根层，AppBackgroundLayer）") {
        assertContains("AA4", loader, "fun AppBackgroundLayer(vm: ChatBackgroundRef)")
        assertContains("AA4", loader, "ContentScale.Crop")
        assertContains("AA4", loader, "alpha = alpha")
        assertContains("AA4", loader, "MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)")
        assertContains("AA4", loader, "loadChatBackground(context, it, config.screenWidthDp, config.screenHeightDp)")
    }
    check("AA5 壁纸与颜色页：SAF 持久化选图 + 换一张/清除 + 浓度滑杆") {
        assertContains("AA5", appearance, "private fun BackgroundSection(vm: ChatAppearanceContract)")
        assertContains("AA5", appearance, "Intent.ACTION_OPEN_DOCUMENT")
        assertContains("AA5", appearance, "FLAG_GRANT_PERSISTABLE_URI_PERMISSION")
        assertContains("AA5", appearance, "takePersistableUriPermission(")
        assertContains("AA5", appearance, "vm.setChatBackground(uri.toString())")
        assertContains("AA5", appearance, "vm.setChatBackground(null)")
        assertContains("AA5", appearance, "valueRange = 0.05f..1.0f")
    }
}

/**
 * AB 区：hotfix33「真机三连」——
 *  ① **DeepSeek 间歇崩溃**（最高优先级真 Bug）：报「出错了：Element class
 *     kotlinx.serialization.json.JsonNull is not a JsonObject」，测试连接正常、
 *     聊天随机崩。根因：kotlinx 的 .jsonObject/.jsonPrimitive/.jsonArray 是硬转换，
 *     遇到 JSON 显式 null（DeepSeek 收尾块 `"delta": null`、非推理回合
 *     `"reasoning_content": null`）即抛。hotfix26 引入 reasoning 解析后开始踩。
 *     修法：JsonSafe 三个 as? 软转换，LLM 链路 93 处全替换 + 去 !!。
 *  ② 背景浓度上限 60% → **100%**（用户原话「60% 都不够清楚，要 100%；为什么
 *     要设置 60%，我们自己不可以弄吗」——浓度是用户的选择）。
 *  ③ 背景图仅对话页 → **全 App**（「不只是要聊天的地方变，其他地方也要变」）。
 */
private fun testJsonSafeAndGlobalBackground() {
    println("— AB 区：JSON 安全化 + 背景 100%/全局（hotfix33） —")
    // 3.5 拆模块：JsonSafe 已随 core 移到 :core，走 readSource 的双根解析
    val safe = readSource("core/llm/JsonSafe.kt")
    val llm = readSource("core/LLMClient.kt")
    val loader = readSource("ui/BackgroundLoader.kt")
    val config = readSource("core/ConfigStore.kt")
    // W-67：背景浓度滑杆随背景图分区搬到「壁纸与颜色」子页
    val appearance = readSource("ui/AppearanceScreen.kt")
    val scaffold = readSource("ui/AppScaffold.kt")
    val chat = readSource("ui/ChatScreen.kt")

    check("AB1 JsonSafe：三个 as? 软转换访问器 + 根因文档") {
        assertContains("AB1", safe, "fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject")
        assertContains("AB1", safe, "fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray")
        // W-47 补漏：JsonNull 继承 JsonPrimitive，单用 as? 会放行 → 必须显式排除
        assertContains("AB1", safe, "fun JsonElement?.primOrNull(): JsonPrimitive?")
        assertContains("AB1", safe, "(this as? JsonPrimitive)?.takeIf { it !is JsonNull }")
        assertContains("AB1", safe, "is not a JsonObject")
        assertContains("AB1", safe, "\"reasoning_content\": null")
    }
    check("AB2 LLM 链路零裸强转（7 文件全扫——崩溃根治的硬证据）") {
        val files = listOf(
            "core/LLMClient.kt",
            "core/llm/ReasoningParsers.kt",
            "core/llm/TokenUsageParsers.kt",
            "core/llm/ClaudeClient.kt",
            "core/llm/GeminiClient.kt",
            "core/llm/local/LocalLlamaClient.kt",
            "core/ModelListFetcher.kt"
        )
        files.forEach { rel ->
            val src = readSource(rel)
            assertTrue("AB2($rel) 残留 .jsonObject", !src.contains(".jsonObject"))
            assertTrue("AB2($rel) 残留 .jsonPrimitive", !src.contains(".jsonPrimitive"))
            assertTrue("AB2($rel) 残留 .jsonArray", !src.contains(".jsonArray"))
        }
    }
    check("AB3 LLMClient：SSE delta 安全取值链 + parseResponse 去 !! 去 first()") {
        assertContains(
            "AB3", llm,
            "obj[\"choices\"]?.arrOrNull()?.firstOrNull()?.objOrNull()?.get(\"delta\")?.objOrNull()"
        )
        assertContains("AB3", llm, "val root = json.parseToJsonElement(text).objOrNull()")
        assertContains("AB3", llm, "?: return AssistantMessage(\"\", emptyList(), null)")
        assertTrue("AB3", !llm.contains("arrOrNull().first()"))
        assertTrue("AB3", !llm.contains("root[\"choices\"]!!"))
    }
    check("AB4 背景浓度：clamp 到 1.0（100%）+ 滑杆 range 同步") {
        assertContains("AB4", config, "alpha.coerceIn(0.05f, 1.0f)")
        assertContains("AB4", appearance, "valueRange = 0.05f..1.0f")
        assertTrue("AB4", !appearance.contains("valueRange = 0.05f..0.60f"))
    }
    check("AB5 全局背景：AppBackgroundLayer + 根底色透明切换 + 对话页不再自绘") {
        assertContains("AB5", loader, "fun AppBackgroundLayer(vm: ChatBackgroundRef)")
        assertContains("AB5", scaffold, "AppBackgroundLayer(vm)")
        assertContains("AB5", scaffold, "if (hasAppBackground) Color.Transparent")
        assertContains("AB5", scaffold, "else MaterialTheme.colorScheme.background")
        assertContains("AB5", scaffold, "val hasAppBackground = !appBgUri.isNullOrBlank()")
        // 对话页旧自绘层已移除（避免与全局层双份解码）
        assertTrue("AB5", !chat.contains("bgConfig.screenWidthDp"))
        assertContains("AB5", chat, "背景图已提升到 App 根层")
    }
}

/**
 * AC 区：hotfix34「供应商全量适配」——真机反馈「你问我哪家的模型，为什么一定要
 * 哪家的？你不能说全部都弄吗？现在大市面上比较出名的都弄一下，或者说全部适配一下」。
 *
 * 设计：ProviderCatalog 数据驱动目录（国内/海外/聚合/本地/原生 共 30+ 家），
 * 每家标注协议风格（openai/claude/gemini/local-llama）；ProviderManager.create
 * 遇未注册 id 按目录风格兜底、未收录 id 一律 OpenAI 兼容——新增供应商零代码。
 */
private fun testProviderCatalog() {
    println("— AC 区：供应商全量适配（hotfix34） —")
    val catalog = readSource("core/llm/ProviderCatalog.kt")
    val llm = readSource("core/LLMClient.kt")
    val modelScreen = readSource("ui/ModelConfigScreen.kt")

    check("AC1 目录：30+ 家齐全 + 关键厂商在册") {
        // 每家 = 一条 LlmProvider(...)；用 id = 计数
        val count = Regex("LlmProvider\\(\\s*\\n?\\s*id = ").findAll(catalog).count()
        assertTrue("AC1 目录条数=$count（期望 >= 30）", count >= 30)
        listOf(
            "STYLE_OPENAI, label = \"OpenAI 兼容\"", "\"deepseek\"", "\"moonshot\"", "\"zhipu\"",
            "\"qwen\"", "\"doubao\"", "\"ernie\"", "\"hunyuan\"", "\"spark\"", "\"minimax\"",
            "\"stepfun\"", "\"yi\"", "\"xai\"", "\"mistral\"", "\"perplexity\"", "\"groq\"",
            "\"together\"", "\"deepinfra\"", "\"cerebras\"", "\"openrouter\"", "\"siliconflow\"",
            "\"modelscope\"", "\"ollama\"", "\"lmstudio\"", "\"vllm\"",
            "STYLE_CLAUDE, label = \"Claude\"", "STYLE_GEMINI, label = \"Gemini\"",
            "STYLE_LOCAL_LLAMA, label = \"本地模型（内置）\""
        ).forEach { needle -> assertContains("AC1", catalog, needle) }
    }
    check("AC2 协议风格：三类云端 + 本地全覆盖 + 本地免 Key") {
        assertContains("AC2", catalog, "const val STYLE_OPENAI = \"openai\"")
        assertContains("AC2", catalog, "const val STYLE_CLAUDE = \"claude\"")
        assertContains("AC2", catalog, "const val STYLE_GEMINI = \"gemini\"")
        assertContains("AC2", catalog, "const val STYLE_LOCAL_LLAMA = \"local-llama\"")
        // 本地部署标 requiresApiKey = false（Ollama / LM Studio / vLLM / 内置）
        val localNoKey = Regex("requiresApiKey = false").findAll(catalog).count()
        assertTrue("AC2 本地免 Key 条数=$localNoKey（期望 >= 4）", localNoKey >= 4)
    }
    check("AC3 前向兼容：未收录 id 一律按 OpenAI 兼容兜底") {
        assertContains("AC3", catalog, "fun styleOf(id: String): String = byId(id)?.style ?: STYLE_OPENAI")
        assertContains("AC3", catalog, "都会被正确当作 OpenAI 兼容处理")
    }
    check("AC4 ProviderManager：未注册 id 按目录风格兜底造客户端") {
        assertContains("AC4", llm, "val style = com.beichen.polaris.core.llm.ProviderCatalog.styleOf(name)")
        assertContains("AC4", llm, "?: providers[com.beichen.polaris.core.llm.ProviderCatalog.STYLE_OPENAI]")
        assertContains("AC4", llm, "新增供应商 = 加一条目录数据，零代码零发版")
    }
    check("AC5 UI：全量选择器（常用 chip 横滑 + 全部供应商分组弹窗）") {
        assertContains("AC5", modelScreen, "import com.beichen.polaris.core.llm.ProviderCatalog")
        assertContains("AC5", modelScreen, "AkChip(text = \"全部供应商…\", selected = false")
        assertContains("AC5", modelScreen, "private fun ProviderAllDialog(")
        assertContains("AC5", modelScreen, "ProviderGroup.entries.forEach { group ->")
        assertContains("AC5", modelScreen, "private fun ProviderOptionRow(")
    }
    check("AC6 UI：默认值委托目录 + 本地部署免 Key 门禁") {
        assertContains("AC6", modelScreen, "fun defaultUrl(id: String): String = ProviderCatalog.defaultUrl(id).ifBlank {")
        assertContains("AC6", modelScreen, "val needsKey = ProviderCatalog.requiresApiKey(providerId)")
        assertContains("AC6", modelScreen, "val canTest = isLocal || (baseUrl.isNotBlank() && (!needsKey || apiKey.isNotBlank()))")
        assertContains("AC6", modelScreen, "val isLocal = providerId == ProviderCatalog.STYLE_LOCAL_LLAMA")
    }
}

/**
 * AD 区：hotfix35「流式非 SSE 降级」——供应商适配的深水区。
 *
 * 场景：部分端点/中转忽略 `stream: true`，直接返回**整段 JSON**（无任何 data: 行）。
 * 旧实现在逐行循环里 `if (!line.startsWith("data:")) continue`，一条都没匹配上 →
 * 最终 emit Completed(空回答)——用户看到「不逐字、有时干脆空白」。
 * （真机「不逐字显示」的另一半根因；与 hotfix33 的 null 崩溃是两条独立路径。）
 *
 * 修法：读取时同步留一份原文 + 标记是否见过 data: 行；整段无 data: → 判定为
 * 非流式响应，走 parseResponse 降级，把整条回答一次性作为增量发出。三家通道
 * （OpenAI 兼容 / Claude / Gemini）同法对称处理；OpenAI 降级路径同时补上
 * reasoning_content 提取（否则降级后思维链整段丢失）。
 */
private fun testSseFallback() {
    println("— AD 区：流式非 SSE 降级（hotfix35） —")
    val llm = readSource("core/LLMClient.kt")
    val claude = readSource("core/llm/ClaudeClient.kt")
    val gemini = readSource("core/llm/GeminiClient.kt")

    check("AD1 读取层：SseReadOutcome + sawData 判定 + 原文留存") {
        assertContains("AD1", llm, "private sealed interface SseReadOutcome {")
        assertContains("AD1", llm, "object Sse : SseReadOutcome")
        assertContains("AD1", llm, "data class PlainBody(val raw: String) : SseReadOutcome")
        assertContains("AD1", llm, "var sawData = false")
        assertContains("AD1", llm, "val rawFallback = StringBuilder()")
        assertContains("AD1", llm, "return@use if (sawData) SseReadOutcome.Sse else SseReadOutcome.PlainBody(rawFallback.toString())")
        assertContains("AD1", llm, "private val PLAIN_BODY_MAX = 2 * 1024 * 1024")
    }
    check("AD2 streamChat/streamText：PlainBody 降级成一次性输出 + Completed(整条)") {
        assertContains("AD2", llm, "if (outcome is SseReadOutcome.PlainBody) {")
        assertContains("AD2", llm, "val parsed = runCatching { parseResponse(outcome.raw.trim()) }.getOrNull()")
        assertContains("AD2", llm, "if (parsed.content.isNotBlank()) send(ChatStreamEvent.TextDelta(parsed.content))")
        assertContains("AD2", llm, "parsed.reasoning?.let { send(ChatStreamEvent.ReasoningDelta(it)) }")
        assertContains("AD2", llm, "send(ChatStreamEvent.Completed(parsed))")
        assertContains("AD2", llm, "return@channelFlow")
        // streamText 通道同样降级
        val streamText = llm.substringAfter("override fun streamText(").substringBefore("override fun streamChat(")
        assertContains("AD2", streamText, "if (outcome is SseReadOutcome.PlainBody) {")
    }
    check("AD3 降级路径保留思维链：parseResponse 提取 reasoning_content/reasoning") {
        assertContains("AD3", llm, "val reasoning = msg[\"reasoning_content\"]?.primOrNull()?.contentOrNull")
        assertContains("AD3", llm, "?: msg[\"reasoning\"]?.primOrNull()?.contentOrNull")
        assertContains("AD3", llm, "reasoning?.takeIf { it.isNotBlank() }")
    }
    check("AD4 三家通道对称：Claude / Gemini 同款 sawData 降级") {
        assertContains("AD4", claude, "var sawData = false")
        assertContains("AD4", claude, "if (!sawData) {")
        assertContains("AD4", claude, "parseResponse(rawFallback.toString().trim())")
        assertContains("AD4", gemini, "var sawData = false")
        assertContains("AD4", gemini, "if (!sawData) {")
        assertContains("AD4", gemini, "parseResponse(rawFallback.toString().trim())")
    }
}

/**
 * AE 区：hotfix36「新建档案不再继承上一家供应商 + 降级留痕」。
 *
 * 真机反馈：「我创建新模型的时候，他自动变成那个 deepseek 了」——当时正用
 * DeepSeek，addProfile 沿用了当前档案的 providerId，用户以为被锁死在 DeepSeek。
 * 修法：新建档案固定中性默认 OpenAI 兼容（占市面绝大多数，自建/中转也走它），
 * 由用户主动选；新建后 Toast 引导「先选供应商、填 Key，再保存」。
 * 顺带把 hotfix35 的流式降级写入日志（用户「不逐字」的疑问可在日志页对上号）。
 */
private fun testProfileProviderDefault() {
    println("— AE 区：新建档案供应商中性默认 + 降级留痕（hotfix36） —")
    val vm = readSource("ui/ChatViewModel.kt")
    val modelScreen = readSource("ui/ModelConfigScreen.kt")
    val llm = readSource("core/LLMClient.kt")
    val claude = readSource("core/llm/ClaudeClient.kt")
    val gemini = readSource("core/llm/GeminiClient.kt")

    check("AE1 addProfile：不再继承当前供应商，中性默认 OpenAI 兼容") {
        assertTrue("AE1 仍继承旧供应商", !vm.contains("providerId = it.providerId"))
        val fn = vm.substringAfter("fun addProfile(name: String? = null): String")
        assertContains("AE1", fn, "providerId = ProviderCatalog.STYLE_OPENAI")
        // 注释即契约：记录真机反馈与决策理由
        assertContains("AE1", fn, "自动变成那个 deepseek 了")
        assertContains("AE1", vm, "import com.beichen.polaris.core.llm.ProviderCatalog")
    }
    check("AE2 新建后引导下一步（v4 起改由对话框承载，不再靠 Toast）") {
        // 原实现：addProfile 后弹 Toast「请先选供应商、填 API Key、再点保存」。
        // v4 删掉重复列表后，新建统一走 AddProfileDialog——对话框本身就是引导，
        // 再补一条 Toast 属于重复提示。断言改为验证对话框存在且标题明确。
        assertTrue(
            "AE2 又出现了重复的 Toast 引导",
            "已新建配置：请先选供应商、填 API Key，再点保存" !in modelScreen
        )
        assertContains("AE2", modelScreen, "private fun AddProfileDialog")
        assertContains("AE2", modelScreen, "新建模型配置")
    }
    check("AE3 降级留痕：三家通道把「未按 SSE 返回」写进日志") {
        assertContains("AE3", llm, "供应商未按 SSE 返回（stream:true 被忽略），已降级为整段响应输出")
        assertContains("AE3", claude, "Claude 通道未按 SSE 返回（stream:true 被忽略），已降级为整段响应输出")
        assertContains("AE3", gemini, "Gemini 通道未按 SSE 返回（alt=sse 被忽略），已降级为整段响应输出")
    }
}

fun main() {
    println("== hotfix11 模型快速切换 + 功能四/五 验证 ==")
    testModelQuickSwitch()
    println()
    testMessageCopy()
    println()
    testImageAttachment()
    println()
    testProviderListEntry()
    println()
    testConversationPinAndSearch()
    println()
    testMessageMoreActions()
    println()
    testTtsReading()
    println()
    testFileAttachment()
    println()
    testModelPickerEnhancements()
    println()
    testTokenUsage()
    println()
    testConversationExport()
    println()
    testBackupRestore()
    println()
    testPromptLibrary()
    println()
    testConversationDrafts()
    println()
    testReasoningFold()
    println()
    testAppearanceEnhancements()
    println()
    testPromptBackup()
    println()
    testMarkdownFileExport()
    println()
    testConversationSwitchWhileRunning()
    println()
    testMemoryInteraction()
    println()
    testChatBackground()
    println()
    testJsonSafeAndGlobalBackground()
    println()
    testProviderCatalog()
    println()
    testSseFallback()
    println()
    testProfileProviderDefault()
    println()
    println("== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED -> $it") }
        throw AssertionError("HOTFIX11_VERIFICATION: FAILED")
    }
    println("HOTFIX11_VERIFICATION: ALL PASS")
}
