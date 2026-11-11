package com.beichen.polaris.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 审计 F15：读通知的**范围**——本次到底读哪几个应用。
 *
 * ## 修的是什么
 *
 * F9 给 `device_notifications` 加了审批，但那个审批的粒度是「这个工具」：
 * 键固定 `private_read:device_notifications`，可记忆。于是用户为了看「刚才那条微信」
 * 点一次「本次会话不再询问」，拿到的是——
 *
 * > 本会话内，任意次，读取**全部应用**最近 60 条通知，含短信验证码、银行推送、私聊。
 *
 * 这跟 R-08 修过的 `shell` 组键是同一个病：**键描述的是工具类别，用户同意的是本次范围**。
 * R-08 已经给出了正确形状（按域名记网络、按目录记读取、不可逆命令永不记），
 * 私有读这一档当时只做到「按工具」，没做到「按范围」。
 *
 * ## 修法
 *
 * 让调用方说清读谁，把范围编进授权键：
 *  - `packages=[com.tencent.mm]` → 键 `private_read:device_notifications:com.tencent.mm`，
 *    可记忆（同意读微信，本会话不再问微信）；
 *  - 不传 / 传了但解析不出 → 键 `…:*`，**不可记忆**。
 *    全量读取的范围用户无法预判（下一个推通知的应用可以是任何东西），
 *    与「不可逆命令不能被记住」同一个理由：范围不可界定时，一次授权就等于无限授权。
 *  - 范围写坏（非字符串项、超过 [MAX_PACKAGES] 个）→ 键 `…:!`，不可记忆，工具直接报错。
 *    这里刻意**不静默截断、不静默放大**：截断会让弹窗写的范围和实际读的不一致，
 *    放大就是把模糊输入当成了全量放行。
 *
 * ## 一条必须成立的不变量
 *
 * **审批键里的包名集合，必须等于工具真正过滤用的集合。**
 * 两处都调本对象的 [parse]，同一个入参得到同一个 [NotificationFilter]——
 * 而不是「弹窗按 A 算、读取按 B 算」。这条由 `NotificationScopeVerification` 直接对账：
 * 拿同一个 args，一边取 `ApprovalScope.of(...).key` 解出包名集合，
 * 一边拿 `NotificationStore.byPackages(NotificationScope.parse(args))` 实际读到的包名集合，
 * 两者必须逐项相等。
 *
 * 纯函数、零 Android 依赖，可在 JVM harness 真跑。
 */
sealed class NotificationFilter {
    /**
     * 只读这几个应用（已 trim、转小写、去重）。
     *
     * 小写折叠的理由：Android 包名按约定全小写，而模型输出的大小写不可控。
     * 不折叠的话 `COM.TENCENT.MM` 会既读不到东西、又占一个独立的授权键。
     */
    data class Named(val packages: Set<String>) : NotificationFilter()

    /** 不限定来源：读全部应用。[NotificationScope.memorizable] 对它恒为 false。 */
    object All : NotificationFilter()

    /** 范围无法识别。工具应当直接报错，而不是「当成全部」读一遍。 */
    data class Invalid(val reason: String) : NotificationFilter()
}

object NotificationScope {

    /** 工具名。键前缀与工具名同源，避免改名时只改一处。 */
    const val TOOL = "device_notifications"

    /** 入参名。 */
    const val ARG = "packages"

    /**
     * 一次最多指定几个应用。
     *
     * 取 8 的理由：授权键要进会话白名单、要显示在弹窗标题行。超过 8 个，
     * 用户已经读不完自己同意了什么——那时「同意」只是走个形式，不如让他分批读。
     */
    const val MAX_PACKAGES = 8

    /** 全量范围的键后缀。 */
    const val WILDCARD = "*"

    /** 无法识别范围的键后缀。 */
    const val REJECTED = "!"

    /** 授权键前缀：`private_read:device_notifications:`。 */
    fun keyPrefix(): String = "${ApprovalGrouping.GROUP_PRIVATE_READ}:$TOOL:"

    /**
     * 从工具入参解析读取范围。
     *
     * 语义（每条都对应一种真实输入）：
     *  - 没有 `packages` 键 / 值为 null / 空数组 / 全是空白 → [NotificationFilter.All]；
     *  - 单个字符串 → 一个应用（模型常把数组写成字符串，这种写法不会放大范围，接受）；
     *  - 字符串数组 → 去空白、去重、转小写；超过 [MAX_PACKAGES] → [NotificationFilter.Invalid]；
     *  - 数组里混了非字符串（对象、数字、布尔）→ [NotificationFilter.Invalid]。
     *
     * `packages: []` 归到 [NotificationFilter.All] 而不是「一个都不读」：空数组表达的是
     * 「没想好读谁」，把它当成全量会弹一个不可记忆的窗，用户当场就能拒——
     * 比静默返回空结果、模型据此断言「没有通知」要诚实得多。
     */
    fun parse(args: JsonObject): NotificationFilter {
        val raw = args[ARG] ?: return NotificationFilter.All
        if (raw is JsonNull) return NotificationFilter.All

        val names: List<String> = when (raw) {
            is JsonArray -> {
                val out = ArrayList<String>(raw.size)
                for (el in raw) {
                    val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: return NotificationFilter.Invalid(
                            "packages 里出现了非字符串项（只接受应用包名）"
                        )
                    out += s
                }
                out
            }
            is JsonPrimitive -> {
                if (!raw.isString) {
                    return NotificationFilter.Invalid("packages 必须是字符串或字符串数组")
                }
                listOf(raw.contentOrNull.orEmpty())
            }
            else -> return NotificationFilter.Invalid("packages 必须是字符串或字符串数组")
        }

        val cleaned = names.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        if (cleaned.isEmpty()) return NotificationFilter.All
        if (cleaned.size > MAX_PACKAGES) {
            return NotificationFilter.Invalid(
                "一次最多指定 $MAX_PACKAGES 个应用（收到 ${cleaned.size} 个）"
            )
        }
        return NotificationFilter.Named(cleaned)
    }

    /**
     * 授权键。**唯一**决定「这次同意覆盖多大范围」的地方。
     *
     * 排序后再拼：`[微信, 短信]` 与 `[短信, 微信]` 必须落到同一个键，
     * 否则同一份授权会因为模型换了参数顺序而重复弹窗。
     */
    fun keyOf(filter: NotificationFilter): String = when (filter) {
        is NotificationFilter.Named ->
            keyPrefix() + filter.packages.sorted().joinToString(",")
        NotificationFilter.All -> keyPrefix() + WILDCARD
        is NotificationFilter.Invalid -> keyPrefix() + REJECTED
    }

    /** 弹窗里的类别名。要写清读的是哪几个应用，而不是笼统的「读取通知」。 */
    fun labelOf(filter: NotificationFilter): String = when (filter) {
        is NotificationFilter.Named -> {
            val sorted = filter.packages.sorted()
            if (sorted.size == 1) {
                "读取通知（${sorted[0]}）"
            } else {
                "读取通知（${sorted[0]} 等 ${sorted.size} 个应用）"
            }
        }
        NotificationFilter.All -> "读取全部通知（所有应用）"
        is NotificationFilter.Invalid -> "读取通知（范围无法识别）"
    }

    /**
     * 这次授权能不能被「本次会话不再询问」记住。
     *
     * 只有点名了应用才能记住：范围一旦不可界定（全量 / 写坏），
     * 记住它就等于把「以后随便读」写进了白名单。
     */
    fun memorizable(filter: NotificationFilter): Boolean =
        filter is NotificationFilter.Named
}
