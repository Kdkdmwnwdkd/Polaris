package com.beichen.polaris.core.container

import android.content.Context
import android.net.ConnectivityManager
import com.beichen.polaris.core.AgentLogger
import java.io.File

/**
 * 容器内 DNS 配置。
 *
 * ## 为什么需要
 *
 * rootfs 是干净的：`/etc/resolv.conf` 要么不存在，要么是空的。proot 只做文件系统与
 * 进程的隔离，**不做网络隔离**——容器里的 `apk` / `apt` 走的是宿主内核的网络栈，
 * 但 musl/glibc 仍要读 `/etc/resolv.conf` 才知道该问哪个 DNS。
 *
 * 不写这个文件，真机上的表现是：
 *
 * ```
 * 安装失败（exit 2）：fetch https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/APKINDEX.tar.gz
 *                    WARNING: ... temporary failure in name resolution
 * ```
 *
 * ## 数据来源
 *
 * 取自宿主**当前活动网络**的 `LinkProperties.dnsServers`，而不是硬编码 `8.8.8.8`：
 * 用户可能在用内网 DNS、运营商 DNS，或者公司网络里只有内部域名能解析。
 *
 * 每次创建容器会话 / 执行容器命令前都重写一遍（成本是一次几十字节的文件写），
 * 这样切换 Wi-Fi、开热点之后不需要用户手动处理。
 */
object ContainerNetwork {

    private const val TAG = "ContainerNetwork"

    /**
     * 把宿主当前 DNS 写进 `<rootfs>/etc/resolv.conf`。
     *
     * 失败只记日志、不阻断：解析不了域名会表现为命令失败，但容器本身仍可用
     * （比如 `apk add` 用本地缓存、或者用户自己在容器里改配置）。
     */
    fun ensureResolvConf(context: Context, rootfs: File) {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory) {
            AgentLogger.w(TAG, "rootfs 缺少 etc/，跳过 DNS 配置：${rootfs.absolutePath}")
            return
        }
        val servers = dnsServers(context)
        if (servers.isEmpty()) {
            AgentLogger.w(TAG, "当前网络没有可用 DNS（离线？），容器内域名解析会失败")
            return
        }
        val text = buildString {
            appendLine("# 由北辰写入：宿主当前网络的 DNS")
            servers.forEach { appendLine("nameserver $it") }
        }
        val target = File(etc, "resolv.conf")
        runCatching { target.writeText(text) }
            .onSuccess { AgentLogger.d(TAG, "resolv.conf 已写入：${servers.joinToString()}") }
            .onFailure { AgentLogger.w(TAG, "写 resolv.conf 失败：${it.message}") }
    }

    /** 宿主当前活动网络的 DNS 服务器（IPv4/IPv6 都收）。 */
    private fun dnsServers(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm?.getLinkProperties(it) }
        props?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }.getOrDefault(emptyList())
}
