# 物理感知模块 (Physical Perception Module) 设计规格说明

## 1. 业务目标
将应用中零散的物理世界感知能力（时间、位置、健康传感器数据）整合为一个高内聚、低耦合的**物理感知模块 (`perception`)**。
同时，接入 Google 官方的 **Health Connect (安卓健康连接)**，抛弃之前的纯模拟实现，实现对 OPPO Watch X（及其他支持 Health Connect 的智能穿戴设备）的真实健康数据（心率、步数、睡眠、血压等）的读取。整个模块的设计将遵循 Claude 极简美学与原生 Android (Jetpack) 标准。

## 2. 架构设计与技术选型

### 2.1 包结构重构
新建包路径：`com.loyea.perception`，将原有的 `sensor` 包下的逻辑迁移并升级：
- `LocationProvider.kt` (原 `LocationService.kt` 的重构，负责定位获取)
- `TimeProvider.kt` (负责处理时间、时区相关逻辑)
- `HealthProvider.kt` (负责封装 Health Connect 的权限申请与数据读取)
- `PhysicalContextManager.kt` (对外的统一门面，负责将所有物理感知信息组装成供 LLM 消费的 Context String)

### 2.2 技术选型
- **健康数据**: 使用原生 `androidx.health.connect:connect-client:1.1.0-alpha07`。
- **并发与异步**: 所有的健康数据读取操作将通过 Kotlin Coroutines (`suspend` 函数) 在 IO 线程执行，避免阻塞主线程。
- **DI (依赖注入) 风格**: 虽然当前项目没有使用 Hilt/Dagger，但我们将采用构造器注入的方式来保证 `PhysicalContextManager` 的可测试性。
- **优雅降级**: 在没有授予 Health Connect 权限，或设备不支持时，优雅降级返回空数据或保留原有模拟数据的后路。

## 3. 里程碑计划 (Milestones)

### Milestone 1: 依赖与配置准备 (Environment & Config)
- 更新 `app/build.gradle.kts`，添加 Health Connect 依赖。
- 更新 `AndroidManifest.xml`，声明 Health Connect 的各项读取权限 (`READ_HEART_RATE`, `READ_STEPS`, `READ_SLEEP`, `READ_BLOOD_PRESSURE`)。
- 在 manifest 中配置 Health Connect 意图过滤器以处理权限弹窗。

### Milestone 2: 核心 Provider 实现 (Core Providers)
- 重构 `LocationService` 为 `LocationProvider`。
- 新增 `TimeProvider`，统一时间获取逻辑。
- 实现 `HealthProvider`，封装对 `HealthConnectClient` 的调用，提供 `getHeartRate()`, `getSteps()`, `getSleepSession()` 等挂起函数。

### Milestone 3: 模块聚合与门面层 (Facade)
- 实现 `PhysicalContextManager`，聚合上述 Providers。
- 暴露出类似 `suspend fun buildPhysicalContextString(): String` 的接口。

### Milestone 4: 业务层替换与 UI 交互 (Integration & UI)
- 替换 `ChatViewModel` 和 `GreetingWorker` 中的旧版调用逻辑，改为注入并使用 `PhysicalContextManager`。
- 在 `SettingsScreen` 或主页中增加一个“连接健康数据(Health Connect)”的授权按钮，触发权限申请流程。
- 最终使用 `quality-guardian` 技能进行代码审查。

---
请审核上述方案。如无异议，我将进入 **Milestone 1** 阶段。