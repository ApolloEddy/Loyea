# Loyea — 拥有实时物理感知的陪伴型赛博伴侣

<p align="center">
  <img src="assets/images/banner.png" alt="Loyea Banner" width="100%" style="border-radius: 12px;" />
</p>

<p align="center">
  <a href="#license"><img src="https://img.shields.io/github/license/ApolloEddy/Loyea?color=orange&style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-1.9+-blue?style=flat-square" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Compose-Material3-red?style=flat-square" alt="Compose" />
</p>

---

## 🌟 项目简介 (About Loyea)

**Loyea** 是一款专为 Android 平台设计的**陪伴型赛博伴侣**客户端。它突破了传统 AI 聊天软件的平面局限，旨在打造一个**具有实时物理感知能力**且能深度定制人设的桌面数字伴侣。

### 💡 核心设计理念

1. **现实物理感知 (Physical Perception via MCP)**  
   项目基于 **Model Context Protocol (MCP)** 架构设计。通过配置多样化的物理设备 MCP 接口（如温湿度传感器、智能手环、桌面物联网组件），让你的 AI 能够实时感知你身边的现实物理世界变化（例如感知到你在熬夜、房间温度过高或心率异常），并根据环境数据主动发起关怀或与你互动。
   
2. **高度自定义人格 (Cyber Companion & Custom Persona)**  
   Loyea 具备类似于 **Silly Tavern (酒馆)** 和 **Astrbot** 的高度自定义人格设定系统。用户可以为伴侣配置丰富的系统提示词、记忆上下文与性格脚本，创造出独一无二的灵魂伴侣。
   
3. **触手可及的温度与美学 (Premium UI & Micro-interactions)**  
   基于 Jetpack Compose 与 Material 3 规范打造，复刻了温馨极简的日式自然纸张质感（琥珀沙黄、莫兰迪灰等多种自适应配色方案）。界面包含细腻的呼吸动效、思考过程弹性伸缩展示等细节交互，让冰冷的屏幕拥有一丝温暖的触觉。

---

## ✨ 核心特性 (Key Features)

- 🤖 **深度赛博人设配置**：支持导入和完全定制 AI 的说话风格、背景设定及情感阈值。
- 🔌 **物联网/MCP 设备联动**：提供标准的 MCP 物理接口配置面板，轻松与智能硬件、物理外设打通。
- 🎨 **Loyea 精致美学主题**：
  - 动态感知系统暗色模式的 `LoyeaTheme` 视觉体系。
  - 用户可自选用户消息气泡配色，文字对比度随底色自适应变化。
- 🌐 **全局中英文自适应**：整个 App 针对中文和英文进行了深度的本地化和语言一键无缝切换。
- ⚙️ **灵活的大模型连接**：内置 Kimi (Moonshot)、千问 (Qwen)、MiniMax、MiMo 等主流 API 预设模板与模型智能填充 Chip，支持自由配置自定义端点。
- 🛡️ **高稳健运行机制**：打通了端侧上下文防抖、深度去重以及内存状态防脏逻辑，确保长时间对话不白屏、不丢失会话上下文。

---

## 🛠️ 技术栈 (Tech Stack)

* **开发语言**：Kotlin 1.9+
* **UI 框架**：Jetpack Compose (声明式 UI，完全基于 Jetpack Material 3 设计)
* **网络请求**：Retrofit / OkHttp
* **数据持久化**：SharedPreferences & Room Database
* **导航管理**：Navigation Component for Compose
* **异步并发**：Kotlin Coroutines & Flow

---

## 🚀 快速开始 (Quick Start)

### 1. 克隆本项目
```bash
git clone https://github.com/ApolloEddy/Loyea.git
```

### 2. 导入与编译
1. 使用 **Android Studio Iguana** 或更高版本打开本项目。
2. 配置好您的本地 Android SDK。
3. 同步 Gradle 项目并直接在真机或模拟器上运行 `app` 模块。

### 3. 配置赛博人格与物理感知
1. 启动应用后，点击左上角菜单栏，进入**设置 (Settings)**。
2. 在 **API 接口配置** 中输入您的大模型密钥，或选择内建的服务商模板。
3. 在相关设置面板中，开启物理设备接口，填入对应的 **MCP 服务端地址**。
4. 在个性化设置中，输入你所设计的赛博伴侣的人设词 (System Prompt)。

---

## 📄 授权协议 (License)

本项目采用 [MIT License](LICENSE) 开源协议。欢迎大家自由 Fork、提交 PR 或是用于个性化定制！
