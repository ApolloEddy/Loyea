package com.loyea.plugin.reader

/**
 * 滚动摘要（Reader Spec §6, 抽取式, 无 LLM 依赖）。
 *
 * - 累计新增文本超过 [ingestThresholdChars] 才更新一次（节流）。
 * - 关键句抽取：未处理文本每 ~[blockChars] 字取首句。
 * - 总量上限 [maxChars]：超出时从最旧的摘要句开始丢弃（滑动窗口语义，
 *   "摘要只含已读部分"恒成立；窗口只影响很早的情节细节）。
 */
class RollingSummary(
    private val ingestThresholdChars: Int = 1500,
    private val maxChars: Int = 600,
    private val blockChars: Int = 500,
) {
    private val keySentences = ArrayDeque<String>()
    private val pending = mutableListOf<String>()
    private var totalIngested = 0

    /** 喂入已读段落（调用方保证只喂防剧透截取后的内容）。 */
    fun ingest(blocks: List<String>) {
        println("RSDIAG ingest blocks=" + blocks + " threshold=" + ingestThresholdChars)
        for (block in blocks) {
            pending.add(block)
            totalIngested += block.length
        }
        if (pending.sumOf { it.length } >= ingestThresholdChars) update()
        println("RSDIAG after pending=" + pending + " sentences=" + keySentences)
    }

    private fun update() {
        println("RS update pending=" + pending.size)
        // 每个未处理段落取首句；超长段落再按 blockChars 切块补句
        for (block in pending) {
            var remaining = block.trim()
            while (remaining.isNotEmpty()) {
                keySentences.addLast(firstSentence(remaining))
                val consumed = firstSentence(remaining).length
                if (consumed <= 0) break
                remaining = remaining.drop(consumed).trim()
                if (remaining.length <= blockChars) {
                    if (remaining.isNotEmpty()) keySentences.addLast(firstSentence(remaining))
                    remaining = ""
                }
            }
        }
        pending.clear()
        println("RS after-loop sentences=" + keySentences.size + " content=<" + summaryTextViaSentences() + ">")
        trimToBudget()
        println("RS after-trim strength=<" + summaryText() + "> total=" + keySentences.size)
    }

    private fun summaryTextViaSentences(): String = keySentences.joinToString("")

    /** 从片段取第一句（到句末标点为止；无标点取整段）。 */
    private fun firstSentence(chunk: String): String {
        val t = chunk.trim()
        if (t.isEmpty()) return ""
        for (i in t.indices) {
            when (t[i]) {
                '。', '！', '？', '…' -> return t.substring(0, i + 1)
            }
        }
        return t
    }

    private fun trimToBudget() {
        var total = keySentences.sumOf { it.length + 1 }
        while (total > maxChars && keySentences.isNotEmpty()) {
            total -= keySentences.removeFirst().length + 1
        }
    }

    fun summaryText(): String = keySentences.joinToString("")

    fun reset() {
        keySentences.clear()
        pending.clear()
        totalIngested = 0
    }

    companion object {
        /** 便捷入口：直接从段落列表生成摘要文本（无状态，冷启动/测试用）。 */
        fun summarize(blocks: List<String>, maxChars: Int = 600): String {
            val rs = RollingSummary(ingestThresholdChars = 0, maxChars = maxChars)
            rs.ingest(blocks)
            return rs.summaryText()
        }
    }
}

/**
 * 实体卡（Reader Spec §6）：人物名 → 首次出场句。
 *
 * 抽取规则（确定性）：中文 2–3 字名 + 后跟言说/动作动词触发
 * （道/说/问/喊/叫/答/笑/叹/想/低声/回头/点头/摇头）。只记录首次出现的
 * 句子（≤60 字），天然不含未读内容。上限 [maxEntities] 个。
 */
class EntityCards(private val maxEntities: Int = 12) {

    data class Card(val name: String, val firstSeen: String)

    private val cards = LinkedHashMap<String, Card>()

    // 名字必须紧邻言语动词（说道/问：/笑道...），且名字前不是中文字
    //（避免截取长句中段）；Kotlin 正则的 \uXXXX 需双反斜杠转义。
    private val nameTrigger = Regex(
        "(?<![\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5]{2,4})(?:说道|问道|笑道|喊道|答道|叹道|低声道|说：|说，|问：|答：)"
    )

    /** 从已读段落抽取/补充实体卡。 */
    fun ingest(blocks: List<String>) {
        if (cards.size >= maxEntities) return
        for (block in blocks) {
            if (cards.size >= maxEntities) return
            for (match in nameTrigger.findAll(block)) {
                val name = match.groupValues[1]
                if (name.first() in NON_NAME_PREFIX) continue
                if (name in cards) continue
                cards[name] = Card(name, firstSeen = shorten(block))
                if (cards.size >= maxEntities) return
            }
        }
    }

    private fun shorten(block: String, max: Int = 60): String =
        if (block.length <= max) block else block.take(max) + "…"

    fun cardsList(): List<Card> = cards.values.toList()

    fun names(): Set<String> = cards.keys

    fun reset() = cards.clear()

    companion object {
        private const val NON_NAME_PREFIX = "这那它她他你们我谁又就也都被把将是很不没别还再刚正就"
    }
}
