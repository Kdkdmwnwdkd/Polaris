package com.beichen.polaris.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * 功能五（P1-4）：待发文件——文件名 + 已提取的正文文本。
 *
 * 文本在读取时就抽好（读文件是 IO 重活），发送时只是字符串拼接；
 * UI 只负责展示文件名与移除，不碰解析细节。
 *
 * 为什么从 `ChatUiTypes.kt` 搬到这里：本类是它的唯一 owner（[ComposerState.pendingFiles]），
 * 放在一起能让这个文件的编译闭包**只剩 kotlinx.coroutines**——变异门禁要能单独编它、
 * 单独跑断言。留在 ChatUiTypes 里会把 ApprovalGrant / FailureKind 整条链拖进来。
 * 同包声明，引用方一行 import 都不用改。
 */
data class PendingFile(val name: String, val text: String)

/**
 * 输入区（composer）的三份状态与它们**唯一的写入口**。
 *
 * ## 为什么要有这个类
 *
 * `input` / `pendingImages` / `pendingFiles` 原先直接在 [ChatViewModel] 里以
 * `MutableStateFlow` 暴露，于是「谁能写」变成了「谁拿到引用谁就能写」：
 * ChatScreen 有三处直接 `vm.input.value = …`，ViewModel 内部有六处。写点一散，
 * 任何一处忘同步（比如发送后忘了清附件）都不会被编译器发现。
 *
 * 收敛后：外部只能读 [input] / [pendingImages] / [pendingFiles]，
 * 写必须经过 [set] / [edit] / [clear] / [addImages] / [addFile] / …
 * 本类零 Android、零 Compose import，能在 JVM 上直接跑（scripts/qa 的变异门禁就编它）。
 *
 * ## 一个不能省的方法：[edit]
 *
 * 语音追加、@ 文件补全、提示词插入都是「读到当前值 → 变换 → 写回」。若拆成
 * 「读 [text] 再 [set]」，两次调用之间插进一次用户输入，后写者会覆盖先写者——
 * 用户看到的是「刚说的话没了」。这正是 W-31 的原始缺陷，当时不得已把 `input`
 * 暴露成 `MutableStateFlow` 来规避。现在 [edit] 用 `update`（CAS 循环）在**一个原子步**
 * 里完成读改写，契约层才能把 `input` 收回成只读的 [StateFlow]。
 */
internal class ComposerState(initialText: String = "") {

    private val _input = MutableStateFlow(initialText)

    /** 输入框文本。UI 只读；写必须走 [set] / [edit] / [clear]。 */
    val input: StateFlow<String> = _input.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<String>>(emptyList())

    /**
     * 功能四（P0-2）：等待发送的图片（data URL 列表）。
     *
     * 选图在 UI 线程拿 Uri，转 base64 是重活——放 IO 线程由 `attachImageFromUri` 处理，
     * 完成后统一落到这里，发送时一次性带走。UI 只读这个流渲染缩略图，不碰编码细节。
     */
    val pendingImages: StateFlow<List<String>> = _pendingImages.asStateFlow()

    private val _pendingFiles = MutableStateFlow<List<PendingFile>>(emptyList())

    /** 功能五（P1-4）：等待发送的文件（名 + 正文）。语义同 [pendingImages]。 */
    val pendingFiles: StateFlow<List<PendingFile>> = _pendingFiles.asStateFlow()

    /** 当前文本的同步读法（判定用）。等价于 `input.value`，但语义更明确。 */
    val text: String get() = _input.value

    /** 待发图片张数——上限判定用，避免把整张列表搬出来只为读个 size。 */
    val imageCount: Int get() = _pendingImages.value.size

    /** 整体替换。用户输入、草稿回填、定时任务代发都用它。 */
    fun set(text: String) {
        _input.value = text
    }

    /**
     * 原子读改写。追加类操作（语音 / @ 补全 / 提示词）必须走这里，理由见类注释。
     *
     * @param transform 拿到当前值、返回新值；**不要**在 transform 里做副作用
     *   （`update` 在竞争下可能重跑多次）。
     */
    fun edit(transform: (String) -> String) {
        _input.update(transform)
    }

    /** 清空文本（发送成功后）。 */
    fun clear() {
        _input.value = ""
    }

    /** 追加待发图片。空列表直接返回——无意义的 emit 会让输入区白重组一次。 */
    fun addImages(urls: List<String>) {
        if (urls.isEmpty()) return
        _pendingImages.update { it + urls }
    }

    fun removeImage(url: String) {
        _pendingImages.update { list -> list.filter { it != url } }
    }

    fun addFile(file: PendingFile) {
        _pendingFiles.update { it + file }
    }

    fun removeFile(name: String) {
        _pendingFiles.update { list -> list.filterNot { it.name == name } }
    }

    /** 一次 [takeAttachments] 的产物。 */
    data class Attachments(val images: List<String>, val files: List<PendingFile>)

    /**
     * 摘走待发附件：返回当前值并清空，两步原子完成。
     *
     * 为什么合成一个调用：原来是「读 `_pendingImages.value`」+「`= emptyList()`」两步。
     * 中间若因取消/异常提前返回，附件会留在输入区——用户看着图片还在，但消息已经发出了。
     * `getAndUpdate` 把「拿到旧值」与「写回空」压成一次 CAS，不存在这个窗口。
     */
    fun takeAttachments(): Attachments = Attachments(
        images = _pendingImages.getAndUpdate { emptyList() },
        files = _pendingFiles.getAndUpdate { emptyList() },
    )
}
