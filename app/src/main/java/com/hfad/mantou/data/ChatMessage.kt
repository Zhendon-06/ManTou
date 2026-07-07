package com.hfad.mantou.data

/**
 * 鑱婂ぉ娑堟伅 UI 鏁版嵁绫? */
data class ChatMessage(
    val messageId: Long = 0,
    val role: String,
    val content: String,
    val imagePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = true,
    val appHtmlPath: String? = null,
    val thinking: String? = null,
    val statusText: String? = null,
    val showStatusLoader: Boolean = false
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}


