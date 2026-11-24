# ChatViewModel 切分分析（第 2 轮 · 只分析，不动代码）

> 基线 `main` @ `025ffd4`。`ChatViewModel.kt` 现 **3221 行 / 冻结上限 3221**，余量 0。
> 本文只回答两件事：**每个 `StateFlow` 谁写谁读**，以及**从哪儿切不会把自己卡死**。
> 按任务书要求，先交这份分析等审核，动手在确认之后。

---

## 0. 结论先说（三句话）

1. **可以切**，手册给的目标形态（一个 ViewModel 持有全部 `StateFlow` + 抽纯计算/无状态流程）在这个文件上是成立的。
2. **但批次顺序要改两处**：`input` 并不"最独立"——它有 6 条写路径，散在 `send`、`reaskFromTurn`、`autoResumeIfPossible` 里；`ComposerState` 必须一次性接管这 6 条，否则就是多写者。
3. **真正的横切钩子是 `onBackupRestored`**：一个函数碰 5 个流、横跨 4 个批。切之前必须约定"这个函数每批改一次"是可以接受的，否则每批都会被它拖回原点。

---

## 1. 度量（口径写明，避免和任务书的数字对不上）

| 指标 | 本文口径 | 数值 |
|---|---|---|
| 行数 | `wc -l` | 3221 |
| `import` | `grep -c '^import '` | 180 |
| `fun` 声明 | 含 `override` / 单行实现 | 157 |
| `val`/`var` 声明 | 成员级（含 `private`/`override`） | 326 |
| `StateFlow` | `MutableStateFlow` 实例 | 20 |

> 任务书给的是 179 / 45 / 251。差异只来自统计口径（那份大概只数了 `    fun xxx(` 这种顶层成员函数）。
> 本文一律用上表口径。

---

## 2. 20 个 `StateFlow`：谁写、谁读

「写点」= 本文件里所有能让它变的路径（直接 `= value`、`update{}`、以及**注入给协作者的 setter lambda**）。

| # | Flow | 写点（函数 / 位置） | 本类读点 | UI 读者 | 建议批次 |
|---|---|---|---|---|---|
| 1 | `input` | `autoResumeIfPossible`、**注入给 `WorkflowFacade` 的 `runPrompt`**、`reaskFromTurn`、`send`、`insertPromptIntoInput`、**注入给 `editor` 的 `setInputText`** | `autoResumeIfPossible`、`send`、`insertPromptIntoInput`、`editor.adopt` | ChatScreen 等输入组件 | **批 1** |
| 2 | `pendingImages` | `attachImageFromUri`、`removePendingImage`、`attachFileFromUri`、`send` | 同 4 处 | ChatScreen | **批 1** |
| 3 | `pendingFiles` | `attachFileFromUri`×2、`removePendingFile`、`send` | `send` | ChatScreen | **批 1** |
| 4 | `skillInfos` | `reloadSkills` | — | ConsoleScreen、SkillsScreen | 批 2 |
| 5 | `importStatus` | `computeSkillInfos`、`setImportStatus`、`importFromUrl`×2、`importDemo`×2、`importFromFile`×2、`importFromUri`×2、`uninstallSkill`×2、`setScriptAuthorization`×2、**`ModelSettings` 的 `onStatus` 回调** | — | SkillsScreen | 批 2 |
| 6 | `conversations` | `refreshConversations` | — | AppScaffold、ChatScreen、ConversationsScreen | 批 3 |
| 7 | `activeConversationId` | **只有注入给 `editor` 的 `setActiveId`**（直接赋值已全部下沉） | 17 处（`send`、`regenerate`、`refreshGrants`、`projectRulesBlock`…） | 同上 | 批 3 |
| 8 | `config` | `updateConfig`、`addProfile`、`switchProfile`、`deleteProfile` | 10 处（`buildMainClient`、`buildTaskClient`、`friendlyMessage`…） | ChatScreen、ModelConfigScreen、SettingsScreen | 批 4 |
| 9 | `profiles` | `ensureActiveConfig`、`updateConfig`、`addProfile`、`renameProfile`、`deleteProfile`、`onBackupRestored` | 12 处 | 同上 | 批 4 |
| 10 | `activeProfileId` | `ensureActiveConfig`×2、`addProfile`、`switchProfile`、`deleteProfile`、`onBackupRestored` | 4 处 | 同上 | 批 4 |
| 11 | `pendingApproval` | `requestApproval`×2、`requestEgressConsent`×2 | `approveOnce`、`approveAlways`、`denyApproval` | ApprovalHost | 批 5 |
| 12 | `retryState` | `refreshGrants`、`stop`、`handleAgentEvent`×2 | — | ChatScreen、ChatUiTypes | 批 6 |
| 13 | `truncationNotice` | `refreshGrants`、`dismissTruncationNotice`、`noticeHistoryTruncated`、`handleAgentEvent` | `noticeHistoryTruncated` | ChatScreen | 批 6 |
| 14 | `interruptedNotice` | `dismissInterruptedNotice`、`showInterruptNotice`×2、`autoResumeIfPossible`、**`refreshCapabilityPlans`** | — | ChatScreen | 批 6 |
| 15 | `serviceConnected` | **只有 `refreshCapabilityPlans` 里的 `AutomationBridge.onConnectionChanged` 回调** | — | AppScaffold 等 5 个文件 | 批 2？↓ |
| 16 | `bookmarks` | `toggleBookmark`、`removeBookmark`、`clearBookmarks` | — | BookmarkScreen | 杂项 |
| 17 | `translations` | `translateMessage`、`clearTranslation`、**`editor` 的 `onTranslationsStale`** | `translateMessage` | ChatScreen | 杂项 |
| 18 | `translatingIndex` | `translateMessage`×2 | `translateMessage` | ChatScreen | 杂项 |
| 19 | `prompts` | `savePrompt`、`deletePrompt`、`duplicatePromptAsCustom`、`onBackupRestored` | — | ChatScreen、PromptLibraryScreen | 杂项 |
| 20 | `localModels`/`localActive`/`localLoaded` 等 | **不在 ViewModel 里**，由 `ModelSettings` 持有，这里只是转发 | — | 多处 | 已完成（参照） |

