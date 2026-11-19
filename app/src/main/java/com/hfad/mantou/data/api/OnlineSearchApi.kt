package com.hfad.mantou.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class SearchPage(val title: String, val url: String, val excerpt: String)

internal data class CommonsImage(
    val id: Long,
    val title: String,
    val creator: String,
    val license: String,
    val licenseUrl: String?,
    val sourceUrl: String,
    val downloadUrl: HttpUrl
)

internal data class SearchImageBytes(val image: CommonsImage, val extension: String, val bytes: ByteArray)

internal class SearchApiException(val httpCode: Int? = null) : IOException()

internal class OnlineSearchApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(9, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
) {
    private val webClient = client.newBuilder()
        .callTimeout(24, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    suspend fun web(query: String, key: String): List<SearchPage> {
        val payload = GSON.toJson(mapOf(
            "query" to query,
            "search_depth" to "basic",
            "auto_parameters" to false,
            "max_results" to 4,
            "safe_search" to true,
            "include_answer" to false,
            "include_raw_content" to false,
            "include_images" to false
        ))
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("X-Client-Source", "mantou")
            .apply {
                if (key.isBlank()) {
                    header("X-Tavily-Access-Mode", "keyless")
                } else {
                    header("Authorization", "Bearer $key")
                }
            }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json(request, webClient).objects("results").take(4).mapNotNull { result ->
            val url = publicHttps(result.string("url")) ?: return@mapNotNull null
            SearchPage(
                title = text(result.string("title"), 160).ifBlank { url.host },
                url = url.toString(),
                excerpt = text(result.string("content"), 620)
            )
        }
    }

    suspend fun images(query: String): List<CommonsImage> {
        val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "$query filetype:bitmap")
            .addQueryParameter("gsrnamespace", "6")
            .addQueryParameter("gsrlimit", "8")
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|mime|thumbmime|extmetadata")
            .addQueryParameter("iiurlwidth", "1280")
            .addQueryParameter("iiextmetadatafilter", "License|LicenseShortName|LicenseUrl|Artist")
            .build()
        val response = json(Request.Builder().url(url).header("User-Agent", COMMONS_USER_AGENT).build())
        if (response.has("error")) throw SearchApiException()
        return response.obj("query")?.objects("pages").orEmpty()
            .sortedBy { it.number("index") ?: Long.MAX_VALUE }
            .mapNotNull(::commonsImage)
            .take(6)
    }

    suspend fun download(image: CommonsImage): SearchImageBytes {
        if (!allowedImageUrl(image.downloadUrl)) throw SearchApiException()
        val request = Request.Builder().url(image.downloadUrl)
            .header("User-Agent", COMMONS_USER_AGENT).build()
        val payload = fetch(request, MAX_IMAGE_BYTES)
        val extension = when (payload.mediaType) {
            "image/jpeg" -> "jpg".takeIf { payload.bytes.startsWith(0xff, 0xd8, 0xff) }
            "image/png" -> "png".takeIf { payload.bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) }
            "image/webp" -> "webp".takeIf {
                payload.bytes.startsWith(0x52, 0x49, 0x46, 0x46) && payload.bytes.size >= 12 &&
                    payload.bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            }
            else -> null
        } ?: throw SearchApiException()
        return SearchImageBytes(image, extension, payload.bytes)
    }

    private fun commonsImage(page: JsonObject): CommonsImage? {
        val id = page.number("pageid")?.takeIf { it > 0 } ?: return null
        val info = page.objects("imageinfo").firstOrNull() ?: return null
        val metadata = info.obj("extmetadata") ?: return null
        fun metadataValue(key: String) = metadata.obj(key)?.string("value").orEmpty()

        val licenseCode = metadataValue("License").lowercase()
        if (licenseCode !in PUBLIC_DOMAIN && !CC_LICENSE.matches(licenseCode)) return null
        val license = text(metadataValue("LicenseShortName"), 80).ifBlank { return null }
        val creator = text(metadataValue("Artist"), 350)
        val licenseUrl = publicHttps(metadataValue("LicenseUrl"))?.takeIf { it.host == "creativecommons.org" }
        if (CC_LICENSE.matches(licenseCode) && (creator.isBlank() || licenseUrl == null)) return null

        val thumb = info.string("thumburl").isNotBlank()
        val mimeType = info.string("thumbmime").ifBlank { info.string("mime") }
        if (mimeType !in IMAGE_MIME_TYPES) return null
        val url = publicHttps(info.string(if (thumb) "thumburl" else "url"))
            ?.takeIf(::allowedImageUrl)?.newBuilder()?.query(null)?.build() ?: return null
        val title = text(page.string("title").removePrefix("File:"), 160).ifBlank { return null }
        return CommonsImage(id, title, creator, license, licenseUrl?.toString(),
            "https://commons.wikimedia.org/wiki/Special:Redirect/page/$id", url)
    }

    private suspend fun json(request: Request, networkClient: OkHttpClient = client): JsonObject {
        val body = fetch(request, 512L * 1024, networkClient).bytes
        return JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject
    }

    private data class Payload(val mediaType: String, val bytes: ByteArray)

    private suspend fun fetch(
        request: Request,
        limit: Long,
        networkClient: OkHttpClient = client
    ): Payload = suspendCancellableCoroutine { continuation ->
        val call = networkClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw SearchApiException(it.code)
                        val body = it.body ?: throw SearchApiException()
                        val source = body.source()
                        if (body.contentLength() > limit || source.request(limit + 1)) throw SearchApiException()
                        val mediaType = body.contentType()?.let { type -> "${type.type}/${type.subtype}" }.orEmpty()
                        Payload(mediaType, source.readByteArray())
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private companion object {
        val GSON = Gson()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val PUBLIC_DOMAIN = setOf("pd", "cc0")
        val CC_LICENSE = Regex("cc-by(?:-sa)?-(?:[1-4]\\.0|2\\.5)")
        val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val HTML_TAG = Regex("<[^>]*>")
        val WHITESPACE = Regex("\\s+")
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        const val COMMONS_USER_AGENT = "ManTou/2.0.1 (https://github.com/Zhendon-06/ManTou)"

        fun allowedImageUrl(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
            (url.host == "upload.wikimedia.org" || url.host == "thumb.wikimedia.org") &&
            url.encodedPath.startsWith("/wikipedia/commons/")

        fun publicHttps(value: String): HttpUrl? = value.takeIf { it.length <= 700 }?.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }

        fun text(value: String, limit: Int): String = value.replace(HTML_TAG, " ")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace(WHITESPACE, " ").filterNot(Char::isISOControl).trim().take(limit)

        fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        fun JsonObject.objects(key: String): List<JsonObject> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()
        fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString.orEmpty()
        fun JsonObject.number(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
        fun ByteArray.startsWith(vararg signature: Int): Boolean = size >= signature.size &&
            signature.indices.all { this[it].toInt() and 0xff == signature[it] }
    }
}
