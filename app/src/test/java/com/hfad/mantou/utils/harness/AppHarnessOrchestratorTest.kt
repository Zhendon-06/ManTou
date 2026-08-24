package com.hfad.mantou.utils.harness

import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.data.logging.HarnessTraceEvent
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import com.hfad.mantou.utils.project.WebAppAcceptanceContract
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHarnessOrchestratorTest {

    @Test
    fun traceCapturesToolMetadataGateTransitionsAndScriptHashes() = runBlocking {
        val traces = mutableListOf<HarnessTraceEvent>()
        val turns = ArrayDeque(
            listOf(
                HarnessModelTurn(
                    toolCalls = listOf(
                        HarnessToolCall("read-1", "read_file", mapOf("path" to "index.html"))
                    )
                ),
                HarnessModelTurn(content = "done")
            )
        )
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { turns.removeFirst() },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(
                    callId = request.call.id,
                    success = true,
                    output = "tool-result",
                    metadata = mapOf(
                        "path" to "index.html",
                        "bytes" to "42",
                        "sha256" to "file-sha"
                    )
                )
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            traceLogger = HarnessTraceLogger(traces::add)
        )

        assertTrue(orchestrator.run(newRequest()) is HarnessRunResult.Delivered)

        val toolTrace = traces.single {
            it.component == "TOOL" &&
                it.operation == "read_file" &&
                it.status == HarnessTraceStatus.SUCCEEDED
        }
        assertEquals("index.html", toolTrace.details["path"])
        assertEquals("42", toolTrace.details["bytes"])
        assertEquals("file-sha", toolTrace.details["sha256"])
        assertEquals(64, toolTrace.details["output_sha256"]?.length)
        assertTrue(
            traces.any {
                it.operation == "gate_transition" &&
                    it.details["from_gate"] == "CODING" &&
                    it.details["to_gate"] == "DEVELOPMENT_BUILD"
            }
        )
        assertTrue(
            traces.any {
                it.component == "CHECK" &&
                    it.operation == HarnessCheckKind.SELF_TEST.name &&
                    it.status == HarnessTraceStatus.STARTED &&
                    it.details["script_sha256"]?.length == 64
            }
        )
    }

    @Test
    fun happyPathRunsToolBuildInspectionSelfTestAndFinalGates() = runBlocking {
        val modelRequests = mutableListOf<HarnessModelRequest>()
        val modelTurns = ArrayDeque(
            listOf(
                HarnessModelTurn(
                    toolCalls = listOf(
                        HarnessToolCall("write-1", "apply_diff", mapOf("diff" to "patch"))
                    )
                ),
                HarnessModelTurn(content = "代码编排完成")
            )
        )
        val toolRequests = mutableListOf<HarnessToolRequest>()
        val buildKinds = mutableListOf<HarnessCheckKind>()
        val testKinds = mutableListOf<HarnessCheckKind>()
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelRequests += request
                modelTurns.removeFirst()
            },
            fileTool = HarnessFileTool { request ->
                toolRequests += request
                HarnessToolResult(
                    callId = request.call.id,
                    success = true,
                    output = "patched",
                    changedFiles = listOf("generated_apps/app/index.html"),
                    artifactPath = "generated_apps/app/index.html"
                )
            },
            builder = HarnessBuilder { request ->
                buildKinds += request.kind
                HarnessCheckResult(true, "build ok", artifactPath = request.artifactPath)
            },
            inspector = HarnessInspector { request ->
                assertEquals(HarnessCheckKind.RUNTIME, request.kind)
                HarnessCheckResult(true, "runtime ok")
            },
            testRunner = HarnessTestRunner { request ->
                testKinds += request.kind
                when (request.kind) {
                    HarnessCheckKind.SELF_TEST -> assertEquals("self-test-script", request.testScript)
                    HarnessCheckKind.TEST_SUITE -> assertEquals("test-suite-script", request.testScript)
                    else -> throw AssertionError("Unexpected test kind ${request.kind}")
                }
                HarnessCheckResult(true, "tests ok")
            }
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(1, result.iterations)
        assertEquals("generated_apps/app/index.html", result.artifactPath)
        assertEquals(2, modelRequests.size)
        assertEquals(HarnessModelPurpose.INITIAL, modelRequests[0].purpose)
        assertEquals(HarnessModelPurpose.TOOL_FOLLOW_UP, modelRequests[1].purpose)
        assertEquals(
            listOf("write-1"),
            modelRequests[1].messages
                .first { it.role == HarnessMessageRole.ASSISTANT && it.toolCalls.isNotEmpty() }
                .toolCalls
                .map(HarnessToolCall::id)
        )
        assertEquals(1, toolRequests.size)
        assertEquals(
            listOf(HarnessCheckKind.DEVELOPMENT_BUILD, HarnessCheckKind.FINAL_BUILD),
            buildKinds
        )
        assertEquals(
            listOf(
                HarnessCheckKind.DEVELOPMENT_BUILD.name to GenerateTaskState.Outcome.RUNNING,
                HarnessCheckKind.DEVELOPMENT_BUILD.name to GenerateTaskState.Outcome.PASSED,
                HarnessCheckKind.FINAL_BUILD.name to GenerateTaskState.Outcome.RUNNING,
                HarnessCheckKind.FINAL_BUILD.name to GenerateTaskState.Outcome.PASSED
            ),
            events
                .filter { it.stage == GenerateTaskState.Stage.BUILD }
                .map { it.operation to it.outcome }
        )
        assertEquals(
            listOf(HarnessCheckKind.SELF_TEST, HarnessCheckKind.TEST_SUITE),
            testKinds
        )
        assertTrue(
            events.any {
                it.stage == GenerateTaskState.Stage.DELIVER &&
                    it.outcome == GenerateTaskState.Outcome.PASSED
            }
        )
    }

    @Test
    fun acceptanceContractIsOnlyExposedToTestSuite() = runBlocking {
        val contract = WebAppAcceptanceContract(criteria = emptyList())
        val checkRequests = mutableListOf<HarnessCheckRequest>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { HarnessModelTurn(content = "done") },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, success = true, output = "ok")
            },
            builder = HarnessBuilder { request ->
                checkRequests += request
                HarnessCheckResult(true, "build ok")
            },
            inspector = HarnessInspector { request ->
                checkRequests += request
                HarnessCheckResult(true, "runtime ok")
            },
            testRunner = HarnessTestRunner { request ->
                checkRequests += request
                HarnessCheckResult(true, "tests ok")
            }
        )

        val result = orchestrator.run(
            newRequest().copy(
                acceptanceContract = contract,
                acceptanceRequired = true
            )
        )

        assertTrue(result is HarnessRunResult.Delivered)
        checkRequests.filterNot { it.kind == HarnessCheckKind.TEST_SUITE }.forEach { request ->
            assertNull(request.acceptanceContract)
            assertFalse(request.acceptanceRequired)
        }
        val testSuiteRequest = checkRequests.single { it.kind == HarnessCheckKind.TEST_SUITE }
        assertEquals(contract, testSuiteRequest.acceptanceContract)
        assertTrue(testSuiteRequest.acceptanceRequired)
    }

    @Test
    fun selfTestFailureTwiceIsBypassedBeforeFinalBuild() = runBlocking {
        val modelPurposes = mutableListOf<HarnessModelPurpose>()
        val buildKinds = mutableListOf<HarnessCheckKind>()
        val testKinds = mutableListOf<HarnessCheckKind>()
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        var selfTestRuns = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelPurposes += request.purpose
                HarnessModelTurn(content = "ready")
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { request ->
                buildKinds += request.kind
                HarnessCheckResult(true, "build ok")
            },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { request ->
                testKinds += request.kind
                if (request.kind == HarnessCheckKind.SELF_TEST) {
                    selfTestRuns++
                    HarnessCheckResult(false, "自测失败", listOf("case failed"))
                } else {
                    HarnessCheckResult(true, "tests ok")
                }
            }
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Delivered)
        assertTrue((result as HarnessRunResult.Delivered).selfTestBypassed)
        assertEquals(2, result.iterations)
        assertEquals(2, selfTestRuns)
        assertEquals(
            listOf(
                HarnessCheckKind.DEVELOPMENT_BUILD,
                HarnessCheckKind.DEVELOPMENT_BUILD,
                HarnessCheckKind.FINAL_BUILD
            ),
            buildKinds
        )
        assertEquals(
            listOf(
                HarnessCheckKind.SELF_TEST,
                HarnessCheckKind.SELF_TEST,
                HarnessCheckKind.TEST_SUITE
            ),
            testKinds
        )
        assertEquals(
            listOf(HarnessModelPurpose.INITIAL, HarnessModelPurpose.REPAIR),
            modelPurposes
        )
        val bypass = events.single { it.operation == "SELF_TEST_BYPASS" }
        assertEquals(GenerateTaskState.Outcome.PASSED, bypass.outcome)
        assertTrue(bypass.message.contains("第 2 次"))
        assertTrue(bypass.diagnostics.contains("self_test_policy=advisory"))
    }

    @Test
    fun selfTestFailureWithoutRemainingIterationBudgetDoesNotBlockDelivery() = runBlocking {
        var selfTestRuns = 0
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { HarnessModelTurn(content = "ready") },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { request ->
                if (request.kind == HarnessCheckKind.SELF_TEST) {
                    selfTestRuns++
                    HarnessCheckResult(false, "自测失败")
                } else {
                    HarnessCheckResult(true, "tests ok")
                }
            },
            limits = HarnessLimits(maxCodeIterations = 1)
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Delivered)
        assertTrue((result as HarnessRunResult.Delivered).selfTestBypassed)
        assertEquals(1, result.iterations)
        assertEquals(1, selfTestRuns)
        assertTrue(events.any { it.operation == "SELF_TEST_BYPASS" })
    }

    @Test
    fun failedBuildFeedsDiagnosticsBackToModelAndRebuilds() = runBlocking {
        val modelRequests = mutableListOf<HarnessModelRequest>()
        val modelTurns = ArrayDeque(
            listOf(
                HarnessModelTurn(
                    toolCalls = listOf(HarnessToolCall("initial", "write_file"))
                ),
                HarnessModelTurn(),
                HarnessModelTurn(
                    toolCalls = listOf(HarnessToolCall("repair", "apply_diff"))
                ),
                HarnessModelTurn()
            )
        )
        var developmentBuilds = 0
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelRequests += request
                modelTurns.removeFirst()
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(
                    callId = request.call.id,
                    success = true,
                    output = "changed",
                    artifactPath = "generated_apps/app/index.html"
                )
            },
            builder = HarnessBuilder { request ->
                if (request.kind == HarnessCheckKind.DEVELOPMENT_BUILD) {
                    developmentBuilds++
                    if (developmentBuilds == 1) {
                        HarnessCheckResult(
                            passed = false,
                            summary = "JavaScript 编译失败",
                            diagnostics = listOf("index.html:42 Unexpected token")
                        )
                    } else {
                        HarnessCheckResult(true, "build ok")
                    }
                } else {
                    HarnessCheckResult(true, "final build ok")
                }
            },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") }
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, result.iterations)
        assertEquals(2, developmentBuilds)
        val repairRequest = modelRequests.first { it.purpose == HarnessModelPurpose.REPAIR }
        assertEquals(listOf("index.html:42 Unexpected token"), repairRequest.diagnostics)
        assertTrue(
            repairRequest.messages.last().content.contains("JavaScript 编译失败")
        )
        assertTrue(
            repairRequest.messages.last().content.contains("继续调用本地代码工具")
        )
        val failedBuild = events.single {
            it.stage == GenerateTaskState.Stage.BUILD &&
                it.outcome == GenerateTaskState.Outcome.FAILED
        }
        assertEquals(HarnessCheckKind.DEVELOPMENT_BUILD.name, failedBuild.operation)
        val retryingBuild = events.single {
            it.stage == GenerateTaskState.Stage.BUILD &&
                it.outcome == GenerateTaskState.Outcome.RETRYING
        }
        assertEquals(HarnessCheckKind.DEVELOPMENT_BUILD.name, retryingBuild.operation)
    }

    @Test
    fun failedFinalBuildKeepsOperationThroughRetry() = runBlocking {
        var finalBuilds = 0
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { HarnessModelTurn(content = "ready") },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { request ->
                if (request.kind == HarnessCheckKind.FINAL_BUILD && finalBuilds++ == 0) {
                    HarnessCheckResult(
                        passed = false,
                        summary = "交付前构建失败",
                        diagnostics = listOf("bundle.js:8 unresolved import")
                    )
                } else {
                    HarnessCheckResult(true, "build ok")
                }
            },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") }
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Delivered)
        val failedBuild = events.single {
            it.stage == GenerateTaskState.Stage.BUILD &&
                it.outcome == GenerateTaskState.Outcome.FAILED
        }
        assertEquals(HarnessCheckKind.FINAL_BUILD.name, failedBuild.operation)
        val retryingBuild = events.single {
            it.stage == GenerateTaskState.Stage.BUILD &&
                it.outcome == GenerateTaskState.Outcome.RETRYING
        }
        assertEquals(HarnessCheckKind.FINAL_BUILD.name, retryingBuild.operation)
    }

    @Test
    fun failedFinalTestSuiteReturnsToDevelopmentPipeline() = runBlocking {
        val modelPurposes = mutableListOf<HarnessModelPurpose>()
        val buildKinds = mutableListOf<HarnessCheckKind>()
        val testKinds = mutableListOf<HarnessCheckKind>()
        var suiteRuns = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelPurposes += request.purpose
                HarnessModelTurn(content = "ready")
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { request ->
                buildKinds += request.kind
                HarnessCheckResult(true, "build ok")
            },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { request ->
                testKinds += request.kind
                if (request.kind == HarnessCheckKind.TEST_SUITE && suiteRuns++ == 0) {
                    HarnessCheckResult(
                        passed = false,
                        summary = "回归测试失败",
                        diagnostics = listOf("todo delete flow failed")
                    )
                } else {
                    HarnessCheckResult(true, "tests ok")
                }
            }
        )

        val result = orchestrator.run(newRequest())

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, result.iterations)
        assertEquals(
            listOf(
                HarnessCheckKind.DEVELOPMENT_BUILD,
                HarnessCheckKind.FINAL_BUILD,
                HarnessCheckKind.DEVELOPMENT_BUILD,
                HarnessCheckKind.FINAL_BUILD
            ),
            buildKinds
        )
        assertEquals(
            listOf(
                HarnessCheckKind.SELF_TEST,
                HarnessCheckKind.TEST_SUITE,
                HarnessCheckKind.SELF_TEST,
                HarnessCheckKind.TEST_SUITE
            ),
            testKinds
        )
        assertEquals(
            listOf(HarnessModelPurpose.INITIAL, HarnessModelPurpose.REPAIR),
            modelPurposes
        )
    }

    @Test
    fun toolFailureIsReturnedToModelInsteadOfEndingRun() = runBlocking {
        val modelRequests = mutableListOf<HarnessModelRequest>()
        var toolCalls = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelRequests += request
                when (modelRequests.size) {
                    1 -> HarnessModelTurn(
                        toolCalls = listOf(HarnessToolCall("bad", "apply_diff"))
                    )

                    2 -> {
                        assertTrue(request.messages.last().content.startsWith("failure"))
                        HarnessModelTurn(
                            toolCalls = listOf(HarnessToolCall("fixed", "apply_diff"))
                        )
                    }

                    else -> HarnessModelTurn()
                }
            },
            fileTool = HarnessFileTool { request ->
                toolCalls++
                if (request.call.id == "bad") {
                    HarnessToolResult(
                        callId = request.call.id,
                        success = false,
                        output = "context mismatch",
                        diagnostics = listOf("hunk 1 did not match")
                    )
                } else {
                    HarnessToolResult(
                        callId = request.call.id,
                        success = true,
                        output = "patched",
                        artifactPath = "generated_apps/app/index.html"
                    )
                }
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") }
        )

        val result = orchestrator.run(newRequest())

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, toolCalls)
        assertEquals(3, modelRequests.size)
        assertTrue(modelRequests.all { it.iteration == 1 })
    }

    @Test
    fun protocolFailureIsReturnedToModelWithSingleActionCorrection() = runBlocking {
        val modelRequests = mutableListOf<HarnessModelRequest>()
        var requestCount = 0
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelRequests += request
                if (requestCount++ == 0) {
                    throw HarnessModelProtocolException(
                        "每次模型响应只允许一个动作：" +
                            "<mantou-read path=\"project.json\"/><mantou-read path=\"project.json\"/>"
                    )
                }
                HarnessModelTurn()
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(
                maxModelRequestRetries = 1,
                modelRetryBaseDelayMs = 0
            )
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, modelRequests.size)
        assertEquals(modelRequests[0].messages, modelRequests[1].messages.dropLast(1))
        val correction = modelRequests[1].messages.last()
        assertEquals(HarnessMessageRole.USER, correction.role)
        assertTrue(correction.content.contains("每次模型响应只允许一个动作"))
        assertTrue(correction.content.contains("只返回一个且仅一个"))
        assertTrue(correction.content.contains("&lt;mantou-read"))
        assertFalse(correction.content.contains("<mantou-read"))
        assertTrue(
            events.any {
                it.stage == GenerateTaskState.Stage.MODEL &&
                    it.outcome == GenerateTaskState.Outcome.RETRYING &&
                    it.diagnostics.any { detail ->
                        detail.startsWith("每次模型响应只允许一个动作")
                    }
            }
        )
    }

    @Test
    fun networkFailureRetriesTheOriginalRequestUnchanged() = runBlocking {
        val modelRequests = mutableListOf<HarnessModelRequest>()
        var requestCount = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelRequests += request
                if (requestCount++ == 0) {
                    throw HarnessModelTransportException("网络状态出错: 连接已断开")
                }
                HarnessModelTurn()
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(
                maxModelRequestRetries = 1,
                modelRetryBaseDelayMs = 0
            )
        )

        val result = orchestrator.run(newRequest())

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, modelRequests.size)
        assertEquals(modelRequests[0], modelRequests[1])
    }

    @Test
    fun nonNetworkTransportFailureIsNotRetried() = runBlocking {
        var requestCount = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair {
                requestCount++
                throw HarnessModelTransportException("请求失败 (401) unauthorized")
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxModelRequestRetries = 2)
        )

        val result = orchestrator.run(newRequest())

        assertTrue(result is HarnessRunResult.Failed)
        assertEquals("请求失败 (401) unauthorized", (result as HarnessRunResult.Failed).reason)
        assertEquals(1, requestCount)
    }

    @Test
    fun upstream502GetsFreshRetryBudgetAfterToolFollowUp() = runBlocking {
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        var followUpAttempts = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                when (request.purpose) {
                    HarnessModelPurpose.INITIAL -> HarnessModelTurn(
                        toolCalls = listOf(
                            HarnessToolCall(
                                id = "read-1",
                                name = "read_file",
                                arguments = mapOf("path" to "project.json")
                            )
                        )
                    )

                    HarnessModelPurpose.TOOL_FOLLOW_UP -> {
                        followUpAttempts++
                        if (followUpAttempts == 1) {
                            throw HarnessModelTransportException(
                                message = "请求失败 (502) upstream_error",
                                isNetworkFailure = true,
                                httpStatus = 502,
                                upstreamRequestId = "provider-request-502",
                                diagnosticId = "mt-trace-502"
                            )
                        }
                        HarnessModelTurn()
                    }

                    HarnessModelPurpose.REPAIR -> HarnessModelTurn()
                }
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "{}")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(
                maxModelRequestRetries = 1,
                modelRetryBaseDelayMs = 0
            )
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, followUpAttempts)
        val retry = events.single {
            it.stage == GenerateTaskState.Stage.MODEL &&
                it.outcome == GenerateTaskState.Outcome.RETRYING &&
                it.diagnostics.contains("http_status=502")
        }
        assertTrue(retry.diagnostics.contains("diagnostic_id=mt-trace-502"))
        assertTrue(retry.diagnostics.contains("upstream_request_id=provider-request-502"))
        assertTrue(retry.diagnostics.contains("attempt=1/2"))
    }

    @Test
    fun exhaustedUpstreamRetriesEmitStructuredFailureDiagnostics() = runBlocking {
        var requestCount = 0
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair {
                requestCount++
                throw HarnessModelTransportException(
                    message = "请求失败 (503) unavailable",
                    isNetworkFailure = true,
                    httpStatus = 503,
                    upstreamRequestId = "provider-request-503",
                    diagnosticId = "mt-trace-503"
                )
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(
                maxModelRequestRetries = 2,
                modelRetryBaseDelayMs = 0
            )
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Failed)
        assertEquals(3, requestCount)
        val failure = events.single {
            it.stage == GenerateTaskState.Stage.MODEL &&
                it.outcome == GenerateTaskState.Outcome.FAILED
        }
        assertEquals("模型请求在 3 次尝试后仍失败", failure.message)
        assertTrue(failure.diagnostics.contains("attempt=3/3"))
        assertTrue(failure.diagnostics.contains("http_status=503"))
        assertTrue((result as HarnessRunResult.Failed).diagnostics.contains("diagnostic_id=mt-trace-503"))
    }

    @Test
    fun legacyCodingIterationCompletesWithinConfiguredTaskTurnLimit() = runBlocking {
        val interactionCount = 2
        var modelTurns = 0
        var toolCalls = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair {
                val turnIndex = modelTurns++
                if (turnIndex < interactionCount) {
                    HarnessModelTurn(
                        toolCalls = listOf(
                            HarnessToolCall(
                                id = "tool-$turnIndex",
                                name = "read_file",
                                arguments = mapOf("path" to "project.json")
                            )
                        )
                    )
                } else {
                    HarnessModelTurn()
                }
            },
            fileTool = HarnessFileTool { request ->
                toolCalls++
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxTaskTurns = interactionCount + 1)
        )

        val result = withTimeout(5_000) {
            orchestrator.run(newRequest())
        }

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(interactionCount + 1, modelTurns)
        assertEquals(interactionCount, toolCalls)
        assertEquals(1, result.iterations)
    }

    @Test
    fun unexpectedModelFailureIsNotRetried() = runBlocking {
        var requestCount = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair {
                requestCount++
                throw IllegalStateException("模型客户端内部错误")
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxModelRequestRetries = 2)
        )

        val result = orchestrator.run(newRequest())

        assertTrue(result is HarnessRunResult.Failed)
        assertEquals("模型客户端内部错误", (result as HarnessRunResult.Failed).reason)
        assertEquals(1, requestCount)
    }

    @Test
    fun repeatedFailuresStopAtConfiguredCodeIterationLimit() = runBlocking {
        val events = mutableListOf<GenerateTaskState.HarnessEvent>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { HarnessModelTurn(content = "attempted repair") },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder {
                HarnessCheckResult(false, "still broken", listOf("syntax error"))
            },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxCodeIterations = 2)
        )

        val result = orchestrator.run(newRequest()) { events += it }

        assertTrue(result is HarnessRunResult.Failed)
        assertEquals(2, result.iterations)
        assertTrue((result as HarnessRunResult.Failed).reason.contains("2 轮上限"))
        assertTrue(
            events.last().stage == GenerateTaskState.Stage.DELIVER &&
                events.last().outcome == GenerateTaskState.Outcome.FAILED
        )
    }

    @Test
    fun legacyCodingIterationStopsAtConfiguredTaskTurnLimit() = runBlocking {
        var modelRequestCount = 0
        var toolExecutionCount = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair {
                modelRequestCount++
                HarnessModelTurn(
                    toolCalls = listOf(
                        HarnessToolCall(
                            id = "read-$modelRequestCount",
                            name = "read_file",
                            arguments = mapOf("path" to "index.html")
                        )
                    )
                )
            },
            fileTool = HarnessFileTool { request ->
                toolExecutionCount++
                HarnessToolResult(request.call.id, true, "ok")
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxTaskTurns = 2)
        )

        val result = withTimeout(5_000) {
            orchestrator.run(newRequest())
        }

        assertTrue(result is HarnessRunResult.Failed)
        assertEquals(1, result.iterations)
        assertTrue((result as HarnessRunResult.Failed).reason.contains("2 次模型请求"))
        assertEquals(2, modelRequestCount)
        assertEquals(2, toolExecutionCount)
    }

    @Test
    fun scheduledGenerationUsesStableDependencyOrderAndFreshTaskContexts() = runBlocking {
        val modelRequests = mutableListOf<HarnessModelRequest>()
        val toolCalls = mutableListOf<HarnessToolCall>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                modelRequests += request
                val target = request.metadata.getValue("task_path")
                HarnessModelTurn(
                    toolCalls = listOf(
                        HarnessToolCall(
                            id = "write-$target",
                            name = "write_file",
                            arguments = mapOf("path" to target, "content" to target)
                        )
                    )
                )
            },
            fileTool = HarnessFileTool { request ->
                toolCalls += request.call
                val path = request.call.arguments.getValue("path")
                if (request.call.name == "write_file") {
                    HarnessToolResult(
                        callId = request.call.id,
                        success = true,
                        output = "written",
                        changedFiles = listOf(path)
                    )
                } else {
                    HarnessToolResult(request.call.id, true, "content:$path")
                }
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") }
        )
        val request = newRequest().copy(
            planPath = "project.json",
            fileTasks = listOf(
                HarnessFileTask("index.html", dependsOn = listOf("styles.css", "app.js")),
                HarnessFileTask("app.js"),
                HarnessFileTask("styles.css")
            )
        )

        val result = orchestrator.run(request)

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(1, result.iterations)
        assertEquals(
            listOf("app.js", "styles.css", "index.html"),
            modelRequests.map { it.metadata.getValue("task_path") }
        )
        assertEquals(
            listOf("app.js", "styles.css", "index.html"),
            toolCalls.filter { it.name == "write_file" }.map { it.arguments.getValue("path") }
        )
        assertTrue(modelRequests.all { requestForTask ->
            requestForTask.messages
                .flatMap(HarnessMessage::toolCalls)
                .filter { it.name == "write_file" }
                .none()
        })
        val indexReads = modelRequests.last().messages
            .flatMap(HarnessMessage::toolCalls)
            .filter { it.name == "read_file" }
            .map { it.arguments.getValue("path") }
        assertEquals(listOf("project.json", "styles.css", "app.js"), indexReads)
    }

    @Test
    fun scheduledGenerationRejectsOutOfScopeWriteBeforeExecutingTool() = runBlocking {
        var modelTurns = 0
        val executedWrites = mutableListOf<String>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair {
                modelTurns++
                val path = if (modelTurns == 1) "other.js" else "app.js"
                HarnessModelTurn(
                    toolCalls = listOf(
                        HarnessToolCall(
                            id = "write-$modelTurns",
                            name = "write_file",
                            arguments = mapOf("path" to path, "content" to "content")
                        )
                    )
                )
            },
            fileTool = HarnessFileTool { request ->
                val path = request.call.arguments.getValue("path")
                if (request.call.name == "write_file") executedWrites += path
                HarnessToolResult(
                    callId = request.call.id,
                    success = true,
                    output = "ok",
                    changedFiles = if (request.call.name == "write_file") listOf(path) else emptyList()
                )
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxTaskTurns = 2)
        )

        val result = orchestrator.run(
            newRequest().copy(fileTasks = listOf(HarnessFileTask("app.js")))
        )

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(listOf("app.js"), executedWrites)
        assertEquals(2, modelTurns)
    }

    @Test
    fun scheduledGenerationRequiresTargetInChangedFilesBeforeCompletingTask() = runBlocking {
        var modelTurns = 0
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair {
                modelTurns++
                HarnessModelTurn(
                    toolCalls = listOf(
                        HarnessToolCall(
                            id = "write-$modelTurns",
                            name = "write_file",
                            arguments = mapOf("path" to "app.js", "content" to "content")
                        )
                    )
                )
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(
                    callId = request.call.id,
                    success = true,
                    output = "ok",
                    changedFiles = if (modelTurns == 1) emptyList() else listOf("app.js")
                )
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxTaskTurns = 2)
        )

        val result = orchestrator.run(
            newRequest().copy(fileTasks = listOf(HarnessFileTask("app.js")))
        )

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, modelTurns)
    }

    @Test
    fun scheduledGenerationDoesNotAcceptFinishBeforeTargetWrite() = runBlocking {
        val requests = mutableListOf<HarnessModelRequest>()
        val orchestrator = AppHarnessOrchestrator(
            modelRepair = HarnessModelRepair { request ->
                requests += request
                if (requests.size == 1) {
                    HarnessModelTurn(content = "<mantou-finish/>")
                } else {
                    HarnessModelTurn(
                        toolCalls = listOf(
                            HarnessToolCall(
                                id = "write-app",
                                name = "write_file",
                                arguments = mapOf("path" to "app.js", "content" to "content")
                            )
                        )
                    )
                }
            },
            fileTool = HarnessFileTool { request ->
                HarnessToolResult(
                    callId = request.call.id,
                    success = true,
                    output = "ok",
                    changedFiles = listOf("app.js")
                )
            },
            builder = HarnessBuilder { HarnessCheckResult(true, "build ok") },
            inspector = HarnessInspector { HarnessCheckResult(true, "runtime ok") },
            testRunner = HarnessTestRunner { HarnessCheckResult(true, "tests ok") },
            limits = HarnessLimits(maxTaskTurns = 2)
        )

        val result = orchestrator.run(
            newRequest().copy(fileTasks = listOf(HarnessFileTask("app.js")))
        )

        assertTrue(result is HarnessRunResult.Delivered)
        assertEquals(2, requests.size)
        assertTrue(requests.last().messages.last().content.contains("TASK_INCOMPLETE_TARGET_NOT_WRITTEN"))
    }

    private fun newRequest(): HarnessRunRequest {
        return HarnessRunRequest(
            runId = "run-1",
            workspacePath = "/workspace/generated_apps/app",
            systemPrompt = "你是代码生成 Agent。",
            userInput = "做一个待办应用",
            selfTestScript = "self-test-script",
            testSuiteScript = "test-suite-script"
        )
    }
}
