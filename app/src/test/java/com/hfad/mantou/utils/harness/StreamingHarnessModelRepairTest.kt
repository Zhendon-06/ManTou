package com.hfad.mantou.utils.harness

import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.StreamingApiService
import com.hfad.mantou.data.database.ProviderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingHarnessModelRepairTest {

    @Test
    fun parsesWriteAndPreservesRawBody() {
        val body = """

            <!doctype html>
            <html><body data-value="a & b">Hello</body></html>

        """.trimIndent()

        val turn = HarnessSingleActionProtocol.parse(
            "<mantou-write path=\"app/index.html\">$body</mantou-write>",
            callId = "call-1"
        )

        val call = turn.toolCalls.single()
        assertEquals("call-1", call.id)
        assertEquals("write_file", call.name)
        assertEquals("app/index.html", call.arguments["path"])
        assertEquals(body, call.arguments["content"])
        assertTrue(turn.content.isEmpty())
    }

    @Test
    fun parsesReadListDeleteAndFinishActions() {
        val read = HarnessSingleActionProtocol.parse(
            "<mantou-read path=\"src/app.js\"/>",
            "read-1"
        )
        val list = HarnessSingleActionProtocol.parse(
            "<mantou-list path=\".\" />",
            "list-1"
        )
        val delete = HarnessSingleActionProtocol.parse(
            "<mantou-delete path=\"src/old.js\"/>",
            "delete-1"
        )
        val finish = HarnessSingleActionProtocol.parse("<mantou-finish />", "finish-1")

        assertEquals("read_file", read.toolCalls.single().name)
        assertEquals("src/app.js", read.toolCalls.single().arguments["path"])
        assertEquals("list_files", list.toolCalls.single().name)
        assertEquals(".", list.toolCalls.single().arguments["path"])
        assertEquals("delete_file", delete.toolCalls.single().name)
        assertEquals("src/old.js", delete.toolCalls.single().arguments["path"])
        assertTrue(finish.toolCalls.isEmpty())
        assertEquals("<mantou-finish/>", finish.content)
    }

    @Test
    fun decodesSupportedEntitiesInPath() {
        val turn = HarnessSingleActionProtocol.parse(
            "<mantou-read path=\"docs/a&amp;b.txt\"/>",
            "read-1"
        )

        assertEquals("docs/a&b.txt", turn.toolCalls.single().arguments["path"])
    }

    @Test
    fun rejectsProseMultipleActionsAndUnsafePaths() {
        assertThrows(HarnessModelProtocolException::class.java) {
            HarnessSingleActionProtocol.parse(
                "I will read it. <mantou-read path=\"app.html\"/>",
                "call-1"
            )
        }
        assertThrows(HarnessModelProtocolException::class.java) {
            HarnessSingleActionProtocol.parse(
                "<mantou-read path=\"a\"/><mantou-read path=\"b\"/>",
                "call-2"
            )
        }
        assertThrows(HarnessModelProtocolException::class.java) {
            HarnessSingleActionProtocol.parse(
                "<mantou-delete path=\"../outside.html\"/>",
                "call-3"
            )
        }
        assertThrows(HarnessModelProtocolException::class.java) {
            HarnessSingleActionProtocol.parse(
                "<mantou-write path=\"app.html\">x<mantou-finish/></mantou-write>",
                "call-4"
            )
        }
        assertThrows(HarnessModelProtocolException::class.java) {
            HarnessSingleActionProtocol.parse(
                "<mantou-read path=\"src/./app.js\"/>",
                "call-5"
            )
        }
    }

    @Test
    fun compactsWriteBodiesInAssistantHistory() {
        val originalBody = "x".repeat(4_096)
        val apiMessages = HarnessSingleActionProtocol.toApiMessages(
            listOf(
                HarnessMessage(HarnessMessageRole.SYSTEM, "system"),
                HarnessMessage(HarnessMessageRole.USER, "build"),
                HarnessMessage(
                    role = HarnessMessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        HarnessToolCall(
                            id = "write-1",
                            name = "write_file",
                            arguments = mapOf(
                                "path" to "index.html",
                                "content" to originalBody
                            )
                        )
                    )
                ),
                HarnessMessage(
                    role = HarnessMessageRole.TOOL,
                    content = "success",
                    toolCallId = "write-1",
                    toolName = "write_file"
                )
            )
        )

        val assistantHistory = apiMessages.first { it.role == "assistant" }.content as String
        assertTrue(assistantHistory.contains("<mantou-history-omitted chars=\"4096\""))
        assertTrue(assistantHistory.contains("sha256=\""))
        assertFalse(assistantHistory.contains(originalBody))
        assertTrue((apiMessages.first().content as String).contains("exactly one action"))
        assertTrue(
            (apiMessages.last().content as String).contains(
                "<mantou-tool-result call-id=\"write-1\" name=\"write_file\">"
            )
        )
    }

    @Test
    fun streamsThinkingReportsProgressAndUsesMaxTokens() = runBlocking {
        var capturedRequest: ChatRequest? = null
        val thinking = mutableListOf<String>()
        val progress = mutableListOf<HarnessModelStreamProgress>()
        val repair = StreamingHarnessModelRepair(
            config = testConfig(),
            maxTokens = 12_345,
            onThinking = thinking::add,
            onProgress = progress::add,
            streamChatCompletion = { _, request ->
                capturedRequest = request
                events(
                    StreamingApiService.StreamEvent.Start,
                    StreamingApiService.StreamEvent.Thinking("checking"),
                    StreamingApiService.StreamEvent.Content("<mantou-read "),
                    StreamingApiService.StreamEvent.Content("path=\"index.html\"/>"),
                    StreamingApiService.StreamEvent.Done
                )
            }
        )

        val turn = repair.requestTurn(modelRequest())

        assertEquals(12_345, capturedRequest?.maxTokens)
        assertEquals(listOf("checking"), thinking)
        assertEquals("read_file", turn.toolCalls.single().name)
        assertEquals(
            listOf(
                HarnessModelStreamPhase.STARTED,
                HarnessModelStreamPhase.THINKING,
                HarnessModelStreamPhase.RECEIVING,
                HarnessModelStreamPhase.RECEIVING,
                HarnessModelStreamPhase.COMPLETED
            ),
            progress.map(HarnessModelStreamProgress::phase)
        )
        assertEquals("<mantou-read path=\"index.html\"/>".length, progress.last().receivedChars)
    }

    @Test
    fun transportErrorsAreThrownAndReported() = runBlocking {
        val progress = mutableListOf<HarnessModelStreamProgress>()
        val repair = StreamingHarnessModelRepair(
            config = testConfig(),
            onProgress = progress::add,
            streamChatCompletion = { _, _ ->
                events(
                    StreamingApiService.StreamEvent.Start,
                    StreamingApiService.StreamEvent.Error("unauthorized")
                )
            }
        )

        assertThrows(HarnessModelTransportException::class.java) {
            runBlocking { repair.requestTurn(modelRequest()) }
        }
        assertEquals(HarnessModelStreamPhase.FAILED, progress.last().phase)
        assertEquals("unauthorized", progress.last().error)
    }

    private fun modelRequest(): HarnessModelRequest {
        return HarnessModelRequest(
            runId = "run-1",
            purpose = HarnessModelPurpose.INITIAL,
            iteration = 1,
            messages = listOf(
                HarnessMessage(HarnessMessageRole.SYSTEM, "system"),
                HarnessMessage(HarnessMessageRole.USER, "build an app")
            )
        )
    }

    private fun testConfig(): ChatCallConfig {
        return ChatCallConfig(
            baseUrl = "https://example.com/v1",
            apiKey = "key",
            model = "test-model",
            apiFormat = ProviderEntity.API_FORMAT_OPENAI
        )
    }

    private fun events(vararg events: StreamingApiService.StreamEvent): Flow<StreamingApiService.StreamEvent> {
        return flowOf(*events)
    }
}
