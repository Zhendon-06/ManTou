package com.hfad.mantou.data.api

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hfad.mantou.data.logging.ApiLoggingInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object VoiceTranscriptionApiService {
    private const val TAG = "VoiceTranscriptionApi"
    const val MIMO_ASR_MODEL = "mimo-v2.5-asr"
    private const val MIMO_AUDIO_MIME = "audio/wav"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val mimoBaseUrls = listOf(
        "https://api.xiaomimimo.com/v1"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(ApiLoggingInterceptor())
        .build()

    suspend fun activateMimoAsr(apiKey: String): Result<VoiceInputConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedKey = apiKey.trim()
                require(normalizedKey.isNotEmpty()) { "请输入 MiMo API Key" }

                val errors = mutableListOf<String>()
                for (baseUrl in mimoBaseUrls) {
                    val probe = probeMimoBaseUrl(baseUrl, normalizedKey)
                    if (probe.isSuccess) {
                        return@runCatching VoiceInputConfig(
                            endpoint = "$baseUrl/chat/completions",
                            apiKey = normalizedKey,
                            model = MIMO_ASR_MODEL
                        )
                    }
                    errors += "${baseUrl}: ${probe.exceptionOrNull()?.message.orEmpty()}"
                }
                error("MiMo API Key 校验失败：${errors.joinToString("；")}")
            }
        }

    suspend fun transcribe(config: VoiceInputConfig, audioFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(audioFile.length() > 0) { "录音文件为空" }
                val endpoint = chatCompletionsEndpoint(config.endpoint)
                val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)

                val payload = buildAsrChatPayload(config.model, audioBase64)
                val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

                val builder = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                if (config.apiKey.isNotEmpty()) {
                    builder.addHeader("Authorization", "Bearer ${config.apiKey}")
                    builder.addHeader("api-key", config.apiKey)
                }

                Log.d(TAG, "POST $endpoint model=${config.model} audioBytes=${audioFile.length()}")
                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    Log.d(TAG, "POST $endpoint -> ${response.code} ${text.take(400)}")
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code} ${text.take(300)}")
                    }
                    extractTranscriptionText(text)
                }
            }
        }

    private fun buildAsrChatPayload(model: String, audioBase64: String): JsonObject {
        val inputAudio = JsonObject().apply {
            addProperty("data", "data:$MIMO_AUDIO_MIME;base64,$audioBase64")
        }
        val contentPart = JsonObject().apply {
            addProperty("type", "input_audio")
            add("input_audio", inputAudio)
        }
        val message = JsonObject().apply {
            addProperty("role", "user")
            add("content", JsonArray().apply { add(contentPart) })
        }
        val asrOptions = JsonObject().apply {
            addProperty("language", "auto")
        }
        return JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply { add(message) })
            add("asr_options", asrOptions)
        }
    }

    private fun extractTranscriptionText(body: String): String {
        val json = JsonParser.parseString(body).asJsonObject
        val choices = json.getAsJsonArray("choices")
            ?: error("响应缺少 choices 字段：${body.take(200)}")
        require(choices.size() > 0) { "响应 choices 为空：${body.take(200)}" }
        val message = choices[0].asJsonObject.getAsJsonObject("message")
            ?: error("响应缺少 message 字段：${body.take(200)}")
        val text = message.get("content")?.asString.orEmpty()
        require(text.isNotBlank()) { "语音识别结果为空" }
        return text.trim()
    }

    private fun probeMimoBaseUrl(baseUrl: String, apiKey: String): Result<Unit> {
        return runCatching {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .get()
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("api-key", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> Unit
                    response.code == 401 || response.code == 403 -> {
                        error("API Key 无效或无权限 (${response.code})")
                    }
                    else -> {
                        error("连接失败 (${response.code}) ${body.take(160)}")
                    }
                }
            }
        }
    }

    private fun chatCompletionsEndpoint(savedEndpoint: String): String {
        val normalized = savedEndpoint.trim().trimEnd('/')
        if (normalized.endsWith("/chat/completions")) return normalized
        val base = when {
            normalized.endsWith("/audio/transcriptions") -> normalized.removeSuffix("/audio/transcriptions")
            normalized.endsWith("/audio/transcription") -> normalized.removeSuffix("/audio/transcription")
            normalized.endsWith("/audio/asr") -> normalized.removeSuffix("/audio/asr")
            normalized.endsWith("/asr") -> normalized.removeSuffix("/asr")
            else -> normalized
        }.trimEnd('/')
        return "$base/chat/completions"
    }
}
