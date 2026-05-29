package com.hfad.mantou.utils

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.hfad.mantou.data.api.ApiConfig
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ChatRequest
import java.util.concurrent.TimeUnit

object AppIntentDetector {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun isAppGenerationByKeywords(message: String): Boolean {
        val lower = message.lowercase()
        val actionWords = listOf("生成", "做一个", "帮我做", "创建", "制作", "帮我生成", "帮我创建", "写一个", "generate", "create", "make")
        val appWords = listOf("app", "应用", "网页", "工具", "游戏", "小程序", "计算器", "todo", "天气", "日历", "笔记", "时钟", "秒表", "website", "web app")
        return actionWords.any { lower.contains(it) } && appWords.any { lower.contains(it) }
    }

    suspend fun isAppGenerationIntent(userMessage: String): Boolean = withContext(Dispatchers.IO) {
        // 关键词优先匹配：明确意图直接走生成流程，跳过 API 调用
        if (isAppGenerationByKeywords(userMessage)) {
            android.util.Log.d("AppIntentDetector", "关键词命中，直接走生成流程: $userMessage")
            return@withContext true
        }

        val request = ChatRequest(
            model = ApiConfig.INTENT_MODEL,
            messages = listOf(
                ApiMessage(
                    role = "system",
                    content = """你是一个意图识别助手。判断用户的消息是否想要生成一个网页应用（app/小程序/网页/工具/计算器/游戏等）。
用户意图是"生成网页应用"时返回JSON: {"intent":"generate_app"}
用户意图是"普通聊天/提问"时返回JSON: {"intent":"chat"}
只返回JSON，不要返回任何其他内容。"""
                ),
                ApiMessage(
                    role = "user",
                    content = userMessage
                )
            ),
            stream = false,
            maxTokens = 50,
            temperature = 0.0
        )

        val jsonBody = gson.toJson(request)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val httpRequest = Request.Builder()
            .url("${ApiConfig.INTENT_BASE_URL}v1/chat/completions")
            .addHeader("Authorization", "Bearer ${ApiConfig.API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: return@withContext false
            if (!response.isSuccessful) return@withContext false

            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            val content = chatResponse.choices?.firstOrNull()?.message?.content ?: return@withContext false

            content.contains("\"generate_app\"")
        } catch (e: Exception) {
            isAppGenerationByKeywords(userMessage)
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
