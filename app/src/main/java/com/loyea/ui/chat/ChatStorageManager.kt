package com.loyea.ui.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.storage.CharacterDocumentStore
import com.loyea.storage.RebuildStorageMigrator
import com.loyea.storage.worldinfo.WorldInfoLibrary
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
    // —— 重启版新增：请求归属检查与 0.6.1 迁移保留字段（Spec §3.2/§9.1）——
    val bindingRevision: Long = 1L,  // 同一会话内人格绑定变化时递增，阻断 A→B→A 写回
    val sessionIncarnationId: String? = null, // 0.6.1 迁移保留；防止删除后复用 session id 的历史围栏
    val legacyExtrasJson: String? = null,     // 0.6.1 会话中本轮不支持功能的只读遗留数据
    val useSystemTime: Boolean? = false, // 是否在此会话中使用真实系统时间
    val coreMemories: List<String> = emptyList(), // 会话核心记忆列表
    val isTitleSummarized: Boolean? = false, // 是否已由AI总结了标题
    val compressedSummary: String = "", // 长会话早期摘要（滑窗外的旧消息被压缩后保留故事脉络）
    val compressedAtCount: Int = 0, // 已参与压缩的消息条数（增量压缩断点）
    val promptTokens: Long = 0, // 本会话累计 prompt token（会话独立计量）
    val completionTokens: Long = 0, // 本会累计 completion token
    val lastContextTokens: Long = 0, // 最近一次主聊天流请求的上下文 token（仅主聊天流更新，用于上下文窗口展示）
    val promptCacheHitTokens: Long = 0, // 本会话累计 DeepSeek 前缀缓存命中 token
    val promptCacheMissTokens: Long = 0 // 本会话累计 DeepSeek 前缀缓存未命中 token
)

/**
 * 全局世界观条目（World Info，仿 SillyTavern 世界书）。
 *
 * 关键词触发注入 system prompt；字段保留 SillyTavern World Info 常用字段，
 * 保证导入/导出往返不失真（导入导出在世界书库页 WorldInfoLibraryScreen）。
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
    val disable: Boolean = false,    // ST 原生 disable（导入时非 disable → enabled，导出时反向）
    // ---- ST v2 高级字段（camelCase）----
    val selectiveLogic: Int = 0,     // 0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL（selective=true 时生效）
    val group: String = "",          // 分组名：同组条目保持连续注入
    val probability: Int = 100,      // 触发概率 0-100（useProbability 时生效）
    val useProbability: Boolean = false, // 是否启用概率触发
    val delayUntilRecursion: Int = 0,    // 延迟到第 n 个递归轮次才激活（>0 时初始轮不扫描）
    val preventRecursion: Boolean = false, // 命中后中断整个递归链
    val allowRecursion: Boolean = true,    // 是否参与递归轮扫描（false 只能被直接扫描激活）
    val excludeRecursion: Boolean = false, // 只能被直接扫描激活，不能被其他条目 content 激活
    val keysContainedIn: String = "chat", // 主关键词扫描源（chat/user/system/world 逗号分隔）
    val position: Int = 0,           // 条内插入位置微调（当前仅保留字段）
    val weight: Int = 0              // 排序权重（order 相等时的次序）
)

/**
 * 本地聊天会话及消息文件存储管理器
 */
class ChatStorageManager(private val context: Context) {
    private val gson = Gson()

    // 重启版存储根：首次访问前完成一次性迁移（旧文件保持原样，幂等）。
    // 路径一律惰性解析：迁移会以目录重命名原子切换根目录，迁移前缓存的
    // File 句柄会指向被替换的旧目录（Spec §9.2 staging 语义）。
    private val storageRoot get() = File(context.filesDir, RebuildStorageMigrator.ROOT_NAME)
    private val sessionsFile get() = File(storageRoot, "sessions_metadata.json")
    private val sessionsDir get() = File(storageRoot, "sessions").apply { if (!exists()) mkdirs() }
    private val documentStore get() = CharacterDocumentStore(File(storageRoot, "characters"))

    /** 世界书 2.0 统一书库（W2 起匹配链路与旧 UI 双轨的共用底座）。 */
    val worldInfoLibrary: WorldInfoLibrary by lazy { WorldInfoLibrary(storageRoot) }

    companion object {
        private val sessionsMutex = Mutex()
        private val messagesMutex = Mutex()
        private val cardsMutex = Mutex()
        private val migrationMutex = Mutex()
    }

