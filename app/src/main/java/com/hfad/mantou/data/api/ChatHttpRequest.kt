package com.hfad.mantou.data.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.hfad.mantou.data.logging.ApiRequestTrace
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object ChatHttpRequest {
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val parameterCompatibility = ChatParameterCompatibility()

    fun build(config: ChatCallConfig, request: ChatRequest): Request {
        val url = if (config.isAnthropic) {
            ApiEndpointResolver.anthropicMessagesUrl(config.baseUrl)
        } else {
            ApiEndpointResolver.openAiChatCompletionsUrl(config.baseUrl)
        }
        val modelRequest = request.copy(model = config.model)
        val builder = Request.Builder()
            .url(url)
            .tag(ApiRequestTrace::class.java, ApiRequestTrace.create(request.diagnosticContext))

        if (request.stream) {
            builder.header("Accept", "text/event-stream")
        }
        if (config.isAnthropic) {
            builder.header("anthropic-version", ANTHROPIC_VERSION)
            if (config.apiKey.isNotEmpty()) builder.header("x-api-key", config.apiKey)
            val endpoint = url.toHttpUrl()
            val cacheSystemPrompt = request.cacheSystemPrompt && endpoint.isHttps && endpoint.host == "api.anthropic.com"
            return builder.post(buildAnthropicBody(modelRequest, cacheSystemPrompt).toString().toRequestBody(jsonMediaType)).build()
        }
        if (config.apiKey.isNotEmpty()) builder.header("Authorization", "Bearer ${config.apiKey}")

        val json = gson.toJsonTree(modelRequest).asJsonObject
        if (url.toHttpUrl().host == "api.openai.com") {
            json.add("max_completion_tokens", json.remove("max_tokens"))
        }
        return builder.postOpenAiBody(json).build()
    }

    internal fun Request.Builder.postOpenAiBody(json: JsonObject): Request.Builder =
        tag(OpenAiChatBody::class.java, OpenAiChatBody(json))
            .post(json.toString().toRequestBody(jsonMediaType))

    internal data class OpenAiChatBody(val json: JsonObject)

    private fun buildAnthropicBody(request: ChatRequest, cacheSystemPrompt: Boolean): JsonObject {
        val root = JsonObject()
        root.addProperty("model", request.model)
        root.addProperty("max_tokens", request.maxTokens)
        root.addProperty("stream", request.stream)

        val systemTexts = mutableListOf<String>()
        val messagesArr = JsonArray()

        request.messages.forEach { msg ->
            when (msg.role) {
                "system" -> {
                    extractText(msg.content)?.let { systemTexts.add(it) }
                }

                "user", "assistant" -> {
                    messagesArr.add(convertAnthropicMessage(msg))
                }
            }
        }
        if (systemTexts.isNotEmpty()) {
            val systemText = systemTexts.joinToString("\n\n")
            if (cacheSystemPrompt && systemText.isNotBlank()) {
                val block = JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", systemText)
                    add("cache_control", JsonObject().apply { addProperty("type", "ephemeral") })
                }
                root.add("system", JsonArray().apply { add(block) })
            } else {
                root.addProperty("system", systemText)
            }
        }
        root.add("messages", messagesArr)
        return root
    }

    private fun convertAnthropicMessage(msg: ApiMessage): JsonObject {
        val obj = JsonObject()
        obj.addProperty("role", msg.role)
        val content = msg.content
        if (content is String) {
            obj.addProperty("content", content)
        } else if (content is List<*>) {
            val blocks = JsonArray()
            content.forEach { part ->
                if (part is ContentPart) {
                    when (part.type) {
                        "text" -> {
                            val block = JsonObject()
                            block.addProperty("type", "text")
                            block.addProperty("text", part.text.orEmpty())
                            blocks.add(block)
                        }

                        "image_url" -> {
                            val rawUrl = part.imageUrl?.url.orEmpty()
                            val block = anthropicImageBlock(rawUrl)
                            if (block != null) {
                                blocks.add(block)
                            }
                        }
                    }
                }
            }
            obj.add("content", blocks)
        } else {
            obj.addProperty("content", content.toString())
        }
        return obj
    }

    private fun anthropicImageBlock(rawUrl: String): JsonObject? {
        if (!rawUrl.startsWith("data:")) return null
        val commaIndex = rawUrl.indexOf(',')
        if (commaIndex <= 5) return null
        val meta = rawUrl.substring(5, commaIndex)
        val mediaType = meta.substringBefore(';').ifEmpty { "image/jpeg" }
        val data = rawUrl.substring(commaIndex + 1)
        val block = JsonObject()
        block.addProperty("type", "image")
        val source = JsonObject()
        source.addProperty("type", "base64")
        source.addProperty("media_type", mediaType)
        source.addProperty("data", data)
        block.add("source", source)
        return block
    }

    private fun extractText(content: Any?): String? {
        return when (content) {
            is String -> content
            is List<*> -> content
                .filterIsInstance<ContentPart>()
                .mapNotNull { it.text }
                .joinToString("\n")
                .ifEmpty { null }

            else -> null
        }
    }

}
