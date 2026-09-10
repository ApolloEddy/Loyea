# Loyea 调制器 v1.0 实现包

先看 `Loyea_Modulator_Spec_v1.0.md`。它已经决定模型、参数和交互方式，状态表保持先前 v0.2 的四个 aspect、三列与词义约定。

| 文件 | 用途 |
|---|---|
| `Loyea_Modulator_Spec_v1.0.md` | 完整设计与验收依据 |
| `prototype/modulator.py` | 确定性参考实现，Python 标准库，无网络和模型下载 |
| `prototype/demo.py` | 三轮合成输入演示 |
| `tests/test_modulator.py` | 34 项场景与数值验证 |
| `config/defaults.json` | 从代码导出的参数登记；未实现任意热加载 |
| `config/state_dictionary.json` | 四个 aspect、31 个候选感受及实现状态 |
| `config/legacy_schema.json` | 已核对的旧模型输出头和版本登记 |
| `integration/Kotlin_Integration.md` | Android 接入位置、时钟、事务和重试约定 |
| `integration/golden_cases.json` | 7 组、50 次观测的移植对照数据 |
| `validation/Validation_Report.md` | 已运行测试与计时范围 |
| `validation/dynamics.png` | 与 CSV 对应的连续轨迹图 |
| `reference/Loyea_State_Representation_Spec_v0.2.md` | 先前状态表示层原文，未经修改 |

从解压后的目录运行，推荐 Python 3.10 及以上；本轮实际测试版本为 3.12.14：

```bash
python3 prototype/demo.py
python3 -m unittest discover -s tests -v
python3 validation/run_validation.py
```

第三条会重建配置登记、回放数据、测试日志和性能报告。绘图使用可选 matplotlib；缺少它仍能完成测试、计时、CSV 与 JSON 生成。核心和测试无需第三方依赖。

所有演示、计时和测试都使用人工构造的感知结果／事件，不代表已经运行旧情绪模型或 LLM。计时不代表 Android 真机表现。旧模型权重与大型 HTML 不在包内重复分发。

这份包是可运行的设计参考和 Kotlin 移植依据，尚未合入 Loyea 仓库。核心参数属于工程初值；先完成集成回放，再进行真实对话与设备验收。
