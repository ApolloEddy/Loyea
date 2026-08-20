package com.loyea.ui.chat

/** 输入框按实际渲染行数决定是否显示全屏编辑入口。 */
internal fun shouldShowExpandedEditor(renderedLineCount: Int): Boolean = renderedLineCount > 3
