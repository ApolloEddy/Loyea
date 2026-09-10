# Loyea 调制器插件（非侵入式）

按 `docs/loyea_modulator/Loyea_Modulator_Spec_v1.0.md` 实现的"事件评价 + 八维连续状态 + 有界事件痕迹 + 滞回输出"调制器，以**独立插件**形式存在于仓库，尚未接入 Loyea 聊天链路。

## 结构

| 部分 | 路径 | 说明 |
|---|---|---|
| `:loyea-modulator-core` | `plugins/modulator/core` | 纯 Kotlin/JVM 插件核心；`prototype/modulator.py` 的逐行移植；零 Android 依赖 |
| web 调试页 | `plugins/modulator/web` | 单文件原生 HTML/JS 调试页；由 core 内 `webdemo` 包的 JDK HttpServer 承载，通过公共 API 驱动真模型 |

## 非侵入式边界（本插件的验收条款）

- `app/` 与 `character-core/` **零改动**；本插件唯一的仓库接线是 `settings.gradle.kts` 末尾追加的一条 include。
- core 模块有边界门禁任务（`verifyModulatorBoundaries`）：源码命名空间锁定 `com.loyea.plugin.modulator.*`，禁止 import Android/宿主类型。
- 模型文件保持纯净（无时钟/随机/网络）；`webdemo` 包只是调试入口，只用公共 API + JDK HttpServer，不触碰模型逻辑。
- 宿主（Loyea）未来接入只通过 `Modulator` / `Wire` / `ModulatorPlugin` 这层公共 API，见下文"接入路径"。

## 插件契约（宿主视角）

```kotlin
// 元数据
ModulatorPlugin.ID / VERSION / SPEC / LEGACY_MODEL_METADATA / LEGACY_DECODER

// 引擎：纯函数式推进，无内部时钟/随机/IO
val m = Modulator(personality, initialAt)
val decision = m.process(observation)   // 同 event_id+seq 重试返回缓存，不重复推进
m.idleTo(at)                            // 无输入时按经过时间解析恢复
val json = m.dumps()                    // 检查点（版本、人格、状态、痕迹、滞回、最新决策）
val restored = Modulator.loads(json)    // 坏检查点绝不部分载入

// wire 解析（宿主以 JSON 提交观测，结构与 integration/golden_cases.json 一致）
Wire.observation(jsonObject)

// 世界书语义选择（Spec §10；字符预算是粗筛，真实 token 预算仍归现有编译器）
selectLore(decision, context, loreRules)

// 参数登记表（config/defaults.json 的 Kotlin 对应物；不是热更新引擎）
configDictionary()
```

状态表输出保持 v0.2 的 `aspect / state / intensity` 三列接口：关系感情、关系信任、背景心境各 1 行，当前感受 0–2 行。

## 验证

```bash
# 核心单测（35 项 = 31 场景 + 3 数值 + 1 golden 回放）
./gradlew :loyea-modulator-core:test

# web 调试页（仓库根运行；Ctrl+C 停止）
./gradlew :loyea-modulator-core:webDemo
# 然后打开 http://127.0.0.1:8628
```

调试页能力：**回放 Golden Cases**（7 组 50 步，容差 1e-10；浏览器实测最大误差 0.00e+00）、**运行三轮演示**（demo.py 场景）、交互沙盒（8 种合成感知 + 6 种宿主事件 + 时间推进 + 重置），实时显示状态表、f/m 连续量、事件痕迹、审计与历史轮次。

协议：页面持有检查点字符串，每次请求 `POST /api {cmd, checkpoint, ...}`；服务端 `dumps/loads` 往返（无损性已由测试覆盖）后用公共 API 推进，返回新检查点与展示数据。页面可随意改版，不需要重新编译模型。

已通过的移植验证（`core/src/test`）：

- Golden cases 7 组 50 步：rows 完全一致；fast/mood/trace strength ≤1e-10（实测 0 误差）；audit 逐字一致。
- 场景测试（主体区分、否定/假设/转述不触发、中性不重置、纠错不惩罚、玩笑敌意歧义弃权、重试/旧序号/非法输入原子性、人格固定、关系只读、话题切换、修复只衰减关联痕迹、滞回、检查点往返与损坏拒绝）。
- 数值测试（时间分段一致性、独立 RK4 核对解析解、32 人格角 × 500 步有界压力 + 年休眠回归基线）。

## 接入路径（未来，逐条对应 Spec §13）

1. ✅ 核心数学与数据结构移植为纯 Kotlin 模块，跑通 golden cases（本插件）。
2. 接旧感知解码结果（`2.1.0 / 2.1.1` 双版本检查），开放 7 条感知路由（core 已实现路由；宿主负责 ONNX 解码）。
3. 宿主接入任务事件、上下文可见性与只读关系视图。
4. 在 PromptAssembler 的本轮快照中挂状态表与已选条目，完成数据库事务与全历史重试复用。
5. 真机与真实对话验收（Spec §12 的三项）。

第 2–5 步全部发生在宿主侧，本插件无需改动即可被采用；这就是"插件化 + 非侵入"的意义。
