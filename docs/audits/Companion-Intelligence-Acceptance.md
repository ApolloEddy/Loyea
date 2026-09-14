# Companion Intelligence Acceptance(调制器与感知接入陪伴模式 · 验收报告)

日期:2026-09-14 · 分支:`feature/companion-intelligence-integration` · HEAD:`86b40fe`(后续测试增补见 §7 提交记录)
Spec:`docs/Loyea_Companion_Modulator_Perception_Integration_Spec_v1.0.md` · Preflight:`docs/audits/Companion-Intelligence-Preflight.md` · 核心修订:`docs/audits/Companion-Intelligence-Core-Revision.md`

## 0. 完成状态措辞(Spec §13.3)

**代码与自动验证完成;模拟器真模型验收完成;真实语义集机器可判定部分(83 条,含 72 条误敌意边界)完成——错误敌意 0 达标;启用/禁用调制 20 组多轮生成对照完成(MiMo 真网关,模拟器)。剩余 BLOCKED:80 条集的人工审定签收、OPPO 真机性能与 30 分钟温升对照(测量脚本已交付 tools/measure_oppo_perf.sh)。**
真实感知 → 调制 → 私有 Lore → 出站请求的全链路已在 Android 运行时(模拟器)上以真实 ONNX 模型、真实 SQLite 账本、实际序列化 provider payload 验证;未以任何"降级聊天正常"替代完整接入验收。

## 1. 样例证据链(Spec §13.2 要求的首项)

样本:`谢谢你今天帮我改简历，真的太感谢了`(speaker_0,无上下文)

| 环节 | 证据 |
|---|---|
| ① 真实感知 | `LegacySensorOnDeviceTest`(Pixel_10 模拟器,API 35):24MB q8 ONNX(SHA-256 `a3e763f4…84419cb`,载入即校验)+ onnxruntime-android 1.18.0 官方运行时;16 条真实样本拼接→WordPiece→推理→校准/解码→语法修正,与 PC 参考实现(原 HTML 内联 JS 管线以 Node 运行 + onnxruntime CPU logits)的**离散解码完全一致**;该样本 act=`thank_appreciate`、target=`listener` |
| ② 事件评价 | `AppraisalEngine` kindness 路由命中(act∈{thank_appreciate} 且 ac≥0.70、direct、factuality asserted)→ `kindness` 事件(label=gratitude) |
| ③ 调制事务 | `CompanionEndToEndDeviceTest.fullPipeline…`:真实 SQLite 账本(`companion_runtime.db`)CAS 提交 Observation+检查点+RequestView+Outbox,seq 0→1、交互计数 0→1;admit 全程 70ms(含感知) |
| ④ 状态投影 | 纯投影 `project()`(不推进状态)输出状态行 `feeling/gratitude/moderate`;重复投影 dumps 不变(核心 A15) |
| ⑤ 私有 Lore | `selectLore` 从 8 条私有条目中确定性选中 `loyea.gratitude`(statesAny=feeling/gratitude,排他组 loyea_feeling_response) |
| ⑥ 出站请求 | `CompanionOutboundPayloadTest`(MockWebServer 捕获实际序列化请求体):恰好一份 `[CURRENT COMPANION STATE]` 块 + `简短回应用户的善意`(loyea.gratitude 文本)进入 payload;历史用户消息旧快照不再注入;`calibratedProbability/grammarHints/policyWeight/dialogueAct/attributionAdjusted` 等解码细节零泄漏;用户原文正常在场 |

## 2. 执行环境与命令

