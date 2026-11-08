package com.beichen.polaris.core

/**
 * 进程环境变量构造工具（纯 Kotlin，零外部依赖）。
 *
 * 存在理由（踩坑点，原理参照 aicode 的 `LinuxContainerEngine`，净室重写）：
 * Android 上通过 `forkpty` / `execve` 启动子进程时，**子进程不继承 JVM 进程的
 * `System.getenv()`**（那是 ART 虚拟机的副本，不是进程的 `environ`）。因此：
 *
 * - 只喂「自定义 env」→ proot 因缺 `ANDROID_ROOT` / `LD_LIBRARY_PATH` 等宿主变量
 *   **瞬间退出，且日志无任何报错**（最难排查的一类故障）；
 * - 只喂「系统 env」→ 容器内的 `PATH` / `HOME` / `TMPDIR` 全是宿主视角，命令找不到。
 *
 * 正确做法是**求并集：系统 env 打底，自定义 env 覆盖同名键**。本项目所有会
 * fork/exec 子进程的入口（[com.beichen.polaris.core.container.ProotRunner]、
 * [com.beichen.polaris.terminal.TerminalSession]）都必须走这里的 [mergeHostEnv]，
 * 保证「一份真源、两处复用」。
 */
object ProcessEnv {

    /**
     * 把 `KEY=VALUE` 形式的 env 列表解析成 Map。
     * - 无 `=` 的项丢弃（非法 env 条目）；
     * - 空 key（`=value`）丢弃；
     * - 重复 key 以后出现的为准（与 libc `environ` 语义一致）。
     */
    fun parse(entries: Iterable<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (entry in entries) {
            val idx = entry.indexOf('=')
            if (idx <= 0) continue
            out[entry.substring(0, idx)] = entry.substring(idx + 1)
        }
        return out
    }

    /** Map → `KEY=VALUE` 列表（保留插入顺序，便于日志比对）。 */
    fun toEnvList(map: Map<String, String>): List<String> = map.map { "${it.key}=${it.value}" }

    /**
     * 宿主环境 + Map 形式自定义 env 的并集（自定义覆盖同名键）。
     *
     * @return `KEY=VALUE` 数组，可直接交给 `execve` / JNI `createSubprocess*`。
     */
    fun mergeHostEnv(custom: Map<String, String>): Array<String> =
        mergeHostEnv(toEnvList(custom))

    /**
     * 宿主环境 + 列表形式自定义 env 的并集（自定义覆盖同名键）。
     *
     * `System.getenv()` 在极端环境下可能抛异常（安全策略 / 沙箱），此处记录 WARN 后
     * 退化为「只有自定义 env」，保证调用方不会因取环境而崩溃；**静默退化正是 T09
     * 要消灭的故障模式**（proot 缺宿主变量瞬间退出且无报错），所以必须留日志。
     *
     * @return `KEY=VALUE` 数组，可直接交给 `execve` / JNI `createSubprocess*`。
     */
    fun mergeHostEnv(custom: Iterable<String>): Array<String> {
        val merged = LinkedHashMap<String, String>()
        val host: Map<String, String> = runCatching { System.getenv() }
            .onFailure {
                AgentLogger.w(
                    "ProcessEnv",
                    "宿主 env 读取失败，已退化为仅自定义 env：${it.message}"
                )
            }
            .getOrDefault(emptyMap())
        merged.putAll(host)
        merged.putAll(parse(custom))
        return toEnvList(merged).toTypedArray()
    }

    /** 宿主环境变量个数（仅供日志自检使用）。 */
    fun hostEnvSize(): Int = runCatching { System.getenv().size }
        .onFailure {
            AgentLogger.w("ProcessEnv", "宿主 env 读取失败，hostEnvSize 退化为 0：${it.message}")
        }
        .getOrDefault(0)
}
