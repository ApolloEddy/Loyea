package com.loyea.ui.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 会话元数据实体
 */
data class ChatSession(
    val id: String,                  // 唯一标识符 (时间戳或UUID)
    val title: String,               // 会话标题
    val lastActiveTime: Long = System.currentTimeMillis() // 最后活动时间，用于排序
)

/**
 * 本地聊天会话及消息文件存储管理器
 */
class ChatStorageManager(private val context: Context) {
    private val gson = Gson()
    private val sessionsFile = File(context.filesDir, "sessions_metadata.json")
    private val sessionsDir = File(context.filesDir, "sessions").apply {
        if (!exists()) mkdirs()
    }

    /**
     * 保存所有会话元数据列表
     */
    fun saveSessionList(sessions: List<ChatSession>) {
        try {
            val json = gson.toJson(sessions)
            sessionsFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 读取所有会话元数据列表
     */
    fun loadSessionList(): List<ChatSession> {
        if (!sessionsFile.exists()) return emptyList()
        return try {
            val json = sessionsFile.readText()
            val type = object : TypeToken<List<ChatSession>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 保存某个会话的消息列表
     */
    fun saveSessionMessages(sessionId: String, messages: List<Message>) {
        try {
            val file = File(sessionsDir, "session_$sessionId.json")
            val json = gson.toJson(messages)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 读取某个会话的消息列表
     */
    fun loadSessionMessages(sessionId: String): List<Message> {
        val file = File(sessionsDir, "session_$sessionId.json")
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<Message>>() {}.type
            val list = gson.fromJson<List<Message>>(json, type)
            // 确保 mcpCalls 不会因为反序列化可能出现的 null 而崩溃
            list?.map { msg ->
                if (msg.mcpCalls == null) msg.copy(mcpCalls = emptyList()) else msg
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 删除某个会话及其对应的消息文件
     */
    fun deleteSession(sessionId: String) {
        try {
            // 1. 删除对应的具体消息 JSON 文件
            val file = File(sessionsDir, "session_$sessionId.json")
            if (file.exists()) {
                file.delete()
            }
            // 2. 从会话列表中移除并重新保存元数据
            val currentSessions = loadSessionList().filter { it.id != sessionId }
            saveSessionList(currentSessions)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
