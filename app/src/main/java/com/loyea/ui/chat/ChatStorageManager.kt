package com.loyea.ui.chat

import com.loyea.context.core.*
import com.loyea.plugins.tavern.core.*
import com.loyea.plugins.tavern.storage.*

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.loyea.plugin.api.PluginIds
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

const val CHAT_SESSION_PERSONA_SCHEMA_VERSION = 1
const val CHAT_SESSION_SCHEMA_VERSION = 1

data class BackgroundOperationReceipt(
    val operationId: String,
    val sessionIncarnationId: String,
    val personaBindingRevision: Long,
    val personaOwnerId: String,
    val personaId: String
) {
    fun matches(binding: PersonaBindingSnapshot): Boolean =
        sessionIncarnationId == binding.sessionIncarnationId &&
            personaBindingRevision == binding.personaBindingRevision &&
            personaOwnerId == binding.ref.ownerId.value &&
            personaId == binding.ref.personaId
}

/**
 * 会话元数据实体
 */
data class ChatSession(
    val id: String,                  // 唯一标识符 (时间戳或UUID)
    val title: String,               // 会话标题
    val lastActiveTime: Long = System.currentTimeMillis(), // 最后活动时间，用于排序
    val characterId: String = "char_loyea_default", // 新增角色人格绑定
    val personaOwnerId: String = PluginIds.NATIVE.value, // 持久化 PersonaRef owner，禁止按卡片字段猜测归属
    val sessionIncarnationId: String = UUID.randomUUID().toString(), // 防止删除后复用 session id 绕过后台围栏
    val personaBindingRevision: Long = 1L, // 同一会话内每次人格绑定变化都递增，阻断 A→B→A
    val personaBindingSchemaVersion: Int = CHAT_SESSION_PERSONA_SCHEMA_VERSION,
    val appliedBackgroundOperations: List<BackgroundOperationReceipt> = emptyList(), // 有界幂等收据
    val useSystemTime: Boolean? = false, // 是否在此会话中使用真实系统时间
    val coreMemories: List<String> = emptyList(), // 会话核心记忆列表
    val isTitleSummarized: Boolean? = false, // 是否已由AI总结了标题
    val compressedSummary: String = "", // 长会话早期摘要（滑窗外的旧消息被压缩后保留故事脉络）
    val compressedAtCount: Int = 0, // 已参与压缩的消息条数（增量压缩断点）
    val promptTokens: Long = 0, // 本会话累计 prompt token（会话独立计量）
    val completionTokens: Long = 0, // 本会话累计 completion token
    val lastContextTokens: Long = 0, // 最近一次主聊天流请求的上下文 token（仅主聊天流更新，用于上下文窗口展示）
    val promptCacheHitTokens: Long = 0, // 本会话累计 DeepSeek 前缀缓存命中 token
    val promptCacheMissTokens: Long = 0, // 本会话累计 DeepSeek 前缀缓存未命中 token
    // SillyTavern chat-specific Author's Note; defaults are intentionally inert.
    val authorNote: String = "",
    val authorNotePosition: String = "in_chat",
    val authorNoteDepth: Int = 4,
    val authorNoteFrequency: Int = 1,
    /** Optional serialized Tavo/SillyTavern group roster for this chat session. */
    val groupChatJson: String? = null,
    /** Original one-line ST ChatHeader, retained so a JSONL re-export keeps chat metadata. */
    val tavernChatHeaderJson: String? = null,
    /** Parent chat name from ST chat_metadata.main_chat, when this session is a fork. */
    val tavernMainChat: String? = null,
    /** BRANCH or CHECKPOINT for a locally-created Tavern fork. */
    val tavernForkMode: String? = null,
    /** B2: 是否在会话列表中置顶。 */
    val isPinned: Boolean = false,
    /** B4: 会话级 API 绑定 id；null 表示跟随全局默认。序列化键 "apiBindingId"。 */
    val apiBindingId: String? = null,
    /** B7: 群聊上下文选角专用 API 绑定 id；null 表示跟随当前会话生效 API。序列化键 "speakerApiBindingId"。 */
    val speakerApiBindingId: String? = null,
    /** B5: 是否启用记忆；null 表示未配置、走全局默认。序列化键 "memoryEnabled"。 */
    val memoryEnabled: Boolean? = null,
    /** 敏感恢复的版本化迁移标记：新会话特性字段（isPinned/apiBindingId/memoryEnabled 等）引入时递增。 */
    val sessionSchemaVersion: Int = CHAT_SESSION_SCHEMA_VERSION
)

fun ChatSession.tavernGroupChat(): TavernGroupChat? = groupChatJson
    ?.takeIf(String::isNotBlank)
    ?.let(TavernGroupCodec::parse)

enum class BackgroundGreetingCommitStatus {
    COMMITTED,
    ALREADY_COMMITTED,
    STALE
}

data class BackgroundGreetingCommitOutcome(
    val status: BackgroundGreetingCommitStatus,
    val message: Message? = null
)

internal enum class BackgroundGreetingCommitStage {
    AFTER_JOURNAL_WRITE,
    AFTER_MESSAGE_WRITE,
    AFTER_SESSION_WRITE
}

private data class BackgroundGreetingJournal(
    val operationId: String,
    val sessionId: String,
    val sessionIncarnationId: String,
    val personaBindingRevision: Long,
    val personaOwnerId: String,
    val personaId: String,
    val message: Message,
    val promptTokens: Long,
    val completionTokens: Long,
    val lastActiveTime: Long
) {
    fun binding(): PersonaBindingSnapshot? = runCatching {
        PersonaBindingSnapshot(
            sessionId = sessionId,
            sessionIncarnationId = sessionIncarnationId,
            personaBindingRevision = personaBindingRevision,
            ref = com.loyea.plugin.api.PersonaRef(
                ownerId = com.loyea.plugin.api.PluginId.of(personaOwnerId),
                personaId = personaId
            )
        )
    }.getOrNull()
}

/**
 * 本地聊天会话及消息文件存储管理器
 */
