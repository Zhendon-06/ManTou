package com.hfad.mantou.data.api

import com.google.gson.annotations.SerializedName
import com.hfad.mantou.data.logging.ApiDiagnosticContext

/**
 * Chat API 请求
 *
 * model 必须由调用方显式提供（来源：用户在 ModelSettingActivity 选中的 Provider + 模型）。
 * 项目不再内置任何默认模型 —— 未配置时上层会直接拦截并提示用户去配置。
 */
data class ChatRequest(
    @SerializedName("model")
    val model: String,

    @SerializedName("messages")
    val messages: List<ApiMessage>,
    
    @SerializedName("stream")
    val stream: Boolean = false,  // 是否流式返回
    
    @SerializedName("max_tokens")
    val maxTokens: Int = ApiConfig.MAX_TOKENS,
    
    @SerializedName("stream_options")
    val streamOptions: StreamOptions? = if (stream) StreamOptions() else null,

    @SerializedName("response_format")
    val responseFormat: Map<String, String>? = null,

    @SerializedName("reasoning_effort")
    val reasoningEffort: String? = null,

    @Transient
    val diagnosticContext: ApiDiagnosticContext? = null,

    @Transient
    val tokenUsageListener: ((ModelTokenUsage) -> Unit)? = null
)

data class StreamOptions(
    @SerializedName("include_usage")
    val includeUsage: Boolean = true
)
