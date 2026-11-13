package com.beichen.polaris.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.widget.Toast
import com.beichen.polaris.core.agent.ApprovalTier
import com.beichen.polaris.core.agent.SurvivalCheckService
import com.beichen.polaris.pet.PetState
import com.beichen.polaris.runtime.lease.LeaseRegistry
import com.beichen.polaris.runtime.lease.LeaseSnapshot
import com.beichen.polaris.ui.contract.SettingsScreenContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（UX v1 §1.8）：自动化、设备能力、各处入口的收纳页。
 *
 * 设置只放「低频但要能找到」的东西：自动化总开关、通知、其他一级页面的入口。
 *
 * W-67 起**外观不再占这里的位置**：主题三选、强调色、动态取色、背景图整体搬到
 * [AppearanceScreen]（「壁纸与颜色」子页），本页只留一行入口 + 当前状态摘要。
 * 原因很直接——原来那一组从模式 chip 一路排到浓度滑杆，占掉整整一屏，
 * 把真正的设置项全推到折叠线以下；而调外观是低频动作，找设置是每次都要做的事。
 *
 * 用户反馈侧边栏拥挤（15 个入口），参照 RikkaHub 极简风：抽屉只留 4+1，
 * 其余 10 个入口全部收进本页——路由不动，只搬家。
 *
 * @param themeMode 当前明暗模式（只用于「壁纸与颜色」这一行的摘要，本页不再改它）。
 * @param dynamicColor 动态取色开关（同上，只作摘要）。
 * @param onNavigate 跳转到其他一级目的地。
 */
