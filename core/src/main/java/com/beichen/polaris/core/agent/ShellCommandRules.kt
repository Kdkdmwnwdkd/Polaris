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
