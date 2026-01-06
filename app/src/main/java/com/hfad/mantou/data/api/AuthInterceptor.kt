package com.hfad.mantou.data.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * SiliconFlow API 鉴权拦截器
 * 统一为所有请求添加 Authorization Header
 */
class AuthInterceptor : Interceptor {
    
    companion object {
        private const val TAG = "AuthInterceptor"
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 调试日志：打印请求信息（不打印真实 API Key）
        val url = originalRequest.url.toString()
        Log.d(TAG, "========== 请求调试信息 ==========")
        Log.d(TAG, "请求 URL: $url")
        Log.d(TAG, "BaseUrl: ${originalRequest.url.scheme}://${originalRequest.url.host}/")
        Log.d(TAG, "Path: ${originalRequest.url.encodedPath}")
        Log.d(TAG, "完整路径: ${originalRequest.url.scheme}://${originalRequest.url.host}${originalRequest.url.encodedPath}")
        
        // 检查 API Key 是否存在
        val apiKey = ApiConfig.API_KEY
        val hasApiKey = apiKey.isNotEmpty() && apiKey != "YOUR_API_KEY_HERE"
        Log.d(TAG, "API Key 是否配置: $hasApiKey")
        if (hasApiKey) {
            Log.d(TAG, "API Key 前缀: ${apiKey.take(8)}...")
        } else {
            Log.e(TAG, "⚠️ API Key 未配置或为默认值！")
        }
        
        // 构建新的请求，添加 Authorization Header
        val authHeader = "Bearer $apiKey"
        val newRequest = originalRequest.newBuilder()
            .header("Authorization", authHeader)
            .build()
        
        // 验证 Header 是否正确添加
        val addedAuthHeader = newRequest.header("Authorization")
        Log.d(TAG, "Authorization Header 是否存在: ${addedAuthHeader != null}")
        Log.d(TAG, "Header 是否以 'Bearer ' 开头: ${addedAuthHeader?.startsWith("Bearer ") == true}")
        if (addedAuthHeader != null) {
            Log.d(TAG, "Header 格式: Bearer ${addedAuthHeader.substring(7).take(8)}...")
        }
        Log.d(TAG, "===================================")
        
        return chain.proceed(newRequest)
    }
}

