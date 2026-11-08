package com.beichen.polaris.skill

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 导入技能仓库（L7「技能导入与安全降权」，净室原创）。
 *
 * 职责：把第三方技能（从 URL 下载 zip / 从本机选 zip / 内置示例）落盘到
 * [Context.getFilesDir]/skills/imported/<id>/，并强制「降权」为 imported 来源，
 * 使其落入 L5 安全内核的收紧策略（禁脚本 + 能力上限 PRIVILEGED）。
 *
 * 关键安全约束：
 *  - **强制降权 + 授权外置**：导入即无条件把磁盘 skill.json 的 trust 写成 imported；
 *    zip 自带的 trust 声明（如 "packaged"）一律不采信。运行时信任等级**唯一**由本地
 *    SharedPreferences 授权状态（[authorizedIds]）决定，重新导入/卸载都会撤销授权（fail-safe）。
 *  - **id 白名单**：技能 id 必须匹配 [A-Za-z0-9._-]+，且其目录 canonicalPath 必须落在
 *    imported 根目录之下，防越界写。
 *  - **防 Zip Slip**：解压前校验每个 entry 名称不含 ".."，且解压目标文件的 canonicalPath 必须
 *    落在目标目录 canonicalPath + 分隔符之下，否则抛异常。
 *  - **防 zip bomb**：条目数与累计解压字节设上限，超限抛 SecurityException。
 *  - **失败回滚**：installZip 任何异常都用 runCatching 包裹，失败时删除可能创建的半残目录。
 */
class ImportedSkillRepository(private val context: Context) {

    /** 导入技能根目录：filesDir/skills/imported。 */
    private val baseDir: File
        get() = File(context.filesDir, "skills/imported")

    /** 解析用（容忍未知字段，兼容各版本 skill.json）。 */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 脚本授权状态持久化（**不写进 skill.json**）。
     * 只有本仓库能读写，第三方 zip 无法通过自带 manifest 篡改授权状态。
     */
    private val prefs by lazy {
        context.getSharedPreferences("imported_skill_auth", Context.MODE_PRIVATE)
    }

    /** 已授权执行脚本的导入技能 id 集合（来自 SharedPreferences，只读副本）。 */
    private fun authorizedIds(): Set<String> =
        prefs.getStringSet("authorized", emptySet<String>()) ?: emptySet<String>()

    /**
     * 写入/撤销某技能的脚本授权。
     * 注意：必须构造**新的** Set 实例再 put，不能直接修改 [getStringSet] 返回的对象。
     */
    private fun setAuthorized(id: String, allow: Boolean) {
        val s = authorizedIds().toMutableSet()
        if (allow) s.add(id) else s.remove(id)
        prefs.edit().putStringSet("authorized", s).apply()
    }

    /**
     * 扫描 filesDir/skills/imported/<id>/skill.json，反序列化为技能清单列表。
     *
     * **无条件覆盖 trust**：以目录名为 id，命中 [authorizedIds] 才是 packaged，否则一律 imported。
     * 这是防绕过的关键——zip 自带 skill.json 里声明的 trust 完全不参与判定。
     *
     * @param app 当前 Context（与构造入参同源，保持与 SkillLoader 一致的调用形态）。
     */
    fun loadImported(app: Context): List<SkillManifest> {
        val out = mutableListOf<SkillManifest>()
        val root = File(app.filesDir, "skills/imported")
        val ids = runCatching { root.list()?.toList() }.getOrNull() ?: return out
        val authorized = authorizedIds()
        for (id in ids) {
            val file = File(root, "$id/skill.json")
            if (!file.isFile) continue
            runCatching {
                val manifest = json.decodeFromString<SkillManifest>(file.readText())
                // 目录名即 id（与卸载/授权操作用的 id 一致）；trust 只认本地授权状态
                out += manifest.copy(trust = if (id in authorized) "packaged" else "imported")
            }
        }
        return out
    }

