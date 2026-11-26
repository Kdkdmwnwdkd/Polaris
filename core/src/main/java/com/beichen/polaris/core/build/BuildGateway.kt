package com.beichen.polaris.core.build

/**
 * 构建发版的门面（2026-11-26）。理由与 [com.beichen.polaris.core.media.MediaGateway] 相同：
 * 把 `Context` 挡在实现类里，工具本体才能在不启 Android 的环境里被测。
 *
 * 语义刻意做得比 [BuildEngine] 窄——工具只需要三件事：**能不能触发**、**触发了**、
 * **现在到哪一步了**。浏览器式的轮询、APK 下载、安装都不该由工具来调，那是构建页与
 * 自动更新的活。
 */
interface BuildGateway {

    /**
     * GitHub 配置缺失的原因；配好了返回 null。
     *
     * 返回字符串而不是布尔：工具要能说「缺 PAT」而不是「未配置」，
     * 否则模型只能让用户自己去猜哪一项没填。
     */
    fun missingConfig(): String?

    fun isRunning(): Boolean

    /** 最近一条构建记录（进度查询用）；没有历史返回 null。 */
    fun latest(): BuildTask?

    /**
     * 后台触发一次构建。
     *
     * @return null = 已启动；非 null = 没启动的原因（可直接展示给用户）
     */
    fun trigger(): String?
}
