package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernQuickReplyTest {
    @Test
    fun `parses current quick reply v2 and preserves unknown fields`() {
        val json = """
            {
              "version":2,
              "name":"Writing",
              "disableSend":true,
              "injectInput":true,
              "vendorFlag":"keep",
              "qrList":[{
                "id":3,
                "label":"Continue scene",
                "title":"tooltip",
                "message":"/continue",
                "isHidden":false,
                "executeBeforeGeneration":true,
                "executeOnNewChat":true,
                "vendorReplyField":42
              }]
            }
        """.trimIndent()

        val set = requireNotNull(TavernQuickReplyCodec.parseSet(json))
        assertEquals("Writing", set.name)
        assertTrue(set.disableSend)
        assertTrue(set.injectInput)
        assertEquals("Continue scene", set.qrList.single().label)
        assertTrue(set.qrList.single().executeBeforeGeneration)
        assertTrue(set.qrList.single().executeOnNewChat)
        val roundTrip = TavernQuickReplyCodec.toJson(set)
        assertTrue(roundTrip.contains("vendorFlag"))
        assertTrue(roundTrip.contains("vendorReplyField"))
        assertEquals("/continue", TavernQuickReplyCodec.parseSet(roundTrip)!!.qrList.single().message)
    }

    @Test
    fun `maps legacy quick reply slot before-generation flag`() {
        val set = requireNotNull(
            TavernQuickReplyCodec.parseSet(
                """
                {
                  "name":"Legacy",
                  "quickReplySlots":[{
                    "label":"Before",
                    "mes":"/echo ready",
                    "autoExecute_beforeGeneration":true
                  }]
                }
                """.trimIndent()
            )
        )

        assertTrue(set.qrList.single().executeBeforeGeneration)
    }

    @Test
    fun `executes macros variables and nested quick replies without host side effects`() {
        val set = TavernQuickReplySet(
            name = "Tools",
            qrList = listOf(
                TavernQuickReply(label = "Nested", message = "/pass nested {{pipe}}")
            )
        )
        val context = TavernStScriptContext(
            macroContext = TavernMacroContext(characterName = "Alice", userName = "Eddy"),
            input = "draft"
        )
        val result = TavernStScriptEngine.execute(
            script = "/pass hello | /setvar key=count 1 | /incvar key=count | /run Nested | /echo {{char}} {{pipe}}",
            context = context,
            quickReplySets = listOf(set)
        )

        assertEquals("Alice nested 2", result.pipe)
        assertEquals("2", result.localVariables["count"])
        assertEquals(1, result.effects.filterIsInstance<TavernStScriptEffect.Toast>().size)
        assertTrue(result.diagnostics.isEmpty())
        assertFalse(result.aborted)
    }

    @Test
    fun `blocks unknown commands and supports escaped pipes`() {
        val result = TavernStScriptEngine.execute("/pass left\\|right | /javascript alert(1)")

        assertEquals("left|right", result.pipe)
        assertEquals("unsupported or blocked command", result.diagnostics.single().reason)
        assertFalse(result.diagnostics.single().fatal)
    }

    @Test
    fun `supports pipe injection conditionals and procedure return`() {
        val procedure = TavernQuickReplySet(
            name = "Flow",
            qrList = listOf(TavernQuickReply(label = "Yes", message = "/pass yes | /return"))
        )
        val result = TavernStScriptEngine.execute(
            "/setvar key=count 5 | " +
                "/if left=count right=5 rule=eq else={: /pass no :} {: /run Flow.Yes :} | " +
                "/echo"
            , quickReplySets = listOf(procedure)
        )

        assertEquals("yes", result.pipe)
        assertEquals("yes", result.effects.filterIsInstance<TavernStScriptEffect.Toast>().single().text)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `plain text quick reply is a send effect while slash send remains append only`() {
        val plain = TavernStScriptEngine.execute(
            "hello {{input}}",
            TavernStScriptContext(input = "world")
        )
        val slash = TavernStScriptEngine.execute("/send hello")

        assertTrue(plain.effects.single() is TavernStScriptEffect.SendMessage)
        assertTrue((plain.effects.single() as TavernStScriptEffect.SendMessage).triggerGeneration)
        assertFalse((slash.effects.single() as TavernStScriptEffect.SendMessage).triggerGeneration)
    }

    @Test
    fun `supports safe impersonate generation effect`() {
        val result = TavernStScriptEngine.execute("/impersonate keep it concise")

        val effect = result.effects.single() as TavernStScriptEffect.Generate
        assertEquals(TavernStScriptGenerationType.IMPERSONATE, effect.type)
        assertEquals("keep it concise", effect.prompt)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `supports named text and positional variable arguments`() {
        val result = TavernStScriptEngine.execute(
            "/setvar count 1 | /incvar count | /echo text={{var::count}}"
        )

        assertEquals("2", result.localVariables["count"])
        assertEquals("2", result.effects.filterIsInstance<TavernStScriptEffect.Toast>().single().text)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `supports safe math loops and named closures`() {
        val result = TavernStScriptEngine.execute(
            "/let twice {: /mul {{pipe}} 2 :} | " +
                "/pass 1 | /times 3 {: /add {{pipe}} 1 :} | " +
                "/:twice | /echo"
        )

        assertEquals("8", result.pipe)
        assertEquals("8", result.effects.filterIsInstance<TavernStScriptEffect.Toast>().single().text)
        assertTrue(result.diagnostics.isEmpty())
    }
}