- JDK 17.0.17 / Gradle wrapper 8.13 / SDK `D:\Dev\Android\Sdk` / 模拟器 Pixel_10(API 35, x86_64, WHPX)
- 实际执行(全部通过,记录于 CI 本地输出):
  - `./gradlew :loyea-modulator-core:verifyModulatorBoundaries :loyea-modulator-core:test` — 41 项(31 场景 + 3 数值 + 5 golden/迁移 + 1 性能 + 1 工具)
  - `./gradlew :app:testDebugUnitTest` — 271 项(含 12 协调器 + 6 生命周期 + 3 感知 golden + 1 出站 payload)
  - `./gradlew :app:connectedDebugAndroidTest` — 4 项设备测试(Pixel_10)
  - `./gradlew :app:assembleDebug` — debug APK

## 3. 验收矩阵 A01–A37

图例:✅=通过(标注验证层级:JVM=单测/设备=instrumented/演绎=设计+既有测试推导);⚠️=部分(原因明示);⛔=BLOCKED。

| ID | 场景 | 状态 | 证据 |
|---|---|---|---|
| A01 | 新装→陪伴发文字,真实路径+有效请求 | ✅设备 | LegacySensorOnDeviceTest(真模型 16 样本离散一致)+CompanionEndToEndDeviceTest(真 SQLite+真模型,payload 含状态块;gratitude→loyea.gratitude 命中) |
| A02 | 普通聊天不触碰陪伴 runtime | ✅JVM/演绎 | 全部陪伴钩子以 `isCompanionCharacter` 门控;普通链路 200+ 既有单测无改动全绿;陪伴分支在 sendMessage/startAiResponseStream 中以 `isCompanionTurn` 隔离 |
| A03 | 同原文重复点击发送 | ✅JVM+设备 | a03_duplicateSendAdmitsOnce(1 观测/1 计数/Reused)+设备端 retry=Reused;admissionMutex 覆盖 responseJob 建立前窗口 |
| A04 | 两次分别发送相同正文 | ✅JVM | a04_identicalTextSeparateSendsAdmittedTwice(两个稳定 ID,分别接纳) |
| A05 | SSE 断线/生成重试十次 | ✅JVM | a05_retryReuseDoesNotAdvanceState(checkpoint/seq/计数 10 次重试不变) |
| A06 | 工具终态重复回调 | ✅JVM | a06_a07_toolFactAdmittedOnceWithoutInteractionCount(第二次=Reused) |
| A07 | 首次工具失败后继续回复 | ✅JVM | 同上(seq+1、计数不加、activeTags 含 host:task_blocked);子请求模块替换为 requestRevision+1(ChatViewModel 工具轮) |
| A08 | 语音转写完成/取消/迟到 | ✅演绎 | 完整转写经 sendMessage→统一接纳;转写归属保护(既有 AC-10/R-03)+换代围栏(generation/incarnation)拦截迟到写入 |
| A09 | 纯媒体无有效文字 | ✅演绎 | attachmentsHash 非空+text 空白→SensorStatus.NO_TEXT→合法空感知;无任何远程情绪分析代码路径(§A37 出站监控亦证) |
| A10 | 模型缺失/坏哈希/错版本/NaN | ✅JVM+演绎 | Sensor:大小+SHA-256 校验失败→INVALID_ASSET;metadata schema≠2.1.0→UNSUPPORTED_VERSION;核心 NaN/±inf/坏版本→IllegalArgumentException 且原子拒绝(ScenarioTest test_11),坏输出不可部分写入 |
| A11 | 热态超时/迟到结果 | ✅设备 | timeoutDegradationDoesNotBlockChat:500ms 截止、等待者经通道分离、迟到结果丢弃、无并发关闭/无界 Session(单线程执行器+nativeJobs 门) |
| A12 | 否定/假设/转述/第三方/玩笑/纠错 | ✅JVM+⚠️ | fixture 门控精确(ScenarioTest test_01–08);83 条真实语义集机器判定完成(§5.1:错误敌意 0 达标,命中率 4.7% 受限能力如实报告);人工审定签收 BLOCKED(§6) |
| A13 | 同话题威胁 A/B 只解决 A | ✅JVM | attributionRevisionProperties:A 分量衰减、B 分量只受时间衰减(1e-12) |
| A14 | 强证据隐藏弱证据可见 | ✅JVM | 同上:感受强度只由可见分量支持,sources 不含隐藏 id,intensity=mild |
| A15 | 投影十次不变 | ✅JVM | 同上:rows/sources/dumps/seq 全部不变 |
| A16 | 物理感知关闭发普通文字 | ✅设备/演绎 | E2E 以 physicalPerceptionEnabled=false 运行,文字感知+状态正常;物理采集走既有 useSystemTime 门控 |
| A17 | 冻结请求后撤回权限 | ✅演绎 | policyRevision 机制+每次 dispatch 前 allowPhysicalContext/allowGraphContext 现算+sanitizeSnapshot 剪裁(既有);CompanionPolicyBook bump 于开关变更 |
| A18 | 删除记忆后重试 | ✅演绎 | memoryRevision 进入 policy snapshot;记忆删除既有修订防线(审计 R-02 体系)失效来源与请求缓存 |
| A19 | 普通模式激活全局书 | ✅JVM/演绎 | resolvePrivateLore:固定私有 book ID+版本校验,不符→NONE;陪伴会话强制 worldInfo=null(既有 ChatViewModel:1651);无全局回退代码路径 |
| A20 | useSystemTime=false 且物理快照空 | ✅设备 | E2E(physical=false)状态下状态模块仍进入实际 payload |
| A21 | builder 裁剪掉唯一情绪证据 | ✅演绎 | visibleEvidence 只由 selectedHistory+当前消息映射的观测构成;被压缩消息的观测不可见→对应感受行退出(仅可见分量计数,核心层) |
| A22 | 多轮含历史快照 | ✅JVM | CompanionOutboundPayloadTest:payload 恰好一份当前状态块,历史快照字符串零出现 |
| A23 | 事务提交失败 | ✅JVM | a23_commitFailureLeavesNoPartialState(状态未变/无观测/同 ID 可重试) |
| A24 | 提交后投影前杀进程 | ✅JVM | a24_projectionFailureRecoversViaOutbox(projectionPending→outbox 保留→recoverOwner 补写同一 ID,不重复计数);设备杀进程专项未做(见 §6) |
| A25 | Store A 旧缓存/Store B 重置 | ✅JVM | a25_staleIncarnationRejected + backupV3ExportImport 的旧 incarnation 写入=Invalid;JSON 写入经 updateSessionMessagesFenced |
| A26 | 关闭/重开/旋转/进设置 | ✅JVM | closePreservesCommittedState(关闭仅撤 lease);application 级单例 coordinator/store;ensureOwner 幂等 |
| A27 | 设备重启/墙钟回拨/前跳/时区 | ⚠️ | CompanionClock 实现单调逻辑秒+BOOT_COUNT 锚点+max(0,Δwall) 补算+7 天裁剪;自动化未模拟真实 reboot(需多设备编排),逻辑由设计评审+FixedClock 核心验证覆盖 |
| A28 | 编辑旧输入并截断后文 | ✅JVM | a28_editRebasesToPreTurnCheckpoint(检查点回退/后缀观测废弃/计数校准/新 inputRevision 新分支)+BeforeRuntimeBaseline 显式报告 |
| A29 | v3 备份导出恢复到新 owner | ✅JVM | backupV3ExportImportPreservesRuntimeState(投影/计数/观测一致,命名空间隔离)+codecV3RoundTripsRuntime |
| A30 | 恢复各阶段故障注入 | ⚠️ | CompanionDataOps 五步事务(既有)+图谱写失败中止(新增 insertTriple 返回真实成败)——未逐阶段注入 IO/杀进程,标注为部分 |
| A31 | 导入 v1/v2 或未知 schema | ✅JVM | v1/v2→runtime=null→新短期基线(RUNTIME_BASELINE_RESET 登记);未知版本拒绝(既有测试);坏 runtime 结构整份拒绝(不静默丢状态) |
| A32 | 所有入口"重新开始" | ✅JVM | restartClearsRuntimeLedger(tombstone+观测/去重/请求视图清理,新 incarnation 从 -1 起步);CompanionDataOps.restart 统一调用 coordinator.resetCompanion |
| A33 | 高喜爱但免打扰/无通知权限 | ✅演绎 | CompanionProactiveGate 门控未改(既有 CompanionProactiveGateTest 全绿);问候仅纯投影(不跑感知/不加计数,GreetingWorker 改动) |
| A34 | 运行中开启主动联系 | ✅演绎 | setConfig 提交入口调用 enqueueProactiveCheck(KEEP 策略,唯一任务,不等 Activity 重建) |
| A35 | 无确认关系来源 | ✅核心 | RelationView 默认 unspecified;compileRows 关系行 unspecified/—(golden explicit_relationship 逐位一致);宿主仅注入 CompanionProfileRepository.relationView()=默认 |
| A36 | 长时/最大容量/极端人格 | ✅JVM | NumericalTest test_32(500 步全人格角点,8 痕迹×4 分量有界,解析恢复独立数值验证);ModulatorPerfTest |
| A37 | 全程抓取出站请求 | ✅JVM | CompanionOutboundPayloadTest:实际序列化 body 断言(单状态块/无解码细节泄漏/无新增远程情绪渠道——全仓无相关网络代码) |

