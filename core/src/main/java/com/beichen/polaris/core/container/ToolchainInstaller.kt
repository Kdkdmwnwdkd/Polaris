package com.beichen.polaris.core.container

import java.io.File

/**
 * 容器运行时一键安装（B 批）。
 *
 * ## 要解决什么
 * 容器（proot + Alpine/Debian rootfs）默认是**极简**的：只有 busybox 一套基础工具。
 * 用户想在里面跑 `npm install`、`python script.py`、`javac` 时什么都找不到，
 * 得自己敲 `apk add nodejs python3 openjdk17`——而手机上的容器没有桌面体验，
 * 这道坎足以让人放弃用容器做开发。
 *
 * 目标：进容器就有完整的语言运行时，不用先敲一串 `apk add` 才敢动手。
 *
 * ## 设计取舍
 * **不预置运行时镜像**。打包 node+python+JRE 进 APK 会让包体增加数百 MB，
 * 且 apk 仓库里现成的包更干净。改为**按需一键装**：
 * - [detect] 先探测容器里已有什么（不重复装、不覆盖用户改过的版本）；
 * - [installCommand] 生成对应的安装命令（Alpine 用 apk，Debian 用 apt）。
 *
 * ## 包管理器识别
 * 不靠发行版名字猜，靠**文件系统事实**：
 * - `<rootfs>/sbin/apk` 或 `<rootfs>/bin/apk` 存在 → Alpine 系（apk）
 * - `<rootfs>/usr/bin/apt-get` 存在 → Debian 系（apt）
 * - 都没有 → 不支持自动安装（返回 null，UI 明说原因，不瞎试）
 *
 * 纯文件判断 + 纯字符串拼接，**零 Android 依赖**，可在 JVM harness 直接断言。
 */
object ToolchainInstaller {

    /** 可安装的语言运行时。 */
    enum class Lang(
        val id: String,
        val displayName: String,
        /** 探测「已安装」用的可执行文件名（按优先级取第一个存在的）。 */
        val probeBinaries: List<String>,
        /** Alpine (apk) 包名。 */
        val apkPackages: List<String>,
        /** Debian (apt) 包名。 */
        val aptPackages: List<String>,
        /** 安装后用于自检的命令（`--version`）。 */
        val verifyCommand: String
    ) {
        NODE(
            id = "node",
            displayName = "Node.js",
            probeBinaries = listOf("node", "nodejs"),
            apkPackages = listOf("nodejs", "npm"),
            aptPackages = listOf("nodejs", "npm"),
            verifyCommand = "node --version"
        ),
        PYTHON(
            id = "python",
            displayName = "Python 3",
            probeBinaries = listOf("python3", "python"),
            apkPackages = listOf("python3", "py3-pip"),
            aptPackages = listOf("python3", "python3-pip"),
            verifyCommand = "python3 --version"
        ),
        JAVA(
            id = "java",
            displayName = "Java (JRE + JDK)",
            probeBinaries = listOf("java", "javac"),
            apkPackages = listOf("openjdk17", "openjdk17-jdk"),
            aptPackages = listOf("default-jdk"),
            // 卡片的标题写着「JRE + JDK」，自检就得把 javac 一起验：
            // 只验 `java -version` 会把「JRE 在、JDK 缺」当成装成功。
            verifyCommand = "java -version && javac -version"
        );

        companion object {
            fun byId(id: String): Lang? = entries.firstOrNull { it.id == id }
        }
    }

    /** 支持的包管理器。 */
    enum class PkgManager(val id: String, val displayName: String) {
        APK("apk", "Alpine (apk)"),
        APT("apt", "Debian (apt)")
    }

