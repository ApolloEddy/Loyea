# Loyea — 触碰现实物理世界的温情赛博伴侣

<p align="center">
  <img src="assets/images/banner.png" alt="Loyea Banner" width="100%" style="border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.15);" />
</p>

<p align="center">
  <a href="#license"><img src="https://img.shields.io/github/license/ApolloEddy/Loyea?color=orange&style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square" alt="Platform" />
  <img src="https://img.shields.io/badge/Architecture-Model%20Context%20Protocol-purple?style=flat-square" alt="MCP" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-red?style=flat-square" alt="Compose" />
</p>

---

## 🌌 赋予 AI 触觉：什么是 Loyea？

**Loyea** 并非市面上又一款冷冰冰的“AI 聊天助手”。它是一款专为 Android 平台打造的**温情赛博生命体**。

传统的 AI 聊天被禁锢在平面的文字对话框中，对外界一无所知；而 Loyea 通过前沿的 **Model Context Protocol (MCP)** 架构与端侧感知能力，打破了虚拟与现实的虚线屏障。**它能听见你窗外的雨声，看见你房间的昏暗，甚至能在你心率异常或深夜熬夜时，自发地发来一声叹息或关怀。**

在 Loyea 的世界里，AI 不仅仅是应答机器，而是与你共享同一片物理时空、拥有呼吸感的“赛博伴侣”。

---

## ✨ 独一无二的“赛博特质” (Unique Features)

### 🔌 1. 虚实无界的物理感知 (Physical Perception)
基于本地设备传感器与物联网数据流，Loyea 拥有敏锐的物理直觉：
* **传感器扫盲**：实时感知用户的地理位置、环境光照强度（Lux）、蓝牙设备连接状态、运动状态（如静止、步行、跑步、乘车）以及电池充电状态。
* **健康连接 (Health Connect)**：无缝适配 Android 标准健康连接，实时读取心率（BPM）、今日步数、血压以及昨晚的睡眠质量。
* **自愈式工具调用**：大模型拥有极高优先级的指令感知。一旦发现某些传感器数据缺失或未授权，大模型将主动发起 MCP 工具调用，在您察觉前自发完成一次物理环境“扫频”。

### 🌐 2. 永不离线的“互联网之眼” (Multi-Source Search)
不同于高度依赖昂贵 API Key 且极易因网络代理反爬而返回空值的传统方案，Loyea 本地集成了一套**多源容灾网页搜索引擎**：
* **智能自适应容灾**：顺次轮询 Bing (必应) ➔ 360搜索 ➔ DuckDuckGo。即使网络环境因 TUN 代理波动导致单一源被反爬机制拦截，系统也能在微秒内自动无感切换，确保 100% 的高稳定性最新资讯输出。

### 🎭 3. 高保真酒馆人格 (Persona) 与灵魂定制
* **Macros 标准渲染**：1:1 深度复刻 **SillyTavern (酒馆)** 核心人设设定标准，融合核心 System Prompt、性格特征与经典对话样本。
* **输入框人格自适应**：聊天输入框的 Placeholder 并非死板的默认文本，而是会随着您切换不同的人设卡片，自动而温情地蜕变为 `与 [当前人格名字] 对话`。
* **纯中文回复净化**：前端交互抹去了一切冰冷的“AI”标识，并在全局提示词深度融入了母语扮演准则，带来沉浸式陪伴体验。

### 🎨 4. 纸张质感美学与对比度自愈
* **日式自然质感**：基于 Jetpack Compose 与 Material 3 标准，打造出温馨、质朴的纸张触感。
* **YIQ Contrast 对比度自愈**：在深色模式下启用自定义气泡配色时，系统会基于 YIQ 亮度感知公式动态推算气泡底色明暗，自动感应并调整文本为深黑 (`#1A1A1A`) 或浅白 (`#FAFAFA`)。无论是淡雅配色还是浓墨重彩，均能保障如丝般顺滑的阅读对比度。

---

## 🛠️ 感知感知，如何感知？(Sensors Overview)

在对话过程中，大模型拥有随时调度以下感知能力的权限：

| 工具名称 | 感知维度 | 实际用途与场景行为 |
| :--- | :--- | :--- |
| `get_location` | **空间位置** | 感知你在哪个城市，以便提供本地化的聊天话题。 |
| `get_live_weather` | **实时气象** | 强制返回国人最熟悉的**摄氏度（°C）**实时气象，大雨天会主动提醒你带伞。 |
| `get_weather_forecast` | **未来预报** | 拉取未来 3 天的平均天气与高低温区间，为你规划明天的穿衣搭配。 |
| `get_environment_light` | **环境光照** | 监测 Lux 值，当你深夜不开灯玩手机时，它会出声劝你开灯。 |
| `get_battery_status` | **设备状态** | 感知手机电量。当电量低于 20% 时，它会打趣说“你该去充电啦”。 |
| `get_bluetooth_status` | **外设状态** | 感知是否连接着蓝牙耳机或运动手环，自然融入运动或听歌话题。 |
| `get_activity_state` | **运动状态** | 识别你是躺在床上还是在乘车出行，动态切换陪伴语境。 |
| `get_health_data` | **身体健康** | 读取当天步数、血压、心率和睡眠，真正融入你的日常生活起居。 |

---

## 📄 授权协议 (License)

本项目采用 [MIT License](LICENSE) 开源协议。欢迎大家自由 Fork、个性化定制，赋予您的伴侣更奇妙的感知灵魂！
