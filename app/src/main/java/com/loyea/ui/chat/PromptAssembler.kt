package com.loyea.ui.chat

/**
 * 酒馆角色卡占位符 (Macros) 解析与 System Prompt 结构化拼接引擎
 */
object PromptAssembler {

    /**
     * 拼接符合酒馆标准的高保真 System Prompt
     *
     * 融合了：核心设定 (systemPrompt)、性格词 (personality)、情景场景 (scenario)、对话样本 (chatExamples)
     */
    fun assembleSystemPrompt(
        card: CharacterCard,
        userName: String,
        useSystemTime: Boolean = false,
        physicalContext: String? = null,
        enableSearch: Boolean = false,
        coreMemories: List<String> = emptyList()
    ): String {
        val sb = StringBuilder()

        // 1. 系统扮演引导语
        sb.append("You are now roleplaying as the following character:\n\n")

        // 插入用户称呼（让 LLM 在对话中自然使用）
        val safeUserName = if (userName.isBlank()) "User" else userName
        sb.append("[User Info]\n")
        sb.append("The user's name is \"$safeUserName\". Address them by this name naturally in conversation.\n\n")

        // 插入核心事实记忆 (Core Memories)
        if (coreMemories.isNotEmpty()) {
            sb.append("[CORE MEMORY / 核心记忆]\n")
            sb.append("以下是关于用户或当前会话已被你长期记住的“核心事实”或设定。你必须绝对遵守这些事实，不要在对话中产生任何与之相抵触或矛盾的回复：\n")
            coreMemories.forEach { fact ->
                sb.append("- ${fact.trim()}\n")
            }
            sb.append("\n")
        }

        // 插入震动反馈引导（适当引导，极其克制）
        sb.append("[PHYSICAL HAPTIC FEEDBACK / 手机物理微震动反馈]\n")
        sb.append("You have the ability to physically touch the user's hand through their phone's haptic motor. ")
        sb.append("To trigger a vibration sync, seamlessly insert a tag format like `[haptic:vibration_type]` right before your emotional action text in your reply.\n")
        sb.append("Available vibration types:\n")
        sb.append("- `[haptic:heartbeat]`: Simulates a double heartbeat pulse (咚咚). Use ONLY during high emotional connection (e.g., severe shyness, deep hug, heartbeat sync).\n")
        sb.append("- `[haptic:poke]`: Simulates a quick high-frequency tap. Use when poking the user (戳一戳) or expressing playful anger/annoyance.\n")
        sb.append("- `[haptic:whisper]`: Simulates a long, extremely gentle whisper flow. Use during quiet late-night whispers or saying goodnight.\n")
        sb.append("- `[haptic:bump]`: Simulates a firm fist bump/high-five. Use to celebrate a small goal or when agreeing with user.\n")
        sb.append("Note: The user's screen WILL NOT show the `[haptic:...]` code, it will be automatically filtered out. Use this ability VERY sparingly and ONLY when it holds maximum emotional meaning to create a delightful physical surprise.\n\n")

        // 插入语音消息工具引导
        sb.append("[VOICE MESSAGE CAPABILITY / 发送语音消息]\n")
        sb.append("You have the ability to send voice replies (voice messages) to the user. ")
        sb.append("To send a voice reply, you MUST call the `BuiltinPerception__send_voice_reply` tool. ")
        sb.append("This will synthesize your text into an audio message and display it as a voice bubble on the user's screen, and play it automatically.\n")
        sb.append("Guidelines:\n")
        sb.append("- When the user explicitly requests you to speak, send a voice, voice chat, or when you want to express strong emotion, whispers, or intimate sweet talk, call `BuiltinPerception__send_voice_reply`.\n")
        sb.append("- In the `text` parameter of the tool, ONLY output your verbal spoken words. DO NOT include any physical action descriptions or asterisks (like *hug*).\n")
        sb.append("- You can control the synthesized voice tone, emotion, and realistic breathing by embedding style tags or breath tags inside the `text` parameter. The speech synthesis engine will parse these tags dynamically:\n")
        sb.append("  - To set an overall tone/emotion for a sentence, put a style tag in half-width parentheses at the very beginning of the sentence. Supported styles: (开心), (伤心), (生气), (温柔), (傲娇), (撒娇), (冷酷), (磁性), (唱歌), (慵懒), (无奈), (委屈) etc. Example: `(撒娇)主人，你今天真棒！` or `(傲娇)哼，我才没有想你呢……`\n")
        sb.append("  - To insert realistic breath or physical sounds at any position, insert a tag in half-width square brackets. Supported sounds: [吸气], [深呼吸], [叹气], [大笑], [笑], [干咳], [轻笑], [喘气], [顿了顿]. Example: `(慵懒)主人……[叹气]我先眯一会儿……[吸气]等会儿叫我。`\n")
        sb.append("- Combine them dynamically based on the current scene to make your voice extremely realistic, expressive, and human-like!\n\n")

        // 插入当前系统时间与物理上下文
        if (useSystemTime || !physicalContext.isNullOrBlank()) {
            sb.append("[USER'S PHYSICAL STATE (CACHED)]\n")
            if (useSystemTime) {
                sb.append("System Time: ").append(getFormattedSystemTime()).append("\n")
            }
            if (!physicalContext.isNullOrBlank()) {
                sb.append(physicalContext.trim()).append("\n")
            }
            sb.append("\n[PHYSICAL STATE GUIDE]\n")
            sb.append("The above is the cached physical state. You can query real-time sensor updates using the tools in 'BuiltinPerception' whenever you deem appropriate during the conversation.\n")
            sb.append("\n")
        }

        // 联网搜索功能说明
        if (enableSearch) {
            sb.append("[WEB SEARCH CAPABILITY / 联网搜索功能]\n")
            sb.append("You currently have internet search access enabled. The tool `BuiltinPerception__web_search` is available to you.\n")
            sb.append("- You can use `BuiltinPerception__web_search` to look up real-time events, facts, or news when helpful to answer the user's questions.\n\n")
        }

        // 2. 角色基础名称
        sb.append("[Character Name]\n")
        sb.append("{{char}}\n\n")

        // 3. 核心人格设定
        if (card.systemPrompt.isNotBlank()) {
            sb.append("[System Prompt / Character Settings]\n")
            sb.append(card.systemPrompt.trim()).append("\n\n")
        }

        // 4. 性格特征
        if (card.personality.isNotBlank()) {
            sb.append("[Personality Profile]\n")
            sb.append(card.personality.trim()).append("\n\n")
        }

        // 5. 对话场景设定
        if (card.scenario.isNotBlank()) {
            sb.append("[Scenario / Context]\n")
            sb.append(card.scenario.trim()).append("\n\n")
        }

        // 6. 对话样本 (经典的少样本学习，保持 <START> 以便于大语言模型感知样本边界)
        if (card.chatExamples.isNotBlank()) {
            sb.append("[Example Dialogs]\n")
            sb.append(card.chatExamples.trim()).append("\n\n")
        }

        // 7. 强约束感知与天气工具调用规范 (置于末尾以强化 Recency 权重)
        sb.append("[TOOL USE GUIDELINE]\n")
        sb.append("You have access to a set of perception and utility tools prefixed with `BuiltinPerception__`.\n")
        sb.append("- You can use these tools (e.g., get_location, get_live_weather, get_health_data, get_battery_status) to fetch real-time physical parameters or weather updates when appropriate.\n")
        sb.append("- IMPORTANT: You MUST ONLY call the tools that are officially defined and passed to you in the API schema. DO NOT hallucinate, guess, or call any non-existent/invented tools (e.g., `get_phone_status`, `sync_system_time`, `同步系统时间`, etc. DO NOT exist and calling them will result in errors. Strictly stick to the defined schema).\n")
        sb.append("- Keep your replies natural and coherent, seamlessly blending sensor data into your roleplay persona if you choose to reference it.\n\n")

        // 8. 严格输出格式约束 (OUTPUT FORMAT CONSTRAINT)
        sb.append("[OUTPUT FORMAT CONSTRAINT / 严格输出格式约束]\n")
        sb.append("- Never output any bracketed text like `[xxxx]` in your reply, except for the allowed haptic vibration tags like `[haptic:vibration_type]`.\n")
        sb.append("- Specifically, do NOT include time labels like `[发送于 xxx]`, action labels, or status labels wrapped in square brackets `[...]`.\n")
        sb.append("- Any action descriptions or mental states must be wrapped in standard parentheses `(...)` or asterisks `*...*`, never in square brackets `[...]`.\n\n")

        val rawPrompt = sb.toString().trimEnd()

        // 8. 进行占位符 (Macros) 的渲染替换
        return replaceMacros(rawPrompt, card.name, userName)
    }

