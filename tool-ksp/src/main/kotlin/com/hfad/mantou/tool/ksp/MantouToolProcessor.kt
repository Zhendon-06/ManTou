package com.hfad.mantou.tool.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate

class MantouToolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return MantouToolProcessor(environment.codeGenerator, environment.logger)
    }
}

private class MantouToolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private var generated = false
    private var hasErrors = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val symbols = resolver.getSymbolsWithAnnotation(MANTOU_TOOL_ANNOTATION).toList()
        val deferred = symbols.filterNot(KSAnnotated::validate)
        if (deferred.isNotEmpty()) return deferred
        if (symbols.isEmpty()) return emptyList()

        val baseTool = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(BASE_TOOL_CLASS)
        )
        if (baseTool == null) {
            reportError("找不到 $BASE_TOOL_CLASS，无法生成 Tool 元数据")
            return emptyList()
        }

        val tools = symbols.mapNotNull { symbol ->
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null) {
                reportError("@$MANTOU_TOOL_SIMPLE_NAME 只能标记类", symbol)
                null
            } else {
                parseTool(declaration, baseTool)
            }
        }.sortedBy(ToolModel::name)

        tools.groupBy(ToolModel::name)
            .filterValues { it.size > 1 }
            .forEach { (name, duplicates) ->
                duplicates.forEach { duplicate ->
                    reportError("Tool 名称重复：$name", duplicate.declaration)
                }
            }

        if (hasErrors) return emptyList()

        val sourceFiles = tools.mapNotNull { it.declaration.containingFile }.distinct()
        val dependencies = Dependencies(true, *sourceFiles.toTypedArray())
        val markdown = renderMarkdown(tools)
        generateToolDocument(dependencies, tools, markdown)
        generateToolRegistry(dependencies, tools)
        generateMarkdownResource(dependencies, markdown)
        generated = true
        logger.info("Mantou Tool KSP: generated ${tools.size} tools and ${tools.sumOf { it.methods.size }} methods")
        return emptyList()
    }

    private fun parseTool(
        declaration: KSClassDeclaration,
        baseTool: KSClassDeclaration
    ): ToolModel? {
        val annotation = declaration.findAnnotation(MANTOU_TOOL_ANNOTATION)
            ?: return null
        val name = annotation.stringArgument("name")
        val description = annotation.stringArgument("description")
        val usageScenario = annotation.stringArgument("usageScenario")
        val autoRegister = annotation.booleanArgument("autoRegister", defaultValue = true)

        if (declaration.classKind != ClassKind.CLASS) {
            reportError("@$MANTOU_TOOL_SIMPLE_NAME 只能标记普通 class", declaration)
        }
        if (!baseTool.asStarProjectedType().isAssignableFrom(declaration.asStarProjectedType())) {
            reportError("${declaration.simpleName.asString()} 必须继承 BaseTool", declaration)
        }
        if (!TOOL_NAME_REGEX.matches(name)) {
            reportError("Tool name 必须是合法的 JavaScript 标识符且以小写字母开头：$name", declaration)
        }
        if (description.isBlank()) {
            reportError("Tool description 不能为空", declaration)
        }
        if (autoRegister) validateAutoRegisteredTool(declaration)

        val methods = declaration.getDeclaredFunctions()
            .filterNot(KSFunctionDeclaration::isConstructor)
            .mapNotNull(::parseToolMethod)
            .sortedBy(MethodModel::name)
            .toList()

        methods.groupBy(MethodModel::name)
            .filterValues { it.size > 1 }
            .forEach { (methodName, duplicates) ->
                duplicates.forEach { duplicate ->
                    reportError("JSBridge 不支持同名重载方法：$methodName", duplicate.declaration)
                }
            }

        return ToolModel(
            declaration = declaration,
            qualifiedName = declaration.qualifiedName?.asString().orEmpty(),
            name = name,
            description = description,
            usageScenario = usageScenario,
            autoRegister = autoRegister,
            methods = methods
        )
    }

    private fun validateAutoRegisteredTool(declaration: KSClassDeclaration) {
        if (declaration.qualifiedName == null || declaration.parentDeclaration != null) {
            reportError("自动注册的 Tool 必须是顶层类", declaration)
        }
        if (declaration.getVisibility() != Visibility.PUBLIC) {
            reportError("自动注册的 Tool 必须是 public", declaration)
        }
        if (Modifier.ABSTRACT in declaration.modifiers) {
            reportError("自动注册的 Tool 不能是 abstract", declaration)
        }

        val hasContextConstructor = declaration.getConstructors().any { constructor ->
            constructor.getVisibility() == Visibility.PUBLIC &&
                constructor.parameters.size == 1 &&
                constructor.parameters.single().resolvedTypeName() == ANDROID_CONTEXT_CLASS
        }
        if (!hasContextConstructor) {
            reportError("自动注册的 Tool 必须提供 public constructor(Context)", declaration)
        }
    }

    private fun parseToolMethod(declaration: KSFunctionDeclaration): MethodModel? {
        val javascriptInterface = declaration.findAnnotation(JAVASCRIPT_INTERFACE_ANNOTATION)
        val methodAnnotation = declaration.findAnnotation(TOOL_METHOD_ANNOTATION)
        val returnsAnnotation = declaration.findAnnotation(TOOL_RETURNS_ANNOTATION)
        if (javascriptInterface == null && methodAnnotation == null && returnsAnnotation == null) {
            return null
        }

        if (javascriptInterface == null) {
            reportError("Tool 方法必须添加 @JavascriptInterface", declaration)
        }
        if (methodAnnotation == null) {
            reportError("Tool 方法必须添加 @ToolMethod", declaration)
        }
        if (returnsAnnotation == null) {
            reportError("Tool 方法必须添加 @ToolReturns", declaration)
        }
        if (declaration.getVisibility() != Visibility.PUBLIC) {
            reportError("JSBridge 方法必须是 public", declaration)
        }
        if (Modifier.SUSPEND in declaration.modifiers) {
            reportError("JSBridge 方法不能是 suspend", declaration)
        }
        if (declaration.extensionReceiver != null) {
            reportError("JSBridge 方法不能是扩展函数", declaration)
        }
        if (declaration.typeParameters.isNotEmpty()) {
            reportError("JSBridge 方法不能声明泛型参数", declaration)
        }
        if (!METHOD_NAME_REGEX.matches(declaration.simpleName.asString())) {
            reportError("JSBridge 方法名必须是合法的 JavaScript 标识符", declaration)
        }
        if (declaration.findAnnotation(JVM_NAME_ANNOTATION) != null) {
            reportError("JSBridge 方法不能使用 @JvmName", declaration)
        }
        if (declaration.findAnnotation(JVM_OVERLOADS_ANNOTATION) != null) {
            reportError("JSBridge 方法不能使用 @JvmOverloads", declaration)
        }

        val returnType = declaration.returnType?.resolve()
        if (returnType?.declaration?.qualifiedName?.asString() != KOTLIN_STRING_CLASS ||
            returnType.nullability == Nullability.NULLABLE
        ) {
            reportError("JSBridge 方法返回类型必须是非空 String", declaration)
        }

        val params = declaration.parameters.map(::parseToolParameter)
        declaration.parameters.filter(KSValueParameter::hasDefault).forEach { parameter ->
            reportError("JSBridge 参数不能声明默认值", parameter)
        }
        val description = methodAnnotation?.stringArgument("description").orEmpty()
        if (description.isBlank()) {
            reportError("ToolMethod description 不能为空", declaration)
        }
        val returnsDescription = returnsAnnotation?.stringArgument("description").orEmpty()
        if (returnsDescription.isBlank()) {
            reportError("ToolReturns description 不能为空", declaration)
        }

        return MethodModel(
            declaration = declaration,
            name = declaration.simpleName.asString(),
            description = description,
            example = methodAnnotation?.stringArgument("example").orEmpty(),
            returnsDescription = returnsDescription,
            returnsJsonExample = returnsAnnotation?.stringArgument("jsonExample").orEmpty(),
            params = params
        )
    }

    private fun parseToolParameter(parameter: KSValueParameter): ParamModel {
        val annotation = parameter.findAnnotation(TOOL_PARAM_ANNOTATION)
        if (annotation == null) {
            reportError("JSBridge 参数必须添加 @ToolParam", parameter)
        }
        if (parameter.isVararg) {
            reportError("JSBridge 参数不能使用 vararg", parameter)
        }

        val resolvedType = parameter.type.resolve()
        val typeName = resolvedType.declaration.qualifiedName?.asString().orEmpty()
        if (typeName !in SUPPORTED_PARAMETER_TYPES || resolvedType.nullability == Nullability.NULLABLE) {
            reportError(
                "JSBridge 参数只支持非空 String / Int / Long / Boolean / Double，当前为 $typeName",
                parameter
            )
        }

        val publicName = annotation?.stringArgument("name").orEmpty()
        if (!PARAMETER_NAME_REGEX.matches(publicName)) {
            reportError("ToolParam name 必须是合法的 JavaScript 标识符：$publicName", parameter)
        }

        return ParamModel(
            name = publicName,
            type = resolvedType.declaration.simpleName.asString(),
            description = annotation?.stringArgument("description").orEmpty()
        )
    }

    private fun generateToolDocument(
        dependencies: Dependencies,
        tools: List<ToolModel>,
        markdown: String
    ) {
        val content = buildString {
            appendLine("package $GENERATED_PACKAGE")
            appendLine()
            appendLine("object GeneratedMantouToolsDoc {")
            appendLine("    val documentedToolNames: List<String> = ${renderStringList(tools.map { it.name })}")
            appendLine("    val markdown: String = ${markdown.toKotlinStringLiteral()}")
            appendLine("}")
        }
        writeGeneratedFile(dependencies, GENERATED_PACKAGE, "GeneratedMantouToolsDoc", "kt", content)
    }

    private fun generateToolRegistry(
        dependencies: Dependencies,
        tools: List<ToolModel>
    ) {
        val registeredTools = tools.filter(ToolModel::autoRegister)
        val content = buildString {
            appendLine("package $GENERATED_PACKAGE")
            appendLine()
            appendLine("import android.content.Context")
            appendLine("import com.hfad.mantou.tool.BaseTool")
            appendLine()
            appendLine("object GeneratedToolRegistry {")
            appendLine("    val toolNames: List<String> = ${renderStringList(registeredTools.map { it.name })}")
            if (registeredTools.isEmpty()) {
                appendLine("    fun createAll(context: Context): List<BaseTool> = emptyList()")
            } else {
                appendLine("    fun createAll(context: Context): List<BaseTool> = listOfNotNull<BaseTool>(")
                registeredTools.forEach { tool ->
                    appendLine("        runCatching { ${tool.qualifiedName}(context) }.getOrNull(),")
                }
                appendLine("    )")
            }
            appendLine("}")
        }
        writeGeneratedFile(dependencies, GENERATED_PACKAGE, "GeneratedToolRegistry", "kt", content)
    }

    private fun generateMarkdownResource(dependencies: Dependencies, markdown: String) {
        writeGeneratedFile(dependencies, "", "mantou_tools", "md", markdown)
    }

    private fun writeGeneratedFile(
        dependencies: Dependencies,
        packageName: String,
        fileName: String,
        extension: String,
        content: String
    ) {
        codeGenerator.createNewFile(dependencies, packageName, fileName, extension)
            .bufferedWriter()
            .use { writer -> writer.write(content) }
    }

    private fun reportError(message: String, node: KSNode? = null) {
        hasErrors = true
        logger.error(message, node)
    }

    private companion object {
        private const val MANTOU_TOOL_ANNOTATION = "com.hfad.mantou.tool.MantouTool"
        private const val MANTOU_TOOL_SIMPLE_NAME = "MantouTool"
        private const val TOOL_METHOD_ANNOTATION = "com.hfad.mantou.tool.ToolMethod"
        private const val TOOL_PARAM_ANNOTATION = "com.hfad.mantou.tool.ToolParam"
        private const val TOOL_RETURNS_ANNOTATION = "com.hfad.mantou.tool.ToolReturns"
        private const val JAVASCRIPT_INTERFACE_ANNOTATION = "android.webkit.JavascriptInterface"
        private const val JVM_NAME_ANNOTATION = "kotlin.jvm.JvmName"
        private const val JVM_OVERLOADS_ANNOTATION = "kotlin.jvm.JvmOverloads"
        private const val BASE_TOOL_CLASS = "com.hfad.mantou.tool.BaseTool"
        private const val ANDROID_CONTEXT_CLASS = "android.content.Context"
        private const val KOTLIN_STRING_CLASS = "kotlin.String"
        private const val GENERATED_PACKAGE = "com.hfad.mantou.tool.generated"
        private val TOOL_NAME_REGEX = Regex("[a-z][A-Za-z0-9_]*")
        private val METHOD_NAME_REGEX = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val PARAMETER_NAME_REGEX = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val SUPPORTED_PARAMETER_TYPES = setOf(
            "kotlin.String",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Boolean",
            "kotlin.Double"
        )
    }
}

