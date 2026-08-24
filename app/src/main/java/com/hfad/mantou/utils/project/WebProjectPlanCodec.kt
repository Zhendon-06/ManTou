package com.hfad.mantou.utils.project

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import java.util.UUID

object WebProjectPlanCodec {
    private const val PLAN_FILE_NAME = "project.json"
    private const val MAX_FILES = 24
    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parse(
        modelOutput: String,
        requireAppSpec: Boolean = false
    ): WebProjectPlanningResult {
        val json = extractJson(modelOutput)
        val root = runCatching { JsonParser.parseString(json).asJsonObject }
            .getOrElse { error ->
                throw WebAppProjectException("模型返回的项目计划不是合法 JSON", error)
            }
        val schemaVersion = root.requiredInt("schemaVersion", "项目计划")
        if (schemaVersion != WebAppProjectManifest.CURRENT_SCHEMA_VERSION) {
            throw WebAppProjectException("项目计划 schemaVersion 必须为 1")
        }
        val requestedName = root.requiredString("name", "项目计划").trim()
        val displayName = when {
            requestedName.isBlank() -> throw WebAppProjectException("项目计划缺少 name")
            requestedName.startsWith("馒头") -> requestedName
            else -> "馒头$requestedName"
        }.take(120)
        val entryPoint = WebProjectPaths.normalizeRelativePath(
            root.requiredString("entry", "项目计划")
        )
        if (!entryPoint.endsWith(".html", true) && !entryPoint.endsWith(".htm", true)) {
            throw WebAppProjectException("项目入口必须是 HTML 文件")
        }
        val fileArray = root.requiredArray("files", "项目计划")
        if (fileArray.size() !in 3..MAX_FILES) {
            throw WebAppProjectException("项目计划文件数必须在 3-$MAX_FILES 之间")
        }
        val planned = fileArray.mapIndexed { index, element ->
            val context = "files[$index]"
            val file = element.requiredObject(context)
            val path = WebProjectPaths.normalizeRelativePath(file.requiredString("path", context))
            if (path == PLAN_FILE_NAME) {
                throw WebAppProjectException("project.json 由编排器维护，不能重复声明")
            }
            WebAppProjectFile(
                path = path,
                role = parseRole(file.requiredString("role", context), path, entryPoint),
                description = file.optionalString("description")?.trim().orEmpty(),
                dependsOn = file.optionalStringList("dependsOn", context)
                    .map(WebProjectPaths::normalizeRelativePath),
                ownsCriteria = file.optionalStringList("ownsCriteria", context)
                    .map(String::trim)
            )
        }
        val duplicate = planned.groupBy(WebAppProjectFile::path).entries.firstOrNull { it.value.size > 1 }
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

        val appSpec = root.optionalObject("appSpec", "项目计划")?.let(::parseAppSpec)
        if (requireAppSpec && appSpec == null) {
            throw WebAppProjectException("项目计划缺少结构化 appSpec")
        }
        val manifestFiles = buildList {
            add(WebAppProjectFile(PLAN_FILE_NAME, WebAppProjectFileRole.OTHER))
            addAll(planned)
        }
        val manifest = WebAppProjectManifest(
            projectId = "app-${UUID.randomUUID()}",
            displayName = displayName,
            entryPoint = entryPoint,
            stateFile = null,
            files = manifestFiles,
            appSpec = appSpec
        )
        WebAppProjectValidator.validateManifest(manifest)
            .filter { it.severity == WebProjectDiagnosticSeverity.ERROR }
            .takeIf(List<WebProjectValidationDiagnostic>::isNotEmpty)
            ?.let { diagnostics ->
                throw WebAppProjectValidationException(WebProjectValidationReport(diagnostics))
            }
        return WebProjectPlanningResult(
            manifest = manifest,
            projectPlanJson = render(manifest),
            appSpec = appSpec
        )
    }

