package com.loyea.ui.chat

import com.loyea.context.core.*
import com.loyea.plugins.tavern.core.*
import com.loyea.plugins.tavern.storage.*

import android.content.Context
import com.loyea.plugin.api.PluginIds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun `legacy Tavern registry migrates into plugin storage without deleting source`() = runBlocking {
        val legacy = File(tempFolder.root, "files/tavern_resources.json")
        legacy.writeText(
            """{"revision":7,"worldBooks":[],"presets":[],"regexCollections":[]}"""
        )

        val loaded = storageManager.loadTavernResourceRegistry()
        val layout = TavernStorageLayout(File(tempFolder.root, "files/tavern"))

        assertEquals(7L, loaded.revision)
        assertTrue(layout.registryFile.isFile)
        assertEquals(legacy.readText(), layout.registryFile.readText())
        assertTrue(legacy.isFile)
        assertTrue(layout.migrationMarkerFile.isFile)
    }

    @Test
    fun `imported card raw document is copied to plugin storage separately from projection`() = runBlocking {
        val card = requireNotNull(
            TavernCardParser.parseJsonCard(
                """{"name":"Stored","description":"raw","creator":"","first_mes":"hello"}"""
            )
        )

        storageManager.saveCharacterCards(listOf(card))

        val layout = TavernStorageLayout(File(tempFolder.root, "files/tavern"))
        val rawFile = layout.resolve(layout.cardDocumentRelativePath(card.id))
        assertTrue(rawFile.isFile)
        assertEquals(
            TavernCardCodec.toJson(TavernCharacterCardAdapter.toDocument(card)),
            rawFile.readText()
        )
        assertTrue(layout.migrationMarkerFile.isFile)
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
    fun `legacy session persona owners migrate once and persist`() = runBlocking {
        val sessionsFile = File(tempFolder.root, "files/sessions_metadata.json")
        val legacyJson = """
            [
              {"id":"native","title":"Native","characterId":"char_loyea_default"},
              {"id":"external","title":"External","characterId":"imported-card"}
            ]
            """.trimIndent()
        sessionsFile.writeText(legacyJson)

        val loaded = storageManager.loadSessionList().associateBy(ChatSession::id)

        assertEquals(PluginIds.NATIVE.value, loaded.getValue("native").personaOwnerId)
        assertEquals(TavernPluginDefinition.ID.value, loaded.getValue("external").personaOwnerId)
        assertTrue(loaded.values.all { it.sessionIncarnationId.isNotBlank() })
        assertTrue(loaded.values.all { it.personaBindingRevision == 1L })
        assertTrue(loaded.values.all { it.personaBindingSchemaVersion == CHAT_SESSION_PERSONA_SCHEMA_VERSION })
        val persisted = sessionsFile.readText()
        assertTrue(persisted.contains("\"personaOwnerId\":\"${PluginIds.NATIVE.value}\""))
        assertTrue(persisted.contains("\"personaOwnerId\":\"${TavernPluginDefinition.ID.value}\""))
        val backup = File(tempFolder.root, "files/sessions_metadata.pre_persona_binding_v1.json")
        assertEquals(legacyJson, backup.readText())

        storageManager.loadSessionList()
        assertEquals(legacyJson, backup.readText())
    }

    @Test
    fun `blank legacy persona remains unresolved instead of becoming default Loyea`() = runBlocking {
        File(tempFolder.root, "files/sessions_metadata.json").writeText(
            """[{"id":"blank","title":"Blank","characterId":""}]"""
        )

        val loaded = storageManager.loadSessionList().single()

        assertEquals("", loaded.characterId)
        assertEquals(CharacterPersonaOwnership.UNRESOLVED_PERSONA_OWNER_ID, loaded.personaOwnerId)
        assertNull(CharacterPersonaOwnership.refFor(loaded))
    }

    @Test
    fun `persona migration write failure preserves the original file for retry`() = runBlocking {
        val sessionsFile = File(tempFolder.root, "files/sessions_metadata.json")
        val legacyJson = """[{"id":"legacy","title":"Legacy","characterId":"char_loyea_default"}]"""
        sessionsFile.writeText(legacyJson)
        val blockedTemporary = File(
            tempFolder.root,
            "files/sessions_metadata.pre_persona_binding_v1.json.tmp"
        )
        assertTrue(blockedTemporary.mkdir())

        val loaded = storageManager.loadSessionList().single()

        assertEquals(PluginIds.NATIVE.value, loaded.personaOwnerId)
        assertEquals(legacyJson, sessionsFile.readText())
        assertFalse(File(tempFolder.root, "files/sessions_metadata.json.corrupt").exists())

        if (blockedTemporary.exists()) assertTrue(blockedTemporary.delete())
        storageManager.loadSessionList()
        assertTrue(sessionsFile.readText().contains("\"personaBindingSchemaVersion\":1"))
    }

    @Test
    fun `persona binding revision advances and defeats ABA changes`() = runBlocking {
        val original = ChatSession(
            id = "aba",
            title = "ABA",
            characterId = "external-a",
            personaOwnerId = TavernPluginDefinition.ID.value
        )
        storageManager.saveSessionList(listOf(original))
        val persistedOriginal = storageManager.loadSessionList().single()
        val fence = requireNotNull(PersonaBindingSnapshot.capture(persistedOriginal))

        storageManager.updateSessionList { sessions ->
            sessions.map { it.copy(characterId = "external-b") }
        }
        storageManager.updateSessionList { sessions ->
            sessions.map { it.copy(characterId = "external-a") }
        }
        val rebound = storageManager.loadSessionList().single()

        assertEquals(original.characterId, rebound.characterId)
        assertEquals(persistedOriginal.personaBindingRevision + 2L, rebound.personaBindingRevision)
        assertFalse(fence.matches(rebound))
    }

    @Test
    fun `session incarnation rejects delete and recreate with the same public id`() = runBlocking {
        val original = ChatSession("same-id", "Original")
        storageManager.saveSessionList(listOf(original))
        val persistedOriginal = storageManager.loadSessionList().single()
        val fence = requireNotNull(PersonaBindingSnapshot.capture(persistedOriginal))

        storageManager.deleteSession(original.id)
        val recreated = ChatSession("same-id", "Recreated")
        storageManager.saveSessionList(listOf(recreated))
        val persistedRecreated = storageManager.loadSessionList().single()

        assertNotEquals(persistedOriginal.sessionIncarnationId, persistedRecreated.sessionIncarnationId)
        assertFalse(fence.matches(persistedRecreated))
    }

    @Test
    fun `explicit persona owner survives storage round trip`() = runBlocking {
        val session = ChatSession(
            id = "future",
            title = "Future plugin",
            characterId = "persona-1",
            personaOwnerId = "com.example.future-plugin"
        )

        storageManager.saveSessionList(listOf(session))

        assertEquals(session, storageManager.loadSessionList().single())
    }

    @Test
    fun `background greeting commit is fenced by persisted persona binding`() = runBlocking {
        val session = ChatSession(
            id = "greeting",
            title = "External",
            characterId = "external-card",
            personaOwnerId = TavernPluginDefinition.ID.value
        )
        storageManager.saveSessionList(listOf(session))
        storageManager.saveSessionMessages(session.id, emptyList())
        val fence = requireNotNull(PersonaBindingSnapshot.capture(session))
        val staleOperationId = "stale-operation"
        val greeting = Message(
            ChatStorageManager.backgroundGreetingMessageId(staleOperationId),
            "hello",
            Sender.AI,
            characterId = session.characterId
        )

        storageManager.updateSessionList { sessions ->
            sessions.map { it.copy(personaOwnerId = PluginIds.NATIVE.value) }
        }
        assertEquals(BackgroundGreetingCommitStatus.STALE, storageManager.commitBackgroundGreeting(
            staleOperationId,
            fence,
            greeting,
            3,
            5,
            lastActiveTime = 123L
        ).status)
        assertEquals(emptyList<Message>(), storageManager.loadSessionMessages(session.id))
        assertEquals(0L, storageManager.loadSessionList().single().promptTokens)

        storageManager.saveSessionList(listOf(session))
        val rebound = storageManager.loadSessionList().single()
        val reboundFence = requireNotNull(PersonaBindingSnapshot.capture(rebound))
        val committedOperationId = "committed-operation"
        val committedGreeting = greeting.copy(
            id = ChatStorageManager.backgroundGreetingMessageId(committedOperationId)
        )
        assertEquals(BackgroundGreetingCommitStatus.COMMITTED, storageManager.commitBackgroundGreeting(
            committedOperationId,
            reboundFence,
            committedGreeting,
            3,
            5,
            lastActiveTime = 123L
        ).status)
        assertEquals(listOf(committedGreeting), storageManager.loadSessionMessages(session.id))
        val committed = storageManager.loadSessionList().single()
        assertEquals(3L, committed.promptTokens)
        assertEquals(5L, committed.completionTokens)
        assertEquals(123L, committed.lastActiveTime)
    }

    @Test
    fun `background greeting retry is idempotent after a successful commit`() = runBlocking {
        val session = ChatSession("greeting-idempotent", "Greeting")
        storageManager.saveSessionList(listOf(session))
        val persisted = storageManager.loadSessionList().single()
        val binding = requireNotNull(PersonaBindingSnapshot.capture(persisted))
        val operationId = "same-work-id"
        val greeting = Message(
            ChatStorageManager.backgroundGreetingMessageId(operationId),
            "hello once",
            Sender.AI,
            characterId = persisted.characterId
        )

        val first = storageManager.commitBackgroundGreeting(
            operationId, binding, greeting, 7, 11, lastActiveTime = 456L
        )
        val second = storageManager.commitBackgroundGreeting(
            operationId, binding, greeting, 7, 11, lastActiveTime = 456L
        )
        val resumedWithoutJournal = storageManager.resumeBackgroundGreeting(operationId, binding)

        assertEquals(BackgroundGreetingCommitStatus.COMMITTED, first.status)
        assertEquals(BackgroundGreetingCommitStatus.ALREADY_COMMITTED, second.status)
        assertEquals(BackgroundGreetingCommitStatus.ALREADY_COMMITTED, resumedWithoutJournal?.status)
        assertEquals(listOf(greeting), storageManager.loadSessionMessages(session.id))
        val finalSession = storageManager.loadSessionList().single()
        assertEquals(7L, finalSession.promptTokens)
        assertEquals(11L, finalSession.completionTokens)
        assertEquals(listOf(operationId), finalSession.appliedBackgroundOperations.map { it.operationId })
    }

    @Test
    fun `background receipt cannot cross an ABA binding revision`() = runBlocking {
        val session = ChatSession(
            id = "greeting-receipt-aba",
            title = "Greeting",
            characterId = "external-a",
            personaOwnerId = TavernPluginDefinition.ID.value
        )
        storageManager.saveSessionList(listOf(session))
        val original = storageManager.loadSessionList().single()
        val originalBinding = requireNotNull(PersonaBindingSnapshot.capture(original))
        val operationId = "receipt-aba-work-id"
        val greeting = Message(
            ChatStorageManager.backgroundGreetingMessageId(operationId),
            "old binding",
            Sender.AI,
            characterId = original.characterId
        )
        storageManager.commitBackgroundGreeting(
            operationId, originalBinding, greeting, 1, 1, lastActiveTime = 1L
        )
        storageManager.updateSessionList { sessions -> sessions.map { it.copy(characterId = "external-b") } }
        storageManager.updateSessionList { sessions -> sessions.map { it.copy(characterId = "external-a") } }
        val rebound = storageManager.loadSessionList().single()
        val reboundBinding = requireNotNull(PersonaBindingSnapshot.capture(rebound))

        val outcome = storageManager.resumeBackgroundGreeting(operationId, reboundBinding)

        assertEquals(BackgroundGreetingCommitStatus.STALE, outcome?.status)
        assertEquals(listOf(greeting), storageManager.loadSessionMessages(session.id))
        assertEquals(1L, rebound.promptTokens)
    }

    @Test
    fun `background greeting journal repairs a crash between message and metadata writes`() = runBlocking {
        val session = ChatSession("greeting-recovery", "Greeting")
        storageManager.saveSessionList(listOf(session))
        val persisted = storageManager.loadSessionList().single()
        val binding = requireNotNull(PersonaBindingSnapshot.capture(persisted))
        val operationId = "recovery-work-id"
        val greeting = Message(
            ChatStorageManager.backgroundGreetingMessageId(operationId),
            "recover me",
            Sender.AI,
            characterId = persisted.characterId
        )
        val crashingStorage = ChatStorageManager(context) { stage ->
            if (stage == BackgroundGreetingCommitStage.AFTER_MESSAGE_WRITE) {
                throw java.io.IOException("simulated process loss")
            }
        }

        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                crashingStorage.commitBackgroundGreeting(
                    operationId, binding, greeting, 13, 17, lastActiveTime = 789L
                )
            }
        }
        assertEquals(listOf(greeting), storageManager.loadSessionMessages(session.id))
        assertEquals(0L, storageManager.loadSessionList().single().promptTokens)

        val recovered = storageManager.resumeBackgroundGreeting(operationId, binding)

        assertEquals(BackgroundGreetingCommitStatus.COMMITTED, recovered?.status)
        assertEquals(listOf(greeting), storageManager.loadSessionMessages(session.id))
        val finalSession = storageManager.loadSessionList().single()
        assertEquals(13L, finalSession.promptTokens)
        assertEquals(17L, finalSession.completionTokens)
    }

    @Test
    fun `background greeting recovery cleans a journal left after metadata commit`() = runBlocking {
        val session = ChatSession("greeting-post-commit-recovery", "Greeting")
        storageManager.saveSessionList(listOf(session))
        val persisted = storageManager.loadSessionList().single()
        val binding = requireNotNull(PersonaBindingSnapshot.capture(persisted))
        val operationId = "post-commit-recovery-work-id"
        val greeting = Message(
            ChatStorageManager.backgroundGreetingMessageId(operationId),
            "already committed",
            Sender.AI,
            characterId = persisted.characterId
        )
        val crashingStorage = ChatStorageManager(context) { stage ->
            if (stage == BackgroundGreetingCommitStage.AFTER_SESSION_WRITE) {
                throw java.io.IOException("simulated process loss")
            }
        }

        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                crashingStorage.commitBackgroundGreeting(
                    operationId, binding, greeting, 19, 23, lastActiveTime = 999L
                )
            }
        }
        val sessionsDir = File(tempFolder.root, "files/sessions")
        assertEquals(1, sessionsDir.listFiles { file ->
            file.name.startsWith("background_greeting_pending_")
        }.orEmpty().size)

        val recovered = storageManager.resumeBackgroundGreeting(operationId, binding)

        assertEquals(BackgroundGreetingCommitStatus.ALREADY_COMMITTED, recovered?.status)
        assertEquals(listOf(greeting), storageManager.loadSessionMessages(session.id))
        val finalSession = storageManager.loadSessionList().single()
        assertEquals(19L, finalSession.promptTokens)
        assertEquals(23L, finalSession.completionTokens)
        assertTrue(sessionsDir.listFiles { file ->
            file.name.startsWith("background_greeting_pending_")
        }.orEmpty().isEmpty())
    }

    @Test
    fun `stale recovery removes an uncommitted greeting fragment`() = runBlocking {
        val session = ChatSession(
            id = "greeting-stale-recovery",
            title = "Greeting",
            characterId = "external-a",
            personaOwnerId = TavernPluginDefinition.ID.value
        )
        storageManager.saveSessionList(listOf(session))
        val persisted = storageManager.loadSessionList().single()
        val binding = requireNotNull(PersonaBindingSnapshot.capture(persisted))
        val operationId = "stale-recovery-work-id"
        val greeting = Message(
            ChatStorageManager.backgroundGreetingMessageId(operationId),
            "stale",
            Sender.AI,
            characterId = persisted.characterId
        )
        val crashingStorage = ChatStorageManager(context) { stage ->
            if (stage == BackgroundGreetingCommitStage.AFTER_MESSAGE_WRITE) {
                throw java.io.IOException("simulated process loss")
            }
        }
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                crashingStorage.commitBackgroundGreeting(
                    operationId, binding, greeting, 2, 3, lastActiveTime = 100L
                )
            }
        }

        storageManager.updateSessionList { sessions ->
            sessions.map { it.copy(characterId = "external-b") }
        }
        val outcome = storageManager.resumeBackgroundGreeting(operationId, binding)

        assertEquals(BackgroundGreetingCommitStatus.STALE, outcome?.status)
        assertEquals(emptyList<Message>(), storageManager.loadSessionMessages(session.id))
        assertEquals(0L, storageManager.loadSessionList().single().promptTokens)
    }

    @Test
    fun `persona fenced message update rejects a changed binding`() = runBlocking {
        val session = ChatSession(
            id = "fenced-messages",
            title = "Fenced",
            characterId = "external-a",
            personaOwnerId = TavernPluginDefinition.ID.value
        )
        storageManager.saveSessionList(listOf(session))
        val persisted = storageManager.loadSessionList().single()
        val binding = requireNotNull(PersonaBindingSnapshot.capture(persisted))
        val first = Message("first", "hello", Sender.USER, characterId = persisted.characterId)

        assertEquals(
            listOf(first),
            storageManager.updateSessionMessagesIfPersonaBinding(binding) { it + first }
        )
        storageManager.updateSessionList { sessions ->
            sessions.map { it.copy(characterId = "external-b") }
        }
        val stale = Message("stale", "must not write", Sender.AI, characterId = persisted.characterId)

        assertNull(storageManager.updateSessionMessagesIfPersonaBinding(binding) { it + stale })
        assertEquals(listOf(first), storageManager.loadSessionMessages(session.id))
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
        val legacyFile = File(tempFolder.root, "files/sessions/session_$legacyId.json")
        legacyFile.writeText("""[{"id":"old","content":"legacy","sender":"USER","timestamp":1}]""")
        val legacyMessage = storageManager.loadSessionMessages(legacyId).single()
        assertNull(legacyMessage.llmContextSnapshot)
        assertNotNull(legacyMessage.llmTimeZoneId)
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
