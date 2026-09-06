package com.loyea.character.core.codec

import com.loyea.character.core.api.CharacterCardImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实角色卡（林芷柔）结构验收（Spec §2）。
 *
 * 纪律（Spec §2 / §13）：原卡只用于用户本地验收，不进入公开源码与安装包。
 * 本测试仅在本地找到真卡时运行（Assume 跳过 ≠ 通过），断言以 Spec 静态核对表为准。
 * 可用 -Dloyea.realCard=<path> 指定卡路径；默认在仓库 docs/ 下查找。
 */
class RealCardAcceptanceTest {

    private val cardName = "【NSFW】端庄中医林芷柔 - 反差 - 猎手与圣女.png"

    private fun findCard(): File? = sequenceOf(
        System.getProperty("loyea.realCard")?.let(::File),
        File("docs/$cardName"),
        File("../docs/$cardName")
    ).filterNotNull().firstOrNull { it.exists() && it.length() > 0 }

    private fun importCard() = CharacterCardImporter.importPng(findCard()!!.inputStream())

    @Test
    fun `card is discoverable for acceptance`() {
        // 真卡不可用时明确跳过而不是静默通过（Spec §13：未完成项明确标记）
        assumeTrue("本地无真卡，真卡验收标记为待执行", findCard() != null)
    }

    @Test
    fun `v3 spec declared inside chara keyword is recognized`() {
        assumeTrue(findCard() != null)
        val result = importCard()
        // C01：chara 文本块内声明 chara_card_v3，不能只认 ccv3
        assertEquals("chara_card_v3", result.document.spec)
        assertEquals("3.0", result.document.specVersion)
    }

    @Test
    fun `core fields preserved without truncation`() {
        assumeTrue(findCard() != null)
        val result = importCard()
        val profile = result.document.profile
        assertEquals("表面端庄内敛的中医-林芷柔", profile.name)
        assertTrue("description 必须完整保留（不被 shortIntro 截断替代）", profile.description.length > 50)
        assertTrue("系统指令非空", profile.systemPrompt.isNotBlank())
        assertTrue("历史后指令非空", profile.postHistoryInstructions.isNotBlank())
        assertTrue("原始卡 JSON 底稿保留", result.document.rawCardJson != null)
    }

    @Test
    fun `world book - 63 entries with 9 constant and correct positions`() {
        assumeTrue(findCard() != null)
        val result = importCard()
        val bookJson = result.document.embeddedBookJson
        assertNotNull("内嵌世界书必须存在", bookJson)
        val book = CharacterCardCodec.parseCharacterBook(bookJson!!)!!
        assertEquals("书名", "林芷柔的世界", book.name)
        assertEquals("条目总数", 63, book.entries.size)

        val constantEntries = book.entries.filter { it.constant }
        assertEquals("常驻条目数", 9, constantEntries.size)
        assertEquals(
            "常驻条目 ID",
            setOf(1, 2, 3, 4, 5, 6, 41, 42, 43),
            constantEntries.mapNotNull { it.id }.toSet()
        )
        assertTrue("常驻正文共 3,639 字符", constantEntries.sumOf { it.content.length } == 3639)

        val beforeChar = book.entries.filter { it.position == "before_char" }
        val afterChar = book.entries.filter { it.position == "after_char" }
        assertEquals("before_char 条目数", 61, beforeChar.size)
        assertEquals("after_char 条目数", 2, afterChar.size)
        assertEquals("after_char 为 ID 1、2", setOf(1, 2), afterChar.mapNotNull { it.id }.toSet())

        assertTrue("全部条目启用", book.entries.all { it.enabled })
        assertTrue("概率均为 100", book.entries.all { it.probability == null || it.probability == 100 })
        assertTrue("无非空互斥组", book.entries.all { it.group.isNullOrBlank() })
        assertTrue("secondary keys 为空", book.entries.all { it.secondaryKeys.isEmpty() })
    }

    @Test
    fun `greetings macros html and scripts`() {
        assumeTrue(findCard() != null)
        val result = importCard()
        val profile = result.document.profile
        assertEquals("1 个默认开场白", true, profile.firstMessage.isNotBlank())
        assertEquals("5 个备用开场白", 5, profile.alternateGreetings.size)

        val allContent = profile.description + profile.systemPrompt + profile.postHistoryInstructions +
            result.document.embeddedBookJson.orEmpty()
        assertTrue("宏 {{user}}", allContent.contains("{{user}}"))
        assertTrue("宏 {{char}}", allContent.contains("{{char}}"))

        val greetingsText = (listOf(profile.firstMessage) + profile.alternateGreetings).joinToString("\n")
        assertTrue("开场白含 HTML details/summary", greetingsText.contains("<details") && greetingsText.contains("<summary"))
        assertTrue("开场白含 strong/span 内联样式", greetingsText.contains("<strong") || greetingsText.contains("<span"))

        // 无脚本 / 无独立正则脚本
        val capabilities = result.document.capabilities
        assertTrue(
            "world book 参与运行",
            capabilities.any { it.field == "character_book" && it.kind == "active" }
        )
        assertFalse(
            "未发现 regex_scripts",
            capabilities.any { it.field == "extensions.regex_scripts" }
        )
        val raw = result.document.rawCardJson.orEmpty()
        assertFalse("未发现 tavern_helper", raw.contains("tavern_helper"))
        assertFalse("未发现 setvar/getvar", raw.contains("\"setvar\"") || raw.contains("\"getvar\""))
    }

    @Test
    fun `export roundtrip preserves all 63 entries`() {
        assumeTrue(findCard() != null)
        val result = importCard()
        val card = CharacterCardCodec.parseJson(result.document.rawCardJson!!)!!
        val exported = CharacterCardCodec.toJson(card)
        val reimported = CharacterCardCodec.parseJson(exported)!!
        assertEquals("往返后条目数不变", 63, reimported.data.characterBook?.entries?.size)
        assertEquals("往返后常驻条目数不变", 9, reimported.data.characterBook?.entries?.count { it.constant })
        assertEquals("往返后名称不变", card.data.name, reimported.data.name)
    }
}
