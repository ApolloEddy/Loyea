package com.loyea.plugin.companion.perception

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.Normalizer

/**
 * 旧 April 感知模型的 WordPiece 分词器移植（Spec 接入文档 §3.2）。
 *
 * 移植自 AprilPerceptionDemo-standalone-2.1.4.html 内联脚本（参考副本
 * docs/audits/perception_reference/Qo_pretokenize.js、mt_tokenizer.js）：
 * 拼接串 → [unused1]/[unused2] 保护 → NFC/控制符清理 → CJK 两侧加空格 →
 * 空白切分 → 标点单字切分 → vocab 直查 / 贪心最长匹配（## 续）→ [UNK]。
 * encode：tokens 截到 maxLength-2（右截断），[CLS]+tokens+[SEP] 右 pad 到 96。
 */
class LegacyTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val maxLength: Int,
) {
    private val unknownId = vocab.getValue("[UNK]")
    private val clsId = vocab.getValue("[CLS]")
    private val sepId = vocab.getValue("[SEP]")
    private val padId = vocab.getValue("[PAD]")

    class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        /** 实际保留的 WordPiece token 词面（不含特殊符号与 pad）。 */
        val tokens: List<String>,
        /** 原始分词是否被右截断（截断前 token 数 > maxLength-2）。 */
        val truncated: Boolean,
    )

    /** 完整分词（与原实现 mt.tokenize 一致：预切分 + WordPiece 展开）。 */
    fun tokenize(text: String): List<String> {
        val pretokens = pretokenize(text)
        val out = mutableListOf<String>()
        for (token in pretokens) out += wordpiece(token)
        return out
    }

    fun encode(text: String): Encoded {
        val all = tokenize(text)
        val contentCap = (maxLength - 2).coerceAtLeast(0)
        val truncated = all.size > contentCap
        val kept = all.take(contentCap)
        val ids = mutableListOf<Long>()
        val mask = mutableListOf<Long>()
        ids.add(clsId.toLong()); mask.add(1L)
        for (token in kept) {
            ids.add((vocab[token] ?: unknownId).toLong())
            mask.add(1L)
        }
        ids.add(sepId.toLong()); mask.add(1L)
        while (ids.size < maxLength) {
            ids.add(padId.toLong())
            mask.add(0L)
        }
        return Encoded(ids.take(maxLength).toLongArray(), mask.take(maxLength).toLongArray(), kept, truncated)
    }

    // ------------------------------------------------------------------

    private fun pretokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        for (segment in splitProtected(text)) {
            if (segment.isEmpty()) continue
            if (segment == PROTECTED_1 || segment == PROTECTED_2) {
                out += segment
                continue
            }
            val spaced = StringBuilder()
            for (cp in codePoints(normalize(segment))) {
                spaced.append(if (isCjk(cp)) " " + toChar(cp) + " " else toChar(cp))
            }
            for (word in spaced.toString().trim().split(WHITESPACE_REGEX)) {
                if (word.isEmpty()) continue
                splitPunctuation(word, out)
            }
        }
        return out
    }

    private fun normalize(text: String): String {
        // clean_text=true：NFC；剔除 \0/U+FFFD 与控制符（Cc/Cf，\t\n\r 保留为空格）；
        // lowercase=false、stripAccents=null（模型固定配置，不实现降级分支）。
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD) continue
            if (cp != '\t'.code && cp != '\n'.code && cp != '\r'.code &&
                isCategory(cp, Character.CONTROL.toInt(), Character.FORMAT.toInt())
            ) continue
            if (isJsWhitespace(cp)) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun splitPunctuation(word: String, out: MutableList<String>) {
        var pending = StringBuilder()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (isPunctuation(cp)) {
                if (pending.isNotEmpty()) {
                    out += pending.toString()
                    pending = StringBuilder()
                }
                out += toChar(cp)
            } else {
                pending.appendCodePoint(cp)
            }
        }
        if (pending.isNotEmpty()) out += pending.toString()
    }

    private fun wordpiece(token: String): List<String> {
        if (vocab.containsKey(token)) return listOf(token)
        val cps = token.codePoints().toArray()
        if (cps.size > 100) return listOf(UNK)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var match: String? = null
            while (start < end) {
                val candidate = (if (start > 0) "##" else "") + stringFrom(cps, start, end - start)
                if (vocab.containsKey(candidate)) {
                    match = candidate
                    break
                }
                end -= 1
            }
            match?.let { pieces += it } ?: return listOf(UNK)
            start = end
        }
        return pieces
    }

    private fun stringFrom(codePoints: IntArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length * 2)
        for (i in offset until offset + length) sb.appendCodePoint(codePoints[i])
        return sb.toString()
    }

    companion object {
        private const val PROTECTED_1 = "[unused1]"
        private const val PROTECTED_2 = "[unused2]"
        private const val UNK = "[UNK]"

        /** 与 JS 一致的空白集合（\s；\uFEFF 归 Cf，在 clean_text 阶段已被剔除）。 */
        private val JS_WHITESPACE = setOf(
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
            0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
        )

        private val WHITESPACE_REGEX = Regex("\\s+")

        /** ji 拼接（原实现）：最近至多三条 context + 当前条，空格连接。 */
        fun joinInput(text: String, speaker: String = "speaker_0", context: List<Pair<String, String>>): String {
            val parts = context.takeLast(3).map { (sp, tx) -> "$PROTECTED_1$sp:$tx" } +
                "$PROTECTED_2$speaker:$text"
            return parts.joinToString(" ")
        }

        private fun splitProtected(text: String): List<String> {
            val parts = mutableListOf<String>()
            var rest = text
            while (true) {
                val idx1 = rest.indexOf(PROTECTED_1)
                val idx2 = rest.indexOf(PROTECTED_2)
                val idx = when {
                    idx1 < 0 && idx2 < 0 -> -1
                    idx2 < 0 || (idx1 >= 0 && idx1 < idx2) -> idx1
                    else -> idx2
                }
                if (idx < 0) {
                    parts += rest
                    break
                }
                val marker = if (idx == idx1) PROTECTED_1 else PROTECTED_2
                parts += rest.substring(0, idx)
                parts += marker
                rest = rest.substring(idx + marker.length)
            }
            return parts
        }

        private fun isJsWhitespace(cp: Int): Boolean = cp in JS_WHITESPACE

        private fun isCjk(cp: Int): Boolean =
            cp in 13312..19903 || cp in 19968..40959 || cp in 131072..195103 || cp in 63744..64255

        private fun isCategory(cp: Int, vararg categories: Int): Boolean {
            val type = Character.getType(cp)
            return categories.any { it == type }
        }

        /** JS \p{P}（全部 Unicode 标点子类）。 */
        private fun isPunctuation(cp: Int): Boolean = isCategory(
            cp,
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )

        private fun toChar(cp: Int): String = String(Character.toChars(cp))

        private fun codePoints(text: String): IntArray = text.codePoints().toArray()

        /** 从 tokenizer.json 载入（app assets 中的验证副本）。 */
        fun fromJson(tokenizerJson: String, maxLength: Int = 96): LegacyTokenizer {
            val root: JsonObject = JsonParser.parseString(tokenizerJson).asJsonObject
            val vocabJson = root.getAsJsonObject("model").getAsJsonObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.size() * 2)
            for ((k, v) in vocabJson.entrySet()) vocab[k] = v.asInt
            require(
                vocab.containsKey("[UNK]") && vocab.containsKey("[CLS]") &&
                    vocab.containsKey("[SEP]") && vocab.containsKey("[PAD]")
            ) {
                "tokenizer vocab missing special tokens"
            }
            return LegacyTokenizer(vocab, maxLength)
        }
    }
}