    /**
     * 已识别的发行版（版本号用来判断「要的这个版本，官方源到底有没有」）。
     *
     * 为什么需要它：Alpine 与 Debian 的官方源**都不提供分版本的 nodejs 包**——
     * 没有 `nodejs18` / `nodejs20` 这种东西。每个发行版只有一条 `nodejs` 版本线，
     * 具体是 18 还是 22 由发行版版本决定。所以「装 node 18」能不能成，
     * 取决于 rootfs 是哪个发行版的哪个版本；不识别发行版就只能瞎猜。
     *
     * @param version Alpine 形如 "3.21.3"，Debian 形如 "12.11"；读不到为 null。
     */
    data class Distro(
        val pkgManager: PkgManager,
        val version: String? = null
    ) {
        /**
         * 版本线：Alpine 取前两段（"3.21"），Debian 取主版本（"12"）。
         *
         * Alpine 每个次版本对应一套独立仓库（3.19/3.20/3.21 各一份），
         * 运行时版本随仓库走；Debian 同理按主版本（11/12/13）区分。
         */
        val line: String?
            get() {
                val v = version?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val parts = v.split('.')
                return when (pkgManager) {
                    PkgManager.APK -> if (parts.size >= 2) "${parts[0]}.${parts[1]}" else null
                    PkgManager.APT -> parts[0].takeIf { p -> p.isNotEmpty() && p.all { it.isDigit() } }
                }
            }
    }

    /** 探测结果。 */
    data class Detection(
        val pkgManager: PkgManager?,
        /** 已安装的语言运行时。 */
        val installed: Set<Lang>,
        /** rootfs 里没找到包管理器时的原因（供 UI 展示）。 */
        val reason: String? = null,
        /** 识别到的发行版（版本可用性判断依赖它）；识别不到为 null。 */
        val distro: Distro? = null
    )

    /**
     * 版本约束（C 批）。
     *
     * 用户要装运行时时，常常不是「随便给我个 node」——而是「我要 node 18」
     *（因为某个项目 lockfile 卡在 18）或「python 要 3.11」（依赖某些新语法）。
     *
     * ## 一个必须先说清的事实（2026-10 按官方仓库核实）
     * Alpine 与 Debian 的**官方仓库都不提供分版本的 nodejs 包**：
     * - Alpine v3.19 / v3.20 的 `nodejs` 是 20.x，v3.21 是 22.x；没有 `nodejs18`、`nodejs20`；
     * - Debian bookworm 的 `nodejs` 是 18.20.x（trixie 才是 20.x）；同样没有 `nodejs18`。
     *
     * 所以「装 node 18」在 Alpine 3.21 上**根本装不到**——不是包名写错，是仓库里没有。
     * 旧实现拼出 `apk add nodejs18`，命令必然失败；候选表还把 18/20/22 全标成可装。
     *
     * 现在的做法：包名只写**真实存在**的那些，能不能装到指定版本由 [Distro] 判断，
     * 装不到就明确说「装不到」，绝不悄悄换成另一个大版本。
     *
     * Java 是例外：`openjdk11/17/21` 确实是分版本包，按号拼包名成立
     *（但 Debian 每个发行版只有其中一两个）。
     */
    data class VersionSpec(
        /** 版本号，如 "18" / "3.11" / "17"。 */
        val version: String,
        /** 该语言在 Alpine 下对应的包名。 */
        val apkPackages: List<String>,
        /** 该语言在 Debian 下对应的包名。 */
        val aptPackages: List<String>,
        /** 解析说明（供 UI 展示「为什么装不到 / 实际会装到哪个版本」）。 */
        val note: String = "",
        /** Alpine 官方源里是否真有这个版本。 */
        val apkAvailable: Boolean = true,
        /** Debian 官方源里是否真有这个版本。 */
        val aptAvailable: Boolean = true
    ) {
        /** 该版本在指定包管理器下是否可装。 */
        fun availableOn(pkg: PkgManager): Boolean = when (pkg) {
            PkgManager.APK -> apkAvailable
            PkgManager.APT -> aptAvailable
        }
    }

