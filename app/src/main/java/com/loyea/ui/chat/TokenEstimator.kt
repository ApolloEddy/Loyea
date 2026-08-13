package com.loyea.ui.chat

/**
 * Token 数量近似估算（仅用于服务端未返回 usage 时的兜底，如 MiMo）。
 *
 * 不是精确 tokenizer，只是粗略近似：
 * - CJK 汉字 ≈ 0.5 token/字（中文每字约半个 token，符合主流中文模型词表密度）
 * - ASCII/其它字符 ≈ 0.25 token/字符（英文每字符约 1/4 token）
 *
 * 空串返回 0；非空结果至少为 1，避免下游把「确有消耗」计成 0。
 */
fun estimateTokens(text: String): Long {
    if (text.isBlank()) return 0L
    var cjk = 0
    var ascii = 0
    for (ch in text) {
        val c = ch.code
        // CJK 统一表意文字 + 扩展 A 区（覆盖绝大多数汉字）
        if ((c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF)) {
            cjk++
        } else {
            ascii++
        }
    }
    val tokens = (cjk * 0.5 + ascii * 0.25).toLong()
    return if (tokens >= 1L) tokens else 1L
}
