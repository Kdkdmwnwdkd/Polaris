package com.beichen.polaris.model

import android.content.Context
import java.io.File

/**
 * 扫描磁盘，出一张账。
 *
 * ## 为什么要走「目录清单 + 兜底」，而不是遍历后自由分类
 *
 * 自由分类（按文件名猜）在真机上一定会错，而且错了没人发现——数字照样显示，
 * 只是被归到了错误的组里。这里的做法相反：**先声明我们知道哪些目录**，
 * 逐个量；量不到的余额单独成一项。清单漏了，界面上会立刻多出一行「其它 · 2.1GB」，
 * 下一轮把它补进清单即可。这是可自证的结构，不需要谁来记住「有没有漏」。
 *
 * ## 线程
 *
 * 递归统计是 IO（几百毫秒到几秒，GB 级模型目录在低端机上更慢）。
 * 调用方必须在 IO 线程调，别在主线程。
 */
fun auditStorage(context: Context): StorageAudit {
    val app = context.applicationContext
    val info = app.applicationInfo
    val files = app.filesDir
    val cache = app.cacheDir

    val apkBytes = info.sourceDir?.let { File(it).length() } ?: 0L
    val nativeBytes = info.nativeLibraryDir?.let { sizeOf(File(it)) } ?: 0L

    val items = buildList {
        // —— 本体 ——
        add(
            SpaceItem(
                id = "apk",
                label = "安装包",
                detail = "你下载的那个 ${formatBytes(apkBytes)} 的包，装完就进了系统里",
                note = "不能删，删了应用就没了",
                bytes = apkBytes,
                bucket = Bucket.BODY
            )
        )
        add(
            SpaceItem(
                id = "native",
                label = "运行时库",
                detail = "从包里解压出来的 native 库（本地推理、容器、语音），必须落盘才能执行",
                note = "不能删。这部分是「应用本体」，不是缓存",
                bytes = nativeBytes,
                bucket = Bucket.BODY
            )
        )

        // —— 模型：先按磁盘真实目录量，再按型号拆分，避免「枚举漏一个就少算」 ——
        // 本地大模型单独一条：它是唯一可能到 GB 级的，也是最该被看见的一条。
        val gguf = File(files, "models")
        add(
            SpaceItem(
                id = "models-local",
                label = "本地大模型",
                detail = localModelDetail(gguf),
                note = "删掉不影响使用，但要重新下载（通常几个 GB）；离线就靠它",
                bytes = sizeOf(gguf),
                bucket = Bucket.MODEL,
                // 删除入口在「模型」页：那里有逐个模型的删除与校验，这里只做账本。
                clearable = false
            )
        )
        // 语音/容器/工具：ModelSpec 已经是单一真源，逐个量。
        ModelSpec.entries.forEach { spec ->
            val bytes = sizeOf(File(File(files, spec.home), spec.dirName))
            add(
                SpaceItem(
                    id = "model-${spec.name}",
                    label = spec.label,
                    detail = "位于 ${spec.home}/${spec.dirName}",
                    note = when (spec.kind) {
                        ModelSpec.Kind.ASR -> "删了语音识别不可用（要说话才需要）"
                        ModelSpec.Kind.TTS -> "删了离线播报与声音克隆不可用（在线音色仍可用）"
                        ModelSpec.Kind.CONTAINER -> "删了容器要重新解压（几十 MB），一次性代价"
                        ModelSpec.Kind.TOOL -> "删了「用 Shizuku 提权」这条路不可用，需要时能重下"
                    },
                    bytes = bytes,
                    bucket = Bucket.MODEL
                )
            )
        }

        // —— 我的内容 ——
        add(
            SpaceItem(
                id = "conversations",
                label = "聊过的对话",
                detail = "每一轮的提问、回答与工具轨迹",
                note = "删了不可恢复。想腾空间先删不用的会话，而不是整目录",
                bytes = sizeOf(File(files, "conversations")),
                bucket = Bucket.CONTENT
            )
        )
        add(
            SpaceItem(
                id = "media",
                label = "附件与作品",
                detail = "发过的图片、生成图片与视频",
                note = "删了不可恢复。这是最容易被忽略的一笔：视频单个就几十 MB",
                bytes = sizeOf(File(files, "media")),
                bucket = Bucket.CONTENT
            )
        )
        add(
            SpaceItem(
                id = "knowledge",
                label = "知识库",
                detail = "导入的文档与切片索引",
                note = "删了要重新导入原始文件",
                bytes = sizeOf(File(files, "knowledge")),
                bucket = Bucket.CONTENT
            )
        )
        add(
            SpaceItem(
                id = "skills",
                label = "装的技能",
                detail = "从本地或市场导入的技能包",
                note = "删了要重装；自己写的技能没有副本",
                bytes = sizeOf(File(files, "skills")),
                bucket = Bucket.CONTENT
            )
        )
        add(
            SpaceItem(
                id = "workspace",
                label = "Agent 的工作区",
                detail = "它读写过的文件都在这（导出的表格、下载的图片、写的脚本）",
                note = "删了不可恢复。想清就按文件清，别整目录删",
                bytes = sizeOf(File(files, "agent_workspace")),
                bucket = Bucket.CONTENT
            )
        )
        add(
            SpaceItem(
                id = "memory",
                label = "记忆库",
                detail = "你让它记住的偏好与事实",
                note = "删了它就真的忘了。通常只有几十 KB，不值得动",
                bytes = sizeOf(File(files, "memory")),
                bucket = Bucket.CONTENT
            )
        )

        // —— 临时与日志 ——
        add(
            SpaceItem(
                id = "cache",
                label = "临时缓存",
                detail = "下载残留、解压中间态、更新包、网页缓存",
                note = "可以直接清，系统空间紧张时它也会自己消失",
                bytes = sizeOf(cache),
                bucket = Bucket.TEMP,
                clearable = true
            )
        )
        add(
            SpaceItem(
                id = "logs",
                label = "运行日志",
                detail = "排查问题用的记录",
                note = "可以直接清；但下次出问题时就没有现场了",
                bytes = sizeOf(File(files, "logs")),
                bucket = Bucket.TEMP,
                clearable = true
            )
        )
        add(
            SpaceItem(
                id = "run-journal",
                label = "回合日志",
                detail = "崩溃后恢复未完成回合用的现场（只留最近 20 个）",
                note = "可以直接清；清掉后崩溃了也没法续跑",
                bytes = sizeOf(File(files, "run-journal")),
                bucket = Bucket.TEMP,
                clearable = true
            )
        )
    }

    // 账必须平：磁盘总数 - 已归类 = 账外。
    // 这个减法不是「算不准的遮羞布」，而是**清单完整性的自动检查**：
    // 漏了一个目录，这里就会显出一个大数字。
    val total = apkBytes + nativeBytes + sizeOf(files) + sizeOf(cache)
    val other = reconcile(total, items)

    return StorageAudit(
        items = items,
        apkBytes = apkBytes,
        nativeBytes = nativeBytes,
        totalBytes = total,
        otherBytes = other
    )
}

fun clearStorageTemporary(context: Context): Long {
    val app = context.applicationContext
    var freed = 0L

    ClearPolicy.targets.forEach { target ->
        val dir = File(ClearPolicy.baseDir(target, app.cacheDir, app.filesDir), target.name)
        if (!dir.isDirectory) return@forEach
        dir.listFiles()?.forEach inner@{ file ->
            if (!ClearPolicy.shouldDelete(target, file)) return@inner
            freed += if (file.isDirectory) sizeOf(file) else file.length()
            file.deleteRecursively()
        }
    }

    return freed
}
