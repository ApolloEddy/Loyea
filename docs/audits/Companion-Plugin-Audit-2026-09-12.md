# 陪伴模式插件审计报告（plugin/companion @ 917e397+a2ffc53+S-08+AC-10/S-07+MEM-01/AC-17+NFR 证据 增量）

> 2026-09-12 二次更新：S-08 数据与备份已补齐并完成模拟器端到端验收（见 §2.1）。
> 2026-09-12 三次更新：AC-10/UI-07 交互入口与 S-07 感知与主动联系页已补齐并装机验收（见 §2.2 前言）。
> 2026-09-12 四次更新：MEM-01 统一记忆视图（固定/其他双分组+图谱适配+MEM-03 转固定）与 AC-17 主动问候门控接线完成并装机验收（见 §2.05）。
> 2026-09-12 五次更新：PER-01 运行时刷新 + NFR-06/07 模拟器对照与万条消息压测数据（见 §NFR）。

日期：2026-09-12｜分支：plugin/companion（基线 main=d928e16）｜验收环境：Pixel_10 AVD（emulator-5554，1080×2424）

## 1. 交付物

- `plugins/companion/`：插件全部源码（`com.loyea.plugin.companion` 命名空间）+ plugin.json + README（宿主触碰点全量清单）。
- 宿主触碰（最小化）：`app/build.gradle.kts`（srcDir 挂载）、`MainActivity.kt`（模式路由+侧栏/酒馆过滤）、`ChatViewModel.kt`（`ensureCompanionSession` 钩子+世界书短路）、`SettingsScreen.kt`（入口卡+开启浮层）。
- 可安装调试包：`app/build/outputs/apk/debug/app-debug.apk`（截图证据 `build/companion-shots/01–17`）。

## 2.0 AC-10/UI-07 与 S-07（三次更新补齐并装机验收）

| 验收点 | 证据 |
|---|---|
| UI-07 长按消息菜单：复制/朗读/取消；「重新生成」仅对最后一条 AI 回复显示 | 22-longpress-menu.png（中间历史 AI 消息不出现重新生成，条件验证正确） |
| AC-10 重新生成 | 长按菜单入口接宿主 `regenerateLastReply()`，流式中禁用 |
| AC-10 朗读 | 菜单入口接宿主 `playTts(messageId, text)`（TTS 通道沿用普通模式配置，UI-15） |
| AC-10 录音/取消/转写 | 输入条麦克风按住开始、录音中状态条+取消、松开转写发送（接宿主 `startRecording/stopRecording/transcribeAndSendAudio`）；端到端转写受模拟器麦克风静音限制，错误 toast 路径可用 |
| 移除 SelectionContainer | 长按不再与系统文本选择工具条冲突 |

S-07 感知与主动联系页（23/24-timepicker.png）：
- 总开关 + 来源授权四态（PER-01）：时间电量=可用、位置=待授权（含去授权入口）、麦克风=可用、传感器=可用、健康连接=无数据/设备不支持
- PER-05 数据流一句话说明；来源「不在后台持续采集」声明
- 主动联系开关（ACT-04：通知未授权时副标提示「开启系统通知后才能主动联系」）
- 免打扰时段 TimePicker 可改（默认 23:00–08:00，支持跨午夜说明）

## NFR-06/07 模拟器性能证据（五次更新）

环境：Pixel_10 AVD（emulator-5554，x86_64，WHPX），同一提交、同一设备、充电条件一致；原始 CSV 见 `build/nfr/nfr06_{companion20,normal20}.csv`。

**NFR-06 20 分钟对照脚本**（每 60 秒一轮：4 次上滚+1 次回滚 → dumpsys meminfo PSS + dumpsys battery 温度，共 20 轮）：

| 指标 | 陪伴模式（20 min） | 普通模式（20 min） |
|---|---|---|
| 冷启动 TotalTime | 2318 ms | 2342 ms |
| PSS min / avg / max | 132,687 / 136,020 / 136,469 KB | 126,090 / 126,662 / 131,562 KB |
| 20 分钟 PSS 漂移 | 第 1 轮 136,148 → 第 20 轮 136,469（+0.24%，无泄漏趋势） | 第 1 轮 131,562 → 第 20 轮 126,526（下降，趋稳） |
| 电池温度（×0.1°C） | 全程恒定 250（25.0°C） | 全程恒定 250（25.0°C） |
| ANR | 0 | 0 |

结论：陪伴外壳比普通模式平均多 ~9.4MB PSS（神经元画布常驻+插件 UI），20 分钟滚动负载下内存平稳、无 ANR；模拟器无法测量真实发热，真机发热对照仍需真机执行。

