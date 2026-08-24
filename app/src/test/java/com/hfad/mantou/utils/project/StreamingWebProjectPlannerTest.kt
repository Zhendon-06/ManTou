package com.hfad.mantou.utils.project

import com.hfad.mantou.data.ChatMessage
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StreamingWebProjectPlannerTest {

    @Test
    fun streamingPlanCarriesDiagnosticContextAndEmitsCorrelatedTrace() = runBlocking {
        val response = structuredPlanJson()
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
        assertEquals("Create the first item", result.requireAppSpec().primaryGoal)
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
                it.details["file_count"] == "4" &&
                it.details["app_spec_version"] == "1" &&
                it.details["acceptance_criteria_count"] == "3"
        })
        assertTrue(traces.any {
            it.component == "PLANNER" && it.operation == "plan" &&
                it.status == HarnessTraceStatus.SUCCEEDED
        })
        assertFalse(traces.flatMap { it.details.values }.any { it.contains("做一个测试应用") })
        assertFalse(traces.flatMap { it.details.values }.any { it.contains(response) })
    }

    @Test
    fun invalidPrefacedPlanIsRepairedOnceWithStrictCodecFeedback() = runBlocking {
        val invalidResponse = "Here is the requested plan:\n${structuredPlanJson()}"
        val repairedResponse = structuredPlanJson()
        val requests = mutableListOf<ChatRequest>()
        val traces = mutableListOf<HarnessTraceEvent>()
        val planner = StreamingWebProjectPlanner(
            config = testConfig(),
            traceLogger = HarnessTraceLogger(traces::add),
            streamChatCompletion = { _, request ->
                requests += request
                val output = if (requests.size == 1) invalidResponse else repairedResponse
                flowOf(
                    StreamingApiService.StreamEvent.Start,
                    StreamingApiService.StreamEvent.Content(output),
                    StreamingApiService.StreamEvent.Done
                )
            }
        )

        val result = planner.plan("做一个测试应用", runId = "run-plan-repair")

        assertEquals("馒头测试", result.manifest.displayName)
        assertEquals(2, requests.size)
        assertEquals(listOf(0, 1), requests.map { it.diagnosticContext?.iteration })
        assertEquals("harness.plan", requests.last().diagnosticContext?.operation)
        assertEquals(4, requests.last().messages.size)
        assertEquals(ChatMessage.ROLE_ASSISTANT, requests.last().messages[2].role)
        assertEquals(invalidResponse, requests.last().messages[2].content)
        val repairFeedback = requests.last().messages.last().content as String
        assertTrue(repairFeedback.contains("PROJECT_PLAN_CODEC_REJECTED"))
        assertTrue(repairFeedback.contains("模型返回的项目计划不是合法 JSON"))
        assertTrue(repairFeedback.contains("不要输出 Markdown、前言、解释或省略内容"))
        assertTrue(traces.any {
            it.component == "PLANNER_PARSE" &&
                it.status == HarnessTraceStatus.FAILED &&
                it.iteration == 0
        })
        assertTrue(traces.any {
            it.component == "PLANNER_REPAIR" &&
                it.status == HarnessTraceStatus.SUCCEEDED &&
                it.iteration == 1
        })
    }

    @Test
    fun validationFailureFeedsDiagnosticCodesIntoRepairRequest() = runBlocking {
        val invalidResponse = structuredPlanJson().replace(
            "\"type\":\"CLICK\",\"target\":\"[data-testid='add-button']\"",
            "\"type\":\"CLICK\",\"target\":\"[data-testid='missing']\""
        )
        val requests = mutableListOf<ChatRequest>()
        val planner = StreamingWebProjectPlanner(
            config = testConfig(),
            streamChatCompletion = { _, request ->
                requests += request
                val output = if (requests.size == 1) invalidResponse else structuredPlanJson()
                flowOf(
                    StreamingApiService.StreamEvent.Content(output),
                    StreamingApiService.StreamEvent.Done
                )
            }
        )

        val result = planner.plan("做一个测试应用")

        assertEquals("馒头测试", result.manifest.displayName)
        assertEquals(2, requests.size)
        assertTrue(
            (requests.last().messages.last().content as String)
                .contains("APP_SPEC_ACTION_TARGET_UNKNOWN")
        )
    }

    @Test
    fun invalidStreamingPlanLogsParseAndPlanFailuresWithoutRawBody() = runBlocking {
        val invalidResponse = "not valid project json"
        val traces = mutableListOf<HarnessTraceEvent>()
        val requests = mutableListOf<ChatRequest>()
        val planner = StreamingWebProjectPlanner(
            config = testConfig(),
            traceLogger = HarnessTraceLogger(traces::add),
            streamChatCompletion = { _, request ->
                requests += request
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
        assertEquals(2, requests.size)
        assertEquals(listOf(0, 1), requests.map { it.diagnosticContext?.iteration })
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
        assertEquals("交互逻辑", result.manifest.files.single { it.path == "scripts/app.js" }.description)
        assertNull(result.appSpec)
        assertTrue(result.projectPlanJson.contains("\"name\": \"馒头待办\""))
        assertTrue(result.projectPlanJson.endsWith("\n"))
    }

    @Test
    fun structuredPlanProducesTypedContractAndStableRoundTrip() {
        val result = WebProjectPlanCodec.parse(structuredPlanJson(), requireAppSpec = true)

        val spec = result.requireAppSpec()
        val coreCriterion = spec.acceptanceContract.criteria.first { it.id == "AC-001" }
        assertEquals(WebAppStatePersistence.MANTOU_STORAGE, spec.state.persistence)
        assertEquals("app-state-v1", spec.state.storageKey)
        assertEquals(
            WebAppAcceptanceActionType.CLICK,
            coreCriterion.actions.single().type
        )
        assertEquals(
            WebAppAcceptanceAssertionType.COUNT_EQUALS,
            coreCriterion.expected.first().type
        )
        assertEquals(
            "app-state-v1",
            coreCriterion.expected.last().target
        )
        assertEquals(
            listOf(
                WebAppAcceptanceCoverage.CORE_SUCCESS,
                WebAppAcceptanceCoverage.PERSISTENCE
            ),
            coreCriterion.covers
        )
        assertEquals(
            listOf("index.html", "styles/app.css"),
            result.manifest.files.single { it.path == "scripts/app.js" }.dependsOn
        )
        assertEquals(
            listOf("AC-001", "AC-002", "AC-003"),
            result.manifest.files.single { it.path == "scripts/app.js" }.ownsCriteria
        )

        val reparsed = WebProjectPlanCodec.parse(result.projectPlanJson, requireAppSpec = true)

        assertEquals(spec, reparsed.appSpec)
        assertEquals(
            result.manifest.files.map { it.copy(sha256 = null) },
            reparsed.manifest.files.map { it.copy(sha256 = null) }
        )
        assertTrue(result.projectPlanJson.contains("\"acceptanceContract\""))
        assertTrue(result.projectPlanJson.contains("\"dependsOn\""))
    }

    @Test
    fun hostAppSpecPolicyRejectsAnyMutation() {
        val baseline = WebProjectPlanCodec.parse(
            structuredPlanJson(),
            requireAppSpec = true
        ).requireAppSpec()
        val originalCriterion = baseline.acceptanceContract.criteria.first { it.id == "AC-001" }
        val addedCriterion = originalCriterion.copy(id = "AC-004", title = "Additional check")

        assertTrue(
            WebAppSpecPolicy.preservesBaseline(
                baseline,
                baseline
            )
        )
        assertFalse(
            WebAppSpecPolicy.preservesBaseline(
                baseline,
                baseline.copy(
                    acceptanceContract = baseline.acceptanceContract.copy(
                        criteria = baseline.acceptanceContract.criteria + addedCriterion
                    )
                )
            )
        )
        assertFalse(
            WebAppSpecPolicy.preservesBaseline(
                baseline,
                baseline.copy(
                    design = baseline.design.copy(minTouchTargetPx = 1)
                )
            )
        )
        assertFalse(WebAppSpecPolicy.preservesBaseline(baseline, null))
        assertTrue(WebAppSpecPolicy.preservesBaseline(null, baseline))
    }

    @Test
    fun structuredPlanRejectsUnknownAcceptanceTargetAndDependencyCycle() {
        val unknownTarget = structuredPlanJson().replace(
            "\"type\":\"CLICK\",\"target\":\"[data-testid='add-button']\"",
            "\"type\":\"CLICK\",\"target\":\"[data-testid='missing']\""
        )
        val dependencyCycle = structuredPlanJson().replace(
            "\"path\":\"index.html\",\"role\":\"entry\",\"description\":\"Page shell\",\"dependsOn\":[]",
            "\"path\":\"index.html\",\"role\":\"entry\",\"description\":\"Page shell\",\"dependsOn\":[\"scripts/app.js\"]"
        )

        val targetFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(unknownTarget, requireAppSpec = true)
        }
        val cycleFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(dependencyCycle, requireAppSpec = true)
        }

        assertTrue(targetFailure.report.diagnostics.any {
            it.code == "APP_SPEC_ACTION_TARGET_UNKNOWN"
        })
        assertTrue(cycleFailure.report.diagnostics.any {
            it.code == "PROJECT_FILE_DEPENDENCY_CYCLE"
        })
    }

    @Test
    fun structuredPlanRejectsActionsAndStorageAssertionsMissingTargets() {
        val keyPressWithoutTarget = structuredPlanJson().replace(
            "{\"type\":\"CLICK\",\"target\":\"[data-testid='add-button']\"}",
            "{\"type\":\"KEY_PRESS\",\"value\":\"Enter\"}"
        )
        val storageAssertionWithoutKey = structuredPlanJson().replace(
            "{\"type\":\"COUNT_EQUALS\",\"target\":\"[data-testid='item-row']\",\"count\":1}",
            "{\"type\":\"STORAGE_EQUALS\",\"value\":\"[]\"}"
        )

        val actionFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(keyPressWithoutTarget, requireAppSpec = true)
        }
        val assertionFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(storageAssertionWithoutKey, requireAppSpec = true)
        }

        assertTrue(actionFailure.report.diagnostics.any {
            it.code == "APP_SPEC_ACTION_TARGET_MISSING"
        })
        assertTrue(assertionFailure.report.diagnostics.any {
            it.code == "APP_SPEC_ASSERTION_TARGET_MISSING"
        })
    }

    @Test
    fun structuredPlanAppliesUniformActionTimeoutRules() {
        val excessiveClickTimeout = structuredPlanJson().replace(
            "{\"type\":\"CLICK\",\"target\":\"[data-testid='add-button']\"}",
            "{\"type\":\"CLICK\",\"target\":\"[data-testid='add-button']\",\"timeoutMs\":10001}"
        )
        val invalidWaitValue = structuredPlanJson().replace(
            "{\"type\":\"CLICK\",\"target\":\"[data-testid='add-button']\"}",
            "{\"type\":\"WAIT\",\"value\":\"soon\"}"
        )
        val zeroWait = structuredPlanJson().replace(
            "{\"type\":\"CLICK\",\"target\":\"[data-testid='add-button']\"}",
            "{\"type\":\"WAIT\",\"timeoutMs\":0}"
        )

        val clickFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(excessiveClickTimeout, requireAppSpec = true)
        }
        val waitFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(invalidWaitValue, requireAppSpec = true)
        }

        assertTrue(clickFailure.report.diagnostics.any {
            it.code == "APP_SPEC_ACTION_TIMEOUT_INVALID"
        })
        assertTrue(waitFailure.report.diagnostics.any {
            it.code == "APP_SPEC_ACTION_TIMEOUT_INVALID"
        })
        assertEquals(
            0,
            WebProjectPlanCodec.parse(zeroWait, requireAppSpec = true)
                .requireAppSpec().acceptanceContract.criteria
                .first { it.id == "AC-001" }
                .actions.single().timeoutMs
        )
    }

    @Test
    fun structuredPlanRejectsNoOpCriteriaAndBlankContainsAssertions() {
        val missingAction = structuredPlanJson().replace(
            "\"actions\":[{\"type\":\"FOCUS\",\"target\":\"[data-testid='item-input']\"}]",
            "\"actions\":[]"
        )
        val waitOnly = structuredPlanJson().replace(
            "\"actions\":[{\"type\":\"FOCUS\",\"target\":\"[data-testid='item-input']\"}]",
            "\"actions\":[{\"type\":\"WAIT\",\"timeoutMs\":0}]"
        )
        val blankTextContains = structuredPlanJson().replace(
            "{\"type\":\"VISIBLE\",\"target\":\"[data-testid='empty-state']\"}",
            "{\"type\":\"TEXT_CONTAINS\",\"target\":\"[data-testid='empty-state']\",\"value\":\"  \"}"
        )
        val blankUrlContains = structuredPlanJson().replace(
            "{\"type\":\"VISIBLE\",\"target\":\"[data-testid='empty-state']\"}",
            "{\"type\":\"URL_CONTAINS\",\"value\":\"\"}"
        )

        assertValidationCode(missingAction, "APP_SPEC_CRITERION_ACTION_MISSING")
        assertValidationCode(waitOnly, "APP_SPEC_CRITERION_ACTION_MISSING")
        assertValidationCode(blankTextContains, "APP_SPEC_ASSERTION_VALUE_EMPTY")
        assertValidationCode(blankUrlContains, "APP_SPEC_ASSERTION_VALUE_EMPTY")
    }

    @Test
    fun structuredPlanRequiresStorageAssertionToUseDeclaredKeyAndJsonValue() {
        val mismatchedKey = structuredPlanJson().replace(
            "\"type\":\"STORAGE_EQUALS\",\"target\":\"app-state-v1\"",
            "\"type\":\"STORAGE_EQUALS\",\"target\":\"other-state\""
        )
        val invalidJson = structuredPlanJson().replace(
            "\"value\":\"{\\\"items\\\":[\\\"Test item\\\"]}\"",
            "\"value\":\"not-json\""
        )
        val missingAssertion = structuredPlanJson().replace(
            "{\"type\":\"STORAGE_EQUALS\",\"target\":\"app-state-v1\",\"value\":\"{\\\"items\\\":[\\\"Test item\\\"]}\"}",
            "{\"type\":\"EXISTS\",\"target\":\"[data-testid='item-row']\"}"
        )

        val keyFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(mismatchedKey, requireAppSpec = true)
        }
        val valueFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(invalidJson, requireAppSpec = true)
        }
        val coverageFailure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(missingAssertion, requireAppSpec = true)
        }

        assertTrue(keyFailure.report.diagnostics.any {
            it.code == "APP_SPEC_STORAGE_ASSERTION_KEY_MISMATCH"
        })
        assertTrue(valueFailure.report.diagnostics.any {
            it.code == "APP_SPEC_STORAGE_ASSERTION_VALUE_INVALID"
        })
        assertTrue(coverageFailure.report.diagnostics.any {
            it.code == "APP_SPEC_STORAGE_ASSERTION_MISSING"
        })
    }

    @Test
    fun structuredPlanRequiresTypedScenarioCoverage() {
        val missingCore = structuredPlanJson().replace(
            "\"covers\":[\"CORE_SUCCESS\",\"PERSISTENCE\"]",
            "\"covers\":[\"PERSISTENCE\"]"
        )
        val missingEmpty = structuredPlanJson().replace(
            "\"covers\":[\"EMPTY_STATE\"]",
            "\"covers\":[\"ERROR_STATE\"]"
        )
        val missingError = structuredPlanJson().replace(
            "\"covers\":[\"ERROR_STATE\"]",
            "\"covers\":[\"EMPTY_STATE\"]"
        )
        val nonP0Core = structuredPlanJson().replaceFirst(
            "\"priority\":\"P0\"",
            "\"priority\":\"P1\""
        )
        val missingPersistenceAssertion = structuredPlanJson().replace(
            "{\"type\":\"STORAGE_EQUALS\",\"target\":\"app-state-v1\",\"value\":\"{\\\"items\\\":[\\\"Test item\\\"]}\"}",
            "{\"type\":\"EXISTS\",\"target\":\"[data-testid='item-row']\"}"
        )
        val duplicateCoverage = structuredPlanJson().replace(
            "\"covers\":[\"EMPTY_STATE\"]",
            "\"covers\":[\"EMPTY_STATE\",\"EMPTY_STATE\"]"
        )

        assertValidationCode(missingCore, "APP_SPEC_CORE_SUCCESS_COVERAGE_MISSING")
        assertValidationCode(missingEmpty, "APP_SPEC_EMPTY_STATE_COVERAGE_MISSING")
        assertValidationCode(missingError, "APP_SPEC_ERROR_STATE_COVERAGE_MISSING")
        assertValidationCode(nonP0Core, "APP_SPEC_CORE_SUCCESS_PRIORITY_INVALID")
        assertValidationCode(
            missingPersistenceAssertion,
            "APP_SPEC_PERSISTENCE_ASSERTION_MISSING"
        )
        assertValidationCode(duplicateCoverage, "APP_SPEC_CRITERION_COVERAGE_DUPLICATE")
    }

    @Test
    fun structuredPlanRequiresUniqueDataTestIdsAndFlowCoverage() {
        val duplicateSelector = structuredPlanJson().replace(
            "{\"id\":\"item-row\",\"selector\":\"[data-testid='item-row']\",\"purpose\":\"Display an item\"}",
            "{\"id\":\"item-row\",\"selector\":\"[data-testid='add-button']\",\"purpose\":\"Display an item\"}"
        )
        val invalidSelector = structuredPlanJson().replace(
            "{\"id\":\"item-input\",\"selector\":\"[data-testid='item-input']\",\"purpose\":\"Enter item text\"}",
            "{\"id\":\"item-input\",\"selector\":\"#item-input\",\"purpose\":\"Enter item text\"}"
        )
        val unreferencedCriterion = structuredPlanJson().replace(
            "\"criterionIds\":[\"AC-003\"]",
            "\"criterionIds\":[\"AC-002\"]"
        )

        assertValidationCode(duplicateSelector, "APP_SPEC_SELECTOR_VALUE_DUPLICATE")
        assertValidationCode(invalidSelector, "APP_SPEC_SELECTOR_FORMAT_INVALID")
        assertValidationCode(
            unreferencedCriterion,
            "APP_SPEC_CRITERION_USER_FLOW_UNREFERENCED"
        )
    }

    @Test
    fun plannerCodecKeepsLegacyPlansReadableButCanRequireAppSpec() {
        val legacy = planJson(
            files = listOf(
                file("index.html", "entry"),
                file("styles/app.css", "style"),
                file("scripts/app.js", "script")
            )
        )

        val parsed = WebProjectPlanCodec.parse(legacy)

        assertNull(parsed.appSpec)
        assertThrows(WebAppProjectException::class.java) {
            WebProjectPlanCodec.parse(legacy, requireAppSpec = true)
        }
    }

    @Test
    fun manifestCodecPersistsStructuredSpecAndFileContracts() {
        val temporaryRoot = Files.createTempDirectory("mantou-app-spec-manifest").toFile()
        try {
            val manifestFile = File(temporaryRoot, "manifest.json")
            val original = WebProjectPlanCodec.parse(
                structuredPlanJson(),
                requireAppSpec = true
            ).manifest

            WebAppProjectManifestCodec.write(manifestFile, original)
            val restored = WebAppProjectManifestCodec.read(manifestFile)

            assertEquals(original, restored)
            assertEquals(
                listOf("AC-001", "AC-002", "AC-003"),
                restored.files.single { it.path == "scripts/app.js" }.ownsCriteria
            )
            assertEquals(
                WebAppAcceptancePriority.P0,
                restored.appSpec?.acceptanceContract?.criteria
                    ?.first { it.id == "AC-001" }
                    ?.priority
            )
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    @Test
    fun manifestCodecNormalizesFilesWrittenBeforePlanningMetadata() {
        val temporaryRoot = Files.createTempDirectory("mantou-legacy-manifest").toFile()
        try {
            val manifestFile = File(temporaryRoot, "manifest.json").apply {
                writeText(
                    """
                        {
                          "schemaVersion":1,
                          "projectId":"legacy-plan-metadata",
                          "displayName":"Legacy",
                          "entryPoint":"index.html",
                          "files":[
                            {"path":"index.html","role":"ENTRY","required":true}
                          ]
                        }
                    """.trimIndent()
                )
            }

            val restored = WebAppProjectManifestCodec.read(manifestFile)
            val entry = restored.files.single()

            assertEquals("", entry.description)
            assertTrue(entry.dependsOn.isEmpty())
            assertTrue(entry.ownsCriteria.isEmpty())
            assertNull(restored.appSpec)
        } finally {
            temporaryRoot.deleteRecursively()
        }
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

    private fun assertValidationCode(json: String, code: String) {
        val failure = assertThrows(WebAppProjectValidationException::class.java) {
            WebProjectPlanCodec.parse(json, requireAppSpec = true)
        }
        assertTrue(
            "Expected validation code $code but got ${failure.report.diagnostics.map { it.code }}",
            failure.report.diagnostics.any { it.code == code }
        )
    }

    private fun structuredPlanJson(): String {
        return """
            {
              "schemaVersion":1,
              "name":"馒头测试",
              "entry":"index.html",
              "files":[
                {"path":"index.html","role":"entry","description":"Page shell","dependsOn":[],"ownsCriteria":[]},
                {"path":"styles/app.css","role":"style","description":"Visual system","dependsOn":["index.html"],"ownsCriteria":[]},
                {"path":"scripts/app.js","role":"script","description":"State and interactions","dependsOn":["index.html","styles/app.css"],"ownsCriteria":["AC-001","AC-002","AC-003"]}
              ],
              "appSpec":{
                "specVersion":1,
                "summary":"A small item tracker",
                "primaryGoal":"Create the first item",
                "selectors":[
                  {"id":"item-input","selector":"[data-testid='item-input']","purpose":"Enter item text"},
                  {"id":"add-button","selector":"[data-testid='add-button']","purpose":"Create an item"},
                  {"id":"item-row","selector":"[data-testid='item-row']","purpose":"Display an item"},
                  {"id":"empty-state","selector":"[data-testid='empty-state']","purpose":"Display the empty state"},
                  {"id":"input-error","selector":"[data-testid='input-error']","purpose":"Display input errors"}
                ],
                "screens":[
                  {"id":"main","title":"Items","purpose":"Manage items","selectorIds":["item-input","add-button","item-row","empty-state","input-error"]}
                ],
                "components":[
                  {"id":"item-form","name":"Item form","purpose":"Collect item text","selectorIds":["item-input","add-button","input-error"],"states":["idle","invalid"]}
                ],
                "state":{
                  "persistence":"MANTOU_STORAGE",
                  "storageKey":"app-state-v1",
                  "fields":[{"name":"items","type":"array","description":"Saved items","initialValue":"[]"}],
                  "emptyState":"Show a prompt when there are no items",
                  "errorStates":["Reject empty item text"]
                },
                "interactions":[
                  {"id":"add-item","title":"Add item","triggerSelectorId":"add-button","outcome":"Append and render an item","stateChanges":["items append"]},
                  {"id":"view-empty","title":"View empty state","triggerSelectorId":"item-input","outcome":"Keep the empty state visible","stateChanges":[]},
                  {"id":"reject-empty","title":"Reject empty item","triggerSelectorId":"item-input","outcome":"Show an input error","stateChanges":[]}
                ],
                "userFlows":[
                  {"id":"create-item","title":"Create an item","interactionIds":["add-item"],"criterionIds":["AC-001"]},
                  {"id":"view-empty-flow","title":"View the empty state","interactionIds":["view-empty"],"criterionIds":["AC-002"]},
                  {"id":"reject-empty-flow","title":"Reject empty input","interactionIds":["reject-empty"],"criterionIds":["AC-003"]}
                ],
                "design":{
                  "theme":"Calm mobile utility",
                  "tokens":[
                    {"name":"color-primary","value":"#4F46E5","purpose":"Primary actions"},
                    {"name":"space-md","value":"16px","purpose":"Standard spacing"},
                    {"name":"radius-md","value":"12px","purpose":"Controls and cards"}
                  ],
                  "constraints":["No horizontal overflow","Readable contrast"],
                  "viewportWidths":[360,393],
                  "minTouchTargetPx":44
                },
                "acceptanceContract":{
                  "criteria":[
                    {
                      "id":"AC-001",
                      "title":"User creates an item",
                      "priority":"P0",
                      "covers":["CORE_SUCCESS","PERSISTENCE"],
                      "setup":[{"type":"INPUT","target":"[data-testid='item-input']","value":"Test item"}],
                      "actions":[{"type":"CLICK","target":"[data-testid='add-button']"}],
                      "expected":[
                        {"type":"COUNT_EQUALS","target":"[data-testid='item-row']","count":1},
                        {"type":"STORAGE_EQUALS","target":"app-state-v1","value":"{\"items\":[\"Test item\"]}"}
                      ]
                    },
                    {
                      "id":"AC-002",
                      "title":"Empty state is visible",
                      "priority":"P1",
                      "covers":["EMPTY_STATE"],
                      "actions":[{"type":"FOCUS","target":"[data-testid='item-input']"}],
                      "expected":[{"type":"VISIBLE","target":"[data-testid='empty-state']"}]
                    },
                    {
                      "id":"AC-003",
                      "title":"Empty input is rejected",
                      "priority":"P1",
                      "covers":["ERROR_STATE"],
                      "actions":[{"type":"BLUR","target":"[data-testid='item-input']"}],
                      "expected":[
                        {"type":"VISIBLE","target":"[data-testid='input-error']"},
                        {"type":"COUNT_EQUALS","target":"[data-testid='item-row']","count":0}
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()
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
