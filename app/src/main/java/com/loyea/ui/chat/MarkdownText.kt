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
                is MarkdownBlock.TableBlock -> {
                    TableLayout(
                        headers = block.headers,
                        alignments = block.alignments,
                        rows = block.rows,
                        color = color
                    )
                }
                is MarkdownBlock.LatexBlock -> {
                    LatexLayout(latex = block.latex, color = color)
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
    data class LatexBlock(val latex: String) : MarkdownBlock()
    data class TableBlock(
        val headers: List<String>,
        val alignments: List<TableAlignment>,
        val rows: List<List<String>>
    ) : MarkdownBlock()
}

// 优化的 Markdown 解析器，支持列表、标题、引用块和分割线
private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    var inCodeBlock = false
    var codeLanguage = ""
    val codeBuilder = StringBuilder()
    // 块级数学公式 $$...$$ 收集状态（可跨行）
    var inLatexBlock = false
    val latexBuilder = StringBuilder()
    val currentTextBlock = StringBuilder()
    
    // 用于列表项折叠的临时变量
    val currentListItems = mutableListOf<String>()
    var currentListOrdered = false
    var inList = false

    // 用于表格的临时变量
    var inTable = false
    var tableHeaders = mutableListOf<String>()
    var tableAlignments = mutableListOf<TableAlignment>()
    val tableRows = mutableListOf<List<String>>()

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

    fun flushTableBlock() {
        if (inTable) {
            blocks.add(MarkdownBlock.TableBlock(tableHeaders.toList(), tableAlignments.toList(), tableRows.toList()))
            inTable = false
            tableHeaders.clear()
            tableAlignments.clear()
            tableRows.clear()
        }
    }

    for (line in lines) {
        val trimmedLine = line.trim()
        
        // 1. 如果在代码块内部，正常收集代码内容，不进行表格等块解析
        if (inCodeBlock && !trimmedLine.startsWith("```")) {
            codeBuilder.append(line).append("\n")
            continue
        }

        // 1.5 块级数学公式 $$...$$ 内容收集（直到遇到结束的 $$ 行）
        if (inLatexBlock) {
            if (trimmedLine.startsWith("$$")) {
                latexBuilder.append(line.substringBefore("$$"))
                blocks.add(MarkdownBlock.LatexBlock(latexBuilder.toString().trim()))
                latexBuilder.clear()
                inLatexBlock = false
            } else {
                latexBuilder.append(line).append("\n")
            }
            continue
        }

        // 2. 如果在表格中，但当前行不再包含 '|'，则先结算表格
        if (inTable && !line.contains("|")) {
            flushTableBlock()
        }

        // 3. 解析代码块起始与结束
        if (trimmedLine.startsWith("```")) {
            flushTableBlock()
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

        // 3.5 开启块级数学公式 $$...$$（单行 $$x^2$$ 或多行 $$ ... $$）
        if (trimmedLine.startsWith("$$")) {
            flushTableBlock()
            flushListBlock()
            flushTextBlock()
            if (trimmedLine.endsWith("$$") && trimmedLine.length > 4) {
                blocks.add(MarkdownBlock.LatexBlock(trimmedLine.removePrefix("$$").removeSuffix("$$").trim()))
            } else {
                inLatexBlock = true
                latexBuilder.append(trimmedLine.removePrefix("$$")).append("\n")
            }
            continue
        }

        // 4. 解析表格对齐/分隔行 (例如 |:---|:---:|---:|)
        if (!inTable && isTableSeparatorLine(line)) {
            val lastLineOfText = currentTextBlock.toString().trimEnd().split("\n").lastOrNull()
            if (lastLineOfText != null && lastLineOfText.contains("|")) {
                // 将 Text 缓冲区里除了最后一行外的其他行先 flush
                val textLines = currentTextBlock.toString().trimEnd().split("\n")
                if (textLines.size > 1) {
                    val remainingText = textLines.dropLast(1).joinToString("\n")
                    blocks.add(MarkdownBlock.TextBlock(remainingText))
                }
                currentTextBlock.clear()
                
                tableHeaders = parseTableRow(lastLineOfText).toMutableList()
                tableAlignments = parseTableAlignments(line).toMutableList()
                tableRows.clear()
                inTable = true
                continue
            }
        }

        // 5. 表格行收集逻辑
        if (inTable) {
            if (line.contains("|")) {
                val rowCells = parseTableRow(line)
                val paddedCells = if (rowCells.size < tableHeaders.size) {
                    rowCells + List(tableHeaders.size - rowCells.size) { "" }
                } else {
                    rowCells.take(tableHeaders.size)
                }
                tableRows.add(paddedCells)
                continue
            } else {
                flushTableBlock()
            }
        }

        // 6. 解析标题 (1-6 级)
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

        // 7. 解析引用
        if (trimmedLine.startsWith(">")) {
            flushListBlock()
            flushTextBlock()
            val quoteText = trimmedLine.substring(1).trim()
            blocks.add(MarkdownBlock.QuoteBlock(quoteText))
            continue
        }

        // 8. 解析分割线
        if (trimmedLine == "---" || trimmedLine == "***") {
            flushListBlock()
            flushTextBlock()
            blocks.add(MarkdownBlock.DividerBlock)
            continue
        }

        // 9. 解析列表
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

        // 10. 普通文本段落
        currentTextBlock.append(line).append("\n")
    }

    // 清理缓冲区剩余内容
    flushTableBlock()
    flushListBlock()
    if (inLatexBlock) {
        blocks.add(MarkdownBlock.LatexBlock(latexBuilder.toString().trim()))
        latexBuilder.clear()
        inLatexBlock = false
    }
    flushTextBlock()

    return blocks
}

