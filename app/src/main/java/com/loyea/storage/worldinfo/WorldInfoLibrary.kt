package com.loyea.storage.worldinfo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.character.core.codec.CardBookAdapter
import com.loyea.character.core.codec.CharacterCardCodec
import com.loyea.storage.CharacterDocumentStore
import com.loyea.ui.chat.WorldInfoBridge
import com.loyea.ui.chat.WorldInfoConfig
import com.loyea.ui.chat.WorldInfoConfigStorage
import com.loyea.ui.chat.WorldInfoEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom

/**
 * 生效书解析的纯函数部分（WorldInfo 2.0 Spec §4.1）。
 * 层级：SESSION_BOUND（会话绑定）> CARD_FOLLOW（随角色卡）> GLOBAL_ACTIVE（全局生效）> NONE。
 * 解析只看 sessionIds / isGlobalActive / origin，scope 字段是展示元数据不参与判定
 * （书可同时全局生效并绑定若干会话：绑定会话走层 1，其余会话走层 3，互不降级）。
 * 同层多本命中（数据异常态）按 createdAt 升序取首本；其余书由
 * [sessionBoundConflicts] 暴露给 UI 标「冲突」徽章，不静默随机。
 */
object ActiveBookResolver {

    fun pick(
        books: List<WorldInfoBookDocument>,
        sessionId: String,
        characterId: String?
    ): Pair<ActiveBookSource, WorldInfoBookDocument?> {
        books.filter { sessionId in it.sessionIds }
            .minByOrNull { it.createdAt }
            ?.let { return ActiveBookSource.SESSION_BOUND to it }
        if (characterId != null) {
            books.filter {
                it.origin == WorldInfoBookOrigin.CARD && it.originCharacterId == characterId
            }.minByOrNull { it.createdAt }
                ?.let { return ActiveBookSource.CARD_FOLLOW to it }
        }
        books.filter { it.isGlobalActive }
            .minByOrNull { it.createdAt }
            ?.let { return ActiveBookSource.GLOBAL_ACTIVE to it }
        return ActiveBookSource.NONE to null
    }

    /** 与 pick 首选同层但被 tie-break 落选的书（供 UI 冲突提示）。 */
    fun sessionBoundConflicts(books: List<WorldInfoBookDocument>, sessionId: String): List<WorldInfoBookDocument> {
        val bound = books
            .filter { sessionId in it.sessionIds }
            .sortedBy { it.createdAt }
        return if (bound.size > 1) bound.drop(1) else emptyList()
    }
}

/**
 * 世界书统一书库（WorldInfo 2.0 Spec §3–§5）。
 *
 * - 书 CRUD：worldinfo/books/<bookId>.json，单文件原子写；
 * - 单一生效书解析：[resolveActiveBook]，输出 core 格式条目 + 已合并配置，
 *   两条提示词编译路径（原生稳定前缀 / 导入卡 CharacterCompiler）统一消费；
 * - 条目开关：owned 书按 entry.id（内容可编辑）；card 书按 uid（override 层，
 *   不修改卡原文，重复导入同卡自动保留）；
 * - 一次性迁移：[migrateIfNeeded]，staging + manifest 原子切换，幂等可重试。
 *
 * 本类不持 Compose 状态、不认识 ViewModel；匹配/预算/插入仍由
 * character-core 的 WorldInfoMatcher / CharacterCompiler 执行（父 Spec §10 单一实现）。
 */
class WorldInfoLibrary(private val storageRoot: File) {

    private val worldinfoDir get() = File(storageRoot, "worldinfo")
    private val booksDir get() = File(worldinfoDir, "books")
    private val stagingDir get() = File(storageRoot, "worldinfo.staging")
    private val manifestFile get() = File(worldinfoDir, "manifest.json")
    private val characterStore get() = CharacterDocumentStore(File(storageRoot, "characters"))

    private val gson = Gson()

    companion object {
        const val MANIFEST_VERSION = "worldinfo_v2"
        private val mutex = Mutex()
        private val idRandom = SecureRandom()
    }

