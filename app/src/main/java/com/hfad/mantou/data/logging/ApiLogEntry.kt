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
    val responseBody: String,      // 流式响应统一记 "(流式响应,正文未保留)"
    val errorMessage: String?      // 网络异常或非 2xx 时的简短说明
) {
    val success: Boolean get() = errorMessage == null && (httpStatus == null || httpStatus in 200..299)
}