// 行内代码、粗体与数学公式 $...$ 渲染
@Composable
private fun renderInlineMarkdown(text: String, textColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val escapedBacktick = "___ESC_BT___"
        val escapedStar = "___ESC_STAR___"
        val escapedDollar = "___ESC_DOLLAR___"
        // 保护被反斜杠转义的反引号、星号和美元符，防止它们参与 Markdown 切割
        val tempText = text
            .replace("\\`", escapedBacktick)
            .replace("\\*", escapedStar)
            .replace("\\$", escapedDollar)

        // 动作描写 / 括注弱化：全角（...）、半角(...)、单星号 *...* 统一渲染为
        // 斜体 + 减弱色 + 小字号（SillyTavern / Tavo 风格），与正文台词形成视觉层次。
        // 括注本身是次要信息，弱化不丢信息，用户消息与 AI 消息全局生效。
        fun appendWithActionWeak(seg: String, baseColor: Color) {
            val actionRegex =
                Regex("（[^（）()\\n]{1,120}）|\\([^（）()\\n]{1,120}\\)|\\*[^*\\n]{1,120}\\*")
            var lastIndex = 0
            actionRegex.findAll(seg).forEach { match ->
                if (match.range.first > lastIndex) {
                    append(seg.substring(lastIndex, match.range.first))
                }
                pushStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        fontSize = 14.sp,
                        color = baseColor.copy(alpha = 0.5f)
                    )
                )
                append(match.value)
                pop()
                lastIndex = match.range.last + 1
            }
            if (lastIndex < seg.length) {
                append(seg.substring(lastIndex))
            }
        }

        // 普通文本与粗体渲染（顺带还原被保护的字符；粗体内同样支持动作弱化）
        fun appendPlain(seg: String) {
            val boldParts = seg.split("**")
            var isBold = false
            boldParts.forEach { boldPart ->
                val restored = boldPart
                    .replace(escapedBacktick, "`")
                    .replace(escapedStar, "*")
                    .replace(escapedDollar, "$")
                if (isBold) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor))
                    appendWithActionWeak(restored, textColor)
                    pop()
                } else {
                    appendWithActionWeak(restored, textColor)
                }
                isBold = !isBold
            }
        }

        val parts = tempText.split("`")
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
                // 还原被保护的字符
                val restoredPart = part.replace(escapedBacktick, "`").replace(escapedStar, "*")
                append(restoredPart)
                pop()
            } else {
                // 行内数学公式 $...$（只匹配成对的美元符，内容不含换行）
                val mathRegex = Regex("\\$([^$\\n]{1,200})\\$")
                var lastIndex = 0
                mathRegex.findAll(part).forEach { match ->
                    appendPlain(part.substring(lastIndex, match.range.first))
                    val mathContent = match.groupValues[1]
                        .replace(escapedBacktick, "`")
                        .replace(escapedStar, "*")
                        .replace(escapedDollar, "$")
                    pushStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    append(latexToUnicode(mathContent))
                    pop()
                    lastIndex = match.range.last + 1
                }
                appendPlain(part.substring(lastIndex))
            }
            isCode = !isCode
        }
    }
}

/**
 * 轻量 LaTeX → Unicode 纯文本转换器（完全离线，零依赖）
 *
 * 覆盖常用公式子集：分式、根号、上下标、希腊字母、常用运算符与函数名。
 * 无法识别的命令会去掉反斜杠原样保留，不会崩溃。
 */
