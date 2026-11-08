package com.beichen.polaris.core.container

import android.content.Context
import android.os.Build
import android.system.Os
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ProcessEnv
import java.io.File
import java.io.FileOutputStream

/**
 * proot 运行时管理：把随包分发的 proot **三件套**从 assets 提取到可写且可执行的
 * 路径，并拼出启动容器的**完整调用描述**（[ProotInvocation]）。
 *
 * 三件套 = `proot`（主程序）+ `libtalloc.so.2` + `libandroid-shmem.so`，三者必须落在
 * **同一目录**（`<filesDir>/bin`）：proot 的 ELF `NEEDED` 是后两个库，而其
 * `RUNPATH` 指向 Termux 私有目录（`/data/data/com.termux/files/usr/lib`），本机上
 * 不存在——因此 [guestEnvMap] 会注入 `LD_LIBRARY_PATH=<libDir>` 覆盖宿主同名值，
 * 否则动态链接器找不到 `libtalloc.so.2`，proot 启动即静默失败。
 *
 * 【GPL/LGPL 合规】三件套均为上游**独立程序/独立库**，我们 **不** 把它们静态链接进
 * 自己的 .so（那会触发 GPL/LGPL 对整体代码的传染），而是作为独立文件随 APK 的
 * `assets/bin/arm64-v8a/` 分发，运行时提取后 `execve` / 由动态链接器加载：
 * - proot：GPL-2.0-or-later（Termux 仓库官方构建，版本 5.1.107.96）；
 * - libtalloc：LGPL-3.0-or-later（Samba talloc，Termux 打包版本 2.5.0）；
 * - libandroid-shmem：Termux 仓库官方构建（版本 0.7）。
 *
 * 随包附带的 `assets/3rdparty/proot/COPYING`（GPLv3 全文）与
 * `SOURCE_OFFER.txt`（源码要约，覆盖三个组件）即履行分发义务。
 *
 * 运行时若三件套不齐（未入 assets 或提取失败），[isAvailable] 返回 false，
 * 调用方据此显示降级文案，而不是静默崩溃。
 *
 * 若将来要从源码自建 proot（NDK 交叉编译 talloc + proot），见仓库根目录脚本
 * `scripts/build-proot.sh`；当前随包分发的是 Termux 上游预编译产物，不再走自建流程。
 */
object ProotRunner {

    private const val TAG = "ProotRunner"

    /** assets 内 proot 三件套目录（正式包只有 arm64；运行时用 [assetDir] 选实际目录）。 */
    const val PROOT_ASSET_DIR = "bin/arm64-v8a"

