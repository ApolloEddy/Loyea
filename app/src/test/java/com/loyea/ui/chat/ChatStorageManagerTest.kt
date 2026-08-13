package com.loyea.ui.chat

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun testOldWorldInfoJsonMissingNewFieldsGetsDefaults() = runBlocking {
        // v0.5.1 时代只有 12 字段的 world_info.json；新字段缺失时 selfHeal 兜底
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
        File(tempFolder.root, "files/global_world_info.json").writeText(oldJson)

        val entries = storageManager.loadWorldInfo()
        assertEquals(1, entries.size)
        val e = entries[0]
        // String/List 字段缺失 → null → ?: 生效
        assertEquals("", e.group)
        assertEquals("chat", e.keysContainedIn)
        assertEquals(emptyList<String>(), e.keysecondary)
        // 原始类型缺失 → Gson 保持 JVM 默认（0/false），? 失效：
        // probability 退化为 0、allowRecursion 退化为 false —— 保守兼容 v0.5.1（无概率/无递归参与）
        assertEquals(0, e.selectiveLogic)
        assertEquals(0, e.probability)
        assertEquals(false, e.useProbability)
        assertEquals(0, e.delayUntilRecursion)
        assertEquals(false, e.preventRecursion)
        assertEquals(false, e.allowRecursion)
        assertEquals(false, e.excludeRecursion)
        assertEquals(0, e.position)
        assertEquals(0, e.weight)
        // 既有字段不受影响
        assertEquals("wi_1", e.id)
        assertEquals(listOf("k1"), e.keywords)
        assertEquals(4, e.depth)
    }

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

    // ---------- 会话专属世界书 ----------

    @Test
    fun testSessionWorldInfoRoundTrip() = runBlocking {
        val sessionId = "sess_wi"
        val book = WorldInfoBook(
            entries = listOf(
                WorldInfoEntry(
                    id = "e1",
                    keywords = listOf("k1"),
                    content = "C1",
                    enabled = true,
                    uid = 1,
                    keysecondary = listOf("ks"),
                    constant = false,
                    order = 50,
                    depth = 3,
                    comment = "c",
                    selective = true,
                    disable = false,
                    selectiveLogic = 3,
                    group = "G",
                    probability = 80,
                    useProbability = true,
                    delayUntilRecursion = 1,
                    preventRecursion = false,
                    allowRecursion = true,
                    excludeRecursion = false,
                    keysContainedIn = "chat,world",
                    position = 1,
                    weight = 2
                )
            ),
            config = WorldInfoConfig(
                scanDepth = 5,
                position = "top",
                insertionOrderMode = WorldInfoInsertionOrder.KEY_LENGTH,
                tokenBudget = 1024,
                recursionDepthCap = 2,
                allowRecursion = false,
                emitGroupHeaders = true
            )
        )
        storageManager.saveSessionWorldInfo(sessionId, book)

        val loaded = storageManager.loadSessionWorldInfo(sessionId)
        assertNotNull(loaded)
        assertEquals(book.entries, loaded!!.entries)
        assertEquals(book.config, loaded.config)
    }

    @Test
    fun testSessionWorldInfoAbsentReturnsNull() = runBlocking {
        assertNull(storageManager.loadSessionWorldInfo("no_such_session"))
    }

    @Test
    fun testDeleteSessionRemovesWorldInfoFile() = runBlocking {
        val sessionId = "sess_del"
        storageManager.saveSessionList(listOf(ChatSession(sessionId, "S", 1000L)))
        storageManager.saveSessionMessages(sessionId, emptyList())
        storageManager.saveSessionWorldInfo(sessionId, WorldInfoBook())

        assertNotNull(storageManager.loadSessionWorldInfo(sessionId))
        storageManager.deleteSession(sessionId)
        assertNull(storageManager.loadSessionWorldInfo(sessionId))
    }

    @Test
    fun testSessionWorldInfoMissingConfigGetsDefaults() = runBlocking {
        // 手工构造旧式/残缺会话书 JSON：有 entries、缺 config 对象
        val sessionId = "sess_old"
        val file = File(tempFolder.root, "files/sessions/world_info_$sessionId.json")
        file.parentFile?.mkdirs()
        file.writeText("""{"entries":[{"id":"e1","keywords":["k"],"content":"C"}]}""")

        val loaded = storageManager.loadSessionWorldInfo(sessionId)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.entries.size)
        assertEquals("e1", loaded.entries[0].id)
        assertEquals(emptyList<String>(), loaded.entries[0].keysecondary)
        assertEquals(WorldInfoConfig(), loaded.config)
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
