package com.hfad.mantou.data

/**
 * 聊天消息 UI 数据类
 */
data class ChatMessage(
    val messageId: Long = 0,
    val role: String,  // "user" 或 "assistant"
    val content: String,
    val imagePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false  // 是否正在流式输出中
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