class ChatStorageManager internal constructor(
    private val context: Context,
    private val backgroundGreetingFailureHook: ((BackgroundGreetingCommitStage) -> Unit)? = null
) {
    private val gson = Gson()
    // TODO1：wire 格式 v2 —— 序列化 character_cards.json 时排除 Tavern 扩展字段（读取仍用 gson）。
    private val wireGson = TavernCardWireFormat.createWireGson()
    private val sessionsFile = File(context.filesDir, "sessions_metadata.json")
    private val personaMigrationBackupFile =
        File(context.filesDir, "sessions_metadata.pre_persona_binding_v1.json")
    private val sessionSchemaMigrationBackupFile =
        File(context.filesDir, "sessions_metadata.pre_session_schema_v1.json")
    private val sessionsDir = File(context.filesDir, "sessions").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        private val sessionsMutex = Mutex()
        private val messagesMutex = Mutex()
        private val cardsMutex = Mutex()
        private val worldInfoMutex = Mutex()
        private val tavernResourcesMutex = Mutex()
        private const val BACKGROUND_GREETING_JOURNAL_PREFIX = "background_greeting_pending_"
        private const val MAX_BACKGROUND_OPERATION_IDS = 512

        fun backgroundGreetingMessageId(operationId: String): String =
            "background-greeting-$operationId"
    }

    private fun saveSessionListInternal(sessions: List<ChatSession>) =
        atomicWrite(sessionsFile, gson.toJson(sessions))

    private fun loadSessionListInternal(): List<ChatSession> {
        if (!sessionsFile.exists()) return emptyList()
        val json = try {
            sessionsFile.readText()
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(sessionsFile)
            return emptyList()
        }
        return try {
            val type = object : TypeToken<List<ChatSession>>() {}.type
            val rawList = gson.fromJson<List<ChatSession>>(json, type) ?: emptyList()
            var personaMigrationNeeded = false
            var schemaMigrationNeeded = false
            val normalized = rawList.map { raw ->
                val characterId = raw.characterId ?: ""
                val persistedOwnerId = raw.personaOwnerId ?: ""
                val personaOwnerId = persistedOwnerId.takeIf(String::isNotBlank)
                    ?: CharacterPersonaOwnership.legacyOwnerId(characterId).also {
                        personaMigrationNeeded = true
                    }
                val incarnationId = raw.sessionIncarnationId?.takeIf(String::isNotBlank)
                    ?: UUID.randomUUID().toString().also { personaMigrationNeeded = true }
                val bindingRevision = raw.personaBindingRevision.takeIf { it > 0L }
                    ?: 1L.also { personaMigrationNeeded = true }
                val schemaVersion = raw.personaBindingSchemaVersion
                    .takeIf { it >= CHAT_SESSION_PERSONA_SCHEMA_VERSION }
                    ?: CHAT_SESSION_PERSONA_SCHEMA_VERSION.also { personaMigrationNeeded = true }
                val sessionSchemaVersion = raw.sessionSchemaVersion
                    .takeIf { it >= CHAT_SESSION_SCHEMA_VERSION }
                    ?: CHAT_SESSION_SCHEMA_VERSION.also { schemaMigrationNeeded = true }
                ChatSession(
                    id = raw.id?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
                    title = raw.title ?: "Unnamed Chat",
                    lastActiveTime = raw.lastActiveTime,
                    characterId = characterId,
                    personaOwnerId = personaOwnerId,
                    sessionIncarnationId = incarnationId,
                    personaBindingRevision = bindingRevision,
                    personaBindingSchemaVersion = schemaVersion,
                    sessionSchemaVersion = sessionSchemaVersion,
                    appliedBackgroundOperations = raw.appliedBackgroundOperations ?: emptyList(),
                    useSystemTime = raw.useSystemTime ?: false,
                    coreMemories = raw.coreMemories ?: emptyList(),
                    isTitleSummarized = raw.isTitleSummarized ?: false,
                    compressedSummary = raw.compressedSummary ?: "",
                    compressedAtCount = raw.compressedAtCount ?: 0,
                    promptTokens = raw.promptTokens ?: 0,
                    completionTokens = raw.completionTokens ?: 0,
                    lastContextTokens = raw.lastContextTokens ?: 0,
                    promptCacheHitTokens = raw.promptCacheHitTokens ?: 0,
                    promptCacheMissTokens = raw.promptCacheMissTokens ?: 0,
                    authorNote = raw.authorNote ?: "",
                    authorNotePosition = raw.authorNotePosition ?: "in_chat",
                    authorNoteDepth = raw.authorNoteDepth ?: 4,
                    authorNoteFrequency = raw.authorNoteFrequency ?: 1,
                    groupChatJson = raw.groupChatJson?.takeIf(String::isNotBlank),
                    tavernChatHeaderJson = raw.tavernChatHeaderJson?.takeIf(String::isNotBlank),
                    tavernMainChat = raw.tavernMainChat?.takeIf(String::isNotBlank),
                    tavernForkMode = raw.tavernForkMode?.takeIf(String::isNotBlank),
                    isPinned = raw.isPinned ?: false,
                    apiBindingId = raw.apiBindingId?.takeIf(String::isNotBlank),
                    speakerApiBindingId = raw.speakerApiBindingId?.takeIf(String::isNotBlank),
                    memoryEnabled = raw.memoryEnabled
                )
            }
            if (personaMigrationNeeded || schemaMigrationNeeded) {
                try {
                    if (personaMigrationNeeded && !personaMigrationBackupFile.exists()) {
                        atomicWrite(personaMigrationBackupFile, json)
                    }
                    if (schemaMigrationNeeded && !sessionSchemaMigrationBackupFile.exists()) {
                        atomicWrite(sessionSchemaMigrationBackupFile, json)
                    }
                    saveSessionListInternal(normalized)
                } catch (migrationFailure: Exception) {
                    // 原文件仍保持有效；下次加载会重试迁移，不能把写入失败误判为源数据损坏。
                    migrationFailure.printStackTrace()
                }
            }
            normalized
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(sessionsFile)
            emptyList()
        }
    }

    private fun saveSessionMessagesInternal(sessionId: String, messages: List<Message>) {
        val file = File(sessionsDir, "session_$sessionId.json")
        atomicWrite(file, gson.toJson(messages))
    }

    private fun loadSessionMessagesStrictInternal(sessionId: String): List<Message> {
        val file = File(sessionsDir, "session_$sessionId.json")
        if (!file.exists()) return emptyList()
        val type = object : TypeToken<List<Message>>() {}.type
        return normalizeMessages(gson.fromJson<List<Message>>(file.readText(), type) ?: emptyList())
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
            normalizeMessages(list ?: emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(file)
            emptyList()
        }
    }

    private fun normalizeMessages(messages: List<Message>): List<Message> = messages.map { msg ->
        msg.copy(
            mcpCalls = msg.mcpCalls ?: emptyList(),
            versions = msg.versions ?: emptyList(),
            contentSegments = msg.contentSegments ?: emptyList(),
            llmTimeZoneId = msg.llmTimeZoneId?.takeIf { it.isNotBlank() }
                ?: java.util.TimeZone.getDefault().id
        )
    }

    private fun saveCharacterCardsInternal(cards: List<CharacterCard>) {
        try {
            // TODO1：wire 格式 v2 —— Tavern 扩展字段不再落盘（读取旧格式仍宽容，见 TavernCardWireFormat）。
            val json = wireGson.toJson(cards)
            atomicWrite(cardsFile, json)
            syncTavernCardDocumentsInternal(cards)
            syncPersonaSummariesInternal(cards)
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
            // D2 一次性备份：在 wire 迁移改写源文件之前，先按旧格式完整备份（幂等）。
            PersonaSummarySplitMigration.ensureBackup(cardsFile, personaSummaryBackupFile)
            // TODO1：wire 格式 v2 一次性迁移 —— 备份原文件、补齐插件文档库、以 v2 重写、写标记。
            TavernFieldDropMigration.ensureWireV2(
                sourceFile = cardsFile,
                backupFile = tavernFieldDropBackupFile,
                markerFile = tavernFieldDropMarkerFile,
                layout = tavernStorageLayout
            )
            val json = cardsFile.readText()
            val type = object : TypeToken<List<CharacterCard>>() {}.type
            val rawList = gson.fromJson<List<CharacterCard>>(json, type) ?: emptyList()
            // 进行自愈式清洗
            val normalized = rawList.map { raw ->
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
                    backgroundUri = raw.backgroundUri,
                    description = raw.description ?: "",
                    creatorNotes = raw.creatorNotes ?: "",
                    postHistoryInstructions = raw.postHistoryInstructions ?: "",
                    alternateGreetings = raw.alternateGreetings ?: emptyList(),
                    groupOnlyGreetings = raw.groupOnlyGreetings ?: emptyList(),
                    tags = raw.tags ?: emptyList(),
                    characterVersion = raw.characterVersion ?: "",
                    nickname = raw.nickname,
                    source = raw.source ?: emptyList(),
                    creationDate = raw.creationDate,
                    modificationDate = raw.modificationDate,
                    creatorNotesMultilingualJson = raw.creatorNotesMultilingualJson ?: "{}",
                    assetsJson = raw.assetsJson ?: "[]",
                    extensionsJson = raw.extensionsJson ?: "{}",
                    characterBookJson = raw.characterBookJson,
                    spec = raw.spec ?: "chara_card_v2",
                    specVersion = raw.specVersion ?: "2.0",
                    originalCardJson = raw.originalCardJson
                )
            }
            // TODO1：非内置卡从插件文档库补齐 Tavern 扩展字段（v2 wire 不再携带，文档库是唯一事实来源）。
            val enriched = normalized.map { card ->
                if (card.isBuiltIn) card
                else overlayTavernFromDocumentStore(card) ?: card
            }
            syncPersonaSummariesInternal(enriched)
            enriched
        } catch (e: Exception) {
            e.printStackTrace()
            backupCorruptFile(cardsFile)
            emptyList()
        }
    }

    /**
     * TODO1：从插件文档库读取非内置卡的 TavernCardDocument 并补齐扩展字段。
     * 文档缺失或解析失败时返回 null，调用方保留卡片的原生最小集（降级但不崩溃）。
     */
    private fun overlayTavernFromDocumentStore(card: CharacterCard): CharacterCard? {
        val rawJson = runCatching { tavernCardDocumentStore.read(card.id) }.getOrNull() ?: return null
        val document = runCatching { TavernCardCodec.parseJson(rawJson) }.getOrNull() ?: return null
        return TavernCharacterCardAdapter.overlayTavernFields(card, document)
    }

    /**
     * 原子写入：先写临时文件再重命名，避免中途崩溃（断电/进程被杀）产生半截 JSON 覆盖有效数据
     */
    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tmpFile).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    tmpFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
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
            val current = loadSessionListInternal()
            saveSessionListInternal(reconcilePersonaBindingRevisions(current, sessions))
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
     * Commits the parent-message update and a new Tavern branch/checkpoint under one lock scope.
     * Message files are written before the metadata list; a failed write restores the original
     * parent file and removes the not-yet-visible child file.
     */
    suspend fun saveTavernSessionFork(
        parentSessionId: String,
        parentMessages: List<Message>,
        childSession: ChatSession,
        childMessages: List<Message>
    ): List<ChatSession> = sessionsMutex.withLock sessionLock@{
        messagesMutex.withLock messageLock@{
            val current = loadSessionListInternal()
            check(current.any { it.id == parentSessionId }) {
                "Parent session '$parentSessionId' no longer exists"
            }
            require(current.none { it.id == childSession.id }) {
                "Child session '${childSession.id}' already exists"
            }
            val originalParentMessages = loadSessionMessagesInternal(parentSessionId)
            val proposedSessions = current + childSession
            try {
                saveSessionMessagesInternal(parentSessionId, parentMessages)
                saveSessionMessagesInternal(childSession.id, childMessages)
                val reconciled = reconcilePersonaBindingRevisions(current, proposedSessions)
                saveSessionListInternal(reconciled)
                reconciled
            } catch (failure: Throwable) {
                runCatching { saveSessionMessagesInternal(parentSessionId, originalParentMessages) }
                runCatching { File(sessionsDir, "session_${childSession.id}.json").delete() }
                throw failure
            }
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
                    // 1.1 删除会话专属世界书（如有）
                    val wiFile = File(sessionsDir, "world_info_$sessionId.json")
                    if (wiFile.exists()) {
                        wiFile.delete()
                    }
                    val wiStateFile = File(sessionsDir, "world_info_state_$sessionId.json")
                    if (wiStateFile.exists()) {
                        wiStateFile.delete()
                    }
                    // 2. 从会话列表中移除并重新保存元数据
                    val currentSessions = loadSessionListInternal().filter { it.id != sessionId }
                    saveSessionListInternal(currentSessions)
                    deleteGreetingJournalsForSessionInternal(sessionId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private val cardsFile = File(context.filesDir, "character_cards.json")

    // D2：CharacterCard → PersonaSummary / TavernCardDocument 拆分存储边界。
    // personaSummaryStoreFile 是宿主 PersonaSummary 存储（原生最小集）；
    // personaSummaryBackupFile 是一次性拆分迁移前的原始备份（沿用 pre_*_v1.json 命名纪律）。
    private val personaSummaryStoreFile = File(context.filesDir, "character_persona_summaries.json")
    private val personaSummaryBackupFile = File(context.filesDir, "character_cards.pre_persona_summary_v1.json")

    // TODO1：wire 格式 v2 迁移的原始备份与幂等标记（沿用 pre_*_v1.json 命名纪律）。
    private val tavernFieldDropBackupFile =
        File(context.filesDir, "character_cards.pre_tavern_field_drop_v1.json")
    private val tavernFieldDropMarkerFile =
        File(context.filesDir, "character_cards.tavern_field_drop_v1.marker")

    private val worldInfoFile = File(context.filesDir, "global_world_info.json")

    private val tavernStorageLayout = TavernStorageLayout(File(context.filesDir, "tavern"))
    private val tavernResourcesFile = tavernStorageLayout.registryFile
    // TODO1：插件私有卡片文档库（wire v2 下 Tavern 扩展字段的唯一事实来源）。
    private val tavernCardDocumentStore = TavernCardDocumentStore(tavernStorageLayout)
    private var tavernStorageMigrationDone = false

    /**
     * Copies the legacy registry into the plugin-private layout once. The legacy file remains
     * untouched for downgrade/recovery; new writes go only to the plugin-owned target.
     */
    private fun ensureTavernStorageMigrationInternal() {
        if (tavernStorageMigrationDone) return
        TavernStorageMigrator.migrate(
            sourceRoot = context.filesDir,
            layout = tavernStorageLayout,
            specs = listOf(
                TavernStorageFileSpec(
                    sourceRelativePath = "tavern_resources.json",
                    targetRelativePath = tavernStorageLayout.registryRelativePath,
                    required = false
                )
            )
        )
        tavernStorageMigrationDone = true
    }

    /** Persist imported card documents separately from the host CharacterCard projection. */
    private fun syncTavernCardDocumentsInternal(cards: List<CharacterCard>) {
        runCatching { ensureTavernStorageMigrationInternal() }
            .onFailure { it.printStackTrace(); return }
        cards.asSequence()
            .filterNot(CharacterCard::isBuiltIn)
            .forEach { card ->
                runCatching {
                    val rawJson = TavernCardCodec.toJson(TavernCharacterCardAdapter.toDocument(card))
                    if (rawJson.isNotBlank()) {
                        tavernCardDocumentStore.write(card.id, rawJson)
                    }
                }.onFailure { it.printStackTrace() }
            }
    }

    /**
     * 落一份宿主 PersonaSummary 存储（D2：原生最小集）。
     * 只保留非内置卡片的原生投影；Tavern 完整字段走 [syncTavernCardDocumentsInternal]。与插件
     * 文档存储配合，构成“拆两个新结构写回”的宿主侧一半。
     *
     * TODO(D4)：宿主核心签名清理完成后，本存储应成为宿主运行时唯一的人格持久化来源，
     * `character_cards.json`（遗留桥类型）可退化为仅服务旧会话/旧图迁移。
     */
    private fun syncPersonaSummariesInternal(cards: List<CharacterCard>) {
        runCatching {
            val summaries = cards.asSequence()
                .filterNot(CharacterCard::isBuiltIn)
                .map(TavernCharacterCardAdapter::toPersonaSummary)
                .toList()
            atomicWrite(personaSummaryStoreFile, gson.toJson(summaries))
        }.onFailure { it.printStackTrace() }
    }

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
     * 自愈式清洗：Gson 反射对缺失字段不触发 Kotlin 默认值，逐字段兜底。
     * 原始类型（Int/Boolean）缺失时 Gson 保持 JVM 默认（0/false），
     * 此时 probability 会退化为 0、allowRecursion 退化为 false ——
     * 均与 v0.5.1 旧行为（无概率、无递归）等价，属保守兼容。
     */
    private fun selfHealWorldInfo(rawList: List<WorldInfoEntry>): List<WorldInfoEntry> =
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
                disable = raw.disable ?: false,
                selectiveLogic = raw.selectiveLogic ?: 0,
                group = raw.group ?: "",
                probability = raw.probability ?: 100,
                useProbability = raw.useProbability ?: false,
                delayUntilRecursion = raw.delayUntilRecursion ?: 0,
                preventRecursion = raw.preventRecursion ?: false,
                allowRecursion = raw.allowRecursion ?: true,
                excludeRecursion = raw.excludeRecursion ?: false,
                keysContainedIn = raw.keysContainedIn ?: "chat",
                position = raw.position ?: 0,
                weight = raw.weight ?: 0,
                useRegex = raw.useRegex ?: false,
                caseSensitive = raw.caseSensitive,
                matchWholeWords = raw.matchWholeWords,
                positionType = raw.positionType ?: "legacy",
                injectionDepth = raw.injectionDepth ?: 0,
                role = raw.role,
                outletName = raw.outletName,
                groupOverride = raw.groupOverride ?: false,
                groupWeight = raw.groupWeight ?: 100,
                useGroupScoring = raw.useGroupScoring ?: false,
                priority = raw.priority,
                scanDepthOverride = raw.scanDepthOverride,
                sticky = raw.sticky ?: 0,
                cooldown = raw.cooldown ?: 0,
                delay = raw.delay ?: 0,
                triggers = raw.triggers ?: emptyList(),
                extensionsJson = raw.extensionsJson ?: "{}",
                automationId = raw.automationId ?: "",
                vectorized = raw.vectorized ?: false,
                matchPersonaDescription = raw.matchPersonaDescription ?: false,
                matchCharacterDescription = raw.matchCharacterDescription ?: false,
                matchCharacterPersonality = raw.matchCharacterPersonality ?: false,
                matchCharacterDepthPrompt = raw.matchCharacterDepthPrompt ?: false,
                matchScenario = raw.matchScenario ?: false,
                matchCreatorNotes = raw.matchCreatorNotes ?: false,
                ignoreBudget = raw.ignoreBudget ?: false,
                characterFilterNames = raw.characterFilterNames ?: emptyList(),
                characterFilterTags = raw.characterFilterTags ?: emptyList(),
                characterFilterExclude = raw.characterFilterExclude ?: false,
                addMemo = raw.addMemo ?: true,
                displayIndex = raw.displayIndex ?: 0,
                rawJson = raw.rawJson
                )
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
                selfHealWorldInfo(rawList)
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
                    selfHealWorldInfo(gson.fromJson<List<WorldInfoEntry>>(json, type) ?: emptyList())
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
     * 某会话专属世界书文件路径（文件存在 = 该会话自定义书，替代全局书）。
     */
    private fun sessionWorldInfoFile(sessionId: String): File = File(sessionsDir, "world_info_$sessionId.json")

    private fun sessionWorldInfoRuntimeStateFile(sessionId: String): File =
        File(sessionsDir, "world_info_state_$sessionId.json")

    /**
     * 读取某会话专属世界书；文件不存在返回 null（调用方回退全局书）。
     */
    suspend fun loadSessionWorldInfo(sessionId: String): WorldInfoBook? {
        return worldInfoMutex.withLock {
            val file = sessionWorldInfoFile(sessionId)
            if (!file.exists()) return@withLock null
            try {
                val obj = gson.fromJson(file.readText(), JsonObject::class.java)
                val entries = if (obj.has("entries")) {
                    val type = object : TypeToken<List<WorldInfoEntry>>() {}.type
                    selfHealWorldInfo(gson.fromJson<List<WorldInfoEntry>>(obj.getAsJsonArray("entries"), type) ?: emptyList())
                } else {
                    emptyList()
                }
                val config = if (obj.has("config")) {
                    WorldInfoConfigStorage.fromJson(obj.get("config").toString())
                } else {
                    WorldInfoConfig()
                }
                WorldInfoBook(entries = entries, config = config)
            } catch (e: Exception) {
                e.printStackTrace()
                backupCorruptFile(file)
                null
            }
        }
    }

    /**
     * 保存某会话专属世界书（写入即建立"自定义书"，匹配时完全替代全局书）。
     */
    suspend fun saveSessionWorldInfo(sessionId: String, book: WorldInfoBook) {
        worldInfoMutex.withLock {
            try {
                val obj = JsonObject()
                obj.add("entries", gson.toJsonTree(book.entries))
                obj.add(
                    "config",
                    gson.fromJson(WorldInfoConfigStorage.toJson(book.config), JsonObject::class.java)
                )
                atomicWrite(sessionWorldInfoFile(sessionId), obj.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 删除某会话专属世界书（该会话恢复回退全局书）。
     */
    suspend fun deleteSessionWorldInfo(sessionId: String) {
        worldInfoMutex.withLock {
            try {
                val file = sessionWorldInfoFile(sessionId)
                if (file.exists()) {
                    file.delete()
                }
                val stateFile = sessionWorldInfoRuntimeStateFile(sessionId)
                if (stateFile.exists()) {
                    stateFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 读取会话专属世界书的 sticky/cooldown 运行时状态；旧会话没有文件时返回空状态。 */
    suspend fun loadSessionWorldInfoRuntimeState(sessionId: String): WorldInfoRuntimeState {
        return worldInfoMutex.withLock {
            val file = sessionWorldInfoRuntimeStateFile(sessionId)
            if (!file.exists()) return@withLock WorldInfoRuntimeState()
            try {
                val raw = gson.fromJson(file.readText(), WorldInfoRuntimeState::class.java)
                WorldInfoRuntimeState(
                    turnKey = raw?.turnKey ?: "",
                    turnIndex = raw?.turnIndex ?: 0L,
                    bookSignature = raw?.bookSignature ?: "",
                    entries = raw?.entries.orEmpty().mapNotNull { (id, state) ->
                        id?.takeIf { it.isNotBlank() }?.let { key ->
                            key to WorldInfoEntryRuntimeState(
                                lastActivatedTurn = state?.lastActivatedTurn ?: -1L,
                                stickyUntilTurn = state?.stickyUntilTurn ?: -1L,
                                cooldownUntilTurn = state?.cooldownUntilTurn ?: -1L
                            )
                        }
                    }.toMap()
                )
            } catch (e: Exception) {
                e.printStackTrace()
                backupCorruptFile(file)
                WorldInfoRuntimeState()
            }
        }
    }

    /** 原子保存会话世界书的 timed runtime state，不修改用户可见世界书内容。 */
    suspend fun saveSessionWorldInfoRuntimeState(sessionId: String, state: WorldInfoRuntimeState) {
        worldInfoMutex.withLock {
            try {
                atomicWrite(sessionWorldInfoRuntimeStateFile(sessionId), gson.toJson(state))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 清除会话世界书及其运行时状态，恢复真正的全局回退语义。 */
    suspend fun deleteSessionWorldInfoRuntimeState(sessionId: String) {
        worldInfoMutex.withLock {
            try {
                val file = sessionWorldInfoRuntimeStateFile(sessionId)
                if (file.exists()) file.delete()
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
     * 读取宿主 PersonaSummary 存储（D2 原生最小集）。业务运行时优先消费本视图，不再直接读
     * CharacterCard 的 Tavern 扩展字段。存储缺失时（极端情况）回退为对已加载卡片实时投影，
     * 保证返回结果始终可用。
     */
    suspend fun loadPersonaSummaries(): List<PersonaSummary> {
        return cardsMutex.withLock {
            if (personaSummaryStoreFile.isFile) {
                try {
                    val type = object : TypeToken<List<PersonaSummary>>() {}.type
                    val stored = gson.fromJson<List<PersonaSummary>>(personaSummaryStoreFile.readText(), type)
                    if (!stored.isNullOrEmpty()) return@withLock stored
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            loadCharacterCardsInternal()
                .asSequence()
                .filterNot(CharacterCard::isBuiltIn)
                .map(TavernCharacterCardAdapter::toPersonaSummary)
                .toList()
        }
    }

    /** 读取外部酒馆资源注册表；文件不存在时返回空注册表。 */
    suspend fun loadTavernResourceRegistry(): TavernResourceRegistry {
        return tavernResourcesMutex.withLock {
            ensureTavernStorageMigrationInternal()
            if (!tavernResourcesFile.exists()) return@withLock TavernResourceRegistry()
            try {
                TavernResourceRegistryCodec.parse(tavernResourcesFile.readText())
                    ?: TavernResourceRegistry()
            } catch (e: Exception) {
                e.printStackTrace()
                backupCorruptFile(tavernResourcesFile)
                TavernResourceRegistry()
            }
        }
    }

    /** 原子保存外部酒馆资源注册表。revision 由调用方递增，供运行时缓存失效。 */
    suspend fun saveTavernResourceRegistry(registry: TavernResourceRegistry) {
        tavernResourcesMutex.withLock {
            try {
                ensureTavernStorageMigrationInternal()
                atomicWrite(tavernResourcesFile, TavernResourceRegistryCodec.toJson(registry))
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    /** Updates messages only while the same session incarnation and persona binding still exist. */
    suspend fun updateSessionMessagesIfPersonaBinding(
        binding: PersonaBindingSnapshot,
        updateBlock: (List<Message>) -> List<Message>
    ): List<Message>? = sessionsMutex.withLock sessionLock@{
        messagesMutex.withLock messageLock@{
            val session = loadSessionListInternal().firstOrNull { it.id == binding.sessionId }
            if (!binding.matches(session)) return@messageLock null
            val updated = updateBlock(loadSessionMessagesStrictInternal(binding.sessionId))
            saveSessionMessagesInternal(binding.sessionId, updated)
            updated
        }
    }

    /**
     * Resumes a previously journaled greeting without another model request. Returns null when
     * this Work operation has never reached the durable journal stage.
     */
    suspend fun resumeBackgroundGreeting(
        operationId: String,
        binding: PersonaBindingSnapshot
    ): BackgroundGreetingCommitOutcome? {
        validateBackgroundOperationId(operationId)
        return sessionsMutex.withLock sessionLock@{
            messagesMutex.withLock messageLock@{
                val currentSession = loadSessionListInternal().firstOrNull { it.id == binding.sessionId }
                val existingReceipt = currentSession?.appliedBackgroundOperations
                    ?.firstOrNull { it.operationId == operationId }
                if (existingReceipt != null) {
                    greetingJournalFile(operationId).delete()
                    val message = loadSessionMessagesStrictInternal(binding.sessionId)
                        .firstOrNull { it.id == backgroundGreetingMessageId(operationId) }
                    return@messageLock BackgroundGreetingCommitOutcome(
                        status = if (existingReceipt.matches(binding)) {
                            BackgroundGreetingCommitStatus.ALREADY_COMMITTED
                        } else {
                            BackgroundGreetingCommitStatus.STALE
                        },
                        message = message.takeIf { existingReceipt.matches(binding) }
                    )
                }
                val journal = loadGreetingJournalInternal(operationId) ?: return@messageLock null
                if (journal.operationId != operationId || journal.binding() != binding) {
                    discardGreetingJournalInternal(journal)
                    return@messageLock BackgroundGreetingCommitOutcome(BackgroundGreetingCommitStatus.STALE)
                }
                applyGreetingJournalInternal(journal, binding)
            }
        }
    }

    /** Removes an uncommitted greeting fragment when a plugin/card is no longer available. */
    suspend fun discardPendingBackgroundGreeting(operationId: String) {
        validateBackgroundOperationId(operationId)
        sessionsMutex.withLock {
            messagesMutex.withLock {
                loadGreetingJournalInternal(operationId)?.let(::discardGreetingJournalInternal)
            }
        }
    }

    /**
     * Crash-recoverable, idempotent two-file commit. The journal is durable before either target
     * file changes; retries repair a missing side and never add the same token delta twice.
     */
    suspend fun commitBackgroundGreeting(
        operationId: String,
        binding: PersonaBindingSnapshot,
        message: Message,
        promptTokens: Long,
        completionTokens: Long,
        lastActiveTime: Long
    ): BackgroundGreetingCommitOutcome {
        validateBackgroundOperationId(operationId)
        require(message.id == backgroundGreetingMessageId(operationId)) {
            "Background greeting message id must be derived from its operation id"
        }
        require(message.characterId == binding.ref.personaId) {
            "Background greeting character does not match its persona binding"
        }
        require(promptTokens >= 0L && completionTokens >= 0L) {
            "Background greeting token deltas must not be negative"
        }
        val journal = BackgroundGreetingJournal(
            operationId = operationId,
            sessionId = binding.sessionId,
            sessionIncarnationId = binding.sessionIncarnationId,
            personaBindingRevision = binding.personaBindingRevision,
            personaOwnerId = binding.ref.ownerId.value,
            personaId = binding.ref.personaId,
            message = message,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            lastActiveTime = lastActiveTime
        )
        return sessionsMutex.withLock sessionLock@{
            messagesMutex.withLock messageLock@{
                val currentSessions = loadSessionListInternal()
                val target = currentSessions.firstOrNull { it.id == binding.sessionId }
                val existingReceipt = target?.appliedBackgroundOperations
                    ?.firstOrNull { it.operationId == operationId }
                if (existingReceipt != null) {
                    greetingJournalFile(operationId).delete()
                    return@messageLock BackgroundGreetingCommitOutcome(
                        status = if (existingReceipt.matches(binding)) {
                            BackgroundGreetingCommitStatus.ALREADY_COMMITTED
                        } else {
                            BackgroundGreetingCommitStatus.STALE
                        },
                        message = loadSessionMessagesStrictInternal(binding.sessionId)
                            .firstOrNull { it.id == message.id }
                            .takeIf { existingReceipt.matches(binding) }
                    )
                }
                if (!binding.matches(target)) {
                    loadGreetingJournalInternal(operationId)?.let(::discardGreetingJournalInternal)
                    return@messageLock BackgroundGreetingCommitOutcome(BackgroundGreetingCommitStatus.STALE)
                }
                atomicWrite(greetingJournalFile(operationId), gson.toJson(journal))
                backgroundGreetingFailureHook?.invoke(BackgroundGreetingCommitStage.AFTER_JOURNAL_WRITE)
                applyGreetingJournalInternal(journal, binding)
            }
        }
    }

    private fun applyGreetingJournalInternal(
        journal: BackgroundGreetingJournal,
        binding: PersonaBindingSnapshot
    ): BackgroundGreetingCommitOutcome {
        val currentSessions = loadSessionListInternal()
        val targetIndex = currentSessions.indexOfFirst { it.id == binding.sessionId }
        val target = currentSessions.getOrNull(targetIndex)
        val existingReceipt = target?.appliedBackgroundOperations
            ?.firstOrNull { it.operationId == journal.operationId }
        if (existingReceipt != null) {
            if (!existingReceipt.matches(binding)) {
                greetingJournalFile(journal.operationId).delete()
                return BackgroundGreetingCommitOutcome(BackgroundGreetingCommitStatus.STALE)
            }
            val messages = loadSessionMessagesStrictInternal(binding.sessionId)
            if (messages.none { it.id == journal.message.id }) {
                saveSessionMessagesInternal(binding.sessionId, messages + journal.message)
            }
            greetingJournalFile(journal.operationId).delete()
            return BackgroundGreetingCommitOutcome(
                BackgroundGreetingCommitStatus.ALREADY_COMMITTED,
                journal.message
            )
        }
        if (!binding.matches(target) || journal.binding() != binding) {
            discardGreetingJournalInternal(journal)
            return BackgroundGreetingCommitOutcome(BackgroundGreetingCommitStatus.STALE)
        }
        val boundTarget = requireNotNull(target)

        val currentMessages = loadSessionMessagesStrictInternal(binding.sessionId)
        val messageIndex = currentMessages.indexOfFirst { it.id == journal.message.id }
        val updatedMessages = when {
            messageIndex < 0 -> currentMessages + journal.message
            currentMessages[messageIndex] == journal.message -> currentMessages
            else -> currentMessages.toMutableList().apply { this[messageIndex] = journal.message }
        }
        if (updatedMessages !== currentMessages) {
            saveSessionMessagesInternal(binding.sessionId, updatedMessages)
        }
        backgroundGreetingFailureHook?.invoke(BackgroundGreetingCommitStage.AFTER_MESSAGE_WRITE)

        val receipt = BackgroundOperationReceipt(
            operationId = journal.operationId,
            sessionIncarnationId = binding.sessionIncarnationId,
            personaBindingRevision = binding.personaBindingRevision,
            personaOwnerId = binding.ref.ownerId.value,
            personaId = binding.ref.personaId
        )
        val appliedOperations = (boundTarget.appliedBackgroundOperations + receipt)
            .distinctBy(BackgroundOperationReceipt::operationId)
            .takeLast(MAX_BACKGROUND_OPERATION_IDS)
        val updatedTarget = boundTarget.copy(
            lastActiveTime = journal.lastActiveTime,
            promptTokens = boundTarget.promptTokens + journal.promptTokens,
            completionTokens = boundTarget.completionTokens + journal.completionTokens,
            appliedBackgroundOperations = appliedOperations
        )
        val updatedSessions = currentSessions.toMutableList().apply { this[targetIndex] = updatedTarget }
            .sortedByDescending(ChatSession::lastActiveTime)
        saveSessionListInternal(updatedSessions)
        backgroundGreetingFailureHook?.invoke(BackgroundGreetingCommitStage.AFTER_SESSION_WRITE)
        greetingJournalFile(journal.operationId).delete()
        return BackgroundGreetingCommitOutcome(BackgroundGreetingCommitStatus.COMMITTED, journal.message)
    }

    private fun discardGreetingJournalInternal(journal: BackgroundGreetingJournal) {
        val sessions = loadSessionListInternal()
        val target = sessions.firstOrNull { it.id == journal.sessionId }
        if (target?.appliedBackgroundOperations.orEmpty().none { it.operationId == journal.operationId }) {
            val messages = loadSessionMessagesStrictInternal(journal.sessionId)
            val filtered = messages.filterNot { it.id == journal.message.id }
            if (filtered.size != messages.size) saveSessionMessagesInternal(journal.sessionId, filtered)
        }
        greetingJournalFile(journal.operationId).delete()
    }

    private fun deleteGreetingJournalsForSessionInternal(sessionId: String) {
        sessionsDir.listFiles { file ->
            file.name.startsWith(BACKGROUND_GREETING_JOURNAL_PREFIX) && file.name.endsWith(".json")
        }.orEmpty().forEach { file ->
            val journal = runCatching {
                gson.fromJson(file.readText(), BackgroundGreetingJournal::class.java)
            }.getOrNull()
            if (journal?.sessionId == sessionId) file.delete()
        }
    }

    private fun loadGreetingJournalInternal(operationId: String): BackgroundGreetingJournal? {
        val file = greetingJournalFile(operationId)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), BackgroundGreetingJournal::class.java)
        } catch (failure: Exception) {
            backupCorruptFile(file)
            null
        }
    }

    private fun greetingJournalFile(operationId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(operationId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(sessionsDir, "$BACKGROUND_GREETING_JOURNAL_PREFIX$digest.json")
    }

    private fun validateBackgroundOperationId(operationId: String) {
        require(operationId.isNotBlank() && operationId.length <= 128) {
            "Background operation id must contain 1..128 characters"
        }
        require(operationId.none(Char::isISOControl)) {
            "Background operation id must not contain control characters"
        }
    }

    /**
     * 原子化更新会话列表
     */
    suspend fun updateSessionList(updateBlock: (List<ChatSession>) -> List<ChatSession>) {
        sessionsMutex.withLock {
            val current = loadSessionListInternal()
            val updated = reconcilePersonaBindingRevisions(current, updateBlock(current))
            saveSessionListInternal(updated)
        }
    }

    /** Atomically persists a Tavern/Tavo group roster without changing persona binding fields. */
    suspend fun updateSessionGroupChat(sessionId: String, group: TavernGroupChat?) {
        val groupJson = group?.let(TavernGroupCodec::toJson)
        updateSessionList { current ->
            current.map { session ->
                if (session.id == sessionId) session.copy(groupChatJson = groupJson) else session
            }
        }
    }

    private fun reconcilePersonaBindingRevisions(
        current: List<ChatSession>,
        proposed: List<ChatSession>
    ): List<ChatSession> {
        val currentById = current.associateBy(ChatSession::id)
        return proposed.map { incoming ->
            val old = currentById[incoming.id]
            val incarnation = incoming.sessionIncarnationId.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString()
            val normalized = incoming.copy(
                sessionIncarnationId = incarnation,
                personaBindingRevision = incoming.personaBindingRevision.coerceAtLeast(1L),
                personaBindingSchemaVersion = CHAT_SESSION_PERSONA_SCHEMA_VERSION,
                appliedBackgroundOperations = incoming.appliedBackgroundOperations
            )
            when {
                old == null || old.sessionIncarnationId != incarnation -> normalized
                old.personaOwnerId != normalized.personaOwnerId ||
                    old.characterId != normalized.characterId -> normalized.copy(
                    personaBindingRevision = maxOf(
                        old.personaBindingRevision.coerceAtLeast(1L) + 1L,
                        normalized.personaBindingRevision
                    )
                )
                else -> normalized.copy(
                    personaBindingRevision = maxOf(
                        old.personaBindingRevision.coerceAtLeast(1L),
                        normalized.personaBindingRevision
                    )
                )
            }
        }
    }

    suspend fun isPersonaBindingCurrent(binding: PersonaBindingSnapshot): Boolean =
        sessionsMutex.withLock {
            binding.matches(loadSessionListInternal().firstOrNull { it.id == binding.sessionId })
        }

    /** Applies worker metadata only while the same persisted PersonaRef still owns the session. */
    suspend fun updateSessionIfPersonaBinding(
        binding: PersonaBindingSnapshot,
        updateBlock: (ChatSession) -> ChatSession
    ): Boolean = sessionsMutex.withLock updateLock@{
        val current = loadSessionListInternal()
        val targetIndex = current.indexOfFirst { it.id == binding.sessionId }
        if (targetIndex < 0 || !binding.matches(current[targetIndex])) return@updateLock false
        val updatedSession = updateBlock(current[targetIndex])
        check(binding.matches(updatedSession)) { "Persona-fenced update changed the session binding" }
        saveSessionListInternal(current.toMutableList().apply { this[targetIndex] = updatedSession })
        true
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

    // ---------- B2 克隆 / 重启存储 API ----------

    /**
     * 把宿主的原生 [Message] 会话投影成核心 [TavernChatFile]，供克隆/重启规划器使用。
     * header 从 [ChatSession.tavernChatHeaderJson] 解析保留，消息经
     * [TavernChatSessionCodec.exportJsonl] + [TavernChatFileCodec.parse] 无损往返。
     */
    private fun sessionAsTavernChatFile(session: ChatSession, messages: List<Message>): TavernChatFile {
        val header = session.tavernChatHeaderJson
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { TavernChatFileCodec.parse(raw).chat.header }.getOrNull() }
            ?: TavernChatHeader()
        val messageJsonl = TavernChatSessionCodec.exportJsonl(
            messages = messages,
            userName = "",
            characterName = "",
            chatMetadataJson = "{}",
            createDate = null,
            headerRawJson = null
        )
        val records = TavernChatFileCodec.parse(messageJsonl).chat.messages
        return TavernChatFile(header = header, messages = records, chatName = session.title)
    }

    /** 把核心产出的 [TavernChatFile] 写回宿主原生投影：返回 (消息列表, 单行 header JSON)。 */
    private fun importTavernChatFile(chat: TavernChatFile): Pair<List<Message>, String?> {
        val jsonl = TavernChatFileCodec.toJsonl(chat)
        val imported = TavernChatSessionCodec.importJsonl(jsonl)
        val headerJson = TavernChatFileCodec.toJsonl(TavernChatFile(header = imported.header))
            .lineSequence()
            .firstOrNull()
            ?.takeIf(String::isNotBlank)
        return imported.messages to headerJson
    }

    /**
     * B2 存储 API：克隆会话。复用核心 [TavernChatLifecyclePlanner.cloneChat]，
     * 消息经 tavern 往返后写入全新的独立会话文件与元数据；源会话不被修改。
     *
     * @return 新建的 [ChatSession]，若 [srcSessionId] 不存在则返回 null。
     * 克隆会继承源的 apiBindingId / memoryEnabled 等配置类字段，但重置 isPinned=false、获得新 id 与 incarnation。
     */
    suspend fun cloneSession(srcSessionId: String, newName: String): ChatSession? =
        sessionsMutex.withLock sessionLock@{
            messagesMutex.withLock messageLock@{
                val current = loadSessionListInternal()
                val source = current.firstOrNull { it.id == srcSessionId } ?: return@messageLock null
                val clonedChat = TavernChatLifecyclePlanner.cloneChat(
                    source = sessionAsTavernChatFile(source, loadSessionMessagesStrictInternal(srcSessionId)),
                    newChatName = newName
                )
                val (childMessages, childHeaderJson) = importTavernChatFile(clonedChat)
                val child = source.copy(
                    id = UUID.randomUUID().toString(),
                    title = newName,
                    sessionIncarnationId = UUID.randomUUID().toString(),
                    tavernChatHeaderJson = childHeaderJson,
                    lastActiveTime = System.currentTimeMillis(),
                    appliedBackgroundOperations = emptyList(),
                    promptTokens = 0,
                    completionTokens = 0,
                    lastContextTokens = 0,
                    promptCacheHitTokens = 0,
                    promptCacheMissTokens = 0,
                    compressedSummary = "",
                    compressedAtCount = 0,
                    isPinned = false
                )
                val proposed = current + child
                try {
                    saveSessionMessagesInternal(child.id, childMessages)
                    saveSessionListInternal(reconcilePersonaBindingRevisions(current, proposed))
                    child
                } catch (failure: Throwable) {
                    runCatching { File(sessionsDir, "session_${child.id}.json").delete() }
                    throw failure
                }
            }
        }

    /**
     * B2 存储 API：重启会话。复用核心 [TavernChatLifecyclePlanner.restartChat]
     * 清空全部消息、保留 header 配置并把 createDate 重置为当前时刻。
     *
     * @return 会话是否存在并成功重启。
     */
    suspend fun restartSession(sessionId: String): Boolean =
        sessionsMutex.withLock sessionLock@{
            messagesMutex.withLock messageLock@{
                val current = loadSessionListInternal()
                val index = current.indexOfFirst { it.id == sessionId }
                if (index < 0) return@messageLock false
                val session = current[index]
                val restarted = TavernChatLifecyclePlanner.restartChat(
                    sessionAsTavernChatFile(session, loadSessionMessagesStrictInternal(sessionId))
                )
                val (emptyMessages, headerJson) = importTavernChatFile(restarted)
                val updated = session.copy(
                    tavernChatHeaderJson = headerJson,
                    lastActiveTime = System.currentTimeMillis(),
                    appliedBackgroundOperations = emptyList(),
                    promptTokens = 0,
                    completionTokens = 0,
                    lastContextTokens = 0,
                    promptCacheHitTokens = 0,
                    promptCacheMissTokens = 0,
                    compressedSummary = "",
                    compressedAtCount = 0
                )
                saveSessionMessagesInternal(sessionId, emptyMessages)
                saveSessionListInternal(current.toMutableList().apply { this[index] = updated })
                true
            }
        }
}
