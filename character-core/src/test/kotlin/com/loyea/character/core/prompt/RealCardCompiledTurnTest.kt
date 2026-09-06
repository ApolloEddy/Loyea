package com.loyea.character.core.prompt

import com.loyea.character.core.api.CharacterCardImporter
import com.loyea.character.core.codec.CardBookAdapter
import com.loyea.character.core.codec.CharacterCardCodec
import com.loyea.character.core.api.PromptBlock
import com.loyea.character.core.api.PromptBlockCategory
import com.loyea.character.core.api.TurnInput
import com.loyea.character.core.worldinfo.WorldInfoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实角色卡（林芷柔）的编译快照验收（Spec §2 / W08：常驻、条件、位置可解释）。
 * 仅在本地存在真卡时运行；原卡不进入公开源码（Spec §2）。
 */
class RealCardCompiledTurnTest {

    private val cardName = "【NSFW】端庄中医林芷柔 - 反差 - 猎手与圣女.png"

    private fun findCard(): File? = sequenceOf(
        System.getProperty("loyea.realCard")?.let(::File),
        File("docs/$cardName"),
        File("../docs/$cardName")
    ).filterNotNull().firstOrNull { it.exists() && it.length() > 0 }

    private fun compileRealCard(history: List<String>) : CompiledResult {
        val importResult = CharacterCardImporter.importPng(findCard()!!.inputStream())
        val document = importResult.document
        val book = CharacterCardCodec.parseCharacterBook(document.embeddedBookJson!!)!!
        val adapted = CardBookAdapter.toWorldInfoBook(book, "charbook:${document.profile.id}")
        val input = TurnInput(
            requestId = "req-real",
            sessionId = "sess-real",
            bindingRevision = 1L,
            characterRevision = document.profile.revision,
            generationKind = "normal",
            historyContents = history,
            userName = "林先生",
            worldInfoEntries = adapted.entries,
            worldInfoConfig = adapted.config,
            randomSeed = 123456L
        )
        val prepared = CharacterCompiler.prepare(
            profile = document.profile,
            input = input,
            hostBlocks = listOf(
                PromptBlock("host.0", PromptBlockCategory.HOST, "[HOST]", PromptBlock.SLOT_HOST)
            )
        )
        return CompiledResult(document, adapted, prepared)
    }

    private data class CompiledResult(
        val document: com.loyea.character.core.api.CharacterDocument,
        val book: com.loyea.character.core.worldinfo.WorldInfoBook,
        val prepared: CharacterCompiler.PreparedCharacterTurn
    )

    @Test
    fun `real card compiles with explainable constant and conditional placement`() {
        assumeTrue("本地无真卡", findCard() != null)
        val (document, book, prepared) = compileRealCard(
            history = listOf("user", "我走进诊所，看到{{user}}常去的那间诊室。")
        )

        // 63 条保存完整
        assertEquals(63, book.entries.size)

        // 无常驻溢出：默认预算 2048 内 9 条常驻（3,639 字符）应可全部入选
        assertTrue("常驻不应溢出（默认预算）", !prepared.constantOverflow)

        // 9 条常驻全部入选且位置可解释：ID 1、2 after_char，其余 before_char
        val constantIds = prepared.selectedWorldInfo
            .filter { it.constant }
            .map { it.id.substringAfterLast(":").toIntOrNull() }
            .toSet()
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 41, 42, 43), constantIds)

        val beforeBlock = prepared.blocks.first { it.sourceId == "world.before_char" }.text
        val afterBlock = prepared.blocks.first { it.sourceId == "world.after_char" }.text
        // ID 3（before_char 常驻）与 ID 1（after_char 常驻）各自出现在正确一侧
        val id3Content = book.entries.first { it.id.substringAfterLast(":") == "3" }.content.trim().take(30)
        val id1Content = book.entries.first { it.id.substringAfterLast(":") == "1" }.content.trim().take(30)
        assertTrue("ID 3 应在 before_char", beforeBlock.contains(id3Content))
        assertTrue("ID 1 应在 after_char", afterBlock.contains(id1Content))
        assertFalse("before_char 不得包含 after_char 条目", beforeBlock.contains(id1Content))

        // 条件条目：含「诊所」相关词的条目可入选；trace 区分未匹配
        assertTrue(prepared.worldInfoTrace.selectedIds.isNotEmpty())
        // 常驻约占满预算后，条件条目按预算整条取舍——排除必须有可解释原因
        assertTrue(
            prepared.worldInfoTrace.budgetExcluded.all {
                it.reason == com.loyea.character.core.worldinfo.WorldInfoMatcher.BudgetExclusion.REASON_BUDGET_EXHAUSTED
            }
        )

        // 宏展开后无残留
        val system = prepared.systemText()
        assertTrue(!system.contains("{{user}}") && !system.contains("{{char}}"))

        // 历史后指令独立成块
        assertTrue(prepared.postHistoryBlock!!.text.contains("历史后指令"))
    }

    @Test
    fun `real card low budget triggers recoverable overflow signal`() {
        assumeTrue("本地无真卡", findCard() != null)
        val (document, book, _) = compileRealCard(history = listOf("user", "你好"))
        // 预算压缩到只够极少量内容 → 常驻必然溢出
        val input = TurnInput(
            requestId = "req-low",
            sessionId = "sess-real",
            bindingRevision = 1L,
            characterRevision = document.profile.revision,
            generationKind = "normal",
            historyContents = listOf("user", "你好"),
            userName = "林先生",
            worldInfoEntries = book.entries,
            worldInfoConfig = book.config.copy(tokenBudget = 32L),
            randomSeed = 1L
        )
        val prepared = CharacterCompiler.prepare(profile = document.profile, input = input)
        assertTrue("预算不足必须给出可恢复错误信号", prepared.constantOverflow)
    }
}