统计:✅ 33 · ⚠️ 4(A08 部分演绎、A12 语义集未完、A27 无 reboot 自动化、A30 未逐阶段注入) · ⛔ 0(以 ⚠️ 明示,不用 PASS 掩盖)

## 4. 性能记录(Spec §11;模拟器测量,非 OPPO 真机)

| 项目 | 目标 | 实测(Pixel_10 模拟器 / 桌面 JVM) | 备注 |
|---|---|---|---|
| 调制器+有界 Lore 选择 | p95 < 2 ms | JVM p95=38µs(p50=11µs,1200 次);设备渲染 p95=248–285µs(1200 次) | 预热后;含 selectLore+渲染 |
| 热态文本感知 | p95 ≤250ms,截止 500ms | 设备 p50=7ms / p95=17ms(n=16) | x86_64 模拟器;ARM 真机待测 |
| 持久化接纳 | p95 ≤50ms | 设备 admit 全程 70–88ms(含感知+解码+SQLite 事务+outbox) | 事务本身远小于该值;无逐项分解,保守报告 |
| 模型准备 | 冷启动不阻塞 | 2,018ms 一次性异步(24MB 解包+SHA-256+Session);首条消息不受阻塞(NOT_READY 降级) | |
| 并发 | 至多 1 推理+有界待处理 | 单线程执行器+admitting/nativeJobs 双门(代码约束+设备测试) | |
| 闲置 | 无空转任务 | 无新增周期 Worker;不唤醒推理 | |
| 内存/包体 | 无逐轮增长 | APK 109,918,706B(模型 24MB+ORT 运行时);RAM 未测(PSS 专项未做) | 待真机 |
| 30 分钟持续 | 开/关对照 | 未执行(真机项) | 测量脚本要点:固定亮度/负载,thermal status 每 5min 采样 |

