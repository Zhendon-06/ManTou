package com.hfad.mantou.utils.harness

import com.hfad.mantou.data.GenerateTaskState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHarnessOrchestratorTest {

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
        assertTrue(
            events.any {
                it.stage == GenerateTaskState.Stage.BUILD &&
                    it.outcome == GenerateTaskState.Outcome.RETRYING
            }
        )
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
