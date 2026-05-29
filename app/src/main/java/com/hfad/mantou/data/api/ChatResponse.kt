package com.hfad.mantou.data.api

import com.google.gson.annotations.SerializedName

/**
 * Chat API 响应（非流式）
 */
data class Choice(
    @SerializedName("index")
    val index: Int,
    
    @SerializedName("message")
    val message: ApiMessage? = null,
    
    @SerializedName("delta")
    val delta: Delta? = null,  // 流式输出时使用
    
    @SerializedName("finish_reason")
    val finishReason: String?
)

/**
 * 流式输出的增量内容
 */
data class Delta(
    @SerializedName("role")
    val role: String? = null,

    @SerializedName("content")
    val content: String? = null,

    @SerializedName("reasoning_content")
    val reasoningContent: String? = null
)

/**
 * 流式响应数据块
 * SSE 格式: data: {...}
 */
data class StreamChunk(
    @SerializedName("id")
    val id: String? = null,
    
    @SerializedName("object")
    val objectType: String? = null,
    
    @SerializedName("created")
    val created: Long? = null,
    
    @SerializedName("model")
    val model: String? = null,
    
    @SerializedName("choices")
    val choices: List<Choice>? = null
)