**测量脚本**:模拟器项由 androidTest 输出(`PERF` 标记,logcat 可复现);真机测量项按 §11 表格逐项待执行。

## 5. 真实语义集与生成对照(Spec §11)

### 5.1 语义集机器判定(83 条,已完成)

样本覆盖七路由正例(43 条期望)+ 否定/假设/转述/第三人称/技术提问/合理纠错/亲昵玩笑/长文本截断边界,其中 **72 条为"不应判为对 Loyea 敌意"专项**(Spec 要求 ≥30)。全部样本经真实 ONNX logits(PC 参考运行时,与设备离散一致已由 LegacySensorOnDeviceTest 证明)→ Kotlin 解码 → 语法修正 → 归因弃权 → AppraisalEngine 全链路。逐路结果(Companion-Intelligence-SemanticSet-Results.md):

| 路由 | 命中/正例 | 命中率 |
|---|---|---|
| user_distress | 0/8 | 0% |
| shared_joy | 0/7 | 0% |
| kindness | 2/6 | 33% |
| affection | 0/5 | 0% |
| hostility | 0/7 | 0% |
| repair | 0/4 | 0% |
| humor | 0/6 | 0% |
| **总体** | **2/43** | **4.7%(Spec 目标 ≥80%,未达)** |
| **错误敌意(应为 0)** | **0/72** | **达标** |

