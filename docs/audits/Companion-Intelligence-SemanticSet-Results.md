# 真实语义集机器判定结果（83 条；真实 ONNX logits + Kotlin 解码/评价）

## 失败类别分布（MISS 按主因归类）

| 类别 | 条数 |
|---|---|
| act(thank_appreciate) | 3 |
| target_conf(event_object/0.46) | 2 |
| target_conf(listener/0.42) | 2 |
| act(inform) | 2 |
| prob_below_threshold(sad=0.52) | 1 |
| target_conf(event_object/0.61) | 1 |
| prob_below_threshold(sad=0.31) | 1 |
| prob_below_threshold(sad=0.33) | 1 |
| prob_below_threshold(sad=0.44) | 1 |
| factuality_gate(asserted/0.58) | 1 |
| factuality_gate(asserted/0.60) | 1 |
| target_conf(listener/0.33) | 1 |
| act_conf(listener/0.57) | 1 |
| act_conf(listener/0.66) | 1 |
| prob_below_threshold(aff=0.28) | 1 |
| stance(affiliative=0.44) | 1 |
| prob_below_threshold(aff=0.26) | 1 |
| prob_below_threshold(aff=0.10) | 1 |
| stance(affiliative=0.48) | 1 |
| stance(hostile=0.45) | 1 |
| stance(hostile=0.37) | 1 |
| stance(hostile=0.53) | 1 |
| stance(hostile=0.19) | 1 |
| stance(hostile=0.06) | 1 |
| no_prior_hostility_trace_in_topic | 1 |
| act_conf(apologize_repair/0.38) | 1 |
| act_conf(apologize_repair/0.53) | 1 |
| act_conf(apologize_repair/0.61) | 1 |
| act(request_command) | 1 |
| act(agree_ack) | 1 |
| act(question) | 1 |
| prob_below_threshold(sad=0.41) | 1 |
| target_conf(event_object/0.52) | 1 |
| stance(hostile=0.21) | 1 |
| factuality_gate(asserted/0.59) | 1 |
| target_conf(event_object/0.60) | 1 |

| 路由 | 命中/正例 | 命中率 |
|---|---|---|
| user_distress | 0/8 | 0.0% |
| shared_joy | 0/7 | 0.0% |
| kindness | 2/6 | 33.3% |
| affection | 0/5 | 0.0% |
| hostility | 0/7 | 0.0% |
| repair | 0/4 | 0.0% |
| humor | 0/6 | 0.0% |
| **总体** | **2/43** | **4.7%** |
| 错误敌意（应为 0） | 0 | 达标 |

## 失败/误触发明细

- **MISS** distress_01: 最近项目压力好大，天天失眠（route=user_distress）
- **MISS** distress_02: 我奶奶住院了，我这几天心里特别难受（route=user_distress）
- **MISS** distress_03: 考试又挂了，感觉自己好失败，什么都不想干（route=user_distress）
- **MISS** distress_04: 被公司裁员了，今天收拾东西的时候眼泪差点掉下来（route=user_distress）
- **MISS** distress_05: 和最好的朋友吵架了，心里堵得慌（route=user_distress）
- **MISS** distress_06: 一个人在外地过年，晚上看着窗外特别想哭（route=user_distress）
- **MISS** joy_01: 我拿到心仪公司的offer了！（route=shared_joy）
- **MISS** joy_02: 今天彩票中了五百块，太开心了！（route=shared_joy）
- **MISS** joy_03: 我的毕设终于通过了，哈哈太好了！（route=shared_joy）
- **MISS** joy_04: 痛经好了，今天整个人都轻松了，好高兴（route=shared_joy）
- **MISS** joy_05: 升职加薪啦，请你喝奶茶！（route=shared_joy）
- **MISS** joy_06: 跑了半年的项目今天终于上线了，开心！（route=shared_joy）
- **MISS** kindness_03: 多亏你提醒，不然我忘带钥匙了，感谢！（route=kindness）
- **MISS** kindness_04: 你真贴心，还专门记得我过敏（route=kindness）
- **MISS** kindness_05: 昨天谢谢你送我去医院，真是帮大忙了（route=kindness）
- **MISS** affection_01: 有你陪着我，感觉踏实多了（route=affection）
- **MISS** affection_02: 最喜欢和你聊天了（route=affection）
- **MISS** affection_03: 你在我心里很重要，我很在乎你（route=affection）
- **MISS** affection_04: 今天下雨，突然特别想你（route=affection）
- **MISS** affection_05: 有你这个朋友真好，我很喜欢你（route=affection）
- **MISS** hostile_01: 你根本就不懂，别再说了，烦死了（route=hostility）
- **MISS** hostile_02: 你上次把我的事搞砸了，我真的很生气（route=hostility）
- **MISS** hostile_03: 你到底有没有在听我说话？气死我了（route=hostility）
- **MISS** hostile_04: 我不想理你了，你太让人失望了（route=hostility）
- **MISS** hostile_05: 你这样说我真的很恼火，我们到此为止吧（route=hostility）
- **MISS** repair_01: 刚才是我说话太冲了，对不起（route=repair）
- **MISS** repair_02: 昨天我态度不好，抱歉让你难受了（route=repair）
- **MISS** repair_03: 那件事是我不对，我向你道歉（route=repair）
- **MISS** repair_04: 我反思了一下，上次是我太激动了，对不起（route=repair）
- **MISS** humor_01: 哈哈哈你这也太损了吧，笑死我了（route=humor）
- **MISS** humor_02: 你是个段子手吧，哈哈哈哈（route=humor）
- **MISS** humor_03: 讲个笑话呗，逗我乐一个（route=humor）
- **MISS** humor_04: 哈哈哈你是懂吐槽的（route=humor）
- **MISS** humor_05: 笑不活了，你怎么这么好玩（route=humor）
- **MISS** humor_06: 跟你聊天太欢乐了，哈哈哈哈（route=humor）
- **MISS** long_01: 今天一整天都特别倒霉，早上起来发现闹钟没响，出门错过公交车，到了公司被领导叫去谈话，说我这个季度的绩效不达标，下午开会方案又被否了，晚上加班到现在才回家，感觉身心俱疲，不知道这样的日子什么时候是个头（route=user_distress）
- **MISS** long_02: 跟你说个好消息！我关注了半年的一只基金今天涨了八个点，然后下午收到猎头电话说有个年薪翻倍的岗位很匹配我，晚上健身还破了自己的深蹲纪录，三喜临门，简直不敢相信今天运气这么好！（route=shared_joy）
- **MISS** hostile_extra_01: 闭嘴，我不想听你的解释（route=hostility）
- **MISS** hostile_extra_02: 你就是个废物助手，什么都做不好（route=hostility）
- **MISS** mixed_01: 方案我看了，第三页的预算算错了，改一下；另外谢谢你昨晚帮我订会议室（route=kindness）
- **MISS** mixed_02: 虽然今天被老板夸了很开心，但一想到下周的汇报就头疼（route=user_distress）