`#20` 行是故意留的：**`ModelSettings` 就是这套做法已经跑通过的样板**（状态在协作者里、ViewModel 只转发流与动作），批 1–4 照它的形状做即可，不需要发明新结构。

---

## 3. 三个必须先对齐的点（不先对齐，每批都会返工）

### 3.1 「一个 StateFlow 只能有一个写者」在本仓库的准确含义

现有代码里，`activeConversationId` 的直接赋值**已经全部下沉**到 `ConversationEditor` 的注入回调：

```kotlin
private val editor = ConversationEditor(
    activeId = { _activeConversationId.value },
    setActiveId = { _activeConversationId.value = it },   // ← 唯一写点
    inputText = { input.value },
    setInputText = { input.value = it },                  // ← input 的写点之一
    onTranslationsStale = { _translations.value = emptyMap() },
)
```

也就是说，"写者唯一"= **所有者唯一（这个 StateFlow 只归某一个协作者管）+ 写点只在 ChatViewModel 内**，
**不是**字面上的"文件里只有一处 `=`"。

> 这一条必须写进验收口径。否则审核方按字面 grep 写点数，批 3 抽完 `ConversationEditor` 会出现
> 「`activeConversationId` 有 1 个写点但它在 lambda 里」这种争议，每批都要吵一次。

### 3.2 `input` 不是"最独立"的那一个

它有 6 条写路径，跨 4 个批：

| 写路径 | 所在位置 | 属于 |
|---|---|---|
| 自动续跑时回填 | `autoResumeIfPossible` | 批 6 |
| 定时任务触发 | **`WorkflowFacade.runPrompt` 回调**（`runPrompt = { prompt -> input.value = prompt; send() }`） | W-17 已拆出，但写点留在 ViewModel |
| 重新发问时回填 | `reaskFromTurn` | 批 6 附近 |
| 发送后清空 | `send` | **核心流程，不属于任何批** |
| 提示词插入 | `insertPromptIntoInput` | 杂项 |
| 切会话带草稿 | `editor.setInputText` 回调 | 批 3 |

> `syncDraftDisplay` 不在表里——它只是转发 `generation.syncDisplay()`，草稿显示走的是 `streamDraft` 流，
> 不写 `input`。（我第一版把它列进来了，查了实现才改掉。）

**结论**：`ComposerState` 这一批真正的边界不是"把 `input` 搬走"，而是
**先把 6 条写路径统一成 `composer.set(text)` / `composer.clear()` 两个动作**，
再搬。否则搬完 `input` 在 ViewModel 里仍有 5 个写点，直接违反单写者。
这一批的报价应该按"改 6 个调用点 + 搬 1 个类"算，不是"搬 3 个字段"。

### 3.3 横切钩子：`onBackupRestored`

**不是 `restoreBackupFrom`**——它只有一行（`= backup.restoreFrom(uri)`），真正动手的是构造
`BackupUseCase` 时传进去的 `onRestored = ::onBackupRestored`。

`onBackupRestored`（2739）一个函数碰 5 个流、横跨 4 个批：

```kotlin
private fun onBackupRestored(items: List<PromptItem>) {
    _prompts.value = items                       // 杂项
    refreshConversations()                       // 批 3
    editor.adopt(...)                            // 批 3（内部经 setActiveId 写 activeConversationId）
    refreshMemories()
    _profiles.value = configStore.loadProfiles() // 批 4
    _activeProfileId.value = ...                 // 批 4
}
```

再加 `editor` 的 `onTranslationsStale`（`_translations.value = emptyMap()`）——恢复后旧译文作废。

**这是整个切分里唯一无法归批的东西。** 两条路：

