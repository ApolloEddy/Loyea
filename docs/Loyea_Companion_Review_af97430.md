# Loyea 陪伴模式独立验收报告

日期：2026-09-12  
仓库：ApolloEddy/Loyea  
审查分支：`plugin/companion`  
锁定提交：`af97430ae318f2d4cfab34198a6dc163c26fbcce`  
对照主线：`d928e16facb7c0cc1a6c0ab28eb5942a491e94f9`

## 结论

**本轮不通过完整验收。陪伴界面与基础聊天已有实质实现，关键的数据保护和功能接入仍未完成，不建议按当前开发报告直接合并发布。**

不需要推倒重做。优先修复感知总开关、固定记忆、异步任务归属和备份事务，再补齐主动联系及基础交互。

这是锁定提交的独立代码审查，不是 Android 真机通过证明。没有修改源码、推送提交或发布版本。

## 审查范围与证据

- 比较分支与 main：7 个新增提交，28 个文件，7,425 行新增、3 行删除；生产宿主文件实际触碰 5 个，包括 README 清单遗漏的 GreetingWorker。
- 阅读原始《Loyea-Companion-Mode-Spec-v0.1.md》、修订 1，以及《Loyea_Spec_Alignment_v1.0.md》；沿调用链审查插件 UI、ChatViewModel、存储、记忆 Worker、问候 Worker。
- 按后续已确认的神经元视觉方向审查，不用旧 Spec 的“禁止持续粒子动效”否定用户后来选定的设计。
- 本期仅为 App 内陪伴。跨应用陪读、悬浮窗、音乐同步、HDS/本地情绪模型及私有 Lore 接入均为后续阶段，不计漏做。
- 尝试执行 `bash gradlew :app:testDebugUnitTest --no-daemon`，在下载 Gradle 8.13 时因 `Network is unreachable` 失败，未进入编译或测试。当前环境没有可用 Android SDK/模拟器；未执行真机交互。
- GitHub Actions 查询该分支返回 0 条运行记录。开发报告自述 172 项单测和模拟器验收，但截图、APK、JUnit XML、性能 CSV/脚本所引用的 build 路径不在本次远程源码树中；不能独立复核这些数值。缺少 CI 不等于代码不能构建。

以下“确认”指代码直接显示的缺口；所列复现步骤是下游必须执行的测试场景，不冒充本轮已完成动态复现。

## 已有的有效实现

| 部分 | 核查结果 |
|---|---|
| 独立身份与会话 | 使用 `char_loyea_companion`，复用宿主存储；正常路径查找并复用已有陪伴会话。 |
| 导航与入口隐藏 | 陪伴外壳替换普通 NavHost；抽屉列表和酒馆过滤陪伴身份。 |
| 世界书隔离 | ChatViewModel 对陪伴身份显式返回空 worldInfo，当前内置资料没有配置导入卡扩展。基础 UI 阶段合理。 |
| 文本聊天 | 实际调用宿主 sendMessage/stopResponse/regenerateLastReply，并显示流式消息。 |
| 视觉 | 神经元场景已移植到 Compose Canvas，带分层动作、拖动、活动状态和生命周期暂停处理。视觉一致性与真实发热仍须设备验证。 |
| 记忆入口 | 固定/其他双分组读取真实核心记忆和会话图谱，支持添加、删除、转固定；并非另建展示库。保护语义仍有问题。 |
| 搜索 | 300 ms 防抖，遍历当前加载的全量消息，点击结果按 ID 定位并高亮。没有只搜索 LazyColumn 可见窗口。 |
| 备份入口 | SAF 导入/导出、JSON 类型版本校验、替换前确认和 Markdown 导出均有实现。完整性、事务和清理仍不合格。 |
| 子页面聊天任务 | 设置/记忆/搜索在同一外壳切页，正常路径没有主动取消聊天任务；不等于进程或旋转恢复已通过。 |

## 必须修复的问题

### R-01 · P1 · 感知开关只更新插件设置，没有控制实际请求与工具

**位置：** `CompanionRoot.kt:55、139`；`CompanionPerceptionScreen.kt:144`；`ChatViewModel.kt:953–975、1491、2030–2085`；`GreetingWorker.kt:114`。

UI 修改 `CompanionConfig.perceptionEnabled`，只保存至插件 SharedPreferences。新会话始终设置 `useSystemTime=true`。前台 Prompt/工具执行、后台问候、记忆敏感信息过滤仍读取会话的 `useSystemTime`，未读取陪伴开关。

