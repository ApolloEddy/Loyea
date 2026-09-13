# Companion Intelligence Preflight(陪伴智能接入 P0 核验报告)

日期:2026-09-14 · 分支:`feature/companion-intelligence-integration` · 基线:main@d3c6c5e(完整 SHA `d3c6c5ea7ab5ca053a5e8ee4ae694ce4643b1575`)
Spec:`docs/Loyea_Companion_Modulator_Perception_Integration_Spec_v1.0.md`

## 1. 现状核验

- 工作区在开工时仅有两个用户未跟踪文件:Spec 本体与 `AprilPerceptionDemo-standalone-2.1.4.html`。未发现其他未提交修改。
- 环境实测:JDK 17.0.17(Microsoft LTS)、Android SDK 位于 `D:/Dev/Android/Sdk`、Gradle wrapper 8.13(腾讯镜像 distributionUrl)、Python 3.8.10 可用。`adb` 不在 PATH,需用 SDK 全路径(与既有诊断记录一致)。
- 构建现状:`settings.gradle.kts` 包含 `:app`、`:character-core`、`:loyea-modulator-core`;companion 插件以 sourceSets 源码目录并入 `:app`(`app/build.gradle.kts:67-76`),方向为插件→宿主 import。app 无 onnxruntime 依赖、无 SQLite/Room(全部 JSON + SharedPreferences)。
- 版本号 `0.8.2 / versionCode 20` 未动(按用户规矩,版本号只在用户指令时修改)。

## 2. 真实模型制品(P0 提取与核验)

来源 HTML 实测 SHA-256 `a7dd693856d4c686690f3907f37c732f4af4017c9d19aef9665f18cc3303bd9c`、47,693,438 字节,与 Spec §3.1 登记完全一致。提取脚本 `tools/extract_perception_artifacts.py`(确定性、可复跑),产物与 manifest:

| 制品 | 字节 | SHA-256(均与 Spec 登记一致) | 落地路径 |
|---|---:|---|---|
| model.onnx | 24,085,508 | `a3e763f4bad5e4e9…84419cb` | `app/src/main/assets/perception/model.onnx` |
| tokenizer.json | 439,753 | `79a7b606f3b655b1…67ed4ac0` | 同目录 |
| metadata.json | 4,219 | `c85279af4f912cfb…429a6e2853` | 同目录 |
| calibration.json | 953 | `e4c2829e9b8dcd91…5fb15eadd` | 同目录 |

manifest:`docs/audits/perception_artifacts_manifest.json`。`embeddedOrtWasm`(14.9MB web 运行时)按 Spec 不打入 Android。

静态核验补充(与 Spec §3.2 逐项对上):
- tokenizer.json:WordPiece,vocab 21,128,`lowercase=false`,BertNormalizer/BertPreTokenizer;`[PAD]=0 [unused1]=1 [unused2]=2 [UNK]=100 [CLS]=101 [SEP]=102`;truncation Right/96,padding Right/pad_id 0。
- metadata.json:`schemaVersion 2.1.0`,12 头共 82 logits(`headOrder/headSizes` 与 `legacy_schema.json` 一致),`maxLength=96`,`export.q8Bytes=24085508`,历史指标 `baseMacroF1=0.3751 / actMacroF1=0.4425`。
- calibration.json:`schemaVersion 2.1.0`,`per-label-vector-scaling`,仅 `basePresence` 八情绪有 temperature/bias。

## 3. 原解码管线符号定位(移植依据)

主逻辑在 HTML 内联 script #6(压缩)。定位:`ji`=join(偏移 56159)、`mt`=WordPiece 编码器 class(49042)、`Fi`=decoder(54475)、`hn`=语法修正(51261);`Qo/Xo/Yo/Ko/Zo`=预分词与归一化(48250–49045)。逐字参考副本存于 `docs/audits/perception_reference/*.js`。移植要点:

1. **拼接 `ji`**:`[unused1]{speaker}:{text}`(context 取 `slice(-3)`)+ `[unused2]{speaker}:{text}`,空格连接;当前 speaker 默认 `speaker_0`。
2. **归一化 `Xo`**:NFC;剔除 `\0`/U+FFFD/控制符(`\t\n\r` 保留,其余 Cc/Cf 删除);空白→空格;`lowercase=false`(本模型)、stripAccents 关闭。CJK 判定 `Yo`:码点 13312–19903、19968–40959、131072–195103、63744–64255 两侧加空格。标点切分 `Zo=/[\p{P}]/u` 单独成 token;`[unused1]/[unused2]` 在 `Qo` 开头被原样保护。
3. **WordPiece `mt`**:vocab 直查;否则贪心最长匹配(`##` 续);片段 >100 字符或无法匹配→整体 `[UNK]`。`encode`:tokens 截到 94,`[CLS]+tokens+[SEP]` 右 pad 到 96;`input_ids/attention_mask` 均 int64 `[1,96]`,读取 `logits`。
4. **解码 `Fi`**:按 `xa` 头序切 82 logits;`{dialogueAct,speakerAffectTarget,utteranceFactuality,referenceEmotion,referenceHolder,referenceFactuality}` 走 softmax,其余走 sigmoid。`basePresence` 每情绪:`rawProbability=sigmoid`;`calibratedProbability=sigmoid(logit(raw)/T+bias)`(logit 截断 [1e-6,0.999999]);tuning(页面默认恒等 `{temperature:1,bias:0,policyWeight:1}`,`labels:{}` 存 localStorage)→`effectiveScore=calibrated*policyWeight`。`dimensions.valence/dominance` 做 `*2-1`(合法负值)。`referencedAffect`:present≥0.55 时 `confidence=present*cbrt(emo*holder*fact)`。`ambiguity`=归一化熵。`schemaVersion:"2.1.1"`。
5. **语法修正 `hn`**:只作用于当前句文本(`analyze` 的 `t.text`,非拼接串)。情绪词表 `ei` 七组正则;holder 判定 `ii`(取匹配前缀最后出现的 我/你/第三人称);factuality 判定 `si`(疑问/转述/否定前缀)。效果:asserted 的 speaker 词 `effectiveScore=max(.,0.82)`(厌恶词除外);negated 全部 `*=0.18` 且 neutral≥0.62;第三人称词写 `referencedAffect`(source=`high_precision_grammar`)且无 asserted 直述词时该情绪 `*=0.15`、neutral≥0.66、`attributionAdjusted=true`;最后重算 `dominantEmotion`(按 effectiveScore)。
6. **执行顺序**:拼接→WordPiece→ONNX→校准/解码(`Fi`)→语法修正(`hn`),与 Spec §3.2 一致。语义边界成立:`hn` 不改 `calibratedProbability`,调制器消费的主要是后者(fineStates/stances/emotions 概率、dialogueAct、toxicity),显示主情绪被修正不等于调制器输入被修正。