    fun render(manifest: WebAppProjectManifest): String {
        val root = JsonObject().apply {
            addProperty("schemaVersion", manifest.schemaVersion)
            addProperty("name", manifest.displayName)
            addProperty("entry", manifest.entryPoint)
            add("files", JsonArray().apply {
                manifest.files
                    .asSequence()
                    .filterNot { it.path == PLAN_FILE_NAME }
                    .forEach { file ->
                        add(JsonObject().apply {
                            addProperty("path", file.path)
                            addProperty("role", file.role.planValue())
                            addProperty("description", file.description)
                            add("dependsOn", gson.toJsonTree(file.dependsOn.orEmpty()))
                            add("ownsCriteria", gson.toJsonTree(file.ownsCriteria.orEmpty()))
                        })
                    }
            })
            manifest.appSpec?.let { add("appSpec", gson.toJsonTree(it)) }
        }
        return gson.toJson(root) + "\n"
    }

    private fun parseAppSpec(root: JsonObject): WebAppSpec {
        val context = "appSpec"
        return WebAppSpec(
            specVersion = root.requiredInt("specVersion", context),
            summary = root.requiredString("summary", context).trim(),
            primaryGoal = root.requiredString("primaryGoal", context).trim(),
            selectors = root.requiredArray("selectors", context).mapIndexed { index, element ->
                val itemContext = "$context.selectors[$index]"
                val selector = element.requiredObject(itemContext)
                WebAppSelectorSpec(
                    id = selector.requiredString("id", itemContext).trim(),
                    selector = selector.requiredString("selector", itemContext).trim(),
                    purpose = selector.requiredString("purpose", itemContext).trim()
                )
            },
            screens = root.requiredArray("screens", context).mapIndexed { index, element ->
                val itemContext = "$context.screens[$index]"
                val screen = element.requiredObject(itemContext)
                WebAppScreenSpec(
                    id = screen.requiredString("id", itemContext).trim(),
                    title = screen.requiredString("title", itemContext).trim(),
                    purpose = screen.requiredString("purpose", itemContext).trim(),
                    selectorIds = screen.requiredStringList("selectorIds", itemContext)
                )
            },
            components = root.optionalArray("components", context).orEmpty().mapIndexed { index, element ->
                val itemContext = "$context.components[$index]"
                val component = element.requiredObject(itemContext)
                WebAppComponentSpec(
                    id = component.requiredString("id", itemContext).trim(),
                    name = component.requiredString("name", itemContext).trim(),
                    purpose = component.requiredString("purpose", itemContext).trim(),
                    selectorIds = component.requiredStringList("selectorIds", itemContext),
                    states = component.optionalStringList("states", itemContext)
                )
            },
            state = parseState(root.requiredObject("state", context)),
            interactions = root.requiredArray("interactions", context).mapIndexed { index, element ->
                val itemContext = "$context.interactions[$index]"
                val interaction = element.requiredObject(itemContext)
                WebAppInteractionSpec(
                    id = interaction.requiredString("id", itemContext).trim(),
                    title = interaction.requiredString("title", itemContext).trim(),
                    triggerSelectorId = interaction.requiredString("triggerSelectorId", itemContext).trim(),
                    outcome = interaction.requiredString("outcome", itemContext).trim(),
                    stateChanges = interaction.optionalStringList("stateChanges", itemContext)
                )
            },
            userFlows = root.requiredArray("userFlows", context).mapIndexed { index, element ->
                val itemContext = "$context.userFlows[$index]"
                val flow = element.requiredObject(itemContext)
                WebAppUserFlowSpec(
                    id = flow.requiredString("id", itemContext).trim(),
                    title = flow.requiredString("title", itemContext).trim(),
                    interactionIds = flow.requiredStringList("interactionIds", itemContext),
                    criterionIds = flow.requiredStringList("criterionIds", itemContext)
                )
            },
            design = parseDesign(root.requiredObject("design", context)),
            acceptanceContract = parseAcceptance(
                root.requiredObject("acceptanceContract", context)
            )
        )
    }

