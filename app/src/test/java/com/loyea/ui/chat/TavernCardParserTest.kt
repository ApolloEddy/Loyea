package com.loyea.ui.chat

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class TavernCardParserTest {

    @Test
    fun testParseLyaPng() {
        var file = File("docs/Lya.png")
        if (!file.exists()) {
            file = File("../docs/Lya.png")
        }
        assertTrue("Lya.png 应该存在于 docs 目录下", file.exists())

        val card = TavernCardParser.parsePngCard(FileInputStream(file))
        println("Lya Card: $card")
        assertNotNull("解析 Lya.png 返回的 CharacterCard 不应为 null", card)
        
        card?.let {
            assertEquals("Lya", it.name)
            // Lya 的描述较长，这里应该提取 description 的前20个字并拼接 "..."
            assertTrue("描述应该被提取", it.shortIntro.isNotEmpty())
            assertTrue("描述应该以 {{char}} 开始", it.shortIntro.startsWith("{{char}}"))
            
            // 系统 Prompt 应该成功读取
            assertTrue("核心设定 Prompt 应该包含 Eddy", it.systemPrompt.contains("Eddy"))
            assertNotEquals("You are a friendly companion.", it.systemPrompt)
        }
    }

    @Test
    fun testParseAnahelPng() {
        var file = File("docs/Anahel.png")
        if (!file.exists()) {
            file = File("../docs/Anahel.png")
        }
        assertTrue("Anahel.png 应该存在于 docs 目录下", file.exists())

        val card = TavernCardParser.parsePngCard(FileInputStream(file))
        println("Anahel Card: $card")
        assertNotNull("解析 Anahel.png 返回 of CharacterCard 不应为 null", card)
        
        card?.let {
            assertEquals("Anahel", it.name)
            assertTrue("personality 应该包含 angel", it.personality.contains("angel"))
        }
    }
}
