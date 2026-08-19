package com.hfad.mantou.data.api

import com.hfad.mantou.data.database.ProviderEntity
import com.hfad.mantou.data.logging.ApiDiagnosticContext
import com.hfad.mantou.data.logging.ApiLogStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StreamingApiServiceTest {

    @Test
    fun streamingRequestsHaveFiniteTimeoutsAndClassifyRetryableFailures() {
        val client = StreamingApiService.createClient()
        assertEquals(
            TimeUnit.SECONDS.toMillis(StreamingApiService.STREAM_READ_TIMEOUT_SECONDS).toInt(),
            client.readTimeoutMillis
        )
        assertEquals(
            TimeUnit.MINUTES.toMillis(StreamingApiService.STREAM_CALL_TIMEOUT_MINUTES).toInt(),
            client.callTimeoutMillis
        )
        assertTrue(StreamingApiService.isRetryableNetworkFailure("网络状态出错: timeout"))
        assertTrue(StreamingApiService.isRetryableNetworkFailure("网络请求超时: 等待首包超过时限"))
        assertTrue(StreamingApiService.isRetryableNetworkFailure("unexpected end of stream"))
        assertTrue(StreamingApiService.isRetryableNetworkFailure("请求失败 (502) upstream_error"))
        assertTrue(StreamingApiService.isRetryableFailure(429, "rate limited"))
        assertTrue(StreamingApiService.isRetryableFailure(503, "service unavailable"))
        assertFalse(StreamingApiService.isRetryableNetworkFailure("请求失败 (401) unauthorized"))
        assertFalse(StreamingApiService.isRetryableFailure(403, "forbidden"))
    }

    @Test
    fun stalledSuccessfulStreamHitsCallTimeoutAndLogsRetryableFailure() = runBlocking {
        ApiLogStore.clear()
        val serverFailure = AtomicReference<Throwable?>()

        ServerSocket(0).use { server ->
            val serverThread = thread(name = "streaming-api-timeout-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().buffered().use { output ->
                            output.write(
                                buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: text/event-stream\r\n")
                                    append("Transfer-Encoding: chunked\r\n")
                                    append("Connection: keep-alive\r\n\r\n")
                                }.toByteArray(Charsets.US_ASCII)
                            )
                            output.flush()
                            Thread.sleep(1_500)
                        }
                    }
                }.onFailure(serverFailure::set)
            }
            val config = testConfig(server.localPort)
            val request = testRequest(config.model).copy(
                diagnosticContext = ApiDiagnosticContext(
                    runId = "run-timeout",
                    operation = "harness.tool_follow_up",
                    iteration = 1
                )
            )
            val timeoutClient = StreamingApiService.createClient(
                readTimeoutSeconds = 60,
                callTimeoutSeconds = 1
            )

            val events = withTimeout(5_000) {
                StreamingApiService.streamChatCompletion(
                    config = config,
                    request = request,
                    callFactory = timeoutClient
                ).toList()
            }
            serverThread.join(3_000)

            assertNull(serverFailure.get())
            assertTrue(events.any { it is StreamingApiService.StreamEvent.Start })
            val disconnected = events
                .filterIsInstance<StreamingApiService.StreamEvent.Disconnected>()
                .single()
            assertTrue(disconnected.message.contains("网络请求超时"))

            val log = ApiLogStore.entries.value.single()
            assertFalse(log.success)
            assertFalse(log.canceled)
            assertEquals(true, log.retryable)
            assertEquals("run-timeout", log.runId)
            assertTrue(log.errorMessage.orEmpty().contains("网络请求超时"))
        }
    }

    @Test
    fun http502PreservesDiagnosticsAndFailedStreamBodyInRequestLog() = runBlocking {
        ApiLogStore.clear()
        val upstreamRequestId = "d2721a53-5eb3-4968-a43e-1c10100641e2"
        val responseJson = """{"error":{"message":"Upstream request failed (request_ori_id: $upstreamRequestId)","type":"upstream_error"}}"""
        val body = responseJson.toByteArray(Charsets.UTF_8)
        val serverFailure = AtomicReference<Throwable?>()
        val tokenUsages = CopyOnWriteArrayList<ModelTokenUsage>()

        ServerSocket(0).use { server ->
            val serverThread = thread(name = "streaming-api-502-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().buffered().use { output ->
                            val headers = buildString {
                                append("HTTP/1.1 502 Bad Gateway\r\n")
                                append("Content-Type: application/json\r\n")
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
            val config = testConfig(server.localPort)
            val request = testRequest(config.model).copy(
                diagnosticContext = ApiDiagnosticContext(
                    runId = "run-502",
                    operation = "harness.tool_follow_up",
                    iteration = 1
                ),
                tokenUsageListener = tokenUsages::add
            )

            val events = withTimeout(10_000) {
                StreamingApiService.streamChatCompletion(config, request).toList()
            }
            serverThread.join(5_000)

            assertNull(serverFailure.get())
            val error = events.filterIsInstance<StreamingApiService.StreamEvent.Error>().single()
            assertTrue(events.none { it is StreamingApiService.StreamEvent.Disconnected })
            assertEquals(502, error.httpStatus)
            assertTrue(error.retryable)
            assertEquals(upstreamRequestId, error.upstreamRequestId)
            assertTrue(error.diagnosticId.orEmpty().startsWith("mt-"))
            assertTrue(error.message.contains("upstream_error"))

            val log = ApiLogStore.entries.value.single()
            assertEquals(502, log.httpStatus)
            assertEquals(error.diagnosticId, log.traceId)
            assertEquals(upstreamRequestId, log.upstreamRequestId)
            assertEquals(true, log.retryable)
            assertEquals("run-502", log.runId)
            assertEquals("harness.tool_follow_up", log.operation)
            assertEquals(1, log.iteration)
            assertFalse(log.requestBody.contains("diagnosticContext"))
            assertTrue(log.requestBody.contains("\"include_usage\":true"))
            assertTrue(log.responseBody.contains("upstream_error"))
            assertTrue(log.responseBody.contains(upstreamRequestId))
            assertTrue(tokenUsages.isEmpty())
        }
    }

    @Test
    fun openAiSuccessfulStreamReportsExactProviderUsageOnce() = runBlocking {
        val tokenUsages = CopyOnWriteArrayList<ModelTokenUsage>()
        val request = testRequest("test-model").copy(tokenUsageListener = tokenUsages::add)
        val streamBody = buildString {
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n")
            append("data: {\"choices\":[],\"usage\":")
            append("{\"prompt_tokens\":37,\"completion_tokens\":5,\"total_tokens\":42}}\n\n")
            append("data: [DONE]\n\n")
        }

        val events = collectOpenAiSse(streamBody, request)

        assertTrue(events.any { it is StreamingApiService.StreamEvent.Done })
        assertEquals(
            listOf(
                ModelTokenUsage(
                    inputTokens = 37,
                    outputTokens = 5,
                    totalTokens = 42,
                    estimated = false
                )
            ),
            tokenUsages.toList()
        )
    }

    @Test
    fun openAiSuccessfulStreamWithoutUsageReportsEstimatedTokens() = runBlocking {
        val tokenUsages = CopyOnWriteArrayList<ModelTokenUsage>()
        val request = testRequest("test-model").copy(tokenUsageListener = tokenUsages::add)
        val streamBody = buildString {
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }

        val events = collectOpenAiSse(streamBody, request)

        assertTrue(events.any { it is StreamingApiService.StreamEvent.Done })
        val usage = tokenUsages.single()
        assertTrue(usage.estimated)
        assertTrue(usage.inputTokens > 0)
        assertTrue(usage.outputTokens > 0)
        assertEquals(usage.inputTokens + usage.outputTokens, usage.totalTokens)
    }

    @Test
    fun nonStreamingUsageResolverSupportsAnthropicUsage() {
        val usage = ModelTokenUsageResolver.resolve(
            responseBody = """{"usage":{"input_tokens":11,"output_tokens":4}}""",
            requestTexts = listOf("system", "user"),
            responseText = "answer"
        )

        assertEquals(
            ModelTokenUsage(
                inputTokens = 11,
                outputTokens = 4,
                totalTokens = 15,
                estimated = false
            ),
            usage
        )
    }

    @Test
    fun nonStreamingUsageResolverFallsBackToEstimate() {
        val usage = ModelTokenUsageResolver.resolve(
            responseBody = "{}",
            requestTexts = listOf("system", "user"),
            responseText = "answer"
        )

        assertTrue(usage.estimated)
        assertTrue(usage.inputTokens > 0)
        assertTrue(usage.outputTokens > 0)
        assertEquals(usage.inputTokens + usage.outputTokens, usage.totalTokens)
    }

    @Test
    fun rapidSseChunksAreNotDroppedWhenCollectorIsSlow() = runBlocking {
        ApiLogStore.clear()
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
            val log = ApiLogStore.entries.value.single()
            assertEquals(true, log.streamCompleted)
            assertTrue(log.success)
        }
    }

    @Test
    fun inBandStreamErrorUpdatesRequestLogInsteadOfReportingDisconnect() = runBlocking {
        ApiLogStore.clear()
        val streamBody = buildString {
            append("data: ")
            append("{\"error\":{\"message\":\"Upstream overloaded\",")
            append("\"type\":\"upstream_error\",\"request_id\":\"req-stream-1\"}}\n\n")
        }.toByteArray(Charsets.UTF_8)
        val serverFailure = AtomicReference<Throwable?>()

        ServerSocket(0).use { server ->
            val serverThread = thread(name = "streaming-api-event-error-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().buffered().use { output ->
                            val headers = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: text/event-stream\r\n")
                                append("Content-Length: ${streamBody.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }.toByteArray(Charsets.US_ASCII)
                            output.write(headers)
                            output.write(streamBody)
                            output.flush()
                        }
                    }
                }.onFailure(serverFailure::set)
            }
            val config = testConfig(server.localPort)

            val events = withTimeout(10_000) {
                StreamingApiService.streamChatCompletion(
                    config,
                    testRequest(config.model)
                ).toList()
            }
            serverThread.join(5_000)

            assertNull(serverFailure.get())
            val error = events.filterIsInstance<StreamingApiService.StreamEvent.Error>().single()
            assertTrue(events.none { it is StreamingApiService.StreamEvent.Disconnected })
            assertTrue(error.retryable)
            assertEquals("req-stream-1", error.upstreamRequestId)

            val log = ApiLogStore.entries.value.single()
            assertFalse(log.success)
            assertEquals(false, log.streamCompleted)
            assertEquals(true, log.retryable)
            assertEquals("req-stream-1", log.upstreamRequestId)
            assertTrue(log.errorMessage.orEmpty().contains("upstream_error"))
            assertTrue(log.responseBody.contains("req-stream-1"))
            assertTrue(log.responseBody.contains("upstream_error"))
        }
    }

    private fun testConfig(port: Int): ChatCallConfig {
        return ChatCallConfig(
            baseUrl = "http://127.0.0.1:$port",
            apiKey = "",
            model = "test-model",
            apiFormat = ProviderEntity.API_FORMAT_OPENAI
        )
    }

    private fun testRequest(model: String): ChatRequest {
        return ChatRequest(
            model = model,
            messages = listOf(ApiMessage(role = "user", content = "test")),
            stream = true
        )
    }

    private suspend fun collectOpenAiSse(
        streamBody: String,
        request: ChatRequest
    ): List<StreamingApiService.StreamEvent> {
        val body = streamBody.toByteArray(Charsets.UTF_8)
        val serverFailure = AtomicReference<Throwable?>()

        ServerSocket(0).use { server ->
            val serverThread = thread(name = "streaming-api-usage-test-server") {
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
            val config = testConfig(server.localPort)
            val events = withTimeout(10_000) {
                StreamingApiService.streamChatCompletion(config, request).toList()
            }
            serverThread.join(5_000)

            assertNull(serverFailure.get())
            return events
        }
    }
}