    // ---------- CRUD ----------

    /** 全部书，按 createdAt 升序（书库页排序习惯），同值按 id 稳定。 */
    suspend fun loadAllBooks(): List<WorldInfoBookDocument> = mutex.withLock {
        loadAllBooksInternal()
    }

    suspend fun loadBook(id: String): WorldInfoBookDocument? = mutex.withLock {
        loadBookInternal(id)
    }

    suspend fun saveBook(book: WorldInfoBookDocument) = mutex.withLock {
        saveBookInternal(book)
    }

    suspend fun deleteBook(id: String) = mutex.withLock {
        bookFile(id).delete()
    }

    /** 新建 owned 书（W3 书库页「新建/导入」用）。设为全局生效时自动互斥取消旧书。 */
    suspend fun createOwnedBook(
        name: String,
        entries: List<WorldInfoEntry>,
        imported: Boolean = false,
        setGlobalActive: Boolean = false,
        sessionIds: List<String> = emptyList(),
        config: WorldInfoConfig? = null
    ): WorldInfoBookDocument = mutex.withLock {
        val now = System.currentTimeMillis()
        val book = WorldInfoBookDocument(
            id = newBookId(),
            name = name,
            createdAt = now,
            updatedAt = now,
            origin = if (imported) WorldInfoBookOrigin.IMPORTED else WorldInfoBookOrigin.CREATED,
            scope = if (setGlobalActive) WorldInfoBookScope.GLOBAL else WorldInfoBookScope.SESSION,
            sessionIds = if (setGlobalActive) emptyList() else sessionIds,
            isGlobalActive = setGlobalActive,
            entries = entries,
            config = config
        )
        if (setGlobalActive) clearOtherGlobalActive(excludeId = book.id)
        saveBookInternal(book)
        book
    }

    // ---------- 生效解析（Spec §4） ----------

    /**
     * 解析某会话当前唯一的生效书。
     *
     * @param defaultConfig 全局默认匹配配置（设置页 WorldInfoConfig）；生效书自带
     *   config 时按书覆盖（owned 书整本替换；card 书沿用 0.7.1 语义——卡配置生效、
     *   recursionDepthCap 跟随用户默认，Spec §4.3）
     */
    suspend fun resolveActiveBook(
        sessionId: String,
        characterId: String?,
        defaultConfig: WorldInfoConfig
    ): ActiveBookResolution = mutex.withLock {
        val defaultCore = WorldInfoBridge.toCoreConfig(defaultConfig)
        // 来源卡已删除/书损坏的 card 书不可解析：过滤后继续下层（Spec §3.2.3 降级语义）
        val books = loadAllBooksInternal().filter { book ->
            book.origin != WorldInfoBookOrigin.CARD || cardBookResolvable(book)
        }
        val (source, book) = ActiveBookResolver.pick(books, sessionId, characterId)
        if (book == null) {
            return@withLock ActiveBookResolution(ActiveBookSource.NONE, null, emptyList(), defaultCore)
        }
        if (book.origin == WorldInfoBookOrigin.CARD) {
            val doc = characterStore.load(book.originCharacterId ?: return@withLock noneResolution(defaultCore))
            val bookJson = doc?.embeddedBookJson?.takeIf { it.isNotBlank() }
                ?: return@withLock noneResolution(defaultCore)
            val parsed = CharacterCardCodec.parseCharacterBook(bookJson)
                // 解析失败视为无书（与 buildWorldBookView 失败语义一致）
                ?: return@withLock noneResolution(defaultCore)
            val adapted = CardBookAdapter.toWorldInfoBook(parsed, "book:${book.id}")
            return@withLock ActiveBookResolution(
                source = source,
                book = book,
                entries = adapted.entries.filter { it.uid !in book.disabledUids },
                config = adapted.config.copy(recursionDepthCap = defaultCore.recursionDepthCap)
            )
        }
        ActiveBookResolution(
            source = source,
            book = book,
            entries = WorldInfoBridge.toCoreEntries(book.entries.filter { it.enabled }),
            config = book.config?.let { WorldInfoBridge.toCoreConfig(it) } ?: defaultCore
        )
    }

