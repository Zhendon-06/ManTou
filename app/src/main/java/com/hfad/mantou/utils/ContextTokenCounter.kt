package com.hfad.mantou.utils

import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ContentPart
import kotlin.math.roundToInt

object ContextTokenCounter {
    private const val CHARS_PER_TOKEN = 2.2f
    private const val MESSAGE_OVERHEAD_TOKENS = 4
    const val IMAGE_TOKENS = 1024

    fun estimateText(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).roundToInt().coerceAtLeast(1)
    }

    fun estimateChatMessages(
        messages: List<ChatMessage>,
        draftText: String = "",
        selectedImageCount: Int = 0,
        systemPrompt: String? = null
    ): Int {
        var total = if (systemPrompt.isNullOrBlank()) {
            0
        } else {
            MESSAGE_OVERHEAD_TOKENS + estimateText(systemPrompt)
        }
        messages.asSequence()
            .filterNot { it.isStreaming }
            .forEach { message ->
                total += MESSAGE_OVERHEAD_TOKENS + estimateText(ChatContextFormatter.contentForContext(message))
                if (!message.imagePath.isNullOrBlank()) {
                    total += IMAGE_TOKENS
                }
            }

        if (draftText.isNotBlank()) {
            total += MESSAGE_OVERHEAD_TOKENS + estimateText(draftText)
        }
        total += selectedImageCount * IMAGE_TOKENS
        return total.coerceAtLeast(0)
    }

    fun estimateApiMessages(messages: List<ApiMessage>): Int {
        return messages.sumOf { message ->
            MESSAGE_OVERHEAD_TOKENS + estimateContent(message.content)
        }.coerceAtLeast(0)
    }

    private fun estimateContent(content: Any?): Int {
        return when (content) {
            null -> 0
            is String -> estimateText(content)
            is List<*> -> content.sumOf { part ->
                when (part) {
                    is ContentPart -> when (part.type) {
                        "image_url" -> IMAGE_TOKENS
                        else -> estimateText(part.text)
                    }
                    else -> estimateText(part?.toString())
                }
            }
            else -> estimateText(content.toString())
        }
    }
}
