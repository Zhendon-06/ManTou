package com.hfad.mantou.utils.harness

import com.google.gson.JsonParser
import com.hfad.mantou.utils.WebProjectContentServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Locale

data class WebProjectStaticCheckResult(
    val passed: Boolean,
    val summary: String,
    val diagnostics: List<String> = emptyList()
)

fun interface WebProjectStaticChecker {
    suspend fun check(project: WebProjectContentServer): WebProjectStaticCheckResult
}

object DefaultWebProjectStaticChecker : WebProjectStaticChecker {

    override suspend fun check(
        project: WebProjectContentServer
    ): WebProjectStaticCheckResult = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        for (projectFile in project.files) {
            val extension = projectFile.relativePath.substringAfterLast('.', "")
                .lowercase(Locale.US)
            if (extension !in TEXT_EXTENSIONS) continue
            val contentResult = runCatching { decodeUtf8(projectFile.file.readBytes()) }
            if (contentResult.isFailure) {
                val error = contentResult.exceptionOrNull()
                diagnostics += "TEXT_INVALID_UTF8: ${projectFile.relativePath}: " +
                    (error?.message ?: "文件不是合法 UTF-8")
                continue
            }
            val content = contentResult.getOrThrow()
            when (extension) {
                "html", "htm" -> {
                    WebInspectionParsers.preflightBuildDiagnostics(content)
                        .filter { it.severity == WebDiagnosticSeverity.ERROR }
                        .forEach { diagnostic ->
                            diagnostics += "${diagnostic.code}: ${projectFile.relativePath}: ${diagnostic.message}"
                        }
                    collectHtmlReferences(content).forEach { reference ->
                        validateReference(project, projectFile.relativePath, reference, diagnostics)
                    }
                }

                "css" -> collectCssReferences(content).forEach { reference ->
                    validateReference(project, projectFile.relativePath, reference, diagnostics)
                }

                "js", "mjs", "cjs" -> {
                    collectJavaScriptModuleReferences(content).forEach { reference ->
                        validateReference(project, projectFile.relativePath, reference, diagnostics)
                    }
                    collectJavaScriptDocumentReferences(content).forEach { reference ->
                        validateReference(project, project.entryRelativePath, reference, diagnostics)
                    }
                }

                "json", "map", "webmanifest" -> runCatching {
                    JsonParser.parseString(content)
                }.onFailure { error ->
                    diagnostics += "JSON_INVALID: ${projectFile.relativePath}: " +
                        (error.message ?: "JSON 语法无效")
                }
            }
            if (diagnostics.size >= MAX_DIAGNOSTICS) break
        }

        if (diagnostics.size < MAX_DIAGNOSTICS) {
            validateHtmlCssClassAlignment(project, diagnostics)
        }