internal fun latexToUnicode(input: String): String {
    var s = input.trim()

    // 1. 移除环境修饰符与间距命令
    s = s.replace("\\left", "").replace("\\right", "")
    s = s.replace("\\displaystyle", "")
    s = s.replace("\\qquad", "    ").replace("\\quad", "  ")
    s = s.replace("\\;", " ").replace("\\,", " ").replace("\\!", "")

    // 2. \text{...} / \mathrm{...} / \operatorname{...} 还原为内文
    s = Regex("\\\\(text|mathrm|operatorname)\\{([^{}]*)\\}").replace(s) { m -> m.groupValues[2] }
    // 3. \mathbf / \boldsymbol 去掉修饰符
    s = Regex("\\\\mathbf\\{([^{}]*)\\}").replace(s) { m -> m.groupValues[1] }
    s = Regex("\\\\boldsymbol\\{([^{}]*)\\}").replace(s) { m -> m.groupValues[1] }

    // 4. 常用大算符（上下限由后续下标/上标规则处理）
    s = s.replace("\\sum", "Σ").replace("\\prod", "Π").replace("\\int", "∫")

    // 5. 希腊字母与符号（先长后短，避免 \leq 被 \le 抢先；必须先于上下标，避免命令被打散）
    val symbolTable = mapOf(
        // 希腊小写
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
        "\\omicron" to "ο", "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ",
        "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        // 希腊大写
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        // 运算符与关系符
        "\\times" to "×", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
        "\\cdot" to "·", "\\ast" to "*", "\\star" to "⋆", "\\circ" to "°",
        "\\leq" to "≤", "\\geq" to "≥", "\\neq" to "≠", "\\ne" to "≠",
        "\\approx" to "≈", "\\equiv" to "≡", "\\propto" to "∝", "\\sim" to "~",
        "\\cong" to "≅", "\\prec" to "≺", "\\succ" to "≻", "\\ll" to "≪", "\\gg" to "≫",
        "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇", "\\prime" to "′",
        "\\emptyset" to "∅", "\\varnothing" to "∅", "\\angle" to "∠", "\\triangle" to "△",
        // 逻辑与集合
        "\\in" to "∈", "\\notin" to "∉", "\\ni" to "∋", "\\subset" to "⊂",
        "\\supset" to "⊃", "\\subseteq" to "⊆", "\\supseteq" to "⊇",
        "\\cup" to "∪", "\\cap" to "∩", "\\forall" to "∀", "\\exists" to "∃",
        "\\neg" to "¬", "\\land" to "∧", "\\lor" to "∨", "\\therefore" to "∴",
        "\\because" to "∵", "\\mid" to "|", "\\parallel" to "∥",
        // 箭头
        "\\rightarrow" to "→", "\\Rightarrow" to "⇒", "\\leftarrow" to "←",
        "\\Leftarrow" to "⇐", "\\leftrightarrow" to "↔", "\\to" to "→",
        "\\mapsto" to "↦", "\\uparrow" to "↑", "\\downarrow" to "↓",
        // 省略号
        "\\cdots" to "⋯", "\\ldots" to "…", "\\dots" to "…", "\\vdots" to "⋮", "\\ddots" to "⋱",
        // 分隔符
        "\\lbrace" to "{", "\\rbrace" to "}", "\\lbrack" to "[", "\\rbrack" to "]",
        "\\langle" to "⟨", "\\rangle" to "⟩", "\\lceil" to "⌈", "\\rceil" to "⌉",
        "\\lfloor" to "⌊", "\\rfloor" to "⌋", "\\degree" to "°"
    )
    val sortedSymbols = symbolTable.entries.sortedByDescending { it.key.length }
    for ((cmd, rep) in sortedSymbols) {
        s = s.replace(cmd, rep)
    }

    // 6. 函数名（\sin \cos \log \lim 等，同样必须先于上下标）
    for (fn in listOf(
        "sinh", "cosh", "tanh", "arcsin", "arccos", "arctan", "limsup", "liminf",
        "sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "lg", "exp",
        "lim", "max", "min", "det", "mod", "gcd", "arg", "ker"
    )) {
        s = Regex("\\\\$fn(?!\\w)").replace(s, fn)
    }

    // 7. 分式/根号/二项式（连跑两轮以兼容嵌套：\frac{\sqrt{2}}{3} 与 \sqrt{\frac{1}{2}}）
    fun fracWrap(t: String): String = if (t.any { it in "+−=±×÷<≤≥≈ " }) "($t)" else t
    repeat(2) {
        s = Regex("\\\\sqrt\\[([^{}]*)\\]\\{([^{}]*)\\}").replace(s) { m -> "${m.groupValues[1]}√(${m.groupValues[2]})" }
        s = Regex("\\\\sqrt\\{([^{}]*)\\}").replace(s) { m -> "√(${m.groupValues[1]})" }
        s = Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}").replace(s) { m ->
            "${fracWrap(m.groupValues[1])}/${fracWrap(m.groupValues[2])}"
        }
    }
    s = Regex("\\\\binom\\{([^{}]*)\\}\\{([^{}]*)\\}").replace(s) { m -> "C(${m.groupValues[1]}, ${m.groupValues[2]})" }

    // 8. 上标 / 下标（{...} 包裹与单个字符两种形态）
    s = Regex("\\^\\{([^{}]*)\\}").replace(s) { m -> toSuperscript(m.groupValues[1]) }
    s = Regex("_\\{([^{}]*)\\}").replace(s) { m -> toSubscript(m.groupValues[1]) }
    s = Regex("\\^([0-9a-zA-Zαβγδεζηθικλμνξπρστυφχψω])").replace(s) { m -> toSuperscript(m.groupValues[1]) }
    s = Regex("_([0-9a-zA-Zαβγδεζηθικλμνξπρστυφχψω])").replace(s) { m -> toSubscript(m.groupValues[1]) }

    // 9. 向量/帽子/均值
    s = Regex("\\\\vec\\{([^{}]*)\\}").replace(s) { m -> m.groupValues[1] + "⃗" }
    s = Regex("\\\\hat\\{([^{}]*)\\}").replace(s) { m -> m.groupValues[1] + "^" }
    s = Regex("\\\\bar\\{([^{}]*)\\}").replace(s) { m -> m.groupValues[1] + "̄" }
    s = Regex("\\\\overline\\{([^{}]*)\\}").replace(s) { m -> "¯(${m.groupValues[1]})" }
    s = Regex("\\\\underline\\{([^{}]*)\\}").replace(s) { m -> "_(${m.groupValues[1]})" }

    // 10. 转义字面量 \_ \{ \} \% \& \# \$
    s = s.replace("\\_", "_").replace("\\{", "{").replace("\\}", "}")
    s = s.replace("\\%", "%").replace("\\&", "&").replace("\\#", "#").replace("\\$", "$")

    // 11. 兜底：残留的未知命令去掉反斜杠原样保留
    s = Regex("\\\\([a-zA-Z]+)").replace(s) { m -> m.groupValues[1] }

    // 12. 压缩多余空白
    return s.replace(Regex("\\s+"), " ").trim()
}

