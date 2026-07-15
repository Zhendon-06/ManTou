package com.hfad.mantou.data.api

import com.hfad.mantou.data.database.ProviderEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StreamingApiServiceTest {

    @Test
    fun streamingRequestsDoNotExpireWhileWaitingForModelOutput() {
        assertEquals(0L, StreamingApiService.STREAM_READ_TIMEOUT_SECONDS)
        assertTrue(StreamingApiService.isRetryableNetworkFailure("网络状态出错: timeout"))
        assertTrue(StreamingApiService.isRetryableNetworkFailure("unexpected end of stream"))
        assertFalse(StreamingApiService.isRetryableNetworkFailure("请求失败 (401) unauthorized"))
    }

    @Test
    fun rapidSseChunksAreNotDroppedWhenCollectorIsSlow() = runBlocking {
        val expectedChunks = (0 until 512).map { "chunk-$it;" }
        val body = buildString {
            expectedChunks.forEach { chunk ->
                append("data: {\"choices\":[{\"delta\":{\"content\":\"")
                append(chunk)
                append("\"}}]}\n\n")
            }
            append("data: [DONE]\n\n")
        }.toByteArray(Charsets.UTF_8)
        val serverFailure = AtomicReference<Throwable?>()

        ServerSocket(0).use { server ->
            val serverThread = thread(name = "streaming-api-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().buffered().use { output ->
                            val headers = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: text/event-stream\r\n")
                                append("Content-Length: ${body.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }.toByteArray(Charsets.US_ASCII)
                            output.write(headers)
                            output.write(body)
                            output.flush()
                        }
                    }
                }.onFailure(serverFailure::set)
            }
            val config = ChatCallConfig(
                baseUrl = "http://127.0.0.1:${server.localPort}",
                apiKey = "",
                model = "test-model",
                apiFormat = ProviderEntity.API_FORMAT_OPENAI
            )
            val request = ChatRequest(
                model = config.model,
                messages = listOf(ApiMessage(role = "user", content = "test")),
                stream = true
            )
            val actualChunks = mutableListOf<String>()

            withTimeout(10_000) {
                StreamingApiService.streamChatCompletion(config, request).collect { event ->
                    if (event is StreamingApiService.StreamEvent.Content) {
                        delay(2)
                        actualChunks += event.text
                    }
                }
            }
            serverThread.join(5_000)

            assertNull(serverFailure.get())
            assertEquals(expectedChunks, actualChunks)
        }
    }
}