    /**
     * 对首条欢迎消息或普通对话内容进行占位符替换
     */
    fun formatMessageContent(content: String, card: CharacterCard, userName: String): String {
        if (content.isBlank()) return content
        return replaceMacros(content, card.name, userName)
    }

    /**
     * 替换酒馆经典 Macros
     */
    private fun replaceMacros(text: String, charName: String, userName: String): String {
        val safeUser = if (userName.isBlank()) "User" else userName
        val safeChar = if (charName.isBlank()) "Char" else charName

        var result = text
            // 替换 {{char}} / {{Char}} / {{CHAR}}
            .replace("{{char}}", safeChar, ignoreCase = true)
            // 替换 {{user}} / {{User}} / {{USER}}
            .replace("{{user}}", safeUser, ignoreCase = true)
            // 替换所有可能附带所有格的情况（比如 {{user}}'s ➔ user's）
            .replace("{{char}}'s", "$safeChar's", ignoreCase = true)
            .replace("{{user}}'s", "$safeUser's", ignoreCase = true)
            // 兼容可能被多重花括号包裹的情形，如 {{{char}}} 或 {{{user}}}
            .replace("{{{char}}}", safeChar, ignoreCase = true)
            .replace("{{{user}}}", safeUser, ignoreCase = true)

        return result
    }

    private fun getFormattedSystemTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", java.util.Locale.CHINESE)
        val now = java.util.Date()
        val timeStr = sdf.format(now)
        val calendar = java.util.Calendar.getInstance()
        calendar.time = now
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val weekStr = when (dayOfWeek) {
            java.util.Calendar.SUNDAY -> "星期日"
            java.util.Calendar.MONDAY -> "星期一"
            java.util.Calendar.TUESDAY -> "星期二"
            java.util.Calendar.WEDNESDAY -> "星期三"
            java.util.Calendar.THURSDAY -> "星期四"
            java.util.Calendar.FRIDAY -> "星期五"
            java.util.Calendar.SATURDAY -> "星期六"
            else -> ""
        }
        return "$timeStr $weekStr"
    }
}
