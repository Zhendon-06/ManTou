package com.hfad.mantou.utils.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import java.util.Locale
import java.util.UUID

data class WebProjectPlanningResult(
    val manifest: WebAppProjectManifest,
    val projectPlanJson: String
)

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
        var phase = PlannerPhase.STREAM
        var completed = false
        var firstPayloadRecorded = false
        var contentChunks = 0
        var thinkingChunks = 0
        var thinkingChars = 0
        var failureDetails = emptyMap<String, String>()
        val response = StringBuilder()

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

        val request = ChatRequest(
            model = config.model,
            messages = listOf(
                ApiMessage("system", PLANNING_PROMPT),
                ApiMessage(ChatMessage.ROLE_USER, userRequirement)
            ),
            stream = true,
            maxTokens = maxTokens,
            temperature = 0.2,
            topP = 0.9,
            diagnosticContext = ApiDiagnosticContext(
                runId = diagnosticRunId,
                operation = "harness.plan",
                iteration = 0
            )
        )

        try {
            streamChatCompletion(config, request).collect { event ->
                when (event) {
                    is StreamingApiService.StreamEvent.Start -> {
                        traceLogger.record(
                            runId = traceRunId,
                            component = "PLANNER_STREAM",
                            operation = "upstream_started",
                            status = HarnessTraceStatus.PROGRESS,
                            message = "项目规划上游流已建立",
                            iteration = 0,
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
                                iteration = 0,
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
                                iteration = 0,
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
                            iteration = 0,
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
                iteration = 0,
                details = responseDetails(
                    response = response,
                    contentChunks = contentChunks,
                    thinkingChunks = thinkingChunks,
                    thinkingChars = thinkingChars
                )
            )
            val result = WebProjectPlanParser.parse(response.toString())
            val parseDetails = responseDetails(
                response = response,
                contentChunks = contentChunks,
                thinkingChunks = thinkingChunks,
                thinkingChars = thinkingChars
            ) + mapOf(
                "project_id" to result.manifest.projectId,
                "entry_point" to result.manifest.entryPoint,
                "file_count" to result.manifest.files.size.toString(),
                "display_name_chars" to result.manifest.displayName.length.toString(),
                "display_name_sha256" to sha256(result.manifest.displayName)
            )
            traceLogger.record(
                runId = traceRunId,
                component = "PLANNER_PARSE",
                operation = "parse_plan",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目规划解析完成",
                iteration = 0,
                durationMs = elapsedMillisSince(startedAt),
                details = parseDetails
            )
            traceLogger.record(
                runId = traceRunId,
                component = "PLANNER",
                operation = "plan",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目规划完成",
                iteration = 0,
                durationMs = elapsedMillisSince(startedAt),
                details = parseDetails
            )
            return result
        } catch (error: CancellationException) {
            traceLogger.record(
                runId = traceRunId,
                component = "PLANNER",
                operation = "plan",
                status = HarnessTraceStatus.CANCELLED,
                message = "项目规划已取消",
                iteration = 0,
                durationMs = elapsedMillisSince(startedAt),
                details = responseDetails(
                    response = response,
                    contentChunks = contentChunks,
                    thinkingChunks = thinkingChunks,
                    thinkingChars = thinkingChars
                ) + mapOf("phase" to phase.logValue)
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
                "error_type" to error::class.java.simpleName
            )
            if (phase == PlannerPhase.PARSE) {
                traceLogger.record(
                    runId = traceRunId,
                    component = "PLANNER_PARSE",
                    operation = "parse_plan",
                    status = HarnessTraceStatus.FAILED,
                    message = "项目规划解析失败",
                    iteration = 0,
                    durationMs = elapsedMillisSince(startedAt),
                    details = errorDetails
                )
            }
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
                iteration = 0,
                durationMs = elapsedMillisSince(startedAt),
                details = errorDetails
            )
            throw error
        }
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
                {"path":"index.html","role":"entry","description":"语义结构和资源入口"},
                {"path":"styles/app.css","role":"style","description":"完整视觉系统"},
                {"path":"scripts/app.js","role":"script","description":"状态、交互和自测"}
              ]
            }

            约束：
            - 文件数 3-24 个，必须包含且只包含一个 entry，并至少包含独立 CSS 与独立 JavaScript。
            - 可以按需规划 data/*.json、assets/*.svg 和拆分后的 JS modules，但不规划二进制文件。
            - 路径只能使用项目内相对路径和 `/`，不能出现隐藏目录、空段、`.`、`..` 或绝对路径。
            - 不要规划 package.json、构建配置、CDN 或任何需要安装依赖的文件。
            - description 要明确该文件职责，让后续逐文件生成请求可以独立完成实现。
        """.trimIndent()
    }

    private enum class PlannerPhase(val logValue: String) {
        STREAM("stream"),
        PARSE("parse")
    }
}

internal object WebProjectPlanParser {
    private const val PLAN_FILE_NAME = "project.json"
    private const val MAX_FILES = 24
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parse(modelOutput: String): WebProjectPlanningResult {
        val json = extractJson(modelOutput)
        val root = runCatching { JsonParser.parseString(json).asJsonObject }
            .getOrElse { error ->
                throw WebAppProjectException("模型返回的项目计划不是合法 JSON", error)
            }
        val schemaVersion = root.int("schemaVersion")
        if (schemaVersion != WebAppProjectManifest.CURRENT_SCHEMA_VERSION) {
            throw WebAppProjectException("项目计划 schemaVersion 必须为 1")
        }
        val requestedName = root.string("name").trim()
        val displayName = when {
            requestedName.isBlank() -> throw WebAppProjectException("项目计划缺少 name")
            requestedName.startsWith("馒头") -> requestedName
            else -> "馒头$requestedName"
        }.take(120)
        val entryPoint = WebProjectPaths.normalizeRelativePath(root.string("entry"))
        if (!entryPoint.endsWith(".html", true) && !entryPoint.endsWith(".htm", true)) {
            throw WebAppProjectException("项目入口必须是 HTML 文件")
        }
        val fileArray = root.getAsJsonArray("files")
            ?: throw WebAppProjectException("项目计划缺少 files")
        if (fileArray.size() !in 3..MAX_FILES) {
            throw WebAppProjectException("项目计划文件数必须在 3-$MAX_FILES 之间")
        }
        val planned = fileArray.mapIndexed { index, element ->
            val file = runCatching { element.asJsonObject }.getOrElse {
                throw WebAppProjectException("files[$index] 必须是对象")
            }
            val path = WebProjectPaths.normalizeRelativePath(file.string("path"))
            if (path == PLAN_FILE_NAME) {
                throw WebAppProjectException("project.json 由编排器维护，不能重复声明")
            }
            val role = parseRole(file.string("role"), path, entryPoint)
            PlannedFile(
                path = path,
                role = role,
                description = file.stringOrNull("description")?.trim().orEmpty()
            )
        }
        val duplicate = planned.groupBy(PlannedFile::path).entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw WebAppProjectException("项目计划包含重复文件：${duplicate.key}")
        val entries = planned.filter { it.role == WebAppProjectFileRole.ENTRY }
        if (entries.size != 1 || entries.single().path != entryPoint) {
            throw WebAppProjectException("项目计划必须且只能把 entry 指向的文件标记为 entry")
        }
        if (planned.none { it.role == WebAppProjectFileRole.STYLE }) {
            throw WebAppProjectException("项目计划必须包含独立 CSS 文件")
        }
        if (planned.none { it.role == WebAppProjectFileRole.SCRIPT }) {
            throw WebAppProjectException("项目计划必须包含独立 JavaScript 文件")
        }

        val normalizedPlan = JsonObject().apply {
            addProperty("schemaVersion", schemaVersion)
            addProperty("name", displayName)
            addProperty("entry", entryPoint)
            add("files", com.google.gson.JsonArray().apply {
                planned.forEach { plannedFile ->
                    add(JsonObject().apply {
                        addProperty("path", plannedFile.path)
                        addProperty("role", plannedFile.role.name.lowercase(Locale.US))
                        addProperty("description", plannedFile.description)
                    })
                }
            })
        }
        val manifestFiles = buildList {
            add(WebAppProjectFile(PLAN_FILE_NAME, WebAppProjectFileRole.OTHER))
            planned.forEach { file ->
                add(WebAppProjectFile(file.path, file.role))
            }
        }
        val manifest = WebAppProjectManifest(
            projectId = "app-${UUID.randomUUID()}",
            displayName = displayName,
            entryPoint = entryPoint,
            stateFile = null,
            files = manifestFiles
        )
        WebAppProjectValidator.validateManifest(manifest)
            .filter { it.severity == WebProjectDiagnosticSeverity.ERROR }
            .takeIf(List<WebProjectValidationDiagnostic>::isNotEmpty)
            ?.let { diagnostics ->
                throw WebAppProjectValidationException(WebProjectValidationReport(diagnostics))
            }
        return WebProjectPlanningResult(
            manifest = manifest,
            projectPlanJson = gson.toJson(normalizedPlan) + "\n"
        )
    }

    private fun parseRole(
        rawRole: String,
        path: String,
        entryPoint: String
    ): WebAppProjectFileRole {
        if (path == entryPoint) return WebAppProjectFileRole.ENTRY
        return when (rawRole.trim().lowercase(Locale.US)) {
            "entry" -> WebAppProjectFileRole.ENTRY
            "style", "css" -> WebAppProjectFileRole.STYLE
            "script", "logic", "js", "module" -> WebAppProjectFileRole.SCRIPT
            "data", "json" -> WebAppProjectFileRole.DATA
            "asset", "svg", "image" -> WebAppProjectFileRole.ASSET
            else -> WebAppProjectFileRole.OTHER
        }
    }

    private fun extractJson(output: String): String {
        val trimmed = output.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.get(1)
            ?.trim()
        return fenced ?: trimmed
    }

    private fun JsonObject.string(name: String): String {
        return stringOrNull(name) ?: throw WebAppProjectException("项目计划缺少 $name")
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun JsonObject.int(name: String): Int {
        val value = get(name) ?: throw WebAppProjectException("项目计划缺少 $name")
        return runCatching { value.asInt }
            .getOrElse { throw WebAppProjectException("项目计划字段 $name 必须是整数") }
    }

    private data class PlannedFile(
        val path: String,
        val role: WebAppProjectFileRole,
        val description: String
    )
}