    /** 所有读写前串行调用：确保迁移完成（幂等、进程内唯一）后才访问新存储根。 */
    private suspend fun ensureMigrated() {
        // manifest 已存在时只付一次 exists() 的代价；迁移本身由 migrator 幂等保证
        if (File(storageRoot, "manifest.json").exists()) {
            // 世界书 2.0 书库迁移（根迁移完成后的独立一步，manifest 短路，Spec §5）
            if (!File(storageRoot, "worldinfo/manifest.json").exists()) {
                runCatching { worldInfoLibrary.migrateIfNeeded() }.onFailure { it.printStackTrace() }
            }
            return
        }
        migrationMutex.withLock {
            runCatching { RebuildStorageMigrator.ensureMigrated(context.filesDir) }
                .onFailure { it.printStackTrace() }
        }
        // 根迁移刚完成：顺带完成书库迁移（同一首启内完成，幂等可重试）
        runCatching { worldInfoLibrary.migrateIfNeeded() }.onFailure { it.printStackTrace() }
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
                    bindingRevision = if ((raw.bindingRevision ?: 0L) > 0L) raw.bindingRevision else 1L,
                    sessionIncarnationId = raw.sessionIncarnationId,
                    legacyExtrasJson = raw.legacyExtrasJson,
                    useSystemTime = raw.useSystemTime ?: false,
                    coreMemories = raw.coreMemories ?: emptyList(),
                    isTitleSummarized = raw.isTitleSummarized ?: false,
                    compressedSummary = raw.compressedSummary ?: "",
                    compressedAtCount = raw.compressedAtCount ?: 0,
                    promptTokens = raw.promptTokens ?: 0,
                    completionTokens = raw.completionTokens ?: 0,
                    lastContextTokens = raw.lastContextTokens ?: 0,
                    promptCacheHitTokens = raw.promptCacheHitTokens ?: 0,
                    promptCacheMissTokens = raw.promptCacheMissTokens ?: 0
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
            // Gson 对旧 JSON 缺失的非空集合字段会写入运行时 null；必须一次性归一化。
            // 分多次 copy 会把尚未修复的另一个 null 传入 Kotlin 非空参数并触发 NPE。
            list?.map { msg ->
                msg.copy(
                    mcpCalls = msg.mcpCalls ?: emptyList(),
                    versions = msg.versions ?: emptyList(),
                    contentSegments = msg.contentSegments ?: emptyList(),
                    llmTimeZoneId = msg.llmTimeZoneId?.takeIf { it.isNotBlank() }
                        ?: java.util.TimeZone.getDefault().id
                )
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(file)
            emptyList()
        }
    }

