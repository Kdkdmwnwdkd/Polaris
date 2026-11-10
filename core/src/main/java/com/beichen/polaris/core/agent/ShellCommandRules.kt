package com.beichen.polaris.core.agent

/**
 * `shell_run` 的「按命令内容决定要不要审批」规则——**纯函数**，无 Android 依赖，可直接单测。
 *
 * ## 要解决的问题
 *
 * `shell_run` 原来的做法是 `needsApproval = true`：不管跑什么，每一条命令都弹一次窗。
 * 实际后果是相反的——
 *  - 用户被 `ls`、`cat`、`pwd` 这类纯读命令反复打断，几次之后就形成「看到弹窗就点允许」的肌肉记忆；
 *  - 等真的出现 `rm -rf /sdcard/Download` 时，那条弹窗和前面十条长得一模一样，拦截形同虚设。
 *
 * 所以判定必须看**命令本身**：只读命令放行，写操作/重定向/命令替换一律询问。
 * 这与 Claude Code 的 `Bash(git status:*)` 授权模型是同一个思路（这里按原理重写，
 * 只读白名单与判定细节都按本项目的执行环境重新定）。
 *
 * ## 为什么要处理这么多边缘情况
 *
 * 「只读」不能只看第一个词：`find . -delete`、`sort -o out.txt`、`tree -o f`、
 * `uniq a.txt b.txt`（第二个参数是输出文件）都是披着只读外衣的写操作。
 * 而 `$(...)`、反引号、`<(...)` 可以在一条「看起来只读」的命令里执行任意东西，
 * 只要出现就必须问。
 *
 * ## 第三个问题：读的是谁的文件
 *
 * 「只读」不等于「无害」：`cat /sdcard/DCIM/x.jpg` 完全只读，但它把文件工具的
 * 工作区沙箱整条绕开了。所以本文件还回答 [sensitiveReadRoot]——这一条不决定
 * 「要不要问」（只读命令照旧不问），而是决定「问的时候按什么粒度记住」。
 */
object ShellCommandRules {

