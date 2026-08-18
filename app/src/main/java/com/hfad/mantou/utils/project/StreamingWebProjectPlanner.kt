package com.hfad.mantou.utils.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.api.ApiMessage
import com.hfad.mantou.data.api.ChatCallConfig
import com.hfad.mantou.data.api.ChatRequest
import com.hfad.mantou.data.api.StreamingApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
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
    private val onProgress: suspend (Int) -> Unit = {}
) {

    init {
        require(maxTokens > 0)
    }

    suspend fun plan(userRequirement: String): WebProjectPlanningResult {
        val request = ChatRequest(
            model = config.model,
            messages = listOf(
                ApiMessage("system", PLANNING_PROMPT),
                ApiMessage(ChatMessage.ROLE_USER, userRequirement)
            ),
            stream = true,
            maxTokens = maxTokens,
            temperature = 0.2,
            topP = 0.9
        )
        val response = StringBuilder()
        var completed = false
        StreamingApiService.streamChatCompletion(config, request).collect { event ->
            when (event) {
                is StreamingApiService.StreamEvent.Thinking -> onThinking(event.text)
                is StreamingApiService.StreamEvent.Content -> {
                    response.append(event.text)
                    onProgress(response.length)
                }
                is StreamingApiService.StreamEvent.Done -> completed = true
                is StreamingApiService.StreamEvent.Disconnected -> {
                    throw WebAppProjectException(event.message)
                }
                is StreamingApiService.StreamEvent.Error -> {
                    throw WebAppProjectException(event.message)
                }
                is StreamingApiService.StreamEvent.Start -> Unit
            }
        }
        if (!completed) throw WebAppProjectException("项目规划请求在返回完整计划前结束")
        return WebProjectPlanParser.parse(response.toString())
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 8_000
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
