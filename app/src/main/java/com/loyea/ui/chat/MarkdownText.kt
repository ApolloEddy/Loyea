package com.loyea.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    val blocks = parseMarkdown(text)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    CodeBlockLayout(code = block.code, language = block.language)
                }
                is MarkdownBlock.HeaderBlock -> {
                    HeaderLayout(text = block.text, level = block.level, color = color)
                }
                is MarkdownBlock.ListBlock -> {
                    ListLayout(items = block.items, ordered = block.ordered, color = color)
                }
                is MarkdownBlock.QuoteBlock -> {
                    QuoteLayout(text = block.text, color = color)
                }
                is MarkdownBlock.DividerBlock -> {
                    DividerLayout()
                }
                is MarkdownBlock.TextBlock -> {
                    Text(
                        text = renderInlineMarkdown(block.text, color),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color
                    )
                }
            }
        }
    }
}

sealed class MarkdownBlock {
    data class TextBlock(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String) : MarkdownBlock()
    data class HeaderBlock(val text: String, val level: Int) : MarkdownBlock()
    data class ListBlock(val items: List<String>, val ordered: Boolean) : MarkdownBlock()
    data class QuoteBlock(val text: String) : MarkdownBlock()
    object DividerBlock : MarkdownBlock()
}

// 优化的 Markdown 解析器，支持列表、标题、引用块和分割线
private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    var inCodeBlock = false
    var codeLanguage = ""
    val codeBuilder = StringBuilder()
    val currentTextBlock = StringBuilder()
    
    // 用于列表项折叠的临时变量
    val currentListItems = mutableListOf<String>()
    var currentListOrdered = false
    var inList = false

    fun flushTextBlock() {
        if (currentTextBlock.isNotEmpty()) {
            blocks.add(MarkdownBlock.TextBlock(currentTextBlock.toString().trimEnd()))
            currentTextBlock.clear()
        }
    }

    fun flushListBlock() {
        if (inList && currentListItems.isNotEmpty()) {
            blocks.add(MarkdownBlock.ListBlock(currentListItems.toList(), currentListOrdered))
            currentListItems.clear()
            inList = false
        }
    }

    for (line in lines) {
        val trimmedLine = line.trim()
        
        // 解析代码块
        if (trimmedLine.startsWith("```")) {
            flushListBlock()
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeBuilder.toString().trimEnd(), codeLanguage))
                codeBuilder.clear()
                inCodeBlock = false
            } else {
                flushTextBlock()
                codeLanguage = trimmedLine.substring(3).trim()
                if (codeLanguage.isEmpty()) codeLanguage = "code"
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            codeBuilder.append(line).append("\n")
            continue
        }

        // 解析标题 (1-6 级)
        if (trimmedLine.startsWith("#")) {
            val level = trimmedLine.takeWhile { it == '#' }.length
            if (level in 1..6 && trimmedLine.length > level && trimmedLine[level] == ' ') {
                flushListBlock()
                flushTextBlock()
                val headerText = trimmedLine.substring(level + 1).trim()
                blocks.add(MarkdownBlock.HeaderBlock(headerText, level))
                continue
            }
        }

        // 解析引用
        if (trimmedLine.startsWith(">")) {
            flushListBlock()
            flushTextBlock()
            val quoteText = trimmedLine.substring(1).trim()
            blocks.add(MarkdownBlock.QuoteBlock(quoteText))
            continue
        }

        // 解析分割线
        if (trimmedLine == "---" || trimmedLine == "***") {
            flushListBlock()
            flushTextBlock()
            blocks.add(MarkdownBlock.DividerBlock)
            continue
        }

        // 解析列表
        val isUnorderedList = trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") || trimmedLine.startsWith("• ")
        val orderedListMatch = Regex("^\\d+\\.\\s+(.*)").find(trimmedLine)
        val isOrderedList = orderedListMatch != null

        if (isUnorderedList || isOrderedList) {
            flushTextBlock()
            val listContent = if (isUnorderedList) trimmedLine.substring(2) else orderedListMatch!!.groupValues[1]
            
            if (inList && currentListOrdered != isOrderedList) {
                flushListBlock()
            }
            
            inList = true
            currentListOrdered = isOrderedList
            currentListItems.add(listContent)
            continue
        } else {
            flushListBlock()
        }

        // 普通文本段落
        currentTextBlock.append(line).append("\n")
    }

    // 清理缓冲区剩余内容
    flushListBlock()
    flushTextBlock()

    return blocks
}