- **A（推荐）**：承认它会改 6 次，每批只改属于自己那 1–2 行，并在每批的提交信息里写明"本批改动了 `onBackupRestored` 的哪一行"。改动小、可逐批回滚。
- **B**：先把"恢复后重载"抽成一个 `RestoreReloader`，由它调各批的接口。但那要求各批先存在——顺序上做不了第一批。

> 需要审核方确认走 A。若审核方认为"横切函数被改 6 次 = 夹带逻辑改动"，那这个文件在现有结构下无法切分。

---

## 4. 建议批次（相对任务书改两处）

| 批 | 抽出物 | 带走 | 本批必须先做的改造 | 预计降行 |
|---|---|---|---|---|
| 1 | `ComposerState` | `input` / `_pendingImages` / `_pendingFiles` / `syncDraftDisplay` | **6 条写路径收敛成 `set/clear` 两个动作**（含 `send` 里那段） | 150–200 |
| 2 | `SkillController` | `_skillInfos`、`buildInitialSkills`、`computeSkillInfos`、`reloadSkills`、`downloadSkillZip`、`refreshMcpTools` | `importStatus` 的 14 个写点全部改道 `skill.setStatus()`；`ModelSettings.onStatus` 改成指向它 | 150–200 |
| 3 | `ConversationListController` | `_conversations`、`_activeConversationId`、`switchConversation`、`refreshConversations`、`reloadActiveMessages`、`maybeGenerateTitle` | 吸收现有 `ConversationEditor` 的注入回调（**不是再造一个**） | 150–250 |
| 4 | `ModelConfigController` | `_config`、`_profiles`、`_activeProfileId`、`buildMainClient`、`buildTaskClient`、`ensureActiveConfig`、`refreshKeyPool`、`currentModelLabel`、`resetToolGroups` | 无（写点本来就集中） | 200–250 |
| 5 | `ApprovalController` | `_pendingApproval`、`requestApproval`、`requestEgressConsent` | 配判据：拒绝/允许/会话内不再问 三条路径 | 100–150 |
| 6 | `RunStateController` + `MessageAppender` | `_retryState`、`_truncationNotice`、`_interruptedNotice`、`advanceRunState`、`autoResumeIfPossible`、`handleAgentEvent`… | 与 `handleAgentEvent` 一起动，**最后做** | 300+ |
| — | 杂项 | `_bookmarks`、`_translations`、`_translatingIndex`、`_prompts`、`_importStatus` 之外的零散 | 穿插 | 100 |

**相对任务书的两处改动**：

1. **批 2 加了 `importStatus` 的所有权**。任务书没提它，但它有 14 个写点，且其中一个来自
   `ModelSettings` 的构造回调（`onStatus = { _importStatus.value = it }`）。不一起走，批 2 就只搬了一半。
2. **批 3 明确"吸收"而不是"新建"控制器**。`ConversationEditor` 已经在了（纯 JVM、有 harness），
   新控制器应该是它的**外壳（持有 StateFlow）**，不是第二个会话状态机。

---

## 5. 每批的验收（可机械执行）

```bash
# ① 本批负责的 StateFlow，在 ChatViewModel 里必须只剩「注入给 owner 的回调」这一条写路径
grep -n '_activeConversationId.value *=' app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt
# 期望：1 条，且在 `editor = ConversationEditor(...)` 的实参里

# ② 行数只减不增（新守卫已能查出来）
bash scripts/qa/run-arch-guard-verification.sh

# ③ 抽出的类必须零 android / 零 Compose，才能进变异门禁
grep -nE '^import (android|androidx\.compose)' <新文件>   # 期望：空

# ④ 每批一次真机主流程（发消息 / 切会话 / 打断 / 审批 / 语音 / 杀进程续跑）
```

---

## 6. 我不确定、需要裁决的三处

1. **`serviceConnected` 归谁**。它唯一的写点在 `refreshCapabilityPlans` 里（`AutomationBridge.onConnectionChanged` 回调），
   但它是"服务连接"状态，语义上不属于技能。若批 2 抽走 `refreshCapabilityPlans`，它会被动跟着走。
   建议：留在 ViewModel（它只有一个写点，不值得为它建控制器），批 2 只搬函数体、把回调尾部留在原处。
2. **`interruptedNotice` 在 `refreshCapabilityPlans` 里也有写点**（无障碍连接变化要清中断提示）。
   这与 1 是同一个位置。若两条建议冲突，以"批 2 不碰 `_interruptedNotice`"为准。
3. **`send()` 的归属**。它是全文件最大的函数之一，同时又写 `input` / `pendingImages` / `pendingFiles`。
   批 1 之后它会瘦一圈，但仍然是核心。**我不建议把它整体搬走**——它是"回合推进"的入口，
   与批 6 咬合；单独立一个 `SendPipeline` 属于第 3 轮的事。

---

## 7. 一句话

这个文件的可切性不在"有多少行"，而在"**写点是不是都收在一个 owner 手上**"。
20 个 `StateFlow` 里 17 个已经符合；剩下 3 个（`input`、`importStatus`、`interruptedNotice`）
的写点分散在别的批次里——先把它们收敛，再谈搬家。
