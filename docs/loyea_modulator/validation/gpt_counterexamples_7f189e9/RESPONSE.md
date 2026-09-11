# GPT 反例复现包处置结论（针对 7f189e9）

复现包：`Loyea_Modulator_Repro_7f189e9.zip`（本目录），方法学与数据见包内 `REPRO_README.md` 与两个结果 JSON。GPT 只执行了仓库内 Python 参考实现与网页内联 JS（桩 DOM + 受控 fetch），未编译 Kotlin；Kotlin 分支为静态核对。

六项发现的逐条处置：

## 已修复（web 调试页真 bug，均为页面自身缺陷，不涉模型）

| 发现 | 根因 | 修复 | 回归验证 |
|---|---|---|---|
| `button_fixtures`：「温柔致谢」「道歉修复」按钮无效果 | 页面 fixture 用 `target="event_object"`，而 kindness/repair 感知路由要求 `direct`（target=listener + 置信 ≥0.75 + scene=real，Spec §6.3） | 两个 preset 改为 `target="listener"` | GPT 用参考实现验证：修复后分别产出 感激/中等、释然/强烈；另入 `web/selftest.cjs` fixture 断言 |
| `concurrent`：并发 submit 同基点丢更新 | 两个在途请求携带同一旧检查点，后响应覆盖前者，第一次状态推进丢失 | 页面请求改Promise 队列串行：后一个请求发出时携带前一个响应的最新检查点 | `selftest.cjs` 断言 #2：第二请求 checkpoint = 第一响应 checkpoint，两轮历史齐全 |
| `resetRace`：重置后旧响应复活旧会话 | reset 与在途 process 响应竞争，旧响应最后到达覆盖新检查点 | 会话 epoch 计数：reset 同步递增，过期 epoch 的响应整体丢弃（检查点/历史/渲染均不落） | `selftest.cjs` 断言 #3：旧响应返回后 checkpoint 保持 null → 最终 fresh |

运行 `node plugins/modulator/web/selftest.cjs` 跑 3/3 正确性断言（即 GPT README 所说"修正实现后改写为新的正确性断言"）。

## 判定为 Spec v1.0 既有语义，非实现缺陷，不改码（改动需 Spec v1.1 裁定）

1. **`independent_causes`（独立原因合并）**：同 `(kind, target, topic_id)` 的痕迹按 §8.3 键合并是规格明文（"痕迹键为 (kind, target, topic_id)"）；`threat_resolved` 按"所关联痕迹 ×1−0.55q"衰减也是 §8.3 明文（"只将所关联痕迹乘 1−0.55q，不会清空所有感受"）。报告数字（0.9375 → 0.55078125）与公式逐位吻合。解决 A 后担忧仍在（B 活跃）语义上合理；粒度粗是规格承认的边界（§8.3："不承担完整事件记忆"）。若要按证据独立衰减，需把痕迹键细化到证据级并重生成 golden 契约——属规格版本变更。
2. **`visibility_leak`（可见性泄漏）**：§9.3 的可见性过滤以痕迹为单位（"仅考虑……证据仍出现在本轮生成上下文中的痕迹"），痕迹强度是合并量，强证据 A 的强度经合并后随可见的弱证据 B 一并呈现（0.90025 → very_strong）。参考实现自身如此，golden 契约同此。若要按证据归因强度，同上属规格变更。

`stale`（idle 后状态表未刷新）为设计使然：无新决策时保留上一轮表格，标题时间已更新，不处理。

## 结论

- 报告中可直接落地的 3 个页面缺陷当轮修复并有回归断言；
- 2 个语义项确认与 Python 参考实现逐位一致（Kotlin 0 误差移植因此同样一致），留档为 Spec v1.1 候选议题，等待规格裁定后再动，避免破坏 golden 对齐契约。
