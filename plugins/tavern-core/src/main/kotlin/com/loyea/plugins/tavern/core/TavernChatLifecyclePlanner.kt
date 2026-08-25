package com.loyea.plugins.tavern.core

import java.time.Instant

/**
 * 会话克隆 / 重启规划器，对齐 Tavo 的 Clone Chat 与 Restart Chat 语义。
 *
 * 本对象刻意保持纯函数风格：不触碰任何 I/O，断言与副作用统一交给调用方，
 * 从而在核心模块内可获得确定性的可测试行为。克隆与重启都是对 [TavernChatFile]
 * 的不可变值变换，深拷贝语义具体指：消息列表必须复制为新的 [List]（不可共享
 * 可变底层），而 [TavernChatMessageRecord.extraJson] / [TavernChatMessageRecord.rawJson]
 * 等字符串字段只需复制内容（字符串本身不可变，共享引用无副作用）。
 */
object TavernChatLifecyclePlanner {

    /** 分支 / 检查点链接元数据键，克隆时必须从 header 中剔除以保持纯净。 */
    private val branchLinkKeys = setOf(
        "main_chat",
        "spring_chat",
        "branches",
        "bookmark_link"
    )

    /**
     * 全量克隆会话。
     *
     * - 消息列表整体深拷贝（新 [List]，[TavernChatMessageRecord.swipes] 复制为新列表，
     *   [TavernChatMessageRecord.extraJson] / [TavernChatMessageRecord.rawJson] 内容原样保留）。
     * - header 中剔除 [branchLinkKeys] 指示的分支 / 检查点链接元数据（保持纯净）。
     * - 写入新的 [chatName]。
     */
    fun cloneChat(source: TavernChatFile, newChatName: String): TavernChatFile {
        require(newChatName.isNotBlank()) { "New chat name must not be blank" }
        return TavernChatFile(
            header = stripBranchLinks(source.header),
            messages = deepCopyMessages(source.messages),
            chatName = newChatName
        )
    }

    /**
     * 重启会话：保留 header 配置（userName / characterName / chatMetadata 等），
     * 清空全部消息并把 [TavernChatHeader.createDate] 重置为指定时刻的 UTC ISO 字符串。
     *
     * @param now 用于重置 [TavernChatHeader.createDate] 的时刻，默认 [Instant.now]，便于测试注入。
     */
    fun restartChat(source: TavernChatFile, now: Instant = Instant.now()): TavernChatFile =
        TavernChatFile(
            header = source.header.copy(createDate = now.toString()),
            messages = emptyList(),
            chatName = source.chatName
        )

    /**
     * 剔除 header 分支链接元数据的纯函数。返回的 header 可能与原 header 相同实例（相对拷贝）。
     * [main_chat] / [spring_chat] / [branches] / [bookmark_link] 等键从
     * [TavernChatHeader.chatMetadataJson] 中移除。
     */
    fun stripBranchLinks(header: TavernChatHeader): TavernChatHeader {
        val metadata = header.metadata()
        var removed = false
        branchLinkKeys.forEach { key ->
            if (metadata.has(key)) {
                metadata.remove(key)
                removed = true
            }
        }
        return if (removed) header.copy(chatMetadataJson = metadata.toString()) else header
    }

    /** 深拷贝消息列表：产生新的 [List]，并把每条消息的可变列表字段也复制为新列表。 */
    fun deepCopyMessages(messages: List<TavernChatMessageRecord>): List<TavernChatMessageRecord> =
        messages.map { message -> message.copy(swipes = message.swipes.toList()) }
}