package com.loyea.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * HTML 子集的原生渲染数据模型（Spec §7.1）。
 * 只支持卡内实际使用的标签：details/summary、div/p、strong/em、span 与内联样式；
 * CSS grid 降级为纵向流式布局；无 WebView、无脚本、无网络资源。
 */
sealed class PanelBlock {
    data class Paragraph(val spans: List<PanelSpan>) : PanelBlock()
    data class Details(
        val summarySpans: List<PanelSpan>,
        val children: List<PanelBlock>,
        val initiallyOpen: Boolean
    ) : PanelBlock()
}

data class PanelSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 卡内指定的强调色（如 #FFD700），null 用主题色 */
    val colorHex: String? = null
)

/** 一个解析完成的 details 面板：summary 为折叠标题，children 为展开内容。 */
data class ParsedPanel(
    val summarySpans: List<PanelSpan>,
    val children: List<PanelBlock>,
    val initiallyOpen: Boolean = false
)

/** 消息正文的展示分段：Markdown 文本与 HTML 面板交替。 */
sealed class ContentSegment {
    data class Markdown(val text: String) : ContentSegment()
    data class Panel(val parsed: ParsedPanel?) : ContentSegment()
}

/**
 * 三类文本分离的展示端（Spec §7.2）：
 * rawContent 始终保持模型原文（复制/编辑/重生成依据）；本对象只派生展示内容，
 * 结果可随时丢弃重建。TTS 朗读用 [narrativeText]——排除面板与标签，只读叙事/对白。
 */
object HtmlDisplaySplitter {

    private val DETAILS_BLOCK = Regex("<details[\\s\\S]*?</details\\s*>", RegexOption.IGNORE_CASE)
    private val UNCLOSED_DETAILS = Regex("<details[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("```[\\s\\S]*?(?:```|$)")

    /** raw → 展示分段：围栏代码里的 details 不是面板；未闭合 details 展示稳定占位。 */
    fun split(raw: String): List<ContentSegment> {
        if (!raw.contains("<", ignoreCase = false) ||
            (!raw.contains("<details", ignoreCase = true) && !raw.contains("<div", ignoreCase = true) &&
                !raw.contains("<p>", ignoreCase = true) && !raw.contains("<span", ignoreCase = true))
        ) {
            return listOf(ContentSegment.Markdown(raw))
        }

        val segments = ArrayList<ContentSegment>()
        // 先按代码围栏切块，围栏外的部分才做面板切分
        var cursor = 0
        for (match in FENCE.findAll(raw)) {
            splitHtmlOutsideFence(raw.substring(cursor, match.range.first), segments)
            segments.add(ContentSegment.Markdown(raw.substring(match.range)))
            cursor = match.range.last + 1
        }
        splitHtmlOutsideFence(raw.substring(cursor), segments)
        return segments.filterNot {
            (it as? ContentSegment.Markdown)?.text?.isBlank() == true && it !is ContentSegment.Panel
        }
    }

    /** TTS 叙事正文：剔除 details 面板与其 summary，保留其余文本（标签由既有清洗链移除）。 */
    fun narrativeText(raw: String): String {
        var text = DETAILS_BLOCK.replace(raw, "")
        // 未闭合面板（流式中途）也剔除，避免朗读半截标签
        text = UNCLOSED_DETAILS.replace(text, "")
        return text
    }

    private fun splitHtmlOutsideFence(part: String, out: MutableList<ContentSegment>) {
        if (part.isBlank()) return
        var last = 0
        DETAILS_BLOCK.findAll(part).forEach { match ->
            if (match.range.first > last) {
                out.add(ContentSegment.Markdown(part.substring(last, match.range.first)))
            }
            out.add(ContentSegment.Panel(parsePanel(match.value)))
            last = match.range.last + 1
        }
        val tail = part.substring(last)
        if (tail.isNotBlank()) {
            if (UNCLOSED_DETAILS.containsMatchIn(tail)) {
                // 流式未完成：展示已完成正文 + 稳定占位，完成后整体解析（Spec §7.1）
                val idx = tail.indexOf("<details", 0, ignoreCase = true)
                if (idx > 0) out.add(ContentSegment.Markdown(tail.substring(0, idx)))
                out.add(ContentSegment.Panel(parsed = null))
            } else {
                out.add(ContentSegment.Markdown(tail))
            }
        }
    }