// Unicode 上标/下标映射（未收录字符原样保留）
private val superscriptMap = mapOf(
    '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴", '5' to "⁵",
    '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹", '+' to "⁺", '-' to "⁻",
    '=' to "⁼", '(' to "⁽", ')' to "⁾",
    'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ", 'f' to "ᶠ",
    'g' to "ᵍ", 'h' to "ʰ", 'i' to "ⁱ", 'j' to "ʲ", 'k' to "ᵏ", 'l' to "ˡ",
    'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ", 'p' to "ᵖ", 'q' to "ᑫ", 'r' to "ʳ",
    's' to "ˢ", 't' to "ᵗ", 'u' to "ᵘ", 'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ",
    'y' to "ʸ", 'z' to "ᶻ",
    'A' to "ᴬ", 'B' to "ᴮ", 'D' to "ᴰ", 'E' to "ᴱ", 'G' to "ᴳ", 'H' to "ᴴ",
    'I' to "ᴵ", 'J' to "ᴶ", 'K' to "ᴷ", 'L' to "ᴸ", 'M' to "ᴹ", 'N' to "ᴺ",
    'O' to "ᴼ", 'P' to "ᴾ", 'R' to "ᴿ", 'T' to "ᵀ", 'U' to "ᵁ", 'V' to "ⱽ",
    'W' to "ᵂ",
    'α' to "ᵅ", 'β' to "ᵝ", 'γ' to "ᵞ", 'δ' to "ᵟ", 'θ' to "ᶿ", 'φ' to "ᵠ", 'χ' to "ᵡ"
)

