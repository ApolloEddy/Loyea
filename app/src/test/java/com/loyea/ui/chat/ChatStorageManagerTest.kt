package com.loyea.ui.chat

import android.content.Context
import com.loyea.character.core.api.CharacterDocument
import com.loyea.character.core.api.CharacterProfile
import com.loyea.storage.CharacterDocumentStore
import com.loyea.storage.RebuildStorageMigrator
import com.loyea.storage.worldinfo.WorldInfoBookOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import java.io.File

class ChatStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = mock()
    private lateinit var storageManager: ChatStorageManager

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        `when`(context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)).thenReturn(mock())
        `when`(context.filesDir).thenReturn(filesDir)
        storageManager = ChatStorageManager(context)
    }

    @Test
    fun testUpdateSessionListAtomic() = runBlocking {
        val initialSessions = listOf(
            ChatSession("1", "Session 1", 1000L),
            ChatSession("2", "Session 2", 2000L)
        )
        storageManager.saveSessionList(initialSessions)

        // 原子更新
        storageManager.updateSessionList { currentList ->
            currentList.map { session ->
                if (session.id == "1") {
                    session.copy(title = "Updated Session 1")
                } else {
                    session
                }
            }
        }

        val loaded = storageManager.loadSessionList()
        assertEquals(2, loaded.size)
        assertEquals("Updated Session 1", loaded.first { it.id == "1" }.title)
    }

    @Test
    fun testUpdateSessionMessagesAtomic() = runBlocking {
        val sessionId = "test_session_id"
        val initialMsgs = listOf(
            Message("m1", "Hello", Sender.USER, characterId = "char_loyea_default"),
            Message("m2", "World", Sender.AI, characterId = "char_loyea_default")
        )
        storageManager.saveSessionMessages(sessionId, initialMsgs)

        // 原子更新
        storageManager.updateSessionMessages(sessionId) { currentMsgs ->
            currentMsgs.map { msg ->
                if (msg.id == "m1") {
                    msg.copy(content = "Hello Atomic")
                } else {
                    msg
                }
            }
        }

        val loaded = storageManager.loadSessionMessages(sessionId)
        assertEquals(2, loaded.size)
        assertEquals("Hello Atomic", loaded.first { it.id == "m1" }.content)
    }

    @Test
    fun testLlmContextSnapshotRoundTripAndLegacyCompatibility() = runBlocking {
        val sessionId = "snapshot_roundtrip"
        storageManager.saveSessionMessages(
            sessionId,
            listOf(
                Message(
                    id = "m1",
                    content = "hello",
                    sender = Sender.USER,
                    llmContextSnapshot = "[TURN CONTEXT SNAPSHOT]\nstable"
                )
            )
        )
        assertEquals(
            "[TURN CONTEXT SNAPSHOT]\nstable",
            storageManager.loadSessionMessages(sessionId).single().llmContextSnapshot
        )

        val legacyId = "legacy_without_snapshot"
        // 模拟迁移前遗留在旧 filesDir/sessions 的会话文件（父目录需先建好）
        val legacyFile = File(tempFolder.root, "files/rebuild_storage_v1/sessions/session_$legacyId.json")
        legacyFile.parentFile.mkdirs()
        legacyFile.writeText("""[{"id":"old","content":"legacy","sender":"USER","timestamp":1}]""")
        val legacyMessage = storageManager.loadSessionMessages(legacyId).single()
        assertNull(legacyMessage.llmContextSnapshot)
        assertNotNull(legacyMessage.llmTimeZoneId)
    }

    // v0.5.1 旧字段缺失的 selfHeal 兜底断言：旧读取路径已退役（W5），
    // 等价断言改走迁移路径，见下方 testLegacyMissingFieldsGetDefaultsThroughMigration。

    @Test
    fun testUpdateSessionTokensAccumulatesCacheTokens() = runBlocking {
        storageManager.saveSessionList(listOf(ChatSession("1", "Session 1", 1000L)))

        // 第一次（非主聊天流路径不传 cache 参数 → 不累计）
        storageManager.updateSessionTokens("1", 3, 5, lastContextTokens = 120)
        // 第二次（主聊天流路径带 cache + 覆盖 lastContext）
        storageManager.updateSessionTokens("1", 3, 5, lastContextTokens = 140, promptCacheHitTokens = 2, promptCacheMissTokens = 8)

        var loaded = storageManager.loadSessionList().first { it.id == "1" }
        assertEquals(6, loaded.promptTokens)
        assertEquals(10, loaded.completionTokens)
        assertEquals(140, loaded.lastContextTokens)
        assertEquals(2, loaded.promptCacheHitTokens)
        assertEquals(8, loaded.promptCacheMissTokens)

        // 第三次累计 cache（prompt/completion 传 0 不干扰既有值）
        storageManager.updateSessionTokens("1", 0, 0, promptCacheHitTokens = 5, promptCacheMissTokens = 5)
        loaded = storageManager.loadSessionList().first { it.id == "1" }
        assertEquals(6, loaded.promptTokens)
        assertEquals(10, loaded.completionTokens)
        assertEquals(7, loaded.promptCacheHitTokens)
        assertEquals(13, loaded.promptCacheMissTokens)
    }

    // ---------- 删除会话 × 书库绑定清理（WorldInfo 2.0 Spec §6.5） ----------

    private val storageRoot get() = File(tempFolder.root, "files/rebuild_storage_v1")

    private fun legacySessionBookJson(entriesJson: String): String =
        """{"entries":$entriesJson}"""

    private fun seedLegacySources(globalEntriesJson: String, sessionBookJson: String?, sessionId: String) {
        // 先完成 rebuild_storage_v1 根迁移建立 manifest：否则 deleteSession 内
        // ensureMigrated() 会把已种子的根整体重建（RebuildStorageMigrator staging 切换语义）
        RebuildStorageMigrator.ensureMigrated(File(tempFolder.root, "files"))
        if (globalEntriesJson.isNotEmpty()) {
            File(storageRoot, "global_world_info.json").writeText(globalEntriesJson)
        }
        if (sessionBookJson != null) {
            val file = File(storageRoot, "sessions/world_info_$sessionId.json")
            file.parentFile?.mkdirs()
            file.writeText(sessionBookJson)
        }
        File(storageRoot, "sessions_metadata.json").writeText(
            """[{"id":"$sessionId","title":"T","lastActiveTime":100}]"""
        )
        runBlocking { storageManager.worldInfoLibrary.migrateIfNeeded() }
    }

    @Test
    fun testDeleteSessionUnbindsCardBookButKeepsIt() = runBlocking {
        // 卡书（引用不落内容）绑定会话：删除会话只解绑，书保留
        val sessionId = "sess_card"
        seedLegacySources("[]", null, sessionId) // 先建根 manifest，避免根迁移重建吞掉后写入的卡
        CharacterDocumentStore(File(storageRoot, "characters")).save(
            CharacterDocument(
                profile = CharacterProfile(id = "char_k", name = "K"),
                embeddedBookJson = """{"name":"KB","entries":[{"id":1,"keys":["a"],"content":"A","constant":true}]}"""
            )
        )
        val library = storageManager.worldInfoLibrary
        library.ensureCardBookRegistered("char_k")
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }
        library.bindBookToSession(cardBook.id, sessionId)

        storageManager.deleteSession(sessionId)

        val after = library.loadBook(cardBook.id)
        assertNotNull(after) // 卡书只解绑不删
        assertTrue(sessionId !in after!!.sessionIds)
    }

    @Test
    fun testDeleteSessionDeletesOwnedBookOnlyBoundToIt() = runBlocking {
        // owned 会话书仅绑定本会话：整本删除
        val sessionId = "sess_owned"
        seedLegacySources(
            "[]",
            legacySessionBookJson("""[{"id":"e1","keywords":["k"],"content":"C"}]"""),
            sessionId
        )
        val library = storageManager.worldInfoLibrary
        val owned = library.loadAllBooks().first { it.sessionIds.contains(sessionId) }

        storageManager.deleteSession(sessionId)

        assertNull(library.loadBook(owned.id))
    }

    @Test
    fun testDeleteSessionKeepsOwnedBookSharedByOtherSessions() = runBlocking {
        // owned 书同时绑定另一会话：只解绑不删
        val sessionId = "sess_a"
        seedLegacySources(
            "[]",
            legacySessionBookJson("""[{"id":"e1","keywords":["k"],"content":"C"}]"""),
            sessionId
        )
        val library = storageManager.worldInfoLibrary
        val owned = library.loadAllBooks().first { it.sessionIds.contains(sessionId) }
        library.bindBookToSession(owned.id, "sess_b")

        storageManager.deleteSession(sessionId)

        val after = library.loadBook(owned.id)
        assertNotNull(after)
        assertEquals(listOf("sess_b"), after!!.sessionIds)
    }

    @Test
    fun testLegacyMissingFieldsGetDefaultsThroughMigration() = runBlocking {
        // v0.5.1 时代只有 12 字段的旧条目：经迁移路径 selfHeal 兜底
        // （String/List 缺失 → ?: 生效；原始类型缺失 → JVM 默认，概率/递归保守禁用）
        val oldJson = """
            [
              {
                "id": "wi_1",
                "keywords": ["k1"],
                "content": "C1",
                "enabled": true,
                "uid": 1,
                "keysecondary": [],
                "constant": false,
                "order": 100,
                "depth": 4,
                "comment": "",
                "selective": false,
                "disable": false
              }
            ]
        """.trimIndent()
        seedLegacySources(oldJson, null, "sess_x")

        val global = storageManager.worldInfoLibrary.loadAllBooks()
            .first { it.isGlobalActive }
        assertEquals(1, global.entries.size)
        val e = global.entries[0]
        assertEquals("", e.group)
        assertEquals("chat", e.keysContainedIn)
        assertEquals(emptyList<String>(), e.keysecondary)
        assertEquals(0, e.selectiveLogic)
        assertEquals(0, e.probability)
        assertEquals(false, e.useProbability)
        assertEquals(0, e.delayUntilRecursion)
        assertEquals(false, e.preventRecursion)
        assertEquals(false, e.allowRecursion)
        assertEquals(false, e.excludeRecursion)
        assertEquals(0, e.position)
        assertEquals(0, e.weight)
        assertEquals("wi_1", e.id)
        assertEquals(listOf("k1"), e.keywords)
        assertEquals(4, e.depth)
    }

    // ---------- WorldInfoConfig JSON 编解码 ----------

    @Test
    fun testWorldInfoConfigJsonRoundTrip() {
        val cfg = WorldInfoConfig(
            scanDepth = 7,
            position = "top",
            insertionOrderMode = WorldInfoInsertionOrder.INSERT_AT_BOTTOM,
            tokenBudget = 512,
            recursionDepthCap = 1,
            allowRecursion = false,
            emitGroupHeaders = true
        )
        assertEquals(cfg, WorldInfoConfigStorage.fromJson(WorldInfoConfigStorage.toJson(cfg)))
    }

    @Test
    fun testWorldInfoConfigFromJsonDefaults() {
        // 空对象 / 非法 JSON / null / 空串 → 全默认
        assertEquals(WorldInfoConfig(), WorldInfoConfigStorage.fromJson("{}"))
        assertEquals(WorldInfoConfig(), WorldInfoConfigStorage.fromJson("not json"))
        assertEquals(WorldInfoConfig(), WorldInfoConfigStorage.fromJson(null))
        assertEquals(WorldInfoConfig(), WorldInfoConfigStorage.fromJson(""))
        // 部分字段存在 → 其余默认（primitive 显式归位）
        val partial = WorldInfoConfigStorage.fromJson("""{"position":"top","scanDepth":3}""")
        assertEquals("top", partial.position)
        assertEquals(3, partial.scanDepth)
        assertEquals(WorldInfoInsertionOrder.ORDER, partial.insertionOrderMode)
        assertEquals(2048L, partial.tokenBudget)
        assertEquals(true, partial.allowRecursion)
    }
}
