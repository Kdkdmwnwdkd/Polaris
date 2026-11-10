package com.beichen.polaris.browser

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import com.beichen.polaris.core.AgentLogger
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 后台 WebView 宿主（W-15）。
 *
 * 为什么需要一个「宿主」：
 *  - Android 的 WebView **必须在主线程创建**，且首次创建要绑定一个 Activity 上下文；
 *  - 我们不想给用户弹一个浏览器界面（Agent 应静默操作网页），所以把一个不可见的
 *    WebView 挂在 MainActivity 上，页面加载与 JS 执行都在它里面完成。
 *
 * 生命周期：MainActivity.onCreate → [attach]；onDestroy → [release]。
 * 用 [WeakReference] 持有 Activity 派生的 WebView，避免配置变更（旋转）时泄漏。
 * App 不在前台时 WebView 仍可执行 JS（进程存活即可），但若系统回收了 Activity，
 * 工具会明确报「浏览器不可用，请回到 App」。
 */
object BrowserHost {

    private const val TAG = "BrowserHost"

    private val main = Handler(Looper.getMainLooper())
    private var webRef: WeakReference<WebView>? = null

    /**
     * WebView 没有 `isDestroyed`（那是 Fragment/Activity 的 API），
     * 自己记一个「是否已销毁」标志，避免 destroyed 之后继续 loadUrl 崩溃。
     */
    @Volatile
    private var destroyed = true

    /**
     * 页面加载完成的等待句柄。
     *
     * 用集合而不是单值：原来只有一个槽位，后设置的会覆盖先设置的，于是两个 web_open
     * 并发时先发起的那次永远收不到回调，只能干等到超时。改成多播后每次 open 各等各的，
     * 谁也不会把别人的等待摘掉。CopyOnWrite：写入少（每次导航一加一减）、读取在主线程回调里，
     * 读多写少的场景正好。
     */
    private val pageFinishedListeners = CopyOnWriteArrayList<() -> Unit>()

    internal fun addPageFinishedListener(listener: () -> Unit) {
        pageFinishedListeners.add(listener)
    }

    internal fun removePageFinishedListener(listener: () -> Unit) {
        pageFinishedListeners.remove(listener)
    }

    /** 在主线程创建（或复用）WebView。重复调用是幂等的。 */
    fun attach(activity: Activity) {
        main.post {
            val existing = webRef?.get()
            if (existing != null && !destroyed) return@post
            runCatching {
                // 计时不是为了好看：WebView 冷启动可能耗时 20~30 秒（要拉起 chromium
                // provider），而它必须占着主线程。这段时间里 App 处于「界面已出、暂不
                // 响应」的状态，日志里必须有耗时可查——否则下次再看到启动卡顿，只能猜。
                val t0 = SystemClock.elapsedRealtime()
                AgentLogger.i(TAG, "开始创建后台 WebView")
                ensureWebViewCacheDir(activity)
                val wv = WebView(activity)
                val tConstruct = SystemClock.elapsedRealtime() - t0
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // 去掉 "wv" 标记：部分站点会给 WebView UA 返回阉割版页面，影响 snapshot 质量
                    userAgentString = userAgentString.replace("; wv", "")
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // 审计 F12：query 里可能是 ?code= / ?access_token=，日志只留 host/path。
                        AgentLogger.d(TAG, "页面加载完成：${AgentLogger.redactUrl(url?.take(200) ?: "")}")
                        // 每个等待者自己决定收不收（有人可能已被超时取消，deferred 已完成）
                        pageFinishedListeners.forEach { runCatching { it() } }
                    }
                }
                webRef = WeakReference(wv)
                destroyed = false
                AgentLogger.i(
                    TAG,
                    "后台 WebView 已就绪（构造 ${tConstruct}ms，总计 ${SystemClock.elapsedRealtime() - t0}ms）"
                )
            }.onFailure {
                AgentLogger.w(TAG, "创建 WebView 失败：${it.message}")
            }
        }
    }

    /** 取当前 WebView（必须在主线程访问）。未就绪返回 null。 */
    fun webView(): WebView? = if (destroyed) null else webRef?.get()

    /**
     * 预建 WebView 的缓存目录 `cache/WebView`。
     *
     * ## 为什么必须先建
     *
     * chromium 初始化崩溃上报器时，会在 `cache/WebView/` **下面**再 mkdir 一个
     * `Crashpad` 子目录。系统并不保证 `cache/WebView` 存在（它是 WebView provider
     * 首次运行时才懒创建的），于是每次冷启动都有一条 E 级错误：
     *
     * ```
     * E chromium: [ERROR:filesystem_posix.cc(62)]
     *   mkdir /data/user/0/<pkg>/cache/WebView/Crashpad: No such file or directory (2)
     * ```
     *
     * 影响：功能上无害（崩溃上报只是没落盘），但它每次冷启动都刷一条 E 级日志——
     * 而「启动日志里有 E 级错误」本身就会让人怀疑是不是出了真问题，排障时白白分神。
     *
     * 修法就是字面意义上的「先把父目录建出来」：mkdirs 会递归建出 `cache/WebView`，
     * chromium 随后的 `mkdir Crashpad` 就能成功。
     */
    private fun ensureWebViewCacheDir(activity: Activity) {
        runCatching {
            val dir = File(activity.cacheDir, "WebView")
            if (!dir.exists()) {
                dir.mkdirs()
                AgentLogger.i(TAG, "已预建 WebView 缓存目录：${dir.absolutePath}")
            }
        }.onFailure {
            // 建不出来也不能阻断 WebView 创建——它是「消除一条 E 级日志」，
            // 不是「WebView 的必要条件」。失败就退回原来的行为（有那条错误日志）。
            AgentLogger.w(TAG, "预建 WebView 缓存目录失败：${it.message}")
        }
    }

    /** 释放（Activity 销毁时调用）。 */
    fun release() {
        main.post {
            runCatching {
                webRef?.get()?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    destroy()
                }
            }
            webRef = null
            destroyed = true
            pageFinishedListeners.clear()
            AgentLogger.i(TAG, "后台 WebView 已释放")
        }
    }

    /** 供工具层判断可用性（无需主线程）。 */
    fun isReady(): Boolean = !destroyed && webRef?.get() != null

    /** 给需要 Context 的调用方（如错误提示）。 */
    fun contextOrNull(): Context? = webRef?.get()?.context

    /**
     * 释放并回到「未 attach」状态（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 直接复用 [release]：销毁 WebView 必须在主线程做，而 `release` 已经 `main.post`。
     * reset 的调用方（harness）跑在 JVM 上、没有 Looper，所以这里额外做一次
     * **同步**的引用清空——`release` 投递的 Runnable 在这些环境里不会被执行，
     * 只投递不清空等于没重置。
     */
    fun reset() {
        release()
        webRef = null
        destroyed = true
        pageFinishedListeners.clear()
    }
}
