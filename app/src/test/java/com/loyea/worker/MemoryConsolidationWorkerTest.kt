package com.loyea.worker

import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginId
import com.loyea.ui.chat.PersonaBindingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemoryConsolidationWorkerTest {
    @Test
    fun `unique work name is deterministic and isolates every binding dimension`() {
        val base = binding()
        val baseName = MemoryConsolidationWorker.uniqueWorkName(base)

        assertEquals(baseName, MemoryConsolidationWorker.uniqueWorkName(base))
        assertNotEquals(baseName, MemoryConsolidationWorker.uniqueWorkName(base.copy(sessionId = "other-session")))
        assertNotEquals(
            baseName,
            MemoryConsolidationWorker.uniqueWorkName(base.copy(sessionIncarnationId = "other-incarnation"))
        )
        assertNotEquals(
            baseName,
            MemoryConsolidationWorker.uniqueWorkName(base.copy(personaBindingRevision = 2L))
        )
        assertNotEquals(
            baseName,
            MemoryConsolidationWorker.uniqueWorkName(
                base.copy(ref = PersonaRef(PluginId.of("com.example.other"), base.ref.personaId))
            )
        )
        assertNotEquals(
            baseName,
            MemoryConsolidationWorker.uniqueWorkName(
                base.copy(ref = PersonaRef(base.ref.ownerId, "other-persona"))
            )
        )
        assertFalse(baseName.contains(base.sessionId))
        assertFalse(baseName.contains(base.ref.personaId))
    }

    private fun binding() = PersonaBindingSnapshot(
        sessionId = "private-session",
        sessionIncarnationId = "incarnation-1",
        personaBindingRevision = 1L,
        ref = PersonaRef(PluginId.of("com.example.plugin"), "private-persona")
    )
}
