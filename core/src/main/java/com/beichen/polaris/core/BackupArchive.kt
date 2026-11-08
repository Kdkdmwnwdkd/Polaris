package com.beichen.polaris.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 功能五（P1-4b）：备份 ZIP 的打包/解包（纯函数，零 Android 依赖，harness 直接断言）。
 *
 * 为什么自制 ZIP 而不是「整个 filesDir 打个包」：
 *  - 备份件要能跨版本恢复——内部按语义成员（manifest/conversations/memories/config）
 *    组织，恢复侧逐成员经公开 API 灌入，带迁移与净化；裸目录包只能整包替换，
 *    一个坏文件就能让 App 起不来；
 *  - manifest 先行：解包先读信封验版本与条数，不合格直接拒收（[read] 的契约），
 *    用户拿到「备份文件无法识别」而不是恢复了一半把数据搞丢。
 *
 * 防滥用：
 *  - 解包只认白名单 entry 名（[ENTRY_*]），其他名字（含 `../` 穿越名）一律忽略——
 *    本实现不落盘、只读内存，穿越无门，但白名单让「包里有奇怪东西」时行为确定；
 *  - 累计解压上限 [MAX_TOTAL_BYTES]：防 zip 炸弹把内存撑爆（64MB 对会话+记忆
 *     JSON 足够宽松，正常备份远小于此）。
 */
object BackupArchive {

    /** 当前备份格式版本。 */
    const val VERSION = 1

    internal const val ENTRY_MANIFEST = "manifest.json"
    internal const val ENTRY_CONVERSATIONS = "conversations.json"
    internal const val ENTRY_MEMORIES = "memories.json"
    internal const val ENTRY_CONFIG = "config.json"
    /** hotfix28：提示词库快照（老备份件没有该成员，恢复时跳过）。 */
    internal const val ENTRY_PROMPTS = "prompts.json"
    /** W-26：备用 Key 池快照（老备份件没有该成员，恢复时跳过）。 */
    internal const val ENTRY_KEYPOOL = "keypool.json"

    /** 解压累计上限（zip 炸弹防护）。 */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /**
     * W-47：**单条目**上限。原来只在整条 entry 读完（`readBytes()`）之后才累加总量，
     * 一个声明的 500MB 单条目会先被完整解压进内存——总量检查再准也来不及。
     * 取值依据：单成员最大的是 conversations.json——200 条/会话 × 多会话，含图片 data URL
     * 时可达十几 MB。取 24MB 既覆盖真实用量，又远低于常见 256MB 堆（留足解压峰值余量）。
     */
    private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** 解包结果：成功（含 manifest 与各成员 JSON 文本；config/prompts 可为 null）或拒收原因。 */
    sealed class ReadResult {
        data class Ok(
            val manifest: BackupManifest,
            val conversationsJson: String,
            val memoriesJson: String,
            val configJson: String?,
            /** hotfix28：提示词库快照（null = 老备份件没带，恢复时不动现有提示词）。 */
            val promptsJson: String? = null,
            /** W-26：备用 Key 池快照（null = 老备份件没带，恢复时不动现有 Key 池）。 */
            val keyPoolJson: String? = null
        ) : ReadResult()

        /** 拒收：不是 ZIP / 缺 manifest / manifest 解析失败 / 版本不认识 / 条数不一致。 */
        data class Invalid(val reason: String) : ReadResult()
    }

