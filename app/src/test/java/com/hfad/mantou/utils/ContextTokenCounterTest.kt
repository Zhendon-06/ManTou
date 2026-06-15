package com.hfad.mantou.utils

import com.hfad.mantou.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTokenCounterTest {

    @Test
    fun estimateChatMessagesIgnoresStreamingProgress() {
        val persisted = ChatMessage(
            messageId = 1L,
            role = ChatMessage.ROLE_USER,
            content = "hello",
            isStreaming = false
        )
        val streaming = ChatMessage(
            messageId = -1L,
            role = ChatMessage.ROLE_ASSISTANT,
            content = "generating",
            thinking = "x".repeat(20_000),
            isStreaming = true
        )

        val withoutStreaming = ContextTokenCounter.estimateChatMessages(listOf(persisted))
        val withStreaming = ContextTokenCounter.estimateChatMessages(listOf(persisted, streaming))

        assertEquals(withoutStreaming, withStreaming)
    }

    @Test
    fun estimateChatMessagesCountsDraftAndImages() {
        val tokens = ContextTokenCounter.estimateChatMessages(
            messages = emptyList(),
            draftText = "hello world",
            selectedImageCount = 2
        )

        assertTrue(tokens >= ContextTokenCounter.IMAGE_TOKENS * 2)
    }
}