private data class ToolModel(
    val declaration: KSClassDeclaration,
    val qualifiedName: String,
    val name: String,
    val description: String,
    val usageScenario: String,
    val autoRegister: Boolean,
    val methods: List<MethodModel>
)

private data class MethodModel(
    val declaration: KSFunctionDeclaration,
    val name: String,
    val description: String,
    val example: String,
    val returnsDescription: String,
    val returnsJsonExample: String,
    val params: List<ParamModel>
)

private data class ParamModel(
    val name: String,
    val type: String,
    val description: String
)

private fun KSAnnotated.findAnnotation(qualifiedName: String): KSAnnotation? {
    return annotations.firstOrNull { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }
}

private fun KSAnnotation.argument(name: String): Any? {
    return arguments.firstOrNull { it.name?.asString() == name }?.value
        ?: defaultArguments.firstOrNull { it.name?.asString() == name }?.value
}

private fun KSAnnotation.stringArgument(name: String): String = argument(name) as? String ?: ""

private fun KSAnnotation.booleanArgument(name: String, defaultValue: Boolean): Boolean {
    return argument(name) as? Boolean ?: defaultValue
}

private fun KSValueParameter.resolvedTypeName(): String {
    return type.resolve().declaration.qualifiedName?.asString().orEmpty()
}

