package com.hfad.mantou.utils.project

import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.StreamingApiService
import com.hfad.mantou.data.database.ProviderEntity
import com.hfad.mantou.data.logging.HarnessTraceEvent
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StreamingWebProjectPlannerTest {

    @Test
    fun streamingPlanCarriesDiagnosticContextAndEmitsCorrelatedTrace() = runBlocking {
        val response = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("scripts/app.js", "script")
            )
        )
        val traces = mutableListOf<HarnessTraceEvent>()
        var capturedRequest: ChatRequest? = null
        val planner = StreamingWebProjectPlanner(
            config = testConfig(),
            maxTokens = 4_321,
            traceLogger = HarnessTraceLogger(traces::add),
            streamChatCompletion = { _, request ->
                capturedRequest = request
                flowOf(
                    StreamingApiService.StreamEvent.Start,
                    StreamingApiService.StreamEvent.Thinking("thinking"),
                    StreamingApiService.StreamEvent.Content(response.take(response.length / 2)),
                    StreamingApiService.StreamEvent.Content(response.drop(response.length / 2)),
                    StreamingApiService.StreamEvent.Done
                )
            }
        )

        val result = planner.plan("做一个测试应用", runId = "run-plan-1")

        assertEquals("馒头测试", result.manifest.displayName)
        assertEquals("run-plan-1", capturedRequest?.diagnosticContext?.runId)
        assertEquals("harness.plan", capturedRequest?.diagnosticContext?.operation)
        assertEquals(0, capturedRequest?.diagnosticContext?.iteration)
        assertEquals(4_321, capturedRequest?.maxTokens)
        assertTrue(traces.all { it.runId == "run-plan-1" })
        assertTrue(traces.any {
            it.component == "PLANNER_STREAM" && it.operation == "first_payload" &&
                it.details["payload_type"] == "thinking"
        })
        assertTrue(traces.any {
            it.component == "PLANNER_STREAM" && it.operation == "stream_complete" &&
                it.status == HarnessTraceStatus.SUCCEEDED &&
                it.details["response_chars"] == response.length.toString() &&
                it.details["content_chunks"] == "2"
        })
        assertTrue(traces.any {
            it.component == "PLANNER_PARSE" && it.operation == "parse_plan" &&
                it.status == HarnessTraceStatus.SUCCEEDED &&
                it.details["file_count"] == "4"
        })
        assertTrue(traces.any {
            it.component == "PLANNER" && it.operation == "plan" &&
                it.status == HarnessTraceStatus.SUCCEEDED
        })
        assertFalse(traces.flatMap { it.details.values }.any { it.contains("做一个测试应用") })
        assertFalse(traces.flatMap { it.details.values }.any { it.contains(response) })
    }

    @Test
    fun invalidStreamingPlanLogsParseAndPlanFailuresWithoutRawBody() = runBlocking {
        val invalidResponse = "not valid project json"
        val traces = mutableListOf<HarnessTraceEvent>()
        val planner = StreamingWebProjectPlanner(
            config = testConfig(),
            traceLogger = HarnessTraceLogger(traces::add),
            streamChatCompletion = { _, _ ->
                flowOf(
                    StreamingApiService.StreamEvent.Start,
                    StreamingApiService.StreamEvent.Content(invalidResponse),
                    StreamingApiService.StreamEvent.Done
                )
            }
        )

        val failure = runCatching {
            planner.plan("private requirement", runId = "run-plan-fail")
        }.exceptionOrNull()

        assertTrue(failure is WebAppProjectException)
        assertTrue(traces.any {
            it.component == "PLANNER_PARSE" && it.status == HarnessTraceStatus.FAILED &&
                it.details["phase"] == "parse"
        })
        assertTrue(traces.any {
            it.component == "PLANNER" && it.status == HarnessTraceStatus.FAILED &&
                it.details["response_chars"] == invalidResponse.length.toString()
        })
        assertFalse(traces.flatMap { it.details.values }.any { it.contains(invalidResponse) })
        assertFalse(traces.flatMap { it.details.values }.any { it.contains("private requirement") })
    }

    @Test
    fun validPlanProducesStableManifestAndVisibleProjectJson() {
        val result = WebProjectPlanParser.parse(
            planJson(
                name = "待办",
                files = listOf(
                    file("index.html", "entry", "页面入口"),
                    file("styles/app.css", "style", "视觉系统"),
                    file("scripts/app.js", "logic", "交互逻辑"),
                    file("data/seed.json", "data", "种子数据")
                )
            )
        )

        assertEquals("馒头待办", result.manifest.displayName)
        assertEquals("index.html", result.manifest.entryPoint)
        assertTrue(result.manifest.projectId.startsWith("app-"))
        assertEquals(
            listOf("project.json", "index.html", "styles/app.css", "scripts/app.js", "data/seed.json"),
            result.manifest.files.map(WebAppProjectFile::path)
        )
        assertEquals(
            WebAppProjectFileRole.SCRIPT,
            result.manifest.files.single { it.path == "scripts/app.js" }.role
        )
        assertTrue(result.projectPlanJson.contains("\"name\": \"馒头待办\""))
        assertTrue(result.projectPlanJson.endsWith("\n"))
    }

    @Test
    fun planRequiresIndependentStyleAndScriptFiles() {
        val withoutStyle = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("scripts/app.js", "script"),
                file("data/seed.json", "data")
            )
        )
        val withoutScript = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("data/seed.json", "data")
            )
        )

        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(withoutStyle)
        }
        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(withoutScript)
        }
    }

    @Test
    fun planRejectsDuplicateAndEscapingPaths() {
        val duplicate = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("scripts/app.js", "script"),
                file("scripts/app.js", "script")
            )
        )
        val escaping = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("../scripts/app.js", "script")
            )
        )

        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(duplicate)
        }
        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanParser.parse(escaping)
        }
    }

    @Test
    fun hostValidatorReportsDeclaredFilesMissingFromDraft() {
        val contentRoot = Files.createTempDirectory("mantou-plan-validation").toFile()
        try {
            File(contentRoot, "index.html").writeText(
                "<!doctype html><html><head></head><body>ready</body></html>"
            )
            File(contentRoot, "styles").mkdirs()
            File(contentRoot, "styles/app.css").writeText("body { margin: 0; }")
            val manifest = WebAppProjectManifest(
                projectId = "missing-script",
                displayName = "馒头缺失脚本",
                files = listOf(
                    WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY),
                    WebAppProjectFile("styles/app.css", WebAppProjectFileRole.STYLE),
                    WebAppProjectFile("scripts/app.js", WebAppProjectFileRole.SCRIPT)
                )
            )

            val report = WebAppProjectValidator().validate(manifest, contentRoot)

            assertFalse(report.passed)
            assertTrue(report.diagnostics.any {
                it.code == "DECLARED_FILE_MISSING" && it.path == "scripts/app.js"
            })
        } finally {
            contentRoot.deleteRecursively()
        }
    }

    private fun planJson(
        name: String = "馒头测试",
        files: List<String>
    ): String {
        return """
            {
              "schemaVersion": 1,
              "name": "$name",
              "entry": "index.html",
              "files": [${files.joinToString(",")}]
            }
        """.trimIndent()
    }

    private fun file(
        path: String,
        role: String,
        description: String = path
    ): String {
        return """{"path":"$path","role":"$role","description":"$description"}"""
    }

    private fun testConfig(): ChatCallConfig {
        return ChatCallConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            apiFormat = ProviderEntity.API_FORMAT_OPENAI
        )
    }
}
