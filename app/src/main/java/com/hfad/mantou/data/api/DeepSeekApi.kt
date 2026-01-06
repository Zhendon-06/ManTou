package com.hfad.mantou.data.api

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hfad.mantou.utils.ImageUtils
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 调用工具类
 * 
 * 注意：DeepSeek 官方 API 目前不支持图片上传
 * 此类提供两种方式：
 * 1. sendMessage - 纯文本对话（官方支持）
 * 2. sendMessageWithImage - 图片上传（预留接口，待官方支持）
 * 
 * 如需图片识别功能，请使用 SiliconFlow API（Qwen-VL 模型）
 */
object DeepSeekApi {

    private const val TAG = "DeepSeekApi"
    private const val BASE_URL = "https://api.deepseek.com"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 发送纯文本消息（官方支持的方式）
     * 
     * @param userMessage 用户消息
     * @param apiKey DeepSeek API Key
     * @param systemPrompt 系统提示词
     * @param callback 回调函数，返回 AI 回复内容或 null（失败时）
     */
    fun sendMessage(
        userMessage: String,
        apiKey: String,
        systemPrompt: String = "You are a helpful assistant.",
        callback: (String?) -> Unit
    ) {
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", messagesArray)
            put("max_tokens", 4096)
            put("temperature", 1.0)
            put("stream", false)
        }

        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        Log.d(TAG, "发送请求: $userMessage")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "请求失败", e)
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d(TAG, "响应码: ${response.code}")
                
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val choices = jsonResponse.getJSONArray("choices")
                        if (choices.length() > 0) {
                            val message = choices.getJSONObject(0).getJSONObject("message")
                            val content = message.getString("content")
                            Log.d(TAG, "AI 回复: $content")
                            callback(content)
                        } else {
                            callback(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析响应失败", e)
                        callback(null)
                    }
                } else {
                    Log.e(TAG, "请求失败: ${response.code} - $responseBody")
                    callback(null)
                }
            }
        })
    }

    /**
     * 发送带图片的消息（使用 multipart/form-data）
     * 
     * ⚠️ 警告：DeepSeek 官方 API 目前不支持此方式！
     * 此方法仅作为预留接口，待官方支持后可用。
     * 如需图片识别，请使用 SiliconFlow API。
     * 
     * @param userMessage 用户消息
     * @param imageFile 图片文件
     * @param apiKey DeepSeek API Key
     * @param callback 回调函数
     */
    @Deprecated("DeepSeek API 目前不支持图片上传，请使用 SiliconFlow API")
    fun sendMessageWithImage(
        userMessage: String,
        imageFile: File,
        apiKey: String,
        callback: (String?) -> Unit
    ) {
        val messagesJson = """
        [
            {"role":"system","content":"You are a helpful assistant."},
            {"role":"user","content":"$userMessage"}
        ]
        """.trimIndent()

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "deepseek-chat")
            .addFormDataPart("messages", messagesJson)
            .addFormDataPart(
                "files[]", imageFile.name,
                imageFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        Log.d(TAG, "发送图片请求: $userMessage, 图片: ${imageFile.name}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "请求失败", e)
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d(TAG, "响应: ${response.code} - $responseBody")
                
                if (response.isSuccessful) {
                    callback(responseBody)
                } else {
                    // DeepSeek 不支持图片时会返回错误
                    Log.e(TAG, "DeepSeek 不支持图片上传: $responseBody")
                    callback(null)
                }
            }
        })
    }

    /**
     * 发送带图片的消息（使用 Base64 编码）
     * 
     * ⚠️ 警告：DeepSeek 官方 API 目前不支持图片！
     * 此方法仅作为预留接口。
     * 
     * @param context Android Context
     * @param userMessage 用户消息
     * @param imageUri 图片 URI
     * @param apiKey DeepSeek API Key
     * @param callback 回调函数
     */
    @Deprecated("DeepSeek API 目前不支持图片，请使用 SiliconFlow API")
    fun sendMessageWithImageBase64(
        context: Context,
        userMessage: String,
        imageUri: Uri,
        apiKey: String,
        callback: (String?) -> Unit
    ) {
        // 将图片转换为 Base64
        val imageBase64 = ImageUtils.uriToBase64(context, imageUri)
        
        if (imageBase64 == null) {
            Log.e(TAG, "图片转换失败")
            callback(null)
            return
        }

        // 构建多模态消息格式（OpenAI Vision API 格式）
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", userMessage)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", imageBase64)
                })
            })
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "You are a helpful assistant that can analyze images.")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", "deepseek-chat")  // DeepSeek 暂无视觉模型
            put("messages", messagesArray)
            put("max_tokens", 4096)
        }

        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "请求失败", e)
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val choices = jsonResponse.getJSONArray("choices")
                        if (choices.length() > 0) {
                            val message = choices.getJSONObject(0).getJSONObject("message")
                            val content = message.getString("content")
                            callback(content)
                        } else {
                            callback(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析响应失败: $responseBody", e)
                        callback(null)
                    }
                } else {
                    Log.e(TAG, "请求失败: ${response.code} - $responseBody")
                    callback(null)
                }
            }
        })
    }

    /**
     * 同步发送消息（阻塞调用，需在后台线程使用）
     */
    fun sendMessageSync(
        userMessage: String,
        apiKey: String,
        systemPrompt: String = "You are a helpful assistant."
    ): String? {
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", messagesArray)
            put("max_tokens", 4096)
            put("temperature", 1.0)
            put("stream", false)
        }

        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val choices = jsonResponse.getJSONArray("choices")
                if (choices.length() > 0) {
                    choices.getJSONObject(0).getJSONObject("message").getString("content")
                } else {
                    null
                }
            } else {
                Log.e(TAG, "请求失败: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求异常", e)
            null
        }
    }
}

