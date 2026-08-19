package com.hfad.mantou.data.logging

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.Headers

object ApiFailureDiagnostics {

    private val retryableStatusCodes = setOf(408, 409, 425, 429)
    private val requestIdHeaders = listOf(
        "x-request-id",
        "request-id",
        "openai-request-id",
        "x-amzn-requestid"
    )
    private val requestIdKeys = listOf(
        "request_ori_id",
        "request_id",
        "requestId"
    )
    private val statusCodePattern = Regex("(?i)(?:请求失败\\s*\\(|http\\s+)(\\d{3})")
    private val requestIdPattern = Regex(
        "(?i)[\\\"']?(?:request_ori_id|request_id|requestId)[\\\"']?\\s*[:=]\\s*[\\\"']?([A-Za-z0-9._:-]{6,})"
    )

    fun isRetryable(httpStatus: Int?, message: String): Boolean {
        val resolvedStatus = httpStatus ?: parseStatusCode(message)
        if (resolvedStatus != null) {
            return resolvedStatus in retryableStatusCodes || resolvedStatus in 500..599
        }

        val normalized = message.lowercase()
        return message.contains("网络状态出错") ||
            message.contains("网络请求超时") ||
            message.contains("请求超时") ||
            message.contains("连接已断开") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out") ||
            normalized.contains("connection reset") ||
            normalized.contains("unexpected end of stream") ||
            normalized.contains("upstream_error") ||
            normalized.contains("upstream request failed") ||
            normalized.contains("overloaded")
    }

    fun extractRequestId(headers: Headers, body: String): String? {
        requestIdHeaders.forEach { name ->
            headers[name]?.normalizeRequestId()?.let { return it }
        }
        return extractRequestId(body)
    }

    fun extractRequestId(body: String): String? {
        if (body.isBlank()) return null
        runCatching { JsonParser.parseString(body) }
            .getOrNull()
            ?.let(::findRequestId)
            ?.let { return it }
        return requestIdPattern.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.normalizeRequestId()
    }

    private fun parseStatusCode(message: String): Int? {
        return statusCodePattern.find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun findRequestId(element: JsonElement): String? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            requestIdKeys.forEach { key ->
                obj.get(key)
                    ?.takeIf(JsonElement::isJsonPrimitive)
                    ?.asString
                    ?.normalizeRequestId()
                    ?.let { return it }
            }
            obj.entrySet().forEach { (_, value) ->
                findRequestId(value)?.let { return it }
            }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { value ->
                findRequestId(value)?.let { return it }
            }
        }
        return null
    }

    private fun String.normalizeRequestId(): String? {
        return trim().take(MAX_REQUEST_ID_CHARS).ifBlank { null }
    }

    private const val MAX_REQUEST_ID_CHARS = 256
}
