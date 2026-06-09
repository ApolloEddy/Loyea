package com.loyea.ui.chat

import androidx.compose.foundation.background
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
}

// 简单的 Markdown 解析器
private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    var inCodeBlock = false
    var codeLanguage = ""
    val codeBuilder = StringBuilder()
    val textBuilder = StringBuilder()

    for (line in lines) {
        if (line.trim().startsWith("```")) {
            if (inCodeBlock) {
                // 结束代码块
                blocks.add(MarkdownBlock.CodeBlock(codeBuilder.toString().trimEnd(), codeLanguage))
                codeBuilder.clear()
                inCodeBlock = false
            } else {
                // 开始代码块
                if (textBuilder.isNotEmpty()) {
                    blocks.add(MarkdownBlock.TextBlock(textBuilder.toString().trimEnd()))
                    textBuilder.clear()
                }
                codeLanguage = line.trim().substring(3).trim()
                if (codeLanguage.isEmpty()) codeLanguage = "code"
                inCodeBlock = true
            }
        } else {
            if (inCodeBlock) {
                codeBuilder.append(line).append("\n")
            } else {
                textBuilder.append(line).append("\n")
            }
        }
    }

    if (inCodeBlock) {
        blocks.add(MarkdownBlock.CodeBlock(codeBuilder.toString().trimEnd(), codeLanguage))
    } else if (textBuilder.isNotEmpty()) {
        blocks.add(MarkdownBlock.TextBlock(textBuilder.toString().trimEnd()))
    }

    return blocks
}

// 行内代码 `code` 渲染
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
                        background = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                append(part)
                pop()
            } else {
                pushStyle(SpanStyle(color = textColor))
                append(part)
                pop()
            }
            isCode = !isCode
        }
    }
}

// 代码块布局
@Composable
fun CodeBlockLayout(code: String, language: String) {
    val clipboardManager = LocalClipboardManager.current
    val containerBg = Color(0xFF1E1E1E) // 固定深色背景，符合代码块习惯
    val headerBg = Color(0xFF2D2D2D)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerBg)
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
    // 仅对 Kotlin/Java 及默认代码块进行高亮，其余返回原文本
    if (lowerLang != "kotlin" && lowerLang != "java" && lowerLang != "kt" && lowerLang != "code" && lowerLang.isNotBlank()) {
        return AnnotatedString(code)
    }

    return buildAnnotatedString {
        append(code)
        // 1. 初始底色设为优雅的淡灰白色
        addStyle(SpanStyle(color = Color(0xFFD4D4D4)), 0, code.length)

        // 2. 匹配数字 (淡蓝色 Hex: 6897BB)
        val numberRegex = Regex("\\b\\d+\\b")
        numberRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF6897BB)), match.range.first, match.range.last + 1)
        }

        // 3. 匹配注解 (黄褐色 Hex: BBB529)
        val annotationRegex = Regex("@\\w+")
        annotationRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFFBBB529)), match.range.first, match.range.last + 1)
        }

        // 4. 匹配关键字 (橙黄色 Hex: CC7832，加粗)
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

        // 5. 匹配字符串字面量 (浅绿色 Hex: 6A8759)
        val stringRegex = Regex("\"[^\n\"\\\\]*(?:\\\\.[^\n\"\\\\]*)*\"")
        stringRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF6A8759)), match.range.first, match.range.last + 1)
        }

        // 6. 匹配单行注释 // (灰色 Hex: 808080，斜体)
        val singleLineCommentRegex = Regex("//.*")
        singleLineCommentRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF808080), fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }

        // 7. 匹配多行注释 /* ... */ (灰色 Hex: 808080，斜体)
        val multiLineCommentRegex = Regex("/\\*[\\s\\S]*?\\*/")
        multiLineCommentRegex.findAll(code).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF808080), fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }
    }
}
