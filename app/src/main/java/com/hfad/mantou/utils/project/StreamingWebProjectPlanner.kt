package com.hfad.mantou.utils.project

import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.StreamingApiService
import com.hfad.mantou.data.logging.ApiDiagnosticContext
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import com.hfad.mantou.data.logging.elapsedMillisSince
import com.hfad.mantou.data.logging.record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.security.MessageDigest

data class WebProjectPlanningResult(
    val manifest: WebAppProjectManifest,
    val projectPlanJson: String,
    val appSpec: WebAppSpec? = manifest.appSpec
) {
    fun requireAppSpec(): WebAppSpec = appSpec
        ?: throw WebAppProjectException("项目计划缺少结构化 appSpec")
}

class StreamingWebProjectPlanner(
    private val config: ChatCallConfig,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val onThinking: suspend (String) -> Unit = {},
    private val onProgress: suspend (Int) -> Unit = {},
    private val traceLogger: HarnessTraceLogger = HarnessTraceLogger {},
    private val onRetry: suspend (String) -> Unit = {},
    private val streamChatCompletion: (
        ChatCallConfig,
        ChatRequest
    ) -> Flow<StreamingApiService.StreamEvent> = StreamingApiService::streamChatCompletion
) {

    init {
        require(maxTokens > 0)
    }

    suspend fun plan(
        userRequirement: String,
        runId: String? = null
    ): WebProjectPlanningResult {
        val startedAt = System.nanoTime()
        val diagnosticRunId = runId?.trim()?.takeIf(String::isNotEmpty)
        val traceRunId = diagnosticRunId ?: UNCORRELATED_RUN_ID
        var iteration = 0
        var attempt = 0
        var phase = PlannerPhase.STREAM
        var completed = false
        var firstPayloadRecorded = false
        var contentChunks = 0
        var thinkingChunks = 0
        var thinkingChars = 0
        var failureDetails = emptyMap<String, String>()
        var response = StringBuilder()

        val initialMessages = listOf(
            ApiMessage("system", PLANNING_PROMPT),
            ApiMessage(ChatMessage.ROLE_USER, userRequirement)
        )
        var request = planningRequest(initialMessages, diagnosticRunId)
        traceLogger.record(
            runId = traceRunId,
            component = "PLANNER",
            operation = "plan",
            status = HarnessTraceStatus.STARTED,
            message = "项目规划请求开始",
            iteration = 0,
            details = mapOf(
                "model" to config.model,
                "api_format" to config.apiFormat,
                "max_tokens" to request.maxTokens.toString(),
                "reasoning_effort" to (request.reasoningEffort ?: "provider_default"),
                "requirement_chars" to userRequirement.length.toString(),
                "requirement_sha256" to sha256(userRequirement),
                "system_prompt_chars" to PLANNING_PROMPT.length.toString(),
                "system_prompt_sha256" to sha256(PLANNING_PROMPT)
            )
        )

        var truncationRetryUsed = false
        var transportRetryUsed = false
        try {
            while (true) {
                attempt++
                val attemptStartedAt = System.nanoTime()
                var retry: PlannerRetry? = null
                phase = PlannerPhase.STREAM
                completed = false
                firstPayloadRecorded = false
                contentChunks = 0
                thinkingChunks = 0
                thinkingChars = 0
                failureDetails = emptyMap()
                response = StringBuilder()

                streamChatCompletion(config, request).collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Start -> {
                            traceLogger.record(
                                runId = traceRunId,
                                component = "PLANNER_STREAM",
                                operation = "upstream_started",
                                status = HarnessTraceStatus.PROGRESS,
                                message = "项目规划上游流已建立",
                                iteration = iteration,
                                durationMs = elapsedMillisSince(attemptStartedAt),
                                details = mapOf("attempt" to attempt.toString())
                            )
                        }

                        is StreamingApiService.StreamEvent.Thinking -> {
                            thinkingChunks++
                            thinkingChars += event.text.length
                            if (!firstPayloadRecorded) {
                                firstPayloadRecorded = true
                                traceLogger.record(
                                    runId = traceRunId,
                                    component = "PLANNER_STREAM",
                                    operation = "first_payload",
                                    status = HarnessTraceStatus.PROGRESS,
                                    message = "收到项目规划首个流片段",
                                    iteration = iteration,
                                    durationMs = elapsedMillisSince(startedAt),
                                    details = mapOf("payload_type" to "thinking")
                                )
                            }
                            onThinking(event.text)
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            contentChunks++
                            if (contentChunks == 1) {
                                traceLogger.record(
                                    runId = traceRunId,
                                    component = "PLANNER_STREAM",
                                    operation = "first_content",
                                    status = HarnessTraceStatus.PROGRESS,
                                    message = "收到项目规划正文",
                                    iteration = iteration,
                                    durationMs = elapsedMillisSince(attemptStartedAt),
                                    details = mapOf("attempt" to attempt.toString())
                                )
                            }
                            if (!firstPayloadRecorded) {
                                firstPayloadRecorded = true
                                traceLogger.record(
                                    runId = traceRunId,
                                    component = "PLANNER_STREAM",
                                    operation = "first_payload",
                                    status = HarnessTraceStatus.PROGRESS,
                                    message = "收到项目规划首个流片段",
                                    iteration = iteration,
                                    durationMs = elapsedMillisSince(startedAt),
                                    details = mapOf("payload_type" to "content")
                                )
                            }
                            response.append(event.text)
                            onProgress(response.length)
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            completed = true
                            traceLogger.record(
                                runId = traceRunId,
                                component = "PLANNER_STREAM",
                                operation = "stream_complete",
                                status = HarnessTraceStatus.SUCCEEDED,
                                message = "项目规划流接收完成",
                                iteration = iteration,
                                durationMs = elapsedMillisSince(startedAt),
                                details = responseDetails(
                                    response = response,
                                    contentChunks = contentChunks,
                                    thinkingChunks = thinkingChunks,
                                    thinkingChars = thinkingChars
                                )
                            )
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            failureDetails = buildMap {
                                put("stream_event", "disconnected")
                                event.diagnosticId?.let { put("diagnostic_id", it) }
                            }
                            if (transportRetryUsed) throw WebAppProjectException(event.message)
                            transportRetryUsed = true
                            retry = PlannerRetry(PlannerRetryReason.TRANSPORT, request)
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            failureDetails = buildMap {
                                put("stream_event", "error")
                                put("retryable", event.retryable.toString())
                                event.httpStatus?.let { put("http_status", it.toString()) }
                                event.diagnosticId?.let { put("diagnostic_id", it) }
                                event.upstreamRequestId?.let { put("upstream_request_id", it) }
                                event.finishReason?.let { put("finish_reason", it) }
                            }
                            val compatibilityRequest = request.withoutRejectedOptions(event)
                            when {
                                compatibilityRequest != null -> {
                                    retry = PlannerRetry(PlannerRetryReason.COMPATIBILITY, compatibilityRequest)
                                }
                                event.finishReason in listOf("length", "max_tokens") &&
                                    !truncationRetryUsed && request.maxTokens < MAX_RETRY_TOKENS -> {
                                    truncationRetryUsed = true
                                    val system = request.messages.first()
                                    retry = PlannerRetry(
                                        PlannerRetryReason.TRUNCATION,
                                        request.copy(
                                            maxTokens = (request.maxTokens.toLong() * 2)
                                                .coerceAtMost(MAX_RETRY_TOKENS.toLong()).toInt(),
                                            messages = listOf(system.copy(
                                                content = system.content.toString() + "\n\n" + TRUNCATION_GUIDANCE
                                            )) + request.messages.drop(1)
                                        )
                                    )
                                }
                                event.retryable && !transportRetryUsed -> {
                                    transportRetryUsed = true
                                    retry = PlannerRetry(PlannerRetryReason.TRANSPORT, request)
                                }
                                else -> throw WebAppProjectException(event.message)
                            }
                        }
                    }
                }
                val next = retry
                if (next != null) {
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_REPAIR",
                        operation = "retry_request",
                        status = HarnessTraceStatus.STARTED,
                        message = next.reason.message,
                        iteration = iteration,
                        durationMs = elapsedMillisSince(attemptStartedAt),
                        details = responseDetails(response, contentChunks, thinkingChunks, thinkingChars) +
                            failureDetails + mapOf(
                                "attempt" to attempt.toString(),
                                "retry_reason" to next.reason.name,
                                "max_tokens" to next.request.maxTokens.toString(),
                                "reasoning_effort" to (next.request.reasoningEffort ?: "provider_default"),
                                "json_mode" to (next.request.responseFormat != null).toString()
                            )
                    )
                    onRetry(next.reason.message)
                    if (next.reason == PlannerRetryReason.TRANSPORT) delay(TRANSPORT_RETRY_DELAY_MS)
                    request = next.request
                    continue
                }
                if (!completed) throw WebAppProjectException("项目规划请求在返回完整计划前结束")

                phase = PlannerPhase.PARSE
                traceLogger.record(
                    runId = traceRunId,
                    component = "PLANNER_PARSE",
                    operation = "parse_plan",
                    status = HarnessTraceStatus.STARTED,
                    message = "开始解析项目规划",
                    iteration = iteration,
                    details = responseDetails(
                        response = response,
                        contentChunks = contentChunks,
                        thinkingChunks = thinkingChunks,
                        thinkingChars = thinkingChars
                    )
                )
                val result = try {
                    if (response.isBlank()) {
                        throw WebAppProjectException(
                            if (thinkingChars > 0) "模型只返回了思考内容，未返回项目计划 JSON 正文"
                            else "模型返回了空响应，未返回项目计划 JSON 正文"
                        )
                    }
                    WebProjectPlanCodec.parse(response.toString(), requireAppSpec = true)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val diagnostics = repairDiagnostics(error)
                    failureDetails = diagnosticTraceDetails(diagnostics)
                    val parseErrorDetails = responseDetails(
                        response = response,
                        contentChunks = contentChunks,
                        thinkingChunks = thinkingChunks,
                        thinkingChars = thinkingChars
                    ) + failureDetails + mapOf(
                        "phase" to phase.logValue,
                        "stream_completed" to completed.toString(),
                        "error_type" to error::class.java.simpleName,
                        "attempt_count" to attempt.toString()
                    )
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_PARSE",
                        operation = "parse_plan",
                        status = HarnessTraceStatus.FAILED,
                        message = "项目规划解析失败",
                        iteration = iteration,
                        durationMs = elapsedMillisSince(startedAt),
                        details = parseErrorDetails
                    )
                    if (iteration >= MAX_REPAIR_ATTEMPTS) {
                        if (iteration > 0) {
                            traceLogger.record(
                                runId = traceRunId,
                                component = "PLANNER_REPAIR",
                                operation = "repair_plan",
                                status = HarnessTraceStatus.FAILED,
                                message = "项目规划修复未通过严格校验",
                                iteration = iteration,
                                durationMs = elapsedMillisSince(startedAt),
                                details = parseErrorDetails
                            )
                        }
                        throw error
                    }

                    val nextIteration = iteration + 1
                    val feedback = ApiMessage(ChatMessage.ROLE_USER, repairFeedback(diagnostics))
                    val repairMessages = if (response.isBlank()) {
                        listOf(request.messages.first(), initialMessages.last().copy(
                            content = "$userRequirement\n\n${feedback.content}"
                        ))
                    } else {
                        listOf(
                            request.messages.first(),
                            initialMessages.last(),
                            ApiMessage(ChatMessage.ROLE_ASSISTANT, response.toString()),
                            feedback
                        )
                    }
                    request = request.copy(
                        messages = repairMessages,
                        diagnosticContext = request.diagnosticContext?.copy(iteration = nextIteration)
                    )
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_REPAIR",
                        operation = "repair_plan",
                        status = HarnessTraceStatus.STARTED,
                        message = "项目规划未通过严格校验，开始有界修复",
                        iteration = nextIteration,
                        durationMs = elapsedMillisSince(startedAt),
                        details = failureDetails + mapOf(
                            "previous_iteration" to iteration.toString(),
                            "max_repair_attempts" to MAX_REPAIR_ATTEMPTS.toString()
                        )
                    )
                    onRetry("项目计划未通过验收规则校验，正在按诊断修复")
                    iteration = nextIteration
                    continue
                }
                val parseDetails = responseDetails(
                    response = response,
                    contentChunks = contentChunks,
                    thinkingChunks = thinkingChunks,
                    thinkingChars = thinkingChars
                ) + mapOf(
                    "project_id" to result.manifest.projectId,
                    "entry_point" to result.manifest.entryPoint,
                    "file_count" to result.manifest.files.size.toString(),
                    "app_spec_version" to result.requireAppSpec().specVersion.toString(),
                    "acceptance_criteria_count" to result.requireAppSpec()
                        .acceptanceContract.criteria.size.toString(),
                    "display_name_chars" to result.manifest.displayName.length.toString(),
                    "display_name_sha256" to sha256(result.manifest.displayName),
                    "attempt_count" to attempt.toString()
                )
                traceLogger.record(
                    runId = traceRunId,
                    component = "PLANNER_PARSE",
                    operation = "parse_plan",
                    status = HarnessTraceStatus.SUCCEEDED,
                    message = "项目规划解析完成",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(startedAt),
                    details = parseDetails
                )
                if (iteration > 0) {
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_REPAIR",
                        operation = "repair_plan",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "项目规划修复通过严格校验",
                        iteration = iteration,
                        durationMs = elapsedMillisSince(startedAt),
                        details = parseDetails
                    )
                }
                traceLogger.record(
                    runId = traceRunId,
                    component = "PLANNER",
                    operation = "plan",
                    status = HarnessTraceStatus.SUCCEEDED,
                    message = "项目规划完成",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(startedAt),
                    details = parseDetails
                )
                return result
            }
        } catch (error: CancellationException) {
            traceLogger.record(
                runId = traceRunId,
                component = "PLANNER",
                operation = "plan",
                status = HarnessTraceStatus.CANCELLED,
                message = "项目规划已取消",
                iteration = iteration,
                durationMs = elapsedMillisSince(startedAt),
                details = responseDetails(
                    response = response,
                    contentChunks = contentChunks,
                    thinkingChunks = thinkingChunks,
                    thinkingChars = thinkingChars
                ) + mapOf(
                    "phase" to phase.logValue,
                    "attempt_count" to attempt.toString()
                )
            )
            throw error
        } catch (error: Exception) {
            val errorDetails = responseDetails(
                response = response,
                contentChunks = contentChunks,
                thinkingChunks = thinkingChunks,
                thinkingChars = thinkingChars
            ) + failureDetails + mapOf(
                "phase" to phase.logValue,
                "stream_completed" to completed.toString(),
                "error_type" to error::class.java.simpleName,
                "attempt_count" to attempt.toString()
            )
            traceLogger.record(
                runId = traceRunId,
                component = "PLANNER",
                operation = "plan",
                status = HarnessTraceStatus.FAILED,
                message = if (phase == PlannerPhase.PARSE) {
                    "项目规划解析失败"
                } else {
                    "项目规划请求失败"
                },
                iteration = iteration,
                durationMs = elapsedMillisSince(startedAt),
                details = errorDetails
            )
            throw error
        }
    }

    private fun planningRequest(
        messages: List<ApiMessage>,
        diagnosticRunId: String?
    ): ChatRequest {
        val openAiFormat = !config.isAnthropic
        return ChatRequest(
            model = config.model,
            messages = messages,
            stream = true,
            maxTokens = maxTokens,
            responseFormat = if (openAiFormat) mapOf("type" to "json_object") else null,
            reasoningEffort = config.webProjectReasoningEffort,
            temperature = 0.2,
            topP = 0.9,
            diagnosticContext = ApiDiagnosticContext(
                runId = diagnosticRunId,
                operation = "harness.plan",
                iteration = 0
            )
        )
    }

    private fun ChatRequest.withoutRejectedOptions(event: StreamingApiService.StreamEvent.Error): ChatRequest? {
        val rejectedJsonMode = responseFormat != null &&
            (event.rejectsRequestOption("response_format") || event.rejectsRequestOption("json_object"))
        val rejectedReasoningEffort = reasoningEffort != null &&
            event.rejectsRequestOption("reasoning_effort")
        if (!rejectedJsonMode && !rejectedReasoningEffort) return null
        return copy(
            responseFormat = responseFormat.takeUnless { rejectedJsonMode },
            reasoningEffort = reasoningEffort.takeUnless { rejectedReasoningEffort }
        )
    }

    private fun repairDiagnostics(error: Exception): List<PlannerRepairDiagnostic> {
        return when (error) {
            is WebAppProjectValidationException -> error.report.diagnostics.map { diagnostic ->
                PlannerRepairDiagnostic(
                    code = diagnostic.code,
                    message = diagnostic.message,
                    path = diagnostic.path
                )
            }

            is WebAppProjectException -> listOf(
                PlannerRepairDiagnostic(
                    code = "PROJECT_PLAN_CODEC_REJECTED",
                    message = error.message ?: "项目计划未通过严格解析"
                )
            )

            else -> listOf(
                PlannerRepairDiagnostic(
                    code = "PROJECT_PLAN_PARSE_FAILED",
                    message = error.message ?: "项目计划解析失败"
                )
            )
        }
    }

    private fun repairFeedback(diagnostics: List<PlannerRepairDiagnostic>): String {
        return buildString {
            appendLine("上一次输出未通过 WebProjectPlanCodec 的严格解析与校验。")
            appendLine("请根据宿主诊断修复，并只返回一个完整、合法的 JSON 对象；不要输出 Markdown、前言、解释或省略内容。")
            appendLine("修复后的计划仍须满足系统消息中的全部原始约束和用户需求。")
            appendLine("宿主诊断：")
            diagnostics.forEach { diagnostic ->
                append("- [")
                append(sanitizeDiagnostic(diagnostic.code))
                append("] ")
                diagnostic.path?.let { path ->
                    append("path=")
                    append(sanitizeDiagnostic(path))
                    append("; ")
                }
                appendLine(sanitizeDiagnostic(diagnostic.message))
            }
        }.trimEnd()
    }

    private fun diagnosticTraceDetails(
        diagnostics: List<PlannerRepairDiagnostic>
    ): Map<String, String> {
        val codes = diagnostics.joinToString(",") { diagnostic -> diagnostic.code }
        val diagnosticText = diagnostics.joinToString("\n") { diagnostic ->
            listOfNotNull(diagnostic.code, diagnostic.path, diagnostic.message).joinToString("|")
        }
        return mapOf(
            "diagnostic_count" to diagnostics.size.toString(),
            "diagnostic_codes" to codes,
            "diagnostic_sha256" to sha256(diagnosticText)
        )
    }

    private fun sanitizeDiagnostic(value: String): String {
        return value.replace(DIAGNOSTIC_WHITESPACE, " ").trim().take(MAX_DIAGNOSTIC_CHARS)
    }

    private fun responseDetails(
        response: CharSequence,
        contentChunks: Int,
        thinkingChunks: Int,
        thinkingChars: Int
    ): Map<String, String> {
        val responseText = response.toString()
        return mapOf(
            "response_chars" to responseText.length.toString(),
            "response_sha256" to sha256(responseText),
            "content_chunks" to contentChunks.toString(),
            "thinking_chunks" to thinkingChunks.toString(),
            "thinking_chars" to thinkingChars.toString()
        )
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 8_000
        const val MAX_RETRY_TOKENS = 32_000
        const val TRANSPORT_RETRY_DELAY_MS = 1_000L
        const val UNCORRELATED_RUN_ID = "planner-unscoped"
        const val MAX_REPAIR_ATTEMPTS = 1
        const val MAX_DIAGNOSTIC_CHARS = 1_000
        val DIAGNOSTIC_WHITESPACE = Regex("\\s+")
        const val TRUNCATION_GUIDANCE =
            "上一轮计划达到输出上限。直接返回精简的完整 JSON，保留用户明确要求、完整 appSpec 及验收条件。"

        fun sha256(content: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        val PLANNING_PROMPT = """
            你是 Web 项目架构规划器。根据用户需求规划一个无需 Node.js、npm、服务器或远程依赖、可直接在移动 WebView 中运行的多文件静态项目。

            只返回一个 JSON 对象，不要 Markdown 或解释。格式：
            {
              "schemaVersion": 1,
              "name": "馒头应用名",
              "entry": "index.html",
              "files": [
                {"path":"index.html","role":"entry","description":"语义结构和资源入口","dependsOn":[],"ownsCriteria":[]},
                {"path":"styles/app.css","role":"style","description":"视觉系统","dependsOn":["index.html"],"ownsCriteria":[]},
                {"path":"scripts/app.js","role":"script","description":"状态和交互","dependsOn":["index.html","styles/app.css"],"ownsCriteria":["AC-001","AC-002","AC-003"]}
              ],
              "appSpec": {
                "specVersion": 1,
                "summary": "应用范围和主要能力",
                "primaryGoal": "用户完成的核心目标",
                "selectors": [
                  {"id":"item-input","selector":"[data-testid='item-input']","purpose":"输入内容"},
                  {"id":"add-button","selector":"[data-testid='add-button']","purpose":"提交新增"},
                  {"id":"item-row","selector":"[data-testid='item-row']","purpose":"已创建条目"},
                  {"id":"empty-state","selector":"[data-testid='empty-state']","purpose":"空态引导"},
                  {"id":"input-error","selector":"[data-testid='input-error']","purpose":"输入错误提示"}
                ],
                "screens": [
                  {"id":"main","title":"主界面","purpose":"完成核心任务","selectorIds":["item-input","add-button","item-row","empty-state","input-error"]}
                ],
                "components": [
                  {"id":"item-form","name":"新增表单","purpose":"收集并提交内容","selectorIds":["item-input","add-button","input-error"],"states":["idle","invalid"]}
                ],
                "state": {
                  "persistence":"MANTOU_STORAGE",
                  "storageKey":"app-state-v1",
                  "fields":[{"name":"items","type":"array","description":"用户创建的条目","initialValue":"[]"}],
                  "emptyState":"没有条目时展示引导",
                  "errorStates":["输入为空时展示校验提示","存储失败时保留当前输入"]
                },
                "interactions": [
                  {"id":"add-item","title":"新增条目","triggerSelectorId":"add-button","outcome":"新增条目并刷新列表","stateChanges":["items append"]},
                  {"id":"focus-empty-input","title":"查看空态后开始输入","triggerSelectorId":"item-input","outcome":"输入框获得焦点且空态引导保持可见","stateChanges":[]},
                  {"id":"reject-empty-item","title":"拒绝空输入","triggerSelectorId":"add-button","outcome":"展示输入错误且不新增条目","stateChanges":[]}
                ],
                "userFlows": [
                  {"id":"create-item","title":"创建第一条内容","interactionIds":["add-item"],"criterionIds":["AC-001"]},
                  {"id":"view-empty","title":"查看空态引导","interactionIds":["focus-empty-input"],"criterionIds":["AC-002"]},
                  {"id":"reject-empty","title":"阻止空内容提交","interactionIds":["reject-empty-item"],"criterionIds":["AC-003"]}
                ],
                "design": {
                  "theme":"单手快速记录的 Android 工具。紧凑顶栏、同一条内容边线，主色用于保存和当前状态。",
                  "tokens":[
                    {"name":"color-primary","value":"#2B5D47","purpose":"保存按钮底色与选中状态"},
                    {"name":"color-surface","value":"#FAFAF4","purpose":"页面底色"},
                    {"name":"color-text","value":"#18201B","purpose":"标题、正文与表单文字"},
                    {"name":"space-md","value":"16px","purpose":"页面横向边距"},
                    {"name":"title-size","value":"22px","purpose":"顶栏标题字号"}
                  ],
                  "constraints":["从顶栏、输入与保存、到记录列表排列；首屏可见主操作","记录列表使用分隔线；空态就地提示创建第一条记录","键盘出现后输入与保存仍可通过纵向滚动触达"],
                  "viewportWidths":[360,393],
                  "minTouchTargetPx":48
                },
                "acceptanceContract": {
                  "criteria":[
                    {
                      "id":"AC-001",
                      "title":"用户可新增一条内容",
                      "priority":"P0",
                      "covers":["CORE_SUCCESS","PERSISTENCE"],
                      "setup":[{"type":"INPUT","target":"[data-testid='item-input']","value":"测试内容"}],
                      "actions":[{"type":"CLICK","target":"[data-testid='add-button']"}],
                      "expected":[
                        {"type":"COUNT_EQUALS","target":"[data-testid='item-row']","count":1},
                        {"type":"STORAGE_EQUALS","target":"app-state-v1","value":"{\"items\":[\"测试内容\"]}"}
                      ],
                      "reopenExpected":[
                        {"type":"TEXT_EQUALS","target":"[data-testid='item-row']","value":"测试内容"}
                      ]
                    },
                    {
                      "id":"AC-002",
                      "title":"首次打开时展示空态引导",
                      "priority":"P1",
                      "covers":["EMPTY_STATE"],
                      "actions":[{"type":"FOCUS","target":"[data-testid='item-input']"}],
                      "expected":[{"type":"VISIBLE","target":"[data-testid='empty-state']"}]
                    },
                    {
                      "id":"AC-003",
                      "title":"空内容不能提交",
                      "priority":"P1",
                      "covers":["ERROR_STATE"],
                      "actions":[{"type":"CLICK","target":"[data-testid='add-button']"}],
                      "expected":[
                        {"type":"VISIBLE","target":"[data-testid='input-error']"},
                        {"type":"COUNT_EQUALS","target":"[data-testid='item-row']","count":0}
                      ]
                    }
                  ]
                }
              }
            }

            约束：
            - 直接生成精简规划。只为实际需求增加文件与验收条件；简单项目优先使用示例的三文件结构。
            - 先确定谁在什么场景使用、打开后第一步做什么。screens[].purpose 写出各屏的内容顺序和主操作，components 写出操作反馈及相关状态。
            - 在 state.fields[].description 说明业务数据来自用户输入、已有数据计算还是已提供的宿主能力。用户记录首次打开默认为空；统计从实际记录得出。没有真实来源的数据写明不可用，只有用户要求演示数据时才规划单独标识的示例。
            - design.theme 用一句话定下适合题材的视觉方向。tokens 为本项目指定实际使用的色彩角色、字号等级和间距，约束写清首屏、主要操作位置、导航或返回路径及键盘遮挡时的行为。示例配色仅说明字段关系。
            - 工具默认参照 Android Material 3 的组件和交互层级。单屏任务用紧凑标题栏和就地操作，多目的地才规划底部导航，短任务可用底部面板，长编辑用独立界面。游戏、绘图等沉浸内容保留题材风格，优先规划画布、控制区、手势冲突与暂停恢复。用户指定的风格和横竖屏优先。
            - 手机主要控件的有效点击区域至少 48 CSS px，正常字号文本与背景至少 4.5:1。使用实际功能决定组件，去掉标语、占位数据、装饰性徽标和重复状态提醒。
            - 文件数 3-24 个，必须包含且只包含一个 entry，并至少包含独立 CSS 与独立 JavaScript。
            - 可以按需规划 data/*.json、assets/*.svg 和拆分后的 JS modules，但不规划二进制文件。
            - 路径只能使用项目内相对路径和 `/`，不能出现隐藏目录、空段、`.`、`..` 或绝对路径。
            - 不要规划 package.json、构建配置、CDN 或任何需要安装依赖的文件。
            - description 要明确文件职责；dependsOn 只能引用 files 中的路径且不能成环。
            - 每条 acceptance criterion 必须由至少一个 files[].ownsCriteria 负责，userFlows 也必须引用它。
            - selectors 必须使用稳定、唯一且格式严格为 `[data-testid='name']` 的 CSS selector；验收动作和普通 DOM 断言的 target 必须直接使用其中的 selector。URL_CONTAINS 不需要 target；STORAGE_EQUALS 的 target 是 storageKey，不是 CSS selector。
            - 验收动作 type 仅可使用 CLICK、INPUT、SELECT、SUBMIT、FOCUS、BLUR、KEY_PRESS、WAIT。
            - 验收断言 type 仅可使用 EXISTS、NOT_EXISTS、VISIBLE、HIDDEN、TEXT_EQUALS、TEXT_CONTAINS、VALUE_EQUALS、ATTRIBUTE_EQUALS、COUNT_EQUALS、URL_CONTAINS、STORAGE_EQUALS。
            - MANTOU_STORAGE 的 storageKey 表示根 JSON 对象中的唯一字段名；实现必须用 storageGet(storageKey) 和 storageSet(storageKey, JSON.stringify(state)) 读写该字段，不能把它当提示标签。
            - 使用 MANTOU_STORAGE 时必须有 STORAGE_EQUALS 断言；其 target 必须与 state.storageKey 完全相同，value 必须是该字段预期值的合法 JSON 文本。
            - 使用 MANTOU_STORAGE 时，至少一个 PERSISTENCE 用例要先写入有辨识度的测试值，再用 reopenExpected 声明重开后应恢复的页面内容或控件状态。宿主会用已验证的存储值重新打开入口且不重放操作；优先用 TEXT_EQUALS 或 VALUE_EQUALS 断言同一个测试值，勿只检查固定标题、URL 或存储键。
            - 每条 criterion 必须声明非空 covers，值仅可使用 CORE_SUCCESS、EMPTY_STATE、ERROR_STATE、PERSISTENCE，且同一值不能重复。
            - 每条 criterion 的 setup 或 actions 中必须至少包含一个非 WAIT 动作，不能用纯静态断言或仅等待来伪造验收。
            - TEXT_CONTAINS 和 URL_CONTAINS 的 value 必须是非空白文本，禁止使用恒真空字符串。
            - acceptanceContract 必须覆盖 CORE_SUCCESS 和 EMPTY_STATE；state.errorStates 非空时必须覆盖 ERROR_STATE；使用 MANTOU_STORAGE 时必须覆盖 PERSISTENCE，且对应 criterion 必须包含 STORAGE_EQUALS。覆盖 CORE_SUCCESS 的 criterion 必须标为 P0。
            - 不得在验收合同中写 JavaScript、自然语言伪步骤或模型自行判断的条件。
        """.trimIndent()
    }

    private enum class PlannerPhase(val logValue: String) {
        STREAM("stream"),
        PARSE("parse")
    }

    private enum class PlannerRetryReason(val message: String) {
        COMPATIBILITY("模型接口拒绝了可选参数，正在使用兼容参数重新规划"),
        TRUNCATION("规划输出达到上限，正在增加预算并生成更精简的计划"),
        TRANSPORT("模型连接中断或服务暂时不可用，正在重试项目规划")
    }

    private data class PlannerRetry(val reason: PlannerRetryReason, val request: ChatRequest)

    private data class PlannerRepairDiagnostic(
        val code: String,
        val message: String,
        val path: String? = null
    )
}