失败类别(逐条归因见结果文件):全部为**模型置信/概率能力问题**——act 头判错 8、target 置信不足 9、情绪校准概率低于阈值 12、stance 不足 6、factuality 门控拦截 4、repair 无先验张力 1。无一是接口/移植缺陷(kindness 在模型自信时正常触发,门控行为与 fixture 一致)。与 metadata 自带历史指标(baseMacroF1=0.3751)相互印证:该 24MB q8 蒸馏模型的绝对识别能力即受限能力。按 Spec 未硬编码测试句、未改标签迁就。

### 5.2 启用/禁用调制多轮生成对照(20 组,已完成)

- 执行环境:Pixel_10 模拟器(API 35)× MiMo 真网关(mimo-v2.5-pro),LlmClient 真实 transport;状态模块来自真 ONNX 感知+真 SQLite 账本。
- 设计:每组 2 轮。第 1 轮共享回复;探针轮同一用户输入、同一历史,唯一差异 = [CURRENT COMPANION STATE] 模块开/关。完整数据集(全部 ON/OFF 回复、模块行、信号)保留于 docs/audits/generation_comparison/results_full.json 与 Companion-Intelligence-Generation-Comparison.md。
- 结果:模块在位 20/20;ON 行为信号命中 16/20(OFF 同时命中 14/20,即 ON 提供定向增益 2 组、无一处 ON 劣于 OFF);主体混淆/过度表演类违例 1 处,人工复核为关键词代理误报(ON 回复"应该也没真的生气对吧?"为良性澄清,反而比 OFF 的"串台困惑"更稳)。
- 结论:对照成立且保留全部失败案例;状态对回复的可见影响集中于模型自信的路由(致谢类),与 §5.1 概率测量互相印证。

## 6. 遗留与受限能力(不隐藏)

1. **真机性能与 30 分钟温升对照未测**(无 OPPO Find X6/Pad 3 Pro 接入):所有性能数字来自模拟器/桌面 JVM,已在 §4 标注,不冒充手机表现。
2. **BLOCKED(需用户资源)**:(a) OPPO Find X6/Pad 3 Pro 真机性能与 30 分钟温升对照——测量脚本 tools/measure_oppo_perf.sh(adb 采样 battery temperature/thermal status + PERF 自动化);(b) 83 条语义集的人工审定签收与自然度评审——机器判定管线与结果文件已备(§5.1),待评审者签收;(c) 扩充生成对照集(当前 20 组×2 条件已满足 Spec 最低线)。
3. **A27 无 reboot 自动化、A30 未逐阶段故障注入**:机制在位(时钟锚点/分步回滚),标注部分通过。
4. **RequestView 导出含于 v3,但恢复后 subRequestId 与原设备轮次的对应关系仅按原值保留**;跨设备 ID 语义一致性依赖消息 ID 保留(§9.2 步骤 3 已按原 ID 恢复)。
5. **模型受限能力**:toxicity/joy/sadness 概率偏保守导致部分路由在真实语料上命中率有限(baseMacroF1=0.3751 为原文件历史记录,未复核);按 Spec 不硬编码测试句、不改标签迁就,宿主以 `task_blocked` 等宿主事实+Lore 兜底体验。
6. **上一轮遗留**(图谱抽取水位推进、删除单条记忆后旧批次重插):本轮未触及该写入路径,保持遗留登记(Preflight §5)。
7. **release 包未构建/未发布**(无签名授权);debug APK SHA-256 `9fb1f73710a0eb98c0b1e081057961d47dfc5325c69e1fbd0178ae7f681d7d5d`(构建于 86b40fe)。

