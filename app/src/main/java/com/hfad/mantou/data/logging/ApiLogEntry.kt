package com.hfad.mantou.data.logging

/**
 * 一条 LLM/语音/模型列表请求的运行时日志记录。仅留在内存里,App 退出即清空。
 */
data class ApiLogEntry(
    val id: Long,
    val timestampMs: Long,
    val provider: String,          // "OpenAI" / "Anthropic" / "其它"
    val endpointLabel: String,     // 例如 "openai/chat.completions.stream"
    val model: String?,            // 解析自 request body 的 "model" 字段
    val method: String,
    val url: String,
    val isStream: Boolean,
    val httpStatus: Int?,          // null = 网络异常,没有 status
    val durationMs: Long,
    val requestBody: String,
    val responseBody: String,      // 成功流式响应不留正文；非 2xx 保留有限错误预览
    val errorMessage: String?,     // 网络异常或非 2xx 时的简短说明
    val traceId: String? = null,
    val upstreamRequestId: String? = null,
    val retryable: Boolean? = null,
    val streamCompleted: Boolean? = null,
    val runId: String? = null,
    val operation: String? = null,
    val iteration: Int? = null,
    val canceled: Boolean = false
) {
    val inProgress: Boolean
        get() = isStream &&
            httpStatus in 200..299 &&
            streamCompleted == false &&
            errorMessage == null &&
            !canceled

    val success: Boolean
        get() = !inProgress &&
            !canceled &&
            errorMessage == null &&
            (httpStatus == null || httpStatus in 200..299)
}