@Composable
fun SettingsScreen(
    vm: SettingsScreenContract,
    themeMode: ThemeMode,
    dynamicColor: Boolean = false,
    onNavigate: (AppDest) -> Unit
) {
    val enabled by vm.automationEnabled.collectAsStateWithLifecycle()
    // 功能五（P1-10）：回答完成提醒（落盘开关，与主题偏好不同纪律——见 VM 注释）
    val completionNotify by vm.completionNotifyEnabled.collectAsStateWithLifecycle()
    // W-67：外观入口的摘要要知道有没有设背景图（有没有 / 什么色，是这一行的全部信息量）
    val backgroundUri by vm.chatBackgroundUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 13+ 通知运行时权限：开开关时按需申请，被拒则回滚 + Toast 说明
    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.setCompletionNotifyEnabled(true)
        } else {
            Toast.makeText(context, "未授予通知权限，无法开启完成提醒", Toast.LENGTH_SHORT).show()
        }
    }

    // —— 功能五（P1-4b）：备份/恢复。SAF 文档选择器（零存储权限，用户自选落点）——
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { vm.exportBackupTo(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.restoreBackupFrom(it) } }
    // 恢复是破坏性操作：先弹确认，用户点头才开文件选择器（双闸门）
    var confirmRestore by remember { mutableStateOf(false) }
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(backupState) {
        when (val s = backupState) {
            is BackupUiState.Done -> {
                Toast.makeText(ctx, s.message, Toast.LENGTH_SHORT).show()
                vm.consumeBackupState()
            }
            is BackupUiState.Failed -> {
                Toast.makeText(ctx, s.reason, Toast.LENGTH_LONG).show()
                vm.consumeBackupState()
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // 设置页无右上动作：本页所有操作都在内容区，顶栏不放可能误触的全局按钮（§6.5.4）
        PageTopBar(title = AppDest.SETTINGS.title, largeTitle = false)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .navigationBarsPadding()
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space1,
                    bottom = AgentSpacing.space8
                )
        ) {
            // v5：页面大标题。设置页的内容全是「一组一组的选择」，没有状态可报，
            // 副标题只说明一件事：改动即时生效。
            AkDisplayTitle(title = AppDest.SETTINGS.title, subtitle = "改动即时生效，无需保存")
            // W-64：整页唯一的视觉重心——「你现在用的是什么模型」。
            // 之前这页从第一行开始就是一串等重的白卡，眼睛没有落点；
            // 模型又是这页几乎所有设置的前提（没配模型，下面全都没意义）。
            ModelHeroCard(vm, onNavigate = onNavigate)

            // —— W-68：轻量任务模型紧跟 hero 卡 ——
            // 它是「当前模型」的下一个问题（主模型用它，跑标题用哪个），两件事挨着才连得上。
            val taskProfileId by vm.taskProfileId.collectAsStateWithLifecycle()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            AkGroup(
                title = "轻量任务",
                description = "标题生成等轻量任务用哪个模型，默认跟随主配置"
            ) {
                // v5：不加图标。这一组是「单选：轻量任务用哪个模型」，档案行本来就没有图标，
                // 只给「跟随主配置」配一个等于暗示它是另一种东西。单选靠 selected 表达。
                AkListItem(
                    icon = null,
                    title = "跟随主配置",
                    description = "与当前聊天模型相同（默认）",
                    selected = taskProfileId.isBlank(),
                    onClick = { vm.setTaskProfile("") }
                )
                profiles.forEach { profile ->
                    AkListItem(
                        icon = null,
                        title = profile.name,
                        description = profile.config.model.ifBlank { "未命名模型" },
                        selected = taskProfileId == profile.id,
                        onClick = { vm.setTaskProfile(profile.id) }
                    )
                }
            }

            // —— W-68：小组收拢成「通用」——
            // 之前外观、通知、任务执行各占一张卡，每张只有一行。卡片一多，页面读起来
            // 是「一串分隔条」而不是「几件事」；而这三项本来就是同一类东西——App 怎么表现、
            // 会不会打断我、Agent 怎么做活。合成一组后，设置页从 10 张卡降到 6 张。
            val accentLabel = AppearanceState.accentHex?.let { hex ->
                AccentPreset.entries
                    .firstOrNull { it.color(false).toHexString().equals(hex, ignoreCase = true) }
                    ?.label ?: hex
            } ?: "默认色"
            val modeLabel = when (themeMode) {
                ThemeMode.SYSTEM -> "跟随系统"
                ThemeMode.LIGHT -> "浅色"
                ThemeMode.DARK -> "深色"
            }
            val planMode by vm.planMode.collectAsStateWithLifecycle()
            AkGroup(
                title = "通用",
                description = "界面、通知与执行方式"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Palette,
                    title = "壁纸与颜色",
                    description = buildString {
                        append(modeLabel)
                        append(" · ")
                        append(accentLabel)
                        if (dynamicColor) append(" · 动态取色")
                        if (backgroundUri != null) append(" · 有背景图")
                    },
                    onClick = { onNavigate(AppDest.APPEARANCE) }
                )
                AkListItem(
                    icon = Icons.Outlined.Notifications,
                    title = "回答完成提醒",
                    description = if (completionNotify) {
                        "已开启：回答完成且 App 在后台时发通知"
                    } else {
                        "关闭：静默完成，不打断"
                    },
                    trailing = {
                        AkSwitch(
                            checked = completionNotify,
                            onCheckedChange = { want ->
                                if (!want) {
                                    // 关开关不需要任何权限，直接落盘
                                    vm.setCompletionNotifyEnabled(false)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    // Android 13+ 未授权：走系统申请，结果回写开关
                                    notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    vm.setCompletionNotifyEnabled(true)
                                }
                            }
                        )
                    }
                )
                AkListItem(
                    icon = Icons.Outlined.AccountTree,
                    title = "先计划后执行",
                    description = if (planMode) {
                        "已开启：3 步以上任务先弹计划卡片，批准后才动手"
                    } else {
                        "已关闭：直接执行；删除、装应用等破坏性操作仍会先确认"
                    },
                    trailing = {
                        AkSwitch(
                            checked = planMode,
                            onCheckedChange = { vm.setPlanMode(it) }
                        )
                    }
                )
                // —— W-RT3：审批档位 ————————————————
                //
                // 三档单选，样式沿用「推理参数 › 资源档位」那一组（RadioButton + 两行文字），
                // 不新造控件：用户已经在模型页见过这套交互，不必再学一遍。
                //
                // 为什么放在「先计划后执行」下面：两者都是「动手前问多严」的档位，
                // 一个管任务级（要不要先出方案），一个管动作级（这一步要不要确认）。
                val approvalTier by vm.approvalTier.collectAsStateWithLifecycle()
                val tierOptions = listOf(
                    ApprovalTier.STRICT to ApprovalTier.descriptionOf(ApprovalTier.STRICT),
                    ApprovalTier.STANDARD to ApprovalTier.descriptionOf(ApprovalTier.STANDARD),
                    ApprovalTier.AUTO to ApprovalTier.descriptionOf(ApprovalTier.AUTO),
                )
                val tierTitle = mapOf(
                    ApprovalTier.STRICT to "逐步确认",
                    ApprovalTier.STANDARD to "常规",
                    ApprovalTier.AUTO to "全自动",
                )
                Column(Modifier.padding(vertical = AgentSpacing.space2)) {
                    tierOptions.forEachIndexed { index, (mode, hint) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.setApprovalTier(mode) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = approvalTier == mode,
                                onClick = { vm.setApprovalTier(mode) }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "危险操作审批：" + (tierTitle[mode] ?: mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index != tierOptions.lastIndex) {
                            Spacer(Modifier.height(AgentSpacing.space1))
                        }
                    }
                }
            }

            // —— 桌面助手（桌宠一期）——
            //
            // 开关直接读写 PetState，与 AppearanceState 同纪律：它是进程级单例，
            // 设置页只是观察者。不走 ViewModel 的理由很实在——桌宠活在 Service 里，
            // 比任何 ViewModel 都活得久，状态寄在 ViewModel 上会出现
            // 「服务在跑、开关却显示关」的错位，用户点一下「开启」反而把已开的重启一遍。
            val petEnabled by PetState.enabled.collectAsStateWithLifecycle()
            val petRunning by PetState.running.collectAsStateWithLifecycle()
            var petHasOverlay by remember { mutableStateOf(PetState.canDrawOverlays(context)) }
            var petHasMic by remember { mutableStateOf(hasRecordAudioPermission(context)) }
            var awaitingOverlayGrant by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { PetState.sync(context) }

            // 桌面助手真正起来的路径只有这一条：两个权限都问过 → 起服务。
            // 抽成 lambda 是因为它要从两个地方进入（主开关直接点、麦克风申请回调里）。
            val startPet: () -> Unit = {
                if (PetState.canDrawOverlays(context)) {
                    PetState.start(context)
                } else {
                    awaitingOverlayGrant = true
                    PetState.openOverlaySettings(context)
                }
            }

            /**
             * 麦克风权限。
             *
             * 二期语音的前提，但**不是桌宠的前提**——拒了照样能开，只是气泡里少一个
             * 按住说话的按钮。所以回调里无论授予与否都继续把桌宠开起来，
             * 而不是像通知权限那样「拒了就回滚开关」。
             */
            val micPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                petHasMic = granted || hasRecordAudioPermission(context)
                if (!granted) {
                    Toast.makeText(
                        context,
                        "没有麦克风权限，桌面助手只能用文字对话",
                        Toast.LENGTH_LONG
                    ).show()
                }
                startPet()
            }

            // 从系统「显示在其他应用上层」页返回：权限是异步写 AppOps 的，
            // 必须在 onResume 复查，不能信跳转前的判断。
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                petHasOverlay = PetState.canDrawOverlays(context)
                petHasMic = hasRecordAudioPermission(context)
                if (awaitingOverlayGrant && petHasOverlay) {
                    awaitingOverlayGrant = false
                    PetState.start(context)
                }
            }
            /**
             * 加磁贴只能**手动**，这里只负责把步骤说清楚。
             *
             * 本来想用 `requestAddTileService`（Android 13 起，会弹系统确认框），
             * 编译期就否掉了：它是 `TileService` 的**实例方法**，不是静态方法
             * （Java 签名 `public void requestAddTileService(...)`，要靠实例的
             * Context 决定"加到哪个用户"）。设置页拿不到 TileService 实例，
             * 自己 bind 又需要系统级权限 `BIND_QUICK_SETTINGS_TILE`，走不通。
             *
             * 于是退回到「告诉用户点哪儿」：多一步，但不撒谎、不引额外依赖。
             */
            val showTileGuide: () -> Unit = {
                Toast.makeText(
                    context,
                    "下拉通知栏 → 点右上角「编辑」（铅笔图标）→ 找到「北辰」，拖到上面两排",
                    Toast.LENGTH_LONG
                ).show()
            }

            AkGroup(
                title = "桌面助手",
                description = "常驻桌面角落的小浮球，点开问一句，答案会念出来"
            ) {
                AkListItem(
                    icon = Icons.Outlined.SmartToy,
                    title = "显示桌面助手",
                    description = when {
                        petEnabled && petRunning -> "已开启：退到桌面也还在，按住它说话"
                        petEnabled -> "已开启，但当前没有运行（可能被系统清理了）"
                        else -> "关闭：需要时从对话页正常使用"
                    },
                    trailing = {
                        AkSwitch(
                            checked = petEnabled,
                            onCheckedChange = { want ->
                                when {
                                    !want -> PetState.stop(context)
                                    !petHasMic -> micPermissionLauncher
                                        .launch(Manifest.permission.RECORD_AUDIO)
                                    else -> startPet()
                                }
                            }
                        )
                    }
                )
                if (!petHasOverlay) {
                    AkListItem(
                        icon = null,
                        title = "需要「显示在其他应用上层」权限",
                        description = "点这里去系统设置里打开；回来后会自动生效",
                        onClick = {
                            awaitingOverlayGrant = true
                            PetState.openOverlaySettings(context)
                        }
                    )
                } else if (petEnabled && !petRunning) {
                    AkListItem(
                        icon = null,
                        title = "重新启动悬浮窗",
                        description = "开关是开的，但服务没在跑",
                        onClick = { PetState.ensureRunning(context) }
                    )
                }
                // 唤醒词的替代入口：不常驻监听（省电），但给一个「下拉点一下」的快捷方式。
                // 放在权限判据之外——磁贴本身不需要悬浮窗权限也能加到面板上，
                // 点它时才去要权限（PetTileService 里那一步会给授权页）。
                AkListItem(
                    icon = Icons.Outlined.Bolt,
                    title = "加到快捷设置",
                    description = "下拉通知栏点「北辰」，不必回桌面找它",
                    onClick = showTileGuide
                )
                // 只在「已开启但没授权」时出现：没开桌宠的人不需要看到这一条，
                // 而开了却拒过权限的人需要一个补授权的入口（否则只能卸载重装）。
                if (petEnabled && !petHasMic) {
                    AkListItem(
                        icon = null,
                        title = "语音输入需要麦克风权限",
                        description = "点这里申请；不授权也能用文字对话",
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }
            }

            // —— W-103：工具范围 ——
            //
            // 「粘性激活」的设计是「被叫到就开、一直开着」，但原先只有「新建会话」能清空。
            // 用户在长会话里用完 shell，那 4 个工具（2676 字符 ≈ 1300 token）就跟着每轮
            // 请求一直发到会话结束——这正是这轮改造想省的钱，却没有开关可关。
            //
            // 这里只提供「关」，不提供「开」：开组是模型自己的判断（`__use_tools`），
            // 用户手动去开一个自己用不上的组，只会白付 token、还会占掉淘汰名额。
            val toolGroups by vm.toolGroupsUi.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.refreshToolGroups() }
            // 默认只列「现在开着的组」：15 个组全铺开要 900dp，会把下面所有设置推走，
            // 而用户来这一行的目的通常是「关掉某个正在花钱的组」，不是浏览全部。
            // 收起状态下误关一个组也能靠「展开」找回来，不用等模型重新开。
            var showAllGroups by remember { mutableStateOf(false) }
            val activeRows = toolGroups.filter { it.active }
            val inactiveRows = toolGroups.filter { !it.active }
            val shownRows = if (showAllGroups) toolGroups else activeRows
            AkGroup(
                title = "工具范围",
                description = "每轮请求都会带上这些工具的定义。用不到的取消勾选，就别再为它付 token"
            ) {
                shownRows.forEachIndexed { i, row ->
                    AkListItem(
                        icon = null,
                        title = row.name,
                        description = row.note + " · " + row.count + " 个工具",
                        trailing = {
                            if (row.pinned) {
                                // 常驻组不给勾选框：勾选框的语义是「能取消」，而它们在
                                // 下一轮 plan 里会被强制加回来，取消是假动作。
                                Text(
                                    text = "常驻",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                // 勾着 = 已启用。取消勾选 = 关掉，比一个「关闭」按钮更直白：
                                // 用户在设置页看到勾选框，默认心智就是「不勾即不要」。
                                AkCheckbox(
                                    checked = row.active,
                                    onCheckedChange = { want ->
                                        if (want) vm.enableToolGroup(row.name) else vm.dropToolGroup(row.name)
                                    }
                                )
                            }
                        }
                    )
                }
                if (inactiveRows.isNotEmpty()) {
                    AkListItem(
                        icon = null,
                        title = if (showAllGroups) "收起未启用的 ${inactiveRows.size} 个组"
                        else "另有 ${inactiveRows.size} 个组未启用",
                        description = if (showAllGroups) null
                        else "模型需要时会自己开（`__use_tools`）；展开可以手动开回来",
                        onClick = { showAllGroups = !showAllGroups },
                        trailing = {
                            Text(
                                text = if (showAllGroups) "收起" else "展开",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }

            // —— R8：重资源观察面 ——
            //
            // 租约管的是「看不见的东西」：本地模型、终端会话、容器会话分别占着内存/进程，
            // 空闲了会被回收器带走。在此之前这些状态只落在 logcat 里，用户想知道
            // 「我的手机为什么一直发热」得会看 adb——观测面缺失，护栏等于没有。
            //
            // 放在「工具范围」后面：上一组管 token 成本，这一组管内存成本，
            // 两者都是「你现在在为什么付代价」，挨着读才连得上。
            ResourceLeaseSection()

            // —— 设备能力：自动化总开关 + 权限入口 + 存活自检 ——
            // W-68：Shizuku / 无线调试原来单独一张「调试通道」卡，和本组的「权限中心」说的是
            // 同一件事（拿 uid 2000 的权限），现在两条调试通道都收进权限中心页。
            AkGroup(
                title = "设备能力",
                description = "关掉后 Agent 只能对话，不能操作手机"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Bolt,
                    title = "自动化总开关",
                    description = if (enabled) "已开启，Agent 可操作手机" else "已关闭，Agent 只能对话",
                    trailing = {
                        AkSwitch(
                            checked = enabled,
                            onCheckedChange = { vm.setAutomationEnabled(it) }
                        )
                    }
                )
                // 权限中心：无障碍、通知、使用情况，以及 Shizuku / 无线调试两条调试通道
                AkListItem(
                    icon = Icons.Outlined.Shield,
                    title = "权限中心",
                    description = "无障碍、通知、使用情况与调试通道（Shizuku / 无线调试）",
                    onClick = { onNavigate(AppDest.PERMISSIONS) }
                )
                // 存活自检：给没有电脑、拉不了 adb 的用户一个自测入口。
                // 判据写在通知里——锁屏回来数字还在涨就是没被冻结。
                AkListItem(
                    icon = Icons.Outlined.Refresh,
                    title = "后台存活自检",
                    description = "锁屏 5 分钟，通知里的秒数还在涨说明任务不会被冻结",
                    onClick = {
                        SurvivalCheckService.start(context)
                        Toast.makeText(
                            context,
                            "已开始：锁屏后回来看通知里的秒数",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }

            // —— 工具：从抽屉搬来的常用入口（用户反馈侧边栏拥挤，参照 RikkaHub 收纳）——
            AkGroup(
                title = "工具",
                description = "日常用的入口都在这里"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Cloud,
                    title = "模型配置",
                    description = "接口地址、API Key、模型名与系统提示词",
                    onClick = { onNavigate(AppDest.MODEL) }
                )
                AkListItem(
                    icon = Icons.Outlined.Apps,
                    title = "容器",
                    description = "Termux 容器会话与命令执行状态",
                    onClick = { onNavigate(AppDest.CONTAINER) }
                )
                AkListItem(
                    icon = Icons.Outlined.Edit,
                    title = "编辑器",
                    description = "查看与修改工作区内的代码文件",
                    onClick = { onNavigate(AppDest.EDITOR) }
                )
                AkListItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "提示词库",
                    description = "输入栏闪电按钮唤起的快捷指令，可新建与编辑",
                    onClick = { onNavigate(AppDest.PROMPTS) }
                )
                AkListItem(
                    icon = Icons.Outlined.Bookmark,
                    title = "收藏",
                    description = "对话里收藏的消息；删会话也不丢",
                    onClick = { onNavigate(AppDest.BOOKMARKS) }
                )
                AkListItem(
                    icon = Icons.Outlined.Extension,
                    title = "MCP",
                    description = "管理外部 MCP 服务器与工具接入",
                    onClick = { onNavigate(AppDest.MCP) }
                )
                AkListItem(
                    icon = Icons.Outlined.Image,
                    title = "创作工坊",
                    description = "生图、视频等多模态创作与结果管理",
                    onClick = { onNavigate(AppDest.MEDIA) }
                )
                AkListItem(
                    icon = Icons.Outlined.Build,
                    title = "构建",
                    description = "推源码到 GitHub、CI 构建并装回本机",
                    onClick = { onNavigate(AppDest.BUILD) }
                )
            }

            // —— W-68：数据与备份合成一组 ——
            // 两件事都在回答「这台手机上存了什么、怎么带走」：技能/日志/记忆是本机数据，
            // 导出/恢复是它的迁移方式。原来分成两张卡，中间隔着一整组「帮助」，找备份
            // 得往下翻两屏。
            AkGroup(
                title = "数据",
                description = "技能、日志、记忆与备份；导出含 API Key，请勿外传"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Extension,
                    title = "技能中心",
                    description = "启停、导入与信任等级",
                    onClick = { onNavigate(AppDest.SKILLS) }
                )
                AkListItem(
                    icon = Icons.Outlined.Description,
                    title = "运行日志",
                    description = "LLM 请求、工具调用与错误记录",
                    onClick = { onNavigate(AppDest.LOGS) }
                )
                AkListItem(
                    icon = Icons.Outlined.Folder,
                    title = "记忆库",
                    description = "跨会话长期记忆",
                    onClick = { onNavigate(AppDest.MEMORY) }
                )
                AkListItem(
                    icon = Icons.Outlined.Save,
                    title = "导出备份",
                    description = "选择保存位置，生成 ZIP（含会话、记忆与模型配置）",
                    onClick = { exportLauncher.launch("beichen-backup.zip") }
                )
                AkListItem(
                    icon = Icons.Outlined.Folder,
                    title = "恢复备份",
                    description = "从 ZIP 备份恢复，覆盖当前全部数据",
                    onClick = { confirmRestore = true }
                )
            }

            // —— W-68：帮助与关于合成一组 ——
            // 两行都在回答「不会用 / 想了解这个 App 时去哪」。关于页里第一张卡就是应用介绍，
            // 紧接着是「权限怎么给」，点一次两件事都看得到。
            AkGroup(
                title = "帮助",
                description = "上手引导、产品介绍与权限怎么给"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Refresh,
                    title = "重看上手引导",
                    description = "回到对话页，重新指出输入框、发送键与抽屉的位置",
                    onClick = {
                        // 两件事一起做：清聚光灯标记 + 标记权限引导已看过。
                        // 只清前者的话，没用过权限引导的用户点「重看」会什么都不发生——
                        // 聚光灯的触发条件里有「权限引导已走过」，用户主动要求重看时不该被它挡住。
                        markGuideSkipped(ctx)
                        resetSpotlight(ctx)
                        onNavigate(AppDest.CHAT)
                    }
                )
                AkListItem(
                    icon = Icons.Outlined.Info,
                    title = "关于与帮助",
                    description = "产品介绍 · 权限怎么给 · 版本与开源许可",
                    onClick = { onNavigate(AppDest.ABOUT) }
                )
            }
        }
    }

    if (confirmRestore) {
        AkConfirmDialog(
            title = "恢复备份",
            message = "恢复将用备份文件的内容覆盖本机全部会话、记忆与模型配置，当前数据不可找回。确定继续吗？",
            confirmText = "选择备份文件",
            danger = true,
            onConfirm = {
                confirmRestore = false
                importLauncher.launch(arrayOf("application/zip"))
            },
            onDismiss = { confirmRestore = false }
        )
    }
}

