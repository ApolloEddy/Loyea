# Loyea 陪伴模式插件（plugin/companion）

依据 `docs/Loyea-Companion-Mode-Spec-v0.1.md` 实现的陪伴模式，UI 复刻 `docs/Loyea-Neural-Companion-v2.html`
（「Loyea Neural Living」琥珀神经元核心 + 暗色玻璃拟态体系）。

## 非侵入边界

全部实现收敛在 `com.loyea.plugin.companion` 命名空间（`plugins/companion/android/src/main/kotlin`），
经 `app/build.gradle.kts` 的 sourceSets 挂载进 app 编译（与调制器插件的目录化组织同构；
因需要 Compose UI 与宿主聊天链路，无法像纯 JVM 调制器那样完全零宿主触碰）。

宿主触碰点（全量清单，详见 plugin.json `hostTouches`）：

| 文件 | 改动 | 对应 Spec / 审计 |
|---|---|---|
| app/build.gradle.kts | sourceSets srcDir（3 行） | 组织挂载 |
| MainActivity.kt | 模式路由分支 + 侧栏/酒馆过滤 + 通知路由（EXTRA_OPEN_COMPANION）+ 前台状态回写 + 问候调度条件扩展 | FUN-08/NAV-01、FUN-02、FUN-03、R-06 |
| ChatViewModel.kt | `ensureCompanionSession` 钩子（含消息文件探测/感知初值）+ `setSessionPerceptionEnabled` + `effectivePromptUserName` + 世界书短路 + 转写任务归属 | FUN-01、FUN-04、R-01/03/07/08 |
| ChatStorageManager.kt | 写入成败上报 + 防复活护栏 + memoryRevision/consolidatedUpTo + 消息文件探测/落盘 | R-02/03/04/07 共用底座（无陪伴语义） |
| GreetingWorker.kt | 陪伴/普通双路径（陪伴完整门控+台账+通知路由；普通原语义保留） | R-06 |
| MemoryConsolidationWorker.kt | 陪伴只读固定记忆 + 修订号条件提交 + 整理水位 | R-02 |
| SettingsScreen.kt | 陪伴模式入口卡 + 开启浮层挂载 | S-01 |

依赖方向：插件 → 宿主公共 API（ChatViewModel / ChatStorageManager 数据模型 / PromptAssembler 间接）；
宿主仅经 `CompanionContract.isCompanionCharacter` 判断归属，不感知插件内部。

## 数据归属

- 插件自有偏好：`loyea_companion_prefs`（CompanionConfig + 主动联系台账 CompanionGreetingLedger）。
- 聊天与记忆：复用宿主 `rebuild_storage_v1` 存储，`characterId = char_loyea_companion`；
  侧栏过滤保证不出现在普通会话列表；普通模式书库/角色编辑不影响陪伴对象。

## 已实现（对应 Spec）

- 模式与导航：S-01 首次开启、S-02 唯一聊天页、模式路由与恢复（FUN-01/02/03/04/05/06/08/09）、NAV-01/02/03、
  关闭陪伴模式（DATA-06 语义：只切入口不清数据）。
- UI：神经元核心完整移植；玻璃拟态气泡与输入条；倾听/思考节律联动；UI-04/06/09/11/13；
  回到底部/未读指示、图片全屏预览、音频播放入口、自适应神经元舞台高度（AC-12 / R-09）。
- 聊天：唯一会话收发/流式/停止/草稿；语音输入（按住说话/取消/转写归属保护 AC-10/R-03）、图片附件、
  长按消息操作（复制/朗读/重新生成）；Markdown 与思考折叠复用宿主渲染。
- 记忆（S-05/MEM-01）：固定/其他双分组；固定记忆为用户独占（自动整理只读，R-02）；
  图谱转固定（MEM-03）、删除不删聊天（UI-17）。
- 查找记录（S-06）：本会话关键词搜索、防抖、后台线程遍历、结果跳转定位高亮（UI-20/21/22、NFR-03）。
- 陪伴设置（S-04）：显示名/称呼（R-08：称呼参与实际 Prompt）、头像更换、聊天服务模式内切换、
  感知与主动联系页（PER-01 四态 + 免打扰时段）、关闭入口（UI-14/15/16）。
- 数据与备份（S-08）：JSON 快照 v2 = 设置 + 固定/图谱记忆 + 全量消息；恢复事务化
  （staging → 原子换列表 → 清旧，失败保留旧数据 R-04/05）；重新开始清聊天/记忆/图谱/草稿；
  恢复态（FUN-09）支持重试/从备份恢复/明确重开（R-07）。
- 主动联系（ACT）：完整门控合同（通知权限、每日 2 条、≥4h 间隔、30 分钟静默、
  未回应抑制、前台不打扰）生成前+提交前双执行；稳定事件 ID 去重；通知路由回陪伴会话（R-06/AC-17~19）。
- 感知（R-01）：陪伴感知开关经会话 `useSystemTime` 统一生效——前台上下文、工具执行、
  记忆敏感过滤、后台问候读取同一状态；普通模式开关独立不受影响。

## 测试

- `CompanionBackupCodecTest`：v2 往返/配置/图谱/媒体剥离/版本拒绝/v1 兼容。
- `CompanionProactiveGateTest`：免打扰区间 + evaluate 全条件独立判定。
- 宿主侧 `ChatStorageManagerTest`（护栏/修订号/水位/探测）与 `MemoryConsolidationPolicyTest`
  （冲突作废/空结果 no-op/锁定保留）覆盖共用底座。

## 已知边界（后续规格）

- 跨应用陪读、悬浮窗、音乐同步、HDS/本地情绪模型及私有 Lore 接入为 Spec 后续阶段。
- 真机热量、厂商电源行为与 TalkBack 等无障碍回归仍按 NFR-06/07 需真机实测（模拟器证据不替代）。
