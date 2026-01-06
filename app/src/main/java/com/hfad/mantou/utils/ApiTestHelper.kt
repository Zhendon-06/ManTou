package com.hfad.mantou.utils

import android.util.Log
import com.hfad.mantou.data.api.ApiConfig
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * API 测试工具
 * 用于诊断 SiliconFlow API 连接问题
 */
object ApiTestHelper {
    
    private const val TAG = "ApiTestHelper"
    
    /**
     * 测试 API 连接
     * 发送一个最小的测试请求
     */
    suspend fun testApiConnection(): TestResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========== 开始 API 测试 ==========")
            
            // 1. 检查 API Key 配置
            val apiKey = ApiConfig.API_KEY
            Log.d(TAG, "1. 检查 API Key")
            Log.d(TAG, "   - API Key 长度: ${apiKey.length}")
            Log.d(TAG, "   - API Key 前缀: ${apiKey.take(8)}")
            Log.d(TAG, "   - 是否为默认值: ${apiKey == "YOUR_API_KEY_HERE"}")
            
            if (apiKey.isEmpty() || apiKey == "YOUR_API_KEY_HERE") {
                return@withContext TestResult.Error("API Key 未配置！请在 local.properties 中设置 SILICONFLOW_API_KEY")
            }
            
            if (!apiKey.startsWith("sk-")) {
                return@withContext TestResult.Error("API Key 格式错误！应该以 'sk-' 开头")
            }
            
            // 2. 组装最小测试请求
            Log.d(TAG, "2. 组装测试请求")
            val testMessages = listOf(
                ApiMessage(
                    role = "system",
                    content = "你是一个测试助手"
                ),
                ApiMessage(
                    role = "user",
                    content = "你好"
                )
            )
            
            val request = ChatRequest(
                model = ApiConfig.DEFAULT_MODEL,
                messages = testMessages,
                maxTokens = 100,
                temperature = 0.7
            )
            
            Log.d(TAG, "   - 模型: ${request.model}")
            Log.d(TAG, "   - API 类型: ${if (ApiConfig.USE_VISION_API) "SiliconFlow (支持视觉)" else "DeepSeek (仅文本)"}")
            Log.d(TAG, "   - 消息数量: ${request.messages.size}")
            Log.d(TAG, "   - 请求体: $request")
            
            // 3. 发送请求
            Log.d(TAG, "3. 发送 API 请求")
            Log.d(TAG, "   - URL: ${ApiConfig.BASE_URL}v1/chat/completions")
            
            val response = RetrofitClient.apiService.chatCompletion(request)
            
            // 4. 分析响应
            Log.d(TAG, "4. 分析响应")
            Log.d(TAG, "   - HTTP 状态码: ${response.code()}")
            Log.d(TAG, "   - 是否成功: ${response.isSuccessful}")
            
            if (response.isSuccessful) {
                val body = response.body()
                Log.d(TAG, "   - ✅ 请求成功！")
                Log.d(TAG, "   - 响应 ID: ${body?.id}")
                Log.d(TAG, "   - 模型: ${body?.model}")
                
                // 安全地获取回复内容（content 是 Any 类型）
                val replyContent = body?.choices?.firstOrNull()?.message?.content?.toString() ?: "无回复内容"
                Log.d(TAG, "   - 回复内容: $replyContent")
                Log.d(TAG, "===================================")
                
                return@withContext TestResult.Success(
                    statusCode = response.code(),
                    message = "API 连接成功！",
                    response = replyContent
                )
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "   - ❌ 请求失败！")
                Log.e(TAG, "   - 错误码: ${response.code()}")
                Log.e(TAG, "   - 错误信息: $errorBody")
                Log.e(TAG, "===================================")
                
                // 分析具体错误
                val errorMessage = when (response.code()) {
                    401 -> analyzeAuthError(apiKey, errorBody)
                    400 -> "请求参数错误: $errorBody"
                    403 -> "API Key 权限不足或已被禁用"
                    429 -> "请求频率超限，请稍后重试"
                    500, 502, 503 -> "服务器错误，请稍后重试"
                    else -> "未知错误 (${response.code()}): $errorBody"
                }
                
                return@withContext TestResult.Error(errorMessage)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "测试过程中发生异常", e)
            Log.e(TAG, "===================================")
            return@withContext TestResult.Error("网络异常: ${e.message}")
        }
    }
    
    /**
     * 分析 401 认证错误
     */
    private fun analyzeAuthError(apiKey: String, errorBody: String?): String {
        val issues = mutableListOf<String>()
        
        // 检查 API Key 格式
        if (!apiKey.startsWith("sk-")) {
            issues.add("API Key 格式错误（应以 'sk-' 开头）")
        }
        
        if (apiKey.length < 20) {
            issues.add("API Key 长度异常（可能不完整）")
        }
        
        if (apiKey.contains(" ") || apiKey.contains("\n")) {
            issues.add("API Key 包含空格或换行符")
        }
        
        // 检查错误信息
        if (errorBody?.contains("Invalid token") == true) {
            issues.add("API Key 无效或已过期")
        }
        
        if (errorBody?.contains("Unauthorized") == true) {
            issues.add("未授权访问")
        }
        
        return if (issues.isNotEmpty()) {
            "401 认证失败:\n" + issues.joinToString("\n") + "\n\n错误详情: $errorBody"
        } else {
            "401 认证失败: $errorBody\n\n请检查:\n1. API Key 是否正确\n2. API Key 是否已激活\n3. 是否有访问权限"
        }
    }
    
    /**
     * 测试结果
     */
    sealed class TestResult {
        data class Success(
            val statusCode: Int,
            val message: String,
            val response: String
        ) : TestResult()
        
        data class Error(
            val message: String
        ) : TestResult()
    }
}

