package com.hfad.mantou.data.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式 API 服务 - 支持 SSE (Server-Sent Events) 流式输出
 */
object StreamingApiService {

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 流式聊天完成
     * @param request 聊天请求
     * @return Flow<StreamEvent> 流式事件流
     */
    fun streamChatCompletion(request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val streamRequest = request.copy(stream = true)
        val jsonBody = gson.toJson(streamRequest)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val httpRequest = Request.Builder()
            .url("${ApiConfig.BASE_URL}v1/chat/completions")
            .addHeader("Authorization", "Bearer ${ApiConfig.API_KEY}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val call = client.newCall(httpRequest)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamEvent.Error("网络错误: ${e.message}"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    trySend(StreamEvent.Error("请求失败 (${response.code})"))
                    close()
                    return
                }

                trySend(StreamEvent.Start)

                response.body?.charStream()?.buffered()?.useLines { lines ->
                    lines.forEach { line ->
                        processLine(line)?.let { event ->
                            trySend(event)
                            if (event is StreamEvent.Done) return@useLines
                        }
                    }
                }

                response.close()
                close()
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** 处理 SSE 数据行，格式: data: {...} */
    private fun processLine(line: String): StreamEvent? {
        if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) return null

        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamEvent.Done

        return try {
            val chunk = gson.fromJson(data, StreamChunk::class.java)
            chunk.choices?.firstOrNull()?.delta?.content?.let { StreamEvent.Content(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** 流式事件 */
    sealed class StreamEvent {
        object Start : StreamEvent()
        data class Content(val text: String) : StreamEvent()
        object Done : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}