// 行内代码与粗体渲染
@Composable
private fun renderInlineMarkdown(text: String, textColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val parts = text.split("`")
        var isCode = false
        parts.forEach { part ->
            if (isCode) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                append(part)
                pop()
            } else {
                val boldParts = part.split("**")
                var isBold = false
                boldParts.forEach { boldPart ->
                    if (isBold) {
                        pushStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        )
                        append(boldPart)
                        pop()
                    } else {
                        append(boldPart)
                    }
                    isBold = !isBold
                }
            }
            isCode = !isCode
        }
    }
}

// 标题渲染排版布局
@Composable
fun HeaderLayout(text: String, level: Int, color: Color) {
    val fontSize = when (level) {
        1 -> 22.sp
        2 -> 19.sp
        3 -> 17.sp
        else -> 15.sp
    }
    val fontWeight = when (level) {
        1, 2 -> FontWeight.ExtraBold
        else -> FontWeight.Bold
    }
    val paddingTop = if (level == 1) 16.dp else 10.dp
    val paddingBottom = if (level == 1) 12.dp else 6.dp

    Text(
        text = renderInlineMarkdown(text, color),
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        modifier = Modifier
            .padding(top = paddingTop, bottom = paddingBottom)
            .fillMaxWidth()
    )
}

// 列表渲染排版布局 (支持无序/有序)
@Composable
fun ListLayout(items: List<String>, ordered: Boolean, color: Color) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        items.forEachIndexed { index, item ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                val prefix = if (ordered) "${index + 1}. " else "• "
                Text(
                    text = prefix,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(if (ordered) 28.dp else 14.dp)
                )
                Text(
                    text = renderInlineMarkdown(item, color),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// 引用块渲染排版布局
@Composable
fun QuoteLayout(text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f))
            .border(
                width = 3.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = renderInlineMarkdown(text, color),
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = color.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// 分割线渲染排版布局
@Composable
fun DividerLayout() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    )
}

// 代码块布局
@Composable
fun CodeBlockLayout(code: String, language: String) {
    val clipboardManager = LocalClipboardManager.current
    val containerBg = Color(0xFF1E1E1E) // 固定深色背景，符合代码习惯
    val headerBg = Color(0xFF2D2D2D)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerBg)
            .padding(bottom = 2.dp)
    ) {
        // 代码块头部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.lowercase(),
                color = Color(0xFFB5B5B5),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(code)) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Code",
                    tint = Color(0xFFB5B5B5),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        // 代码内容
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = highlightCode(code, language),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

// 极其轻量且高效的 Kotlin/Java 正则语法高亮引擎
fun highlightCode(code: String, language: String): AnnotatedString {
    val lowerLang = language.lowercase()
    if (lowerLang != "kotlin" && lowerLang != "java" && lowerLang != "kt" && lowerLang != "code" && lowerLang.isNotBlank()) {
        return AnnotatedString(code)
    }

    return buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = Color(0xFFD4D4D4)), 0, code.length)

        val numberRegex = Regex("\\b\\d+\\b")
        numberRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF6897BB)), match.range.first, match.range.last + 1)
        }

        val annotationRegex = Regex("@\\w+")
        annotationRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFFBBB529)), match.range.first, match.range.last + 1)
        }

        val keywords = listOf(
            "val", "var", "fun", "class", "object", "interface", "import", "package", 
            "return", "if", "else", "when", "for", "in", "by", "while", "do", "try", 
            "catch", "finally", "throw", "as", "is", "super", "this", "private", 
            "protected", "public", "internal", "override", "open", "abstract", 
            "companion", "suspend", "flow"
        )
        val keywordRegex = Regex("\\b(" + keywords.joinToString("|") + ")\\b")
        keywordRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFFCC7832), fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }

        val stringRegex = Regex("\"[^\n\"\\\\]*(?:\\\\.[^\n\"\\\\]*)*\"")
        stringRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF6A8759)), match.range.first, match.range.last + 1)
        }

        val singleLineCommentRegex = Regex("//.*")
        singleLineCommentRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF808080), fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }

        val multiLineCommentRegex = Regex("/\\*[\\s\\S]*?\\*/")
        multiLineCommentRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF808080), fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }
    }
}
