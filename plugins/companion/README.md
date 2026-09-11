# Loyea 陪伴模式插件（plugin/companion）

依据 `docs/Loyea-Companion-Mode-Spec-v0.1.md` 实现的陪伴模式，UI 复刻 `docs/Loyea-Neural-Companion-v2.html`
（「Loyea Neural Living」琥珀神经元核心 + 暗色玻璃拟态体系）。

## 非侵入边界

全部实现收敛在 `com.loyea.plugin.companion` 命名空间（`plugins/companion/android/src/main/kotlin`），
经 `app/build.gradle.kts` 的 sourceSets 挂载进 app 编译（与调制器插件的目录化组织同构；
因需要 Compose UI 与宿主聊天链路，无法像纯 JVM 调制器那样完全零宿主触碰）。

宿主触碰点（全量清单，详见 plugin.json `hostTouches`）：

| 文件 | 改动 | 对应 Spec |
|---|---|---|
| app/build.gradle.kts | sourceSets srcDir（3 行） | 组织挂载 |
| MainActivity.kt | 模式路由分支 + 侧栏过滤 + 酒馆过滤 | FUN-08/NAV-01、FUN-02、FUN-03 |
| ChatViewModel.kt | `ensureCompanionSession` 钩子 + 世界书短路 | FUN-01、FUN-04 |
| SettingsScreen.kt | 陪伴模式入口卡 + 开启浮层挂载 | S-01 |

依赖方向：插件 → 宿主公共 API（ChatViewModel / ChatStorageManager 数据模型 / PromptAssembler 间接）；
宿主仅经 `CompanionContract.isCompanionCharacter` 判断归属，不感知插件内部。

## 数据归属

- 插件自有偏好：`loyea_companion_prefs`（CompanionConfig：enabled/sessionId/显示资料/感知/主动联系/lastNormalSessionId）。
- 聊天与记忆：复用宿主 `rebuild_storage_v1` 存储，`characterId = char_loyea_companion`；
  侧栏过滤保证不出现在普通会话列表；普通模式书库/角色编辑不影响陪伴对象。

## 已实现（对应 Spec）

- 模式与导航：S-01 首次开启、S-02 唯一聊天页、模式路由与恢复（FUN-01/02/03/04/05/06/08/09）、NAV-01/02/03、关闭陪伴模式（DATA-06 语义：只切入口不清数据）。
- UI：神经元核心完整移植（135+610 节点/度上限 5 近邻边/28 桥接/2 组轨道/尘埃/电火花/五层呼吸帧/拖拽旋转/生命周期暂停）；
  玻璃拟态气泡与输入条；倾听/思考节律联动；UI-04/06/09/11/13。
- 聊天：唯一会话收发/流式/停止/草稿；Markdown 与思考折叠复用宿主渲染；错误显示与重试语义沿用宿主。
- 记忆（S-05）：固定记忆添加/修改/删除、立即整理入口、空状态；删除不删聊天（UI-17）。
- 查找记录（S-06）：本会话关键词搜索、防抖、结果跳转定位高亮（UI-20/21/22）。
- 陪伴设置（S-04）：显示名/称呼、感知与联系开关、关闭入口（UI-14/15/16）。

## 已知边界（后续规格）

- S-08 数据与备份（导出/恢复/重新开始的完整实现）未做；FUN-09 恢复态提供重试与明确重开。
- 语音输入/朗读、图片附件输入沿用宿主普通模式能力，陪伴页内入口在后续版本补齐（发送链路已支持 imageUrl/audioUrl 参数）。
- 图谱记忆的管理视图适配层（MEM-01）未接入，记忆页如实展示核心记忆；固定/其他双分组待 MemoryView 合同落地。
- 感知页逐项授权状态（PER-01）与主动联系免打扰细化（ACT 系列）沿用宿主现有能力，独立页在后续版本。
- 未按 NFR-06/07 做真机资源对照与万条消息验收。