    /**
     * 解析版本约束（C 批）。
     *
     * @param lang 目标语言
     * @param version 用户指定的版本号；空串表示不指定
     * @param distro 已识别的发行版；null 表示未知（此时不做「装不到」的硬判定，
     *        只在 note 里提示无法确认，避免在信息不足时误拦）
     * @return 解析结果；版本号非法（如含字母、空格）返回 null
     */
    fun resolveVersion(lang: Lang, version: String, distro: Distro? = null): VersionSpec? {
        val v = version.trim()
        if (v.isEmpty()) return null
        // 只接受数字与点（如 18 / 3.11）——版本号会拼进 shell 命令，
        // 必须严格白名单，不能让 `18; rm -rf /` 这类东西混进来。
        if (!v.matches(Regex("^[0-9]+(\\.[0-9]+)*$"))) return null

        val major = v.substringBefore('.')
        val line = distro?.line
        return when (lang) {
            Lang.NODE -> {
                // 官方源只有一个 nodejs：版本由发行版决定，无法按包名指定。
                val shipped = when (distro?.pkgManager) {
                    PkgManager.APK -> ALPINE_NODE_BY_LINE[line]
                    PkgManager.APT -> DEBIAN_NODE_BY_LINE[line]
                    null -> null
                }
                val ok = shipped == null || shipped == major
                val note = when {
                    shipped == null ->
                        "未能识别发行版版本，无法确认官方源是否有 node $major.x；该发行版可能只带默认版本。"
                    ok ->
                        "${distroName(distro)} 官方源的 nodejs 就是 $shipped.x，装到的正是你要的大版本。"
                    else ->
                        "${distroName(distro)} 官方源只有 nodejs $shipped.x，没有 node $major 的包；要 node $major 需自行配置 NodeSource。"
                }
                VersionSpec(
                    version = v,
                    apkPackages = listOf("nodejs", "npm"),
                    aptPackages = listOf("nodejs", "npm"),
                    note = note,
                    apkAvailable = ok,
                    aptAvailable = ok
                )
            }
            Lang.PYTHON -> {
                // Alpine 只有 python3 一条线；Debian 的包名带完整小版本（python3.11）。
                val shipped = when (distro?.pkgManager) {
                    PkgManager.APK -> ALPINE_PYTHON_BY_LINE[line]
                    PkgManager.APT -> DEBIAN_PYTHON_BY_LINE[line]
                    null -> null
                }
                val wantsMinor = '.' in v
                val ok = shipped == null || !wantsMinor || shipped == v
                val note = when {
                    shipped == null ->
                        "未能识别发行版版本，无法确认是否有 python $v；装不到时安装命令会直接报错。"
                    ok && wantsMinor ->
                        "${distroName(distro)} 官方源的 python3 就是 $shipped，与你要的一致。"
                    ok ->
                        "${distroName(distro)} 官方源的 python3 是 $shipped。"
                    else ->
                        "${distroName(distro)} 官方源只有 python3 $shipped，没有 python $v 的包。"
                }
                VersionSpec(
                    version = v,
                    // Alpine 无分小版本包：python3 恒为当前线
                    apkPackages = listOf("python3", "py3-pip"),
                    // pip 统一是 python3-pip，不存在 python3.11-pip 这种包
                    aptPackages = if (wantsMinor) {
                        listOf("python$v", "python3-pip")
                    } else {
                        listOf("python3", "python3-pip")
                    },
                    note = note,
                    apkAvailable = ok,
                    aptAvailable = ok
                )
            }
            Lang.JAVA -> {
                // Java 确实是分版本包（openjdk11 / openjdk17 / openjdk21）
                val apkOk = major in ALPINE_JAVA_VERSIONS
                val aptOk = when {
                    line == null -> true
                    else -> major in (DEBIAN_JAVA_BY_LINE[line] ?: emptyList())
                }
                val note = when {
                    !apkOk -> "Alpine 官方源没有 openjdk$major；可选 11 / 17 / 21。"
                    line == null -> "未能识别发行版版本；openjdk-$major-jdk 在部分发行版里不存在。"
                    !aptOk -> "${distroName(distro)} 官方源没有 openjdk-$major-jdk；该发行版自带的是 " +
                        (DEBIAN_JAVA_BY_LINE[line] ?: emptyList()).joinToString("/") { "openjdk-$it" } + "。"
                    else -> ""
                }
                VersionSpec(
                    version = v,
                    apkPackages = listOf("openjdk$major", "openjdk$major-jdk"),
                    aptPackages = listOf("openjdk-$major-jdk"),
                    note = note,
                    apkAvailable = apkOk,
                    aptAvailable = aptOk
                )
            }
        }
    }