        val bounded = diagnostics.distinct().take(MAX_DIAGNOSTICS)
        WebProjectStaticCheckResult(
            passed = bounded.isEmpty(),
            summary = if (bounded.isEmpty()) {
                "项目静态检查通过（${project.files.size} 个文件）"
            } else {
                "项目静态检查失败：${bounded.size} 个问题"
            },
            diagnostics = bounded
        )
    }

    private fun validateReference(
        project: WebProjectContentServer,
        sourcePath: String,
        reference: String,
        diagnostics: MutableList<String>
    ) {
        val value = reference.trim()
        if (value.isEmpty() || value.startsWith('#') ||
            SAFE_EMBEDDED_SCHEMES.any { value.startsWith(it, ignoreCase = true) }
        ) {
            return
        }
        if (value.startsWith("//") || ABSOLUTE_SCHEME.containsMatchIn(value)) {
            diagnostics += "EXTERNAL_RESOURCE_BLOCKED: $sourcePath -> ${value.take(MAX_REFERENCE_CHARS)}"
            return
        }
        if (project.resolveProjectReference(sourcePath, value) == null) {
            diagnostics += "LOCAL_RESOURCE_MISSING: $sourcePath -> ${value.take(MAX_REFERENCE_CHARS)}"
        }
    }

    private fun collectHtmlReferences(content: String): List<String> {
        return HTML_RESOURCE_ELEMENT.findAll(content).flatMap { element ->
            RESOURCE_ATTRIBUTE.findAll(element.value).flatMap { attribute ->
                val name = attribute.groupValues[1].lowercase(Locale.US)
                val value = attribute.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
                if (name == "srcset") {
                    value.split(',').asSequence()
                        .map { it.trim().split(Regex("\\s+"), limit = 2).first() }
                        .filter(String::isNotEmpty)
                } else {
                    sequenceOf(value)
                }
            }
        }.distinct().toList()
    }

    private fun collectCssReferences(content: String): List<String> {
        val urls = CSS_URL.findAll(content).map { match ->
            match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()
        }
        val imports = CSS_IMPORT.findAll(content).map { match ->
            match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()
        }
        return (urls + imports).filter(String::isNotEmpty).distinct().toList()
    }

    private fun collectJavaScriptModuleReferences(content: String): List<String> {
        return sequenceOf(JS_STATIC_IMPORT, JS_DYNAMIC_IMPORT)
            .flatMap { pattern ->
                pattern.findAll(content).map { match ->
                    match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()
                }
            }
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
    }

    private fun collectJavaScriptDocumentReferences(content: String): List<String> {
        return JS_FETCH_OR_WORKER.findAll(content)
            .map { match -> match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty() }
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
    }

    private fun validateHtmlCssClassAlignment(
        project: WebProjectContentServer,
        diagnostics: MutableList<String>
    ) {
        val html = runCatching { decodeUtf8(project.entryFile.readBytes()) }.getOrNull() ?: return
        val htmlClasses = collectHtmlClassNames(html)
            .filterTo(sortedSetOf()) { SIMPLE_CLASS_NAME.matches(it) }
        if (htmlClasses.size < MIN_ALIGNMENT_CLASS_COUNT) return

        val stylesheetSources = collectLoadedStylesheets(project, html)
        val inlineStyles = HTML_STYLE_BLOCK.findAll(html).map { it.groupValues[1] }.toList()
        val cssClasses = (stylesheetSources.map(LoadedStylesheet::content) + inlineStyles)
            .asSequence()
            .flatMap { collectCssSelectorClasses(it).asSequence() }
            .filterTo(sortedSetOf()) { SIMPLE_CLASS_NAME.matches(it) }
        if (cssClasses.size < MIN_ALIGNMENT_CLASS_COUNT) return

        val matchedClasses = htmlClasses intersect cssClasses
        val unmatchedClasses = htmlClasses - matchedClasses
        val coverage = matchedClasses.size.toDouble() / htmlClasses.size
        if (coverage >= MIN_HTML_CLASS_COVERAGE ||
            unmatchedClasses.size < MIN_UNMATCHED_HTML_CLASSES
        ) {
            return
        }

        val stylesheetLabel = stylesheetSources
            .map(LoadedStylesheet::relativePath)
            .distinct()
            .sorted()
            .joinToString(", ")
            .ifBlank { "入口内联样式" }
        val unmatchedSample = unmatchedClasses.take(MAX_CLASS_SAMPLE_COUNT).joinToString(", ")
        val coveragePercent = (coverage * 100).toInt()
        diagnostics += buildString {
            append("HTML_CSS_CLASS_MISMATCH: ")
            append(project.entryRelativePath)
            append(" -> ").append(stylesheetLabel)
            append(": HTML 中 ").append(htmlClasses.size).append(" 个静态类名仅有 ")
            append(matchedClasses.size).append(" 个被已加载样式表引用（")
            append(coveragePercent).append("%），页面结构与样式表可能来自不同版本")
            append("；未匹配示例: ").append(unmatchedSample)
        }
    }

    private fun collectHtmlClassNames(html: String): Set<String> {
        val markupOnly = HTML_COMMENT.replace(
            HTML_SCRIPT_OR_STYLE_BLOCK.replace(html, " "),
            " "
        )
        return HTML_TAG.findAll(markupOnly)
            .mapNotNull { tag -> htmlAttribute(tag.value, "class") }
            .flatMap { value -> value.splitToSequence(Regex("\\s+")) }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
    }

    private fun collectCssSelectorClasses(css: String): Set<String> {
        val selectorSource = CSS_STRING.replace(
            CSS_COMMENT.replace(
                CSS_URL.replace(css, " "),
                " "
            ),
            " "
        )
        return CSS_RULE_PRELUDE.findAll(selectorSource)
            .map { it.groupValues[1].substringAfterLast(';').trim() }
            .filterNot { it.startsWith('@') }
            .flatMap { prelude -> CSS_CLASS_SELECTOR.findAll(prelude).map { it.groupValues[1] } }
            .toSet()
    }

    private fun collectLoadedStylesheets(
        project: WebProjectContentServer,
        html: String
    ): List<LoadedStylesheet> {
        val queue = ArrayDeque<WebProjectContentServer.ProjectFile>()
        val visited = mutableSetOf<String>()
        val stylesheets = mutableListOf<LoadedStylesheet>()

        HTML_LINK_ELEMENT.findAll(html).forEach { link ->
            val rel = htmlAttribute(link.value, "rel")
                ?.split(Regex("\\s+"))
                ?.any { it.equals("stylesheet", ignoreCase = true) }
                ?: false
            if (!rel) return@forEach
            val href = htmlAttribute(link.value, "href") ?: return@forEach
            project.resolveProjectReference(project.entryRelativePath, href)
                ?.takeIf { it.relativePath.endsWith(".css", ignoreCase = true) }
                ?.let(queue::addLast)
        }

        while (queue.isNotEmpty()) {
            val stylesheet = queue.removeFirst()
            if (!visited.add(stylesheet.relativePath)) continue
            val content = runCatching { decodeUtf8(stylesheet.file.readBytes()) }.getOrNull() ?: continue
            stylesheets += LoadedStylesheet(stylesheet.relativePath, content)
            CSS_IMPORT.findAll(content).forEach { match ->
                val reference = match.groupValues.drop(1)
                    .firstOrNull(String::isNotEmpty)
                    ?: return@forEach
                project.resolveProjectReference(stylesheet.relativePath, reference)
                    ?.takeIf { it.relativePath.endsWith(".css", ignoreCase = true) }
                    ?.let(queue::addLast)
            }
        }
        return stylesheets
    }

    private fun htmlAttribute(tag: String, name: String): String? {
        return HTML_ATTRIBUTE.findAll(tag)
            .firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.groupValues
            ?.drop(2)
            ?.firstOrNull(String::isNotEmpty)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private val TEXT_EXTENSIONS = setOf(
        "html", "htm", "css", "js", "mjs", "cjs", "json", "map", "webmanifest", "svg", "txt", "md"
    )
    private val SAFE_EMBEDDED_SCHEMES = listOf("data:", "blob:", "about:", "javascript:", "mailto:", "tel:")
    private val ABSOLUTE_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val HTML_RESOURCE_ELEMENT = Regex(
        "<(?:script|link|img|source|video|audio|track|object|embed|iframe)\\b[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val HTML_LINK_ELEMENT = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<[A-Za-z][^>]*>")
    private val HTML_ATTRIBUTE = Regex(
        """\b([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))"""
    )
    private val HTML_COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val HTML_SCRIPT_OR_STYLE_BLOCK = Regex(
        "<(?:script|style)\\b[^>]*>[\\s\\S]*?</(?:script|style)\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val HTML_STYLE_BLOCK = Regex(
        "<style\\b[^>]*>([\\s\\S]*?)</style\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val RESOURCE_ATTRIBUTE = Regex(
        "\\b(src|href|poster|data|srcset)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
        RegexOption.IGNORE_CASE
    )
    private val CSS_URL = Regex(
        "url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)'\"\\s]+))\\s*\\)",
        RegexOption.IGNORE_CASE
    )
    private val CSS_IMPORT = Regex(
        "@import\\s+(?:url\\(\\s*)?(?:\"([^\"]*)\"|'([^']*)')",
        RegexOption.IGNORE_CASE
    )
    private val CSS_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
    private val CSS_STRING = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
    private val CSS_RULE_PRELUDE = Regex("([^{}]+)\\{")
    private val CSS_CLASS_SELECTOR = Regex("(?<![A-Za-z0-9_-])\\.(-?[_A-Za-z][_A-Za-z0-9-]*)")
    private val SIMPLE_CLASS_NAME = Regex("-?[_A-Za-z][_A-Za-z0-9-]*")
    private val JS_STATIC_IMPORT = Regex(
        "(?:import|export)\\s+(?:[^;]*?\\s+from\\s+)?[\"']([^\"']+)[\"']"
    )
    private val JS_DYNAMIC_IMPORT = Regex("\\bimport\\s*\\(\\s*[\"']([^\"']+)[\"']")
    private val JS_FETCH_OR_WORKER = Regex(
        "\\b(?:fetch|Worker|SharedWorker)\\s*\\(\\s*[\"']([^\"']+)[\"']"
    )

    private const val MAX_DIAGNOSTICS = 40
    private const val MAX_REFERENCE_CHARS = 500
    private const val MIN_ALIGNMENT_CLASS_COUNT = 8
    private const val MIN_UNMATCHED_HTML_CLASSES = 6
    private const val MIN_HTML_CLASS_COVERAGE = 0.25
    private const val MAX_CLASS_SAMPLE_COUNT = 8

    private data class LoadedStylesheet(
        val relativePath: String,
        val content: String
    )
}
