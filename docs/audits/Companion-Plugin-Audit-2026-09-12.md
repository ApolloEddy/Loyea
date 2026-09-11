# 陪伴模式插件审计报告（plugin/companion @ 917e397+）

日期：2026-09-12｜分支：plugin/companion（基线 main=d928e16）｜验收环境：Pixel_10 AVD（emulator-5554，1080×2424）

## 1. 交付物

- `plugins/companion/`：插件全部源码（`com.loyea.plugin.companion` 命名空间）+ plugin.json + README（宿主触碰点全量清单）。
- 宿主触碰（最小化）：`app/build.gradle.kts`（srcDir 挂载）、`MainActivity.kt`（模式路由+侧栏/酒馆过滤）、`ChatViewModel.kt`（`ensureCompanionSession` 钩子+世界书短路）、`SettingsScreen.kt`（入口卡+开启浮层）。
- 可安装调试包：`app/build/outputs/apk/debug/app-debug.apk`（截图证据 `build/companion-shots/01–17`）。

## 2. 实测通过项（模拟器真机操作，非推断）

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

- S-08 数据与备份（导出/恢复/重开完整实现）未做；数据管理页未上。
- 语音输入、朗读：陪伴页内入口未放（发送链路已支持 audioUrl 参数）；图片端到端选图受模拟器媒体库为空限制（picker 弹出与宿主共用处理链已验证）。
- 图谱记忆管理视图（MEM-01 适配层）、固定/其他双分组未接入，记忆页如实只呈现核心记忆。
- 感知页逐项授权（PER-01）与主动联系免打扰/限频细化（ACT-01~06）沿用宿主现有能力，未做陪伴专属页。
- NFR-06/07 真机 20 分钟对照、万条消息搜索压测未执行（当前验证机为模拟器）。
- 版本号未动（0.8.2/20，按规矩待用户指令）；分支未推送远端。

## 6. 结论

陪伴模式以插件形态完成最小侵入接入，核心 P0（模式路由、唯一会话、聊天 UI、记忆、查找、设置与关闭）在模拟器端到端跑通并留有截图证据；UI 按用户要求完整复刻 HTML 神经元核心并实现玻璃拟态气泡/输入条，文字高对比保证辨识度。
