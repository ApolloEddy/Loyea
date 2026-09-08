package com.loyea.ui.chat

import org.json.JSONObject

/**
 * 语音文本参数提取与净化（TTS 语音卡转写展示 / MiMo STT 原文清洗共用）。
 *
 * 提取必须正规 JSON 解析优先：惰性正则 ([\s\S]*?) 遇值内引号（转义 \"）会在第一个
 * 转义引号处拦腰截断、尾部残留孤立 \ —— TTS 音频走 Gson 正规解析故完好，转写却断半句。
 */
object VoiceTextExtractor {

    /**
     * TTS 语音卡转写展示：从工具调用 argumentsJson 提取 text 参数并净化标签。
     * 正规解析优先，失败退转义感知正则；两者皆败返回空串（不展示原始 JSON）
     */
    fun extractTranscript(argumentsJson: String?): String {
        if (argumentsJson.isNullOrBlank()) return ""
        var parsedText: String? = null
        runCatching {
            val t = JSONObject(argumentsJson).optString("text", "")
            if (t.isNotBlank()) parsedText = t
        }
        if (parsedText == null) {
            val escaped = textArgRegex.find(argumentsJson)?.groupValues?.get(1)
            if (!escaped.isNullOrEmpty()) parsedText = unescapeJsonString(escaped)
        }
        val text = parsedText ?: return ""
        return stripStyleTags(text).trim()
    }

    /**
     * MiMo STT 原文清洗：JSON 形态（含 "text":）提取 text 参数，纯文本直通；
     * 直通路径保留反转义清洗（模型文本可能带字面转义残留），解析路径已是明文不再二次处理
     */
    fun cleanSttText(rawText: String?): String {
        if (rawText.isNullOrBlank()) return ""
        val isJsonLike = rawText.contains("\"text\"") && rawText.contains(":")
        val text = if (isJsonLike) {
            var parsed: String? = null
            runCatching {
                val t = JSONObject(rawText).optString("text", "")
                if (t.isNotBlank()) parsed = t
            }
            parsed ?: textArgRegex.find(rawText)?.groupValues?.get(1)?.let(::unescapeJsonString)
            ?: rawText
        } else {
            rawText
        }
        if (text.isBlank()) return ""
        var result = stripStyleTags(text)
        if (text === rawText) result = unescapeJsonString(result)
        return result.trim()
    }

    /** 转义感知提取：(?:\\.|[^"\\])* 越过 \" 继续消费，停在第一个未转义引号 */
    private val textArgRegex = Regex("""\"text\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")

    /** 净化语气/呼吸音标签（小括号、中括号、大括号、尖括号、全角括号） */
    private fun stripStyleTags(text: String): String {
        var result = text.replace(Regex("\\([\\s\\S]*?\\)"), "")
        result = result.replace(Regex("（[\\s\\S]*?）"), "")
        result = result.replace(Regex("\\[[\\s\\S]*?\\]"), "")
        result = result.replace(Regex("【[\\s\\S]*?】"), "")
        result = result.replace(Regex("\\{[\\s\\S]*?\\}"), "")
        result = result.replace(Regex("<[\\s\\S]*?>"), "")
        return result
    }

    /** 单遍 JSON 字符串反转义：\" \\ \n \t \r \/ \uXXXX；未知转义保留转义后字符 */
    fun unescapeJsonString(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) { sb.append(code.toChar()); i += 6 }
                        else { sb.append(n); i += 2 }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }
}
