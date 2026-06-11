# 代码审查报告 — Review Report

**审查结论**: REQUEST_CHANGES

## 一、 审查要点汇总与结论

| 审查维度 | 具体内容 | 结论 | 备注 |
| :--- | :--- | :--- | :--- |
| **1. 依赖引入** | `app/build.gradle.kts` 中引入 `okhttp-sse` 及单元测试 Mock 库 | **PASS** | 引入版本对齐（4.12.0），无版本冲突风险，测试库配置正确。 |
| **2. 损坏自愈** | `McpConfigStorage.kt` 损坏自愈逻辑是否无缝 | **PASS** | `try-catch` 包裹反序列化，检测到异常自动 `remove` 脏配置键，防启动崩溃。 |
| **3. 协程挂起与退避** | `McpClient.kt` 挂起与 `McpManager.kt` 指数退避（Jitter）及网络状态监听是否健壮 | **FAIL** | 存在**协程/内存泄露**以及**随机抖动（Jitter）计算偏差**两项问题，需修改。 |
| **4. UI 美学与动画** | `SettingsScreen.kt` 的莫兰迪美学及折叠动画细节是否恰当 | **PASS** | 琥珀黄状态灯呼吸动效良好，莫兰迪配色极佳，高阻尼弹性展开折叠动效体验顺滑。 |

---

## 二、 发现的问题与改进建议 (Findings)

### 1. 关键缺陷：`McpManager` 存在协程/内存泄漏 (Coroutine & Memory Leak)
* **位置**：`app/src/main/java/com/loyea/mcp/McpManager.kt`，第 135-139 行：
  ```kotlin
  // Monitor status changes
  coroutineScope.launch {
      newClient.status.collect {
          updateServerStates()
      }
  }
  ```
* **问题描述**：`newClient.status` 是一个 `StateFlow`。在 Kotlin Coroutines 中，调用 `Flow.collect` 对 `StateFlow` 进行收集是一个**无限挂起**的过程，除非收集它的协程作用域被取消，或者当前收集协程被显式取消。
  在 `updateConfigs(newConfigs)` 中，当 MCP 服务器配置被停用、删除或替换时，原有的 `McpClient` 实例会被调用 `disconnect()` 并从 `activeClients` 中移出，但是上面启动的 `collect` 协程**从未被主动取消**，且它的 Job 没有在任何地方被保存。因此，该收集协程将一直挂起在内存中，并持续持有已被废弃的 `McpClient` 强引用，导致内存泄漏与协程累积。
* **改进建议**：
  引入一个管理状态监听 Job 的容器：`private val statusJobs = ConcurrentHashMap<String, Job>()`。
  当添加新客户端时，将 Job 存入：
  ```kotlin
  statusJobs[config.id]?.cancel()
  statusJobs[config.id] = coroutineScope.launch {
      newClient.status.collect {
          updateServerStates()
      }
  }
  ```
  并在客户端被停用/删除/替换时，调用 `statusJobs[id]?.cancel()` 并 `statusJobs.remove(id)`。

### 2. 重要问题：`McpManager` 指数退避的 Jitter 计算存在负向偏差 (Negative Jitter Bias)
* **位置**：`app/src/main/java/com/loyea/mcp/McpManager.kt`，第 185-188 行：
  ```kotlin
  // Add Jitter (+/- 10%)
  val jitterRange = (baseDelay * 0.1).toLong()
  val jitter = if (jitterRange > 0) random.nextLong() % (2 * jitterRange) - jitterRange else 0L
  ```
* **问题描述**：在 Java/Kotlin 中，`random.nextLong()` 可以返回负数。使用 `%` 取模运算符时，若被除数为负数，结果也将是负数或零。
  例如，若 `baseDelay = 2000`，`jitterRange = 200`，`2 * jitterRange = 400`：
  - `random.nextLong() % 400` 的输出范围是 `[-399, 399]`。
  - 再减去 `jitterRange (200)`，计算得到的 `jitter` 实际范围是 `[-599, 199]`。
  - 这种算法不仅导致抖动范围不是宣称的 `[-10%, +10%]`（实际是 `[-30%, +10%]`），而且导致抖动的期望值为**负数**（平均比 `baseDelay` 减少了约 10% 的时间）。这违避了指数退避防止“雷鸣群涌（Thundering Herd）”的初衷，容易导致过多重试堆积在较短时间间隔内。
* **改进建议**：
  使用 `random.nextDouble()` 产生对称的 `[-0.1, +0.1]` 抖动：
  ```kotlin
  val jitter = if (jitterRange > 0) {
      ((random.nextDouble() * 2 - 1.0) * jitterRange).toLong()
  } else 0L
  ```

---

## 三、 亮点与可取之处 (Key Strengths)

1. **自愈鲁棒性极佳**：`McpConfigStorage` 中对反序列化的逻辑隔离十分周密，能够通过直接移除脏配置恢复空列表状态，完美规避了低版本升级或数据损坏时导致的开机闪退崩溃。
2. **连接池设计清晰**：`McpManager` 采用 `ConcurrentHashMap` 管理客户端连接池，能根据最新配置动态决定连接的启动与释放，并对网络重连逻辑进行了网络感知性挂起（通过 `first { it }` 挂起），相比传统的定时重试轮询，极大地降低了电量和 CPU 开销。
3. **UI 细节充满美感**：莫兰迪色系的呼吸灯，在高阻尼弹性 spring 阻尼配置下的 `expandVertically` 折叠动画过渡非常柔和。且支持中英双语的交互切换和 `FlowRow` 响应式展示，符合 Modern Android UI 设计标准。

---

## 四、 验证过程 attestation

- **静态审查**：已对相关 Kotlin 代码及 Build 依赖进行全量静态走查。
- **动态运行测试验证**：在 Windows 环境下发起构建与单元测试，由于缺少本地环境交互确认产生超时未批准，测试详情标记为“待真机集成验证”（见交接文件）。