private val subscriptMap = mapOf(
    '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄", '5' to "₅",
    '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉", '+' to "₊", '-' to "₋",
    '=' to "₌", '(' to "₍", ')' to "₎",
    'a' to "ₐ", 'b' to "ᵦ", 'c' to "꜀", 'd' to "ᵈ", 'e' to "ₑ", 'f' to "ᶠ",
    'g' to "ᵍ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ", 'k' to "ₖ", 'l' to "ₗ",
    'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ", 'p' to "ₚ", 'r' to "ᵣ", 's' to "ₛ",
    't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ", 'w' to "w", 'x' to "ₓ", 'y' to "ᵧ", 'z' to "ᶻ",
    'β' to "ᵦ", 'γ' to "ᵧ", 'ρ' to "ᵨ", 'φ' to "ᵩ", 'χ' to "ᵪ"
)

private fun toSuperscript(s: String): String = s.map { superscriptMap[it] ?: it }.joinToString("")
private fun toSubscript(s: String): String = s.map { subscriptMap[it] ?: it }.joinToString("")

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

// 块级数学公式渲染排版布局（$$...$$，离线转换 Unicode 纯文本）
@Composable
fun LatexLayout(latex: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = latexToUnicode(latex),
            fontSize = 15.sp,
            fontStyle = FontStyle.Italic,
            color = color,
            modifier = Modifier.fillMaxWidth()
        )
    }
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

// 表格相关的辅助对象与高颜值布局组件
enum class TableAlignment {
    LEFT, CENTER, RIGHT
}

private fun isTableSeparatorLine(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return false
    return trimmed.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
}

private fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim()
    val rawCells = trimmed.split("|")
    val startIdx = if (trimmed.startsWith("|")) 1 else 0
    val endIdx = if (trimmed.endsWith("|")) rawCells.size - 1 else rawCells.size
    if (startIdx >= endIdx) return emptyList()
    return rawCells.subList(startIdx, endIdx).map { it.trim() }
}

private fun parseTableAlignments(line: String): List<TableAlignment> {
    val cells = parseTableRow(line)
    return cells.map { cell ->
        val trimmed = cell.trim()
        val left = trimmed.startsWith(":")
        val right = trimmed.endsWith(":")
        when {
            left && right -> TableAlignment.CENTER
            right -> TableAlignment.RIGHT
            else -> TableAlignment.LEFT
        }
    }
}

@Composable
fun TableLayout(
    headers: List<String>,
    alignments: List<TableAlignment>,
    rows: List<List<String>>,
    color: Color
) {
    val scrollState = rememberScrollState()
    val colCount = headers.size
    
    // 如果列数大于 3，才启用横向滚动，避免 scroll 容器内部测量 weight(1f) 导致异常
    val containerModifier = if (colCount <= 3) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
    }
    
    val columnModifier = if (colCount <= 3) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.wrapContentWidth()
    }
    
    Box(
        modifier = containerModifier.padding(vertical = 4.dp)
    ) {
        Column(
            modifier = columnModifier
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 1. 绘制表头
            Row(
                modifier = if (colCount <= 3) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                headers.forEachIndexed { index, header ->
                    val alignment = alignments.getOrNull(index) ?: TableAlignment.LEFT
                    val textAlign = when (alignment) {
                        TableAlignment.LEFT -> Alignment.CenterStart
                        TableAlignment.CENTER -> Alignment.Center
                        TableAlignment.RIGHT -> Alignment.CenterEnd
                    }
                    val cellModifier = if (colCount <= 3) Modifier.weight(1f) else Modifier.width(120.dp)
                    
                    Box(
                        modifier = cellModifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = textAlign
                    ) {
                        Text(
                            text = header,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (index < headers.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(38.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        )
                    }
                }
            }
            
            // 横向分割线
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // 2. 绘制数据行
            rows.forEachIndexed { rowIndex, row ->
                val isEven = rowIndex % 2 == 0
                val rowBg = if (isEven) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f)
                val rowModifier = if (colCount <= 3) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
                
                Row(
                    modifier = rowModifier.background(rowBg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEachIndexed { colIndex, cell ->
                        val alignment = alignments.getOrNull(colIndex) ?: TableAlignment.LEFT
                        val textAlign = when (alignment) {
                            TableAlignment.LEFT -> Alignment.CenterStart
                            TableAlignment.CENTER -> Alignment.Center
                            TableAlignment.RIGHT -> Alignment.CenterEnd
                        }
                        val cellModifier = if (colCount <= 3) Modifier.weight(1f) else Modifier.width(120.dp)
                        
                        Box(
                            modifier = cellModifier
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = textAlign
                        ) {
                            Text(
                                text = renderInlineMarkdown(cell, color),
                                fontSize = 13.sp,
                                color = color
                            )
                        }
                        if (colIndex < row.size - 1) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(34.dp)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            )
                        }
                    }
                }
                if (rowIndex < rows.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
            }
        }
    }
}