    /** 与 resolveActiveBook 同会话的绑定冲突书（W4 面板提示用）。 */
    suspend fun sessionBoundConflicts(sessionId: String): List<WorldInfoBookDocument> = mutex.withLock {
        ActiveBookResolver.sessionBoundConflicts(loadAllBooksInternal(), sessionId)
    }

    // ---------- 条目开关 / 绑定（Spec §3.2 / §4.1） ----------

    /** owned 书条目开关（按 entry.id 唯一定位）。 */
    suspend fun setOwnedEntryEnabled(bookId: String, entryId: String, enabled: Boolean): WorldInfoBookDocument? =
        mutex.withLock {
            val book = loadBookInternal(bookId)?.takeIf { it.isOwned } ?: return@withLock null
            val updated = book.copy(
                entries = book.entries.map { if (it.id == entryId) it.copy(enabled = enabled) else it },
                updatedAt = System.currentTimeMillis()
            )
            saveBookInternal(updated)
            updated
        }

    /** card 书条目开关（按卡内条目 uid，写入 override 层，不碰卡原文）。 */
    suspend fun setCardEntryOverride(bookId: String, uid: Int, enabled: Boolean): WorldInfoBookDocument? =
        mutex.withLock {
            val book = loadBookInternal(bookId)?.takeIf { !it.isOwned } ?: return@withLock null
            val updated = book.copy(
                disabledUids = if (enabled) book.disabledUids - uid else (book.disabledUids + uid).distinct(),
                updatedAt = System.currentTimeMillis()
            )
            saveBookInternal(updated)
            updated
        }

    /** 会话换书（Spec §4.1 层 1）：绑定后该会话最高优先用这本书。不降级全局标记——
     *  该书仍作为其余会话的全局生效书（层 3 与层 1 可共存于同一本书）。 */
    suspend fun bindBookToSession(bookId: String, sessionId: String): WorldInfoBookDocument? = mutex.withLock {
        val book = loadBookInternal(bookId) ?: return@withLock null
        val updated = book.copy(
            scope = if (book.isGlobalActive) WorldInfoBookScope.GLOBAL else WorldInfoBookScope.SESSION,
            sessionIds = (book.sessionIds + sessionId).distinct(),
            updatedAt = System.currentTimeMillis()
        )
        saveBookInternal(updated)
        updated
    }

