package com.hfad.mantou.tool

/**
 * Tool 元信息注解。标注一个 Tool 的名称、描述、使用场景和注册策略。
 * KSP 会读取这些注解，校验 Tool 契约并生成注册表和 LLM API 文档。
 * autoRegister=false 表示由宿主按页面实例化和注入，不进入全局 GeneratedToolRegistry。
 *
 * 命名概念说明：
 * - Tool 不是 LLM agent 意义上的"工具调用"，而是馒头 App 自定义的
 *   "Android 系统能力桥"。开发者按统一接口包装一个 Android 能力
 *   （闹钟、日历、定位…），生成的网页 HTML 在 WebView 里通过
 *   `window.MantouApp.<toolName>.<method>(...)` 调用真实的 Android API，
 *   让 LLM 生成的网页不只是空壳，而能驱动系统功能。
 *
 * 示例：
 * <pre>
 * @MantouTool(
 *     name = "alarm",
 *     description = "设置系统闹钟、定时器和提醒",
 *     usageScenario = "用户需要设置闹钟、提醒、定时；网页中需要提醒功能时"
 * )
 * class AlarmTool(context: Context) : BaseTool(context) { ... }
 * </pre>
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MantouTool(
    val name: String,
    val description: String,
    val usageScenario: String = "",
    val autoRegister: Boolean = true
)

/**
 * Tool 方法元信息注解。给 @JavascriptInterface 方法补充说明，
 * 生成的 API 文档会包含这些说明，帮助 LLM 正确调用。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class ToolMethod(
    val description: String,
    val example: String = ""
)

/**
 * Tool 参数注解。声明稳定的公开参数名并补充描述，KSP 生成 API 文档时使用。
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class ToolParam(
    val name: String,
    val description: String = ""
)

/**
 * Tool 方法返回值的 JSON 结构描述。
 * LLM 读文档时需要知道返回值格式，以便生成正确的 JS 解析代码。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class ToolReturns(
    val description: String,
    val jsonExample: String = ""
)
