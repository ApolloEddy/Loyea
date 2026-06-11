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
    fun assembleSystemPrompt(card: CharacterCard, userName: String, useSystemTime: Boolean = false): String {
        val sb = StringBuilder()

        // 1. 系统扮演引导语
        sb.append("You are now roleplaying as the following character:\n\n")

        // 插入用户称呼（让 LLM 在对话中自然使用）
        val safeUserName = if (userName.isBlank()) "User" else userName
        sb.append("[User Info]\n")
        sb.append("The user's name is \"$safeUserName\". Address them by this name naturally in conversation.\n\n")

        // 插入当前系统时间
        if (useSystemTime) {
            sb.append("[Current System Time]\n")
            sb.append(getFormattedSystemTime()).append("\n\n")
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

        val rawPrompt = sb.toString().trimEnd()

        // 7. 进行占位符 (Macros) 的渲染替换
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
