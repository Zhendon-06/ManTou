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
 * - OpenAI 兼容: GET {resolvedModelListUrl} + Authorization: Bearer {key}
 * - Anthropic   : GET {resolvedModelListUrl} + x-api-key: {key} + anthropic-version
 *
 * 常见响应形态包括 { "data": [...] }、{ "models": [...] } 或顶层数组，统一抽取 id/name/model。
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
            val url = ApiEndpointResolver.modelListUrl(baseUrl)
            val builder = Request.Builder().url(url).get()
            when (apiFormat) {
                ProviderEntity.API_FORMAT_ANTHROPIC -> {
                    if (apiKey.isNotEmpty()) {
                        builder.addHeader("x-api-key", apiKey)
                    }
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
                    throw RuntimeException("GET $url -> HTTP ${response.code}: ${body.take(200)}")
                }
                parseModelIds(body)
            }
        }
    }

    private fun parseModelIds(body: String): List<String> {
        val root = JsonParser.parseString(body)
        val modelArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val obj = root.asJsonObject
                listOf("data", "models")
                    .firstNotNullOfOrNull { key ->
                        obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                    }
            }
            else -> null
        } ?: return emptyList()

        return modelArray.mapNotNull { el ->
            when {
                el.isJsonPrimitive -> el.asString
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    firstStringValue(obj, "id", "name", "model")
                }
                else -> null
            }
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun firstStringValue(
        obj: com.google.gson.JsonObject,
        vararg keys: String
    ): String? {
        return keys.firstNotNullOfOrNull { key ->
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        }
    }
}