    /**
     * 一个「候选版本」（D 批 · 版本矩阵探测）。
     *
     * @param version 版本号
     * @param installable 该发行版**是否真有这个包**（false = 装不到，见 note）
     * @param note 说明，UI 直接展示
     * @param exact 是否能**精确**装到该版本；false 表示只能装到发行版默认版本，
     *        实际装到的可能是别的大版本（不会假装精确）
     */
    data class VersionOption(
        val version: String,
        val installable: Boolean,
        val note: String = "",
        val exact: Boolean = true
    )

    /**
     * 列出某语言在该包管理器下的**候选版本**。
     *
     * ## 这个功能解决什么
     * 原来只能让用户在输入框里**手打**版本号（「18」「3.11」），
     * 但用户并不知道哪些版本装得上——打完才失败，还得再猜一次。
     * 这里把「已知存在的版本」列出来，让用户从列表里选。
     *
     * ## 诚实性约束（重要）
     * 这张表是**编译期写死的候选**，不是实时查询仓库（那需要容器里有网、
     * 还要跑 `apk search` / `apt-cache`，且各发行版镜像源差异极大）。
     * 但「写死」不等于「瞎写」：条目按官方仓库实际内容核对过，
     * 并且用 [distro] 的发行版版本收窄成「这个 rootfs 上真正能装的」。
     *
     * 三态而不是两态：
     * - `installable=true, exact=true`  → ✅ 官方源就是这个版本；
     * - `installable=true, exact=false` → 🔄 能装，但装到的是发行版默认版本；
     * - `installable=false`             → ⚠️ 官方源没有，装不到。
     *
     * 宁可告诉用户"这里装不了"，也不要让他试三次才发现。
     */
    fun knownVersions(
        lang: Lang,
        pkg: PkgManager,
        distro: Distro? = null
    ): List<VersionOption> {
        val line = distro?.line
        return when (lang) {
            Lang.NODE -> {
                val shipped = when (pkg) {
                    PkgManager.APK -> ALPINE_NODE_BY_LINE[line]
                    PkgManager.APT -> DEBIAN_NODE_BY_LINE[line]
                }
                listOf("18", "20", "22", "24").map { v ->
                    when {
                        shipped == null -> VersionOption(
                            v, true,
                            "未能识别发行版版本；官方源只有一条 nodejs 版本线，装到的不一定是 $v.x",
                            exact = false
                        )
                        shipped == v -> VersionOption(
                            v, true, "${distroName(distro)} 官方源的 nodejs 就是 $v.x"
                        )
                        else -> VersionOption(
                            v, false,
                            "${distroName(distro)} 官方源只有 nodejs $shipped.x，没有 node $v 的包"
                        )
                    }
                }
            }

            Lang.PYTHON -> {
                val shipped = when (pkg) {
                    PkgManager.APK -> ALPINE_PYTHON_BY_LINE[line]
                    PkgManager.APT -> DEBIAN_PYTHON_BY_LINE[line]
                }
                val candidates = listOf("3", "3.9", "3.11", "3.12", "3.13")
                candidates.map { v ->
                    when {
                        v == "3" -> VersionOption(
                            v, true,
                            if (shipped != null) "python3 当前线是 $shipped" else "装 python3 当前线",
                            exact = false
                        )
                        shipped == null -> VersionOption(
                            v, true, "未能识别发行版版本，装不到时会直接报错", exact = false
                        )
                        shipped == v -> VersionOption(v, true, "官方源的 python3 就是 $v")
                        else -> VersionOption(
                            v, false, "${distroName(distro)} 官方源只有 python3 $shipped"
                        )
                    }
                }
            }

            Lang.JAVA -> {
                val available = when (pkg) {
                    PkgManager.APK -> ALPINE_JAVA_VERSIONS
                    PkgManager.APT -> DEBIAN_JAVA_BY_LINE[line] ?: listOf("11", "17", "21")
                }
                listOf("17", "21", "11").map { v ->
                    when {
                        v in available -> VersionOption(v, true)
                        line == null -> VersionOption(
                            v, true, "未能识别发行版版本；openjdk-$v-jdk 可能不存在", exact = false
                        )
                        else -> VersionOption(
                            v, false,
                            "${distroName(distro)} 官方源没有 openjdk-$v-jdk（可用：${available.joinToString("/")}）"
                        )
                    }
                }
            }
        }
    }