    /** 会话「跟随默认」：解除绑定，回到随角色/全局的自动解析。 */
    suspend fun unbindSession(sessionId: String) = mutex.withLock {
        loadAllBooksInternal().forEach { book ->
            if (sessionId in book.sessionIds) {
                saveBookInternal(
                    book.copy(
                        sessionIds = book.sessionIds - sessionId,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** 设为全局生效书（至多一本，互斥自动取消旧书）；null = 取消全部。
     *  保留目标书已有的会话绑定（层 1/层 3 可共存于同一本书）。 */
    suspend fun setGlobalActive(bookId: String?) = mutex.withLock {
        if (bookId == null) {
            loadAllBooksInternal().forEach { book ->
                if (book.isGlobalActive) {
                    saveBookInternal(book.copy(isGlobalActive = false, updatedAt = System.currentTimeMillis()))
                }
            }
            return@withLock
        }
        val target = loadBookInternal(bookId) ?: return@withLock
        clearOtherGlobalActive(excludeId = bookId)
        saveBookInternal(
            target.copy(
                scope = WorldInfoBookScope.GLOBAL,
                isGlobalActive = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ---------- 一次性迁移（Spec §5） ----------

    /**
     * 全局书 / 会话书 / 角色卡书 → 统一书库。
     * 幂等（manifest 短路）；staging 构建失败整体作废可重试；旧文件原样保留不读不删
     * （迁移后旧读取路径由 W2 切换）。构建中任何源读取异常 → 放弃本次迁移并返回失败说明。
     */
    suspend fun migrateIfNeeded(): WorldInfoMigrationOutcome = mutex.withLock {
        if (manifestFile.exists()) {
            return@withLock WorldInfoMigrationOutcome(performed = false, booksCreated = 0, notes = emptyList())
        }
        try {
            // 上次 staging 未完成：整体作废重来（源文件未动过）
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            val stagingBooksDir = File(stagingDir, "books").apply { mkdirs() }

            val notes = ArrayList<String>()
            val books = ArrayList<WorldInfoBookDocument>()
            val now = System.currentTimeMillis()

            // 1. 全局书（条目非空才建书，验收 C11）
            val globalFile = File(storageRoot, "global_world_info.json")
            var globalCount = 0
            if (globalFile.exists()) {
                val entries = readLegacyEntryArray(globalFile.readText())
                if (entries.isNotEmpty()) {
                    books += WorldInfoBookDocument(
                        id = newBookId(),
                        name = "全局世界书",
                        createdAt = now,
                        updatedAt = now,
                        origin = WorldInfoBookOrigin.CREATED,
                        scope = WorldInfoBookScope.GLOBAL,
                        isGlobalActive = true,
                        entries = entries
                    )
                    globalCount = 1
                    notes += "全局世界书：${entries.size} 条。"
                }
            }

            // 2. 会话书（每文件一本；条目为空也保留其 config 绑定语义）
            val sessionsDir = File(storageRoot, "sessions")
            val titles = loadSessionTitles()
            var sessionCount = 0
            sessionsDir.listFiles { f ->
                f.isFile && f.name.startsWith("world_info_") && f.name.endsWith(".json")
            }?.sortedBy { it.name }?.forEach { file ->
                val sessionId = file.name.removePrefix("world_info_").removeSuffix(".json")
                val (entries, config) = readLegacySessionBook(file.readText())
                books += WorldInfoBookDocument(
                    id = newBookId(),
                    name = "会话书 · ${titles[sessionId] ?: sessionId.takeLast(6)}",
                    createdAt = now,
                    updatedAt = now,
                    origin = WorldInfoBookOrigin.CREATED,
                    scope = WorldInfoBookScope.SESSION,
                    sessionIds = listOf(sessionId),
                    entries = entries,
                    config = config
                )
                sessionCount++
            }
            if (sessionCount > 0) notes += "会话书：$sessionCount 本。"

            // 3. 角色卡书（引用书，不落内容；scope 无意义恒 GLOBAL）
            var cardCount = 0
            characterStore.loadAll().forEach { doc ->
                if (!doc.embeddedBookJson.isNullOrBlank()) {
                    books += WorldInfoBookDocument(
                        id = newBookId(),
                        name = "角色卡 · ${doc.profile.name}",
                        createdAt = now,
                        updatedAt = now,
                        origin = WorldInfoBookOrigin.CARD,
                        originCharacterId = doc.profile.id,
                        scope = WorldInfoBookScope.GLOBAL
                    )
                    cardCount++
                }
            }
            if (cardCount > 0) notes += "角色卡书：$cardCount 本。"

            // 4. 写 staging（每书一文件 + manifest 最后写入），原子切换
            books.forEach { book ->
                atomicWrite(File(stagingBooksDir, "${book.id}.json"), WorldInfoBookJson.toJson(book))
            }
            val manifest = JsonObject().apply {
                addProperty("version", MANIFEST_VERSION)
                addProperty("migratedAt", now)
                addProperty("books", books.size)
                add("counts", JsonObject().apply {
                    addProperty("global", globalCount)
                    addProperty("session", sessionCount)
                    addProperty("card", cardCount)
                })
                add("notes", JsonArray().apply { notes.forEach { add(com.google.gson.JsonPrimitive(it)) } })
            }
            File(stagingDir, "manifest.json").writeText(manifest.toString())

            if (worldinfoDir.exists()) worldinfoDir.deleteRecursively() // 无 manifest 的半成品目录
            if (!stagingDir.renameTo(worldinfoDir)) {
                worldinfoDir.mkdirs()
                stagingDir.copyRecursively(worldinfoDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
            WorldInfoMigrationOutcome(performed = true, booksCreated = books.size, notes = notes)
        } catch (e: Exception) {
            e.printStackTrace()
            stagingDir.deleteRecursively()
            WorldInfoMigrationOutcome(
                performed = false,
                booksCreated = 0,
                notes = listOf("迁移中止（${e.javaClass.simpleName}: ${e.message ?: "未知"}），可重试；旧文件未受影响。")
            )
        }
    }

    // ---------- 内部 ----------

    private fun noneResolution(defaultCore: com.loyea.character.core.worldinfo.WorldInfoConfig) =
        ActiveBookResolution(ActiveBookSource.NONE, null, emptyList(), defaultCore)

    private fun cardBookResolvable(book: WorldInfoBookDocument): Boolean {
        val characterId = book.originCharacterId ?: return false
        if (!characterStore.exists(characterId)) return false
        return !characterStore.load(characterId)?.embeddedBookJson.isNullOrBlank()
    }

    private fun loadAllBooksInternal(): List<WorldInfoBookDocument> {
        if (!booksDir.exists()) return emptyList()
        return booksDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file -> runCatching { WorldInfoBookJson.fromJson(file.readText()) }.getOrNull() }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    private fun loadBookInternal(id: String): WorldInfoBookDocument? {
        val file = bookFile(id)
        if (!file.exists()) return null
        return runCatching { WorldInfoBookJson.fromJson(file.readText()) }.getOrNull()
    }

    private fun saveBookInternal(book: WorldInfoBookDocument) {
        if (!booksDir.exists()) booksDir.mkdirs()
        atomicWrite(bookFile(book.id), WorldInfoBookJson.toJson(book))
    }

    private fun clearOtherGlobalActive(excludeId: String) {
        loadAllBooksInternal().forEach { book ->
            if (book.id != excludeId && book.isGlobalActive) {
                saveBookInternal(book.copy(isGlobalActive = false, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private fun bookFile(id: String): File =
        File(booksDir, "${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(content)
        }
    }

    private fun newBookId(): String {
        val rnd = ByteArray(2).also { idRandom.nextBytes(it) }
        return "wb_${System.currentTimeMillis()}_" + rnd.joinToString("") { "%02x".format(it) }
    }

    /** 旧全局书：WorldInfoEntry 数组。 */
    private fun readLegacyEntryArray(json: String): List<WorldInfoEntry> = runCatching {
        val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        WorldInfoBookJson.selfHealEntries(gson.fromJson(array, Array<WorldInfoEntry>::class.java).toList())
    }.getOrDefault(emptyList())

    /** 旧会话书：{"entries": [...], "config": {...}}。 */
    private fun readLegacySessionBook(json: String): Pair<List<WorldInfoEntry>, WorldInfoConfig?> = runCatching {
        val obj = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList<WorldInfoEntry>() to null
        val entries = obj.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray?.let { array ->
            WorldInfoBookJson.selfHealEntries(gson.fromJson(array, Array<WorldInfoEntry>::class.java).toList())
        } ?: emptyList()
        val config = obj.get("config")?.takeIf { it.isJsonObject }?.asJsonObject?.toString()
            ?.let { WorldInfoConfigStorage.fromJson(it) }
        entries to config
    }.getOrDefault(emptyList<WorldInfoEntry>() to null)

    /** sessions_metadata.json 的 id → title 查找表（缺失/损坏退空表）。 */
    private fun loadSessionTitles(): Map<String, String> = runCatching {
        val file = File(storageRoot, "sessions_metadata.json")
        if (!file.exists()) return emptyMap()
        val array = JsonParser.parseString(file.readText()).takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: return@forEach
            val title = obj.get("title")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (id != null && !title.isNullOrBlank()) result[id] = title
        }
        result
    }.getOrDefault(emptyMap())
}
