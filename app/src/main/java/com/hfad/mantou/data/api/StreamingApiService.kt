package com.hfad.mantou.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式 API 服务 - 支持 SSE (Server-Sent Events) 流式输出
 *
 * 两种调用入口：
 *  - streamChatCompletion(request)          : 旧路径，沿用 ApiConfig 写死的 DeepSeek/SiliconFlow
 *  - streamChatCompletionDynamic(config,..) : 新路径，根据 ChatCallConfig 动态切换 baseUrl/apiKey/model 与 OpenAI / Anthropic 格式
 */
object StreamingApiService {

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
        val jsonBody = gson.toJson(streamRequest)
        val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType())

        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
        if (config.apiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }

        val call = client.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamEvent.Error("网络错误: ${e.message}"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty().take(200)
                    trySend(StreamEvent.Error("请求失败 (${response.code}) $errBody"))
                    close()
                    return
                }
                trySend(StreamEvent.Start)
                response.body?.charStream()?.buffered()?.useLines { lines ->
                    lines.forEach { line ->
                        processOpenAiLine(line)?.let { event ->
                            trySend(event)
                            if (event is StreamEvent.Done) return@useLines
                        }
                    }
                }
                response.close()
                close()
            }
        })
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
        val bodyJson = buildAnthropicBody(config.model, request)
        val requestBody = bodyJson.toString()
            .toRequestBody(JSON_MEDIA_TYPE.toMediaType())

        val builder = Request.Builder()
            .url(url)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
        if (config.apiKey.isNotEmpty()) {
            builder.addHeader("x-api-key", config.apiKey)
        }

        val call = client.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamEvent.Error("网络错误: ${e.message}"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty().take(200)
                    trySend(StreamEvent.Error("请求失败 (${response.code}) $errBody"))
                    close()
                    return
                }
                trySend(StreamEvent.Start)
                response.body?.charStream()?.buffered()?.useLines { lines ->
                    lines.forEach { line ->
                        processAnthropicLine(line)?.let { event ->
                            trySend(event)
                            if (event is StreamEvent.Done) return@useLines
                        }
                    }
                }
                response.close()
                close()
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    // ===================================================================
    // Anthropic body 构造
    // ===================================================================

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
        if (systemTexts.isNotEmpty()) root.addProperty("system", systemTexts.joinToString("\n\n"))
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
                            val b = JsonObject()
                            b.addProperty("type", "text")
                            b.addProperty("text", part.text.orEmpty())
                            blocks.add(b)
                        }
                        "image_url" -> {
                            val rawUrl = part.imageUrl?.url.orEmpty()
                            val b = anthropicImageBlock(rawUrl)
                            if (b != null) blocks.add(b)
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

    /** OpenAI 的 image_url 通常是 data:image/jpeg;base64,xxx，转成 Anthropic 的 image block。 */
    private fun anthropicImageBlock(rawUrl: String): JsonObject? {
        if (!rawUrl.startsWith("data:")) return null
        val commaIdx = rawUrl.indexOf(',')
        if (commaIdx <= 5) return null
        val meta = rawUrl.substring(5, commaIdx)
        val mediaType = meta.substringBefore(';').ifEmpty { "image/jpeg" }
        val data = rawUrl.substring(commaIdx + 1)
        val b = JsonObject()
        b.addProperty("type", "image")
        val src = JsonObject()
        src.addProperty("type", "base64")
        src.addProperty("media_type", mediaType)
        src.addProperty("data", data)
        b.add("source", src)
        return b
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

    // ===================================================================
    // SSE 解析
    // ===================================================================

    /** OpenAI 兼容: 行格式 "data: {json}" 或 "data: [DONE]" */
    private fun processOpenAiLine(line: String): StreamEvent? {
        if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamEvent.Done
        return try {
            val chunk = gson.fromJson(data, StreamChunk::class.java)
            val delta = chunk.choices?.firstOrNull()?.delta ?: return null
            delta.reasoningContent?.let { return StreamEvent.Thinking(it) }
            delta.content?.let { StreamEvent.Content(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Anthropic: 行格式 "event: xxx" 与 "data: {json}" 交替。
     * 我们只需要解析 data 行，按 json 的 type 字段判断。
     */
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
                        "thinking_delta" -> delta.get("thinking")?.asString?.let { StreamEvent.Thinking(it) }
                        else -> null
                    }
                }
                "message_stop" -> StreamEvent.Done
                "error" -> {
                    val msg = obj.getAsJsonObject("error")?.get("message")?.asString
                    StreamEvent.Error(msg ?: "Anthropic 流式错误")
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 流式事件 */
    sealed class StreamEvent {
        object Start : StreamEvent()
        data class Thinking(val text: String) : StreamEvent()
        data class Content(val text: String) : StreamEvent()
        object Done : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}
