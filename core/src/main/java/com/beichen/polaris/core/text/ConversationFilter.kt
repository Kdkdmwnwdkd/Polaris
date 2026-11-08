package com.beichen.polaris.core.text

/**
 * 会话标题过滤（W-69）：纯函数，无 Android / 序列化依赖，harness 可直接断言。
 *
 * ## 为什么单独抽出来
 * 这段逻辑原来写在 Composable 里，跟着 UI 一起只能靠肉眼验。但它有两条**必须成立**的约定：
 *
 *  - **空白词等于不过滤**：用户敲一个空格、或者删光输入框，列表必须回到全部；
 *    写成 `if (keyword.isEmpty())` 就会漏掉这个空格，列表当场变空。
 *  - **不重排**：会话列表的顺序由 store 给（置顶在前、其次按最近活动时间）。
 *    过滤如果顺手 `sortedBy` 一下，「置顶」这个承诺就失效了。
 *
 * 两条都是「看起来显然、改起来容易踩」的类型，用断言钉住比写在注释里可靠。
 */
object ConversationFilter {

    /**
     * 按标题关键词过滤。
     *
     * @param items 已由数据层排好序的集合（本函数**保证不改变相对顺序**）
     * @param keyword 用户输入；前后空白会被剥掉，剥完为空则原样返回 [items]
     * @param titleOf 取标题的方式（避免本函数依赖具体的数据类，泛型可测）
     */
    fun <T> apply(items: List<T>, keyword: String, titleOf: (T) -> String): List<T> {
        val k = keyword.trim()
        if (k.isEmpty()) return items
        // 用 contains 而不是 startsWith：用户记得的往往是中间那段词
        // （「记忆库那个对话」→ 搜「记忆」），从开头匹配会漏掉一半
        return items.filter { titleOf(it).contains(k, ignoreCase = true) }
    }

    /**
     * 统计行的文案：有关键词时给出「匹配 M / N」，让用户知道是被筛掉了、不是真没了。
     */
    fun countText(total: Int, matched: Int, keyword: String): String =
        if (keyword.trim().isEmpty()) "共 $total 个会话" else "匹配 $matched / $total 个会话"
}
