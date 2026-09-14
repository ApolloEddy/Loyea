package com.loyea.plugin.reader

/**
 * 小说屏显文本提纯器（Reader Spec §5；纯 Kotlin，无 Android 依赖）。
 * 顺序固定：行合并 → 噪声剔除 → 对话标记保留 → 标点规整 → 长度闸。
 * 所有规则确定性、可单测；不改正文用字，只删噪声与规整标点。
 */
object ReaderTextPurifier {

    /** 广告/诱导行模式（命中即整行剔除）。 */
    private val AD_PATTERNS = listOf(
        Regex("点击.{0,6}(领取|查看|下载)"),
        Regex("(下载|打开).{0,4}(App|APP|客户端)"),
        Regex("(加入书架|立即阅读|本章未完|点击下一页|继续阅读)"),
        Regex("^[\\s　]*[-—~·]\\s*\\d{1,3}%\\s*[-—~·]?[\\s　]*$"), // 进度条行
        Regex("^[\\s　]*(首页|书页|目录|设置|夜间|日间)[\\s　]*$"),
    )

    /** 章节标题启发式（用于章节键与切章检测）。 */
    private val CHAPTER_TITLE = Regex(
        "^\\s*(第[0-9零一二三四五六七八九十百千万两]+[章回节卷]|Chapter\\s+\\d+|[（(]?\\d+[）)]?)(?:[\\s　、.．:：][^\\n]{0,40})?$"
    )

    /** 提纯入口：输入原始文本块（节点文本），输出净化后的段落序列。 */
    fun purify(rawBlocks: List<String>): List<String> {
        val merged = mergeLines(rawBlocks)
        val denoised = merged.map { stripNoise(it) }.filter { it.isNotBlank() }
        val folded = denoised.map { foldPunctuation(it) }
        return folded.filter { it.length in 2..8000 }
    }

    /** 合并硬换行：CJK 行尾无标点时直接拼接；保留段落边界（空行）。 */
    fun mergeLines(blocks: List<String>): List<String> {
        val out = mutableListOf<String>()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotBlank()) out += pending.toString().trim()
            pending.clear()
        }
        for (block in blocks) {
            for (rawLine in block.split('\n')) {
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    flush()
                    continue
                }
                pending.append(line)
                val last = line.last()
                val endsSentence = last in "。！？…”』」\")" || last == '!' || last == '?'
                if (endsSentence) flush()
            }
            flush()
        }
        return out
    }

    /** 剔除页眉页脚/广告/进度行：整行命中广告模式，或与已见行重复率 >80% 的短行。 */
    fun stripNoise(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (AD_PATTERNS.any { it.containsMatchIn(t) }) return ""
        if (t.length <= 24 && isRepeatedBoilerplate(t)) return ""
        return t
    }

    /** 页眉页脚判定：书中高频出现的同一短行（按去重空白比较）。 */
    private val seenShortLines = HashMap<String, Int>()

    private fun isRepeatedBoilerplate(line: String): Boolean {
        val key = line.replace(Regex("\\s+"), "")
        val n = (seenShortLines[key] ?: 0) + 1
        seenShortLines[key] = n
        return n >= 3 // 同一短行出现 ≥4 次视作页眉/页脚（章节内正常文本几乎不重复）
    }

    /** 标点规整：折叠重复（！！→！、？？→？、。。。→…），统一省略号。 */
    fun foldPunctuation(text: String): String = text
        .replace(Regex("！{2,}"), "！")
        .replace(Regex("？{2,}"), "？")
        .replace(Regex("。{3,}"), "……")
        .replace(Regex("…{2,}"), "……")
        .replace(Regex("。{2,}"), "。")
        .replace(Regex("([，、；：])\\1+"), "$1")

    /** 章节标题检测（当前行为章节标题时返回清洗后的标题）。 */
    fun chapterTitleOf(line: String): String? {
        val t = line.trim()
        return if (CHAPTER_TITLE.containsMatchIn(t)) t else null
    }

    /** 长度闸：低于最小长度视为封面/广告页，应整体丢弃。 */
    fun isTrivialSample(paragraphs: List<String>): Boolean =
        paragraphs.sumOf { it.length } < 200
}

/** 书章缓冲（Reader Spec §6）：内存 LRU ≤2 章；防剧透游标物理截取上下文。 */
class ReaderChapterBuffer(private val maxChapters: Int = 2) {

    data class Chapter(val key: String, val blocks: MutableList<String>)

    private val chapters = ArrayDeque<Chapter>()
    var currentKey: String = ""
        private set
    /** 可视游标：当前章节内“用户已看到”的最后一段下标（防剧透屏障）。 */
    var visibleCursor: Int = -1
        private set

    val currentBlocks: List<String> get() = chapters.lastOrNull()?.blocks ?: emptyList()

    /** 追加/替换段落；返回是否触发了切章（调用方需重置游标与摘要）。 */
    fun ingest(paragraphs: List<String>, chapterKey: String?): Boolean {
        var switched = false
        val key = chapterKey ?: currentKey.ifEmpty { "untitled" }
        if (chapters.isEmpty() || key != currentKey) {
            switched = currentKey.isNotEmpty() || chapters.isNotEmpty()
            chapters.addLast(Chapter(key, mutableListOf()))
            while (chapters.size > maxChapters) chapters.removeFirst()
            currentKey = key
            visibleCursor = -1
        }
        val cur = chapters.last()
        for (p in paragraphs) if (p !in cur.blocks) cur.blocks.add(p)
        return switched
    }

    /** 由可视区命中的段落下标推进游标（只进不退）。 */
    fun markVisible(index: Int) {
        if (index in 0 until (chapters.lastOrNull()?.blocks?.size ?: 0)) {
            visibleCursor = maxOf(visibleCursor, index)
        }
    }

    /** 防剧透上下文：只暴露 [0..visibleCursor] 的段落（物理截除未来内容）。 */
    fun visibleContext(): List<String> =
        chapters.lastOrNull()?.blocks?.take((visibleCursor + 1).coerceAtLeast(0)) ?: emptyList()

    fun clear() {
        chapters.clear()
        currentKey = ""
        visibleCursor = -1
    }
}

/**
 * 会话级提纯器：持有跨采样的页眉/页脚统计（同一短行 ≥3 次视为噪音，
 * 且清除该短行的全部出现——两遍式，避免前两次残留在正文里）。
 * 每次伴读会话新建一个实例；切书时调用 reset()。
 */
class ReaderPurifierSession {

    private val seenShortLines = HashMap<String, Int>()

    fun purify(rawBlocks: List<String>): List<String> {
        val merged = ReaderTextPurifier.mergeLines(rawBlocks)
        // 第一遍：统计短行频次（页眉/页脚识别）
        val boilerplate = HashSet<String>()
        for (block in merged) {
            val t = block.trim()
            if (t.isEmpty() || t.length > 24) continue
            if (ReaderTextPurifier.stripNoise(t).isEmpty()) continue
            val key = t.replace(Regex("\\s+"), "")
            val n = (seenShortLines[key] ?: 0) + 1
            seenShortLines[key] = n
            if (n >= 3) boilerplate.add(key)
        }
        // 第二遍：清洗
        val out = mutableListOf<String>()
        for (block in merged) {
            val t = block.trim()
            if (t.isEmpty()) continue
            if (ReaderTextPurifier.stripNoise(t).isEmpty()) continue
            if (t.length <= 24 && t.replace(Regex("\\s+"), "") in boilerplate) continue
            out += ReaderTextPurifier.foldPunctuation(t)
        }
        return out.filter { it.length in 2..8000 }
    }

    fun reset() = seenShortLines.clear()
}
