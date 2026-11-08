package com.beichen.polaris.core

import com.beichen.polaris.core.io.AtomicJsonFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 功能五（P1-7）：提示词库存储（形态学 [com.beichen.polaris.memory.MemoryStore]）。
 *
 * 与 MemoryStore 同款持久化纪律：`prompts.json` 放 App filesDir，tmp + rename
 * 原子写 + .bak 备份；`Json { ignoreUnknownKeys = true; prettyPrint = true }`；
 * 读写全部 synchronized + runCatching——提示词库坏了最多是「没有快捷指令」，
 * 不能拖垮主流程。
 *
 * 首次启动（文件不存在）种入 [BUILTIN_PROMPTS]：内置提示词随包走，
 * 用户删除自定义项不影响内置；升级加新内置时只补种不覆盖用户数据
 * （[mergeWithBuiltins]：按 id 合并，用户改过标题的内置项——理论上不会发生，
 * 内置不可改——以文件为准）。
 */
class PromptStore(file: File) {

    private val file: File = file
    private val bakFile = File(file.parentFile, file.name + ".bak")
    private val tmpFile = File(file.parentFile, file.name + ".tmp")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val items = mutableListOf<PromptItem>()

    init {
        runCatching { file.parentFile?.mkdirs() }
        load()
    }

    /** 全部提示词：内置在前（包内顺序），自定义按写入序在后（列表渲染直接消费）。 */
    fun list(): List<PromptItem> = synchronized(lock) { items.toList() }

    /** 新建或更新（内置项会被 [upsertPrompt] 拒收，返回原列表）。 */
    fun save(draft: PromptItem): PromptUpsertResult {
        val result = upsertPrompt(snapshotForPure(), draft)
        if (result is PromptUpsertResult.Ok) {
            synchronized(lock) {
                items.clear()
                items.addAll(result.items)
            }
            persist()
        }
        return result
    }

    /** 删除（内置受保护）。 */
    fun delete(id: String): PromptDeleteResult {
        val result = deletePrompt(snapshotForPure(), id)
        if (result is PromptDeleteResult.Ok) {
            synchronized(lock) {
                items.clear()
                items.addAll(result.items)
            }
            persist()
        }
        return result
    }

    /** 「另存为自定义」：复制任意项为新自定义项并落盘，返回新 id。 */
    fun duplicateAsCustom(id: String): String? {
        val newId = newPromptId(System.currentTimeMillis(), UUID.randomUUID().hashCode())
        val entry = duplicateAsCustom(snapshotForPure(), id, newId) ?: return null
        synchronized(lock) { items.add(entry) }
        persist()
        return newId
    }

    /**
     * 备份恢复入口（hotfix28）：整包替换为备份里的提示词列表并落盘。
     *
     * 与 [MemoryStore.restoreAll] 同纪律：走公开 API、过 [mergeWithBuiltins] 净化
     * （备份件可能来自旧版本、缺新内置——补种；用户改过的 id 以备份为准）。
     * 恢复后调用方负责刷新 UI StateFlow。
     */
    fun restoreAll(restored: List<PromptItem>) {
        val merged = mergeWithBuiltins(restored)
        synchronized(lock) {
            items.clear()
            items.addAll(merged)
        }
        persist()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /** 纯函数层的输入快照（锁内拷贝，纯函数本身无锁概念）。 */
    private fun snapshotForPure(): List<PromptItem> = synchronized(lock) { items.toList() }

    /**
     * 读盘：优先主文件，损坏回退 .bak；两者都不可用或为空 → 种内置。
     * 读入一律过 [sanitizePrompts]，并与 [BUILTIN_PROMPTS] 合并补种
     * （老用户文件里内置被手动删过——早期版本没有 builtin 保护——也补回来）。
     */
    private fun load() {
        val parsed = parseOrNull(readTextOrEmpty(file)) ?: parseOrNull(readTextOrEmpty(bakFile))
        val merged = mergeWithBuiltins(parsed ?: emptyList())
        synchronized(lock) {
            items.clear()
            items.addAll(merged)
        }
        // 首次启动或补种了内置：立即落盘，免得下次读还是旧态
        if (parsed == null || parsed.size != merged.size) persist()
    }

    /**
     * 与内置合并（纯逻辑，[load] 内联测试不了就靠这里的不变量）：
     * 文件里已有的 id 以文件为准（用户数据优先），内置里有而文件缺的补在**最前**。
     */
    internal fun mergeWithBuiltins(stored: List<PromptItem>): List<PromptItem> {
        val cleaned = sanitizePrompts(stored)
        val have = cleaned.map { it.id }.toHashSet()
        val missing = BUILTIN_PROMPTS.filter { it.id !in have }
        return missing + cleaned
    }

    /** 原子写：完整快照 → .tmp；上一版备份为 .bak；rename 替换主文件。 */
    private fun persist() {
        val payload = runCatching { json.encodeToString(snapshotForPure()) }.getOrNull() ?: return
        AtomicJsonFile.write(file, payload)
    }

    private fun readTextOrEmpty(f: File): String =
        runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")

    private fun parseOrNull(text: String): List<PromptItem>? =
        if (text.isBlank()) null
        else runCatching { json.decodeFromString<List<PromptItem>>(text) }.getOrNull()
}
