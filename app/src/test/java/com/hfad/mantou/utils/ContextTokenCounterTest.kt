package com.hfad.mantou.utils

import com.hfad.mantou.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun estimateChatMessagesCountsGeneratedHtmlContent() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Todo</title></head>
            <body><button id="add">Add task</button></body>
            </html>
        """.trimIndent()
        val htmlFile = File.createTempFile("mantou_context_counter", ".html").apply {
            writeText(html)
            deleteOnExit()
        }
        val message = ChatMessage(
            messageId = 1L,
            role = ChatMessage.ROLE_ASSISTANT,
            content = "已为你生成网页应用，点击下方预览或全屏查看",
            isStreaming = false,
            appHtmlPath = htmlFile.absolutePath
        )
        val messageWithoutHtml = message.copy(appHtmlPath = null)

        val withoutHtml = ContextTokenCounter.estimateChatMessages(listOf(messageWithoutHtml))
        val withHtml = ContextTokenCounter.estimateChatMessages(listOf(message))

        assertTrue(withHtml > withoutHtml + ContextTokenCounter.estimateText(html))
    }
}
