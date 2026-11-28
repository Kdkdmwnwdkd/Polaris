package com.beichen.polaris.core.net

/**
 * GitHub 资产在国内的可达通道。
 *
 * ## 为什么单有「自己的仓库」不够
 *
 * 2026-11-15 的真机日志：
 *
 * ```
 * failed to connect to github.com/…:443 from 192.168.2.5 after 10000ms
 * ```
 *
 * 仓库确实是自己的（`Kdkdmwnwdkd/Polaris`），但 `github.com` 这个**域名**在手机网络上
 * 直接连接超时。同一台手机上，`api.github.com` 通、[ghfast.top] 通——所以「把模型搬进
 * 自己仓库」这一步本身不解决问题，得让下载走一条能通的通道。
 *
 * ## 为什么顺序是「镜像 → 镜像 → 直连」
 *
 * 直连放最后而不是最前：目标用户的网络里它多半不通，先试它等于每次白等 10 秒超时。
 * 海外网络下直连往往最快，但那是少数情况——真到了那一步，前面的镜像失败也会很快
 * （HTTP 错误立刻返回，不是超时）。
 *
 * ## 这些前缀是第三方
 *
 * 换源只解决「拿得到字节」，不解决「拿到的是不是对的字节」。所以调用方**必须**自己做
 * 内容校验：APK 用 Release 接口给的 sha256（[com.beichen.polaris.core.update.ApkDownloader]），
 * 模型用清单里的 sha256（[com.beichen.polaris.audio.asr.AsrModelInstaller]）。
 * 没有校验就和「从任意第三方下载」没有区别。
 *
 * ## 为什么还要 [rank]（2026-11-29）
 *
 * 上面那张表是**固定顺序 + 失败才换源**。问题是「慢」不是「失败」：一条只有 30 KB/s
 * 的通道不会抛异常，于是 50 MB 的包会在那条路上跑 28 分钟，全程没人切。
 * [rank] 把「按固定顺序试」换成「按实测吞吐排」——先探一小段（几百 KB），
 * 谁快谁先上；探测不到的把可达的排前面、把明确挂掉的排最后。
 *
 * 注意：**换分支不换线路**。分支只是 URL 路径里的一段，最终仍然落在
 * `github.com` / `release-assets.githubusercontent.com` 这两个域名上，
 * 路由和 CDN 完全一样。想换线路只能换**域名**，也就是这张前缀表。
 */
object GitHubMirror {

    /**
     * 加速前缀，按顺序尝试；末尾空串 = 直连。
     *
     * 只放公开、无需登录的公共加速通道。它们随时可能限流或下线，所以这里是一个
     * **有序候选表**而不是一个常量：某个挂了，后面的自动接上。
     *
     * 2026-11-29 实测（单线程 Range 请求 512 KB）：ghproxy.net / gh.llkk.cc /
     * github.moeyy.xyz / hub.gitmirror.com 已连不上，ghproxy.cc 返回 502，
     * akams.cn 与 ghp.li 返回 404——**都不能加**，加了只会让探测多等几次超时。
     * `ghproxy.cfd` 仍返回 200 但首字节要 4 秒，作为最后一条兜底保留（探测会把它排到最后）。
     */
    private val PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.cfd/",
        ""
    )

    /**
     * 粘性源的容忍倍数：上次成功的源即使不是最快，只要没慢到这个倍数以上就仍然置顶。
     *
     * 为什么要容忍而不是「永远用最快的」：探测本身有误差（256 KB 的样本说明不了
     * 整条链路），每次更新都换一个源会让「上次成功」这条信息白丢；而 3 倍以内的差距
     * 在 50 MB 上也就多十几秒，换来的是下次更新直接命中。
     */
    private const val STICKY_MAX_RATIO = 3

    /** 一个候选下载地址。 */
    data class Source(val label: String, val url: String)

    /**
     * 一条线路的探测结果。
     *
     * [reachable] 为 false 表示这次探测没拿到字节（连不上 / HTTP 错误 / 超时）。
     * 注意它只说明**这一刻**不通，不代表这条线路永久失效——所以排序时是「排后」而不是「删掉」。
     */
    data class Probe(
        val label: String,
        val reachable: Boolean,
        /** 首字节耗时（毫秒）；不可达时给 [Long.MAX_VALUE]。 */
        val rttMs: Long,
        /** 实测吞吐（字节/秒）；不可达时给 0。 */
        val bytesPerSec: Long
    )

    /**
     * 把官方地址展开成候选列表。
     *
     * @param officialUrl 形如 `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>`
     */
    fun sources(officialUrl: String): List<Source> =
        PREFIXES.map { prefix ->
            Source(
                label = if (prefix.isEmpty()) "直连" else prefix.removeSuffix("/"),
                url = prefix + officialUrl
            )
        }

    /**
     * 按探测结果排序：快者优先，挂掉的垫底。
     *
     * 排序规则（顺序即优先级）：
     *  1. 可达的排前面，按吞吐**降序**；吞吐相同按首字节耗时升序；
     *  2. 没有探测结果的排中间——它可能只是探测超时，不该被当成死源；
     *  3. 明确不可达的排最后；
     *  4. [sticky]（上次实际成功的源）若可达且吞吐不低于最快的 `1/STICKY_MAX_RATIO`，
     *     置顶。慢太多就不讲情面——粘在一个慢源上比每次都探测更糟。
     *
     * 全程稳定排序：相同条件的源保持传入顺序，这样调用方的候选表顺序仍然是
     * 「同等条件下」的优先级，不会被排序打乱成随机。
     */
    fun rank(
        sources: List<Source>,
        probes: List<Probe>,
        sticky: String? = null
    ): List<Source> {
        if (sources.isEmpty()) return emptyList()
        val byLabel = probes.associateBy { it.label }

        val reachable = sources
            .filter { byLabel[it.label]?.reachable == true }
            .sortedWith(
                compareByDescending<Source> { byLabel.getValue(it.label).bytesPerSec }
                    .thenBy { byLabel.getValue(it.label).rttMs }
            )
        val unknown = sources.filter { it.label !in byLabel }
        val dead = sources.filter { byLabel[it.label]?.reachable == false }

        val ordered = reachable + unknown + dead
        if (sticky == null) return ordered

        val idx = ordered.indexOfFirst { it.label == sticky }
        if (idx < 0) return ordered
        val head = ordered.first()
        val bestSpeed = byLabel[head.label]?.bytesPerSec ?: 0L
        val stickySpeed = byLabel[sticky]?.bytesPerSec ?: 0L
        // 只有「可达 + 没慢到 3 倍以上」才置顶；bestSpeed 为 0 说明整批都慢，此时粘住没有意义。
        if (byLabel[sticky]?.reachable != true || bestSpeed <= 0) return ordered
        if (stickySpeed * STICKY_MAX_RATIO < bestSpeed) return ordered

        return buildList(ordered.size) {
            add(ordered[idx])
            ordered.forEachIndexed { i, s -> if (i != idx) add(s) }
        }
    }
}
