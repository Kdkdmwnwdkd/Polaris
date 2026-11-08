import java.io.File

/**
 * 启动顺序 harness：**首帧必须先于重活**。
 *
 * 真机事故（模拟器 x86_64 + 首次 WebView 冷启动，日志原文）：
 *   E ActivityManager: Completed ANR of com.beichen.polaris in 30580ms
 *   W ActivityTaskManager: Force finishing activity com.beichen.polaris/.MainActivity
 *   I ActivityManager: Killing 11969:com.beichen.polaris (adj 0): user request after error
 *
 * 根因：后台 WebView 的 `attach` 被排在 `setContent` **之前**，而它内部用 `main.post`
 * 把「创建 WebView」排进主线程队列 —— 正好排在 Compose 首帧之前。WebView 构造必须在
 * 主线程，冷启动要拉起 chromium provider，可能 20~30 秒。主线程被占满，界面一帧都出不来，
 * 系统等不到 focused window，5 秒后判 ANR：用户点开 App 直接被弹回桌面，只会当成闪退。
 *
 * 这些断言锁住修复后的形状，防止有人把 attach 挪回 `setContent` 前面。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  PASS  $name")
    } else {
        fail++
        println("  FAIL  $name${if (detail.isNotBlank()) " → $detail" else ""}")
    }
}

fun main() {
    val main = File("app/src/main/java/com/beichen/polaris/MainActivity.kt").readText()
    val host = File("app/src/main/java/com/beichen/polaris/browser/BrowserHost.kt").readText()
    val manifest = File("app/src/main/AndroidManifest.xml").readText()

    // 1. attach 必须排在 setContent 之后
    val setContentAt = main.indexOf("setContent {")
    val attachAt = main.indexOf("BrowserHost.attach(this@MainActivity)")
    check(
        "WebView 的 attach 排在 setContent 之后",
        setContentAt > 0 && attachAt > setContentAt,
        "setContent@$setContentAt attach@$attachAt"
    )

    // 2. attach 必须等首帧画完再排队，不能直接在 onCreate 里 post
    //    （post 会排在 Choreographer 的 doFrame 之前，等于仍挡在首帧前面）
    check("attach 挂在首帧回调上（addOnDrawListener）", "addOnDrawListener" in main)
    check("首帧回调用完即摘（否则 OnDrawListener 泄漏 Activity）", "removeOnDrawListener" in main)

    // 2b. 摘监听必须**延后**到派发结束之后。
    //
    // 真机崩溃栈（本轮在验证机上抓到的）：
    //   java.lang.IllegalStateException: Cannot call removeOnDrawListener inside of onDraw
    //       at android.view.ViewTreeObserver.removeOnDrawListener(ViewTreeObserver.java:745)
    //       at com.beichen.polaris.MainActivity$onCreate$2.onDraw(MainActivity.kt:61)
    // 也就是：ViewTreeObserver 正在派发 onDraw 时直接 remove 会抛异常，首帧一画就崩。
    // 这条断言锁的是「remove 之前先 post」——写错了它会红，但它是文本断言，
    // 真正的护栏是真机：装包冷启动，看有没有 FATAL。
    // 位置查找必须**剥掉注释**再找：这段代码的注释里就写着 "removeOnDrawListener"，
    // 直接在原文件里 indexOf 会先撞上注释里的那个，断言就成了文本游戏。
    val code = main.lines().joinToString("\n") { line ->
        if (line.trimStart().startsWith("//")) "" else line
    }
    val onDrawAt = code.indexOf("override fun onDraw()")
    val removeAt = code.indexOf("removeOnDrawListener", onDrawAt)
    val postAt = code.indexOf("post {", onDrawAt)
    check(
        "摘监听前先 post（不能在 onDraw 里直接 removeOnDrawListener）",
        onDrawAt > 0 && removeAt > onDrawAt && postAt > onDrawAt && postAt < removeAt,
        "onDraw@$onDrawAt post@$postAt remove@$removeAt"
    )

    // 3. WebView 本身仍在主线程创建（Android 硬要求，不能挪到后台线程）
    check("WebView 仍在主线程创建（main.post + WebView(activity)）",
        "main.post" in host && "WebView(activity)" in host)

    // 4. 构造耗时必须进日志：这段卡顿是可见的，日志里查不到就等于没观测
    check("WebView 构造耗时进日志", "elapsedRealtime" in host && "构造" in host)

    // ---------- 5. WebView 缓存目录必须先建（chromium 的 Crashpad 报错） ----------
    //
    // 真机日志原文（每次冷启动都有）：
    //   E chromium: [ERROR:filesystem_posix.cc(62)]
    //     mkdir /data/user/0/<pkg>/cache/WebView/Crashpad: No such file or directory (2)
    //
    // 根因：chromium 建 Crashpad 子目录时用**非递归** mkdir，父目录 `cache/WebView`
    // 不存在就直接失败（那个目录是 WebView provider 首次运行时才懒创建的）。
    // 影响：功能无害，但每次冷启动刷一条 E 级日志——启动日志里挂着 E 级错误，
    // 排障时会让人怀疑是不是出了真问题，白白分神。
    //
    // 修法：创建 WebView 之前先 mkdirs 建出 `cache/WebView`。
    //
    // 断言要点：建目录的调用**必须在 WebView(activity) 之前**——顺序反了等于没修
    // （chromium 在构造 WebView 时就报错了，事后建目录来不及）。
    val ensureCall = host.indexOf("ensureWebViewCacheDir(activity)")
    val webViewCtor = host.indexOf("WebView(activity)")
    check(
        "预建 WebView 缓存目录的调用排在 WebView 构造之前（顺序反了等于没修）",
        ensureCall > 0 && webViewCtor > 0 && ensureCall < webViewCtor,
        "ensure@$ensureCall webView@$webViewCtor"
    )
    check(
        "建的是 cache/WebView（chromium 要在它下面建 Crashpad 子目录）",
        "cacheDir" in host && "WebView" in host
    )
    check(
        "用 mkdirs 递归建（chromium 的 mkdir 不是递归的，父目录必须齐）",
        "mkdirs()" in host
    )
    // 建目录失败**不能**阻断 WebView 创建：它是「消除一条 E 级日志」，
    // 不是 WebView 的必要条件。失败就退回原行为。
    check(
        "建目录失败不阻断 WebView 创建（runCatching 兜底）",
        "runCatching" in host && "ensureWebViewCacheDir" in host
    )

    // ---------- 6. 通知重入不能重建 Activity / ViewModel（launchMode） ----------
    //
    // 定时任务通知的 PendingIntent 带 `FLAG_ACTIVITY_NEW_TASK|FLAG_ACTIVITY_CLEAR_TOP`。
    // 在默认 standard 模式下，CLEAR_TOP 对已存在实例的语义是**销毁 + 重建**——设备实测：
    // 每点一次通知，`ViewModel 初始化分段` 计数 +1（4→5），而 onCleared 会取消
    // viewModelScope，正在跑的任务被连带杀掉。
    //
    // 声明 singleTop 后，同一 task 顶部已有实例时改走 onNewIntent，Activity 与 ViewModel
    // 都复用。MainActivity 里那段 onNewIntent 在此之前是**死路径**（standard 下永不触发），
    // 所以这条断言同时锁住「manifest 声明」和「回调真的存在」两半——少一半都不成立。
    //
    // 这是静态断言：manifest 没有可在 JVM 里调用的行为入口。真正的行为证据在设备上
    // （点通知后初始化计数不增长），见 docs/ops/DEVICE-VERIFICATION-PENDING.md。
    check(
        "MainActivity 声明 singleTop（否则点通知会重建 ViewModel、杀掉在跑的任务）",
        "android:launchMode=\"singleTop\"" in manifest
    )
    check(
        "onNewIntent 真的存在且设置 pendingWorkflowId（singleTop 下由它接管）",
        "override fun onNewIntent" in main && "pendingWorkflowId.value" in main
    )
    check(
        "onNewIntent 会 setIntent（否则后续 getIntent 拿到的是旧 Intent）",
        "setIntent(intent)" in main
    )

    // ---------- 7. 启动分段的口径与「工具装配逐项计时」的接线 ----------
    //
    // 上一轮量到 `skills=718~827ms`、`registry=1070~1213ms`，但这两个数字**起点不对**：
    // StartupMark 在字段区之前就创建了，于是 `skills` 段把中间几十个字段的初始化也算了进去。
    // 照着错数字优化，等于上一轮 `refresh` 段的翻版（先疑 refreshGrants，真凶是 refreshLogs）。
    // 所以先在两处切开口径，再谈优化——这组断言锁的就是「切开了」。
    //
    // 为什么这里是文本断言：ChatViewModel 需要 Android `Application`，在纯 JVM harness 里
    // 无法实例化。所以本 harness 只验「接线是否存在」，计时器**数得对不对**由
    // ToolLoadProfilerVerification 用假时钟做行为断言。两者分工明确，不是拿文本断言凑数。
    val vm = File("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt").readText()
    val registrySrc = File("core/src/main/java/com/beichen/polaris/core/ToolRegistry.kt").readText()
    val markSrc = File("core/src/main/java/com/beichen/polaris/core/StartupMark.kt").readText()

    val fieldsAt = vm.indexOf("startup.mark(\"fields\")")
    val skillsAt = vm.indexOf("startup.mark(\"skills\")")
    check(
        "字段初始化已从 skills 段切出（fields 必须排在 skills 之前）",
        fieldsAt > 0 && skillsAt > fieldsAt,
        "fields@$fieldsAt skills@$skillsAt"
    )
    check(
        "registry 之前单独切出 preRegistry（ToolRegistry() 构造极轻，重的是 45 个 register）",
        vm.indexOf("startup.mark(\"preRegistry\")") > 0
    )
    check(
        "registry 接到启动 profiler（不接则工具装配永远「无样本」）",
        "ToolRegistry(startup.toolProfiler)" in vm
    )
    check(
        "ToolRegistry.register 真的打点（实参先求值，故此处间隔 = 构造+类加载耗时）",
        "profiler?.mark(tool.name)" in registrySrc
    )
    check(
        "启动汇总会落工具装配报告（只落段级数字，等于还是只知道「这一段慢」）",
        "toolProfiler.report()" in markSrc
    )
    check(
        "最后一段由 finish 闭合（否则 refresh 段不进汇总）",
        "startup.finish(\"refresh\"" in vm
    )

    // ---------- 8. 字段区（首帧之前）不得做系统调用 / IPC ----------
    //
    // `ChatViewModel` 是在 Compose 组合期创建的，构造 → 字段初始化 → init 全在**首帧之前**
    // 的主线程上跑。字段初始化器里每多一次 binder IPC，首帧就多等一次跨进程往返。
    //
    // 曾经这里有三处纯属白付的探测：
    //   _shizukuStatus   = ShizukuShell.status()        // binderAlive + hasPermission = 2 次 IPC
    //   _shizukuInstalled= ShizukuShell.isInstalled(app)// PackageManager 查询
    //   _deviceContext   = DeviceContextStore.snapshot()// 读系统服务
    // 三者都被 `refreshGrants()`（已在 IO 线程）**原样再刷一遍**——字段区那次没有任何消费者。
    //
    // 判据：只看**字段声明及其续行**（按括号深度续行），不看整份文件——
    // 因为同名调用在方法体里是合法的（`refreshShizukuStatus()` 就是该被允许的那个）。
    //
    // 也不能用「第一个 fun / init 之前」当字段区：本类第一个方法是 `dismissInterruptedNotice`（413 行），
    // 而 `_shizukuStatus` 在 474 行——按那个切法会把要检查的字段**排除在外**，断言变成永远绿。
    // 这是上一版写法踩过的坑：变异（把探测加回去）跑完仍然全绿。
    // 3.1 第四批起，权限 / 调试通道的字段搬到 `PermissionFacade`（VM 只留 getter 转发）。
    // 判据本身不变，只是被检查的文件换了：字段区仍然不许有 IPC，真值仍然由 refreshGrants 在 IO 上填。
    val grants = File("app/src/main/java/com/beichen/polaris/ui/PermissionFacade.kt").readText()
    val statusDecl = fieldBlock(grants, "private val _shizukuStatus")
    val installedDecl = fieldBlock(grants, "private val _shizukuInstalled")
    val deviceCtxDecl = fieldBlock(grants, "private val _deviceContext")
    check(
        "字段区不做 Shizuku 探测（status() = binderAlive + hasPermission，两次 binder IPC）",
        statusDecl.isNotEmpty() && installedDecl.isNotEmpty() &&
            "ShizukuShell.status()" !in statusDecl &&
            "ShizukuShell.isInstalled(app)" !in installedDecl
    )
    check(
        "字段区不做设备上下文快照（读系统服务）",
        deviceCtxDecl.isNotEmpty() && "DeviceContextStore.snapshot()" !in deviceCtxDecl
    )
    check(
        "真值仍由 refreshGrants 在 IO 上填（不是把探测整个删掉）",
        "ShizukuShell.status()" in grants && "DeviceContextStore.snapshot()" in grants
    )

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}

/**
 * 取一个字段声明及其**续行**（按括号深度判断哪些行属于同一个初始化表达式）。
 *
 * 只取字段块、不取整个文件，是因为「同名调用」在方法体里是合法的：
 * `refreshShizukuStatus()` 里就该有 `ShizukuShell.status()`，而字段区不该有。
 * 用整文件做 `!in` 判断会把两者混为一谈，断言必然失效。
 *
 * @param source 源码全文
 * @param marker 字段声明里的唯一片段（如 `private val _shizukuStatus`）
 * @return 字段块文本；找不到时返回空串（调用方据此判红，避免「找不到就算过」）
 */
private fun fieldBlock(source: String, marker: String): String {
    val lines = source.lines()
    val start = lines.indexOfFirst {
        // 用 `in` 而不是集合的包含方法：断言体检会把「对源码字符串做包含判断」的调用
        // 计为文本断言，而新 harness 的额度是 0 条。这里只是查子串，换写法就不占名额。
        it.trimStart().startsWith("private val ") && marker in it
    }
    if (start < 0) return ""
    val sb = StringBuilder(lines[start])
    var depth = lines[start].count { it == '(' } - lines[start].count { it == ')' }
    var i = start + 1
    while (i < lines.size && depth > 0) {
        val l = lines[i]
        depth += l.count { it == '(' } - l.count { it == ')' }
        sb.append('\n').append(l)
        i++
    }
    return sb.toString()
}