package com.hfad.mantou.utils.harness

import com.hfad.mantou.data.GenerateTaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AppHarnessOrchestrator(
    private val modelRepair: HarnessModelRepair,
    private val fileTool: HarnessFileTool,
    private val builder: HarnessBuilder,
    private val inspector: HarnessInspector,
    private val testRunner: HarnessTestRunner,
    private val promptFilter: HarnessPromptFilter = DefaultHarnessPromptFilter,
    private val limits: HarnessLimits = HarnessLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun run(
        request: HarnessRunRequest,
        onEvent: suspend (GenerateTaskState.HarnessEvent) -> Unit = {}
    ): HarnessRunResult {
        var artifactPath = request.artifactPath
        var iteration = 0
        val messages = mutableListOf<HarnessMessage>()

        suspend fun emit(
            stage: GenerateTaskState.Stage,
            outcome: GenerateTaskState.Outcome,
            message: String,
            diagnostics: List<String> = emptyList()
        ) {
            onEvent(
                GenerateTaskState.HarnessEvent(
                    stage = stage,
                    outcome = outcome,
                    message = message,
                    iteration = iteration,
                    diagnostics = diagnostics,
                    timestamp = clock()
                )
            )
        }

        val preparedPrompt = try {
            emit(
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = "正在过滤用户输入和 System Prompt"
            )
            promptFilter.prepare(request.systemPrompt, request.userInput)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val reason = error.message ?: "输入过滤失败"
            emit(
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.FAILED,
                message = reason,
                diagnostics = listOf(reason)
            )
            return HarnessRunResult.Failed(
                runId = request.runId,
                iterations = iteration,
                artifactPath = artifactPath,
                reason = reason,
                diagnostics = listOf(reason)
            )
        }

        emit(
            stage = GenerateTaskState.Stage.INPUT,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "输入过滤完成",
            diagnostics = preparedPrompt.notices
        )
        emit(
            stage = GenerateTaskState.Stage.PROMPT,
            outcome = GenerateTaskState.Outcome.RUNNING,
            message = "正在组装 Harness 提示词"
        )
        messages += HarnessMessage(HarnessMessageRole.SYSTEM, preparedPrompt.systemPrompt)
        messages += HarnessMessage(HarnessMessageRole.USER, preparedPrompt.userPrompt)
        emit(
            stage = GenerateTaskState.Stage.PROMPT,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "Harness 提示词已就绪"
        )

        suspend fun runCodingIteration(
            purpose: HarnessModelPurpose,
            feedback: RepairFeedback? = null
        ) {
            currentCoroutineContext().ensureActive()
            if (iteration >= limits.maxCodeIterations) {
                throw HarnessLimitException("自动修复已达到 ${limits.maxCodeIterations} 轮上限")
            }
            iteration++
            if (feedback != null) {
                val diagnostics = compactDiagnostics(feedback.diagnostics)
                messages += HarnessMessage(
                    role = HarnessMessageRole.USER,
                    content = buildRepairPrompt(feedback.stage, feedback.summary, diagnostics)
                )
                emit(
                    stage = feedback.stage,
                    outcome = GenerateTaskState.Outcome.RETRYING,
                    message = "检查未通过，返回模型继续修改",
                    diagnostics = diagnostics
                )
            }

            var activePurpose = purpose
            var modelTurns = 0
            var toolCalls = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (modelTurns >= limits.maxModelTurnsPerIteration) {
                    throw HarnessLimitException("单轮模型与工具交互超过 ${limits.maxModelTurnsPerIteration} 次")
                }
                modelTurns++
                emit(
                    stage = GenerateTaskState.Stage.MODEL,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = when (activePurpose) {
                        HarnessModelPurpose.INITIAL -> "正在请求模型规划并生成下一个项目文件"
                        HarnessModelPurpose.TOOL_FOLLOW_UP -> "正在把工具结果回传模型"
                        HarnessModelPurpose.REPAIR -> "正在请求模型规划下一项诊断修复"
                    },
                    diagnostics = feedback?.diagnostics.orEmpty().let(::compactDiagnostics)
                )

                val turn = requestModelTurn(
                    request = HarnessModelRequest(
                        runId = request.runId,
                        purpose = activePurpose,
                        iteration = iteration,
                        messages = messages.toList(),
                        diagnostics = feedback?.diagnostics.orEmpty().let(::compactDiagnostics),
                        metadata = request.metadata
                    ),
                    emit = ::emit
                )
                if (turn.content.isNotBlank() || turn.toolCalls.isNotEmpty()) {
                    messages += HarnessMessage(
                        role = HarnessMessageRole.ASSISTANT,
                        content = turn.content,
                        toolCalls = turn.toolCalls
                    )
                }
                emit(
                    stage = GenerateTaskState.Stage.MODEL,
                    outcome = GenerateTaskState.Outcome.PASSED,
                    message = if (turn.toolCalls.isEmpty()) {
                        "模型已完成本轮代码编排"
                    } else {
                        "模型请求调用 ${turn.toolCalls.size} 个本地代码工具"
                    }
                )
                if (turn.toolCalls.isEmpty()) return

                for (call in turn.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    toolCalls++
                    if (toolCalls > limits.maxToolCallsPerIteration) {
                        throw HarnessLimitException("单轮工具调用超过 ${limits.maxToolCallsPerIteration} 次")
                    }
                    emit(
                        stage = GenerateTaskState.Stage.TOOL,
                        outcome = GenerateTaskState.Outcome.RUNNING,
                        message = buildString {
                            append("正在调用代码工具 ").append(call.name)
                            call.arguments["path"]?.takeIf(String::isNotBlank)?.let { path ->
                                append(" · ").append(path)
                            }
                        }
                    )
                    val result = executeTool(request, call, iteration)
                    artifactPath = result.artifactPath ?: artifactPath
                    messages += HarnessMessage(
                        role = HarnessMessageRole.TOOL,
                        content = buildToolResultMessage(result),
                        toolCallId = call.id,
                        toolName = call.name
                    )
                    emit(
                        stage = GenerateTaskState.Stage.TOOL,
                        outcome = if (result.success) {
                            GenerateTaskState.Outcome.PASSED
                        } else {
                            GenerateTaskState.Outcome.FAILED
                        },
                        message = if (result.success) {
                            buildString {
                                append("代码工具 ").append(call.name).append(" 执行完成")
                                result.changedFiles.singleOrNull()?.let { append(" · ").append(it) }
                            }
                        } else {
                            "代码工具 ${call.name} 执行失败，结果将回传模型"
                        },
                        diagnostics = compactDiagnostics(result.diagnostics)
                    )
                }
                activePurpose = HarnessModelPurpose.TOOL_FOLLOW_UP
            }
        }

        suspend fun runCheck(
            kind: HarnessCheckKind,
            runner: suspend (HarnessCheckRequest) -> HarnessCheckResult
        ): HarnessCheckResult {
            currentCoroutineContext().ensureActive()
            val stage = kind.eventStage()
            emit(
                stage = stage,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = kind.runningMessage()
            )
            val checkRequest = HarnessCheckRequest(
                runId = request.runId,
                workspacePath = request.workspacePath,
                artifactPath = artifactPath,
                kind = kind,
                iteration = iteration,
                testScript = when (kind) {
                    HarnessCheckKind.SELF_TEST -> request.selfTestScript
                    HarnessCheckKind.TEST_SUITE -> request.testSuiteScript
                    else -> null
                },
                metadata = request.metadata
            )
            val result = try {
                runner(checkRequest)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                HarnessCheckResult(
                    passed = false,
                    summary = error.message ?: "${kind.displayName()}执行失败",
                    diagnostics = listOf(error.stackTraceToString().take(MAX_STACK_TRACE_CHARS))
                )
            }
            artifactPath = result.artifactPath ?: artifactPath
            emit(
                stage = stage,
                outcome = if (result.passed) {
                    GenerateTaskState.Outcome.PASSED
                } else {
                    GenerateTaskState.Outcome.FAILED
                },
                message = result.summary.ifBlank {
                    if (result.passed) "${kind.displayName()}通过" else "${kind.displayName()}未通过"
                },
                diagnostics = compactDiagnostics(result.diagnostics)
            )
            return result.copy(diagnostics = compactDiagnostics(result.diagnostics))
        }

        return try {
            runCodingIteration(HarnessModelPurpose.INITIAL)
            var gate = HarnessGate.DEVELOPMENT_BUILD
            while (true) {
                currentCoroutineContext().ensureActive()
                when (gate) {
                    HarnessGate.DEVELOPMENT_BUILD -> {
                        val result = runCheck(HarnessCheckKind.DEVELOPMENT_BUILD, builder::build)
                        if (result.passed) {
                            gate = HarnessGate.RUNTIME_INSPECTION
                        } else {
                            runCodingIteration(
                                purpose = HarnessModelPurpose.REPAIR,
                                feedback = result.toRepairFeedback(GenerateTaskState.Stage.BUILD)
                            )
                        }
                    }

                    HarnessGate.RUNTIME_INSPECTION -> {
                        val result = runCheck(HarnessCheckKind.RUNTIME, inspector::inspect)
                        if (result.passed) {
                            gate = HarnessGate.SELF_TEST
                        } else {
                            runCodingIteration(
                                purpose = HarnessModelPurpose.REPAIR,
                                feedback = result.toRepairFeedback(GenerateTaskState.Stage.INSPECT)
                            )
                            gate = HarnessGate.DEVELOPMENT_BUILD
                        }
                    }

                    HarnessGate.SELF_TEST -> {
                        val result = runCheck(HarnessCheckKind.SELF_TEST, testRunner::runTests)
                        if (result.passed) {
                            gate = HarnessGate.FINAL_BUILD
                        } else {
                            runCodingIteration(
                                purpose = HarnessModelPurpose.REPAIR,
                                feedback = result.toRepairFeedback(GenerateTaskState.Stage.SELF_TEST)
                            )
                            gate = HarnessGate.DEVELOPMENT_BUILD
                        }
                    }

                    HarnessGate.FINAL_BUILD -> {
                        val result = runCheck(HarnessCheckKind.FINAL_BUILD, builder::build)
                        if (result.passed) {
                            gate = HarnessGate.TEST_SUITE
                        } else {
                            runCodingIteration(
                                purpose = HarnessModelPurpose.REPAIR,
                                feedback = result.toRepairFeedback(GenerateTaskState.Stage.BUILD)
                            )
                            gate = HarnessGate.DEVELOPMENT_BUILD
                        }
                    }

                    HarnessGate.TEST_SUITE -> {
                        val result = runCheck(HarnessCheckKind.TEST_SUITE, testRunner::runTests)
                        if (result.passed) {
                            emit(
                                stage = GenerateTaskState.Stage.DELIVER,
                                outcome = GenerateTaskState.Outcome.PASSED,
                                message = "构建、运行检查和测试均已通过，可以交付"
                            )
                            return HarnessRunResult.Delivered(
                                runId = request.runId,
                                iterations = iteration,
                                artifactPath = artifactPath,
                                filteredUserInput = preparedPrompt.filteredUserInput
                            )
                        }
                        runCodingIteration(
                            purpose = HarnessModelPurpose.REPAIR,
                            feedback = result.toRepairFeedback(GenerateTaskState.Stage.TEST)
                        )
                        gate = HarnessGate.DEVELOPMENT_BUILD
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Harness state machine exited unexpectedly")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val reason = error.message ?: "Harness 编排失败"
            val diagnostics = listOf(reason)
            emit(
                stage = GenerateTaskState.Stage.DELIVER,
                outcome = GenerateTaskState.Outcome.FAILED,
                message = reason,
                diagnostics = diagnostics
            )
            HarnessRunResult.Failed(
                runId = request.runId,
                iterations = iteration,
                artifactPath = artifactPath,
                reason = reason,
                diagnostics = diagnostics
            )
        }
    }

    private suspend fun requestModelTurn(
        request: HarnessModelRequest,
        emit: suspend (
            GenerateTaskState.Stage,
            GenerateTaskState.Outcome,
            String,
            List<String>
        ) -> Unit
    ): HarnessModelTurn {
        var lastError: Exception? = null
        repeat(limits.maxModelRequestRetries + 1) { retryIndex ->
            currentCoroutineContext().ensureActive()
            try {
                return modelRepair.requestTurn(request)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastError = error
                if (retryIndex < limits.maxModelRequestRetries) {
                    val message = error.message ?: "模型请求失败"
                    emit(
                        GenerateTaskState.Stage.MODEL,
                        GenerateTaskState.Outcome.RETRYING,
                        "模型请求失败，正在重试 ${retryIndex + 1}/${limits.maxModelRequestRetries}",
                        listOf(message)
                    )
                }
            }
        }
        throw lastError ?: IllegalStateException("模型请求失败")
    }

    private suspend fun executeTool(
        runRequest: HarnessRunRequest,
        call: HarnessToolCall,
        iteration: Int
    ): HarnessToolResult {
        return try {
            fileTool.execute(
                HarnessToolRequest(
                    runId = runRequest.runId,
                    workspacePath = runRequest.workspacePath,
                    iteration = iteration,
                    call = call,
                    metadata = runRequest.metadata
                )
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            HarnessToolResult(
                callId = call.id,
                success = false,
                output = error.message ?: "工具执行失败",
                diagnostics = listOf(error.stackTraceToString().take(MAX_STACK_TRACE_CHARS))
            )
        }
    }

    private fun compactDiagnostics(diagnostics: List<String>): List<String> {
        if (diagnostics.isEmpty()) return emptyList()
        var remainingChars = limits.maxDiagnosticChars
        val compacted = mutableListOf<String>()
        for (diagnostic in diagnostics.asSequence().map(String::trim).filter(String::isNotEmpty)) {
            if (compacted.size >= limits.maxDiagnostics || remainingChars <= 0) break
            val value = diagnostic.take(remainingChars)
            compacted += value
            remainingChars -= value.length
        }
        return compacted
    }

    private fun buildRepairPrompt(
        stage: GenerateTaskState.Stage,
        summary: String,
        diagnostics: List<String>
    ): String {
        return buildString {
            appendLine("<harness_feedback>")
            appendLine("阶段：${stage.name}")
            appendLine("结果：${summary.ifBlank { "检查未通过" }}")
            if (diagnostics.isNotEmpty()) {
                appendLine("诊断：")
                diagnostics.forEach { appendLine("- $it") }
            }
            appendLine("请分析根因，继续调用本地代码工具修改工作区。完成修改后停止调用工具，Harness 会重新构建和测试。")
            append("</harness_feedback>")
        }
    }

    private fun buildToolResultMessage(result: HarnessToolResult): String {
        return buildString {
            appendLine(if (result.success) "success" else "failure")
            appendLine(result.output)
            if (result.changedFiles.isNotEmpty()) {
                appendLine("changed_files:")
                result.changedFiles.forEach { appendLine("- $it") }
            }
            if (result.diagnostics.isNotEmpty()) {
                appendLine("diagnostics:")
                compactDiagnostics(result.diagnostics).forEach { appendLine("- $it") }
            }
        }.trimEnd()
    }

    private data class RepairFeedback(
        val stage: GenerateTaskState.Stage,
        val summary: String,
        val diagnostics: List<String>
    )

    private fun HarnessCheckResult.toRepairFeedback(
        stage: GenerateTaskState.Stage
    ): RepairFeedback {
        return RepairFeedback(
            stage = stage,
            summary = summary,
            diagnostics = diagnostics.ifEmpty { listOf(summary) }
        )
    }

    private enum class HarnessGate {
        DEVELOPMENT_BUILD,
        RUNTIME_INSPECTION,
        SELF_TEST,
        FINAL_BUILD,
        TEST_SUITE
    }

    private class HarnessLimitException(message: String) : IllegalStateException(message)

    private companion object {
        const val MAX_STACK_TRACE_CHARS = 8_000
    }
}

private fun HarnessCheckKind.eventStage(): GenerateTaskState.Stage {
    return when (this) {
        HarnessCheckKind.DEVELOPMENT_BUILD,
        HarnessCheckKind.FINAL_BUILD -> GenerateTaskState.Stage.BUILD

        HarnessCheckKind.RUNTIME -> GenerateTaskState.Stage.INSPECT
        HarnessCheckKind.SELF_TEST -> GenerateTaskState.Stage.SELF_TEST
        HarnessCheckKind.TEST_SUITE -> GenerateTaskState.Stage.TEST
    }
}

private fun HarnessCheckKind.runningMessage(): String {
    return when (this) {
        HarnessCheckKind.DEVELOPMENT_BUILD -> "正在执行开发构建"
        HarnessCheckKind.RUNTIME -> "正在通过 WebView 检查器读取运行错误"
        HarnessCheckKind.SELF_TEST -> "正在运行应用自测"
        HarnessCheckKind.FINAL_BUILD -> "自测通过，正在执行交付前构建"
        HarnessCheckKind.TEST_SUITE -> "交付前构建通过，正在运行完整测试集"
    }
}

private fun HarnessCheckKind.displayName(): String {
    return when (this) {
        HarnessCheckKind.DEVELOPMENT_BUILD -> "开发构建"
        HarnessCheckKind.RUNTIME -> "运行检查"
        HarnessCheckKind.SELF_TEST -> "应用自测"
        HarnessCheckKind.FINAL_BUILD -> "交付前构建"
        HarnessCheckKind.TEST_SUITE -> "完整测试集"
    }
}
