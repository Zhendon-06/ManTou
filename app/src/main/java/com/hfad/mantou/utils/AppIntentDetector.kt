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

object AppIntentDetector {

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val INTENT_SYSTEM_PROMPT = """你是一个意图识别助手。判断用户的消息是否想要生成一个网页应用（app/小程序/网页/工具/计算器/游戏等）。
用户意图是"生成网页应用"时返回JSON: {"intent":"generate_app"}
用户意图是"普通聊天/提问"时返回JSON: {"intent":"chat"}
只返回JSON，不要返回任何其他内容。"""

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(ApiLoggingInterceptor())
        .build()

    private fun isAppGenerationByKeywords(message: String): Boolean {
        val lower = message.lowercase()
        val actionWords = listOf("生成", "做一个", "做个", "搞一个", "搞个", "来一个", "来个", "弄一个", "弄个", "帮我做", "创建", "制作", "帮我生成", "帮我创建", "写一个", "写个", "generate", "create", "make")
        val appWords = listOf("app", "应用", "网页", "工具", "游戏", "小程序", "计算器", "todo", "天气", "日历", "笔记", "时钟", "秒表", "website", "web app")
        return actionWords.any { lower.contains(it) } && appWords.any { lower.contains(it) }
    }

    suspend fun isAppGenerationIntent(config: ChatCallConfig, userMessage: String): Boolean = withContext(Dispatchers.IO) {
        if (isAppGenerationByKeywords(userMessage)) return@withContext true

        try {
            val builder = buildIntentRequest(config, userMessage)
            client.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body?.string() ?: return@withContext false
                if (!response.isSuccessful) return@withContext false

                val content = parseIntentContent(responseBody, config.isAnthropic)
                    ?: return@withContext false

                content.contains("\"generate_app\"")
            }
        } catch (e: Exception) {
            isAppGenerationByKeywords(userMessage)
        }
    }

    private fun buildIntentRequest(
        config: ChatCallConfig,
        userMessage: String
    ): Request.Builder {
        val requestJson = if (config.isAnthropic) {
            gson.toJson(mapOf(
                "model" to config.model,
                "system" to INTENT_SYSTEM_PROMPT,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to userMessage)
                ),
                "stream" to false,
                "max_tokens" to 400
            ))
        } else {
            gson.toJson(mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to INTENT_SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to userMessage)
                ),
                "stream" to false,
                "max_tokens" to 400
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

    private fun parseIntentContent(responseBody: String, isAnthropic: Boolean): String? {
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
