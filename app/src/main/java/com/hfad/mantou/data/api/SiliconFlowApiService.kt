package com.hfad.mantou.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * SiliconFlow API 服务接口
 * Authorization Header 由 AuthInterceptor 统一注入
 */
interface SiliconFlowApiService {
    
    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatRequest
    ): Response<ChatResponse>
}