    /**
     * 当前设备该用哪个 assets 目录。
     *
     * 正式包只带 arm64-v8a（真机全是 arm64）。本地调试包可用
     * `-Px86Rootfs=true` 额外带 x86_64，让 x86_64 模拟器也能真跑容器——
     * 否则容器链路在服务器上永远只能做逻辑断言。
     *
     * 按**首选 ABI** 判断，不看「支持列表里有没有」：arm64 设备通常也声明
     * 支持 armeabi-v7a，但绝不能因此去取 32 位目录。
     */
    fun assetDir(): String =
        if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true) {
            "bin/x86_64"
        } else {
            "bin/arm64-v8a"
        }

    /** assets 内 proot 主二进制完整路径（保留常量：调用方按「单文件」语义引用）。 */
    const val PROOT_ASSET = "bin/arm64-v8a/proot"

    /** proot 三件套中主二进制的资产文件名。 */
    const val PROOT_ASSET_NAME = "proot"

    /** libtalloc 资产文件名（ELF SONAME 即 `libtalloc.so.2`，proot 按此名 NEEDED）。 */
    const val LIBTALLOC_ASSET_NAME = "libtalloc.so.2"

    /** libandroid-shmem 资产文件名（proot 的 NEEDED 之一）。 */
    const val LIBANDROID_SHMEM_ASSET_NAME = "libandroid-shmem.so"

    /** 三件套在 assets 目录下必须齐备的文件名（提取与就绪判定共用一份真源）。 */
    private val REQUIRED_ASSET_NAMES = listOf(
        PROOT_ASSET_NAME,
        LIBTALLOC_ASSET_NAME,
        LIBANDROID_SHMEM_ASSET_NAME
    )

    /** 只需要落到 filesDir 的运行库（proot 本体改走 jniLibs，不在此列）。 */
    private val LIB_ASSET_NAMES = listOf(LIBTALLOC_ASSET_NAME, LIBANDROID_SHMEM_ASSET_NAME)

    /**
     * jniLibs 打包的 proot（`libproot.so`）。
     *
     * 系统会把它解压到 `applicationInfo.nativeLibraryDir`——**只有这个目录允许 execve**。
     * `filesDir` 从 Android 10 起受 W^X 限制，`targetSdk 34` 下会直接
     * `Cannot run program ...: error=13, Permission denied`（真机实测 2026-10-24）。
     */
    fun nativeProot(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").takeIf { it.isFile }

    /** proot 落位目录（相对 `filesDir`）——抽成常量，便于 D1 改造时只改一处。 */
    const val PROOT_BIN_DIR = "bin"

    /** proot 落位文件名——抽成常量，便于 D1 改造时只改一处。 */
    const val PROOT_BIN_NAME = "proot"

    /** proot 相对 `filesDir` 的完整路径。 */
    const val PROOT_RELATIVE_PATH = "$PROOT_BIN_DIR/$PROOT_BIN_NAME"

    /** guest 内默认 shell（login shell，会加载 `/etc/profile`）。 */
    const val GUEST_SHELL = "/bin/sh"

    /** guest 内默认 HOME。 */
    const val GUEST_HOME = "/root"

    /** guest 视角的临时目录（覆盖宿主指向 App cache 的 TMPDIR）。 */
    const val GUEST_TMP = "/tmp"

    /** guest 内 git 全局配置路径（避免容器内 git 找不到全局配置 / 报 dubious ownership）。 */
    const val GIT_CONFIG_GLOBAL = "/root/.gitconfig"

    /**
     * guest PATH：显式列出 `/usr/local/bin`（非 login shell 不加载 `/etc/profile`，
     * 缺它会导致 `/usr/local/bin` 下的自建命令在容器内找不到）。
     */
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** 位置参数传命令时的 `$0`（纯标识位，仅用于报错信息可读性）。 */
    const val COMMAND_ARGV0 = "agentkit"

    /**
     * 位置参数传命令的脚本体：`cd -- "$1" && eval "$2"`。
     *
     * 命令**不拼进这个字符串**，而是作为 `$2` 由位置参数传入，因此命令里的空格、
     * 引号、通配符（`echo "*.vue"`）都按字面传给 `eval`，不会被二次转义。
     */
    const val COMMAND_SCRIPT = "cd -- \"\$1\" && eval \"\$2\""

    /** 提取后的宿主路径：`<filesDir>/bin/proot`。 */
    fun binaryFile(context: Context): File = File(context.filesDir, PROOT_RELATIVE_PATH)

    /**
     * 三件套共同落位目录：`<filesDir>/bin`——proot 与两个 .so 放同一目录，
     * `LD_LIBRARY_PATH` 即指这里（proot 的 RUNPATH 指向 Termux 私有目录，不可用）。
     */
    fun libDir(context: Context): File = File(context.filesDir, PROOT_BIN_DIR)

    /**
     * proot 是否已就绪：**三件套齐备**（proot + libtalloc.so.2 + libandroid-shmem.so）
     * 且 proot 可执行。
     *
     * 不能只判 proot：so 缺失时 `execve` 动态链接器直接失败，表现为进程瞬间退出且
     * 无清晰报错，只有三文件全齐才真正可启动。
     */
    fun isAvailable(context: Context): Boolean {
        val dir = libDir(context)
        val libsOk = LIB_ASSET_NAMES.all { isElf(File(dir, it)) }
        return libsOk && (nativeProot(context) != null || binaryFile(context).canExecute())
    }

    /**
     * 文件是否是**有效的 ELF 可执行体**（只看魔数 `\x7fELF`）。
     *
     * 为什么不能只判「存在」：仓库里的三个 arm64 二进制曾以 base64 文本形态提交，
     * APK 里带的就是纯文本。只看 `exists()` / `canExecute()` 会全部通过，
     * 直到运行期动态链接器才报错。历史上装过旧版的用户，`filesDir/bin` 里
     * 残留的正是这些文本文件——升级后若不重提，等于没修。
     *
     * 读 4 字节的代价可以忽略，换来的是「脏数据自动重建」。
     */
    private fun isElf(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 4L) return false
        file.inputStream().use { ins ->
            val head = ByteArray(4)
            if (ins.read(head) != 4) return false
            head[0] == 0x7F.toByte() &&
                head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() &&
                head[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /**
     * 确保 proot 三件套就位：先对 [REQUIRED_ASSET_NAMES] 逐个 `assets.open` **探测**
     * 判齐备（**不用 `assets.list`**：部分 Android 版本/ROM 上 `AssetManager.list()`
     * 对 assets 子目录会返回不完整甚至空数组，把「三个文件都好好躺在 APK 里」误判成
     * 「三件套不齐」，容器白白降级且日志误导；「能不能打开」才是可靠判据），通过后
     * 逐个提取到 [libDir]，每个都 `chmod 0700`（Android 对 filesDir 内的文件默认
     * 无执行位）。已齐备且 proot 可执行则直接复用（`force=true` 强制重提）。
     *
     * ## ⚠️ 风险说明（T09 / D1：proot 落位，必须在真机上验证）
     *
     * 当前落位：`assets/bin/arm64-v8a/{proot,libtalloc.so.2,libandroid-shmem.so}`
     * → `<filesDir>/bin/` 同名三文件 + 各自 `chmod 0700`。
     *
     * **Android 10+（API 29+）对应用私有目录施加 W^X（Write XOR Execute）限制**，
     * 且部分 ROM / SELinux 策略会拒绝从 `filesDir` `execve` 三方可执行文件。一旦被拦：
     * - 表象：`proot` 进程瞬间退出（退出码非 0），logcat **没有**清晰的 permission denied，
     *   很容易误判成「rootfs 坏了 / ptrace 受限」；
     * - 判定方法：见 `docs/` 中 T09 真机验证清单，核心是 adb 里直接
     *   `run-as <pkg> <filesDir>/bin/proot --version` 看退出码与 logcat 的
     *   `avc: denied { execute }` 行；
     * - **若确认被拦，改造方案**：把 proot 改名为 `libproot.so` 放进
     *   `app/src/main/jniLibs/<abi>/`，运行时从 `applicationInfo.nativeLibraryDir`
     *   取路径；同时在 `app/build.gradle.kts` 的 packaging 里设
     *   `useLegacyPackaging=true`（否则 `.so` 不解压，`execve` 拿不到文件）。
     *   **targetSdk 保持 34 不变**——参照仓库为了绕过该限制把 targetSdk 锁在 28，
     *   代价是上不了 Google Play，我们不接受这个代价。
     *
     * @return 就绪的 proot 文件；任何失败（资产缺失 / 提取失败 / 校验不过）返回
     *   null（调用方降级），并落 `AgentLogger.e` 点名缺哪个。
     */
    fun ensureBinary(context: Context, force: Boolean = false): File? {
        // 首选 jniLibs 里的 proot：nativeLibraryDir 允许 execve，绕开 Android 10+ 的 W^X。
        // 两个运行库仍要落到 filesDir（LD_LIBRARY_PATH 指向那里）。
        nativeProot(context)?.let { native ->
            if (!force && isAvailable(context)) return native
            if (extractLibs(context, force)) return native
            AgentLogger.e(TAG, "运行库提取失败，proot 无法启动")
            return null
        }

        val out = binaryFile(context)
        if (!force && isAvailable(context)) return out

        val dir = libDir(context)
        runCatching { dir.mkdirs() }.onFailure { e ->
            AgentLogger.e(TAG, "创建 proot 落位目录 ${dir.absolutePath} 失败：${e.message}")
            return null
        }

        // 齐备性判定逐个探测，不用 assets.list：部分 Android 版本/ROM 上
        // AssetManager.list() 对 assets 子目录会返回不完整甚至空数组，会把「三个文件
        // 都好好躺在 APK 里」误判成「三件套不齐」——容器白白降级且日志误导。
        // 「能不能打开」才是可靠判据：打开成功 → 资产在位（用完立即 close）；
        // 抛 IOException → 确实缺失，计入 missingAssets。
        val missingAssets = REQUIRED_ASSET_NAMES.filter { name ->
            runCatching {
                context.assets.open("${assetDir()}/$name").use { }
            }.isFailure
        }
        if (missingAssets.isNotEmpty()) {
            AgentLogger.e(
                TAG,
                "assets/${assetDir()} 三件套不齐，缺少：${missingAssets.joinToString(", ")}" +
                    "（proot 需要 libtalloc.so.2 与 libandroid-shmem.so 才能启动）"
            )
            return null
        }

        for (name in REQUIRED_ASSET_NAMES) {
            val target = File(dir, name)
            runCatching {
                context.assets.open("${assetDir()}/$name").use { ais ->
                    FileOutputStream(target).use { fos -> ais.copyTo(fos) }
                }
                // 0700：仅本应用可读可执行（proot 内含完整路径重写逻辑，最小权限够用；
                // 两个 .so 同权——动态链接器按调用者权限读，无需放宽到 0644）
                Os.chmod(target.absolutePath, 0b111000000)
            }.onFailure { e ->
                AgentLogger.e(TAG, "提取 assets/${assetDir()}/$name 失败：${e.message}")
                return null
            }
        }

        // 提取后校验：proot 可执行 + 两个 .so 在位（缺 .so 时 execve 会静默失败，
        // 这里显式点名，避免把「动态链接器找不到库」误诊成 rootfs 问题）
        if (!out.canExecute()) {
            AgentLogger.w(
                TAG,
                "proot 已落位但不可执行（${out.absolutePath}）：" +
                    "可能是 Android 10+ W^X/SELinux 限制，见 ensureBinary 的 KDoc 改造方案"
            )
            return null
        }
        val missingLibs = REQUIRED_ASSET_NAMES
            .filter { it != PROOT_ASSET_NAME && !File(dir, it).exists() }
        if (missingLibs.isNotEmpty()) {
            AgentLogger.e(
                TAG,
                "提取后运行库仍缺失：${missingLibs.joinToString(", ")}" +
                    "（目录 ${dir.absolutePath}）"
            )
            return null
        }
        return out
    }

    /**
     * proot 的「绑定 + 根目录 + 工作目录」选项（**不含 proot 自身路径与 guest shell**）。
     *
     * - `-r <rootfs>`：把解压后的 rootfs 作为容器根；
     * - `-b /dev -b /proc -b /sys`：透传 Android 的三个伪文件系统（容器里 `ps`/挂载视图靠它）；
     * - `-b /sdcard`：把外存挂进容器，便于和宿主交换文件；
     * - `-w <workDir>`：guest 初始工作目录（如 `/root`）；
     * - `-L`（`--link2symlink`）：把硬链接降级成符号链接。Android 上 `/data` 与 `/sdcard`
     *   是不同设备，tar 解包 / 包管理器建硬链接会 EFBIG/EXDEV，这一项让它们改走 symlink；
     * - `--root-id`（`-0`）：**fake root**，容器内 uid/gid 映射成 0。
     *   缺了它容器里就是普通 app uid——提示符是 `$` 而不是 `#`，更关键的是包管理器
     *   的 trigger 脚本里 `chown root:root` 会直接失败（真机装 JDK 时的
     *   `1 error: 424 MiB in 77 packages` 正是这个：77 个包全装上了，只有一个
     *   trigger 因非 root 返回非 0，把 apk 的整体退出码拖成 1）。
     *   **注意 `-L` 不是 fake root**（旧注释写错了）：`-L` 是 link2symlink，
     *   fake root 的长选项是 `--root-id`，两者必须分别给；
     * - `--kill-on-exit`（`-k`）：proot 退出时杀掉它所有 tracee。
     *   没有它，超时后我们 `destroyForcibly()` 只杀 proot 本身，底下的 `apk` 会被
     *   detach 继续跑并占着 `/lib/apk/db/lock`，下一次安装直接 exit 99——
     *   这正是「超时 → 抢锁」的死循环入口。
     *
     * 以上三个选项均已在实际使用的 proot 二进制里确认存在
     *（`strings libproot.so` 可见 `--link2symlink` / `--root-id` / `--kill-on-exit`）。
     *
     * **注意**：返回值**不含 proot 自身路径**，只是选项片段；直接当 argv 用会让所有选项
     * 错位一位（`-r` 被当成 argv[0]）。需要完整调用请用 [buildInvocation]。
     *
     * @param rootfs   已解压的 rootfs 宿主路径
     * @param workDir  guest 内初始工作目录（相对 guest 根，如 `/root`）
     */
    fun prootOptions(rootfs: File, workDir: String): List<String> = buildList {
        add("-r"); add(rootfs.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-b"); add("/sdcard")
        add("-w"); add(workDir)
        add("--link2symlink")
        add("--root-id")
        add("--kill-on-exit")
    }

    /**
     * 容器内的环境变量（guest 视角，区别于宿主 TerminalSession 的 buildEnv）。
     *
     * 补齐项（T09 / D2，原理参照 aicode `LinuxContainerEngine` 的 env 段，净室重写）：
     * - `PROOT_TMP_DIR=<rootfs>/tmp`：**宿主路径**（proot 在 chroot 前用），Android 无 `/tmp`，
     *   缺它 proot 会在宿主根建临时文件失败；
     * - `TMPDIR=/tmp`：**guest 视角**，覆盖宿主指向 App cache 的值——容器内 `/data` 未挂载，
     *   沿用宿主 TMPDIR 会让 `mktemp` / `dpkg` 直接失败；
     * - `LD_LIBRARY_PATH=<libDir>`（[libDir] 非空时）：proot 的 ELF RUNPATH 指向 Termux
     *   私有目录 `/data/data/com.termux/files/usr/lib`，本机不存在，**必须用本项覆盖
     *   宿主同名值**，动态链接器才能找到 `libtalloc.so.2` / `libandroid-shmem.so`；
     * - `PATH` 显式含 `/usr/local/bin`（见 [GUEST_PATH]）；
     * - `GIT_CONFIG_GLOBAL=<guest 路径>`：避免容器内 git 报 dubious ownership / 找不到全局配置；
     * - `CI=true` / `NO_COLOR=1` / `PAGER=cat`（T10）：非交互三件套，
     *   抑制确认提示（`apt install -y`）、分页器（`git log`）、ANSI 颜色转义残留。
     *
     * @param rootfs      已解压的 rootfs 宿主路径；传 null 则不带 `PROOT_TMP_DIR`
     * @param packageName 宿主包名（容器内脚本据此识别调用方）
     * @param libDir      三件套落位目录（[libDir]）；传 null 则不带 `LD_LIBRARY_PATH`
     *   （仅测试/降级路径用，正式调用必须传）
     */
    fun guestEnvMap(
        rootfs: File?,
        packageName: String,
        nonInteractive: Boolean = true,
        libDir: File? = null
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = GUEST_HOME
        env["PATH"] = GUEST_PATH
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["ANDROID_PACKAGE"] = packageName
        env["TMPDIR"] = GUEST_TMP
        env["GIT_CONFIG_GLOBAL"] = GIT_CONFIG_GLOBAL
        if (rootfs != null) {
            // 宿主绝对路径：proot 自身的临时目录（在 -r chroot 生效之前就要可用）
            env["PROOT_TMP_DIR"] = File(rootfs, "tmp").absolutePath
        }
        if (libDir != null) {
            // 必须放在自定义 env 里覆盖宿主同名值：proot 的 RUNPATH 指向 Termux 私有目录，
            // 不覆盖则动态链接器找不到 libtalloc.so.2，execve 后静默失败
            env["LD_LIBRARY_PATH"] = libDir.absolutePath
        }
        if (nonInteractive) {
            env["CI"] = "true"
            env["NO_COLOR"] = "1"
            env["PAGER"] = "cat"
        }
        return env
    }

    /**
     * [guestEnvMap] 的列表出口（`KEY=VALUE`），供需要 `List<String>` 的调用点使用。
     *
     * @param rootfs 传 null 则不带 `PROOT_TMP_DIR`
     * @param libDir 三件套落位目录；传 null 则不带 `LD_LIBRARY_PATH`
     */
    fun guestEnv(rootfs: File?, packageName: String, libDir: File? = null): List<String> =
        ProcessEnv.toEnvList(guestEnvMap(rootfs, packageName, libDir = libDir))

    /**
     * 构造完整的 proot 调用描述（T09 / C4+C5 的核心出口）。
     *
     * **argv[0] 必须是 proot 二进制自身**（否则所有选项错位一位），末位是 guest shell。
     *
     * `envArray` = `System.getenv()` + 自定义 env 的**并集**（自定义覆盖同名键）：
     * PTY `forkpty` 后子进程不继承 JVM 的 `System.getenv()`，只喂自定义 env 会让 proot
     * 因缺 `ANDROID_ROOT` 等宿主变量瞬间退出**且日志无任何报错**。自定义 env 里的
     * `LD_LIBRARY_PATH` 同样以「覆盖」语义压掉宿主的 Termux 路径失效值。
     *
     * `libDir` 取 `prootBinary.parentFile`——三件套同目录落位（见 [libDir]），
     * 与 [ensureBinary] 的提取布局保持一致。
     *
     * @param prootBinary 已就绪的 proot 二进制（[ensureBinary] 的返回值）
     * @param rootfs      已解压的 rootfs 宿主路径
     * @param workDir     guest 内初始工作目录
     * @param packageName 宿主包名
     */
    fun buildInvocation(
        prootBinary: File,
        rootfs: File,
        workDir: String,
        packageName: String,
        /**
         * 两个运行库所在目录（`LD_LIBRARY_PATH`）。
         *
         * **不能默认用 `prootBinary.parentFile`**：proot 本体已改从 `nativeLibraryDir`
         * 执行（绕开 Android 10+ 的 W^X），但 `libtalloc.so.2` / `libandroid-shmem.so`
         * 仍由 assets 提到 `filesDir/bin`。两者不同目录时若沿用 parentFile，
         * 动态链接器会报 `library "libtalloc.so.2" not found`——exec 成功但立刻退出
         *（真机/模拟器实测 2026-10-24）。
         */
        libDir: File? = null
    ): ProotInvocation {
        // 尽力确保 guest /tmp 与宿主侧 proot 临时目录存在（失败不影响调用，仅日志）。
        // mkdirs() 失败只返回 false 不抛异常（runCatching 抓不到），必须显式校验返回值。
        val tmpDir = File(rootfs, "tmp")
        val tmpOk = runCatching { tmpDir.mkdirs() || tmpDir.isDirectory }.getOrDefault(false)
        if (!tmpOk) {
            AgentLogger.w(TAG, "创建 <rootfs>/tmp 失败，PROOT_TMP_DIR 将指向不存在的目录")
        }

        val executable = prootBinary.absolutePath
        val argv = buildList {
            add(executable) // argv[0] == proot 自身路径
            addAll(prootOptions(rootfs, workDir))
            add(GUEST_SHELL) // guest shell
        }
        val envMap = guestEnvMap(rootfs, packageName, libDir = libDir ?: prootBinary.parentFile)
        return ProotInvocation(
            executable = executable,
            argv = argv,
            envMap = envMap,
            envArray = ProcessEnv.mergeHostEnv(envMap)
        )
    }

    /**
     * [buildInvocation] 的 Context 便捷重载：路径与包名都从 [context] 取。
     *
     * @return 调用描述；proot 三件套未就位（未提取 / 不可执行 / .so 缺失）时返回
     *   null，由调用方降级。
     */
    fun buildInvocation(
        context: Context,
        rootfs: File,
        workDir: String = GUEST_HOME
    ): ProotInvocation? {
        val bin = ensureBinary(context)
        if (bin == null || !isAvailable(context)) {
            AgentLogger.w(
                TAG,
                "proot 三件套未就位（proot=${bin?.absolutePath ?: "null"}，libDir=${libDir(context)}）：" +
                    "请先 ensureBinary；若 proot 存在但不可执行，见 ensureBinary 的 KDoc" +
                    "（Android 10+ W^X 风险）；若 .so 缺失，proot 会启动即静默失败"
            )
            return null
        }
        return buildInvocation(bin, rootfs, workDir, context.packageName, libDir(context))
    }

    /**
     * 构造 guest 内「一次性命令」的 argv（T10：**位置参数传命令**）。
     *
     * 形态（7 项 argv，命令以 `$2` 传入，不拼进脚本字符串）：
     * ```
     * /bin/sh -l -c 'cd -- "$1" && eval "$2"' agentkit "$cwd" "$cmd"
     * ```
     *
     * **为什么安全**：`$cmd` 是独立 argv 元素，shell 只对它做一次参数展开——
     * `eval` 看到的就是命令原始文本。像 `echo "*.vue"` 这类含空格 / 引号 / 通配符的
     * 命令，引号与通配符都由**容器内 shell 自己**按字面解析一次，**不会**被我们二次
     * 转义（拼进脚本字符串则需要再套一层转义，极易写错）。
     * `-l` 是 login shell，会加载 `/etc/profile`，使 `/usr/local/bin` 等路径生效。
     *
     * @param cwd     guest 内工作目录（作为 `$1`）
     * @param command 要执行的命令原文（作为 `$2`，**原样传入，不做转义**）
     * @param shell   guest shell 路径，默认 [GUEST_SHELL]
     */
    fun buildCommandArgv(
        cwd: String,
        command: String,
        shell: String = GUEST_SHELL
    ): List<String> = listOf(
        shell,
        "-l",
        "-c",
        COMMAND_SCRIPT,
        COMMAND_ARGV0, // $0
        cwd,           // $1
        command        // $2
    )

    /**
     * POSIX 单引号强引用：把任意字符串包成 shell 里的**单个字面量参数**。
     * `'` → `'\''`（闭合 + 转义单引号 + 重新打开）。
     *
     * 仅在「必须把 argv 退化成一整行文本写进 pty stdin」时使用（见
     * [ContainerSession.runCommand]）；有 argv 通道时优先用 [buildCommandArgv]。
     */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /** [buildCommandArgv] 的文本出口：argv → 可直接写进 pty stdin 的一行命令。 */
    fun toCommandLine(cwd: String, command: String, shell: String = GUEST_SHELL): String =
        buildCommandArgv(cwd, command, shell).joinToString(" ") { shellQuote(it) }

    /** 把两个运行库从 assets 提到 filesDir；proot 通过 LD_LIBRARY_PATH 找它们。 */
    private fun extractLibs(context: Context, force: Boolean): Boolean {
        val dir = libDir(context)
        runCatching { dir.mkdirs() }
        for (name in LIB_ASSET_NAMES) {
            val target = File(dir, name)
            // 已存在但不是 ELF（旧版残留的 base64 文本）→ 必须覆盖，否则永远修不好
            if (!force && isElf(target)) continue
            runCatching {
                context.assets.open("${assetDir()}/$name").use { ais ->
                    FileOutputStream(target).use { fos -> ais.copyTo(fos) }
                }
                Os.chmod(target.absolutePath, 0b111000000)
            }.onFailure { e ->
                AgentLogger.e(TAG, "提取 $name 失败：${e.message}")
                return false
            }
        }
        return true
    }
}

/**
 * proot 的完整调用描述：可执行文件 + 完整 argv + 环境变量（Map / 数组两种出口）。
 *
 * - [executable]：proot 二进制绝对路径（供 `ProcessBuilder` 用）；
 * - [argv]：**完整 argv，`argv[0] == executable`**，末位是 guest shell `/bin/sh`
 *   （供 `execve` / JNI `createSubprocessArgv` 用）；
 * - [envMap]：自定义环境变量（供 `ProcessBuilder.environment()` 用，DEBUG 可读性最好）；
 * - [envArray]：`System.getenv()` + [envMap] 的并集，同名键 [envMap] 胜出
 *   （供 PTY / `execve` 用——这是 proot 不静默退出的关键）。
 */
data class ProotInvocation(
    val executable: String,
    val argv: List<String>,
    val envMap: Map<String, String>,
    val envArray: Array<String>
) {

    /**
     * 自检：返回所有不满足不变量的描述（**空列表 = 通过**）。
     *
     * 项目无 JUnit 依赖（离线仓库缺 junit 包），验收走「纯函数 + 日志自检」：
     * 调用方（[ContainerSession.create]）把结果打进 [AgentLogger]，真机 / 日志一查即知。
     */
    fun validate(): List<String> = buildList {
        if (argv.isEmpty()) {
            add("argv 为空")
        } else {
            if (argv[0] != executable) add("argv[0]=${argv[0]} != executable=$executable（选项会错位一位）")
            if (argv.last() != ProotRunner.GUEST_SHELL) {
                add("argv 末位=${argv.last()} 不是 guest shell(${ProotRunner.GUEST_SHELL})")
            }
        }
        if (envArray.isEmpty()) {
            add("envArray 为空：PTY 不继承父进程环境，proot 会因缺 ANDROID_ROOT 等静默退出")
        } else {
            val arr = envArray.map { it.substringBefore('=') }.toSet()
            for (key in listOf(
                "PATH", "TMPDIR", "PROOT_TMP_DIR", "LD_LIBRARY_PATH",
                "CI", "NO_COLOR", "PAGER"
            )) {
                if (key !in arr) add("envArray 缺少 $key")
            }
            if (!arr.contains("ANDROID_ROOT") && !arr.contains("ANDROID_DATA")) {
                add("envArray 未包含任何宿主 Android 变量（未与 System.getenv() 合并？）")
            }
        }
    }

    /** 一行摘要（供日志；避免直接打 envArray 把宿主环境全量写进日志）。 */
    fun describe(): String =
        "executable=$executable, argv=${argv.joinToString(" ")}, " +
            "envMap=${envMap.size} 项, envArray=${envArray.size} 项"

    // 手动实现：data class 默认的 equals/hashCode 对 Array 走引用比较，toString 会打出对象地址
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProotInvocation) return false
        return executable == other.executable &&
            argv == other.argv &&
            envMap == other.envMap &&
            envArray.contentEquals(other.envArray)
    }

    override fun hashCode(): Int {
        var result = executable.hashCode()
        result = 31 * result + argv.hashCode()
        result = 31 * result + envMap.hashCode()
        result = 31 * result + envArray.contentHashCode()
        return result
    }

    override fun toString(): String = "ProotInvocation(${describe()})"
}