    /**
     * 解压 zip 到 filesDir/skills/imported/<id>/，并强制降权。
     * @return 成功返回技能 id（Result 包装），失败返回异常（并回滚半残目录）。
     */
    fun installZip(zipBytes: ByteArray): Result<String> {
        var destDir: File? = null
        val result = runCatching {
            // 第一遍：仅读取 zip 内 skill.json 拿 id（不落盘）
            val skillJsonText = readZipEntry(zipBytes) { name ->
                name == "skill.json" || name.endsWith("/skill.json")
            } ?: throw IllegalArgumentException("zip 内未找到 skill.json")
            val id = json.decodeFromString<SkillManifest>(skillJsonText).id
            if (id.isBlank()) throw IllegalArgumentException("skill.json 的 id 为空")

            // id 白名单校验 + 目录越界校验：只允许 [A-Za-z0-9._-]，且必须落在 imported 根目录下
            require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "非法技能 id：$id" }
            val target = File(baseDir, id)
            require(target.canonicalPath.startsWith(baseDir.canonicalPath + File.separator)) {
                "非法技能 id：$id"
            }

            destDir = target
            // 幂等：已有则先清空，支持重新导入
            if (target.exists()) target.deleteRecursively()

            // 第二遍：整包解压（带 Zip Slip / zip bomb 防护）
            extractZipAll(zipBytes, target)

            // 反序列化校验 name 非空
            val manifest = json.decodeFromString<SkillManifest>(File(target, "skill.json").readText())
            if (manifest.name.isBlank()) throw IllegalArgumentException("skill.json 的 name 为空")

            // 强制降权（无条件）：磁盘 trust 恒为 imported，zip 自带的 trust 一律不采信。
            // 运行时信任等级只由 SharedPreferences 授权状态决定。
            writeTrust(target, "imported")
            // fail-safe：重新导入视为新来源，撤销既有授权，需用户重新显式授权。
            setAuthorized(id, false)
            id
        }
        // 失败回滚：删除可能创建的半残目录
        if (result.isFailure) {
            destDir?.let { if (it.exists()) it.deleteRecursively() }
        }
        return result
    }

    /** 卸载指定技能：删除 filesDir/skills/imported/<id> 整目录，并撤销其授权。 */
    fun uninstall(id: String): Boolean {
        val dir = File(baseDir, id)
        if (!dir.exists()) return false
        val ok = dir.deleteRecursively()
        // 卸载即撤销授权，避免同 id 再次导入时继承旧的授权状态（fail-safe）
        if (ok) setAuthorized(id, false)
        return ok
    }

    /**
     * 设置某技能的脚本授权（仅写 SharedPreferences，**不改 skill.json**）。
     * @param allow true 授权（运行时 trust=packaged），false 撤销（运行时 trust=imported）。
     * @return 是否成功（技能目录不存在则 false）。
     */
    fun setScriptAuthorization(id: String, allow: Boolean): Boolean {
        if (!File(baseDir, id).exists()) return false
        setAuthorized(id, allow)
        return true
    }

    /** 把 skill.json 的 trust 字段重写为 [trust]（prettyPrint 写回）。 */
    private fun writeTrust(dir: File, trust: String) {
        val file = File(dir, "skill.json")
        val obj = json.parseToJsonElement(file.readText()) as JsonObject
        val newObj = buildJsonObject {
            for ((k, v) in obj) {
                if (k == "trust") put(k, JsonPrimitive(trust)) else put(k, v)
            }
        }
        file.writeText(prettyJson.encodeToString(JsonObject.serializer(), newObj))
    }

    /**
     * 在 zip 字节流里找到首个匹配 predicate 的条目并读取其文本（不落盘）。
     *
     * W-48：单条读取加 256KB 上限。`readText()` 是无上限的——一个把 `skill.json`
     * 撑到 8MB 的包能在解压前就把内存吃掉；skill.json 正常只有几 KB，256KB 极宽松。
     */
    private fun readZipEntry(zipBytes: ByteArray, predicate: (String) -> Boolean): String? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && predicate(entry.name)) {
                    if (entry.size > MAX_META_BYTES) return null
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        if (out.size() + n > MAX_META_BYTES) return null
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * 只读探测 zip 内声明的技能 id（不落盘、不安装）。
     *
     * W-48：市场安装前用它校验「市场条目 id == 包内 id」。没有这道校验时，一个把
     * `skill.json` 写成别的 id 的包会**覆盖同名的已装技能**，而市场列表还以为装的是自己那条。
     *
     * @return 声明的 id；找不到 skill.json / 解析失败 / 超限时返回 null（调用方按失败处理）
     */
    fun peekSkillId(zipBytes: ByteArray): String? = runCatching {
        readZipEntry(zipBytes) { it == "skill.json" || it.endsWith("/skill.json") }
            ?.let { json.decodeFromString<SkillManifest>(it).id }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 把 zip 整体解压到 destDir，带 Zip Slip 与 zip bomb 防护。 */
    private fun extractZipAll(zipBytes: ByteArray, destDir: File) {
        // 补上分隔符，避免 /a/b 与 /a/bc 的前缀误判
        val canonicalDest = destDir.canonicalPath + File.separator
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                // 0) zip bomb 防护：条目数 / 累计解压字节上限
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw SecurityException("zip 条目数超过上限 $MAX_ZIP_ENTRIES，疑似 zip bomb")
                }
                if (!entry.isDirectory) {
                    // 1) 名称含 ".." 直接拒绝（路径穿越特征）
                    if (entry.name.contains("..")) {
                        throw SecurityException("检测到 Zip Slip 路径穿越：${entry.name}")
                    }
                    // 1.5) 声明体积预拒：能提前挡掉夸张条目（声明值不可信，后面仍会边读边计数）
                    if (entry.size > MAX_ZIP_BYTES) {
                        throw SecurityException("解压条目过大（声明 ${entry.size} 字节），疑似 zip bomb")
                    }
                    val outFile = File(destDir, entry.name)
                    // 2) canonicalPath 必须落在目标目录之下（防 ../ 越界写）
                    if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                        throw SecurityException("检测到 Zip Slip 越界写入：${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    // 3) A 批：**边写边计数**，超限立即中止。
                    // 旧实现先 copyTo 写完再累加判断——一个 2GB 的单条目会先把磁盘写满，
                    // 检查再准也来不及（与 W-47 备份解包的修复保持同构）。
                    outFile.outputStream().use { os ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            totalBytes += n
                            if (totalBytes > MAX_ZIP_BYTES) {
                                throw SecurityException(
                                    "解压后体积超过上限 ${MAX_ZIP_BYTES / 1024 / 1024}MB，疑似 zip bomb"
                                )
                            }
                            os.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        /** zip bomb 防护：单个技能包的条目数上限。 */
        private const val MAX_ZIP_ENTRIES = 500

        /** zip bomb 防护：单个技能包解压后累计字节上限（8MB）。 */
        private const val MAX_ZIP_BYTES = 8L * 1024 * 1024

        /** 元数据（skill.json）单条读取上限：正常几 KB，256KB 已极宽松。 */
        private const val MAX_META_BYTES = 256 * 1024

        /**
         * 写回用（prettyPrint，便于人工审阅）。
         * 定义在 companion 作用域，使静态的 [demoZipBytes] 与实例方法
         * [writeTrust] / [setScriptAuthorization] 可共用同一实例。
         */
        private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /**
         * 生成一个示例技能的 zip 字节（纯提示词形态、无 entry，便于无网络演示）。
         * 含 skill.json（id=com.example.demo.greet、name=示例·打招呼、trust=imported）与 SKILL.md。
         */
        fun demoZipBytes(): ByteArray {
            val skillJson = buildJsonObject {
                put("id", JsonPrimitive("com.example.demo.greet"))
                put("name", JsonPrimitive("示例·打招呼"))
                put("version", JsonPrimitive("1.0.0"))
                put("description", JsonPrimitive("示例技能：用提示词形态向用户问好，用于演示「导入技能」功能。"))
                put("prompt", JsonPrimitive("当用户想打招呼或问好时，用中文友好地问候对方，并询问需要什么帮助。"))
                put("tools", buildJsonArray { add(JsonPrimitive("read_file")) })
                put("trust", JsonPrimitive("imported"))
            }
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("skill.json"))
                zos.write(prettyJson.encodeToString(JsonObject.serializer(), skillJson).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("SKILL.md"))
                zos.write("这是一个示例技能，用于演示北辰的「导入技能」功能。".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            return baos.toByteArray()
        }
    }
}
