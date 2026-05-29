package com.hfad.mantou.utils

import android.content.Context
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object AppGenerator {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val APP_DIR = "generated_apps"

    data class AppGenResult(
        val success: Boolean,
        val htmlPath: String? = null,
        val error: String? = null
    )

    suspend fun generateApp(context: Context, userMessage: String): AppGenResult = withContext(Dispatchers.IO) {
        try {
            val request = ChatRequest(
                model = ApiConfig.APP_GEN_MODEL,
                messages = listOf(
                    ApiMessage(
                        role = "system",
                        content = """你是一个专业的网页应用生成器。根据用户的一句话描述，生成一个完整的、可直接运行的HTML文件。

严格要求：
1. 必须生成一个完整的、自包含的HTML文件，所有CSS和JavaScript都内联在HTML中
2. 必须使用移动端App风格的布局设计：
   - 使用viewport meta标签适配移动端
   - 底部导航栏（如需要）
   - 顶部标题栏
   - 卡片式布局
   - 圆角、阴影等现代UI元素
   - 合适的字体大小和间距
   - 响应式设计
3. 页面要美观、交互完整、功能可用
4. 使用现代化的配色方案
5. 所有交互功能必须完整实现，不能有占位或空函数
6. 只返回HTML代码，不要有任何解释说明文字
7. 代码必须以<!DOCTYPE html>开头，以</html>结尾"""
                    ),
                    ApiMessage(
                        role = "user",
                        content = userMessage
                    )
                ),
                stream = false,
                maxTokens = 8192,
                temperature = 0.7
            )

            val jsonBody = gson.toJson(request)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val httpRequest = Request.Builder()
                .url("${ApiConfig.APP_GEN_BASE_URL}v1/chat/completions")
                .addHeader("Authorization", "Bearer ${ApiConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            android.util.Log.d("AppGenerator", "请求 URL: ${ApiConfig.APP_GEN_BASE_URL}v1/chat/completions, model: ${ApiConfig.APP_GEN_MODEL}")
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
                ?: return@withContext AppGenResult(false, error = "空响应")

            android.util.Log.d("AppGenerator", "响应码: ${response.code}, 响应体前500字符: ${responseBody.take(500)}")

            if (!response.isSuccessful) {
                return@withContext AppGenResult(false, error = "请求失败 (${response.code}): ${responseBody.take(200)}")
            }

            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            val content = chatResponse.choices?.firstOrNull()?.message?.content
                ?: return@withContext AppGenResult(false, error = "无内容返回: ${responseBody.take(200)}")

            android.util.Log.d("AppGenerator", "AI 返回内容前500字符: ${content.take(500)}")

            val htmlContent = extractHtml(content)
                ?: return@withContext AppGenResult(false, error = "无法提取HTML内容，AI返回: ${content.take(200)}")

            val file = saveHtmlFile(context, htmlContent)
            android.util.Log.d("AppGenerator", "HTML 已保存到: ${file.absolutePath}")
            AppGenResult(success = true, htmlPath = file.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e("AppGenerator", "生成失败", e)
            AppGenResult(success = false, error = "生成失败: ${e.message}")
        }
    }

    private fun extractHtml(content: String): String? {
        var trimmed = content.trim()

        // 去掉 markdown 代码块包裹 (```html ... ``` 或 ``` ... ```)
        val codeBlockRegex = Regex("```(?:html|HTML)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val match = codeBlockRegex.find(trimmed)
        if (match != null) {
            trimmed = match.groupValues[1].trim()
        }

        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            return trimmed
        }
        val startIndex = trimmed.indexOf("<!DOCTYPE", ignoreCase = true)
        if (startIndex >= 0) return trimmed.substring(startIndex)
        val htmlStart = trimmed.indexOf("<html", ignoreCase = true)
        if (htmlStart >= 0) return trimmed.substring(htmlStart)
        return null
    }

    private fun saveHtmlFile(context: Context, htmlContent: String): File {
        val appDir = File(context.filesDir, APP_DIR)
        if (!appDir.exists()) appDir.mkdirs()

        val fileName = "app_${UUID.randomUUID()}.html"
        val file = File(appDir, fileName)
        file.writeText(htmlContent)
        return file
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