private fun renderMarkdown(tools: List<ToolModel>): String = buildString {
    appendLine("# 馒头 App 可用 Tools")
    appendLine()
    appendLine("> **KSP 编译期自动生成，请勿手动修改**。")
    appendLine("> 来源: `@MantouTool` / `@ToolMethod` / `@ToolParam` / `@ToolReturns`")
    appendLine(">")
    appendLine("> ## 调用约定")
    appendLine("> ```js")
    appendLine("> if (window.MantouApp && window.MantouApp.isMantouApp && window.MantouApp.isMantouApp()) {")
    appendLine(">     var rawJson = window.MantouApp.<toolName>.<methodName>(...args);")
    appendLine(">     var result = JSON.parse(rawJson);")
    appendLine(">     if (result.success) { /* 使用 result.data */ } else { /* 提示 result.error */ }")
    appendLine("> }")
    appendLine("> ```")
    appendLine(">")
    appendLine("> 所有方法都返回 JSON 字符串：")
    appendLine("> `{\"success\": bool, \"data\": any, \"error\": string|null}`")
    appendLine(">")
    appendLine("> 参数只能使用 String / Int / Long / Boolean / Double；复杂数据请传 JSON 字符串。")
    appendLine()
    appendLine("---")
    appendLine()

    tools.forEach { tool ->
        appendLine("## `${tool.name}`")
        appendLine()
        appendLine("**描述**：${tool.description}")
        if (tool.usageScenario.isNotBlank()) {
            appendLine()
            appendLine("**使用场景**：${tool.usageScenario}")
        }
        appendLine()

        if (tool.methods.isEmpty()) {
            appendLine("_（该 Tool 暂无对外方法）_")
            appendLine()
        } else {
            tool.methods.forEach { method ->
                val signature = method.params.joinToString(", ") { "${it.name}: ${it.type}" }
                appendLine("### `window.MantouApp.${tool.name}.${method.name}($signature)` → String")
                appendLine()
                appendLine(method.description)
                appendLine()
                if (method.params.isNotEmpty()) {
                    appendLine("**参数**：")
                    method.params.forEach { parameter ->
                        appendLine("- `${parameter.name}` (${parameter.type})：${parameter.description}")
                    }
                    appendLine()
                }
                appendLine("**返回**：${method.returnsDescription}")
                if (method.returnsJsonExample.isNotBlank()) {
                    appendLine()
                    appendLine("```json")
                    appendLine(method.returnsJsonExample)
                    appendLine("```")
                }
                appendLine()
                if (method.example.isNotBlank()) {
                    appendLine("**调用示例**：")
                    appendLine("```js")
                    appendLine(method.example)
                    appendLine("```")
                    appendLine()
                }
            }
        }
        appendLine("---")
        appendLine()
    }
}

private fun renderStringList(values: List<String>): String {
    if (values.isEmpty()) return "emptyList()"
    return values.joinToString(prefix = "listOf(", postfix = ")") { it.toKotlinStringLiteral() }
}

private fun String.toKotlinStringLiteral(): String = buildString {
    append('"')
    this@toKotlinStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> {
                append('\\')
                append('$')
            }
            else -> append(character)
        }
    }
    append('"')
}