**影响：** 首次选择关闭、或者聊天中关闭感知，页面会显示关闭，但底层仍可能在已授权来源上采集、调用感知工具并发送环境内容。不是必然每轮采集全部来源，而是关闭后的禁止条件没有生效。

**修复：** 统一有效感知策略，以陪伴设置为权威，接入前台上下文、工具列举/执行、后台问候及记忆写入；请求前与结果接纳时均检查当前开关。保留普通模式的独立设置。

**验收：** 关闭后检查实际请求体与工具调用；重试旧轮、后台问候、在途定位结果、重启及重新开启均不得绕过关闭状态。对应 AC-07～09。

### R-02 · P1 · “固定记忆不会被自动整理改写”的承诺不成立

**位置：** `CompanionMemoryScreen.kt:101、158、310`；`MemoryConsolidationWorker.kt:79–156、214`。

页面将全部 `coreMemories` 标为固定；添加和图谱转固定时直接保存普通字符串。实际 Worker 只将以 `★` 开头的字符串识别为锁定事实，仍调用模型生成并整体替换 `coreMemories`。开发报告所称“自动整理仅写图谱”与代码相反。

还存在明确的空结果清空路径：旧记忆非空、模型正常返回“无新增记忆”等不含方括号的文本时，正则提取为空；`oldMemories.isNotEmpty()` 使写入条件成立，最终写入空列表。用户在任务开始后添加、修改或删除内容，也没有修订检查保护；对旧图谱的迟到提取可重新插入已删除条目。

**修复：** 使 UI 的固定状态与存储/Worker 合同一致；陪伴固定资料不由自动整理重写。加入修订或等价的条件提交、删除记录保护；无新增结果必须 no-op。不能只在文案或字符串前加符号就声称解决了并发问题。

**验收：** 整理运行中执行新增/修改/删除/转固定，再放行旧响应；固定项保持最新，旧删除不复活，空结果不清空。重复处理同一来源不应反复强化图谱 mentionCount。对应 AC-14、15、25。

### R-03 · P1 · 语音转写没有捕获任务归属，切换后可能发进错误会话

**位置：** `ChatViewModel.kt:207、644、4224–4305`；`CompanionRoot.kt:317–330`。

转写以独立 `viewModelScope.launch` 运行，既没有保存来源 sessionId/代际，也不属于 `responseJob`。关闭陪伴调用 stopResponse/selectSession，不能取消这项转写。转写完成后调用 `sendMessage(cleanedText, ...)`，使用届时的当前会话。

**影响：** 陪伴录音正在转写时关闭陪伴，迟到文本和音频会进入普通会话；恢复/重开期间也有同类风险。这是现有宿主能力被陪伴模式直接复用后必须补齐的集成缺口。

**修复：** 转写、图注、录音任务统一捕获会话/有效代际；切换和重置显式取消，发送前检查归属。取消的录音不得自动发送。

**验收：** 用延迟 STT 响应分别覆盖关闭模式、恢复备份、重新开始和快速切换，再释放响应；不得落入新会话或触发额外生成。对应 AC-10、16、22。

### R-04 · P1 · 恢复不是事务；写入失败后可能继续删除旧记录

**位置：** `CompanionDataScreen.kt:387–410`；`ChatStorageManager.kt:114–121、157–165、248–255`。

恢复顺序为写新消息、换会话列表、删旧消息。底层保存函数捕获写入异常后仅打印日志，返回 Unit，调用方无法知道新数据没有写成功。磁盘不足或写失败时，恢复流程仍可能换列表并删除旧数据。

单文件临时写入不构成跨消息、索引、图谱和配置的事务；rename 失败还回退直接覆盖。恢复没有完整 staging 校验、提交标记和失败回滚。此外 `applyRestore` 本身没有在写入前调用 stopResponse，与开发报告的描述不符。

**修复：** 保存必须返回成功/失败或向上传播异常。完整数据先写入并校验 staging，原子提交有效索引/归属，再清理旧数据；异常保留旧版本。冻结一致快照并使旧任务失效。

**验收：** 分别在写新消息、写索引、提交配置等位置注入磁盘错误/中断；旧记录可完整恢复，不得伪报成功。对应 AC-21，关联 AC-16、20、22。

### R-05 · P1 · 备份漏图谱与部分配置，重新开始也没有清除图谱

**位置：** `CompanionBackupCodec.kt:26–83、119–144`；`CompanionDataScreen.kt:387–426`；`CompanionRoot.kt:172–180`。

备份只包含会话对象和消息，没有独立存储的图谱条目。配置未导出用户称呼、免打扰时段等；虽导出了感知/主动联系开关，解析预览不保留这些字段，恢复也只更新新 sessionId，未应用备份配置。

