package com.loyea.ui.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

/**
 * 会话元数据实体
 */
data class ChatSession(
    val id: String,                  // 唯一标识符 (时间戳或UUID)
    val title: String,               // 会话标题
    val lastActiveTime: Long = System.currentTimeMillis(), // 最后活动时间，用于排序
    val characterId: String = "char_loyea_default", // 新增角色人格绑定
    val useSystemTime: Boolean? = false, // 是否在此会话中使用真实系统时间
    val coreMemories: List<String> = emptyList() // 会话核心记忆列表
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

    companion object {
        private val sessionsMutex = Mutex()
        private val messagesMutex = Mutex()
        private val cardsMutex = Mutex()
    }

    private fun saveSessionListInternal(sessions: List<ChatSession>) {
        try {
            val json = gson.toJson(sessions)
            sessionsFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadSessionListInternal(): List<ChatSession> {
        if (!sessionsFile.exists()) return emptyList()
        return try {
            val json = sessionsFile.readText()
            val type = object : TypeToken<List<ChatSession>>() {}.type
            val rawList = gson.fromJson<List<ChatSession>>(json, type) ?: emptyList()
            rawList.map { raw ->
                ChatSession(
                    id = raw.id ?: System.currentTimeMillis().toString(),
                    title = raw.title ?: "Unnamed Chat",
                    lastActiveTime = raw.lastActiveTime,
                    characterId = raw.characterId ?: "char_loyea_default",
                    useSystemTime = raw.useSystemTime ?: false,
                    coreMemories = raw.coreMemories ?: emptyList()
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveSessionMessagesInternal(sessionId: String, messages: List<Message>) {
        try {
            val file = File(sessionsDir, "session_$sessionId.json")
            val json = gson.toJson(messages)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadSessionMessagesInternal(sessionId: String): List<Message> {
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

    private fun saveCharacterCardsInternal(cards: List<CharacterCard>) {
        try {
            val json = gson.toJson(cards)
            cardsFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCharacterCardsInternal(): List<CharacterCard> {
        return try {
            if (!cardsFile.exists()) {
                val defaults = TavernCardParser.getBuiltInCards()
                saveCharacterCardsInternal(defaults)
                return defaults
            }
            val json = cardsFile.readText()
            val type = object : TypeToken<List<CharacterCard>>() {}.type
            gson.fromJson<List<CharacterCard>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 保存所有会话元数据列表
     */
    suspend fun saveSessionList(sessions: List<ChatSession>) {
        sessionsMutex.withLock {
            saveSessionListInternal(sessions)
        }
    }

    /**
     * 读取所有会话元数据列表 (进行自愈式数据清洗，防御 Gson 反序列化带来的内存 null 隐患)
     */
    suspend fun loadSessionList(): List<ChatSession> {
        return sessionsMutex.withLock {
            loadSessionListInternal()
        }
    }

    /**
     * 保存某个会话的消息列表
     */
    suspend fun saveSessionMessages(sessionId: String, messages: List<Message>) {
        messagesMutex.withLock {
            saveSessionMessagesInternal(sessionId, messages)
        }
    }

    /**
     * 读取某个会话的消息列表
     */
    suspend fun loadSessionMessages(sessionId: String): List<Message> {
        return messagesMutex.withLock {
            loadSessionMessagesInternal(sessionId)
        }
    }

    /**
     * 删除某个会话及其对应的消息文件
     */
    suspend fun deleteSession(sessionId: String) {
        sessionsMutex.withLock {
            messagesMutex.withLock {
                try {
                    // 1. 删除对应的具体消息 JSON 文件
                    val file = File(sessionsDir, "session_$sessionId.json")
                    if (file.exists()) {
                        file.delete()
                    }
                    // 2. 从会话列表中移除并重新保存元数据
                    val currentSessions = loadSessionListInternal().filter { it.id != sessionId }
                    saveSessionListInternal(currentSessions)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private val cardsFile = File(context.filesDir, "character_cards.json")

    /**
     * 保存所有角色卡列表
     */
    suspend fun saveCharacterCards(cards: List<CharacterCard>) {
        cardsMutex.withLock {
            saveCharacterCardsInternal(cards)
        }
    }

    /**
     * 读取所有角色卡列表 (如不存在则自动注入内置角色模板)
     */
    suspend fun loadCharacterCards(): List<CharacterCard> {
        return cardsMutex.withLock {
            loadCharacterCardsInternal()
        }
    }

    /**
     * 原子化更新会话消息
     */
    suspend fun updateSessionMessages(sessionId: String, updateBlock: (List<Message>) -> List<Message>) {
        messagesMutex.withLock {
            val current = loadSessionMessagesInternal(sessionId)
            val updated = updateBlock(current)
            saveSessionMessagesInternal(sessionId, updated)
        }
    }

    /**
     * 原子化更新会话列表
     */
    suspend fun updateSessionList(updateBlock: (List<ChatSession>) -> List<ChatSession>) {
        sessionsMutex.withLock {
            val current = loadSessionListInternal()
            val updated = updateBlock(current)
            saveSessionListInternal(updated)
        }
    }

    /**
     * 原子化更新某个会话的核心记忆
     */
    suspend fun updateSessionCoreMemories(sessionId: String, memories: List<String>) {
        updateSessionList { currentList ->
            currentList.map { session ->
                if (session.id == sessionId) {
                    session.copy(coreMemories = memories)
                } else {
                    session
                }
            }
        }
    }
}