    /**
     * 探测 rootfs 里已有的运行时与包管理器。
     *
     * @param rootfs 已解压的 rootfs 目录
     */
    fun detect(rootfs: File): Detection {
        val pkg = detectPkgManager(rootfs)
        val installed = Lang.entries.filter { lang ->
            lang.probeBinaries.any { binaryExists(rootfs, it) }
        }.toSet()
        val reason = when {
            pkg != null -> null
            else -> "未识别到包管理器（apk / apt-get 都不存在），该 rootfs 无法自动安装运行时"
        }
        return Detection(pkg, installed, reason, detectDistro(rootfs, pkg))
    }

    /**
     * 识别发行版版本（决定「指定版本能不能装到」）。
     *
     * 不解析 `os-release` 的全部字段，只取能定版本的那一个：
     * - Alpine：`/etc/alpine-release`（形如 "3.21.3"）
     * - Debian：`/etc/debian_version`（形如 "12.11"；testing 会是 "trixie/sid"）
     * - 兜底：`/etc/os-release` 的 `VERSION_ID`
     *
     * @return 识别不到版本时返回 Distro(pkg, null)——仍然带上包管理器，
     *         只是无法做版本判定（调用方按「未知」处理，不误拦）
     */
    fun detectDistro(rootfs: File, pkg: PkgManager? = null): Distro? {
        val manager = pkg ?: detectPkgManager(rootfs) ?: return null
        val raw = when (manager) {
            PkgManager.APK -> readTrimmed(File(rootfs, "etc/alpine-release"))
            PkgManager.APT -> readTrimmed(File(rootfs, "etc/debian_version"))
        } ?: readOsReleaseVersionId(rootfs)
        return Distro(manager, raw)
    }

