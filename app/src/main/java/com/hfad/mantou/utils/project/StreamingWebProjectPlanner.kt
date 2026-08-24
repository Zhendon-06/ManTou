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
        var phase = PlannerPhase.STREAM
        var completed = false
        var firstPayloadRecorded = false
        var contentChunks = 0
        var thinkingChunks = 0
        var thinkingChars = 0
        var failureDetails = emptyMap<String, String>()
        var response = StringBuilder()

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
                "max_tokens" to maxTokens.toString(),
                "requirement_chars" to userRequirement.length.toString(),
                "requirement_sha256" to sha256(userRequirement),
                "system_prompt_chars" to PLANNING_PROMPT.length.toString(),
                "system_prompt_sha256" to sha256(PLANNING_PROMPT)
            )
        )

        val initialMessages = listOf(
            ApiMessage("system", PLANNING_PROMPT),
            ApiMessage(ChatMessage.ROLE_USER, userRequirement)
        )
        var request = planningRequest(
            messages = initialMessages,
            diagnosticRunId = diagnosticRunId,
            iteration = iteration
        )

        try {
            while (true) {
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
                                durationMs = elapsedMillisSince(startedAt)
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
                            throw WebAppProjectException(event.message)
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            failureDetails = buildMap {
                                put("stream_event", "error")
                                put("retryable", event.retryable.toString())
                                event.httpStatus?.let { put("http_status", it.toString()) }
                                event.diagnosticId?.let { put("diagnostic_id", it) }
                                event.upstreamRequestId?.let { put("upstream_request_id", it) }
                            }
                            throw WebAppProjectException(event.message)
                        }
                    }
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
                        "attempt_count" to (iteration + 1).toString()
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
                    request = planningRequest(
                        messages = initialMessages + listOf(
                            ApiMessage(ChatMessage.ROLE_ASSISTANT, response.toString()),
                            ApiMessage(
                                ChatMessage.ROLE_USER,
                                repairFeedback(diagnostics)
                            )
                        ),
                        diagnosticRunId = diagnosticRunId,
                        iteration = nextIteration
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
                    "attempt_count" to (iteration + 1).toString()
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
                    "attempt_count" to (iteration + 1).toString()
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
                "attempt_count" to (iteration + 1).toString()
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
        diagnosticRunId: String?,
        iteration: Int
    ): ChatRequest {
        return ChatRequest(
            model = config.model,
            messages = messages,
            stream = true,
            maxTokens = maxTokens,
            temperature = 0.2,
            topP = 0.9,
            diagnosticContext = ApiDiagnosticContext(
                runId = diagnosticRunId,
                operation = "harness.plan",
                iteration = iteration
            )
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
        const val UNCORRELATED_RUN_ID = "planner-unscoped"
        const val MAX_REPAIR_ATTEMPTS = 1
        const val MAX_DIAGNOSTIC_CHARS = 1_000
        val DIAGNOSTIC_WHITESPACE = Regex("\\s+")

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
                  "theme":"清晰、克制的移动端主题",
                  "tokens":[
                    {"name":"color-primary","value":"#4F46E5","purpose":"主要操作"},
                    {"name":"space-md","value":"16px","purpose":"标准间距"},
                    {"name":"radius-md","value":"12px","purpose":"卡片和控件圆角"}
                  ],
                  "constraints":["无横向溢出","支持安全区和内容滚动","文本与背景保持可读对比度"],
                  "viewportWidths":[360,393],
                  "minTouchTargetPx":44
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

    private data class PlannerRepairDiagnostic(
        val code: String,
        val message: String,
        val path: String? = null
    )
}
