package com.loyea.storage.worldinfo

import com.google.gson.JsonParser
import com.loyea.character.core.api.CharacterDocument
import com.loyea.character.core.api.CharacterProfile
import com.loyea.storage.CharacterDocumentStore
import com.loyea.ui.chat.WorldInfoConfig
import com.loyea.ui.chat.WorldInfoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 世界书 2.0 数据层测试（WorldInfo Spec §8 W1 退出条件）：
 * 迁移幂等/重试/校验、三类源全平移、旧文件字节不变、resolveActiveBook 全层级与 override。
 */
class WorldInfoLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeRoot(): File = tmp.newFolder("root").apply {
        File(this, "characters").mkdirs()
        File(this, "sessions").mkdirs()
    }

    private fun seedLegacyData(root: File) {
        // 全局书（数组，2 条）
        File(root, "global_world_info.json").writeText(
            """[{"id":"g1","keywords":["诊所"],"content":"诊所全局设定","enabled":true,"uid":1,"constant":true},
                {"id":"g2","keywords":["茶室"],"content":"茶室设定","enabled":true,"uid":2}]"""
        )
        // 会话元数据（标题查找表）
        File(root, "sessions_metadata.json").writeText(
            """[{"id":"s1","title":"会话一","lastActiveTime":100,"characterId":"char_lin"},
                {"id":"s2","title":"会话二","lastActiveTime":200,"characterId":"char_native"}]"""
        )
        // 会话书 s1（1 条 + 自带 config）
        File(root, "sessions/world_info_s1.json").writeText(
            """{"entries":[{"id":"e1","keywords":["学校"],"content":"学校设定","enabled":true,"uid":1}],
                "config":{"scanDepth":5,"tokenBudget":1024}}"""
        )
        // 角色卡（内嵌书 2 条，scan_depth=7）
        val store = CharacterDocumentStore(File(root, "characters"))
        store.save(
            CharacterDocument(
                profile = CharacterProfile(id = "char_lin", name = "林芷柔"),
                embeddedBookJson = """{"name":"林芷柔的世界","scan_depth":7,"entries":[
                    {"id":1,"keys":["针灸"],"content":"常驻设定","enabled":true,"constant":true,"position":"before_char"},
                    {"id":2,"keys":["诊所"],"content":"诊所条件设定","enabled":true,"position":"before_char"}]}"""
            )
        )
    }

    private fun defaultConfig() = WorldInfoConfig(recursionDepthCap = 6)

    // ---------- 迁移 ----------

    @Test
    fun `migration moves all three sources into library`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val globalBefore = File(root, "global_world_info.json").readBytes()
        val sessionBookBefore = File(root, "sessions/world_info_s1.json").readBytes()

        val outcome = WorldInfoLibrary(root).migrateIfNeeded()

        assertTrue(outcome.performed)
        assertEquals(3, outcome.booksCreated) // 全局 1 + 会话 1 + 卡 1

        val library = WorldInfoLibrary(root)
        val books = library.loadAllBooks()
        assertEquals(3, books.size)

        val global = books.first { it.name == "全局世界书" }
        assertTrue(global.isGlobalActive)
        assertEquals(2, global.entries.size)
        assertNull(global.config) // 继承全局默认

        val session = books.first { it.name == "会话书 · 会话一" }
        assertEquals(listOf("s1"), session.sessionIds)
        assertEquals(1, session.entries.size)
        assertNotNull(session.config)
        assertEquals(5, session.config!!.scanDepth)

        val card = books.first { it.name == "角色卡 · 林芷柔" }
        assertEquals(WorldInfoBookOrigin.CARD, card.origin)
        assertEquals("char_lin", card.originCharacterId)
        assertTrue(card.entries.isEmpty()) // 引用书不落内容
        assertFalse(card.isGlobalActive)

        // manifest + 旧文件字节不变
        assertTrue(File(root, "worldinfo/manifest.json").exists())
        assertTrue(globalBefore.contentEquals(File(root, "global_world_info.json").readBytes()))
        assertTrue(sessionBookBefore.contentEquals(File(root, "sessions/world_info_s1.json").readBytes()))
    }

    @Test
    fun `migration skips empty global book`() = runBlocking {
        val root = makeRoot()
        File(root, "global_world_info.json").writeText("[]")
        WorldInfoLibrary(root).migrateIfNeeded()

        val books = WorldInfoLibrary(root).loadAllBooks()
        assertTrue(books.isEmpty())
    }

    @Test
    fun `migration is idempotent and preserves user changes`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()

        // 用户后续改动：新建一本书
        library.createOwnedBook(name = "我的新书", entries = emptyList())
        val booksAfterChange = library.loadAllBooks().size
        assertEquals(4, booksAfterChange)

        val second = library.migrateIfNeeded()
        assertFalse(second.performed)
        assertEquals(4, library.loadAllBooks().size) // 改动未被覆盖
    }

    @Test
    fun `migration recovers from leftover staging`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        // 模拟上次迁移中途崩溃：残留 staging 垃圾
        val garbage = File(root, "worldinfo.staging/books").apply { mkdirs() }
        File(garbage, "wb_garbage.json").writeText("{broken")

        val outcome = WorldInfoLibrary(root).migrateIfNeeded()
        assertTrue(outcome.performed)
        assertEquals(3, WorldInfoLibrary(root).loadAllBooks().size)
        assertFalse(File(root, "worldinfo.staging").exists())
    }

    // ---------- 解析层级（纯函数） ----------

    @Test
    fun `resolver picks layers in priority order`() {
        val global = book("b1", createdAt = 1, scope = WorldInfoBookScope.GLOBAL, isGlobalActive = true)
        val card = book(
            "b2", createdAt = 2, origin = WorldInfoBookOrigin.CARD,
            originCharacterId = "char_lin"
        )
        val sessionBound = book(
            "b3", createdAt = 3, scope = WorldInfoBookScope.SESSION,
            sessionIds = listOf("s1")
        )

        // 层 1 最高
        assertEquals(
            ActiveBookSource.SESSION_BOUND,
            ActiveBookResolver.pick(listOf(global, card, sessionBound), "s1", "char_lin").first
        )
        // 无绑定时随角色
        assertEquals(
            ActiveBookSource.CARD_FOLLOW,
            ActiveBookResolver.pick(listOf(global, card, sessionBound), "s9", "char_lin").first
        )
        // 无卡无绑定 → 全局
        assertEquals(
            ActiveBookSource.GLOBAL_ACTIVE,
            ActiveBookResolver.pick(listOf(global, card, sessionBound), "s9", "char_native").first
        )
        // 全无 → NONE
        assertEquals(
            ActiveBookSource.NONE,
            ActiveBookResolver.pick(emptyList(), "s9", null).first
        )
        // 会话绑定的书对其他会话不生效
        assertEquals(
            ActiveBookSource.GLOBAL_ACTIVE,
            ActiveBookResolver.pick(listOf(global, sessionBound), "s9", "char_native").first
        )
    }

    @Test
    fun `resolver tie-breaks by createdAt and reports conflicts`() {
        val older = book(
            "b_old", createdAt = 1, scope = WorldInfoBookScope.SESSION,
            sessionIds = listOf("s1")
        )
        val newer = book(
            "b_new", createdAt = 2, scope = WorldInfoBookScope.SESSION,
            sessionIds = listOf("s1")
        )
        val (source, picked) = ActiveBookResolver.pick(listOf(newer, older), "s1", null)
        assertEquals(ActiveBookSource.SESSION_BOUND, source)
        assertEquals("b_old", picked!!.id)
        assertEquals(listOf("b_new"), ActiveBookResolver.sessionBoundConflicts(listOf(newer, older), "s1").map { it.id })
    }

    // ---------- resolveActiveBook ----------

    @Test
    fun `card book resolves live entries with override filter`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        // 未 override：2 条常驻/条件全在，条目 ID 前缀 book:<bookId>
        // （用无会话书的 s9：s1 已被迁移的会话书层 1 占据）
        val full = library.resolveActiveBook("s9", "char_lin", defaultConfig())
        assertEquals(ActiveBookSource.CARD_FOLLOW, full.source)
        assertEquals(2, full.entries.size)
        assertTrue(full.entries.all { it.id.startsWith("book:${cardBook.id}") })

        // 卡书配置生效（scan_depth=7），recursionDepthCap 跟随用户默认（6）
        assertEquals(7, full.config.scanDepth)
        assertEquals(6, full.config.recursionDepthCap)

        // 关掉 uid=1 → 只剩 uid=2
        library.setCardEntryOverride(cardBook.id, uid = 1, enabled = false)
        val filtered = library.resolveActiveBook("s9", "char_lin", defaultConfig())
        assertEquals(1, filtered.entries.size)
        assertEquals(2, filtered.entries.first().uid)

        // 来源卡删除 → 降级到下一层（全局书）
        CharacterDocumentStore(File(root, "characters")).delete("char_lin")
        val fallen = library.resolveActiveBook("s9", "char_lin", defaultConfig())
        assertEquals(ActiveBookSource.GLOBAL_ACTIVE, fallen.source)
        assertEquals(2, fallen.entries.size) // 全局书 2 条
    }

    @Test
    fun `owned book resolution filters disabled entries and applies book config`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()

        // s1 绑定的会话书：1 条 enabled → 层 1 生效
        val resolution = library.resolveActiveBook("s1", "char_lin", defaultConfig())
        assertEquals(ActiveBookSource.SESSION_BOUND, resolution.source)
        assertEquals(1, resolution.entries.size)
        assertEquals(5, resolution.config.scanDepth) // 书自带 config 整本生效
        // 整本生效含 recursionDepthCap（旧会话书缺字段时由默认值补齐 = 3，
        // 与 0.7.1 会话书 wholesale 语义一致；用户全局 6 不覆盖书内配置）
        assertEquals(3, resolution.config.recursionDepthCap)

        // 关掉那条 → 空条目解析（书仍在但零注入）
        val book = resolution.book!!
        library.setOwnedEntryEnabled(book.id, entryId = "e1", enabled = false)
        val empty = library.resolveActiveBook("s1", "char_lin", defaultConfig())
        assertEquals(ActiveBookSource.SESSION_BOUND, empty.source)
        assertTrue(empty.entries.isEmpty())
    }

    @Test
    fun `unbound native session falls back to global active book`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()

        val resolution = library.resolveActiveBook("s2", "char_native", defaultConfig())
        assertEquals(ActiveBookSource.GLOBAL_ACTIVE, resolution.source)
        assertEquals(2, resolution.entries.size)
    }

    // ---------- 绑定与全局生效 ----------

    @Test
    fun `bind and unbind session book changes resolution immediately`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val globalBook = library.loadAllBooks().first { it.isGlobalActive }

        // s2（原生角色）换书为全局书 → 层 1
        library.bindBookToSession(globalBook.id, "s2")
        val bound = library.resolveActiveBook("s2", "char_native", defaultConfig())
        assertEquals(ActiveBookSource.SESSION_BOUND, bound.source)
        assertEquals(globalBook.id, bound.book!!.id)
        // 绑定不降级全局标记：该书仍是其余会话的全局生效书
        assertTrue(library.loadBook(globalBook.id)!!.isGlobalActive)

        // 跟随默认 → 回到层 2/3 自动解析
        library.unbindSession("s2")
        val released = library.resolveActiveBook("s2", "char_native", defaultConfig())
        assertEquals(ActiveBookSource.GLOBAL_ACTIVE, released.source)
    }

    @Test
    fun `set global active is mutually exclusive`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }
        val globalBook = library.loadAllBooks().first { it.isGlobalActive }

        // 把卡书设为全局生效 → 旧全局书自动取消
        library.setGlobalActive(cardBook.id)
        val books = library.loadAllBooks()
        assertEquals(cardBook.id, books.first { it.isGlobalActive }.id)
        assertFalse(books.first { it.id == globalBook.id }.isGlobalActive)

        // 卡书从 s2（无绑定原生角色）解析为 GLOBAL_ACTIVE
        val resolution = library.resolveActiveBook("s2", "char_native", defaultConfig())
        assertEquals(ActiveBookSource.GLOBAL_ACTIVE, resolution.source)
        assertEquals(2, resolution.entries.size) // 卡书内容实时解析
    }

    // ---------- 持久化 ----------

    @Test
    fun `entry toggles survive library reload`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        library.setCardEntryOverride(cardBook.id, uid = 2, enabled = false)
        val reloaded = WorldInfoLibrary(root).loadBook(cardBook.id)!!
        assertEquals(listOf(2), reloaded.disabledUids)

        // 重开 override
        library.setCardEntryOverride(cardBook.id, uid = 2, enabled = true)
        assertTrue(WorldInfoLibrary(root).loadBook(cardBook.id)!!.disabledUids.isEmpty())
    }

    @Test
    fun `book json round trip preserves schema fields`() = runBlocking {
        val root = makeRoot()
        val book = WorldInfoBookDocument(
            id = "wb_1", name = "往返", createdAt = 111, updatedAt = 222,
            origin = WorldInfoBookOrigin.IMPORTED,
            scope = WorldInfoBookScope.SESSION,
            sessionIds = listOf("s1", "s2"),
            entries = listOf(
                WorldInfoEntry(id = "x1", keywords = listOf("a"), content = "c", enabled = false, uid = 7)
            ),
            config = WorldInfoConfig(scanDepth = 3)
        )
        val library = WorldInfoLibrary(root)
        library.saveBook(book)
        val loaded = WorldInfoLibrary(root).loadBook("wb_1")!!

        assertEquals(book.copy(entries = loaded.entries), loaded)
        assertEquals(listOf("s1", "s2"), loaded.sessionIds)
        assertEquals(3, loaded.config!!.scanDepth)
        assertFalse(loaded.entries.first().enabled)
        assertEquals(7, loaded.entries.first().uid)

        // 预留扩展位（injectionMode）不阻断读取
        val file = File(root, "worldinfo/books/wb_1.json")
        val patched = JsonParser.parseString(file.readText()).asJsonObject
        patched.addProperty("injectionMode", "stack")
        file.writeText(patched.toString())
        assertEquals("wb_1", WorldInfoLibrary(root).loadBook("wb_1")!!.id)
    }

    private fun book(
        id: String,
        createdAt: Long,
        origin: WorldInfoBookOrigin = WorldInfoBookOrigin.CREATED,
        scope: WorldInfoBookScope = WorldInfoBookScope.GLOBAL,
        isGlobalActive: Boolean = false,
        sessionIds: List<String> = emptyList(),
        originCharacterId: String? = null
    ) = WorldInfoBookDocument(
        id = id, name = id, createdAt = createdAt, updatedAt = createdAt,
        origin = origin, originCharacterId = originCharacterId,
        scope = scope, sessionIds = sessionIds, isGlobalActive = isGlobalActive
    )
}
