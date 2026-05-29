package com.hfad.mantou.data

/**
 * 聊天消息 UI 数据类
 */
data class ChatMessage(
    val messageId: Long = 0,
    val role: String,
    val content: String,
    val imagePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = true,
    val appHtmlPath: String? = null,
    val thinking: String? = null
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

