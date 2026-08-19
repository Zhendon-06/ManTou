package com.hfad.mantou.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hfad.mantou.data.logging.ApiFailureDiagnostics
import com.hfad.mantou.data.logging.ApiLogStore
import com.hfad.mantou.data.logging.ApiLoggingInterceptor
import com.hfad.mantou.data.logging.ApiLogRedactor
import com.hfad.mantou.data.logging.ApiRequestTrace
import com.hfad.mantou.utils.ContextTokenCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
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
import java.io.InterruptedIOException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object StreamingApiService {

    private const val anthropicVersion = "2023-06-01"
    private const val jsonMediaType = "application/json; charset=utf-8"
    private const val maxErrorBodyChars = 4_096
    internal const val STREAM_READ_TIMEOUT_SECONDS = 180L
    internal const val STREAM_CALL_TIMEOUT_MINUTES = 30L

    private val gson = Gson()

    private val client = createClient()

    internal fun createClient(
        readTimeoutSeconds: Long = STREAM_READ_TIMEOUT_SECONDS,
        callTimeoutSeconds: Long = TimeUnit.MINUTES.toSeconds(STREAM_CALL_TIMEOUT_MINUTES)
    ): OkHttpClient {
        require(readTimeoutSeconds > 0)
        require(callTimeoutSeconds > 0)
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(ApiLoggingInterceptor())
            .build()
    }

    fun streamChatCompletion(
        config: ChatCallConfig,
        request: ChatRequest
    ): Flow<StreamEvent> = streamChatCompletion(config, request, client)

    internal fun streamChatCompletion(
        config: ChatCallConfig,
        request: ChatRequest,
        callFactory: Call.Factory
    ): Flow<StreamEvent> = if (config.isAnthropic) {
        streamAnthropic(config, request, callFactory)
    } else {
        streamOpenAi(config, request, callFactory)
    }

    internal fun isRetryableNetworkFailure(message: String): Boolean {
        return ApiFailureDiagnostics.isRetryable(httpStatus = null, message = message)
    }

    internal fun isRetryableFailure(httpStatus: Int?, message: String): Boolean {
        return ApiFailureDiagnostics.isRetryable(httpStatus = httpStatus, message = message)
    }

    private fun streamOpenAi(
        config: ChatCallConfig,
        request: ChatRequest,
        callFactory: Call.Factory
    ): Flow<StreamEvent> = callbackFlow {
        val url = runCatching {
            ApiEndpointResolver.openAiChatCompletionsUrl(config.baseUrl)
        }.getOrElse {
            trySendBlocking(StreamEvent.Error(it.message ?: "Base URL 无效"))
            close()
            return@callbackFlow
        }
        val streamRequest = request.copy(model = config.model, stream = true)
        val tokenUsage = RequestTokenUsageTracker(streamRequest)
        val requestBody = gson.toJson(streamRequest)
            .toRequestBody(jsonMediaType.toMediaType())
        val trace = ApiRequestTrace.create(request.diagnosticContext)

        val builder = Request.Builder()
            .url(url)
            .tag(ApiRequestTrace::class.java, trace)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
        if (config.apiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }

        val call = callFactory.newCall(builder.build())
        call.enqueue(
            createStreamingCallback(
                call = call,
                tokenUsage = tokenUsage,
                onEachLine = { line -> processOpenAiLine(line, tokenUsage) }
            )
        )
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun streamAnthropic(
        config: ChatCallConfig,
        request: ChatRequest,
        callFactory: Call.Factory
    ): Flow<StreamEvent> = callbackFlow {
        val url = runCatching {
            ApiEndpointResolver.anthropicMessagesUrl(config.baseUrl)
        }.getOrElse {
            trySendBlocking(StreamEvent.Error(it.message ?: "Base URL 无效"))
            close()
            return@callbackFlow
        }
        val requestBody = buildAnthropicBody(config.model, request)
            .toString()
            .toRequestBody(jsonMediaType.toMediaType())
        val tokenUsage = RequestTokenUsageTracker(request)
        val trace = ApiRequestTrace.create(request.diagnosticContext)

        val builder = Request.Builder()
            .url(url)
            .tag(ApiRequestTrace::class.java, trace)
            .addHeader("anthropic-version", anthropicVersion)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
        if (config.apiKey.isNotEmpty()) {
            builder.addHeader("x-api-key", config.apiKey)
        }

        val call = callFactory.newCall(builder.build())
        call.enqueue(
            createStreamingCallback(
                call = call,
                tokenUsage = tokenUsage,
                onEachLine = { line -> processAnthropicLine(line, tokenUsage) }
            )
        )
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.createStreamingCallback(
        call: Call,
        tokenUsage: RequestTokenUsageTracker,
        onEachLine: (String) -> StreamEvent?
    ): Callback {
        return object : Callback {
            private val trace = call.request().tag(ApiRequestTrace::class.java)
            private var streamStarted = false
            private var sawDone = false
            private var disconnectionReported = false

            override fun onFailure(call: Call, e: IOException) {
                val timeoutFailure = isTimeoutFailure(e)
                if (call.isCanceled() && !timeoutFailure) {
                    markStreamCanceled()
                    tokenUsage.reportIfConsumed(streamStarted)
                    close()
                    return
                }

                val message = buildNetworkFailureMessage(e, streamStarted)
                markStreamFailed(message)
                tokenUsage.reportIfConsumed(streamStarted)
                if (streamStarted) {
                    trySendBlocking(
                        StreamEvent.Disconnected(
                            message = message,
                            diagnosticId = trace?.id
                        )
                    )
                } else {
                    trySendBlocking(
                        StreamEvent.Error(
                            message = message,
                            retryable = true,
                            diagnosticId = trace?.id
                        )
                    )
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    try {
                        val errBody = runCatching {
                            readErrorBody(response)
                        }.fold(
                            onSuccess = ApiLogRedactor::redactBody,
                            onFailure = { error ->
                                "(读取错误响应失败: ${error.message ?: error.javaClass.simpleName})"
                            }
                        )
                        val upstreamRequestId = ApiFailureDiagnostics.extractRequestId(
                            headers = response.headers,
                            body = errBody
                        )
                        val message = buildHttpErrorMessage(
                            statusCode = response.code,
                            responseMessage = response.message,
                            body = errBody,
                            diagnosticId = trace?.id,
                            upstreamRequestId = upstreamRequestId
                        )
                        trySendBlocking(
                            StreamEvent.Error(
                                message = message,
                                httpStatus = response.code,
                                retryable = isRetryableFailure(response.code, errBody),
                                diagnosticId = trace?.id,
                                upstreamRequestId = upstreamRequestId,
                                bodyPreview = errBody
                            )
                        )
                    } finally {
                        response.close()
                        close()
                    }
                    return
                }

                streamStarted = true
                trySendBlocking(StreamEvent.Start)

                try {
                    response.body?.charStream()?.buffered()?.useLines { lines ->
                        lines.forEach { line ->
                            val event = onEachLine(line)
                                ?.withDiagnosticId(trace?.id)
                                ?: return@forEach
                            if (event is StreamEvent.Done) {
                                sawDone = true
                            }
                            tokenUsage.record(event)
                            if (event is StreamEvent.Error) {
                                disconnectionReported = true
                                markStreamFailed(
                                    message = event.message,
                                    retryable = event.retryable,
                                    upstreamRequestId = event.upstreamRequestId,
                                    bodyPreview = event.bodyPreview
                                )
                            }
                            trySendBlocking(event)
                            if (event is StreamEvent.Done || event is StreamEvent.Error) {
                                return@useLines
                            }
                        }
                    }
                } catch (e: IOException) {
                    val timeoutFailure = isTimeoutFailure(e)
                    if (call.isCanceled() && !timeoutFailure) {
                        markStreamCanceled()
                    } else {
                        disconnectionReported = true
                        val message = buildNetworkFailureMessage(e, streamStarted)
                        markStreamFailed(message)
                        trySendBlocking(
                            StreamEvent.Disconnected(
                                message = message,
                                diagnosticId = trace?.id
                            )
                        )
                    }
                } finally {
                    response.close()
                    tokenUsage.reportIfConsumed(streamStarted)
                    if (sawDone) {
                        markStreamCompleted()
                    } else if (call.isCanceled()) {
                        markStreamCanceled()
                    } else if (!call.isCanceled() && streamStarted && !disconnectionReported) {
                        val message = "网络状态出错: 连接已断开"
                        markStreamFailed(message)
                        trySendBlocking(
                            StreamEvent.Disconnected(
                                message = message,
                                diagnosticId = trace?.id
                            )
                        )
                    }
                    close()
                }
            }

            private fun markStreamCompleted() {
                val requestTrace = trace ?: return
                ApiLogStore.update(requestTrace.id) { entry ->
                    entry.copy(
                        durationMs = System.currentTimeMillis() - requestTrace.startedAtMs,
                        responseBody = "(流式响应已完成，正文未保留)",
                        streamCompleted = true,
                        canceled = false
                    )
                }
            }

            private fun markStreamFailed(
                message: String,
                retryable: Boolean = true,
                upstreamRequestId: String? = null,
                bodyPreview: String? = null
            ) {
                val requestTrace = trace ?: return
                ApiLogStore.update(requestTrace.id) { entry ->
                    entry.copy(
                        durationMs = System.currentTimeMillis() - requestTrace.startedAtMs,
                        responseBody = if (streamStarted) {
                            bodyPreview ?: "(流式响应返回错误或中断，正文未保留)"
                        } else {
                            entry.responseBody
                        },
                        errorMessage = message,
                        upstreamRequestId = upstreamRequestId ?: entry.upstreamRequestId,
                        retryable = retryable,
                        streamCompleted = false,
                        canceled = false
                    )
                }
            }

            private fun markStreamCanceled() {
                if (disconnectionReported) return
                val requestTrace = trace ?: return
                ApiLogStore.update(requestTrace.id) { entry ->
                    if (entry.streamCompleted == true) {
                        entry
                    } else {
                        entry.copy(
                            durationMs = System.currentTimeMillis() - requestTrace.startedAtMs,
                            errorMessage = null,
                            retryable = false,
                            streamCompleted = false,
                            canceled = true
                        )
                    }
                }
            }
        }
    }

    private fun StreamEvent.withDiagnosticId(diagnosticId: String?): StreamEvent {
        if (diagnosticId == null) return this
        return when (this) {
            is StreamEvent.Error -> if (this.diagnosticId == null) {
                copy(diagnosticId = diagnosticId)
            } else {
                this
            }

            is StreamEvent.Disconnected -> if (this.diagnosticId == null) {
                copy(diagnosticId = diagnosticId)
            } else {
                this
            }

            else -> this
        }
    }

    private fun buildHttpErrorMessage(
        statusCode: Int,
        responseMessage: String,
        body: String,
        diagnosticId: String?,
        upstreamRequestId: String?
    ): String {
        return buildString {
            append("请求失败 (").append(statusCode).append(")")
            val detail = body.ifBlank { responseMessage }.trim()
            if (detail.isNotBlank()) append(' ').append(detail)
            diagnosticId?.let { append("\n诊断 ID: ").append(it) }
            upstreamRequestId?.let { append("\n上游请求 ID: ").append(it) }
        }
    }

    private fun buildNetworkFailureMessage(error: IOException, streamStarted: Boolean): String {
        val detail = error.message ?: error.javaClass.simpleName
        if (!isTimeoutFailure(error)) return "网络状态出错: $detail"
        val phase = if (streamStarted) {
            "流式响应超过空闲或总时限"
        } else {
            "连接、发送或等待首个响应超过时限"
        }
        return "网络请求超时: $phase（连续 ${STREAM_READ_TIMEOUT_SECONDS} 秒无数据，" +
            "或单次请求超过 ${STREAM_CALL_TIMEOUT_MINUTES} 分钟；$detail）"
    }

    private fun isTimeoutFailure(error: IOException): Boolean {
        return error is SocketTimeoutException ||
            (error is InterruptedIOException &&
                error.message.orEmpty().contains("timeout", ignoreCase = true))
    }

    private fun readErrorBody(response: Response): String {
        val body = response.body ?: return ""
        return body.charStream().use { reader ->
            val preview = StringBuilder()
            val buffer = CharArray(ERROR_BODY_BUFFER_CHARS)
            while (preview.length < maxErrorBodyChars) {
                val remaining = maxErrorBodyChars - preview.length
                val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                preview.append(buffer, 0, read)
            }
            preview.toString()
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

    private fun processOpenAiLine(
        line: String,
        tokenUsage: RequestTokenUsageTracker
    ): StreamEvent? {
        if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamEvent.Done
        return try {
            val obj = JsonParser.parseString(data).asJsonObject
            obj.get("usage")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { usage ->
                    tokenUsage.updateProviderUsage(
                        inputTokens = usage.longValue("prompt_tokens", "input_tokens"),
                        outputTokens = usage.longValue("completion_tokens", "output_tokens"),
                        totalTokens = usage.longValue("total_tokens")
                    )
                }
            obj.getAsJsonObject("error")?.let { error ->
                val message = error.get("message")?.asString ?: "OpenAI 流式错误"
                val type = error.get("type")?.asString
                val detail = listOfNotNull(type, message).joinToString(": ")
                return StreamEvent.Error(
                    message = detail,
                    retryable = isRetryableFailure(httpStatus = null, message = detail),
                    upstreamRequestId = ApiFailureDiagnostics.extractRequestId(data),
                    bodyPreview = ApiLogRedactor.redactBody(data.take(maxErrorBodyChars))
                )
            }
            val chunk = gson.fromJson(obj, StreamChunk::class.java)
            val delta = chunk.choices?.firstOrNull()?.delta ?: return null
            delta.reasoningContent?.let { return StreamEvent.Thinking(it) }
            delta.content?.let { StreamEvent.Content(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun processAnthropicLine(
        line: String,
        tokenUsage: RequestTokenUsageTracker
    ): StreamEvent? {
        if (line.isBlank() || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        return try {
            val obj = JsonParser.parseString(data).asJsonObject
            when (obj.get("type")?.asString) {
                "message_start" -> {
                    obj.getAsJsonObject("message")
                        ?.getAsJsonObject("usage")
                        ?.let { usage ->
                            tokenUsage.updateProviderUsage(
                                inputTokens = usage.longValue("input_tokens"),
                                outputTokens = usage.longValue("output_tokens"),
                                totalTokens = null
                            )
                        }
                    null
                }

                "message_delta" -> {
                    obj.getAsJsonObject("usage")?.let { usage ->
                        tokenUsage.updateProviderUsage(
                            inputTokens = usage.longValue("input_tokens"),
                            outputTokens = usage.longValue("output_tokens"),
                            totalTokens = null
                        )
                    }
                    null
                }

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
                    val error = obj.getAsJsonObject("error")
                    val message = error?.get("message")?.asString ?: "Anthropic 流式错误"
                    val type = error?.get("type")?.asString
                    val detail = listOfNotNull(type, message).joinToString(": ")
                    StreamEvent.Error(
                        message = detail,
                        retryable = isRetryableFailure(httpStatus = null, message = detail),
                        upstreamRequestId = ApiFailureDiagnostics.extractRequestId(data),
                        bodyPreview = ApiLogRedactor.redactBody(data.take(maxErrorBodyChars))
                    )
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private class RequestTokenUsageTracker(request: ChatRequest) {
        private val listener = request.tokenUsageListener
        private val estimatedInputTokens = ContextTokenCounter
            .estimateApiMessages(request.messages)
            .toLong()
        private var outputCharacterCount = 0
        private val reported = AtomicBoolean(false)
        private var providerInputTokens: Long? = null
        private var providerOutputTokens: Long? = null
        private var providerTotalTokens: Long? = null

        fun record(event: StreamEvent) {
            when (event) {
                is StreamEvent.Thinking -> addOutputCharacters(event.text.length)
                is StreamEvent.Content -> addOutputCharacters(event.text.length)
                else -> Unit
            }
        }

        private fun addOutputCharacters(characterCount: Int) {
            outputCharacterCount = (outputCharacterCount.toLong() + characterCount)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

        fun updateProviderUsage(
            inputTokens: Long?,
            outputTokens: Long?,
            totalTokens: Long?
        ) {
            inputTokens?.takeIf { it >= 0 }?.let { value ->
                providerInputTokens = maxOf(providerInputTokens ?: 0L, value)
            }
            outputTokens?.takeIf { it >= 0 }?.let { value ->
                providerOutputTokens = maxOf(providerOutputTokens ?: 0L, value)
            }
            totalTokens?.takeIf { it >= 0 }?.let { value ->
                providerTotalTokens = maxOf(providerTotalTokens ?: 0L, value)
            }
        }

        fun reportIfConsumed(streamStarted: Boolean) {
            if (!streamStarted || !reported.compareAndSet(false, true)) return
            val usageListener = listener ?: return
            val estimatedOutputTokens = ContextTokenCounter
                .estimateTextLength(outputCharacterCount)
                .toLong()
            val inputTokens = providerInputTokens ?: estimatedInputTokens
            val outputTokens = providerOutputTokens ?: estimatedOutputTokens
            val providerTotal = providerTotalTokens
            val totalTokens = providerTotal ?: (inputTokens + outputTokens)
            val hasExactUsage = providerTotal != null ||
                (providerInputTokens != null && providerOutputTokens != null)
            if (totalTokens <= 0L) return
            runCatching {
                usageListener.invoke(
                    ModelTokenUsage(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        totalTokens = totalTokens,
                        estimated = !hasExactUsage
                    )
                )
            }
        }
    }

    private fun JsonObject.longValue(vararg names: String): Long? {
        names.forEach { name ->
            val value = get(name) ?: return@forEach
            runCatching { value.asLong }.getOrNull()?.let { return it }
        }
        return null
    }

    sealed class StreamEvent {
        object Start : StreamEvent()
        data class Thinking(val text: String) : StreamEvent()
        data class Content(val text: String) : StreamEvent()
        object Done : StreamEvent()
        data class Disconnected(
            val message: String,
            val diagnosticId: String? = null
        ) : StreamEvent()

        data class Error(
            val message: String,
            val httpStatus: Int? = null,
            val retryable: Boolean = isRetryableFailure(httpStatus, message),
            val diagnosticId: String? = null,
            val upstreamRequestId: String? = null,
            val bodyPreview: String? = null
        ) : StreamEvent()
    }

    private const val ERROR_BODY_BUFFER_CHARS = 1_024
}
