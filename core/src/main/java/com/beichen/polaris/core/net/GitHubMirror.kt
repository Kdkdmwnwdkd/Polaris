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
 */
object GitHubMirror {

    /**
     * 加速前缀，按顺序尝试；末尾空串 = 直连。
     *
     * 只放公开、无需登录的公共加速通道。它们随时可能限流或下线，所以这里是一个
     * **有序候选表**而不是一个常量：某个挂了，后面的自动接上。
     */
    private val PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        ""
    )

    /** 一个候选下载地址。 */
    data class Source(val label: String, val url: String)

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
}