    /**
     * 确认无副作用的命令。
     *
     * 只收「输出到 stdout、不碰文件系统」的：`ls cat head tail wc grep find pwd echo …`。
     * 刻意**不含** `sed`/`awk`（`-i` 会改文件）、`tee`、`xargs`（能拼出任意命令）、
     * `cp`/`mv`/`rm`。
     */
    private val READ_ONLY: Set<String> = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "find", "pwd",
        "echo", "printf", "date", "stat", "file", "du", "df", "sort", "uniq", "diff",
        "cmp", "md5sum", "sha1sum", "sha256sum", "which", "tree", "uname", "whoami",
        "id", "basename", "dirname", "cut", "tr", "nl", "od", "hexdump", "xxd", "rg",
        "jq", "column", "seq", "free", "uptime", "ps", "getprop", "dumpsys",
    )

    /** 这些命令的「记忆粒度」是前两个词（`git status` 与 `git push` 风险完全不同）。 */
    private val TWO_WORD: Set<String> = setOf(
        "git", "npm", "pnpm", "yarn", "pip", "pip3", "cargo", "go", "apt", "apt-get",
        "apk", "pkg", "pm", "cmd", "svc",
    )

    /** `&& | || ; 换行` 都是「一条命令串」，每一段都得单独判定。 */
    private val SPLIT = Regex("\\s*(?:&&|\\|\\||;|\\||\\n)\\s*")

    private val FIND_WRITES = Regex("(^|\\s)-(exec|execdir|ok|okdir|delete|fprint\\w*)\\b")

    /** 命令里出现这些，就可能在「只读」的外壳里执行任意代码。 */
    private val SUBSTITUTION = listOf("\$(", "`", "<(", ">(", "\${")

    /** 拆成独立的命令段（去掉空段）。 */
    fun segments(command: String): List<String> =
        command.trim().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 这条命令要不要弹窗。
     *
     * @return true = 需要用户确认
     */
    fun needsApproval(command: String): Boolean {
        val parts = segments(command)
        if (parts.isEmpty()) return true          // 空命令交给工具自己报错，但不静默放行
        if (SUBSTITUTION.any { command.contains(it) }) return true
        // 重定向：`>` `>>` 写文件；`<` 虽是读，但配合上面的替换判定已经足够可疑，一并问
        if (command.contains('>')) return true
        return parts.any { !isReadOnly(it) }
    }

    /** 单段是否只读（不含管道，管道已在 [segments] 拆开）。 */
    private fun isReadOnly(segment: String): Boolean {
        if ('&' in segment) return false           // 后台执行：可能留下长期进程
        val words = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = words.firstOrNull()?.substringAfterLast('/') ?: return false
        if (head !in READ_ONLY) return false
        val args = words.drop(1)
        return when (head) {
            // find 自身的 `-exec`/`-delete`/`-ok` 就是执行/删除
            "find" -> !FIND_WRITES.containsMatchIn(segment)
            // sort -o / --output 写文件（-o 可能黏在组合短选项里，如 `-uo`）
            "sort" -> args.none { it == "-o" || it.startsWith("--output") || (it.startsWith("-") && !it.startsWith("--") && 'o' in it) }
            // uniq 的第二个位置参数是输出文件
            "uniq" -> args.count { !it.startsWith("-") } <= 1
            // tree -o 写文件
            "tree" -> args.none { it == "-o" }
            // dumpsys 本身只读，但 dumpsys 某些服务（如 battery reset）会改状态，保守起来只信常见只读子命令
            "dumpsys" -> args.isEmpty() || args.first() in SAFE_DUMPSYS
            else -> true
        }
    }

    /** `dumpsys` 里确认只读的服务。 */
    private val SAFE_DUMPSYS: Set<String> = setOf(
        "activity", "window", "package", "battery", "meminfo", "cpuinfo", "display",
        "wifi", "telephony.registry", "input", "power", "alarm",
    )

    /**
     * 「总是允许」要记住的前缀（第一段的命令名；[TWO_WORD] 里取前两个词）。
     *
     * 目前只用于日志与后续规则表；判定本身不依赖它——先让「只读放行」跑起来，
     * 用户自定义规则（`git status` → 允许、`rm` → 拒绝）留到下一步接进设置页。
     */
    fun prefixOf(command: String): String {
        val words = segments(command).firstOrNull().orEmpty()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
            .dropWhile { it.contains('=') && !it.startsWith("-") }
        if (words.isEmpty()) return ""
        val first = words[0].substringAfterLast('/')
        return if (first in TWO_WORD && words.size > 1 && !words[1].startsWith("-")) {
            "$first ${words[1]}"
        } else {
            first
        }
    }

    /**
     * R-08：这条命令是不是**不可逆**的那一类。
     *
     * 判定不参与「要不要弹窗」（那是 [needsApproval]），只决定「用户点『不再询问』之后
     * 能不能记住」。修前的缺陷正是把两者混成一个：`sessionAllowed` 存的是 `shell` 组，
     * 于是用户为了不再被 `npm install` 打断而勾选，顺带把 `rm -rf` 也放行了。
     *
     * 收敛尺度：只收「删了/改了/提权了就回不去」的命令。**不含**重定向与普通写文件——
     * 那类操作用户勾选时是知情的，每次都问反而把人训练成闭眼点允许。
     */
    fun isDestructive(command: String): Boolean {
        if (command.isBlank()) return false
        return segments(command).any { seg -> segmentIsDestructive(seg) }
    }

    /** 不可逆命令的首词。刻意保守：只收真正回不去的，宁可漏也不把日常命令都圈进来。 */
    private val DESTRUCTIVE_HEADS: Set<String> = setOf(
        "rm", "rmdir", "shred", "dd", "mkfs", "mke2fs", "fdisk", "parted", "wipefs",
        "mv", "chmod", "chown", "chgrp",
        "su", "sudo", "sh", "bash", "zsh", "dash",
        "reboot", "shutdown", "halt", "poweroff",
        "kill", "pkill", "killall",
        "mount", "umount", "insmod", "rmmod",
        "fastboot",
    )

    private fun segmentIsDestructive(segment: String): Boolean {
        val words = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = words.firstOrNull()?.substringAfterLast('/') ?: return false
        // mkfs 有多种后缀形态（mkfs.ext4 / mkfs.vfat），按前缀收
        if (head in DESTRUCTIVE_HEADS || head.startsWith("mkfs.")) return true
        // 同一段里的子命令判定：这些工具本身无害，但某个子命令不可逆
        return when (head) {
            // find -delete / -exec 前面已按写操作处理，这里同样算不可逆
            "find" -> FIND_WRITES.containsMatchIn(segment)
            "pm" -> words.any { it == "uninstall" || it == "clear" || it == "uninstall-system-updates" }
            "am" -> words.any { it == "force-stop" }
            "git" -> segment.contains("reset --hard") || words.any { it == "clean" } ||
                segment.contains("push --force") || segment.contains("push -f")
            "cmd" -> words.any { it == "wipe" || it == "delete" }
            else -> false
        }
    }

    /* ------------------------------------------------------------------ */
    /* 路径边界：只读命令读的是不是「工作区以外」的用户数据                    */
    /* ------------------------------------------------------------------ */

    /**
     * 受保护的读目录：共享存储（相册 / 下载 / 微信目录）与其它应用的数据目录。
     *
     * ## 为什么要有这一档
     *
     * 文件工具（`read_file` / `write_file`）被 [com.beichen.polaris.core.workspace.PathGuard]
     * 锁在工作区里，但 `shell_run` 里的 `cat` 是**整条绕过**：容器把 `/sdcard` 直接
     * bind 进去（`-b /sdcard`），无线调试 / Shizuku 以 uid 2000 跑，本机 shell 也照样能读。
     * 于是「工作区沙箱」只对文件工具成立，对 shell 是句空话——
     * 一条提示注入就能让 Agent `cat /sdcard/DCIM/…`，把相册读进上下文，
     * 再经模型自身的通道发出去（读是 Agent 的本职，这一层拦不住也不该拦）。
     *
     * 拦截点只能是**读的那一刻**：让用户看见「要读相册了」，而不是事后发现。
     * 判定放在审批层而不是后端里，是因为它要覆盖 proot / 无线调试 / Shizuku / 本机
     * 四条通道——那四条通道的启动方式各不相同，但审批链只有一条。
     *
     * 尺度：只收**用户数据**所在的根。`/proc`、`/sys`、`/system`、`/data/local/tmp`
     * 这类读起来不泄露用户内容的位置一律不收，避免把「看一眼 CPU 型号」也变成弹窗。
     */
    private val PROTECTED_READ_ROOTS: List<String> = listOf(
        "/sdcard", "/storage", "/mnt/media_rw", "/mnt/user", "/mnt/runtime",
        "/data/media", "/data/data", "/data/user", "/data/misc", "/data/system", "/data/app",
    )

    /**
     * 这条命令读的是不是受保护目录；返回**同意粒度**（见 [consentScope]），否则 null。
     *
     * 只看**只读段**里的**绝对路径**：写命令本来就要审批（且多半不可记忆），
     * 相对路径落在 cwd 里（本机=filesDir、容器=/root），都不构成「绕过工作区」——
     * 唯一能越界的写法就是写死一个绝对路径。
     *
     * 注意这**不是**「要不要弹窗」的完整判据：`cat /sdcard/x > out` 这种带重定向的命令
     * 由 [isMemorizable] 判成不可记忆，走的是「每次都问」那一档；本函数只回答
     * 「它在读哪个受保护目录」，供审批链生成**按目录**的授权键。
     */
    fun sensitiveReadRoot(command: String): String? {
        if (command.isBlank()) return null
        for (seg in segments(command)) {
            if (!isReadOnly(seg)) continue
            for (token in seg.split(Regex("\\s+"))) {
                val raw = absolutePathIn(token) ?: continue
                val normalized = normalizeAbsolute(raw) ?: continue
                val root = PROTECTED_READ_ROOTS.firstOrNull {
                    normalized == it || normalized.startsWith("$it/")
                } ?: continue
                return consentScope(normalized, root)
            }
        }
        return null
    }

    /**
     * 从 argv 里一枚 token 取出绝对路径；不是绝对路径返回 null。
     *
     * 处理的三种写法都是真机上常见的：`/sdcard/a`、`"/sdcard/My Files/a.jpg"`（引号被
     * 分词留下）、`--file=/sdcard/a`（长选项带值）。
     */
    private fun absolutePathIn(token: String): String? {
        var t = token.trim().trimEnd(',', ';', ')', ']', '}')
        t = t.trim('"', '\'')
        if (t.startsWith("--")) {
            val eq = t.indexOf('=')
            if (eq < 0) return null
            t = t.substring(eq + 1).trim('"', '\'')
        }
        return t.takeIf { it.startsWith("/") }
    }

    /**
     * 规范化绝对路径：折叠 `.` / `..` 与重复分隔符。
     *
     * 必须做，否则 `cat /sdcard/../data/data/x` 会被当成 `/sdcard` 下的读——
     * 授权键按字面量算，用户同意的是「相册」，实际读的却是别的应用的私有目录。
     */
    private fun normalizeAbsolute(path: String): String? {
        val parts = ArrayDeque<String>()
        for (piece in path.split('/')) {
            when (piece) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(piece)
            }
        }
        return if (parts.isEmpty()) null else "/" + parts.joinToString("/")
    }

    /**
     * 同意粒度：受保护根 + **第一层子目录**（`/sdcard/DCIM/a.jpg` → `/sdcard/DCIM`）。
     *
     * 比文件粒度粗一档是刻意的：用户勾「不再询问」时想要的是「这个目录里的东西别问我」，
     * 按文件记会让「统计整个下载目录」变成十几次弹窗，最后又训练出闭眼点允许的习惯。
     * 粗到根本身（`/sdcard`）则不行——那等于一次点击放行整个相册。
     */
    private fun consentScope(normalized: String, root: String): String {
        if (normalized == root) return root
        val first = normalized.removePrefix("$root/").substringBefore('/')
        return if (first.isEmpty()) root else "$root/$first"
    }

    /* ------------------------------------------------------------------ */
    /* 记忆粒度：这条命令允许被「本次会话不再询问」记住吗？                    */
    /* ------------------------------------------------------------------ */

    /**
     * 审计 F2：`isDestructive` 只认首词，因此「不可记忆」档能被一层壳绕过——
     * `busybox rm -rf`、`env rm`、`nohup rm`、`$(rm -rf …)` 的首词全都无害，
     * 于是它们落回可记忆的 `shell` 键：用户为 `npm install` 勾一次「不再询问」，
     * 之后这些命令全部静默执行。
     *
     * 本函数回答的是**另一个问题**：「这条命令本身能不能被记住」，与「它是不是不可逆」
     * 分开判。分开的理由：`echo x > out.txt` 是写文件、但不是不可逆（不该标红吓人），
     * 然而它同样不该被一次 `npm install` 的授权顺带放行。
     *
     * 判据（任一命中即不可记忆）：
     *  - 不可逆（[isDestructive]）；
     *  - 含命令替换 / 反引号 / here-string：外壳里能塞任意命令；
     *  - 含重定向（`>`、`<`）：写文件或读任意文件；
     *  - 任一段的首词是包装器（[WRAPPERS]）或解释器（[INTERPRETERS]）；
     *  - 任一段既不在只读白名单、也不在可记忆白名单（[MEMORIZABLE_HEADS]）。
     *
     * 也就是说：**可记忆的是闭集**。需要放行的日常命令请往白名单里加，
     * 而不是把判定放宽成「首词不认识就当安全」。
     */
    fun isMemorizable(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return true          // 空命令由工具自己报错，不在这里拦
        if (isDestructive(trimmed)) return false
        if (SUBSTITUTION.any { trimmed.contains(it) }) return false
        if (trimmed.contains('>') || trimmed.contains('<')) return false
        val parts = segments(trimmed)
        return parts.isNotEmpty() && parts.all { isMemorizableSegment(it) }
    }

    /**
     * 包装器：首词无害，真正的命令在后面。
     *
     * 不再逐个解包（`busybox sh -c "busybox rm -rf"` 这类嵌套解不干净），
     * 直接判「不可记忆」——用户多确认一次的成本，低于误删一次相册。
     */
    private val WRAPPERS: Set<String> = setOf(
        "busybox", "toybox", "env", "nohup", "setsid", "timeout", "stdbuf", "nice", "ionice",
        "xargs", "parallel", "watch", "strace", "ltrace", "proot", "chroot", "doas", "pkexec",
        "command", "exec", "eval", "source", ".",
    )

    /** 解释器：`python -c` / `node -e` / `sed -i` 都能在「首词安全」的外壳里做任何事。 */
    private val INTERPRETERS: Set<String> = setOf(
        "python", "python3", "node", "deno", "bun", "ruby", "perl", "php", "lua", "java",
        "jshell", "rscript", "osascript", "awk", "gawk", "mawk", "sed",
    )

    /**
     * 可以直接被记住的**构建/查询类**命令首词（闭集）。
     *
     * 选它们的标准：日常任务里重复出现、且单条命令的破坏半径有限（构建产物、依赖目录）。
     * `cp` / `mv` / `tee` / `dd` 一律不在内——它们的破坏半径由参数决定，不由命令名决定。
     */
    private val MEMORIZABLE_HEADS: Set<String> = setOf(
        "npm", "pnpm", "yarn", "pip", "pip3", "cargo", "go", "gradle", "gradlew", "mvn",
        "make", "cmake", "ninja", "bazel", "uv", "poetry", "composer", "dotnet", "flutter",
    )

    /** `git` 只放行只读子命令；`git clean` / `reset --hard` 已由 [isDestructive] 先拦掉。 */
    private val MEMORIZABLE_GIT_SUBCOMMANDS: Set<String> = setOf(
        "status", "diff", "log", "show", "fetch", "branch", "remote", "rev-parse", "describe",
    )

    private fun isMemorizableSegment(segment: String): Boolean {
        if ('&' in segment) return false
        val words = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .dropWhile { it.contains('=') && !it.startsWith("-") }   // 跳过 `FOO=1 cmd` 的前缀赋值
        val head = words.firstOrNull()?.substringAfterLast('/') ?: return false
        if (head in WRAPPERS || head in INTERPRETERS) return false
        // 复用只读判定的细节：`tree -o` / `sort -o` / `uniq a b` 首词在只读白名单里，
        // 但参数把结果写进了文件。直接 `return true` 会漏掉这一层。
        if (head in READ_ONLY) return isReadOnly(segment)
        if (head in MEMORIZABLE_HEADS) return true
        if (head == "git") {
            val sub = words.getOrNull(1)
            return sub != null && sub in MEMORIZABLE_GIT_SUBCOMMANDS
        }
        return false
    }
}