/**
 * 顶部 hero 卡（W-64）：当前模型 + 一个「更换」入口。
 *
 * 为什么值得单独占一块：这是全页唯一「一眼要知道」的状态。其余设置都是调一次就不动的，
 * 只有模型会被频繁切换（限流了换一个、任务重了换贵的）。它放在最上面，同时充当整页的
 * 视觉锚点——下面那些卡片才有「次要信息」的层次。
 */
@Composable
private fun ModelHeroCard(vm: SettingsScreenContract, onNavigate: (AppDest) -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeProfileId.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == activeId }
    val profileName = active?.name?.takeIf { it.isNotBlank() }
    val modelName = active?.config?.model?.takeIf { it.isNotBlank() } ?: profileName

    // v5：这里原本是整页唯一的「英雄卡」——22dp 圆角 + primaryContainer 底色 + 全宽实心按钮。
    // 结果是整页最重的一块留给了「你现在用的是什么」，可它其实只是一条信息。
    // 编辑式的做法相反：一个 11sp 标签、一个 19sp 的值、一条行动行，靠字号差距立住重心，
    // 页面因此只有一处「大」（30sp 的页标题），不会两块互相抢。
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = AgentSpacing.space5)
    ) {
        Text(
            text = "当前模型",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.2.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = modelName ?: "还没配置模型",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 19.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (modelName != null && profileName != null && profileName != modelName) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = profileName,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        AkActionLine(
            text = if (modelName == null) "去配置模型" else "更换模型",
            onClick = { onNavigate(AppDest.MODEL) }
        )
    }
}