**tuning 基线裁定**:原 Demo 默认恒等 tuning;Android 侧按恒等配置打包(`docs/loyea_modulator/config/` 增补 `perception_tuning.json`),保留结构供后续调参,不冒充"实测最优"。

## 4. 调用图与存储边界(基线事实)

- 聊天主链:`ChatViewModel.sendMessage`(:1077)→ `mergeAndSaveMessages`(:2631)→ `startAiResponseStream`(:1448)→ `PromptAssembler.assemblePromptParts` → `LlmConversationBuilder.selectWithinBudget/build` → `LlmClient.sendChatCompletionStream`。工具续轮 while(maxRounds=5)在流内。发送互斥仅 `responseJob?.isActive` 软检查;`inputRevision` 不存在;`sessionIncarnationId` 运行期从不生成(仅迁移保留);`bindingRevision` 仅 `CompanionDataOps.restore:74` 递增、`GreetingWorker:117-118/220-225` 做围栏。
- 快照机制:动态上下文经 `Message.llmContextSnapshot` 固化到用户消息(`persistLlmContextSnapshot` :2662),历史快照一旦存在即逐字复用(:1581);`LlmConversationBuilder` 在 USER 消息前注入 sanitized 快照(:71-81)。
- 存储:`ChatStorageManager` JSON 原子写(最终回退会直接覆盖正式文件,`:272-305`);进程级静态 Mutex + 实例级 `cachedSessionIds`;世界书 `WorldInfoLibrary` 会话书→卡书→全局书回退;`CharacterCompiler.prepare` + `WorldInfoMatcher.worldInfoRenderFor` 为可复用渲染;`estimateTokens` 为启发式(CJK 0.5/ASCII 0.25)。
- 调制器:`Modulator.process/idleTo/dumps/loads/compileRows/selectLore`,`version="1.0.0-prototype"`,golden fixture 1e-10;`Trace(kind,target,topicId,strength,halfLife,evidenceIds,lastEventAt)` 聚合强度——§7.1 归因缺口确认存在;`compileRows` 写 `labelLevels` 滞回——§7.2 纯投影缺口确认存在。`AppraisalEngine.appraise` 已实现七条路由并检查 `2.1.0/2.1.1` 版本对。
- 陪伴:`CompanionConfigStore` 单 key JSON(`perceptionEnabled`=物理感知总开关;`useSystemTime` 载体在 `ChatSession`);`CompanionProactiveGate`(每日 2 条/≥4h/静默 30min/DND);`GreetingWorker` 陪伴/普通双路径,写回围栏 sessionId+bindingRevision;`CompanionBackupCodec` v2;`CompanionDataOps.restart/restore`。

## 5. 必要旧缺陷处置(P0 认领清单)

| 缺陷 | 处置阶段 |
|---|---|
| `atomicWrite` 失败回退直接覆盖正式文件(ChatStorageManager:298) | P2:保留旧文件的原子替换,失败向上传递 |
| `cachedSessionIds` 实例缓存成为唯一判据 | P2:陪伴写入统一按 runtime Store owner/incarnation 核验 |
| 发送互斥仅软检查(连点窗口) | P2:协调器 Mutex + observation 唯一键 |
| 图谱保存吞异常、恢复无法判断失败 | P5:staging 写入返回真实成败,失败不报恢复成功 |
| 问候开关运行时启用不生效 | P5:配置提交入口确保唯一任务 |
| 图谱抽取水位/旧批次重插(上一轮遗留) | 本轮不触及该写入路径,保持遗留登记 |

## 6. 依赖裁定

- ONNX Runtime:`com.microsoft.onnxruntime:onnxruntime-android` 固定版本(实现期锁定并实测,P3 报告具体版本与下载来源);不使用 `+`。
- 其余全部复用现有依赖;不为接入升级 Gradle 工具链。

## 7. 阶段计划与门禁

按 Spec §13.1 P1→P6 推进;门禁映射:P1 收口 A13–A15/A36 + 数学问归;P2 收口 A03–A07/A23–A28 行为可复现;P3 收口真模型通路一致性(缺制品则 BLOCKED——本卡已提取,不适用);P4 收口 A01/A02/A08–A22/A35/A37;P5 收口 A29–A34;P6 交付性能/语义集/对照/APK/验收报告。
