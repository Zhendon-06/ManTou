package com.hfad.mantou.utils.harness

import com.google.gson.JsonParser

internal data class InlineScriptSource(
    val index: Int,
    val source: String,
    val startLine: Int,
    val type: String?,
    val sourceUrl: String?
)

internal data class ParsedSelfTestResult(
    val passed: Boolean,
    val cases: List<WebSelfTestCase>
)

internal object WebInspectionParsers {
    private val scriptPattern = Regex(
        pattern = "<script\\b([^>]*)>([\\s\\S]*?)</script\\s*>",
        option = RegexOption.IGNORE_CASE
    )
    private val attributePattern = Regex(
        pattern = "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
        option = RegexOption.IGNORE_CASE
    )
    private val linkPattern = Regex(
        pattern = "<link\\b([^>]*)>",
        option = RegexOption.IGNORE_CASE
    )
    private val stackLocationPattern = Regex(
        pattern = "(?:at\\s+.*?\\s+\\()?((?:https?|file|content)://[^\\s)]+?):(\\d+):(\\d+)\\)?",
        option = RegexOption.IGNORE_CASE
    )

    fun preflightBuildDiagnostics(html: String): List<WebInspectionDiagnostic> {
        val diagnostics = mutableListOf<WebInspectionDiagnostic>()
        if (html.isBlank()) {
            diagnostics += buildError("HTML_EMPTY", "生成的 HTML 为空")
            return diagnostics
        }
        if (html.trimStart().startsWith("```")) {
            diagnostics += buildError("HTML_MARKDOWN_FENCE", "HTML 仍包含 Markdown 代码围栏")
        }
        if (!Regex("<html\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            diagnostics += buildError("HTML_ROOT_MISSING", "缺少 <html> 根元素")
        }
        if (!Regex("<!doctype\\s+html", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            diagnostics += WebInspectionDiagnostic(
                stage = WebInspectionStage.BUILD,
                severity = WebDiagnosticSeverity.WARNING,
                category = WebDiagnosticCategory.HTML,
                code = "HTML_DOCTYPE_MISSING",
                message = "缺少 <!DOCTYPE html> 声明"
            )
        }
        val openingScripts = Regex("<script\\b", RegexOption.IGNORE_CASE).findAll(html).count()
        val closingScripts = Regex("</script\\s*>", RegexOption.IGNORE_CASE).findAll(html).count()
        if (openingScripts != closingScripts) {
            diagnostics += buildError(
                code = "HTML_SCRIPT_UNBALANCED",
                message = "<script> 标签未闭合：开始 $openingScripts 个，结束 $closingScripts 个"
            )
        }
        return diagnostics
    }

    fun extractInlineScripts(html: String): List<InlineScriptSource> {
        return scriptPattern.findAll(html).mapIndexedNotNull { index, match ->
            val attributes = parseAttributes(match.groupValues[1])
            val sourceUrl = attributes["src"]
            val type = attributes["type"]?.trim()?.lowercase()
            if (sourceUrl != null) {
                null
            } else {
                InlineScriptSource(
                    index = index + 1,
                    source = match.groupValues[2],
                    startLine = html.take(match.range.first).count { it == '\n' } + 1,
                    type = type,
                    sourceUrl = null
                )
            }
        }.toList()
    }

    fun externalScriptSources(html: String): List<String> {
        return scriptPattern.findAll(html).mapNotNull { match ->
            parseAttributes(match.groupValues[1])["src"]
        }.toList()
    }

    fun injectRuntimeHarness(html: String, harnessScript: String): String {
        if (html.contains(GeneratedAppWebViewInspector.RUNTIME_HARNESS_MARKER)) return html

        val favicon = if (hasExplicitIcon(html)) "" else EMPTY_FAVICON_LINK
        val injection = favicon + "<script>$harnessScript</script>"
        val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (head != null) {
            val offset = head.range.last + 1
            return html.substring(0, offset) + injection + html.substring(offset)
        }

        val doctype = Regex("<!doctype\\s+html[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (doctype != null) {
            val offset = doctype.range.last + 1
            return html.substring(0, offset) + injection + html.substring(offset)
        }
        return injection + html
    }

    fun parseConsoleDiagnostic(
        stage: WebInspectionStage,
        message: String,
        level: String,
        source: String?,
        line: Int?
    ): WebInspectionDiagnostic? {
        val normalizedLevel = level.uppercase()
        if (normalizedLevel != "ERROR" && normalizedLevel != "WARNING") return null

        val severity = if (normalizedLevel == "ERROR") {
            WebDiagnosticSeverity.ERROR
        } else {
            WebDiagnosticSeverity.WARNING
        }
        val isSyntaxError = message.contains("SyntaxError", ignoreCase = true)
        val isResourceError = message.contains("Failed to load resource", ignoreCase = true) ||
            message.contains("net::ERR_", ignoreCase = true)
        val category = when {
            isSyntaxError -> WebDiagnosticCategory.JAVASCRIPT
            isResourceError -> WebDiagnosticCategory.RESOURCE
            else -> WebDiagnosticCategory.CONSOLE
        }
        val code = when {
            isSyntaxError -> "JS_SYNTAX_ERROR"
            isResourceError -> "RESOURCE_CONSOLE_ERROR"
            message.contains("Unhandled", ignoreCase = true) ||
                message.contains("Uncaught", ignoreCase = true) -> "JS_RUNTIME_ERROR"
            normalizedLevel == "WARNING" -> "CONSOLE_WARNING"
            else -> "CONSOLE_ERROR"
        }
        val stackLocation = parseStackLocation(message)
        val location = WebSourceLocation(
            source = source?.takeIf { it.isNotBlank() } ?: stackLocation?.source,
            line = line?.takeIf { it > 0 } ?: stackLocation?.line,
            column = stackLocation?.column
        ).takeUnless { it.source == null && it.line == null && it.column == null }

        return WebInspectionDiagnostic(
            stage = stage,
            severity = severity,
            category = category,
            code = code,
            message = message,
            location = location,
            stackTrace = message.takeIf { it.contains("\n    at ") }
        )
    }

    fun decodeJavascriptString(value: String?): String? {
        if (value == null || value == "null" || value == "undefined") return null
        return runCatching {
            val element = JsonParser.parseString(value)
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                element.asString
            } else {
                value
            }
        }.getOrElse { value }
    }

    fun parseSelfTestResult(payload: String): ParsedSelfTestResult? {
        return runCatching {
            val root = JsonParser.parseString(payload).asJsonObject
            val cases = root.getAsJsonArray("cases")?.mapIndexed { index, element ->
                val item = element.asJsonObject
                WebSelfTestCase(
                    name = item.get("name")?.takeUnless { it.isJsonNull }?.asString
                        ?: "self-test-${index + 1}",
                    passed = item.get("passed")?.takeUnless { it.isJsonNull }?.asBoolean == true,
                    message = item.get("message")?.takeUnless { it.isJsonNull }?.asString,
                    details = item.get("details")?.takeUnless { it.isJsonNull }?.asString
                )
            }.orEmpty()
            ParsedSelfTestResult(
                passed = root.get("passed")?.takeUnless { it.isJsonNull }?.asBoolean == true &&
                    cases.all { it.passed },
                cases = cases
            )
        }.getOrNull()
    }

    fun parseDiagnosticPayload(
        stage: WebInspectionStage,
        payload: String,
        defaultCategory: WebDiagnosticCategory
    ): List<WebInspectionDiagnostic>? {
        return runCatching {
            JsonParser.parseString(payload).asJsonArray.map { element ->
                val item = element.asJsonObject
                val severity = item.get("severity")?.takeUnless { it.isJsonNull }?.asString
                    ?.let { runCatching { WebDiagnosticSeverity.valueOf(it.uppercase()) }.getOrNull() }
                    ?: WebDiagnosticSeverity.ERROR
                val category = item.get("category")?.takeUnless { it.isJsonNull }?.asString
                    ?.let { runCatching { WebDiagnosticCategory.valueOf(it.uppercase()) }.getOrNull() }
                    ?: defaultCategory
                val source = item.get("source")?.takeUnless { it.isJsonNull }?.asString
                val line = item.get("line")?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it > 0 }
                val column = item.get("column")?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it > 0 }
                WebInspectionDiagnostic(
                    stage = stage,
                    severity = severity,
                    category = category,
                    code = item.get("code")?.takeUnless { it.isJsonNull }?.asString
                        ?: "WEB_INSPECTION_ERROR",
                    message = item.get("message")?.takeUnless { it.isJsonNull }?.asString
                        ?: "WebView 检查失败",
                    location = WebSourceLocation(source, line, column)
                        .takeUnless { source == null && line == null && column == null },
                    stackTrace = item.get("stack")?.takeUnless { it.isJsonNull }?.asString
                )
            }
        }.getOrNull()
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        return attributePattern.findAll(raw).associate { match ->
            val value = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            match.groupValues[1].lowercase() to value
        }
    }

    private fun hasExplicitIcon(html: String): Boolean {
        return linkPattern.findAll(html).any { match ->
            parseAttributes(match.groupValues[1])["rel"]
                ?.split(Regex("\\s+"))
                ?.any { it.equals("icon", ignoreCase = true) } == true
        }
    }

    private fun parseStackLocation(message: String): WebSourceLocation? {
        val match = stackLocationPattern.find(message) ?: return null
        return WebSourceLocation(
            source = match.groupValues[1],
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull()
        )
    }

    private fun buildError(code: String, message: String): WebInspectionDiagnostic {
        return WebInspectionDiagnostic(
            stage = WebInspectionStage.BUILD,
            severity = WebDiagnosticSeverity.ERROR,
            category = WebDiagnosticCategory.HTML,
            code = code,
            message = message
        )
    }

    internal const val EMPTY_FAVICON_LINK =
        "<link rel=\"icon\" type=\"image/png\" href=\"data:image/png;base64," +
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=\">"
}
