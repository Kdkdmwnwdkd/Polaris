package com.beichen.polaris.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.ui.container.ContainerInstallController
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.container.ContainerCommandRunner
import com.beichen.polaris.core.container.ContainerSession
import com.beichen.polaris.core.container.ProotRunner
import com.beichen.polaris.core.container.RootfsManager
import com.beichen.polaris.core.container.ToolchainInstaller
import com.beichen.polaris.runtime.lease.ContainerLeases
import com.beichen.polaris.runtime.lease.LeaseHandle
import com.beichen.polaris.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 容器页（调研文档「第 5 步：容器管理」直译）。
 *
 * 结构：发行版卡片列表（从 assets/containers/ 下探测 *.tar.gz）→ 点选解压 rootfs →
 * 进入 proot 启动的伪终端（复用 [TerminalContent]）。proot 二进制缺失或 ptrace 受限时
 * 明确降级，不静默崩溃。
 */
@Composable
fun ContainerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var distros by remember { mutableStateOf<List<RootfsManager.Distro>>(emptyList()) }
    var extracted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<RootfsManager.Distro?>(null) }
    /** B 批：运行时安装后的刷新计数——探测结果按 (id, tick) 重算。 */
    var toolchainTick by remember { mutableStateOf(0) }
    /**
     * C 批：用户为每种语言填写的目标版本号。
     *
     * key 用 "<distroId>:<langId>"，因为不同发行版的可用版本不一样，
     * 分开记才不会串。
     */
    val versionInputs = remember { mutableStateMapOf<String, String>() }

    /**
     * 安装任务改由应用级 [ContainerInstallController] 持有（W-62）。
     *
     * 原来这里是 `var installing by remember` + `rememberCoroutineScope()`：
     * 切页/进终端会销毁组合 → 协程被取消、状态归零，但 proot 还在跑（阻塞调用不吃取消）。
     * 结果就是真机 13:56 那一幕：按钮又变回「安装 Java」可点，点了被 EXIT_BUSY 拒，
     * 20 秒内连拒 6 次，用户以为按钮坏了。
     *
     * 现在页面只是**观察者**：页面重建后直接恢复成「正在安装 Java… 120s」。
     */
    val running by ContainerInstallController.running.collectAsStateWithLifecycle()
    val installReport by ContainerInstallController.report.collectAsStateWithLifecycle()
    val finishedCount by ContainerInstallController.finished.collectAsStateWithLifecycle()

    /**
     * 已用时的刷新节拍。
     *
     * 直接按 `startedAtMs` 算差，而不是「每秒 +1」——页面切走再回来时后者会从 0 重新数，
     * 用户看到「刚开始装」的假象，又一次选择重试。
     */
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running?.startedAtMs) {
        if (running == null) {
            nowMs = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    // 每完成一次安装就重算探测结果（已装/未装）
    LaunchedEffect(finishedCount) {
        if (finishedCount > 0) toolchainTick++
    }

    // proot 三件套是**首次使用时**才从 assets 提取的（ensureBinary）。
    // 原来这里只判断 isAvailable，新装应用还没提取过 → 永远显示「需要 proot」，
    // 用户根本没有触发提取的入口，等于容器功能对新用户完全不可用。
    // 现在进页面就后台提取一次（幂等，已就绪直接返回）。
    var prootReady by remember { mutableStateOf(ProotRunner.isAvailable(context)) }
    var prootError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!prootReady) {
            val bin = withContext(Dispatchers.IO) { ProotRunner.ensureBinary(context) }
            prootReady = bin != null
            prootError = bin == null
        }
    }

    LaunchedEffect(Unit) {
        distros = RootfsManager.listAvailable(context)
        extracted = distros.filter { RootfsManager.isExtracted(context, it.id) }
            .map { it.id }.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = AppDest.CONTAINER.title,
            actionIcon = if (selected != null) Icons.AutoMirrored.Outlined.ArrowBack else null,
            actionDescription = "返回发行版列表",
            onAction = { selected = null }
        )

        if (!prootReady) {
            AkEmptyState(
                icon = Icons.Outlined.ViewInAr,
                title = if (prootError) "proot 运行时不可用" else "正在准备容器运行时…",
                description = if (prootError) {
                    "没能从安装包里取出 proot（当前设备 ABI：${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}）。" +
                        "正式包只带 arm64-v8a；在 x86_64 模拟器上做容器验证，请用 " +
                        "`-Px86Rootfs=true` 构建调试包（会额外带 x86_64 的 proot 与 rootfs）。"
                } else {
                    "首次进入需要把 proot 从安装包释放到应用私有目录，通常一两秒。"
                }
            )
            return@Column
        }

        if (selected == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberPersistentScrollState())
                    .padding(AgentSpacing.space5)
            ) {
                Text(
                    text = "选择发行版启动容器",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AgentSpacing.space4))
                if (distros.isEmpty()) {
                    AkEmptyState(
                        icon = Icons.Outlined.ViewInAr,
                        title = "无可用 rootfs",
                        description = "请在 app/src/main/assets/containers/ 下放置 <发行版>.tar.gz（如 alpine.tar.gz）。"
                    )
                } else {
                    distros.forEach { d ->
                        DistroCard(
                            distro = d,
                            ready = extracted.contains(d.id),
                            isBusy = busy == d.id,
                            onClick = {
                                if (extracted.contains(d.id)) {
                                    // 真机 13:56：装 Java 的 proot 在跑，用户进终端起了第二个 proot，
                                    // 系统 LMK 直接把 pty 杀掉（终端 2 秒后 code=137，用户只看到黑屏）。
                                    // 这里挡住并说清原因，而不是让他撞上去。
                                    val busyMs = ContainerCommandRunner.busyFor(d.id)
                                    if (busyMs != null) {
                                        Toast.makeText(
                                            context,
                                            "正在安装运行时（已 ${busyMs / 1000}s）。现在进终端会起第二个 proot、" +
                                                "把正在装的那个顶掉——等它装完再进。",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        selected = d
                                    }
                                } else {
                                    busy = d.id
                                    scope.launch(Dispatchers.IO) {
                                        val ok = RootfsManager.ensureExtracted(context, d) != null
                                        withContext(Dispatchers.Main) {
                                            if (ok) extracted = extracted + d.id
                                            busy = null
                                            if (ok) selected = d
                                        }
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.height(AgentSpacing.space3))
                    }
                }

                Spacer(Modifier.height(AgentSpacing.space4))

                // B 批：容器运行时一键安装。
                // 容器默认只有 busybox 一套基础工具，node/python/java 都没有——
                // 这里按发行版探测现状，缺什么给一条可执行命令，点一下就在容器里装好。
                AkSectionCard(
                    title = "语言运行时",
                    icon = Icons.Outlined.DeveloperMode,
                    description = "容器默认不带开发语言运行时。检测缺失后一键安装（node / python / java）。"
                ) {
                    if (extracted.isEmpty()) {
                        Text(
                            "请先解压一个发行版。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        extracted.forEach { id ->
                            val detection = remember(id, toolchainTick) {
                                ToolchainInstaller.detect(RootfsManager.rootfsDir(context, id))
                            }
                            val missing = ToolchainInstaller.Lang.entries.filter { it !in detection.installed }
                            // 本发行版此刻正在装什么（跨页面存活，切走再回来仍在）。
                            // 必须定义在这里：下面「一键装齐」和「逐语言安装」是两个平级 if 分支，
                            // 定义在任一分支内，另一个分支都看不到。
                            val thisRunning = running?.takeIf { it.distroId == id }
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    id,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(AgentSpacing.space1))
                                Text(
                                    text = when {
                                        detection.pkgManager == null ->
                                            detection.reason ?: "该 rootfs 无包管理器，无法自动安装"
                                        detection.installed.isEmpty() -> "未检测到任何语言运行时"
                                        else -> "已安装：" + detection.installed.joinToString("、") { it.displayName }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (detection.pkgManager != null && missing.isNotEmpty()) {
                                    // 命令先算出来，UI 与执行共用同一份——避免文案与实际执行不一致
                                    val installCmd = ToolchainInstaller.installMissingCommand(detection)
                                    val allLabel = missing.joinToString("、") { it.displayName }
                                    Spacer(Modifier.height(AgentSpacing.space2))
                                    AkTextButton(
                                        text = if (thisRunning != null) {
                                            "正在安装 ${thisRunning.label}… " +
                                                "${thisRunning.elapsedSeconds(nowMs)}s"
                                        } else {
                                            "安装 $allLabel"
                                        },
                                        enabled = installCmd != null && running == null,
                                        onClick = {
                                            val cmd = installCmd
                                            if (cmd == null) {
                                                Toast.makeText(context, "无法生成安装命令", Toast.LENGTH_SHORT).show()
                                                return@AkTextButton
                                            }
                                            if (!ContainerInstallController.start(context, id, allLabel, cmd, missing)) {
                                                Toast.makeText(context, "已有安装在执行，等它结束", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(AgentSpacing.space1))
                                    // 这段命令有 200 多字符（含社区源前奏），不截断会把卡片撑到
                                    // 五屏高（真机 15:05 截图），下面的按钮全被挤出视野。
                                    Text(
                                        text = "将在容器内执行：${installCmd ?: "（无法生成命令）"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // C 批：按语言单独指定版本安装。
                                //
                                // 「一键装齐」装的是各语言的**当前默认版**——但用户真正要的
                                // 往往是特定版本（项目 lockfile 卡在 node 18、代码用了
                                // python 3.11 的新语法）。所以这里逐语言给出版本输入框：
                                // 留空 = 用默认版；填了 = 装指定版本。
                                //
                                // 条件不再要求 `missing.isNotEmpty()`：三个运行时全装上之后，
                                // 这块整块消失，用户想「换 node 22」「JDK 装坏了要重装」时
                                // 一个入口都没有（真机就是这个状态：卡片只剩一行
                                // 「已安装：Node.js、Python 3、Java」）。现在始终显示，
                                // 已装的语言按钮变成「重装」。
                                if (detection.pkgManager != null) {
                                    Spacer(Modifier.height(AgentSpacing.space3))
                                    Text(
                                        text = if (missing.isEmpty()) "重装 / 更换版本" else "指定版本安装",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (missing.isEmpty()) {
                                            "三个运行时都已安装。留空版本号则按发行版默认版重装；填入版本号则装对应版本。"
                                        } else {
                                            "留空则安装默认版本；填入版本号则装对应版本。"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(AgentSpacing.space2))
                                    ToolchainInstaller.Lang.entries.forEach { lang ->
                                        val vKey = "$id:${lang.id}"
                                        val vInput = versionInputs[vKey].orEmpty()
                                        Column(Modifier.fillMaxWidth()) {
                                            AkTextField(
                                                value = vInput,
                                                onValueChange = { versionInputs[vKey] = it },
                                                label = "${lang.displayName} 版本",
                                                placeholder = "默认",
                                                machine = true
                                            )
                                            val pm = detection.pkgManager
                                            val spec = if (vInput.isBlank()) null
                                            else ToolchainInstaller.resolveVersion(lang, vInput, detection.distro)
                                            // 版本号非法时**明确报错**，不静默回落成默认版——
                                            // 用户要 node 18 却悄悄装了 node 20，比报错更糟。
                                            val unavailable = spec != null && pm != null && !spec.availableOn(pm)
                                            if (vInput.isNotBlank() && spec == null) {
                                                Spacer(Modifier.height(AgentSpacing.space1))
                                                Text(
                                                    text = "版本号非法：只接受数字与点（如 18 / 3.11）",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            } else if (unavailable) {
                                                // 官方源没有这个版本：说清原因，且不给可点的按钮。
                                                // 换成相近版本悄悄装上去，比装不上更难排查。
                                                Spacer(Modifier.height(AgentSpacing.space1))
                                                Text(
                                                    text = spec?.note?.takeIf { it.isNotBlank() }
                                                        ?: "该发行版装不到 ${lang.displayName} ${spec?.version ?: vInput}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            } else {
                                                // 跨模块 public 属性不能 smart cast：复用上面已取的 pm
                                                val cmd = pm?.let {
                                                    ToolchainInstaller.installCommand(lang, it, spec)
                                                }
                                                if (cmd != null) {
                                                    Spacer(Modifier.height(AgentSpacing.space1))
                                                    val langLabel = lang.displayName +
                                                        (spec?.let { " ${it.version}" } ?: "")
                                                    val langRunning = thisRunning
                                                        ?.takeIf { it.label == langLabel }
                                                    AkTextButton(
                                                        text = if (langRunning != null) {
                                                            "正在安装 $langLabel… " +
                                                                "${langRunning.elapsedSeconds(nowMs)}s"
                                                        } else if (lang in detection.installed) {
                                                            "重装 $langLabel"
                                                        } else {
                                                            "安装 $langLabel"
                                                        },
                                                        enabled = running == null,
                                                        onClick = {
                                                            if (!ContainerInstallController.start(
                                                                    context, id, langLabel, cmd, listOf(lang)
                                                                )
                                                            ) {
                                                                Toast.makeText(
                                                                    context, "已有安装在执行，等它结束",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    )
                                                    Spacer(Modifier.height(AgentSpacing.space1))
                                                    Text(
                                                        text = "将执行：$cmd",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 3,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    // 版本说明：Debian 官方源没有 node 18 这类分版本包，
                                                    // 用户得知道「可能装不上、或者装到的是默认版」。
                                                    spec?.note?.takeIf { it.isNotBlank() }?.let { note ->
                                                        Text(
                                                            text = note,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(AgentSpacing.space3))
                                        }
                                    }
                                }
                                // 安装结果常驻展示：Toast 一闪而过，而 apk/apt 的失败原因
                                // 几乎都在输出的最后几行——真机上的 exit 99 就是这样被截成半句
                                // 「ERROR: Unable to lock database: temporary error」，等于没给信息。
                                installReport?.takeIf { it.distroId == id }?.let { report ->
                                    Spacer(Modifier.height(AgentSpacing.space2))
                                    Text(
                                        text = report.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (report.effectiveOk) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                                    )
                                    // 卡片只放得下十来行，apk 的「1 error」上面几十行才是真因；
                                    // 告诉用户完整输出在哪，别再靠截图来回猜。
                                    if (!report.effectiveOk && !report.result.busy) {
                                        Spacer(Modifier.height(AgentSpacing.space1))
                                        Text(
                                            text = "完整输出已存到应用 files/logs/container-fail-*.log，可在「日志」页导出。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(AgentSpacing.space3))
                            }
                        }
                    }
                }
            }
        } else {
            val distro = selected!!
            var session by remember { mutableStateOf<TerminalSession?>(ContainerSessionHolder.peekSession()) }
            LaunchedEffect(distro) {
                session = ContainerSessionHolder.acquire(context, distro)
            }
            DisposableEffect(Unit) {
                onDispose { ContainerSessionHolder.release() }
            }
            TerminalContent(
                session = session,
                updateTick = ContainerSessionHolder.updateTick.value,
                exitCode = ContainerSessionHolder.exitCode.value,
                startError = ContainerSessionHolder.startError.value
            )
        }
    }
}

/** 发行版卡片：图标底座 + 名称 + 状态（已就绪 / 解压中 / 点击解压）+ 整卡点击。 */
@Composable
private fun DistroCard(
    distro: RootfsManager.Distro,
    ready: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    val ak = LocalAkColors
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(AgentSpacing.space5),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ak.surface1,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.ViewInAr,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(AgentSpacing.space4))
            Column(Modifier.weight(1f)) {
                Text(
                    text = distro.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AgentSpacing.space1))
                Text(
                    text = if (isBusy) "正在解压…"
                    else if (ready) "已就绪 · 点击进入"
                    else "点击解压 rootfs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 容器会话的进程级持有者（与 [TerminalSessionHolder] 同构，但创建的是 proot 容器会话）。
 * 引用计数 + 旋转窗口期延迟销毁；acquire 时负责确保 rootfs 已解压、proot 可用。
 */
private object ContainerSessionHolder {
    private const val TAG = "ContainerScreen"
    private const val ROTATION_GRACE_MS = 1000L

    val updateTick = mutableStateOf(0L)
    val exitCode = mutableStateOf<Int?>(null)
    val startError = mutableStateOf<String?>(null)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var session: TerminalSession? = null

    /**
     * 当前会话对应的发行版 id。
     *
     * 之前没有这个字段，`session?.let { return it }` 只要会话还在就直接返回——
     * 用户在 alpine 里按返回、1 秒宽限期内点进 debian，`pendingKill` 被取消，
     * 拿到的仍是 alpine 的 shell，界面标题却显示 debian。会话必须与发行版绑定。
     */
    private var sessionDistroId: String? = null

    /** 容器租约句柄（R8）：UI 持有期间 activeCount > 0，空闲回收不会动它。 */
    private var leaseHandle: LeaseHandle? = null

    private var refs = 0
    private var pendingKill: Runnable? = null

    fun peekSession(): TerminalSession? = session

    fun acquire(context: Context, distro: RootfsManager.Distro): TerminalSession? {
        pendingKill?.let { main.removeCallbacks(it) }
        pendingKill = null
        refs++
        // 切换发行版：旧会话不能复用（复用的后果是「标题 debian、shell 是 alpine」）
        if (session != null && sessionDistroId != distro.id) {
            AgentLogger.i(TAG, "切换发行版 ${sessionDistroId} → ${distro.id}，销毁旧容器会话")
            destroyNow()
        }
        session?.let { return it }

        startError.value = null
        exitCode.value = null

        if (RootfsManager.ensureExtracted(context, distro) == null) {
            startError.value = "rootfs 解压失败，请检查 assets/containers/${distro.assetName}"
            AgentLogger.e(TAG, "rootfs 解压失败：${distro.id}")
            refs--
            return null
        }
        val s = ContainerSession.create(context, distro)
        if (s == null) {
            startError.value = "无法创建容器会话（proot 不可用？）"
            AgentLogger.e(TAG, "ContainerSession.create 返回 null")
            refs--
            return null
        }
        s.onUpdate = { main.post { updateTick.value++ } }
        s.onExit = { code -> main.post { exitCode.value = code } }
        session = s
        sessionDistroId = distro.id
        val ok = runCatching { s.start() }.isSuccess
        if (!ok) {
            startError.value = "容器 shell 启动失败（proot 执行失败，可能是 ptrace 受限）"
            AgentLogger.e(TAG, "容器启动失败")
            s.destroy()
            session = null
            sessionDistroId = null
            refs--
            return null
        }
        // 会话真正跑起来之后才借租约：借了却拿不到会话，会让 activeCount 虚高，
        // 空闲回收永远等不到归零（这正是「泄漏检测只报不管」也要单独记一笔的原因）。
        leaseHandle = ContainerLeases.forDistro(
            distroId = distro.id,
            alive = { session?.isAlive == true },
            destroy = { destroyNow() }
        ).borrow()
        return s
    }

    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs > 0) return
        if (session == null) {
            closeLease()
            return
        }
        val kill = Runnable {
            destroyNow()
            pendingKill = null
        }
        pendingKill = kill
        main.postDelayed(kill, ROTATION_GRACE_MS)
    }

    /** 销毁会话并归还租约。幂等——空闲回收与 UI 退出都会走到这里。 */
    private fun destroyNow() {
        closeLease()
        session?.destroy()
        session = null
        sessionDistroId = null
    }

    private fun closeLease() {
        leaseHandle?.close()
        leaseHandle = null
    }
}
