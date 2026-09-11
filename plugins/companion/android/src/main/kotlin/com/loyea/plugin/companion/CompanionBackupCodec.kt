package com.loyea.plugin.companion

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.Message

/**
 * S-08 陪伴备份编解码（DATA-01~05 最小合同）。
 *
 * 备份为单一 JSON 快照：陪伴范围设置 + 会话元数据（核心记忆/摘要/token 计量）+ 全量消息
 * （含多版本与时间戳）。DATA-01：不含 API Key、授权令牌或任何服务配置（结构上就不写入）；
 * DATA-02：不含图片/音频二进制，媒体字段在导出时剥离，恢复后以文字描述与占位呈现。
 * DATA-04：session.id 仅为旧标识参考，恢复方必须生成新 id 并递增 bindingRevision。
 */
object CompanionBackupCodec {

    const val BACKUP_TYPE = "loyea_companion_backup"
    const val BACKUP_VERSION = 1

    private val gson = Gson()

    /** 恢复前校验结果：仅携带展示所需摘要，不直接引用旧会话标识做写回。 */
    data class BackupPreview(
        val displayName: String,
        val createdAt: Long,
        val exportedAt: Long,
        val messageCount: Int,
        val memoryCount: Int,
        val firstMessageAt: Long?,
        val lastMessageAt: Long?,
        val session: ChatSession,
        val messages: List<Message>
    )

    sealed class ParseResult {
        data class Ok(val preview: BackupPreview) : ParseResult()
        data class Rejected(val reason: String) : ParseResult()
    }

    /** 导出 JSON（DATA-01/02：不写 Key，不写媒体二进制；媒体字段剥离，imageDesc 保留）。 */
    fun exportJson(
        displayName: String,
        perceptionEnabled: Boolean,
        proactiveEnabled: Boolean,
        configVersion: Int,
        createdAt: Long,
        exportedAt: Long,
        session: ChatSession,
        messages: List<Message>
    ): String {
        val root = JsonObject()
        root.addProperty("type", BACKUP_TYPE)
        root.addProperty("version", BACKUP_VERSION)
        root.addProperty("exportedAt", exportedAt)

        val companion = JsonObject()
        companion.addProperty("displayName", displayName)
        companion.addProperty("perceptionEnabled", perceptionEnabled)
        companion.addProperty("proactiveEnabled", proactiveEnabled)
        companion.addProperty("configVersion", configVersion)
        companion.addProperty("createdAt", createdAt)
        root.add("companion", companion)

        // 会话元数据：剥掉运行期标识（id 由恢复方重新映射，DATA-04）
        val sessionTree = gson.toJsonTree(session).asJsonObject.deepCopy()
        sessionTree.remove("id")
        sessionTree.remove("legacyExtrasJson")
        root.add("session", sessionTree)

        // 消息：剥离本地媒体路径（DATA-02），保留正文/版本/时间/思考/工具记录与 imageDesc
        val messagesArray = JsonArray()
        for (message in messages) {
            val tree = gson.toJsonTree(message).asJsonObject.deepCopy()
            tree.remove("imageUrl")
            tree.remove("audioUrl")
            tree.remove("llmContextSnapshot") // 请求期上下文快照属于派生数据，不属于陪伴记录
            messagesArray.add(tree)
        }
        root.add("messages", messagesArray)
        return gson.toJson(root)
    }

    /** 恢复前校验（DATA-03：全部解析成功才算 Ok，任何结构异常原样拒绝）。 */
    fun parse(json: String): ParseResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return ParseResult.Rejected("文件不是有效的 JSON")
        }
        if (!root.isJsonObject) return ParseResult.Rejected("文件结构不正确")
        val obj = root.asJsonObject

        val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        if (type != BACKUP_TYPE) return ParseResult.Rejected("这不是陪伴模式备份文件")

        val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.let {
            try { it.asInt } catch (e: Exception) { -1 }
        } ?: -1
        if (version != BACKUP_VERSION) return ParseResult.Rejected("备份版本不受支持（v$version）")

        val session = try {
            gson.fromJson(obj.get("session"), ChatSession::class.java)
        } catch (e: Exception) {
            return ParseResult.Rejected("会话数据无法解析")
        } ?: return ParseResult.Rejected("会话数据缺失")
        if (session.coreMemories == null) {
            return ParseResult.Rejected("会话数据不完整")
        }

        val messages = try {
            gson.fromJson(obj.get("messages"), Array<Message>::class.java).toList()
        } catch (e: Exception) {
            return ParseResult.Rejected("消息数据无法解析")
        } ?: return ParseResult.Rejected("消息数据缺失")

        val companion = obj.getAsJsonObject("companion")
        val displayName = companion?.get("displayName")?.asString ?: "Loyea"
        val createdAt = companion?.get("createdAt")?.asLong ?: 0L
        val exportedAt = obj.get("exportedAt")?.asLong ?: 0L

        // 归一化：Gson 对缺失集合字段可能产出 null（与宿主 ChatStorageManager 同样的防御）
        val safeMessages = messages.map { m ->
            m.copy(
                mcpCalls = m.mcpCalls ?: emptyList(),
                versions = m.versions ?: emptyList(),
                contentSegments = m.contentSegments ?: emptyList(),
                llmTimeZoneId = m.llmTimeZoneId?.takeIf { it.isNotBlank() }
                    ?: java.util.TimeZone.getDefault().id
            )
        }
        val times = safeMessages.map { it.timestamp }
        return ParseResult.Ok(
            BackupPreview(
                displayName = displayName,
                createdAt = createdAt,
                exportedAt = exportedAt,
                messageCount = safeMessages.size,
                memoryCount = session.coreMemories?.size ?: 0,
                firstMessageAt = times.minOrNull(),
                lastMessageAt = times.maxOrNull(),
                session = session,
                messages = safeMessages
            )
        )
    }

    /** Markdown 导出聊天（可读，不称为完整备份——Spec §6.2）。 */
    fun exportMarkdown(displayName: String, messages: List<Message>, exportedAt: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy/M/d HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.append("# 陪伴聊天记录\n\n")
        sb.append("导出于：").append(fmt.format(java.util.Date(exportedAt))).append('\n')
        sb.append("说明：可读文本导出，不包含图片/音频文件，不能用于恢复。\n\n")
        for (m in messages) {
            val who = if (m.sender == com.loyea.ui.chat.Sender.USER) "你" else displayName
            sb.append("**").append(who).append("** ").append(fmt.format(java.util.Date(m.timestamp))).append('\n')
            if (m.content.isBlank()) {
                sb.append("(无文字内容)\n")
            } else {
                sb.append(m.content).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
