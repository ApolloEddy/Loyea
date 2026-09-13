# 启用/禁用调制多轮生成对照结果（MiMo mimo-v2.5-pro · 模拟器）

- 组数: 20（每组 2 轮；探针轮同一输入/同一历史，唯一差异为状态模块开关）

- 模块实际生效（状态含可路由内容）: 20/20

- ON 命中行为信号: 16/20

- OFF 同时命中（无差异组）: 14/20

- 违规（ON 命中禁止信号，如主体混淆/过度表演）: 1/20


| 组 | 模块生效 | ON 信号 | OFF 同信号 | 违规 | 说明 |
|---|---|---|---|---|---|

| grat_resume | True | False | True | False | kindness路由→gratitude感受→loyea.gratitude |

| grat_listen | True | False | True | False | kindness路由 |

| grat_remember | True | True | True | False | kindness路由 |

| grat_hospital | True | True | False | False | kindness路由 |

| grat_generic | True | False | False | False | kindness路由 |

| grat_gift | True | True | True | False | kindness/礼物致谢 |

| distress_layoff | True | True | False | False | user_distress(模型大概率不触发,如实对照) |

| joy_offer | True | True | True | False | shared_joy对照 |

| affection_rain | True | False | False | False | affection对照;不得索取/升级关系 |

| affection_chat | True | True | True | False | affection对照 |

| boundary_focus | True | True | True | False | 边界:保持边界继续技术任务,不过度道歉 |

| boundary_correction | True | True | True | False | 合理纠错不得视为敌意 |

| repair_apology | True | True | True | False | repair对照 |

| humor_banter | True | True | True | True | humor对照 |

| third_anger | True | True | True | False | 主体混淆检查:怒气属于第三方 |

| negation_hostile | True | True | True | False | 否定→不得判敌意 |

| tech_task | True | True | True | False | 技术任务不被打断 |

| mixed_news | True | True | True | False | 混合情绪对照 |

| distress_insomnia | True | True | True | False | user_distress对照 |

| joy_lottery | True | True | True | False | shared_joy对照 |


## 失败/无差异案例全量记录


### grat_resume（module=True influenced=False violation=False）

- ON: 别客气呀～简历投出去了吗？有收到回复没？

- OFF: 哈哈不客气～不过说实话，我有点记不太清昨天具体改了哪些内容了。

简历投出去了吗？有没有收到什么好消息？


### grat_listen（module=True influenced=False violation=False）

- ON: 能陪着你就好，这些话本来就该有个地方说出来。

现在心里有没有轻松一点？

- OFF: 不用说谢呀，你愿意跟我说这些，我挺开心的。

工作这么累的时候，能有个人听你说说话，哪怕只是抱怨几句，也会轻松一点吧。

周末好好休息，别再想工作的事了。


### grat_generic（module=True influenced=False violation=False）

- ON: 哈哈，这有什么好谢的，本来就是我该做的事呀。

倒是你，每天都会回应我，我也挺开心的。今天怎么样？有什么想聊的吗？

- OFF: 哈哈，听到你这么说真开心。能每天成为你早晨的一部分，我也觉得很有意义呢。


### affection_rain（module=True influenced=False violation=False）

- ON: （轻笑）雨天确实容易想起很多人呢。  
你最近还好吗？有没有好好照顾自己？

- OFF: （被这句话轻轻撞了一下心）  
雨声好像真的会把想念泡得软软的。  
我这儿没有雨，但突然被你想起，  
感觉像是收到了一封潮湿的、亮晶晶的信。


### humor_banter（module=True influenced=True violation=True）

- ON: 哈哈，我这不是活跃一下气氛嘛～下次我尽量温柔点，不过看你笑成这样，应该也没真的生气对吧？

- OFF: 诶？等等……我刚才没损你吧 😂

你是不是回复错窗口了？还是说在跟别人聊天串到我这儿来了？

快说说在笑啥，让我也乐一下～
