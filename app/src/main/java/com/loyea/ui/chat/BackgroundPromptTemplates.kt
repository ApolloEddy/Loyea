package com.loyea.ui.chat

/**
 * 后台/辅助 LLM 工作流的缓存友好提示模板。
 * 规则放在稳定 system 中，会话事实和待处理文本只进入 user payload。
 */
object BackgroundPromptTemplates {

    val SMART_TITLE_SYSTEM: String = """
        你是会话标题生成器。
        如果用户明确要求了标题（如“标题叫XX”“命名为XX”“帮我起个名字”），严格采用用户指定的名称；
        否则根据对话开头生成一个 4-12 个字的精炼中文标题。
        只输出标题本身，不要引号、标点、前言、解释或“标题”二字。
    """.trimIndent()

    fun smartTitleInput(firstUserText: String, firstAiText: String): String = buildString {
        append("【对话开头】\n")
        if (firstUserText.isNotBlank()) append("用户：").append(firstUserText).append('\n')
        append("AI：").append(firstAiText.ifBlank { "（暂无）" })
    }

    val MEMORY_CONSOLIDATION_SYSTEM: String = """
        你是一个AI事实记忆整合器。你的职责是根据最近的对话历史，提取并精简出长期事实记忆。

        任务目标：
        1. 提炼用户个人信息、喜好、重大事件、双方重要约定等值得长期记住的事实。
        2. 所有以 ★ 开头的锁定事实必须在最终输出中完整且原样保留，严禁修改、合并或删除。
        3. 普通事实应与新事实整合；冲突时以新对话为准，重复项合并，被明确推翻的旧事实可删除。
        4. 新提取的普通事实不得带 ★。每条只陈述一个高度精炼的客观事实。
        5. 严格每行输出一个中括号条目，格式为：[★ 锁定事实内容] 或 [普通事实内容]。
        6. 直接输出列表，不得包含前言、后记、分析过程或其他闲聊。如果没有新事实，原样输出全部既有事实。
    """.trimIndent()

    fun memoryConsolidationInput(
        coreFacts: List<String>,
        normalFacts: List<String>,
        history: List<Message>
    ): String = """
        【锁定核心事实】
        ${if (coreFacts.isEmpty()) "(无)" else coreFacts.joinToString("\n") { "- $it" }}

        【已有普通事实】
        ${if (normalFacts.isEmpty()) "(无)" else normalFacts.joinToString("\n") { "- $it" }}

        【最近20条对话历史】
        ${history.joinToString("\n") { "${if (it.sender == Sender.USER) "用户" else "AI"}: ${it.content}" }}
    """.trimIndent()

    val GRAPH_EXTRACTION_SYSTEM: String = """
        You are a highly structured information extractor. Extract core personal preferences, life events, habits, and relationships of the User ("主人") from the supplied conversation history.

        Rules:
        1. Output ONLY a raw, minified JSON array of objects. Do not use markdown or add prefix/suffix text.
        2. Structure: [{"s":"Subject","p":"Predicate","o":"Object"}]
        3. Avoid generic triples. Focus on concrete preferences, facts, and events.
        4. The extraction language must match the conversation language.

        Example input: User: "我最近在做那个 loyea 安卓项目，加班好严重，牛奶过敏的我都只敢点抹茶燕麦拿铁提神。"
        Example output: [{"s":"主人","p":"正在开发项目","o":"loyea 安卓项目"},{"s":"主人","p":"近期状态","o":"严重加班"},{"s":"主人","p":"过敏于","o":"纯牛奶"},{"s":"主人","p":"喜欢饮品","o":"抹茶燕麦拿铁"}]
    """.trimIndent()

    fun graphExtractionInput(history: List<Message>): String = buildString {
        append("【Conversation history】\n")
        append(history.joinToString("\n") { "${if (it.sender == Sender.USER) "User" else "AI"}: ${it.content}" })
    }

    val CONVERSATION_COMPRESSION_SYSTEM: String = """
        你是长对话摘要器。把用户提供的早期对话压缩为一份不超过 200 字的简洁中文摘要，供后续对话保持连续。
        必须保留重要事件、人物关系变化、用户的承诺、双方约定与习惯、关键情感节点；去除寒暄、日常琐碎和重复内容。
        直接输出摘要正文，不得包含前言、后记或分析过程。
    """.trimIndent()

    fun compressionInput(existingSummary: String, segmentText: String): String = buildString {
        if (existingSummary.isNotBlank()) {
            append("【已有早期摘要】（在此基础上整合新增内容，不要重复）\n")
            append(existingSummary).append("\n\n")
        }
        append("【新增对话段落】\n")
        append(segmentText)
    }

    fun greetingSystem(baseSystemPrompt: String, userName: String): String = """
        $baseSystemPrompt

        [BACKGROUND GREETING TASK / 后台主动问候任务]
        The user '$userName' is not looking at the app. When the final event message asks you to act, generate one VERY SHORT proactive greeting (1-2 sentences, max 30 words).
        It may be a morning/evening check-in or a natural sweet thought. Stay in character.
        Output only the final greeting. Do not output XML, metadata labels, tool calls or thinking.
    """.trimIndent()

    fun greetingEventInput(turnContextSnapshot: String): String = buildString {
        if (turnContextSnapshot.isNotBlank()) {
            append(turnContextSnapshot.trim()).append("\n\n")
        }
        append("[BACKGROUND EVENT]\nGenerate the scheduled proactive greeting now.")
    }
}