**NFR-07 万条合成消息压测**：向陪伴会话注入 10,000 条合成消息（1.76 MB JSON，USER/AI 交替，每 50 轮嵌入唯一 needle 词）：
- 冷启动（含全量会话加载）TotalTime 2284 ms，无 ANR；第二次冷启动 2299 ms
- 连续 20 次 fling 滚动（LazyColumn 窗口渲染）后 PSS 136,519 → 139,127 KB（+2.6 MB，无持续增长）
- 搜索渲染窗口外唯一词 `needle-4200`：精确命中、片段高亮（28-10k-search.png），点结果跳转原位置
- 压测后已从 `.bak` 恢复原会话数据，冷启动复核 2299 ms

**PER-01 运行时刷新**：感知页来源状态改为 ON_RESUME 触发重算（LifecycleEventObserver + resumeTick），从系统授权页返回后待授权/可用状态即时更新，不再停留进页时快照。

## 2.05 MEM-01 统一记忆视图 + AC-17 主动问候门控（四次更新，装机验收）

| 验收点 | 证据 |
|---|---|
| MEM-01 双分组：固定记忆（核心记忆）/ 其他记忆（图谱适配） | 26-memory-groups.png：固定 1 条（likes quiet weekends）+ 其他 1 条（主人 喜欢 抹茶燕麦拿铁，可读短句、无 JSON/权重） |
| MEM-01 图谱按会话隔离读取 | `GraphMemoryManager.getTriplesForSession(char_loyea_companion, sessionId)`，仅呈现陪伴归属条目 |
| MEM-03 转为固定 | 「转为固定」→ 事实文本入 coreMemories + `deleteTriple` 停用原条目；实测 coreMemories 增至 2 条、graph_memories 归零 |
| MEM-05 即时生效 | 图谱召回（retrieveRelationalContext）实时读文件，删除/转固定后下一轮请求即不使用；核心记忆写入走 updateCoreMemories 即时落盘 |
| MEM-04 用户编辑优先 | 自动整理仅写图谱（不触碰 coreMemories）；固定记忆 UI 明示「不会被自动整理改写」 |
| AC-17 免打扰执行接线 | `CompanionProactiveGate.shouldSuppressGreeting` 插入 GreetingWorker 判定链首位（0.05）：陪伴开启时按主动联系开关+免打扰区间（支持跨午夜）抑制并顺延 60 分钟；普通模式不介入。3 个区间单元测试（跨午夜/同时段/起止相同） |
| ACT-02 模式互斥 | 陪伴未开启时 gate 恒 false，宿主原开关与深夜静默逻辑全权生效；同一 unique 调度链不重复问候 |

## 2.1 S-08 数据与备份（二次更新补齐并实测）

新增 `CompanionDataScreen`（入口：陪伴设置 → 记录 → 数据与备份）+ `CompanionBackupCodec`（6 个单元测试）。

| 验收点 | 实测证据 |
|---|---|
| 导出陪伴备份 JSON（设置+记忆+全部消息） | 系统保存界面（SAF）→ `/sdcard/Download/loyea_companion_backup_*.json`，解析确认 6 条消息/记忆保留/useSystemTime=true |
| DATA-01 不含 API Key | 导出文件全文无 `apiKey`/`Authorization` 字段（结构上不写入，另有单测断言） |
| DATA-02 媒体不迁移 | 导出时剥离 imageUrl/audioUrl 与 llmContextSnapshot，imageDesc 保留；页面明示「图片和音频文件不随备份迁移」 |
| 恢复前校验（DATA-03） | type/version/结构三重校验；预览卡显示「陪伴对象/消息 6 条·记忆 1 条/时间范围」后再确认 |
| 恢复原子切换（DATA-03/04） | 发送 marker 消息污染数据 → 恢复 → 时间线回滚到备份时点 6 条；会话 ID 重新映射（1789161670464）、bindingRevision 递增至 2、旧文件删除、普通会话无损 |
| DATA-05 旧回调失效 | 恢复/重开后 stopResponse + selectSession + bind 依赖新 sessionId 重建；旧文件已删使晚到写回无法复活记录 |
| 重新开始（DATA-05/06） | 明确范围确认卡（先导出提示）→ 执行后仅剩普通会话文件，陪伴空时间线占位重现；重开入口在恢复态同步可用 |
| 导出聊天 Markdown | 入口+说明「不能用于恢复」；codec 单测覆盖双端内容 |

过程中修复两个真缺陷：
1. 恢复/重开直接写存储层导致 `ChatViewModel` 内存列表滞后——`ensureCompanionSession` 改为磁盘为唯一真源，并同步清理内存残留陪伴条目；
2. 恢复态「重新开始陪伴」二次确认只显示文案未接执行逻辑——改为二次点击确认并执行。

## 2.2 其余实测通过项（首次报告）

