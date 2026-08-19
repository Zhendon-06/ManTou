package com.hfad.mantou.data.logging

import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import java.io.IOException

/**
 * 给所有 OkHttpClient 共用的拦截器:把请求/响应概要写入 [ApiLogStore]。
 *
 * 注意:
 * - 成功的流式响应(SSE)不会消费 body,只记 status + 占位文案,避免阻塞调用方。
 * - 非 2xx 响应会通过 peekBody 保留错误正文,即使原请求是流式请求。
 * - 非流式响应通过 peekBody 复制最多 [MAX_BODY_BYTES] 字节,不影响真实读取。
 * - 网络异常不抛出,记录后继续 throw 原 IOException。
 */
class ApiLoggingInterceptor : Interceptor {

    companion object {
        private const val MAX_BODY_BYTES = 64L * 1024L
        private const val PREVIEW_LIMIT = 32 * 1024
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val trace = request.tag(ApiRequestTrace::class.java) ?: ApiRequestTrace.create()
        val startedAt = trace.startedAtMs

        val requestBodyText = readRequestBody(request)
        val model = parseModel(requestBodyText)
        val isStream = isStreamRequest(request, requestBodyText)
        val (provider, endpointLabel) = classify(request.url.encodedPath, isStream)

        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            val duration = System.currentTimeMillis() - startedAt
            ApiLogStore.append(
                ApiLogEntry(
                    id = ApiLogStore.nextId(),
                    timestampMs = startedAt,
                    provider = provider,
                    endpointLabel = endpointLabel,
                    model = model,
                    method = request.method,
                    url = ApiLogRedactor.redactUrl(request.url),
                    isStream = isStream,
                    httpStatus = null,
                    durationMs = duration,
                    requestBody = requestBodyText,
                    responseBody = "",
                    errorMessage = "网络错误: ${e.message ?: e.javaClass.simpleName}",
                    traceId = trace.id,
                    retryable = true,
                    streamCompleted = if (isStream) false else null,
                    runId = trace.context?.runId,
                    operation = trace.context?.operation,
                    iteration = trace.context?.iteration
                )
            )
            throw e
        }

        val duration = System.currentTimeMillis() - startedAt
        val responseBodyText = if (isStream && response.isSuccessful) {
            "(流式响应，正文未保留)"
        } else {
            readResponseBody(response)
        }
        val upstreamRequestId = ApiFailureDiagnostics.extractRequestId(
            headers = response.headers,
            body = responseBodyText
        )
        val retryable = if (response.isSuccessful) {
            null
        } else {
            ApiFailureDiagnostics.isRetryable(response.code, responseBodyText)
        }
        val errMsg = if (!response.isSuccessful) {
            "HTTP ${response.code}"
        } else null

        ApiLogStore.append(
            ApiLogEntry(
                id = ApiLogStore.nextId(),
                timestampMs = startedAt,
                provider = provider,
                endpointLabel = endpointLabel,
                model = model,
                method = request.method,
                url = ApiLogRedactor.redactUrl(request.url),
                isStream = isStream,
                httpStatus = response.code,
                durationMs = duration,
                requestBody = requestBodyText,
                responseBody = responseBodyText,
                errorMessage = errMsg,
                traceId = trace.id,
                upstreamRequestId = upstreamRequestId,
                retryable = retryable,
                streamCompleted = if (isStream) false else null,
                runId = trace.context?.runId,
                operation = trace.context?.operation,
                iteration = trace.context?.iteration
            )
        )
        return response
    }

    private fun readRequestBody(request: okhttp3.Request): String {
        val body = request.body ?: return ""
        if (body.isDuplex() || body.isOneShot()) {
            return "(一次性或双工请求体未记录)"
        }
        val contentLength = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (contentLength > MAX_BODY_BYTES) {
            return "(请求体超过日志上限，${contentLength}字节未记录)"
        }
        val contentType = body.contentType()
        if (contentType != null && !isTextContentType(contentType.type, contentType.subtype)) {
            return if (contentLength >= 0) {
                "(二进制请求体未记录，共 $contentLength 字节)"
            } else {
                "(二进制请求体未记录)"
            }
        }
        return try {
            val buffer = Buffer()
            var truncated = false
            val cappedSink = object : ForwardingSink(buffer) {
                private var capturedBytes = 0L

                override fun write(source: Buffer, byteCount: Long) {
                    val writableBytes = minOf(byteCount, MAX_BODY_BYTES - capturedBytes)
                    if (writableBytes > 0) {
                        super.write(source, writableBytes)
                        capturedBytes += writableBytes
                    }
                    val skippedBytes = byteCount - writableBytes
                    if (skippedBytes > 0) {
                        source.skip(skippedBytes)
                        truncated = true
                    }
                }
            }.buffer()
            body.writeTo(cappedSink)
            cappedSink.flush()
            val charset = contentType?.charset() ?: Charsets.UTF_8
            val text = ApiLogRedactor.redactBody(buffer.readString(buffer.size, charset))
            if (truncated) "$text\n…(请求体已截断)" else text
        } catch (e: Exception) {
            "(无法读取请求体: ${e.message})"
        }
    }

    private fun isTextContentType(type: String, subtype: String): Boolean {
        if (type.equals("text", ignoreCase = true)) return true
        if (!type.equals("application", ignoreCase = true)) return false
        return subtype.contains("json", ignoreCase = true) ||
            subtype.contains("xml", ignoreCase = true) ||
            subtype.contains("x-www-form-urlencoded", ignoreCase = true) ||
            subtype.contains("graphql", ignoreCase = true)
    }

    private fun readResponseBody(response: Response): String {
        return try {
            val peeked = response.peekBody(MAX_BODY_BYTES)
            val text = peeked.string()
            val preview = if (text.length > PREVIEW_LIMIT) {
                text.substring(0, PREVIEW_LIMIT) + "\n…(已截断)"
            } else {
                text
            }
            ApiLogRedactor.redactBody(preview)
        } catch (e: Exception) {
            "(无法读取响应体: ${e.message})"
        }
    }

    private fun parseModel(requestBody: String): String? {
        if (requestBody.isBlank() || !requestBody.trimStart().startsWith("{")) return null
        return try {
            val obj = JsonParser.parseString(requestBody).asJsonObject
            obj.get("model")?.takeIf { it.isJsonPrimitive }?.asString
        } catch (e: Exception) {
            null
        }
    }

    private fun isStreamRequest(request: okhttp3.Request, body: String): Boolean {
        val accept = request.header("Accept").orEmpty()
        if (accept.contains("event-stream", ignoreCase = true)) return true
        if (body.isBlank()) return false
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 用 URL path 粗分类。完全照抄 path 关键词,够看就行。
     */
    private fun classify(path: String, isStream: Boolean): Pair<String, String> {
        val streamSuffix = if (isStream) ".stream" else ""
        return when {
            path.contains("/messages") -> "Anthropic" to "anthropic/messages$streamSuffix"
            path.contains("/chat/completions") -> "OpenAI" to "openai/chat.completions$streamSuffix"
            path.contains("/audio/transcriptions") -> "OpenAI" to "openai/audio.transcriptions"
            path.endsWith("/models") -> "OpenAI" to "openai/models"
            else -> "其它" to path.trimStart('/')
        }
    }
}
