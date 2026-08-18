package com.hfad.mantou.utils.harness

import com.google.gson.JsonParser
import com.hfad.mantou.utils.WebProjectContentServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
    private val JS_STATIC_IMPORT = Regex(
        "(?:import|export)\\s+(?:[^;]*?\\s+from\\s+)?[\"']([^\"']+)[\"']"
    )
    private val JS_DYNAMIC_IMPORT = Regex("\\bimport\\s*\\(\\s*[\"']([^\"']+)[\"']")
    private val JS_FETCH_OR_WORKER = Regex(
        "\\b(?:fetch|Worker|SharedWorker)\\s*\\(\\s*[\"']([^\"']+)[\"']"
    )

    private const val MAX_DIAGNOSTICS = 40
    private const val MAX_REFERENCE_CHARS = 500
}
