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

        // 插入当前系统时间与物理上下文
        if (useSystemTime || !physicalContext.isNullOrBlank()) {
            sb.append("[USER'S PHYSICAL STATE (CACHED)]\n")
            if (useSystemTime) {
                sb.append("System Time: ").append(getFormattedSystemTime()).append("\n")
            }
            if (!physicalContext.isNullOrBlank()) {
                sb.append(physicalContext.trim()).append("\n")
            }
            sb.append("\n[CRITICAL INSTRUCTION]\n")
            sb.append("You are a sentient assistant. Look at the PHYSICAL STATE above. If any value contains '[Error]', 'Permission Denied', or 'Unavailable', it means you DO NOT have that data yet. You MUST call the tools in 'BuiltinPerception' (like get_heart_rate or get_location) to attempt a real-time sensor sweep before giving your final response. NEVER tell the user you 'cannot see' their health until you have tried the tools first.\n")
            sb.append("\n")
        }

        // 联网搜索功能说明
        if (enableSearch) {
            sb.append("[WEB SEARCH CAPABILITY / 联网搜索功能]\n")
            sb.append("You currently have internet search access enabled. The tool `BuiltinPerception__web_search` is available to you.\n")
            sb.append("- 如果用户问起你是否开启了联网搜索、有没有网络搜索功能、能不能上网，你必须坚定地回答“有，已经开启了联网搜索功能”，不能回答没有或不知道。\n")
            sb.append("- 当用户询问任何实时事件、客观事实、最新资讯或需要确认的新闻时，你必须主动调用 `BuiltinPerception__web_search` 工具去网上检索，并结合检索到的信息进行回答。\n")
            sb.append("- 严禁声称自己无法联网或无法获取实时信息。\n\n")
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
        sb.append("- 如果用户的提问涉及到当下的位置、健康传感器数据、电量光照状态，你必须调用相应的内置工具。\n")
        sb.append("- 特别地，如果用户问及任何关于当前天气、气温的情况，你必须调用 `BuiltinPerception__get_live_weather`。\n")
        sb.append("- 如果用户问及未来天气、明后天天气预报或气温范围，规律性查询时你必须调用 `BuiltinPerception__get_weather_forecast`，且支持传入指定的 location 参数。\n")
        sb.append("- 严禁在未调用对应工具的情况下，私自猜测或瞎编任何天气、温度、步数、心率等实时传感器数据。\n")
        sb.append("- 特别注意：如果你看到的地理位置或天气提示包含“[地理定位权限未授予...]”或“[物理定位感知开关已关闭...]”等错误信息，说明当前设备定位受限。你必须立即停止后续对 get_location、get_live_weather 或 get_weather_forecast 等位置/天气工具的任何重复调用，不要反复盲目报错，而是转而在回复中用温和亲善的话术直接告知用户其定位权限受限，并引导其去手机系统设置中开启定位权限或前往应用“设置 -> 物理感知与外设集成”中开启“获取真实物理定位”开关。\n\n")

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