    /** 读文件并 trim；不存在或为空返回 null。 */
    private fun readTrimmed(file: File): String? = runCatching {
        if (!file.isFile) return null
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 从 `/etc/os-release` 取 VERSION_ID（形如 3.21.3 / 12）。 */
    private fun readOsReleaseVersionId(rootfs: File): String? = runCatching {
        val f = File(rootfs, "etc/os-release")
        if (!f.isFile) return null
        f.readLines()
            .firstOrNull { it.startsWith("VERSION_ID=") }
            ?.substringAfter('=')
            ?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 展示用的发行版名（Alpine 3.21 / Debian 12）。 */
    private fun distroName(distro: Distro?): String {
        if (distro == null) return "该发行版"
        val base = if (distro.pkgManager == PkgManager.APK) "Alpine" else "Debian"
        val line = distro.line ?: return base
        return "$base $line"
    }

    /** 识别包管理器（看二进制是否存在，不猜发行版名）。 */
    fun detectPkgManager(rootfs: File): PkgManager? = when {
        binaryExists(rootfs, "apk") -> PkgManager.APK
        binaryExists(rootfs, "apt-get") -> PkgManager.APT
        else -> null
    }

    /**
     * 生成安装命令。
     *
     * Alpine：`apk add --no-cache nodejs npm`
     * Debian：`apt-get update && apt-get install -y nodejs npm`
     *
     * ## 为什么 Alpine 加 `--no-cache`
     * apk 默认会缓存索引与包文件。在手机存储上，缓存一个 nodejs + npm 缓存能吃掉
     * 数百 MB 且用户根本不会去清。`--no-cache` 装完即走。
     *
     * ## 为什么 Debian 必须先 update
     * Debian 系不 update 直接 install 会用陈旧索引报「包不可用」。
     *
     * @param spec 可选的版本约束（C 批）；非 null 时用它的包名覆盖默认包名
     * @return 安装命令；包管理器不支持或该语言无对应包时返回 null
     */
    fun installCommand(
        lang: Lang,
        pkgManager: PkgManager,
        spec: VersionSpec? = null
    ): String? {
        val packages = when (pkgManager) {
            PkgManager.APK -> spec?.apkPackages ?: lang.apkPackages
            PkgManager.APT -> spec?.aptPackages ?: lang.aptPackages
        }
        if (packages.isEmpty()) return null
        return when (pkgManager) {
            // 前奏：npm / py3-pip / openjdk 都在 Alpine 的 community 仓库，
            // 而精简 rootfs 往往只启用 main。缺了它 `apk add nodejs npm` 会报
            // "npm (no such package)"——这不是版本问题，是源没开全。
            PkgManager.APK -> apkCommunityPrelude() +
                "apk add --no-cache ${packages.joinToString(" ")}"
            PkgManager.APT -> "apt-get update && apt-get install -y ${packages.joinToString(" ")}"
        }
    }

    /**
     * 生成「装完所有缺失运行时」的命令。
     *
     * @return 命令；无缺失返回 null（无需安装）
     */
    fun installMissingCommand(detection: Detection): String? {
        val pkg = detection.pkgManager ?: return null
        val missing = Lang.entries.filter { it !in detection.installed }
        if (missing.isEmpty()) return null
        // 一次装齐：Alpine 合并包名；Debian 分两次 apt（update 必须在前）
        return when (pkg) {
            PkgManager.APK -> {
                val all = missing.flatMap { it.apkPackages }.distinct()
                apkCommunityPrelude() + "apk add --no-cache ${all.joinToString(" ")}"
            }
            PkgManager.APT -> {
                val all = missing.flatMap { it.aptPackages }.distinct()
                "apt-get update && apt-get install -y ${all.joinToString(" ")}"
            }
        }
    }

    /**
     * Alpine 的 community 仓库前奏（幂等）。
     *
     * 为什么要它：`npm`、`py3-pip`、`openjdk*` 都在 community 仓库，minirootfs
     * 常常只写了 main。不补这一句，装 node 就会卡在 npm 上（node 装上了、npm 没有）。
     *
     * 逻辑：读 /etc/alpine-release 得到 "3.21"，若 /etc/apk/repositories 里
     * 没有 community，就追加对应版本的 community 源。读不到版本（非 Alpine）
     * 就什么都不做，交给 apk 自己报错——不猜、不写死版本号。
     */
    private fun apkCommunityPrelude(): String =
        "ALP=\$(sed -n '1s/[^0-9]*\\([0-9][0-9]*\\.[0-9][0-9]*\\).*/\\1/p' /etc/alpine-release 2>/dev/null); " +
            "[ -z \"\$ALP\" ] || grep -qs '/community' /etc/apk/repositories 2>/dev/null || " +
            "echo \"https://dl-cdn.alpinelinux.org/alpine/v\$ALP/community\" >> /etc/apk/repositories; "

    /**
     * 生成「装完自检」脚本：逐语言跑它的 `verifyCommand`，成功打 `OK:<id>`，失败打 `FAIL:<id>`。
     *
     * ## 为什么不能只看 apk 的退出码（真机 exit 1）
     * 真机上装 `openjdk17 + openjdk17-jdk`（424 MiB / 77 个包）最后是：
     * ```
     * Executing busybox-1.36.1-r29.trigger
     * 1 error: 424 MiB in 77 packages
     * ```
     * 包全部装完了（卡片也显示「已安装 Java」），但只要有一个 trigger 脚本在 proot 下
     * 返回非 0，apk 的整体退出码就是 1。**退出码 ≠ 能力**：判断「能不能跑 java」的
     * 唯一办法是跑一次 `java -version`。
     *
     * 反过来也成立：退出码 0 不代表 `javac` 真能用（JRE 包装上了、JDK 包被跳过的情形）。
     * 所以自检是独立的一步，不依赖包管理器的结论。
     *
     * @param langs 需要自检的语言；空集合返回空串（调用方应跳过这次容器命令）
     * @return 可直接交给 [ContainerCommandRunner] 执行的一行 shell 脚本
     */
    fun verifyScript(langs: List<Lang>): String {
        if (langs.isEmpty()) return ""
        // 子 shell 包住整条命令再重定向：`java -version` 走 stderr，只重定向 stdout 会漏出去
        return langs.joinToString("; ") { lang ->
            "( ${lang.verifyCommand} ) >/dev/null 2>&1 && echo \"OK:${lang.id}\" || echo \"FAIL:${lang.id}\""
        }
    }

    /**
     * 从 [verifyScript] 的输出里取出通过自检的语言 id。
     *
     * 纯函数，JVM harness 可断言（不依赖 Android / 容器）。
     */
    fun verifiedLangs(output: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("OK:") }
            .map { it.removePrefix("OK:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * 解析自检失败项（供 UI 说明「哪个没装上」）。
     */
    fun failedLangs(output: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("FAIL:") }
            .map { it.removePrefix("FAIL:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** 二进制是否存在（查 rootfs 下常见 bin 目录）。 */
    private fun binaryExists(rootfs: File, name: String): Boolean =
        BIN_DIRS.any { dir -> File(rootfs, "$dir/$name").exists() }

    /**
     * 失败是否是「包管理器数据库被占用」。
     *
     * ## 为什么这条判据放在这里（而不是执行器里）
     * 它属于**包管理器的语义**，不是进程管理的语义：apk 报 99、apt 报 100，文本也不同。
     * 放在纯逻辑层有两个好处：
     * 1. JVM harness 能直接断言（执行器带 `android.content.Context`，进不了 harness）；
     * 2. 真机复现一次锁冲突要好几分钟，而判据写错的代价是「重试逻辑静默失效」——
     *    必须有不需要真机就能守住的测试。
     *
     * 覆盖三种真实形态：
     * - Alpine apk：退出码 99 / `Unable to lock database: temporary error (try again later)`
     * - Debian apt：`Could not get lock /var/lib/dpkg/lock-frontend`
     *
     * @param exitCode 进程退出码
     * @param output 合并后的 stdout+stderr
     */
    fun isLockConflict(exitCode: Int, output: String): Boolean {
        if (exitCode == 99) return true
        val text = output.lowercase()
        return "unable to lock database" in text ||
            "could not get lock" in text ||
            "lock-frontend" in text
    }

    /** rootfs 内的可执行文件目录（覆盖 Alpine 与 Debian 布局）。 */
    private val BIN_DIRS = listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")

    /* ------------------------- 发行版 → 运行时版本对照表 -------------------------
     *
     * 数据来源：官方仓库索引（2026-10 核对，Alpine 侧用清华镜像的 APKINDEX 复验）。
     * - Alpine：3.18 node=18.20.1 / py=3.11.12；3.19 node=20.15.1 / py=3.11.14；
     *   3.20 node=20.15.1 / py=3.12.13；3.21 node=22.23.2 / py=3.12.15；
     *   3.22 node=22.23.4 / py=3.12.15；3.23 node=24.22.1 / py=3.12.15。
     *   **没有 nodejs18/20/22 这类分版本包**。
     * - Debian：bullseye(11) node=12.22 / java 11、17；
     *   bookworm(12) node=18.20.4 / py=3.11.2 / java 只有 17；
     *   trixie(13) node=20.x / py=3.13 / java 17、21。
     *
     * 只收录**核实过**的版本线。表里没有的线不做判断（当未知处理），
     * 而不是拿相近版本猜——猜错就会让用户装到与预期不符的运行时。
     */
    private val ALPINE_NODE_BY_LINE = mapOf(
        "3.18" to "18", "3.19" to "20", "3.20" to "20",
        "3.21" to "22", "3.22" to "22", "3.23" to "24"
    )
    private val ALPINE_PYTHON_BY_LINE = mapOf(
        "3.18" to "3.11", "3.19" to "3.11", "3.20" to "3.12",
        "3.21" to "3.12", "3.22" to "3.12", "3.23" to "3.12"
    )
    private val ALPINE_JAVA_VERSIONS = listOf("11", "17", "21")
    private val DEBIAN_NODE_BY_LINE = mapOf("11" to "12", "12" to "18", "13" to "20")
    private val DEBIAN_PYTHON_BY_LINE = mapOf("11" to "3.9", "12" to "3.11", "13" to "3.13")
    private val DEBIAN_JAVA_BY_LINE = mapOf(
        "11" to listOf("11", "17"),
        "12" to listOf("17"),
        "13" to listOf("17", "21")
    )
}