    /** 解析单个 <details>…</details>；解析失败回退为 Markdown 文本（不吞内容）。 */
    fun parsePanel(html: String): ParsedPanel? {
        return try {
        val body = Jsoup.parseBodyFragment(html).body()
        val details = body.selectFirst("details") ?: return null
        val summary = details.selectFirst("summary")
        val summarySpans = summary?.let { inlineSpans(it) } ?: emptyList()
        val children = ArrayList<PanelBlock>()
        details.children().forEach { child ->
            if (child.tagName() == "summary") return@forEach
            collectBlocks(child, children)
        }
        ParsedPanel(
            summarySpans = summarySpans.ifEmpty { listOf(PanelSpan("详情")) },
            children = children,
            initiallyOpen = details.hasAttr("open")
        )
        } catch (e: Exception) {
            null
        }
    }

    private fun collectBlocks(element: Element, out: MutableList<PanelBlock>) {
        when (element.tagName().lowercase()) {
            "p" -> out.add(PanelBlock.Paragraph(inlineSpans(element)))
            "div" -> {
                // CSS grid / 多列布局降级为流式：只保留内容结构（Spec §7.1）
                if (element.children().isEmpty()) {
                    out.add(PanelBlock.Paragraph(inlineSpans(element)))
                } else {
                    element.children().forEach { collectBlocks(it, out) }
                }
            }
            "details" -> {
                val summary = element.selectFirst("summary")
                val children = ArrayList<PanelBlock>()
                element.children().forEach { c ->
                    if (c.tagName() != "summary") collectBlocks(c, children)
                }
                out.add(
                    PanelBlock.Details(
                        summarySpans = summary?.let { inlineSpans(it) } ?: listOf(PanelSpan("详情")),
                        children = children,
                        initiallyOpen = element.hasAttr("open")
                    )
                )
            }
            "script", "style" -> Unit // 程序/样式正文不作为聊天文字输出（Spec §7.1）
            else -> {
                if (element.children().isEmpty()) {
                    val text = element.text()
                    if (text.isNotBlank()) out.add(PanelBlock.Paragraph(listOf(PanelSpan(text))))
                } else {
                    element.children().forEach { collectBlocks(it, out) }
                }
            }
        }
    }

    /** 行内内容 → 带样式 span：strong/b 加粗、em/i 斜体、span 保留 color 强调；实体由 Jsoup 解码。 */
    private fun inlineSpans(element: Element): List<PanelSpan> {
        // 元素自身的内联颜色作为其全部内容的基底强调色（如 <summary style='color:#FFD700'>）
        val ownColor = inlineColor(element.attr("style"))
        val spans = ArrayList<PanelSpan>()
        element.childNodes().forEach { node ->
            when {
                node is org.jsoup.nodes.TextNode -> {
                    val text = node.text()
                    if (text.isNotBlank()) spans.add(PanelSpan(text))
                }
                node is Element -> {
                    val tag = node.tagName().lowercase()
                    val inner = inlineSpans(node)
                    when (tag) {
                        "strong", "b" -> spans.addAll(inner.map { it.copy(bold = true) })
                        "em", "i" -> spans.addAll(inner.map { it.copy(italic = true) })
                        "br" -> spans.add(PanelSpan("\n"))
                        "script", "style" -> Unit
                        else -> {
                            val color = inlineColor(node.attr("style"))
                            spans.addAll(inner.map { it.copy(colorHex = it.colorHex ?: color) })
                        }
                    }
                }
            }
        }
        val styled = spans.map { it.copy(colorHex = it.colorHex ?: ownColor) }
        return styled.ifEmpty {
            element.ownText().takeIf { it.isNotBlank() }?.let { listOf(PanelSpan(it, colorHex = ownColor)) } ?: emptyList()
        }
    }

    /** 从内联 style 中提取 color（#RGB/#RRGGBB/rgb()/rgba()）；不支持的样式忽略。 */
    private fun inlineColor(style: String): String? {
        val m = Regex("color\\s*:\\s*(#[0-9A-Fa-f]{3,8})").find(style) ?: return null
        return m.groupValues[1]
    }
}

/**
 * 消息正文统一渲染入口：markdown 段走 MarkdownText，HTML 面板段走原生渲染。
 * raw 原文不做任何改写（三类文本分离，Spec §7.2）；流式未闭合面板显示稳定占位。
 */
