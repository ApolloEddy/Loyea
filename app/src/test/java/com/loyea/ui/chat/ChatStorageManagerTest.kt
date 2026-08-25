package com.loyea.ui.chat

import com.loyea.context.core.*
import com.loyea.plugins.tavern.core.*
import com.loyea.plugins.tavern.storage.*

import android.content.Context
import com.google.gson.JsonParser
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
    fun `Tavern group roster persists with session and can return to solo mode`() = runBlocking {
        storageManager.saveSessionList(listOf(ChatSession("group", "Group", 1000L)))
        val group = TavernGroupChat(
            id = "group-1",
            name = "Party",
            members = listOf(
                TavernGroupMember("alice", "Alice"),
                TavernGroupMember("bob", "Bob", muted = true)
            ),
            replyMode = TavernGroupReplyMode.ALL_MEMBERS
        )

        storageManager.updateSessionGroupChat("group", group)
        val loaded = storageManager.loadSessionList().single()
        assertEquals(TavernGroupCodec.toJson(group), loaded.groupChatJson)
        assertEquals(group, loaded.tavernGroupChat())

        storageManager.updateSessionGroupChat("group", null)
        assertNull(storageManager.loadSessionList().single().tavernGroupChat())
    }

    @Test
    fun `Tavern fork commit persists parent update and child metadata together`() = runBlocking {
        val parent = ChatSession("parent", "Main", 1000L)
        val parentMessages = listOf(Message("m1", "hello", Sender.USER))
        val updatedParentMessages = parentMessages.map {
            it.copy(tavernExtraJson = "{\"branches\":[\"Child\"]}")
        }
        val child = parent.copy(
            id = "child",
            title = "Child",
            tavernMainChat = "Main",
            tavernForkMode = "BRANCH"
        )
        storageManager.saveSessionList(listOf(parent))
        storageManager.saveSessionMessages(parent.id, parentMessages)

        val updated = storageManager.saveTavernSessionFork(
            parentSessionId = parent.id,
            parentMessages = updatedParentMessages,
            childSession = child,
            childMessages = parentMessages
        )

        assertEquals(listOf("parent", "child"), updated.map(ChatSession::id))
        assertEquals(updatedParentMessages, storageManager.loadSessionMessages("parent"))
        assertEquals(parentMessages, storageManager.loadSessionMessages("child"))
        val persistedChild = storageManager.loadSessionList().single { it.id == "child" }
        assertEquals("Main", persistedChild.tavernMainChat)
        assertEquals("BRANCH", persistedChild.tavernForkMode)
    }

    @Test
    fun `Tavern chat header metadata survives session normalization`() = runBlocking {
        val header = "{\"user_name\":\"Eddy\",\"character_name\":\"Alice\",\"chat_metadata\":{\"main_chat\":\"root\"}}"
        storageManager.saveSessionList(
            listOf(ChatSession("jsonl", "JSONL", tavernChatHeaderJson = header))
        )

        assertEquals(header, storageManager.loadSessionList().single().tavernChatHeaderJson)
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
            emitGroupHeaders = true,
            includeNames = false
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
        assertEquals(true, partial.includeNames)
    }

    // ---------- B2/B4/B5/B7 会话特性字段存储 ----------

    @Test
    fun `B2B4B5B7 新字段显式值与默认值往返一致`() = runBlocking {
        // 显式写入全部新字段（含 B7 选角专用绑定 speakerApiBindingId）
        val explicit = ChatSession(
            id = "feature",
            title = "Feature",
            isPinned = true,
            apiBindingId = "api-1",
            speakerApiBindingId = "speaker-api-1",
            memoryEnabled = false
        )
        storageManager.saveSessionList(listOf(explicit))
        val loaded = storageManager.loadSessionList().single()
        assertTrue(loaded.isPinned)
        assertEquals("api-1", loaded.apiBindingId)
        assertEquals("speaker-api-1", loaded.speakerApiBindingId)
        assertEquals(false, loaded.memoryEnabled)

        // 未指定时走默认：isPinned=false、apiBindingId=null、speakerApiBindingId=null、memoryEnabled=null
        storageManager.saveSessionList(listOf(ChatSession("plain", "Plain")))
        val loadedDefault = storageManager.loadSessionList().single()
        assertFalse(loadedDefault.isPinned)
        assertNull(loadedDefault.apiBindingId)
        assertNull(loadedDefault.speakerApiBindingId)
        assertNull(loadedDefault.memoryEnabled)
    }

    @Test
    fun `克隆会话产生深度独立的消息与元数据且不污染源`() = runBlocking {
        val source = ChatSession(
            id = "src",
            title = "Source",
            isPinned = true,
            apiBindingId = "api-x",
            memoryEnabled = true
        )
        storageManager.saveSessionList(listOf(source))
        storageManager.saveSessionMessages(
            source.id,
            listOf(Message("m1", "hello", Sender.USER), Message("m2", "world", Sender.AI))
        )

        val clone = requireNotNull(storageManager.cloneSession("src", "Clone"))

        // 克隆元数据
        assertEquals("Clone", clone.title)
        assertNotEquals("src", clone.id)
        assertNotEquals(source.sessionIncarnationId, clone.sessionIncarnationId)
        // 克隆重置置顶，但继承 apiBindingId / memoryEnabled 等配置类继承
        assertFalse(clone.isPinned)
        assertEquals("api-x", clone.apiBindingId)
        assertEquals(true, clone.memoryEnabled)

        // 消息深度独立：内容一致、各持一份
        val cloneMessages = storageManager.loadSessionMessages(clone.id)
        val sourceMessages = storageManager.loadSessionMessages("src")
        assertEquals(2, cloneMessages.size)
        assertEquals(2, sourceMessages.size)
        assertEquals(sourceMessages.map(Message::content), cloneMessages.map(Message::content))

        // 删除克隆不会影响源会话
        storageManager.deleteSession(clone.id)
        assertEquals(2, storageManager.loadSessionMessages("src").size)
        assertEquals(listOf("src"), storageManager.loadSessionList().map(ChatSession::id))
    }

    @Test
    fun `重启会话清空消息但保留配置`() = runBlocking {
        val session = ChatSession(
            id = "rs",
            title = "Restart",
            apiBindingId = "api-r",
            memoryEnabled = false,
            authorNote = "keep me"
        )
        storageManager.saveSessionList(listOf(session))
        storageManager.saveSessionMessages(
            session.id,
            listOf(Message("m1", "one", Sender.USER), Message("m2", "two", Sender.AI))
        )

        assertTrue(storageManager.restartSession("rs"))

        // 消息被清空
        assertEquals(emptyList<Message>(), storageManager.loadSessionMessages("rs"))
        // 配置字段全部保留
        val kept = storageManager.loadSessionList().single()
        assertEquals("Restart", kept.title)
        assertEquals("api-r", kept.apiBindingId)
        assertEquals(false, kept.memoryEnabled)
        assertEquals("keep me", kept.authorNote)

        // 不存在的会话返回 false 且不抛异常
        assertFalse(storageManager.restartSession("no-such-session"))
    }

    @Test
    fun `旧数据加载补默认值不抛异常并写入版本迁移标记与恢复备份`() = runBlocking {
        val sessionsFile = File(tempFolder.root, "files/sessions_metadata.json")
        val legacyJson = """[{"id":"legacy1","title":"Old","characterId":"char_loyea_default"}]"""
        sessionsFile.writeText(legacyJson)

        val loaded = storageManager.loadSessionList().single()
        assertFalse(loaded.isPinned)
        assertNull(loaded.apiBindingId)
        assertNull(loaded.speakerApiBindingId)
        assertNull(loaded.memoryEnabled)
        assertEquals(CHAT_SESSION_SCHEMA_VERSION, loaded.sessionSchemaVersion)

        // 迁移落盘后元数据带版本标记，且生成敏感恢复备份（原始 JSON 原样保留）
        assertTrue(sessionsFile.readText().contains("\"sessionSchemaVersion\":1"))
        val backup = File(tempFolder.root, "files/sessions_metadata.pre_session_schema_v1.json")
        assertEquals(legacyJson, backup.readText())
    }

    @Test
    fun `B7 旧会话缺失 speakerApiBindingId 归一化为 null 且空白串也被清洗`() = runBlocking {
        // 模拟旧/残缺数据：speakerApiBindingId 要么不存在、要么为空白串
        val sessionsFile = File(tempFolder.root, "files/sessions_metadata.json")
        val legacyJson = """
            [
              {"id":"old","title":"Old","characterId":"char_loyea_default"},
              {"id":"blank","title":"Blank","characterId":"char_loyea_default","speakerApiBindingId":"   "}
            ]
            """.trimIndent()
        sessionsFile.writeText(legacyJson)

        val loaded = storageManager.loadSessionList().associateBy(ChatSession::id)
        assertNull(loaded.getValue("old").speakerApiBindingId)
        assertNull(loaded.getValue("blank").speakerApiBindingId)

        // 显式有效值保留并正常往返
        storageManager.saveSessionList(
            listOf(ChatSession("filled", "Filled", speakerApiBindingId = "speaker-api-9"))
        )
        assertEquals("speaker-api-9", storageManager.loadSessionList().single().speakerApiBindingId)
    }

    // ---- TODO2：personaSummaryStore 单一真源 ----

    @Test
    fun `save writes persona summary store and document store but not character cards json`() = runBlocking {
        val card = requireNotNull(
            TavernCardParser.parseJsonCard(
                """{"name":"V2","description":"desc","tags":["a"],"first_mes":"hi","extensions":{"x":1}}"""
            )
        )

        storageManager.saveCharacterCards(listOf(card))

        // 单一真源：Tavern 完整字段进插件文档库，原生投影进 personaSummaryStore。
        val layout = TavernStorageLayout(File(tempFolder.root, "files/tavern"))
        val docRaw = layout.resolve(layout.cardDocumentRelativePath(card.id)).readText()
        assertTrue(docRaw.contains("desc"))
        assertTrue(docRaw.contains("a"))
        val summaries = storageManager.loadPersonaSummaries().single()
        assertEquals(card.id, summaries.id)
        assertEquals("V2", summaries.name)
        // character_cards.json 不再写入（退化为仅服务一次性迁移）。
        assertFalse(File(tempFolder.root, "files/character_cards.json").exists())
    }

    @Test
    fun `load assembles cards from persona summary store plus document store`() = runBlocking {
        val card = requireNotNull(
            TavernCardParser.parseJsonCard(
                """{"name":"SingleSource","description":"d","tags":["t"],"first_mes":"hi","extensions":{"k":1}}"""
            )
        )
        storageManager.saveCharacterCards(listOf(card))

        // store-first 路径：personaSummaryStore 已存在，直接组装（不再读 character_cards.json）。
        val loaded = storageManager.loadCharacterCards()

        val user = loaded.first { it.id == card.id }
        assertEquals("SingleSource", user.name)
        assertEquals("d", user.description)
        assertEquals(listOf("t"), user.tags)
        assertTrue(loaded.any { it.isBuiltIn })
    }

    @Test
    fun `edited builtin override survives single source round trip but pristine builtin stays out of store`() = runBlocking {
        // 首载注入内置卡并落 store（未改动内置卡不入库）。
        storageManager.loadCharacterCards()
        assertTrue(storageManager.loadPersonaSummaries().isEmpty())

        // 用户编辑一个内置卡（原生字段），保存。
        val pristine = TavernCardParser.getBuiltInCards()
        val edited = pristine.first().copy(systemPrompt = "edited prompt")
        storageManager.saveCharacterCards(pristine.map { if (it.id == edited.id) edited else it })

        // 二次加载：编辑保留；store 只含该内置覆盖。
        val reloaded = storageManager.loadCharacterCards()
        assertEquals("edited prompt", reloaded.first { it.id == edited.id }.systemPrompt)
        val summaries = storageManager.loadPersonaSummaries()
        assertEquals(listOf(edited.id), summaries.map { it.id })
    }

    @Test
    fun `load migrates legacy json to v2 backup and enriches tavern fields from document store`() = runBlocking {
        val legacy = """
            [
              {"id":"c1","name":"Imported","shortIntro":"s","systemPrompt":"p",
               "description":"desc","tags":["a"],"extensionsJson":"{\"k\":1}","isBuiltIn":false}
            ]
        """.trimIndent()
        File(tempFolder.root, "files/character_cards.json").writeText(legacy)

        val loaded = storageManager.loadCharacterCards()

        val card = loaded.single()
        assertEquals("desc", card.description)
        assertEquals(listOf("a"), card.tags)

        // 源文件已重写为 v2。
        val wireJson = File(tempFolder.root, "files/character_cards.json").readText()
        assertFalse(JsonParser.parseString(wireJson).asJsonArray[0].asJsonObject.has("description"))

        // wire v2 备份与 D2 备份都存在（D2 备份先于 wire 迁移、捕获旧格式原样）。
        assertTrue(File(tempFolder.root, "files/character_cards.pre_tavern_field_drop_v1.json").isFile)
        assertTrue(File(tempFolder.root, "files/character_cards.pre_persona_summary_v1.json").isFile)
    }
}