    /**
     * 打包。conversationsJson/memoriesJson 由调用方用 [Json] 编码快照得到
     * （保留 store 侧的序列化配置）；configJson/promptsJson 为 null 时跳过该成员。
     */
    fun create(
        manifest: BackupManifest,
        conversationsJson: String,
        memoriesJson: String,
        configJson: String?,
        promptsJson: String? = null,
        keyPoolJson: String? = null
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putText(ENTRY_MANIFEST, json.encodeToString(manifest))
            zip.putText(ENTRY_CONVERSATIONS, conversationsJson)
            zip.putText(ENTRY_MEMORIES, memoriesJson)
            if (configJson != null) zip.putText(ENTRY_CONFIG, configJson)
            if (promptsJson != null) zip.putText(ENTRY_PROMPTS, promptsJson)
            if (keyPoolJson != null) zip.putText(ENTRY_KEYPOOL, keyPoolJson)
        }
        return bos.toByteArray()
    }

    /**
     * 解包 + 信封校验。契约：
     *  ① 可解析为 ZIP 且含 manifest.json；② manifest 版本 ≤ [VERSION]；
     *  ③ 条数与 manifest 声明一致（conversations/memories 两个成员必需；
     *     hotfix28：hasPrompts 为 true 时 prompts 成员也必需且条数要相符）。
     * 任一不满足 → [ReadResult.Invalid]，调用方不得触碰生产数据。
     */
    fun read(bytes: ByteArray): ReadResult {
        val texts = HashMap<String, String>()
        var total = 0L
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name in ENTRY_WHITELIST) {
                        // 先看声明的 size（未声明为 -1），能提前拒掉夸张条目
                        if (entry.size > MAX_ENTRY_BYTES) {
                            return ReadResult.Invalid("备份成员「${entry.name}」过大")
                        }
                        val data = readEntryLimited(zip, MAX_ENTRY_BYTES)
                            ?: return ReadResult.Invalid("备份成员「${entry.name}」过大")
                        total += data.size
                        if (total > MAX_TOTAL_BYTES) {
                            return ReadResult.Invalid("备份内容超出大小上限")
                        }
                        texts[entry.name] = data.toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return ReadResult.Invalid("不是有效的 ZIP 备份文件")
        }
        val manifestText = texts[ENTRY_MANIFEST] ?: return ReadResult.Invalid("缺少备份清单 manifest.json")
        val manifest = runCatching { json.decodeFromString<BackupManifest>(manifestText) }.getOrNull()
            ?: return ReadResult.Invalid("备份清单无法解析")
        if (manifest.version > VERSION) {
            return ReadResult.Invalid("备份格式版本过新（v${manifest.version}），请升级 App 后重试")
        }
        val conversationsJson = texts[ENTRY_CONVERSATIONS] ?: return ReadResult.Invalid("缺少会话数据")
        val memoriesJson = texts[ENTRY_MEMORIES] ?: return ReadResult.Invalid("缺少记忆数据")
        val convCount = runCatching { json.parseToJsonElement(conversationsJson).jsonArrayCount() }.getOrNull()
        val memCount = runCatching { json.parseToJsonElement(memoriesJson).jsonArrayCount() }.getOrNull()
        if (convCount == null || memCount == null) {
            return ReadResult.Invalid("会话或记忆数据不是合法 JSON 数组")
        }
        if (convCount != manifest.conversationCount || memCount != manifest.memoryCount) {
            return ReadResult.Invalid("备份条目数与清单不符（清单 ${manifest.conversationCount}/${manifest.memoryCount}，实际 $convCount/$memCount）")
        }
        // hotfix28：声明带了提示词库就必须有该成员且条数相符；没声明（老备份件）→ 跳过不动
        val promptsJson = texts[ENTRY_PROMPTS]
        if (manifest.hasPrompts) {
            if (promptsJson == null) return ReadResult.Invalid("缺少提示词库数据")
            val promptCount = runCatching { json.parseToJsonElement(promptsJson).jsonArrayCount() }.getOrNull()
            if (promptCount == null) {
                return ReadResult.Invalid("提示词库数据不是合法 JSON 数组")
            }
            if (promptCount != manifest.promptCount) {
                return ReadResult.Invalid("提示词条数与清单不符（清单 ${manifest.promptCount}，实际 $promptCount）")
            }
        }
        return ReadResult.Ok(
            manifest, conversationsJson, memoriesJson, texts[ENTRY_CONFIG], promptsJson,
            texts[ENTRY_KEYPOOL]
        )
    }

    /**
     * 限流读一条 entry：边读边计数，超过 [limit] 立即返回 null（并停止读取）。
     *
     * 不用 `readBytes()` 的理由：它无上限地读到流结束，zip 炸弹正是靠「解压后极大、
     * 压缩后很小」绕过总量检查。这里把判断放在**读的过程中**，内存峰值不超过 limit。
     */
    private fun readEntryLimited(zip: ZipInputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            // 读异常直接上抛：由 read() 的外层 catch 统一报「不是有效的 ZIP 备份文件」。
            // 不能吞成「过大」——那会把「包坏了」误导成「包太大」，用户排查方向全错。
            val n = zip.read(buf)
            if (n <= 0) break
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private val ENTRY_WHITELIST = setOf(
        ENTRY_MANIFEST,
        ENTRY_CONVERSATIONS,
        ENTRY_MEMORIES,
        ENTRY_CONFIG,
        ENTRY_PROMPTS,
        ENTRY_KEYPOOL
    )

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArrayCount(): Int =
        (this as? kotlinx.serialization.json.JsonArray)?.size ?: -1
}
