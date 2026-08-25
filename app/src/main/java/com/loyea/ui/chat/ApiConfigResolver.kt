package com.loyea.ui.chat

import com.loyea.ui.settings.ApiConfig

/**
 * 会话级 / 群聊选角级 API 绑定解析（纯函数，可单测）。
 *
 * B4：会话级绑定 `ChatSession.apiBindingId`；B7：选角专用绑定 `ChatSession.speakerApiBindingId`。
 * 两者都是独立维度，不破坏顶部 ModelSelector 切换全局（activeConfigId）的行为：
 * 仅当会话显式绑定了配置时才覆盖全局，否则照常回落全局。
 */
object ApiConfigResolver {

    /**
     * 解析某会话应生效的 [ApiConfig]。
     *
     * 优先级：
     * 1. [session] 非空且 `session.apiBindingId` 非空，并从 [configList] 中命中对应配置 → 使用该绑定配置；
     * 2. 否则回退全局 [globalConfigId] 对应的配置；
     * 3. 全局也未命中 → 返回 null（由调用方再兜底默认配置）。
     */
    fun resolveApiConfigForSession(
        session: ChatSession?,
        globalConfigId: String,
        configList: List<ApiConfig>
    ): ApiConfig? {
        val boundId = session?.apiBindingId?.takeIf(String::isNotBlank)
        if (boundId != null) {
            val bound = configList.find { it.id == boundId }
            if (bound != null) return bound
        }
        return configList.find { it.id == globalConfigId }
    }

    /**
     * 解析群聊上下文选角请求应使用的 [ApiConfig]。
     *
     * 优先级：
     * 1. [session] 非空且 `session.speakerApiBindingId` 非空，并从 [configList] 中命中 → 使用选角专用绑定配置；
     * 2. 否则（无选角绑定或绑定配置缺失）回退会话生效 API（即 [resolveApiConfigForSession] 结果）。
     */
    fun resolveSpeakerApiConfig(
        session: ChatSession?,
        globalConfigId: String,
        configList: List<ApiConfig>
    ): ApiConfig? {
        val speakerBoundId = session?.speakerApiBindingId?.takeIf(String::isNotBlank)
        if (speakerBoundId != null) {
            val bound = configList.find { it.id == speakerBoundId }
            if (bound != null) return bound
        }
        return resolveApiConfigForSession(session, globalConfigId, configList)
    }
}