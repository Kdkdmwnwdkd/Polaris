package com.beichen.polaris.skill

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 技能包（zip）的体积上限与受限读取（审计 F10）。
 *
 * ## 修的是什么
 *
 * 本地导入的三条路径各写各的读取：
 *
 * ```
 * URL  → URL(url).openStream().readBytes()
 * SAF  → contentResolver.openInputStream(uri).readBytes()
 * 字节 → 从上面两条来，或者直接给（内置示例）
 * ```
 *
 * 两条都**没有上限**。而 `OutOfMemoryError` 是 `Error` 不是 `Exception`，
 * `runCatching` 抓不住——一个 500MB 的 zip 地址不是「导入失败」，是**整个 App 没了**。
 * 市场的下载路径早就有 8MB 上限与 20s 读超时（`MarketClient` 的三道限制），
 * 本地导入却从旁边绕了过去：同一个动作（取一个 zip 交给 installZip），两条入口两套规矩。
 *
 * ## 为什么上限在这里再写一个数字
 *
 * [MAX_ZIP_BYTES] 与市场的 `MarketRules.MAX_PACKAGE_BYTES`（`core/skill/market`）是同一个语义。
 * 不复用那个常量是因为它跨了包、且是 `Long`（`const val Int` 不能由它算出），
 * 但**两个值必须相等**——这件事由 `SkillPackageLimitVerification` 的断言钉住，
 * 改了一处不改另一处就红。写死两个数字又不加断言，正是「迟早有一处被改掉」的形态。
 */
object SkillPackage {

    /** 单个技能 zip 的上限：8MB。与市场的 `MAX_PACKAGE_BYTES` 必须相等（有断言钉住）。 */
    const val MAX_ZIP_BYTES: Int = 8 * 1024 * 1024

    /**
     * 边读边计数，超限立刻中止（**不是**读完再检查——那时内存已经吃满了）。
     *
     * 不负责关闭 [input]：调用方用 `use { }` 包住。这里关掉会让调用方的 `use` 二次关闭，
     * 虽然多数流是幂等的，但没必要埋这个坑。
     *
     * @param what 出错信息里的主语（「所选文件」/「下载的包」），面向用户可读。
     */
    fun readCapped(input: InputStream, what: String, limit: Int = MAX_ZIP_BYTES): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) {
                throw IOException("$what 超过 ${mb(limit)}MB 上限，读到 $total 字节时中止")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 已经知道大小（`Content-Length`、`File.length()`、内存里的字节数组）时的预判。
     *
     * 预判只是省流量/省内存，**不能替代** [readCapped]：分块传输没有 `Content-Length`，
     * 声称 1KB 也可以吐 1GB。两道都要。
     */
    fun requireWithin(declared: Long, what: String, limit: Int = MAX_ZIP_BYTES) {
        if (declared > limit) {
            throw IOException("$what 有 $declared 字节，超过 ${mb(limit)}MB 上限")
        }
    }

    private fun mb(limit: Int): Int = limit / (1024 * 1024)
}
