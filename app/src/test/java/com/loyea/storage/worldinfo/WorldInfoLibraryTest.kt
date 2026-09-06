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

    // ---------- 卡书内容 override（2026-09-06 用户决策：卡书条目可编辑，原卡文件不动） ----------

    private fun editedUid2() = WorldInfoEntry(
        id = "uid_2",
        keywords = listOf("诊所", "复诊"),
        content = "被用户改写的诊所设定",
        enabled = true,
        uid = 2,
        constant = true
    )

    @Test
    fun `card entry content override reaches resolution detail and counts while card file stays untouched`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val cardFile = File(root, "characters/char_lin.json")
        val before = cardFile.readBytes()
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        library.saveCardEntryOverride(cardBook.id, uid = 2, entry = editedUid2())

        // 详情视图：内容/关键词被替换，uid 不变
        val view = library.loadCardBookEntries(cardBook.id)!!
        val e2 = view.first { it.uid == 2 }
        assertEquals("被用户改写的诊所设定", e2.content)
        assertEquals(listOf("诊所", "复诊"), e2.keywords)
        assertTrue(e2.constant)

        // 摘要计数随 override（常驻 1 → 2）
        assertEquals(2, library.bookOverview(cardBook.id)!!.constantEntries)

        // 解析（无绑定的林芷柔会话 CARD_FOLLOW）：ID 前缀不变、内容生效
        val resolution = library.resolveActiveBook("s9", "char_lin", defaultConfig())
        assertEquals(ActiveBookSource.CARD_FOLLOW, resolution.source)
        val resolved = resolution.entries.first { it.uid == 2 }
        assertEquals("被用户改写的诊所设定", resolved.content)
        assertTrue(resolved.id.startsWith("book:${cardBook.id}"))

        // 原卡文件一字节未动
        assertTrue(before.contentEquals(cardFile.readBytes()))
    }

    @Test
    fun `reset card entry override restores original content`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        library.saveCardEntryOverride(cardBook.id, uid = 2, entry = editedUid2())
        library.resetCardEntryOverride(cardBook.id, uid = 2)

        val view = library.loadCardBookEntries(cardBook.id)!!
        assertEquals("诊所条件设定", view.first { it.uid == 2 }.content)
        assertTrue(library.loadBook(cardBook.id)!!.entryOverrides.isEmpty())
    }

    @Test
    fun `card entry overrides persist across library reload`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        library.saveCardEntryOverride(cardBook.id, uid = 2, entry = editedUid2())

        val reloaded = WorldInfoLibrary(root)
        val view = reloaded.loadCardBookEntries(cardBook.id)!!
        assertEquals("被用户改写的诊所设定", view.first { it.uid == 2 }.content)
    }

    @Test
    fun `content override and disable toggle stay orthogonal`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        library.saveCardEntryOverride(cardBook.id, uid = 2, entry = editedUid2())
        // 开关关掉 → 解析排除该条
        library.setCardEntryOverride(cardBook.id, uid = 2, enabled = false)
        assertTrue(library.resolveActiveBook("s9", "char_lin", defaultConfig()).entries.none { it.uid == 2 })

        // 重新打开 → 内容 override 仍在
        library.setCardEntryOverride(cardBook.id, uid = 2, enabled = true)
        val resolved = library.resolveActiveBook("s9", "char_lin", defaultConfig()).entries.first { it.uid == 2 }
        assertEquals("被用户改写的诊所设定", resolved.content)

        // 重置内容不影响开关
        library.resetCardEntryOverride(cardBook.id, uid = 2)
        val afterReset = library.resolveActiveBook("s9", "char_lin", defaultConfig()).entries.first { it.uid == 2 }
        assertEquals("诊所条件设定", afterReset.content)
        assertFalse(library.loadBook(cardBook.id)!!.disabledUids.contains(2))
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

    // ---------- W3：书库查询 / 卡书注册 / 绑定 / 实时条目 ----------

    @Test
    fun `deleted card book re-registers on next resolve with overrides cleared`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        // 用户关掉一条
        library.setCardEntryOverride(cardBook.id, uid = 1, enabled = false)
        assertEquals(1, library.loadBook(cardBook.id)!!.disabledUids.size)

        // 删除卡书（= 清除 override 与绑定）
        library.deleteBook(cardBook.id)
        assertTrue(library.loadAllBooks().none { it.id == cardBook.id })

        // 下次解析自动重新入库（Spec §6.2 删除语义）
        val resolution = library.resolveActiveBook("s9", "char_lin", defaultConfig())
        assertEquals(ActiveBookSource.CARD_FOLLOW, resolution.source)
        assertEquals(2, resolution.entries.size) // override 已清除，2 条全回
        val reRegistered = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }
        assertTrue(reRegistered.disabledUids.isEmpty())
    }

    @Test
    fun `setBookSessionBindings replaces bindings and updates scope metadata`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val globalBook = library.loadAllBooks().first { it.isGlobalActive }

        val updated = library.setBookSessionBindings(globalBook.id, listOf("s1", "s2"))!!
        assertEquals(listOf("s1", "s2"), updated.sessionIds)
        assertEquals(WorldInfoBookScope.GLOBAL, updated.scope) // 全局书保持 GLOBAL 展示
        assertTrue(updated.isGlobalActive) // 全局标记不受绑定影响

        // 全量替换
        val updated2 = library.setBookSessionBindings(globalBook.id, listOf("s2"))!!
        assertEquals(listOf("s2"), updated2.sessionIds)

        // 清空绑定回到无绑定态
        val updated3 = library.setBookSessionBindings(globalBook.id, emptyList())!!
        assertTrue(updated3.sessionIds.isEmpty())
    }

    @Test
    fun `loadCardBookEntries merges card-level disable and user override`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        val entries = library.loadCardBookEntries(cardBook.id)!!
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.enabled }) // 卡内全启用、无 override

        // 用户关 uid=1 → merged enabled=false，id 稳定 uid_<n>
        library.setCardEntryOverride(cardBook.id, uid = 1, enabled = false)
        val merged = library.loadCardBookEntries(cardBook.id)!!
        assertEquals(false, merged.first { it.uid == 1 }.enabled)
        assertTrue(merged.first { it.uid == 2 }.enabled)

        // 来源卡删除 → null
        CharacterDocumentStore(File(root, "characters")).delete("char_lin")
        assertNull(library.loadCardBookEntries(cardBook.id))
    }

    @Test
    fun `bookSummaries counts entries, conflicts and source deletion`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()

        // 会话书 s1（1 条，enabled）+ 另一本书也绑 s1 → 冲突
        library.createOwnedBook(
            name = "另一本", entries = emptyList(), sessionIds = listOf("s1")
        )
        val summaries = library.bookSummaries()

        assertEquals(4, summaries.size) // 全局 + 会话 + 卡 + 新建
        val global = summaries.first { it.book.isGlobalActive }
        assertEquals(2, global.totalEntries)
        assertEquals(1, global.constantEntries) // g1 constant=true
        assertEquals(0, global.disabledEntries)

        val card = summaries.first { it.book.origin == WorldInfoBookOrigin.CARD }
        assertEquals(2, card.totalEntries)
        assertEquals(1, card.constantEntries) // 卡内 uid=1 constant
        assertFalse(card.sourceDeleted)

        // 两本绑 s1 的书互为冲突
        val sessionBound = summaries.filter { "s1" in it.book.sessionIds }
        assertEquals(2, sessionBound.size)
        assertTrue(sessionBound.all { "s1" in it.conflictingSessions })

        // 来源卡删除 → sourceDeleted
        CharacterDocumentStore(File(root, "characters")).delete("char_lin")
        val refreshed = library.bookSummaries()
        assertTrue(refreshed.first { it.book.origin == WorldInfoBookOrigin.CARD }.sourceDeleted)
    }

    @Test
    fun `card book config override takes precedence over card-embedded config`() = runBlocking {
        val root = makeRoot()
        seedLegacyData(root)
        val library = WorldInfoLibrary(root)
        library.migrateIfNeeded()
        val cardBook = library.loadAllBooks().first { it.origin == WorldInfoBookOrigin.CARD }

        // 无覆盖：卡内 scan_depth=7 生效
        val base = library.resolveActiveBook("s9", "char_lin", defaultConfig())
        assertEquals(7, base.config.scanDepth)

        // 书级覆盖 scanDepth=2 → 整本覆盖生效（Spec §4.3）
        library.saveBook(
            cardBook.copy(config = WorldInfoConfig(scanDepth = 2), updatedAt = System.currentTimeMillis())
        )
        val overridden = library.resolveActiveBook("s9", "char_lin", defaultConfig())
        assertEquals(2, overridden.config.scanDepth)
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
