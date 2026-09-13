# 调制器核心 v1.1.0 证据归因修订:新旧差异说明

Spec:《Loyea_Companion_Modulator_Perception_Integration_Spec_v1.0.md》§7。
修订日期:2026-09-14 · 基线:1.0.0-prototype(golden 1e-10 全绿) · 结果:v1.1.0 全部 39 个核心测试通过。

## 1. 修订内容(语义裁定)

1. **证据分量**:痕迹组键仍为 `(kind, target, topicId)`,组内最多 4 个分量,每个分量保存自己的 `evidenceId / strength / lastEventAt`。新分量以该事件经过 gate、人格增益和不应期后的幅度 `q` 初始化。
2. **独立衰减**:`evolve` 时各分量按所在组 halfLife 独立衰减,弱分量(< TRACE_PRUNE)剪除,全部分量消失时整组移除。**与 v1.0"聚合后衰减"不等价**(Spec 明确的语义修订):设组内分量 c1..cn,v1.0 是 `decay(1-Π(1-ci))`,v1.1 是 `1-Π(1-decay(ci))`,后者 ≥ 前者(弱分量不再被按比例压扁)。
3. **组强度**:`min(TRACE_CAP, 1-Π(1-c))`,由分量实时推导;排序键与 v1.0 一致(`-组强度, kind, target, topicId`)。
4. **重复接纳拦截**:组内已存在的 evidenceId 再次到达时,审计 `duplicate_evidence_ignored:<id>`,不合并、不加强度。
5. **显式修复**:`resolves` 只衰减被点名的分量(`1 - RESOLUTION_DAMP * q`);`Context.explicitResolutionLinks` 为宿主显式关联输入,空集时道歉不枚举话题内全部敌意证据(审计 `repair_without_explicit_link:resolution_deferred` / `repair_link_unmatched:resolution_deferred`),修复事件本身照常产生。
6. **可见感受**:感受强度只由 `visible` 集合内的可见分量计算(noisy-OR);隐藏分量不泄漏(A14)。纯投影 `project(context, visibleEvidence)` 不使用 `process` 的"自动补入当前事件"行为。
7. **纯投影**:`Modulator.project / projectFromCheckpoint` 在副本上编译,seq/at/痕迹/量化器/lastDecision 全部不变;返回 `StateProjection(rows, sources, relationAvailable)`,sources 键 `aspect/state` 值为支持证据 id。
8. **检查点**:版本 `1.1.0`,trace 序列化为 `components`;`1.0.0-prototype` 只能迁移载入(清除痕迹/滞回/感受选择,保留 fast/mood/at/人格/lastSeq/lastEventId/moodLabel/last_decision),显式报告 `LEGACY_TRACE_ATTRIBUTION_RESET`。

## 2. fixture 差异逐项归因

原 v1.0 fixture 保留于 `integration/golden_cases.json`(资源)与 `docs/loyea_modulator/integration/golden_cases_v1.0_archival.json`;新语义 fixture 为 `golden_cases_v1.1.json`(含 `legacy_divergences` 机器可读登记)。`GoldenCasesTest.legacyFixtureDivergenceIsBounded` 用同一批 v1.0 输入重放,断言**未登记字段与 v1.0 期望逐位一致**——差异由测试本身保证有界。

实测差异(全部可归因于上述修订,无未解释偏差):

| 序列 | 步骤 | 差异字段 | 原因 |
|---|---|---|---|
| repair_sequence | seq3(道歉) | rows, audit, traces | 显式关联语义:无 `explicit_resolution_links` 时敌意不被衰减(0.4990→0.7836,只受正常时间衰减),审计新增 `repair_without_explicit_link:resolution_deferred`;愤怒分量未衰减导致感受行强度/排序变化 |
| background_and_current | seq2 | rows | 可见感受只算可见分量:当前可见集只含 hostile-2,不再把整组聚合强度带上(very_strong→strong),即 A14 语义 |
| background_and_current | seq3–seq39 | rows, traces.strength | 分量独立衰减 vs 聚合衰减的数学差异 + 4 槽容量淘汰(旧版淘汰只删 id 仍保留强度贡献,新版彻底移除) |
| background_and_current | seq40(humor) | traces.strength | 同上(敌意组强度携带差异) |
| user_distress / third_party_anger / explicit_relationship / guilt_with_evidence / threat_resolution | 全部 | 无差异 | 单分量或事实类修复,语义与 v1.0 完全一致 |

`fast/mood` 全程与 v1.0 逐位一致(刺激幅度公式未变),验证了"修订只改归因簿记、不改动力学"。

## 3. 测试矩阵(核心层)

- `GoldenCasesTest.replayAllSequencesWithinTolerance`:v1.1 golden,7 序列 50 步,误差 ≤1e-10。
- `GoldenCasesTest.legacyFixtureDivergenceIsBounded`:v1.0 输入重放,差异严格限于登记字段。
- `GoldenCasesTest.attributionRevisionProperties`:A13(只衰减被点名的分量)、A14(隐藏强证据不泄漏)、A15(投影十次 dumps/seq 不变)。
- `CheckpointMigrationTest`:旧版迁移显式报告、迁移后无继承感受、v1.1 缺 components 拒绝、未知版本拒绝。
- `ScenarioTest`(31)+ `NumericalTest`(3,含 500 步全人格角点有界性 = A36):继续全绿;test_28 按新宿主契约改由宿主声明可见历史证据,并新增组内分量数 ≤4 断言。
