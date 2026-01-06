package com.hfad.mantou.data.api

import com.google.gson.annotations.SerializedName

/**
 * Chat API 请求
 * 兼容 DeepSeek 和 SiliconFlow API
 */
data class ChatRequest(
    @SerializedName("model")
    val model: String = ApiConfig.DEFAULT_MODEL,  // 动态默认模型
    
    @SerializedName("messages")
    val messages: List<ApiMessage>,
    
    @SerializedName("stream")
    val stream: Boolean = false,  // 是否流式返回
    
    @SerializedName("max_tokens")
    val maxTokens: Int = ApiConfig.MAX_TOKENS,
    
    @SerializedName("temperature")
    val temperature: Double = ApiConfig.TEMPERATURE,
    
    @SerializedName("top_p")
    val topP: Double = ApiConfig.TOP_P
)