恢复后换了 sessionId，旧图谱不会归属到新会话；重新开始调用的 `deleteSession` 只清消息/元数据/世界书关联，不调用 `clearMemoriesForSession`。旧图谱残留磁盘；新会话看不到旧图谱不等于已清除。

**修复：** 完整导出/恢复陪伴配置、核心/图谱记忆、摘要与消息；为图谱重新映射会话归属；重开清理全部陪伴范围数据。剥离媒体路径时保存缺失附件标记和可读描述，当前消息气泡未处理仅 imageDesc 存在的恢复场景。

**验收：** 修改称呼、DND、开关，加入一条固定记忆和一条图谱记忆，导出后在干净数据环境恢复并检查下一轮实际召回；重开后存储中无旧图谱。对应 AC-20、22。

### R-06 · P1 · 主动联系只补了开关/DND 前置判断，未完成任务合同

**位置：** `GreetingWorker.kt:42–57、88–114、183–216、251–257`；`CompanionProactiveGate`。

- 任务仍以普通 prefs 的 `current_session_id` 选目标，没有显式绑定陪伴会话。
- 只在任务开始检查陪伴门控；生成完成、写盘和通知前不复核模式、开关、DND、归属代际。
- 没有每日最多 2 条、间隔至少 4 小时、最近聊天不足 30 分钟、正在输入/录音/生成、上次未回应等约束；沿用随机 120～479 分钟。
- 通知未授权只在 UI 提示，Worker 仍可能生成和落盘。
- 陪伴开关仍受普通模式 `enable_background_greeting` 前置开关阻断；DND 还叠加宿主固定 00:00–07:00。
- 通知 Intent 没有目标会话 ID，无法保证旧通知跳转正确会话。
- 关闭/重开没有取消或废弃已运行问候；消息 ID 以当前时间生成，没有稳定事件去重。

`ChatStorageManager.updateSessionMessages` 对不存在的文件读取空列表后重新保存，**“旧文件删了，旧回调就无法复活记录”的报告结论不成立**。旧问候至少可重建已删会话的消息文件并发通知；不声称一定会重新出现在侧栏索引中。

**修复：** 共用 Worker 可以保留，但须明确任务目标/模式/代际，生成前与提交前执行同一门控；持久化限频和回应状态，使用稳定事件 ID；实现通知路由与任务取消。

**验收：** 对每条门控单独测试，并覆盖生成中关闭开关/模式、恢复、重开、时区变化、无通知权限、重复投递。对应 AC-17～19、22。

### R-07 · P1 · 消息文件丢失或损坏会被当作空历史打开

**位置：** `ChatViewModel.ensureCompanionSession:953–964`；`ChatStorageManager.loadSessionMessagesInternal:167–190`；`CompanionRoot.kt:70–90、111–119`。

绑定恢复只检查会话元数据有无，不检查对应消息文件的可读状态。消息文件不存在或解析失败均返回 emptyList，界面进入 READY 空时间线，而不是 MISSING。恢复页本身也没有“恢复备份”入口，用户无法从异常态直接导入备份。

**修复：** 区分合法空会话、文件缺失和文件损坏；首次创建持久化合法空消息文件或明确状态。异常态允许重试、恢复备份及明确重开，不能悄悄把损坏历史当成新会话。

**验收：** 保留元数据，分别移走/损坏已有消息文件；必须进入恢复态且不会自动发消息或覆盖原数据。对应 FUN-09。

### R-08 · P2 · 用户称呼未参与实际 Prompt

**位置：** `CompanionSettingsScreen.kt:92–96`；`CompanionConfigStore.kt:20、33`；宿主 ChatViewModel/PromptAssembler 和 `GreetingWorker.kt:108`。

用户称呼保存在插件配置；全仓检索未发现 `effectiveUserName` 被调用，实际聊天与问候沿用普通 `user_name`。保存设置后不会按该字段改变称呼。

**修复：** 陪伴上下文使用陪伴用户资料；普通模式维持原用户名。通过请求体验证，不只检查输入框保存。

### R-09 · P2 · 部分基础聊天入口仍缺失

**位置：** `CompanionSettingsScreen.kt:138–149`；`CompanionChatScreen.kt:382–450、649–701`；`CompanionSetupScreen.kt:87`。

