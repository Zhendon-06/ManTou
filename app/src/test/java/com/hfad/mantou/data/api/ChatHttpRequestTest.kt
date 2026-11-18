package com.hfad.mantou.data.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hfad.mantou.data.database.ProviderEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHttpRequestTest {
    @Test
    fun kimiRequestOmitsSamplingOverrides() {
        val request = ChatHttpRequest.build(
            compatibleConfig,
            chatRequest.copy(stream = true, streamOptions = StreamOptions())
        )
        val json = request.jsonBody()

        assertEquals("kimi-k3.1-pro", json.get("model").asString)
        assertFalse(json.has("temperature"))
        assertFalse(json.has("top_p"))
        assertEquals(512, json.get("max_tokens").asInt)
        assertTrue(json.getAsJsonObject("stream_options").get("include_usage").asBoolean)
    }

    @Test
    fun openAiUsesModernTokenLimitForNonStreamingRequests() {
        val config = compatibleConfig.copy(baseUrl = "https://api.openai.com/v1", model = "o3")
        val json = ChatHttpRequest.build(config, chatRequest).jsonBody()

        assertFalse(json.has("max_tokens"))
        assertEquals(512, json.get("max_completion_tokens").asInt)
        assertFalse(json.get("stream").asBoolean)
        assertFalse(json.has("stream_options"))
    }

    @Test
    fun anthropicKeepsRequiredLimitAndMessageFormat() {
        val config = compatibleConfig.copy(apiFormat = ProviderEntity.API_FORMAT_ANTHROPIC)
        val request = ChatHttpRequest.build(config, chatRequest)
        val json = request.jsonBody()

        assertEquals("/v1/messages", request.url.encodedPath)
        assertEquals("test-key", request.header("x-api-key"))
        assertEquals(512, json.get("max_tokens").asInt)
        assertEquals("system instruction", json.get("system").asString)
        val image = json.getAsJsonArray("messages")[0].asJsonObject
            .getAsJsonArray("content")[1].asJsonObject.getAsJsonObject("source")
        assertEquals("base64", image.get("type").asString)
        assertEquals("AA==", image.get("data").asString)
        assertFalse(json.has("temperature"))
        assertFalse(json.has("stream_options"))
    }

    @Test
    fun streamingRetriesExplicitRejectionsWithoutChangingMessagesOrBudget() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = mockClient(requests, listOf(
            Reply(400, """{"error":{"code":"unsupported_parameter","param":"stream_options"}}"""),
            Reply(422, """{"detail":[{"loc":["body","max_tokens"],"type":"extra_forbidden"}]}"""),
            Reply(200, "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n")
        ))
        val events = withTimeout(10_000) {
            StreamingApiService.streamChatCompletion(
                compatibleConfig,
                chatRequest.copy(stream = true, streamOptions = StreamOptions()),
                client
            ).toList()
        }

        assertEquals(listOf(
            StreamingApiService.StreamEvent.Start,
            StreamingApiService.StreamEvent.Content("hello"),
            StreamingApiService.StreamEvent.Done
        ), events)
        val payloads = requests.map { it.jsonBody() }
        assertEquals(3, payloads.size)
        assertTrue(payloads[0].has("stream_options"))
        assertFalse(payloads[1].has("stream_options"))
        assertEquals(512, payloads[1].get("max_tokens").asInt)
        assertEquals(512, payloads[2].get("max_completion_tokens").asInt)
        assertFalse(payloads[2].has("max_tokens"))
        assertTrue(payloads.all { it["messages"] == payloads[0]["messages"] })
        assertTrue(requests.all { it.header("Authorization") == "Bearer test-key" })
    }

    @Test
    fun tokenLimitValueErrorIsReturnedWithoutRetry() {
        val requests = mutableListOf<Request>()
        val error = """{"error":{"code":"unsupported_value","param":"max_tokens","message":"Unsupported value for max_tokens"}}"""
        val client = mockClient(requests, listOf(Reply(400, error)))

        client.newCall(ChatHttpRequest.build(compatibleConfig, chatRequest)).execute().use {
            assertEquals(400, it.code)
            assertEquals(error, it.body?.string())
        }
        assertEquals(1, requests.size)
    }

    private fun Request.jsonBody(): JsonObject {
        val buffer = Buffer()
        checkNotNull(body).writeTo(buffer)
        return JsonParser.parseString(buffer.readUtf8()).asJsonObject
    }

    private fun mockClient(requests: MutableList<Request>, replies: List<Reply>): OkHttpClient =
        StreamingApiService.createClient().newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val reply = replies[requests.size]
                requests.add(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(reply.status)
                    .message("Test response")
                    .body(reply.body.toResponseBody(
                        (if (reply.status == 200) "text/event-stream" else "application/json").toMediaType()
                    ))
                    .build()
            }.build()

    private data class Reply(val status: Int, val body: String)

    private val compatibleConfig = ChatCallConfig(
        "http://127.0.0.1:12345",
        "test-key",
        "kimi-k3.1-pro",
        ProviderEntity.API_FORMAT_OPENAI
    )

    private val chatRequest = ChatRequest(
        model = compatibleConfig.model,
        messages = listOf(
            ApiMessage("system", "system instruction"),
            ApiMessage("user", listOf(
                ContentPart("text", text = "hello"),
                ContentPart("image_url", imageUrl = ImageUrl("data:image/png;base64,AA=="))
            ))
        ),
        maxTokens = 512
    )
}