/**
 * 备用 Key 管理（W-21）：一个 Key 被限流就自动换下一把。
 *
 * 界面纪律：列表只显示脱敏 Key（前 4 后 4），明文永不进 UI 状态也不上屏；
 * 「重置冷却」用于误判或用户已充值后立刻恢复全部 Key。
 */
@Composable
private fun BackupKeySection(vm: SettingsScreenContract) {
    val slots by vm.backupKeys.collectAsStateWithLifecycle()
    val summary by vm.keyPoolSummary.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { vm.reloadBackupKeys() }

    AkGroup(
        title = "备用 Key",
        description = "主 Key 被限流（429）或额度耗尽时自动换下一把，冷却期内不再浪费请求"
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AgentSpacing.space4)
        )
        Spacer(Modifier.height(AgentSpacing.space2))

        if (slots.isEmpty()) {
            Text(
                text = "还没有备用 Key。填一把后，主 Key 限流时会自动顶上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AgentSpacing.space4)
            )
        } else {
            slots.forEach { slot ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space1),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            slot.masked,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.removeBackupKey(slot.id) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除这把 Key")
                    }
                }
            }
        }

        Spacer(Modifier.height(AgentSpacing.space3))
        AkTextField(
            value = label,
            onValueChange = { label = it },
            label = "备注名（可选）",
            placeholder = "如：备用账号",
            modifier = Modifier.padding(horizontal = AgentSpacing.space4)
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        AkTextField(
            value = input,
            onValueChange = { input = it },
            label = "API Key",
            placeholder = "sk-...",
            modifier = Modifier.padding(horizontal = AgentSpacing.space4),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AgentSpacing.space4),
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            AkTextButton(
                text = "添加备用 Key",
                icon = Icons.Outlined.Add,
                // 原来 enabled = input.isNotBlank()：没填 Key 时按钮是灰的、点下去毫无反应。
                // 真机反馈「这个功能都没用」——用户分不清是「还没填」还是「功能坏了」。
                // 现在按钮常亮，点了再告诉他缺什么：禁用态不该是唯一的解释渠道。
                enabled = true,
                onClick = {
                    if (input.isBlank()) {
                        Toast.makeText(
                            ctx,
                            "先把 API Key 填进上面的框（sk- 开头那串），再点添加",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@AkTextButton
                    }
                    vm.addBackupKey(input, label)
                    input = ""
                    label = ""
                    Toast.makeText(ctx, "已添加，主 Key 限流时会自动切换", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

/**
 * 重资源租约观察面（R8）。
 *
 * ## 为什么放在设置页而不是新开一页
 * 三行信息（本地模型 / 终端 / 容器）不足以撑起一个页面，而设置页恰好是用户
 * 「想知道 App 在后台干什么」时会来的地方。新开一页还要多一次点击才看得到。
 *
 * ## 为什么每 2 秒采样一次
 * 回收器的节拍是 30 秒（[LeaseRegistry.checkIntervalMs]），2 秒足够让
 * 「退出容器页 → 容器会话销毁」这类变化在用户离开设置页之前可见，
 * 又不会让滚动列表每帧都去遍历三张表。采到空表就整块不显示，
 * 避免在什么都没跑的时候摆一张「暂无数据」的空卡。
 */
@Composable
private fun ResourceLeaseSection() {
    var snapshots by remember { mutableStateOf(LeaseRegistry.snapshot()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            // 放 IO：snapshot 要读会话池的锁（TerminalPoolLease 靠它数存活会话），
            // 主线程顺手拿别人的锁是 ANR 的经典起点。
            snapshots = withContext(Dispatchers.IO) { LeaseRegistry.snapshot() }
            delay(2_000L)
        }
    }
    if (snapshots.isEmpty()) return

    AkGroup(
        title = "重资源",
        description = "本地模型、终端与容器会话的占用；空闲超时的会被自动回收"
    ) {
        snapshots.forEachIndexed { index, lease ->
            AkListItem(
                icon = null,
                title = lease.title,
                description = leaseStatusText(lease),
                trailing = {
                    Text(
                        text = if (lease.activeCount > 0) "使用中" else "空闲",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (lease.activeCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }
}

/**
 * 一条租约的状态说明。
 *
 * 只显示**能被用户理解的异常**：
 * - 「句柄未归还」= 有代码借了没还，日志里会有泄漏警告，用户至少知道内存为什么没降；
 * - 「未注册释放器」= 回收器会跳过它，这类资源**永远不会**被空闲回收（配置错误）。
 *
 * 不显示 outstanding 为 0 的正常情况——每个正常租约都写「无泄漏」纯属噪音。
 */
private fun leaseStatusText(lease: LeaseSnapshot): String = buildString {
    if (lease.activeCount > 0) {
        append("${lease.activeCount} 个持有者")
    } else {
        append("空闲 ")
        append(formatIdle(lease.idleForMs))
    }
    if (lease.idleTimeoutMs < 0L) {
        append(" · 不自动回收")
    } else {
        append(" · 超 ")
        append(formatIdle(lease.idleTimeoutMs))
        append(" 回收")
    }
    if (lease.outstanding > 0L) append(" · ${lease.outstanding} 个句柄未归还")
    if (!lease.releaserRegistered) append(" · 未注册释放器")
}

private fun formatIdle(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1000L} 秒"
    ms < 3_600_000L -> "${ms / 60_000L} 分钟"
    else -> "${ms / 3_600_000L} 小时"
}

/**
 * 录音权限是否已授予。
 *
 * 抽成函数是因为它要在三处用（`remember` 初值、`ON_RESUME` 复查、申请回调）。
 * 三处各写一遍 `checkSelfPermission(...) == PERMISSION_GRANTED` 的话，
 * 将来漏改一处就会出现「设置页显示有权限、实际申请不到」。
 */
private fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
