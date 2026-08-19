package com.hfad.mantou.data.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hfad.mantou.utils.ContextTokenCounter

data class ModelTokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val estimated: Boolean
)

internal object ModelTokenUsageResolver {

    fun resolve(
        responseBody: String,
        requestTexts: List<String>,
        responseText: String
    ): ModelTokenUsage {
        val estimatedInputTokens = ContextTokenCounter.estimateApiMessages(
            requestTexts.map { text -> ApiMessage(role = "user", content = text) }
        ).toLong()
        val estimatedOutputTokens = ContextTokenCounter.estimateText(responseText).toLong()
        val usage = runCatching {
            JsonParser.parseString(responseBody)
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("usage")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        }.getOrNull()
        val providerInputTokens = usage?.longValue("prompt_tokens", "input_tokens")
        val providerOutputTokens = usage?.longValue("completion_tokens", "output_tokens")
        val providerTotalTokens = usage?.longValue("total_tokens")
        val inputTokens = providerInputTokens ?: estimatedInputTokens
        val outputTokens = providerOutputTokens ?: estimatedOutputTokens
        val totalTokens = providerTotalTokens ?: (inputTokens + outputTokens)
        val exact = providerTotalTokens != null ||
            (providerInputTokens != null && providerOutputTokens != null)
        return ModelTokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            estimated = !exact
        )
    }

    private fun JsonObject.longValue(vararg names: String): Long? {
        names.forEach { name ->
            val value = get(name) ?: return@forEach
            runCatching { value.asLong }
                .getOrNull()
                ?.takeIf { it >= 0L }
                ?.let { return it }
        }
        return null
    }
}