    private fun parseState(root: JsonObject): WebAppStateSpec {
        val context = "appSpec.state"
        return WebAppStateSpec(
            persistence = root.requiredEnum("persistence", context),
            storageKey = root.optionalString("storageKey")?.trim(),
            fields = root.optionalArray("fields", context).orEmpty().mapIndexed { index, element ->
                val itemContext = "$context.fields[$index]"
                val field = element.requiredObject(itemContext)
                WebAppStateFieldSpec(
                    name = field.requiredString("name", itemContext).trim(),
                    type = field.requiredString("type", itemContext).trim(),
                    description = field.requiredString("description", itemContext).trim(),
                    initialValue = field.optionalString("initialValue")
                )
            },
            emptyState = root.requiredString("emptyState", context).trim(),
            errorStates = root.optionalStringList("errorStates", context)
        )
    }

    private fun parseDesign(root: JsonObject): WebAppDesignSpec {
        val context = "appSpec.design"
        return WebAppDesignSpec(
            theme = root.requiredString("theme", context).trim(),
            tokens = root.requiredArray("tokens", context).mapIndexed { index, element ->
                val itemContext = "$context.tokens[$index]"
                val token = element.requiredObject(itemContext)
                WebAppDesignToken(
                    name = token.requiredString("name", itemContext).trim(),
                    value = token.requiredString("value", itemContext).trim(),
                    purpose = token.requiredString("purpose", itemContext).trim()
                )
            },
            constraints = root.requiredStringList("constraints", context),
            viewportWidths = root.optionalIntList("viewportWidths", context).ifEmpty { listOf(360, 393) },
            minTouchTargetPx = root.optionalInt("minTouchTargetPx", context) ?: 44
        )
    }

    private fun parseAcceptance(root: JsonObject): WebAppAcceptanceContract {
        val context = "appSpec.acceptanceContract"
        return WebAppAcceptanceContract(
            criteria = root.requiredArray("criteria", context).mapIndexed { index, element ->
                val itemContext = "$context.criteria[$index]"
                val criterion = element.requiredObject(itemContext)
                WebAppAcceptanceCriterion(
                    id = criterion.requiredString("id", itemContext).trim(),
                    title = criterion.requiredString("title", itemContext).trim(),
                    priority = criterion.requiredEnum("priority", itemContext),
                    covers = criterion.requiredStringList("covers", itemContext).mapIndexed {
                            coverageIndex,
                            rawCoverage ->
                        parseEnum<WebAppAcceptanceCoverage>(
                            raw = rawCoverage,
                            context = "$itemContext.covers[$coverageIndex]"
                        )
                    },
                    setup = parseAcceptanceActions(
                        criterion.optionalArray("setup", itemContext).orEmpty(),
                        "$itemContext.setup"
                    ),
                    actions = parseAcceptanceActions(
                        criterion.optionalArray("actions", itemContext).orEmpty(),
                        "$itemContext.actions"
                    ),
                    expected = criterion.requiredArray("expected", itemContext)
                        .mapIndexed { assertionIndex, assertionElement ->
                            val assertionContext = "$itemContext.expected[$assertionIndex]"
                            val assertion = assertionElement.requiredObject(assertionContext)
                            WebAppAcceptanceAssertion(
                                type = assertion.requiredEnum("type", assertionContext),
                                target = assertion.optionalString("target")?.trim(),
                                value = assertion.optionalString("value"),
                                attribute = assertion.optionalString("attribute")?.trim(),
                                count = assertion.optionalInt("count", assertionContext)
                            )
                        }
                )
            }
        )
    }