    /**
     * 卡片持久化已切换为 rebuild_storage_v1/characters/<id>.json（CharacterDocument 单一真源）。
     * 旧 character_cards.json 只在迁移时读取一次；此后的投影改动按字段写回文档。
     */
    private suspend fun saveCharacterCardsInternal(cards: List<CharacterCard>) {
        try {
            val existing = documentStore.loadAll().associateBy { it.profile.id }
            // 空列表保护：不因"加载失败→空列表→自动保存"清空文档库（Spec §9.2）
            if (cards.isEmpty() && existing.isNotEmpty()) {
                return
            }
            val keepIds = cards.map { it.id }.toSet()
            existing.values.forEach { doc ->
                if (doc.profile.id !in keepIds) documentStore.delete(doc.profile.id)
            }
            cards.forEach { card ->
                val doc = existing[card.id]
                if (doc != null) {
                    documentStore.save(CharacterDocumentStore.applyCardToDocument(doc, card))
                } else {
                    documentStore.save(CharacterDocumentStore.documentFromCard(card))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadCharacterCardsInternal(): List<CharacterCard> {
        return try {
            val documents = documentStore.loadAll().associateBy { it.profile.id }
            val cards = ArrayList<CharacterCard>()
            // 内置模板是首次默认值：有已保存文档用文档，没有才用模板（Spec §4.8）
            TavernCardParser.getBuiltInCards().forEach { template ->
                val doc = documents[template.id]
                cards += if (doc != null) CharacterDocumentStore.projectCard(doc) else template
            }
            documents.values
                .filter { !it.profile.isBuiltIn }
                .forEach { cards += CharacterDocumentStore.projectCard(it) }
            if (documents.isEmpty()) {
                // 全新安装：落盘内置模板文档，之后的编辑走文档
                TavernCardParser.getBuiltInCards().forEach { template ->
                    documentStore.save(CharacterDocumentStore.documentFromCard(template))
                }
            }
            cards
        } catch (e: Exception) {
            e.printStackTrace()
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
        ensureMigrated()
        sessionsMutex.withLock {
            saveSessionListInternal(sessions)
        }
    }

    /**
     * 读取所有会话元数据列表 (进行自愈式数据清洗，防御 Gson 反序列化带来的内存 null 隐患)
     */
    suspend fun loadSessionList(): List<ChatSession> {
        ensureMigrated()
        return sessionsMutex.withLock {
            loadSessionListInternal()
        }
    }

    /**
     * 保存某个会话的消息列表
     */
    suspend fun saveSessionMessages(sessionId: String, messages: List<Message>) {
        ensureMigrated()
        messagesMutex.withLock {
            saveSessionMessagesInternal(sessionId, messages)
        }
    }

    /**
     * 读取某个会话的消息列表
     */
    suspend fun loadSessionMessages(sessionId: String): List<Message> {
        ensureMigrated()
        return messagesMutex.withLock {
            loadSessionMessagesInternal(sessionId)
        }
    }

    /**
     * 删除某个会话及其对应的消息文件
     */
    suspend fun deleteSession(sessionId: String) {
        ensureMigrated()
        sessionsMutex.withLock {
            messagesMutex.withLock {
                try {
                    // 1. 删除对应的具体消息 JSON 文件
                    val file = File(sessionsDir, "session_$sessionId.json")
                    if (file.exists()) {
                        file.delete()
                    }
                    // 1.1 删除会话专属世界书遗留文件（迁移前残留）
                    val wiFile = File(sessionsDir, "world_info_$sessionId.json")
                    if (wiFile.exists()) {
                        wiFile.delete()
                    }
                    // 1.2 清理书库中该会话的绑定（WorldInfo 2.0 Spec §6.5）：
                    // 仅本会话绑定的 owned 书整本删除（多会话共享只解绑）；卡书只解绑不删
                    runCatching {
                        worldInfoLibrary.loadAllBooks()
                            .filter { sessionId in it.sessionIds }
                            .forEach { book ->
                                val remaining = book.sessionIds - sessionId
                                if (!book.isOwned) {
                                    if (remaining.size < book.sessionIds.size) {
                                        worldInfoLibrary.saveBook(
                                            book.copy(sessionIds = remaining, updatedAt = System.currentTimeMillis())
                                        )
                                    }
                                } else if (remaining.isEmpty()) {
                                    worldInfoLibrary.deleteBook(book.id)
                                } else {
                                    worldInfoLibrary.saveBook(
                                        book.copy(sessionIds = remaining, updatedAt = System.currentTimeMillis())
                                    )
                                }
                            }
                    }.onFailure { it.printStackTrace() }
                    // 2. 从会话列表中移除并重新保存元数据
                    val currentSessions = loadSessionListInternal().filter { it.id != sessionId }
                    saveSessionListInternal(currentSessions)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // ---------- 世界书（WorldInfo 2.0：旧 shim 已随 W5 旧 UI 退役，统一走 worldInfoLibrary） ----------

    /**
     * 保存所有角色卡列表
     */
    suspend fun saveCharacterCards(cards: List<CharacterCard>) {
        ensureMigrated()
        cardsMutex.withLock {
            saveCharacterCardsInternal(cards)
        }
    }

    /**
     * 读取所有角色卡列表 (如不存在则自动注入内置角色模板)
     */
    suspend fun loadCharacterCards(): List<CharacterCard> {
        ensureMigrated()
        return cardsMutex.withLock {
            loadCharacterCardsInternal()
        }
    }

    // ---------- CharacterDocument 直通（导入与编辑回写使用） ----------

    suspend fun loadCharacterDocument(id: String): com.loyea.character.core.api.CharacterDocument? {
        ensureMigrated()
        return cardsMutex.withLock { documentStore.load(id) }
    }

    suspend fun saveCharacterDocument(document: com.loyea.character.core.api.CharacterDocument) {
        ensureMigrated()
        cardsMutex.withLock { documentStore.save(document) }
    }

    suspend fun characterDocumentExists(id: String): Boolean {
        ensureMigrated()
        return cardsMutex.withLock { documentStore.exists(id) }
    }

    /**
     * 原子化更新会话消息
     */
    suspend fun updateSessionMessages(sessionId: String, updateBlock: (List<Message>) -> List<Message>) {
        ensureMigrated()
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
        ensureMigrated()
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
        ensureMigrated()
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
        ensureMigrated()
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
    /**
     * 原子化累加某个会话的 token 用量（加性：prompt/completion 为增量，跨多次调用累计）。
     * lastContextTokens 传非 null 时覆盖（仅主聊天流更新），否则保留旧值。
     * promptCacheHitTokens/promptCacheMissTokens 传非 null 时加性累加（DeepSeek 前缀缓存），否则不变。
     */
    suspend fun updateSessionTokens(
        sessionId: String,
        promptTokens: Long,
        completionTokens: Long,
        lastContextTokens: Long? = null,
        promptCacheHitTokens: Long? = null,
        promptCacheMissTokens: Long? = null
    ) {
        ensureMigrated()
        updateSessionList { currentList ->
            currentList.map { session ->
                if (session.id == sessionId) {
                    session.copy(
                        promptTokens = session.promptTokens + promptTokens,
                        completionTokens = session.completionTokens + completionTokens,
                        lastContextTokens = lastContextTokens ?: session.lastContextTokens,
                        promptCacheHitTokens = session.promptCacheHitTokens + (promptCacheHitTokens ?: 0),
                        promptCacheMissTokens = session.promptCacheMissTokens + (promptCacheMissTokens ?: 0)
                    )
                } else {
                    session
                }
            }
        }
    }
}