@Composable
fun MessageContentWithPanels(
    raw: String,
    collapseKeyPrefix: String,
    color: Color,
    displayRegexRules: List<com.loyea.character.core.regex.RegexRule> = emptyList()
) {
    val collapseState = remember(collapseKeyPrefix) { mutableStateOf(mutableMapOf<String, Boolean>()) }
    // P5 显示阶段（Spec §8）：从原文每次重新派生显示内容，规则变更即失效；raw 不改写
    val displayRaw = remember(raw, displayRegexRules) {
        if (displayRegexRules.isEmpty()) raw
        else {
            com.loyea.character.core.regex.BoundedRegexEngine.applyForStage(
                raw, displayRegexRules, com.loyea.character.core.regex.RegexStage.DISPLAY_ASSISTANT
            ).first
        }
    }
    val segments = remember(displayRaw, displayRegexRules) { HtmlDisplaySplitter.split(displayRaw) }
    segments.forEachIndexed { index, segment ->
        when (segment) {
            is ContentSegment.Markdown -> if (segment.text.isNotBlank()) {
                val processed = remember(segment.text) {
                    segment.text
                        .replace(Regex("(?<!`)`(?!`)"), "\\\\`")
                        .replace(Regex("\n{2,}"), "\n\n")
                        .trim('\n', '\r')
                }
                if (processed.isNotBlank()) {
                    MarkdownText(text = processed, color = color)
                }
            }
            is ContentSegment.Panel -> {
                val parsed = segment.parsed
                if (parsed == null) {
                    // 流式占位：完成后整段重新解析，不逐 token 重建（Spec §7.1）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "状态面板生成中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    HtmlPanelBody(
                        panel = parsed,
                        collapseKey = "$collapseKeyPrefix:panel$index",
                        collapseState = collapseState
                    )
                }
            }
        }
    }
}

/**
 * 面板原生渲染（Compose）。折叠状态由调用方以 messageId+面板序号为键持久于会话内存，
 * 不存成剧情状态（Spec §7.1）。颜色/强调信息保留自卡内样式，容器适配 Loyea 主题。
 */
@Composable
fun HtmlPanelBody(
    panel: ParsedPanel,
    collapseKey: String,
    collapseState: MutableState<MutableMap<String, Boolean>>,
    modifier: Modifier = Modifier
) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val defaultOpen = panel.initiallyOpen
    val expanded = collapseState.value[collapseKey] ?: defaultOpen
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(container, RoundedCornerShape(12.dp))
            .border(1.dp, outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    collapseState.value = collapseState.value.toMutableMap().apply {
                        put(collapseKey, !expanded)
                    }
                }
                .padding(vertical = 4.dp)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = annotated(panel.summarySpans),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                panel.children.forEachIndexed { index, block ->
                    RenderPanelBlock(block, "$collapseKey:body$index", collapseState)
                }
            }
        }
    }
}

@Composable
private fun RenderPanelBlock(
    block: PanelBlock,
    key: String,
    collapseState: MutableState<MutableMap<String, Boolean>>
) {
    when (block) {
        is PanelBlock.Paragraph -> {
            Text(
                text = annotated(block.spans),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        is PanelBlock.Details -> {
            val defaultOpen = block.initiallyOpen
            val expanded = collapseState.value[key] ?: defaultOpen
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { collapseState.value = collapseState.value.toMutableMap().apply { put(key, !expanded) } }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = annotated(block.summarySpans),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    block.children.forEachIndexed { i, child ->
                        RenderPanelBlock(child, "$key:c$i", collapseState)
                    }
                }
            }
        }
    }
}

private fun annotated(spans: List<PanelSpan>): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val color = span.colorHex?.let(::parseHexColor)
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                color = color ?: Color.Unspecified
            )
        ) {
            append(span.text)
        }
    }
}

private fun parseHexColor(hex: String): Color? {
    return try {
    val clean = hex.removePrefix("#")
    val argb = when (clean.length) {
        3 -> clean.map { c -> c.toString() + c }.joinToString("")
        6 -> "FF$clean"
        8 -> clean
        else -> return null
    }
    Color(argb.toLong(16).toInt())
    } catch (e: Exception) {
        null
    }
}
