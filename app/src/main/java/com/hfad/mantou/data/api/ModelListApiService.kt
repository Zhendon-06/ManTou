package com.hfad.mantou.data.api

import com.google.gson.JsonParser
import com.hfad.mantou.data.database.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 拉取 Provider 模型列表。
 *
 * - OpenAI 兼容: GET {baseUrl}v1/models  + Authorization: Bearer {key}
 * - Anthropic   : GET {baseUrl}v1/models  + x-api-key: {key} + anthropic-version
 *
 * 两者响应都返回 { "data": [ { "id": "...", ... }, ... ] }，统一抽取 id。
 */
object ModelListApiService {

    private const val ANTHROPIC_VERSION = "2023-06-01"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        apiFormat: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildModelsUrl(baseUrl)
            val builder = Request.Builder().url(url).get()
            when (apiFormat) {
                ProviderEntity.API_FORMAT_ANTHROPIC -> {
                    builder.addHeader("x-api-key", apiKey)
                    builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
                }
                else -> {
                    if (apiKey.isNotEmpty()) {
                        builder.addHeader("Authorization", "Bearer $apiKey")
                    }
                }
            }
            builder.addHeader("Accept", "application/json")

            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${body.take(200)}")
                }
                parseModelIds(body)
            }
        }
    }

    private fun buildModelsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return "$trimmed/v1/models"
    }

    private fun parseModelIds(body: String): List<String> {
        val root = JsonParser.parseString(body)
        if (!root.isJsonObject) return emptyList()
        val data = root.asJsonObject.get("data") ?: return emptyList()
        if (!data.isJsonArray) return emptyList()
        return data.asJsonArray.mapNotNull { el ->
            if (el.isJsonObject) el.asJsonObject.get("id")?.asString else null
        }.filter { it.isNotBlank() }
    }
}
