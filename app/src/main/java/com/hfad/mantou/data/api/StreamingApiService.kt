package com.hfad.mantou.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hfad.mantou.data.logging.ApiLoggingInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

object StreamingApiService {

    private const val anthropicVersion = "2023-06-01"
    private const val jsonMediaType = "application/json; charset=utf-8"

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(ApiLoggingInterceptor())
        .build()

    fun streamChatCompletion(
        config: ChatCallConfig,
        request: ChatRequest
    ): Flow<StreamEvent> = if (config.isAnthropic) {
        streamAnthropic(config, request)
    } else {
        streamOpenAi(config, request)
    }

    private fun streamOpenAi(
        config: ChatCallConfig,
        request: ChatRequest
    ): Flow<StreamEvent> = callbackFlow {
        val url = runCatching {
            ApiEndpointResolver.openAiChatCompletionsUrl(config.baseUrl)
        }.getOrElse {
            trySend(StreamEvent.Error(it.message ?: "Base URL 无效"))
            close()
            return@callbackFlow
        }
        val streamRequest = request.copy(model = config.model, stream = true)
        val requestBody = gson.toJson(streamRequest)
            .toRequestBody(jsonMediaType.toMediaType())

        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
        if (config.apiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }

        val call = client.newCall(builder.build())
        call.enqueue(
            createStreamingCallback(
                call = call,
                onEachLine = ::processOpenAiLine
            )
        )
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun streamAnthropic(
        config: ChatCallConfig,
        request: ChatRequest
    ): Flow<StreamEvent> = callbackFlow {
        val url = runCatching {
            ApiEndpointResolver.anthropicMessagesUrl(config.baseUrl)
        }.getOrElse {
            trySend(StreamEvent.Error(it.message ?: "Base URL 无效"))
            close()
            return@callbackFlow
        }
        val requestBody = buildAnthropicBody(config.model, request)
            .toString()
            .toRequestBody(jsonMediaType.toMediaType())

        val builder = Request.Builder()
            .url(url)
            .addHeader("anthropic-version", anthropicVersion)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
        if (config.apiKey.isNotEmpty()) {
            builder.addHeader("x-api-key", config.apiKey)
        }

        val call = client.newCall(builder.build())
        call.enqueue(
            createStreamingCallback(
                call = call,
                onEachLine = ::processAnthropicLine
            )
        )
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.createStreamingCallback(
        call: Call,
        onEachLine: (String) -> StreamEvent?
    ): Callback {
        return object : Callback {
            private var streamStarted = false
            private var sawDone = false
            private var disconnectionReported = false

            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    close()
                    return
                }

                val message = "网络状态出错: ${e.message ?: "连接已断开"}"
                if (streamStarted) {
                    trySend(StreamEvent.Disconnected(message))
                } else {
                    trySend(StreamEvent.Error(message))
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty().take(200)
                    trySend(StreamEvent.Error("请求失败 (${response.code}) $errBody"))
                    response.close()
                    close()
                    return
                }

                streamStarted = true
                trySend(StreamEvent.Start)

                try {
                    response.body?.charStream()?.buffered()?.useLines { lines ->
                        lines.forEach { line ->
                            val event = onEachLine(line) ?: return@forEach
                            if (event is StreamEvent.Done) {
                                sawDone = true
                            }
                            trySend(event)
                            if (event is StreamEvent.Done) {
                                return@useLines
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (!call.isCanceled()) {
                        disconnectionReported = true
                        trySend(
                            StreamEvent.Disconnected(
                                "网络状态出错: ${e.message ?: "连接已断开"}"
                            )
                        )
                    }
                } finally {
                    response.close()
                    if (!call.isCanceled() && streamStarted && !sawDone && !disconnectionReported) {
                        trySend(StreamEvent.Disconnected("网络状态出错: 连接已断开"))
                    }
                    close()
                }
            }
        }
    }

    private fun buildAnthropicBody(model: String, request: ChatRequest): JsonObject {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("max_tokens", request.maxTokens)
        root.addProperty("temperature", request.temperature)
        root.addProperty("stream", true)

        val systemTexts = mutableListOf<String>()
        val messagesArr = com.google.gson.JsonArray()

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
            root.addProperty("system", systemTexts.joinToString("\n\n"))
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
            val blocks = com.google.gson.JsonArray()
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
            obj.addProperty("content", content?.toString().orEmpty())
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

    @Suppress("UNCHECKED_CAST")
    private fun extractText(content: Any?): String? {
        return when (content) {
            is String -> content
            is List<*> -> (content as List<Any?>)
                .filterIsInstance<ContentPart>()
                .mapNotNull { it.text }
                .joinToString("\n")
                .ifEmpty { null }

            else -> null
        }
    }

    private fun processOpenAiLine(line: String): StreamEvent? {
        if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamEvent.Done
        return try {
            val chunk = gson.fromJson(data, StreamChunk::class.java)
            val delta = chunk.choices?.firstOrNull()?.delta ?: return null
            delta.reasoningContent?.let { return StreamEvent.Thinking(it) }
            delta.content?.let { StreamEvent.Content(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun processAnthropicLine(line: String): StreamEvent? {
        if (line.isBlank() || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        return try {
            val obj = JsonParser.parseString(data).asJsonObject
            when (obj.get("type")?.asString) {
                "content_block_delta" -> {
                    val delta = obj.getAsJsonObject("delta") ?: return null
                    when (delta.get("type")?.asString) {
                        "text_delta" -> delta.get("text")?.asString?.let { StreamEvent.Content(it) }
                        "thinking_delta" -> {
                            delta.get("thinking")?.asString?.let { StreamEvent.Thinking(it) }
                        }

                        else -> null
                    }
                }

                "message_stop" -> StreamEvent.Done
                "error" -> {
                    val message = obj.getAsJsonObject("error")?.get("message")?.asString
                    StreamEvent.Error(message ?: "Anthropic 流式错误")
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    sealed class StreamEvent {
        object Start : StreamEvent()
        data class Thinking(val text: String) : StreamEvent()
        data class Content(val text: String) : StreamEvent()
        object Done : StreamEvent()
        data class Disconnected(val message: String) : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}
