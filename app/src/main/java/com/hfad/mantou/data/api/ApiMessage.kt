package com.hfad.mantou.data.api

import com.google.gson.annotations.SerializedName

/**
 * API 消息格式
 */
data class ApiMessage(
    @SerializedName("role")
    val role: String,  // "system", "user", "assistant"
    
    @SerializedName("content")
    val content: Any  // 可以是 String 或 List<ContentPart>（支持图片）
)

/**
 * 内容部分（支持文本和图片）
 */
data class ContentPart(
    @SerializedName("type")
    val type: String,  // "text" 或 "image_url"
    
    @SerializedName("text")
    val text: String? = null,
    
    @SerializedName("image_url")
    val imageUrl: ImageUrl? = null
)

/**
 * 图片 URL
 */
data class ImageUrl(
    @SerializedName("url")
    val url: String
)

