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
    val coreMemories: List<String> = emptyList(), // 会话核心记忆列表
    val isTitleSummarized: Boolean? = false, // 是否已由AI总结了标题
    val compressedSummary: String = "", // 长会话早期摘要（滑窗外的旧消息被压缩后保留故事脉络）
    val compressedAtCount: Int = 0, // 已参与压缩的消息条数（增量压缩断点）
    val promptTokens: Long = 0, // 本会话累计 prompt token（会话独立计量）
    val completionTokens: Long = 0, // 本会话累计 completion token
    val lastContextTokens: Long = 0 // 最近一次主聊天流请求的上下文 token（仅主聊天流更新，用于上下文窗口展示）
)

/**
 * 全局世界观条目（World Info，仿 SillyTavern 世界书）。
 *
 * 关键词触发注入 system prompt；字段保留 SillyTavern World Info 常用字段，
 * 保证导入/导出往返不失真（见 WorldInfoSettings 的 import/export）。
 */
data class WorldInfoEntry(
    val id: String,
    val keywords: List<String>,      // 主关键词（ST key）
    val content: String,
    val enabled: Boolean = true,     // 本地启用开关（未启用不参与关键词匹配注入）
    // ---- SillyTavern 兼容字段 ----
    val uid: Int = 0,                // ST uid（用于序列化顺序；可重复）
    val keysecondary: List<String> = emptyList(), // ST 次关键词
    val constant: Boolean = false,   // ST 常驻注入：无视关键词始终注入
    val order: Int = 100,            // ST order：命中后的输出顺序
    val depth: Int = 4,              // ST depth：注入深度
    val comment: String = "",        // ST 备注
    val selective: Boolean = false,  // ST selective
    val disable: Boolean = false     // ST 原生 disable（导入时非 disable → enabled，导出时反向）
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
        private val worldInfoMutex = Mutex()
    }

    private fun saveSessionListInternal(sessions: List<ChatSession>) {
        try {
            val json = gson.toJson(sessions)
            atomicWrite(sessionsFile, json)
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
                    coreMemories = raw.coreMemories ?: emptyList(),
                    isTitleSummarized = raw.isTitleSummarized ?: false,
                    compressedSummary = raw.compressedSummary ?: "",
                    compressedAtCount = raw.compressedAtCount ?: 0,
                    promptTokens = raw.promptTokens ?: 0,
                    completionTokens = raw.completionTokens ?: 0,
                    lastContextTokens = raw.lastContextTokens ?: 0
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(sessionsFile)
            emptyList()
        }
    }

    private fun saveSessionMessagesInternal(sessionId: String, messages: List<Message>) {
        try {
            val file = File(sessionsDir, "session_$sessionId.json")
            val json = gson.toJson(messages)
            atomicWrite(file, json)
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
            // 确保 mcpCalls 和 versions 不会因为反序列化可能出现的 null 而崩溃
            list?.map { msg ->
                var cleaned = msg
                if (cleaned.mcpCalls == null) {
                    cleaned = cleaned.copy(mcpCalls = emptyList())
                }
                if (cleaned.versions == null) {
                    cleaned = cleaned.copy(versions = emptyList())
                }
                if (cleaned.contentSegments == null) {
                    cleaned = cleaned.copy(contentSegments = emptyList())
                }
                cleaned
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(file)
            emptyList()
        }
    }

    private fun saveCharacterCardsInternal(cards: List<CharacterCard>) {
        try {
            val json = gson.toJson(cards)
            atomicWrite(cardsFile, json)
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
            val rawList = gson.fromJson<List<CharacterCard>>(json, type) ?: emptyList()
            // 进行自愈式清洗
            rawList.map { raw ->
                CharacterCard(
                    id = raw.id ?: System.currentTimeMillis().toString(),
                    name = raw.name ?: "Unknown",
                    avatarUri = raw.avatarUri,
                    avatarColor = raw.avatarColor ?: "#E5D3B3",
                    shortIntro = raw.shortIntro ?: "",
                    systemPrompt = raw.systemPrompt ?: "",
                    personality = raw.personality ?: "",
                    scenario = raw.scenario ?: "",
                    firstMessage = raw.firstMessage ?: "",
                    chatExamples = raw.chatExamples ?: "",
                    isBuiltIn = raw.isBuiltIn,
                    creatorName = raw.creatorName,
                    backgroundUri = raw.backgroundUri
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(cardsFile)
            emptyList()
        }
    }

    /**
     * 原子写入：先写临时文件再重命名，避免中途崩溃（断电/进程被杀）产生半截 JSON 覆盖有效数据
     */
    private fun atomicWrite(file: File, content: String) {
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        tmpFile.writeText(content)
        if (!tmpFile.renameTo(file)) {
            tmpFile.delete()
            file.writeText(content) // rename 失败（罕见）时回退为直接写入
        }
    }

    /**
     * 解析损坏时重命名备份（.corrupt），防止下次保存直接覆盖用户原始数据
     */
    private fun backupCorruptFile(file: File) {
        try {
            if (file.exists()) {
                file.renameTo(File(file.parentFile, "${file.name}.corrupt"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    private val worldInfoFile = File(context.filesDir, "global_world_info.json")

    /**
     * 保存全局世界观条目列表
     */
    suspend fun saveWorldInfo(entries: List<WorldInfoEntry>) {
        worldInfoMutex.withLock {
            try {
                atomicWrite(worldInfoFile, gson.toJson(entries))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 读取全局世界观条目列表（不存在则返回空列表）
     */
    suspend fun loadWorldInfo(): List<WorldInfoEntry> {
        return worldInfoMutex.withLock {
            if (!worldInfoFile.exists()) return@withLock emptyList()
            try {
                val json = worldInfoFile.readText()
                val type = object : TypeToken<List<WorldInfoEntry>>() {}.type
                val rawList = gson.fromJson<List<WorldInfoEntry>>(json, type) ?: emptyList()
                // 自愈式清洗：Gson 反射对缺失字段不触发 Kotlin 默认值，逐字段兜底
                rawList.map { raw ->
                    WorldInfoEntry(
                        id = raw.id ?: System.currentTimeMillis().toString(),
                        keywords = raw.keywords ?: emptyList(),
                        content = raw.content ?: "",
                        enabled = raw.enabled ?: true,
                        uid = raw.uid ?: 0,
                        keysecondary = raw.keysecondary ?: emptyList(),
                        constant = raw.constant ?: false,
                        order = raw.order ?: 100,
                        depth = raw.depth ?: 4,
                        comment = raw.comment ?: "",
                        selective = raw.selective ?: false,
                        disable = raw.disable ?: false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                backupCorruptFile(worldInfoFile)
                emptyList()
            }
        }
    }

    /**
     * 原子化更新全局世界观条目列表
     */
    suspend fun updateWorldInfo(updateBlock: (List<WorldInfoEntry>) -> List<WorldInfoEntry>) {
        worldInfoMutex.withLock {
            val current = if (worldInfoFile.exists()) {
                try {
                    val json = worldInfoFile.readText()
                    val type = object : TypeToken<List<WorldInfoEntry>>() {}.type
                    gson.fromJson<List<WorldInfoEntry>>(json, type) ?: emptyList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    backupCorruptFile(worldInfoFile)
                    emptyList()
                }
            } else {
                emptyList()
            }
            val updated = updateBlock(current)
            try {
                atomicWrite(worldInfoFile, gson.toJson(updated))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

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

    /**
     * 原子化更新某个会话的长会话压缩摘要与压缩断点
     */
    suspend fun updateSessionCompression(sessionId: String, summary: String, compressedAtCount: Int) {
        updateSessionList { currentList ->
            currentList.map { session ->
                if (session.id == sessionId) {
                    session.copy(compressedSummary = summary, compressedAtCount = compressedAtCount)
                } else {
                    session
                }
            }
        }
    }

    /**
     * 原子化累加某个会话的 token 用量（加性：prompt/completion 为增量，跨多次调用累计）。
     * lastContextTokens 传非 null 时覆盖（仅主聊天流更新），否则保留旧值。
     */
    suspend fun updateSessionTokens(
        sessionId: String,
        promptTokens: Long,
        completionTokens: Long,
        lastContextTokens: Long? = null
    ) {
        updateSessionList { currentList ->
            currentList.map { session ->
                if (session.id == sessionId) {
                    session.copy(
                        promptTokens = session.promptTokens + promptTokens,
                        completionTokens = session.completionTokens + completionTokens,
                        lastContextTokens = lastContextTokens ?: session.lastContextTokens
                    )
                } else {
                    session
                }
            }
        }
    }
}
