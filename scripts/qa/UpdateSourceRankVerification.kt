/**
 * 下载线路择优 harness（纯 JVM，零依赖，不用 Android SDK）。
 *
 * ## 为什么单独打这一枪
 *
 * `GitHubMirror.rank` 是整个「多线路」里唯一有判断逻辑的部分：把探测结果变成下载顺序。
 * 它一旦排错，后果不是报错，而是**静默变慢**——用户看到的是进度条爬得慢，
 * 而不是一条红字。这种失败没人会报 bug，只会说「更新好慢」。
 *
 * `ApkDownloader` 那边（并发探测、Range 续传、慢源看门狗）依赖 Context 与 OkHttp，
 * 进不了纯 JVM 门禁，只能真机验；但**排序规则**可以在这里逐条钉死，
 * 也就是把「最容易写错又最难发现」的那一段搬到能测的地方。
 *
 * ## 断言口径
 *
 * 每条都对应一处「改一行就会红」的实现：
 *  - 吞吐降序 / rtt 升序 —— 排序比较器写反立刻红；
 *  - 不可达垫底、未探测夹中间 —— 把「探测超时」当成「死源」会误伤可用线路；
 *  - sticky 的 3 倍容忍 —— 无脑置顶会让「上次成功」变成「永远用同一个慢源」；
 *  - 不丢不重 —— 排序写成 `filter + sorted` 时最容易漏掉某一类。
 */
import com.beichen.polaris.core.net.GitHubMirror
import com.beichen.polaris.core.net.GitHubMirror.Probe
import com.beichen.polaris.core.net.GitHubMirror.Source

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

private fun eq(msg: String, expected: List<String>, actual: List<String>) {
    if (expected != actual) throw AssertionError("$msg：期望 $expected，实际 $actual")
}

private fun src(label: String) = Source(label, "https://example.invalid/$label")

/** 可达，给定吞吐（B/s）与首字节耗时（ms）。 */
private fun ok(label: String, bps: Long, rtt: Long = 50) = Probe(label, true, rtt, bps)

/** 明确探测不通。 */
private fun dead(label: String) = Probe(label, false, Long.MAX_VALUE, 0)

private fun labels(xs: List<Source>) = xs.map { it.label }

fun main() {
    println("=== 下载线路排序 ===")

    check("空候选返回空，不抛异常") {
        eq("空输入", emptyList(), labels(GitHubMirror.rank(emptyList(), emptyList())))
    }

    check("没有任何探测结果时保持候选表原顺序") {
        val s = listOf(src("a"), src("b"), src("c"))
        eq("无探测", listOf("a", "b", "c"), labels(GitHubMirror.rank(s, emptyList())))
    }

    check("可达线路按吞吐降序") {
        val s = listOf(src("a"), src("b"), src("c"))
        val p = listOf(ok("a", 100_000), ok("b", 900_000), ok("c", 300_000))
        eq("吞吐排序", listOf("b", "c", "a"), labels(GitHubMirror.rank(s, p)))
    }

    check("吞吐相同按首字节耗时升序") {
        val s = listOf(src("a"), src("b"))
        val p = listOf(ok("a", 500_000, rtt = 200), ok("b", 500_000, rtt = 50))
        eq("rtt 排序", listOf("b", "a"), labels(GitHubMirror.rank(s, p)))
    }

    check("探测不通的线路排到最后，但仍保留在列表里") {
        val s = listOf(src("a"), src("b"))
        val p = listOf(dead("a"), ok("b", 100_000))
        eq("死源垫底", listOf("b", "a"), labels(GitHubMirror.rank(s, p)))
    }

    check("没有探测结果的夹在可达与不通之间") {
        // 探测超时 ≠ 线路死掉。把它当死源排到最后，等于白白放弃一条可能能用的通道。
        val s = listOf(src("a"), src("b"), src("c"))
        val p = listOf(dead("a"), ok("b", 100_000))
        eq("未探测居中", listOf("b", "c", "a"), labels(GitHubMirror.rank(s, p)))
    }

    check("sticky 可达且不算慢时置顶") {
        val s = listOf(src("a"), src("b"), src("c"))
        val p = listOf(ok("a", 1_000_000), ok("b", 500_000), ok("c", 100_000))
        eq("sticky 置顶", listOf("b", "a", "c"), labels(GitHubMirror.rank(s, p, sticky = "b")))
    }

    check("sticky 慢到 3 倍以上时不再置顶") {
        val s = listOf(src("a"), src("b"))
        val p = listOf(ok("a", 1_000_000), ok("b", 100_000))
        eq("慢源不该粘住", listOf("a", "b"), labels(GitHubMirror.rank(s, p, sticky = "b")))
    }

    check("sticky 恰好等于 3 倍差距时仍然置顶（边界含等号）") {
        val s = listOf(src("a"), src("b"))
        val p = listOf(ok("a", 900_000), ok("b", 300_000))
        eq("边界", listOf("b", "a"), labels(GitHubMirror.rank(s, p, sticky = "b")))
    }

    check("sticky 这次探测不通时不置顶") {
        val s = listOf(src("a"), src("b"))
        val p = listOf(ok("a", 100_000), dead("b"))
        eq("sticky 挂了", listOf("a", "b"), labels(GitHubMirror.rank(s, p, sticky = "b")))
    }

    check("sticky 不在候选表里时不影响排序") {
        val s = listOf(src("a"), src("b"))
        val p = listOf(ok("a", 100_000), ok("b", 900_000))
        eq("未知 sticky", listOf("b", "a"), labels(GitHubMirror.rank(s, p, sticky = "zzz")))
    }

    check("全部不通时保持原顺序（不随机打乱）") {
        val s = listOf(src("a"), src("b"), src("c"))
        val p = listOf(dead("a"), dead("b"), dead("c"))
        eq("全死", listOf("a", "b", "c"), labels(GitHubMirror.rank(s, p)))
    }

    check("整批吞吐为 0 时 sticky 不置顶（没有可比较的基准）") {
        val s = listOf(src("a"), src("b"))
        val p = listOf(ok("a", 0), ok("b", 0))
        eq("零吞吐", listOf("a", "b"), labels(GitHubMirror.rank(s, p, sticky = "b")))
    }

    check("排序不丢源、不重复、不改入参") {
        val s = listOf(src("a"), src("b"), src("c"), src("d"))
        val snapshot = labels(s)
        val p = listOf(ok("a", 10), dead("b"), ok("c", 900), ok("d", 500))
        val out = GitHubMirror.rank(s, p, sticky = "d")
        if (out.size != s.size) throw AssertionError("数量变了：${out.size} != ${s.size}")
        if (out.map { it.label }.toSet() != s.map { it.label }.toSet()) {
            throw AssertionError("源集合变了：${labels(out)}")
        }
        eq("入参被修改", snapshot, labels(s))
    }

    check("真实候选表展开后仍是 4 条，且直连在表尾") {
        val real = GitHubMirror.sources(
            "https://github.com/Kdkdmwnwdkd/Polaris/releases/download/latest/app-release.apk"
        )
        if (real.size != 4) throw AssertionError("候选数变了：${real.size}")
        if (real.last().label != "直连") throw AssertionError("直连不在最后：${labels(real)}")
        if (!real.last().url.startsWith("https://github.com/")) {
            throw AssertionError("直连地址没拼对：${real.last().url}")
        }
        real.filter { it.label != "直连" }.forEach {
            if (!it.url.endsWith("/app-release.apk")) throw AssertionError("镜像地址拼错：${it.url}")
        }
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
