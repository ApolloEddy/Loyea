package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.TavernCardSource
import com.loyea.plugins.tavern.core.TavernCardUrlResolver
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TavernCardDownloader 的单测。
 *
 * 全部测试均注入自定义 [fetchBytes]，**不触碰真实网络**；只覆盖纯逻辑与分支映射。
 */
class TavernCardDownloaderTest {

    private fun chubSource(apiUrl: String = "https://www.chub.ai/api/characters/testsourceid") =
        TavernCardSource(host = "www.chub.ai", sourceId = "testsourceid", apiUrl = apiUrl, displayName = "test")

    // ---------- 提取函数 fixture ----------

    @Test
    fun `extractJson when character is object returns its json text`() {
        val json = """{"character":{"name":"Alice","description":"hello"},"meta":1}"""
        val extracted = TavernCardDownloader.extractCharacterJson(json)
        assertTrue(extracted != null)
        assertTrue(extracted!!.contains("\"name\":\"Alice\""))
        assertTrue(extracted.contains("\"description\":\"hello\""))
    }

    @Test
    fun `extractJson when character is json string returns the inner json`() {
        val inner = """{"name":"Bob","description":"hi"}"""
        // 注意转义：JSON 字符串值内部的引号必须用 \" 转义，否则整个 JSON 非法。
        val json = """{"character":"{\"name\":\"Bob\",\"description\":\"hi\"}"}"""
        val extracted = TavernCardDownloader.extractCharacterJson(json)
        assertEquals(inner, extracted)
    }

    @Test
    fun `extractJson when character is base64 string decodes and returns json`() {
        val inner = """{"name":"Cara"}"""
        val b64 = Base64.getEncoder().encodeToString(inner.toByteArray(StandardCharsets.UTF_8))
        val json = """{"character":"$b64"}"""
        val extracted = TavernCardDownloader.extractCharacterJson(json)
        assertEquals(inner, extracted)
    }

    @Test
    fun `extractJson when character is missing returns null`() {
        val json = """{"foo":1,"bar":2}"""
        assertNull(TavernCardDownloader.extractCharacterJson(json))
    }

    @Test
    fun `extractJson when character is a plain non-json non-base64 string returns null`() {
        val json = """{"character":"just-plain-text"}"""
        assertNull(TavernCardDownloader.extractCharacterJson(json))
    }

    @Test
    fun `extractJson when input is malformed returns null`() {
        assertNull(TavernCardDownloader.extractCharacterJson("{not valid json"))
        assertNull(TavernCardDownloader.extractCharacterJson(""))
    }

    @Test
    fun `download success extracts json and description from character object`() {
        val body = """{"character":{"name":"Alice","description":"x"}}"""
        val result = TavernCardDownloader.downloadCard(chubSource()) { body.toByteArray() }
        assertTrue(result is TavernCardDownloader.TavernDownloadResult.Success)
        val success = result as TavernCardDownloader.TavernDownloadResult.Success
        assertEquals(body.toByteArray().toList(), success.rawBytes.toList())
        assertTrue(success.cardJson!!.contains("\"name\":\"Alice\""))
        assertEquals("Alice", success.description)
    }

    // ---------- 1MB 超限 ----------

    @Test
    fun `download fails as too_large when bytes exceed limit`() {
        val big = ByteArray(TavernCardDownloader.MAX_DOWNLOAD_BYTES + 1)
        val result = TavernCardDownloader.downloadCard(chubSource()) { big }
        assertEquals(
            TavernCardDownloader.TavernDownloadResult.Failure(TavernCardDownloader.TavernDownloadFailure.TOO_LARGE),
            result
        )
    }

    @Test
    fun `download succeeds when bytes equal the limit`() {
        val bytes = ByteArray(TavernCardDownloader.MAX_DOWNLOAD_BYTES)
        val result = TavernCardDownloader.downloadCard(chubSource()) { bytes }
        assertTrue(result is TavernCardDownloader.TavernDownloadResult.Success)
        assertEquals(bytes.toList(), (result as TavernCardDownloader.TavernDownloadResult.Success).rawBytes.toList())
    }

    // ---------- fetchBytes 抛异常 → 网络失败 ----------

    @Test
    fun `download maps fetchBytes thrown exception to network failure`() {
        val result = TavernCardDownloader.downloadCard(chubSource()) { url ->
            throw IOException("boom for $url")
        }
        assertEquals(
            TavernCardDownloader.TavernDownloadResult.Failure(TavernCardDownloader.TavernDownloadFailure.NETWORK),
            result
        )
    }

    // ---------- 空 URL ----------

    @Test
    fun `resolve empty url returns null`() {
        assertNull(TavernCardUrlResolver.resolve(""))
        assertNull(TavernCardUrlResolver.resolve("   "))
    }

    @Test
    fun `download with empty apiUrl fails as invalid_url`() {
        val result = TavernCardDownloader.downloadCard(chubSource(apiUrl = "")) { ByteArray(0) }
        assertEquals(
            TavernCardDownloader.TavernDownloadResult.Failure(TavernCardDownloader.TavernDownloadFailure.INVALID_URL),
            result
        )
    }

    @Test
    fun `downloadFromUrl with empty url fails as invalid_url`() {
        val result = TavernCardDownloader.downloadFromUrl("") { ByteArray(0) }
        assertEquals(
            TavernCardDownloader.TavernDownloadResult.Failure(TavernCardDownloader.TavernDownloadFailure.INVALID_URL),
            result
        )
    }
}