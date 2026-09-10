# Kotlin 接入约定

本文件是接口与提交顺序约定，代码片段是类型草案。本轮可执行实现为 Python；没有将这些片段编译成 Android 应用，也未修改 Loyea 仓库。

## 接入位置

核对入口：[Loyea 仓库](https://github.com/ApolloEddy/Loyea)、[PromptAssembler.kt](https://github.com/ApolloEddy/Loyea/blob/main/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt)。本轮读取的代码存在稳定 System Prompt 与 `turnContextSnapshot` 分离，以及 `assembleTurnSnapshotOnly` 的复用途径。

| 内容 | 放置位置 |
|---|---|
| 动力学、事件适配、状态编译 | 纯 Kotlin 层，优先复用已有 `character-core` 的模块边界 |
| ONNX Session、分词、数据库和会话时钟 | app 宿主；不放进纯状态函数 |
| Soul／完整稳定人格描述 | 现有稳定 Prompt |
| 本轮状态表、选中 Lore 内容 | 现有本轮上下文快照 |
| 选择哪个世界书 | 现有 resolver；本版不另建活动世界书来源优先级 |

仓库会继续变化，实施时以当前实际路径为准。不要为了接入本调制器重写聊天链路、角色卡解析或世界书编译器。

## 类型草案

建议使用 Double，内部数值不进入面向用户的状态表。下列名字可以按仓库命名规范调整，字段语义不可自行改变。

```kotlin
data class StateRow(
    val aspect: String,
    val state: String,
    val intensity: String,
)

data class AppraisalEvent(
    val kind: String,
    val strength: Double,
    val confidence: Double,
    val evidenceId: String,
    val target: String,
    val topicId: String,
    val resolves: Set<String>,
)

data class TurnDecision(
    val observationId: String,
    val sequence: Long,
    val rows: List<StateRow>,
    val selectedLoreEntryIds: List<String>,
    val diagnostics: List<String>, // 调试信息，不进入生成层
)
```

完整状态结构、输入默认值及序列化字段以 `prototype/modulator.py` 为准。纯 Kotlin 转换函数应接受不可变旧快照并返回新快照与本轮决定；不在内部访问时间、数据库、随机数或网络。人格版本、算法版本必须放进检查点。

## 消息流程与事务

1. 宿主先按稳定观测 ID 查 TurnSnapshot。若已经存在，则复用，不重新感知、不再次推进状态。
2. 冻结当前用户原文、相关历史、角色档案版本和感知输入。只有新接纳的用户输入或明确宿主事件才是新观测；当前说话者必须已被宿主识别，不能把 assistant 输出或引文作者当作用户再次处理。
3. 在当前任务需要的时机调用一次已复用的本地感知 Session。不要对每个 token、音频小片或 SSE 分片启动完整推理；语音在形成已接纳的完整文本后处理。
4. 在单会话 actor 中检查期望旧序号，调用纯转换函数。过期的异步感知结果不能覆盖新状态。
5. 用已有 resolver 的单一活动世界书做语义选择，交给现有编译器计 token 与排序，生成本轮约束文本。
6. 在一个数据库事务中保存：已处理观测、下一状态检查点、最终 TurnSnapshot。成功后才替换内存中的正式状态并开始生成。
7. 生成重试、工具续轮、重连和旧轮重新生成都复用已提交快照。生成失败也不撤回一次已发生的用户输入；失败重试不能增加一次情绪刺激。

事务失败则保持旧正式状态，重新处理同一待提交观测。若同一 ID 正文发生变化，要求宿主执行输入修订／回放，不按普通重试忽略新正文。

Python 原型仅缓存最新 Decision。宿主数据库必须承担历史 ID 去重，禁止把旧观测换一个新序号传入原型来“重新生成”。

## 上下文与事件生产者

`visible_evidence` 取本轮实际注入的消息／任务／记忆来源 ID；不要把整个数据库中的 ID 全部当成可见。记忆摘要必须携带内部来源映射，状态表本身不增加证据列。

默认 `topic_id=general` 可以先运行；这不等于已经做出了主题识别。陪读、代码任务等已有明确会话状态时，复用其场景 ID。没有可靠主题边界时通过证据可见性保守筛选。

`legitimate_feedback` 不应由情绪分数产生。已确认的工具错误、用户指出的具体数值／事实更正与宿主检查结果可以作为纠错依据；真实输入仍需回放检查误判。敌意状态只是一项内部反应，即使误触发也不得覆盖 Soul 中的正常沟通约束。

宿主事件一次最多 8 条。超过上限必须由宿主按照明确的事件合并政策拆分或压缩后再提交，不能在核心里截断任意前八条。推送同一故障的多个通知不得变成多个新证据。

现有感知模型的输入格式保留原约定：最近 3 条 context 使用 `[unused1]{speaker}:{text}`，当前条使用 `[unused2]{speaker}:{text}`，空格连接；最大长度登记为 96。分词器与截断行为跟随实际旧实现，不因本次设计擅自更换特殊 token 或重排情绪索引。

## 关系、Lore 与生成边界

关系来源是已确认档案或独立长期关系模块。不存在来源时输出未确定；不要用聊天次数创建假定信任，更不要因用户离开衰减长期关系。

关系记录在本轮同样冻结。原型的范围检查将不适用当前议题的专门信任标为未确定，不把一个领域的能力信任扩成整体信任。

选中 Lore ID 之后保存最终文本或版本化引用，以免同一轮重试时因世界书被编辑而得到不同约束。可选条目的字符预算只作粗筛，实际 token 预算仍服从现有编译器。固定 Soul 不能因为可选条目多而被挤掉。

生成层收到当前原文、必要上下文、Soul、三列表格和已选条目。不要把所有候选状态、连续参数、未选条目或调试日志一并注入。

## 移植验证

`golden_cases.json` 包含 7 个独立序列、50 次观测。每个序列从指定初始人格与时间开始，按序输入，不得逐步重置状态。

- `rows` 的三个字段、行顺序与离散档位完全一致。
- fast、mood、trace strength 绝对误差不超过 1e−10。
- 痕迹依据和范围一致；证据集合的内部顺序差异不应改变语义，保留最近四条的策略必须一致。
- 固定同分排序使用 ID 的字典序，禁止因 Map 枚举顺序产生不稳定结果。
- 保持 `exp`、`expm1` 的精度；禁止改成每帧 Euler 积分后仍声称移植等价。

golden cases 由参考实现生成，用于发现移植偏差，不能单独证明理论正确。因此还要保留 `tests/test_modulator.py` 中的独立 RK4、时间分段、有界压力、主体区分及事务场景测试。

本轮已完成这些参考测试，Android 数据库事务、进程重启、输入修订、真机功耗和真实模型误判仍由集成阶段验收。没有 Kotlin 构建产物随包交付。