    private fun parseAcceptanceActions(
        elements: List<JsonElement>,
        context: String
    ): List<WebAppAcceptanceAction> {
        return elements.mapIndexed { index, element ->
            val itemContext = "$context[$index]"
            val action = element.requiredObject(itemContext)
            WebAppAcceptanceAction(
                type = action.requiredEnum("type", itemContext),
                target = action.optionalString("target")?.trim(),
                value = action.optionalString("value"),
                timeoutMs = action.optionalInt("timeoutMs", itemContext)
            )
        }
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

    private fun WebAppProjectFileRole.planValue(): String = when (this) {
        WebAppProjectFileRole.ENTRY -> "entry"
        WebAppProjectFileRole.STYLE -> "style"
        WebAppProjectFileRole.SCRIPT -> "script"
        WebAppProjectFileRole.DATA -> "data"
        WebAppProjectFileRole.ASSET -> "asset"
        WebAppProjectFileRole.OTHER -> "other"
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

    private fun JsonElement.requiredObject(context: String): JsonObject {
        if (!isJsonObject) throw WebAppProjectException("$context 必须是对象")
        return asJsonObject
    }

    private fun JsonObject.requiredObject(name: String, context: String): JsonObject {
        val value = get(name) ?: throw WebAppProjectException("$context 缺少 $name")
        if (!value.isJsonObject) throw WebAppProjectException("$context.$name 必须是对象")
        return value.asJsonObject
    }

    private fun JsonObject.optionalObject(name: String, context: String): JsonObject? {
        val value = get(name) ?: return null
        if (!value.isJsonObject) throw WebAppProjectException("$context.$name 必须是对象")
        return value.asJsonObject
    }

    private fun JsonObject.requiredArray(name: String, context: String): JsonArray {
        val value = get(name) ?: throw WebAppProjectException("$context 缺少 $name")
        if (!value.isJsonArray) throw WebAppProjectException("$context.$name 必须是数组")
        return value.asJsonArray
    }

    private fun JsonObject.optionalArray(name: String, context: String): List<JsonElement>? {
        val value = get(name) ?: return null
        if (!value.isJsonArray) throw WebAppProjectException("$context.$name 必须是数组")
        return value.asJsonArray.toList()
    }

    private fun JsonObject.requiredString(name: String, context: String): String {
        return optionalString(name) ?: throw WebAppProjectException("$context 缺少字符串字段 $name")
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun JsonObject.requiredInt(name: String, context: String): Int {
        return optionalInt(name, context) ?: throw WebAppProjectException("$context 缺少整数字段 $name")
    }

    private fun JsonObject.optionalInt(name: String, context: String): Int? {
        val value = get(name) ?: return null
        return runCatching { value.asInt }
            .getOrElse { throw WebAppProjectException("$context.$name 必须是整数") }
    }

    private fun JsonObject.requiredStringList(name: String, context: String): List<String> {
        return stringList(name, context, required = true)
    }

    private fun JsonObject.optionalStringList(name: String, context: String): List<String> {
        return stringList(name, context, required = false)
    }

    private fun JsonObject.stringList(
        name: String,
        context: String,
        required: Boolean
    ): List<String> {
        val array = if (required) {
            requiredArray(name, context)
        } else {
            val value = get(name) ?: return emptyList()
            if (!value.isJsonArray) throw WebAppProjectException("$context.$name 必须是数组")
            value.asJsonArray
        }
        return array.mapIndexed { index, value ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                throw WebAppProjectException("$context.$name[$index] 必须是字符串")
            }
            value.asString.trim()
        }
    }

    private fun JsonObject.optionalIntList(name: String, context: String): List<Int> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) throw WebAppProjectException("$context.$name 必须是数组")
        return value.asJsonArray.mapIndexed { index, element ->
            runCatching { element.asInt }
                .getOrElse { throw WebAppProjectException("$context.$name[$index] 必须是整数") }
        }
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(
        name: String,
        context: String
    ): T {
        return parseEnum(requiredString(name, context), "$context.$name")
    }

    private inline fun <reified T : Enum<T>> parseEnum(
        raw: String,
        context: String
    ): T {
        val normalized = raw.trim().uppercase(Locale.US)
        return enumValues<T>().firstOrNull { it.name == normalized }
            ?: throw WebAppProjectException("$context 包含不支持的值：$normalized")
    }
}

internal object WebProjectPlanParser {
    fun parse(
        modelOutput: String,
        requireAppSpec: Boolean = false
    ): WebProjectPlanningResult = WebProjectPlanCodec.parse(modelOutput, requireAppSpec)
}
