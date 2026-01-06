package com.hfad.mantou.data.api

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式 API 服务
 * 支持 SSE (Server-Sent Events) 流式输出
 */
object StreamingApiService {

    private const val TAG = "StreamingApiService"
    
    private val gson = Gson()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 流式聊天完成
     * 
     * @param request 聊天请求
     * @return Flow<StreamEvent> 流式事件流
     */
    fun streamChatCompletion(request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        // 构建流式请求（stream = true）
        val streamRequest = request.copy(stream = true)
        val jsonBody = gson.toJson(streamRequest)
        
        Log.d(TAG, "发送流式请求: ${ApiConfig.BASE_URL}v1/chat/completions")
        Log.d(TAG, "请求体: $jsonBody")
        
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
                Log.e(TAG, "流式请求失败", e)
                trySend(StreamEvent.Error("网络错误: ${e.message}"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "流式请求错误: ${response.code} - $errorBody")
                    trySend(StreamEvent.Error("请求失败 (${response.code}): $errorBody"))
                    close()
                    return
                }

                Log.d(TAG, "开始接收流式响应")
                trySend(StreamEvent.Start)

                try {
                    val reader = response.body?.charStream()?.buffered()
                    reader?.useLines { lines ->
                        lines.forEach { line ->
                            processLine(line)?.let { event ->
                                trySend(event)
                                if (event is StreamEvent.Done) {
                                    return@useLines
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理流式响应异常", e)
                    trySend(StreamEvent.Error("处理响应异常: ${e.message}"))
                } finally {
                    response.close()
                    close()
                }
            }
        })

        awaitClose {
            Log.d(TAG, "取消流式请求")
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 处理 SSE 数据行
     * 格式: data: {...}
     */
    private fun processLine(line: String): StreamEvent? {
        // 跳过空行和注释
        if (line.isBlank() || line.startsWith(":")) {
            return null
        }

        // 处理 data: 前缀
        if (!line.startsWith("data:")) {
            return null
        }

        val data = line.removePrefix("data:").trim()
        
        // 检查是否结束
        if (data == "[DONE]") {
            Log.d(TAG, "流式输出完成")
            return StreamEvent.Done
        }

        // 解析 JSON
        return try {
            val chunk = gson.fromJson(data, StreamChunk::class.java)
            val content = chunk.choices?.firstOrNull()?.delta?.content
            
            if (content != null) {
                Log.v(TAG, "收到内容: $content")
                StreamEvent.Content(content)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析流式数据失败: $data", e)
            null
        }
    }

    /**
     * 流式事件
     */
    sealed class StreamEvent {
        /** 开始接收 */
        object Start : StreamEvent()
        
        /** 内容片段 */
        data class Content(val text: String) : StreamEvent()
        
        /** 完成 */
        object Done : StreamEvent()
        
        /** 错误 */
        data class Error(val message: String) : StreamEvent()
    }
}

