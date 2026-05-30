package com.hfad.mantou.data.api

import com.hfad.mantou.data.database.ProviderEntity

/**
 * 单次聊天调用所用的"动态配置"。
 *
 * 由 ChatViewModel 根据当前 ActiveModelStore + ProviderRepository 解析得到。
 */
data class ChatCallConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val apiFormat: String  // ProviderEntity.API_FORMAT_OPENAI / ANTHROPIC
) {
    val isAnthropic: Boolean get() = apiFormat == ProviderEntity.API_FORMAT_ANTHROPIC
}