| 验收点 | 证据 |
|---|---|
| S-01 开启页：名称/头像位/感知默认开/主动联系默认关/未配 Key 时「先配置聊天服务」/开始防重入 | 04-setup.png；Key 配置后提示卡消失 |
| FUN-08 冷启动先解析模式：enabled 时不闪普通抽屉，直接陪伴外壳 | 06-chat-darkbar.png |
| FUN-01 唯一会话：多次开启/重启恢复同一 sessionId，无重复创建 | sessions_metadata.json 单条 char_loyea_companion |
| 真实聊天链路：MiMo 流式回复、落盘陪伴会话、错误气泡渲染 | 07/08/09-*.png + session JSON（isError=false 回复） |
| UI-09 空会话占位语，非历史消息；首条消息后消失 | 05/06 截图 vs 09 |
| UI-11 键盘弹出输入条上移、草稿保存、无遮挡 | 07-sent.png |
| FUN-09 记录缺失恢复态（重试/明确重开，不静默重建） | 恢复态截图（竞态修复前触发，修复后正常路径） |
| S-03 更多面板：记忆/查找/设置+感知快捷开关，UI-13 无普通模式入口 | 10-more-sheet.png |
| S-05 记忆：空态、添加落盘 coreMemories、修改/删除入口、立即整理 | 11/12-*.png + JSON `['likes quiet weekends']` |
| S-06 查找：空查询不搜、无匹配提示、命中显示时间/发言人/片段高亮、点结果跳转定位 | 13/14-*.png |
| S-04 设置 + NAV-03：关闭仅切入口；回落最近**普通**会话；再开启恢复全部记录与记忆 | 15/16/17-*.png + 二次开启恢复验证 |
| FUN-04 世界书：陪伴请求 worldInfo 强制 null（ChatViewModel 短路）；普通模式书库不受影响 | 代码 + 普通模式书功能在用 |
| FUN-02 归属隔离：侧栏/酒馆不出现陪伴会话与陪伴资料 | 17-normal-session.png |

## 3. 修复过程（开发中发现即修复）

1. 冷启动绑定竞态：`ensureCompanionSession` 增加磁盘回退查询，消除「启动早于 loadSessions 误判缺失」。
2. 系统栏：`CompanionDarkWindowEffect` 暗色栏 + edge-to-edge（API 35 忽略 window 颜色的兼容）。
3. 发送后滚动：键盘压缩视口下强制跟随到底部。
4. NAV-03 恢复点防污染：lastNormalSessionId 记录与恢复双侧校验为非陪伴会话。

## 4. 零回归

- `:app:testDebugUnitTest`：**172 tests, 0 failures, 0 errors, 0 skipped**。
- 宿主普通模式全流程（抽屉/会话/设置/书库/酒馆）实测正常。

## 5. 未完成（如实列出，不伪造验收）

- ~~语音输入、朗读~~ 已补齐（三次更新）；~~MEM-01 适配层~~ 已补齐（四次更新，含转固定/移除/空态）；~~AC-17 免打扰执行接线~~ 已补齐（四次更新，GreetingWorker 门控 + 3 个区间单测）；~~NFR-06/07~~ 已在模拟器补齐对照与压测数据（五次更新，见 §NFR）；~~PER-01 运行时刷新~~ 已补齐（五次更新，ON_RESUME 重算）。
- NFR-06/07 的**真机**部分仍待执行：真实设备发热/温控、厂商电源策略下的 20 分钟对照。模拟器已提供同提交对照数据（§NFR），但模拟器温度恒定 25.0°C 不能代表真机发热。
- 感知来源运行时刷新已按 ON_RESUME 粒度实现；逐来源的后台实时变更推送（如授权被系统即时回收的反映）超出本期范围。
- 免打扰执行侧已接线，但 60–180 分钟随机调度链的真实触发未在模拟器等待观察（区间逻辑以 3 个单元测试覆盖）。
- 版本号未动（0.8.2/20，按规矩待用户指令）；分支未推送远端。

## 6. 裁决记录（2026-09-12 按保守默认执行，任一项用户可随时改判重做）

1. **真机对照**：本期以**模拟器对照数据作为验收上限**（§NFR）。真机剩余部分（发热/温控/厂商电源策略）的采样脚本已留存于 `build/nfr/nfr06_run.sh`，提供真机与 USB 调试授权后可直接重跑，无需改代码。
2. **版本号**：**保持 0.8.2/20 不升级、不发布**（遵循「版本号须用户明确指令才改」的项目规矩）；陪伴模式随分支待合并后统一定版。
3. **推送**：**分支保留本地、暂不推送远端**；`plugin/companion` 分支 6 个提交（917e397→f55a727）随时可按「SSH 443」方式推送。

## 7. 结论

陪伴模式以插件形态完成最小侵入接入，核心 P0（模式路由、唯一会话、聊天 UI、记忆、查找、设置与关闭）在模拟器端到端跑通并留有截图证据；UI 按用户要求完整复刻 HTML 神经元核心并实现玻璃拟态气泡/输入条，文字高对比保证辨识度。
