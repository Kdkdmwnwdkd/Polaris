package com.beichen.polaris.core.workspace

import android.content.Context
import java.io.File

/**
 * [FileBackupStore] 的 Android 侧构造入口。
 *
 * ## 为什么要单独一个文件
 * [FileBackupStore] 本身是**纯文件操作**（无 Android 依赖），可以直接被 JVM harness
 * 编译断言；而「工作区目录在哪」这件事依赖 [WorkspaceStore]——后者会触发老目录
 * 迁移，硬编码路径会绕过迁移，导致备份指向一个空目录。
 *
 * 把这层依赖拆到独立文件，纯逻辑部分就能脱离 android.jar 编译。
 */
object FileBackupStoreFactory {

    /** 设置项 key：备份保留条数（0 或负数表示不清理）。 */
    const val PREF_KEEP = "file_backup_keep"

    /** 默认保留条数（与 [FileBackupStore.DEFAULT_KEEP] 一致）。 */
    private const val DEFAULT_KEEP = FileBackupStore.DEFAULT_KEEP

    /**
     * 按约定路径构造：备份仓 `<filesDir>/file_backups`，工作区取 [WorkspaceStore.dir]。
     *
     * 保留条数从设置读（[PREF_KEEP]），读不到或不合法就用默认 50。
     * 传入 0 或负数 = 不清理（用户明确想全留，比如在做审计）。
     */
    fun default(context: Context): FileBackupStore =
        FileBackupStore(
            File(context.filesDir, FileBackupStore.DIR_NAME),
            WorkspaceStore.dir(context),
            { msg -> com.beichen.polaris.core.AgentLogger.w("FileBackupStore", msg) },
            keep = keepOf(context)
        )

    /** 读「保留条数」设置；未设置或非法值回落默认。 */
    fun keepOf(context: Context): Int {
        val raw = runCatching {
            context.getSharedPreferences("agentkit_settings", Context.MODE_PRIVATE)
                .getInt(PREF_KEEP, DEFAULT_KEEP)
        }.getOrDefault(DEFAULT_KEEP)
        // 负数没有意义（当成 0 处理），0 表示不清理
        return raw.coerceAtLeast(0)
    }
}
