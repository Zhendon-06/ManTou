package com.hfad.mantou.tool

import android.content.Context
import android.webkit.JavascriptInterface
import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Tool 注册表。
 *
 * 职责：
 * 1. 在 register 列表里列出所有 Tool 类。每次新增 Tool 后在这里加一行即可。
 * 2. init(context) 把所有 Tool 实例化、缓存。
 * 3. 提供 instances() 给 JSBridge 注入到 WebView。
 * 4. 提供 generateMarkdownDoc()：扫描注解，生成 LLM 可读的 Markdown 文档。
 *    第一次初始化时把文档落盘到 AgentWorkspace 指定目录，
 *    Web App 生成 prompt 时把这个文档注入给 LLM，
 *    LLM 就知道当前 App 能调哪些 Android 系统能力。
 */
object ToolRegistry {

    /** 注册新 Tool：在这里加一行类引用即可。 */
    private val toolClasses: List<KClass<out BaseTool>> = listOf(
        com.hfad.mantou.tool.impl.AlarmTool::class,
        com.hfad.mantou.tool.impl.CalendarTool::class,
        com.hfad.mantou.tool.impl.CameraTool::class,
        com.hfad.mantou.tool.impl.ClipboardTool::class,
        com.hfad.mantou.tool.impl.FlashlightTool::class,
        com.hfad.mantou.tool.impl.ToastTool::class,
        com.hfad.mantou.tool.impl.VibrationTool::class,
    )

    private var initialized: Boolean = false
    private val toolInstances: MutableList<BaseTool> = mutableListOf()

    /** 初始化：实例化所有 Tool。线程安全（同步），主线程调用即可。 */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        toolInstances.clear()
        for (kClass in toolClasses) {
            runCatching {
                val ctor = kClass.java.getConstructor(Context::class.java)
                toolInstances.add(ctor.newInstance(appContext))
            }
        }
        initialized = true
    }

    /** 返回所有 Tool 实例，供 WebView 注入 JSBridge 时使用。 */
    fun instances(): List<BaseTool> = toolInstances.toList()

    /** 按 toolName 查找单个 Tool 实例。 */
    fun get(toolName: String): BaseTool? = toolInstances.firstOrNull { it.toolName == toolName }

    /**
     * 生成给 LLM 阅读的 Markdown 文档。
     * Web App 生成 prompt 时拼接此文档，LLM 据此知道能调哪些方法、参数和返回值。
     */
    fun generateMarkdownDoc(): String = buildString {
        appendLine("# 馒头 App 可用 Tools")
        appendLine()
        appendLine("> 以下 Tool 在馒头 App 的 WebView 中可直接调用。")
        appendLine("> JS 调用约定：`window.MantouApp.<toolName>.<methodName>(...args)`")
        appendLine("> 所有方法均返回 JSON 字符串：")
        appendLine("> `{\"success\": bool, \"data\": any, \"error\": string|null}`")
        appendLine()
        appendLine("调用前请用 `window.MantouApp.isMantouApp()` 判断当前是否在馒头 App 中，")
        appendLine("不在时引导用户在馒头 App 中打开。")
        appendLine()
        appendLine("---")
        appendLine()

        for (tool in toolInstances) {
            val toolAnno = tool::class.java.getAnnotation(MantouTool::class.java) ?: continue
            appendLine("## ${toolAnno.name}")
            appendLine()
            appendLine("**描述**：${toolAnno.description}")
            if (toolAnno.usageScenario.isNotBlank()) {
                appendLine()
                appendLine("**使用场景**：${toolAnno.usageScenario}")
            }
            appendLine()

            val methods = collectToolMethods(tool::class.java)
            if (methods.isEmpty()) {
                appendLine("_（该 Tool 暂无对外方法）_")
                appendLine()
                continue
            }

            for (method in methods) {
                appendMethodDoc(this, toolAnno.name, method)
            }
            appendLine("---")
            appendLine()
        }
    }

    /** 收集类里同时挂着 @JavascriptInterface + @ToolMethod 的方法。 */
    private fun collectToolMethods(clazz: Class<*>): List<Method> =
        clazz.methods
            .filter {
                it.isAnnotationPresent(JavascriptInterface::class.java) &&
                    it.isAnnotationPresent(ToolMethod::class.java)
            }
            .sortedBy { it.name }

    private fun appendMethodDoc(sb: StringBuilder, toolName: String, method: Method) {
        val methodAnno = method.getAnnotation(ToolMethod::class.java)!!
        val returnsAnno = method.getAnnotation(ToolReturns::class.java)

        val params = method.parameters.map { p ->
            val pa = p.getAnnotation(ToolParam::class.java)
            val name = pa?.name ?: p.name
            val type = p.type.simpleName
            val desc = pa?.description.orEmpty()
            Triple(name, type, desc)
        }
        val signature = params.joinToString(", ") { "${it.first}: ${it.second}" }

        sb.appendLine("### `${toolName}.${method.name}($signature) → String`")
        sb.appendLine()
        sb.appendLine(methodAnno.description)
        sb.appendLine()
        if (params.isNotEmpty()) {
            sb.appendLine("**参数**：")
            for ((n, t, d) in params) sb.appendLine("- `$n` (${t})：$d")
            sb.appendLine()
        }
        if (returnsAnno != null) {
            sb.appendLine("**返回**：${returnsAnno.description}")
            if (returnsAnno.jsonExample.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("```json")
                sb.appendLine(returnsAnno.jsonExample)
                sb.appendLine("```")
            }
            sb.appendLine()
        }
        if (methodAnno.example.isNotBlank()) {
            sb.appendLine("**调用示例**：")
            sb.appendLine("```js")
            sb.appendLine(methodAnno.example)
            sb.appendLine("```")
            sb.appendLine()
        }
    }
}
