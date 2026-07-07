package com.hfad.mantou.data.logging

import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * 给所有 OkHttpClient 共用的拦截器:把请求/响应概要写入 [ApiLogStore]。
 *
 * 注意:
 * - 流式响应(SSE)不会消费 body,只记 status + 占位文案,避免阻塞调用方。
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
        val startedAt = System.currentTimeMillis()

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
                    url = request.url.toString(),
                    isStream = isStream,
                    httpStatus = null,
                    durationMs = duration,
                    requestBody = requestBodyText,
                    responseBody = "",
                    errorMessage = "网络错误: ${e.message ?: e.javaClass.simpleName}"
                )
            )
            throw e
        }

        val duration = System.currentTimeMillis() - startedAt
        val responseBodyText = if (isStream) {
            "(流式响应，正文未保留)"
        } else {
            readResponseBody(response)
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
                url = request.url.toString(),
                isStream = isStream,
                httpStatus = response.code,
                durationMs = duration,
                requestBody = requestBodyText,
                responseBody = responseBodyText,
                errorMessage = errMsg
            )
        )
        return response
    }

    private fun readRequestBody(request: okhttp3.Request): String {
        val body = request.body ?: return ""
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val charset = body.contentType()?.charset() ?: Charsets.UTF_8
            buffer.readString(minOf(buffer.size, MAX_BODY_BYTES), charset)
        } catch (e: Exception) {
            "(无法读取请求体: ${e.message})"
        }
    }

    private fun readResponseBody(response: Response): String {
        return try {
            val peeked = response.peekBody(MAX_BODY_BYTES)
            val text = peeked.string()
            if (text.length > PREVIEW_LIMIT) text.substring(0, PREVIEW_LIMIT) + "\n…(已截断)"
            else text
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