- “聊天服务”只是说明文字，要求退出陪伴后修改，没有 Spec 的模式内配置入口。
- 未实现头像修改；消息菜单仅复制、朗读、重新生成、取消，缺少回复版本切换和处理详情。
- 消息气泡没有音频播放入口；图片可显示但没有点击全屏预览接线。
- 没有回到底部/未读指示；流式自动滚动只监听消息数和 isThinking，不能据此证明长回复持续跟随可用。
- 神经元区域固定 292 dp，缺少可用高度适配；横屏+键盘下的聊天空间和输入可达性须实测，不据静态代码断言必然遮挡。
- 录音 UI 使用本地 recording 状态，startRecording 因权限拒绝直接返回后 UI 仍设为 true，可能显示未实际开始的“正在聆听”。
- 历史搜索在 LaunchedEffect 默认主线程遍历全量消息，不满足 NFR-03 的线程要求；性能影响须用真实长文本数据测量。

以上按 P0 交付范围补齐，不需要为此引入新架构。手势取消、系统返回、TalkBack、旋转和返回后滚动位置应纳入设备回归。

## AC 覆盖判定

“基础实现”不代表本轮真机通过；“待验证”不等于确定有缺陷。

| AC | 当前结论 |
|---|---|
| 01 | 普通入口过滤和独立资料基础实现；升级/普通模式完整回归待验证。 |
| 02–03 | 唯一会话正常路径基础实现；并发初始化、错误恢复和旋转仍待验证。 |
| 04 | 独立外壳与入口隐藏实现；横屏/平板布局待验证。 |
| 05 | 普通恢复点过滤、草稿持久化和关闭保留数据基础实现。 |
| 06 | 当前 UI 阶段世界书隔离基础实现。 |
| 07–09 | 不通过：R-01；来源状态也不能仅用系统授权替代真实可用性。 |
| 10–11 | 部分实现：R-03、R-09；无完整真机语音及无障碍证明。 |
| 12 | 不完整：缺回到底部/未读，流式跟随需复测。 |
| 13 | 全量内存搜索及跳转已接；万条测试自述尚未独立复核。 |
| 14–15 | 不通过：R-02。 |
| 16 | 不通过：R-03、R-04、R-06。 |
| 17–19 | 不通过：R-06。 |
| 20 | 不通过：R-05。 |
| 21 | 不通过：R-04。 |
| 22 | 不通过：R-03、R-05、R-06。 |
| 23 | 待验证；缺真机证据，不能用模拟器恒定温度证明无发热。 |
| 24 | 内部设置/记忆/搜索保持同一 ViewModel 的基础路径成立；预览入口缺失，完整行为未验。 |
| 25 | 不通过：R-02。 |

## 建议修复顺序与重新验收

1. **先处理误采集/串话/丢数据：** R-01、R-02、R-03、R-04。用可控延迟响应与存储故障注入做针对性测试。
2. **补齐数据生命周期：** R-05、R-07；与 R-06 共用任务归属、取消和有效性检查，不重复设计同义代际系统。
3. **完成主动联系合同：** R-06。按 Spec 每个条件分别验收，而不是仅测试 DND 区间函数。
4. **补齐产品入口和状态反馈：** R-08、R-09；在手机竖屏、横屏+键盘、平板和 TalkBack 上验证。
5. 对修复提交运行完整单测，提供 JUnit XML、构建 APK、关键界面截图和实际请求/任务事件记录。性能报告绑定同一提交与设备；真机热量和厂商电源行为另行实测。

开发报告与 README 应同步修正：固定记忆保护、恢复原子性、旧回调失效不能继续写“通过”；README 的“备份/语音/图谱尚未实现”和报告的“尚未推送”均已过时。报告中自行采用模拟器作为验收上限的决定，不等于用户已豁免原 Spec 的真机条款。

## 代码入口

以下链接均锁定本轮审查提交，避免分支更新后证据漂移：

- [陪伴外壳](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/plugins/companion/android/src/main/kotlin/com/loyea/plugin/companion/CompanionRoot.kt)
- [记忆 Worker](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/app/src/main/java/com/loyea/worker/MemoryConsolidationWorker.kt)
- [问候 Worker](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/app/src/main/java/com/loyea/worker/GreetingWorker.kt)
- [备份操作](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/plugins/companion/android/src/main/kotlin/com/loyea/plugin/companion/CompanionDataScreen.kt)
- [备份编解码](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/plugins/companion/android/src/main/kotlin/com/loyea/plugin/companion/CompanionBackupCodec.kt)
- [ChatViewModel](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt)
- [存储实现](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt)
- [开发方报告](https://github.com/ApolloEddy/Loyea/blob/af97430ae318f2d4cfab34198a6dc163c26fbcce/docs/audits/Companion-Plugin-Audit-2026-09-12.md)
