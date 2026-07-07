package com.hfad.mantou.utils

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hfad.mantou.data.api.ApiEndpointResolver
import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.logging.ApiLoggingInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ErrorAnalyzer {

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val SYSTEM_PROMPT = """你是馒头 App 的错误诊断助手。下面是用户调用大模型时出现的原始报错信息。
请用中文向普通用户解释，严格按下面两段格式输出，不要 markdown 代码块、不要多余前后缀：

问题描述：<一两句话说明发生了什么>
解决方案：<给出 1-3 条用户可执行的操作建议>"""

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(ApiLoggingInterceptor())
        .build()

    suspend fun analyze(
        config: ChatCallConfig,
        rawError: String,
        scene: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val userMessage = "场景：$scene\n原始报错：\n$rawError"
            val request = buildRequest(config, userMessage).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext null
                if (!response.isSuccessful) return@withContext null
                parseContent(body, config.isAnthropic)?.takeIf { it.isNotBlank() }?.trim()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildRequest(config: ChatCallConfig, userMessage: String): Request.Builder {
        val requestJson = if (config.isAnthropic) {
            gson.toJson(mapOf(
                "model" to config.model,
                "system" to SYSTEM_PROMPT,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to userMessage)
                ),
                "stream" to false,
                "max_tokens" to 600
            ))
        } else {
            gson.toJson(mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to userMessage)
                ),
                "stream" to false,
                "max_tokens" to 600
            ))
        }

        val requestBody = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(
                if (config.isAnthropic) {
                    ApiEndpointResolver.anthropicMessagesUrl(config.baseUrl)
                } else {
                    ApiEndpointResolver.openAiChatCompletionsUrl(config.baseUrl)
                }
            )
            .addHeader("Content-Type", "application/json")
            .post(requestBody)

        if (config.isAnthropic) {
            builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
            if (config.apiKey.isNotEmpty()) {
                builder.addHeader("x-api-key", config.apiKey)
            }
        } else if (config.apiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        return builder
    }

    private fun parseContent(responseBody: String, isAnthropic: Boolean): String? {
        if (!isAnthropic) {
            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            return chatResponse.choices?.firstOrNull()?.message?.content
        }

        val root = JsonParser.parseString(responseBody)
        if (!root.isJsonObject) return null
        val content = root.asJsonObject.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.joinToString("") { el ->
                if (el.isJsonObject) {
                    el.asJsonObject.get("text")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        .orEmpty()
                } else {
                    ""
                }
            }.ifBlank { null }
            else -> null
        }
    }

    private data class ChatCompletionResponse(
        val choices: List<Choice>? = null
    )

    private data class Choice(
        val message: MessageData? = null
    )

    private data class MessageData(
        val content: String? = null
    )
}