## 7. Reader 伴读插件验证(模拟器实测)

| 项 | 状态 | 证据 |
|---|---|---|
| Reader M1: a11y 服务 + 悬浮球 + 提纯采样 | ✅模拟器 | Bound services 含 ReaderAccessService；PURIFY 日志含全小说文本块；悬浮球覆盖 Chrome 渲染 |
| Reader M2: 滚动摘要 + 实体卡 + 管线 | ✅JVM | 9 项 ReaderContextMemoryTest 含防剧透游标/增量喂入/跨章延续/预算裁剪 |
| Reader M3: 对话面板 + LLM 问答 + 防剧透拒绝 | ✅模拟器 | 面板展开显示状态行+输入框+问 Loyea；防剧透拒绝不发起网络；渠道未配置时优雅降级（"聊天服务未配置，无法回答"） |
| NeuralLiving 迷你画布 | ✅模拟器 | ReaderNeuralBallView 15fps Handler 驱动 advance+project；帧差 367px 证明节点在移动（非静态）；琥珀色节点+边在 Chrome 之上渲染 |
| 返回键修复 | ✅模拟器 | 子页返回上级，聊天主页双击退出+Toast |

### Reader R01-R12 逐项状态

| ID | 状态 | 说明 |
|---|---|---|
| R01 | ✅模拟器 | 无障碍服务未绑定前悬浮球不出现（dumpsys accessibility 确认服务绑定） |
| R02 | ✅模拟器 | PURIFY 日志证明 Chrome 小说页文本完整采样（章节名+段落+对话） |
| R03 | ✅设计 | Whitelist.contains() 硬编码排除自身包名 |
| R04 | ✅代码 | collectText 跳过 isPassword 节点与 EditText |
| R05 | ✅JVM | bufferDetectsChapterSwitchAndResetsCursor（游标重置+缓冲清出） |
| R06 | ✅JVM+代码 | ReaderBubbleChat.localRefusal 本地拒绝，无网络请求 |
| R07 | ✅设计 | onUnbind 调用 pipeline.reset() 清空缓冲 |
| R08 | ✅模拟器 | 拖动+吸边实测通过（reader7 reader8 截图） |
| R09 | ✅代码 | companionEnabled() false→removeBall()；陪伴聊天零影响 |
| R10 | ✅设计 | 采样 800ms 去重+15fps 画布+无周期 Worker；30 分钟实测待真机 |
| R11 | ✅模拟器 | 渠道未配置时"聊天服务未配置"友好提示（截图） |
| R12 | ✅设计 | reader 数据独立 prefs+内存缓冲，备份 v3 runtime 不含 reader |

## 8. 提交清单(feature 分支)

| commit | 内容 |
|---|---|
| df0cc18 | P0 制品提取/manifest/Preflight |
| 2a51c12 | P1 核心 v1.1.0 归因修订+投影+迁移+golden |
| 1d42adb | P2 运行时基础(协调器/账本/时钟/策略)+旧缺陷修复 |
| 4d7a363 | P3 感知管线移植+ONNX 传感器+双黄金 |
| 78c28b6 | P4 聊天闭环+UI 开关+诊断 |
| 86b40fe | P5 备份 v3+生命周期+问候共用状态 |
| (本次) | P6 设备/出站/性能测试+验收报告+README+M2 摘要/实体卡+M3 面板+神经球 |

推送与合并按用户授权办理;本文不构成自动合并 main 的依据(Spec §13.2)。
