package com.hfad.mantou.utils.harness

import com.hfad.mantou.utils.WebProjectContentServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class WebViewHarnessInspectorAdapter(
    private val inspector: GeneratedAppWebViewInspector,
    private val staticChecker: WebProjectStaticChecker = DefaultWebProjectStaticChecker
) : HarnessBuilder, HarnessInspector, HarnessTestRunner {

    override suspend fun build(request: HarnessCheckRequest): HarnessCheckResult {
        return execute(request)
    }

    override suspend fun inspect(request: HarnessCheckRequest): HarnessCheckResult {
        return execute(request)
    }

    override suspend fun runTests(request: HarnessCheckRequest): HarnessCheckResult {
        return execute(request)
    }

    private suspend fun execute(request: HarnessCheckRequest): HarnessCheckResult {
        val projectResult = withContext(Dispatchers.IO) {
            runCatching { createProjectServer(request) }
        }
        val project = projectResult.getOrElse { error ->
            return HarnessCheckResult(
                passed = false,
                summary = "无法准备 Web 项目",
                diagnostics = listOf(
                    "PROJECT_PREPARE_FAILED: ${error.message ?: error::class.java.simpleName}"
                ),
                artifactPath = request.artifactPath
            )
        }
        coroutineContext.ensureActive()

        if (request.kind == HarnessCheckKind.DEVELOPMENT_BUILD ||
            request.kind == HarnessCheckKind.FINAL_BUILD
        ) {
            val staticResult = runCatching { staticChecker.check(project) }.getOrElse { error ->
                return HarnessCheckResult(
                    passed = false,
                    summary = "项目静态检查执行失败",
                    diagnostics = listOf(
                        "PROJECT_STATIC_CHECK_FAILED: ${error.message ?: error::class.java.simpleName}"
                    ),
                    artifactPath = project.entryFile.absolutePath
                )
            }
            if (!staticResult.passed) {
                return HarnessCheckResult(
                    passed = false,
                    summary = staticResult.summary,
                    diagnostics = staticResult.diagnostics,
                    artifactPath = project.entryFile.absolutePath
                )
            }
            val html = readHtml(project.entryFile).getOrElse { error ->
                return HarnessCheckResult(
                    passed = false,
                    summary = "无法读取项目入口 HTML",
                    diagnostics = listOf(
                        "ARTIFACT_READ_FAILED: ${error.message ?: error::class.java.simpleName} " +
                            "(${project.entryFile.absolutePath})"
                    ),
                    artifactPath = project.entryFile.absolutePath
                )
            }
            val report = inspector.inspectBuild(html)
            return report.toHarnessCheckResult(
                artifactPath = project.entryFile.absolutePath,
                prefixSummary = staticResult.summary,
                extraDiagnostics = staticResult.diagnostics
            )
        }

        val target = WebInspectionTarget.Url(project.entryUrl)
        val requestInterceptor = WebRequestInterceptor(project::intercept)
        val report = when (request.kind) {
            HarnessCheckKind.RUNTIME -> inspector.inspectRuntime(
                target = target,
                requestInterceptor = requestInterceptor
            )

            HarnessCheckKind.SELF_TEST -> inspector.runSelfTests(
                target = target,
                testScript = request.testScript.orEmpty(),
                requestInterceptor = requestInterceptor
            )

            HarnessCheckKind.TEST_SUITE -> inspector.runTestSuite(
                target = target,
                testScript = request.testScript.orEmpty(),
                requestInterceptor = requestInterceptor
            )

            HarnessCheckKind.DEVELOPMENT_BUILD,
            HarnessCheckKind.FINAL_BUILD -> error("Build checks returned before runtime inspection")
        }
        return report.toHarnessCheckResult(project.entryFile.absolutePath)
    }

    private fun createProjectServer(request: HarnessCheckRequest): WebProjectContentServer {
        val workspacePath = request.workspacePath.trim()
        require(workspacePath.isNotEmpty()) { "workspacePath 不能为空" }
        val projectRoot = File(workspacePath)
        require(projectRoot.isDirectory) { "workspacePath 不存在或不是目录" }
        val artifactPath = request.artifactPath?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("artifactPath 不能为空")
        val artifact = File(artifactPath).let { file ->
            if (file.isAbsolute) file else File(projectRoot, artifactPath)
        }
        val htmlTransformer = when (request.kind) {
            HarnessCheckKind.RUNTIME,
            HarnessCheckKind.SELF_TEST,
            HarnessCheckKind.TEST_SUITE -> { html: String ->
                GeneratedAppWebViewInspector.prepareProjectHtmlForInspection(html)
            }

            HarnessCheckKind.DEVELOPMENT_BUILD,
            HarnessCheckKind.FINAL_BUILD -> null
        }
        return WebProjectContentServer.create(
            projectRoot = projectRoot,
            entryFile = artifact,
            projectId = request.metadata[PROJECT_ID_METADATA_KEY]
                ?: request.metadata[APP_ID_METADATA_KEY],
            htmlTransformer = htmlTransformer
        )
    }

    private suspend fun readHtml(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "文件不存在或不是普通文件" }
            require(file.extension.equals("html", ignoreCase = true) ||
                file.extension.equals("htm", ignoreCase = true)) {
                "产物必须是 .html 或 .htm 文件"
            }
            file.readText()
        }
    }

    private fun WebInspectionReport.toHarnessCheckResult(
        artifactPath: String,
        prefixSummary: String? = null,
        extraDiagnostics: List<String> = emptyList()
    ): HarnessCheckResult {
        val failedTests = selfTests.count { !it.passed }
        val errorCount = diagnostics.count { it.severity == WebDiagnosticSeverity.ERROR }
        val warningCount = diagnostics.count { it.severity == WebDiagnosticSeverity.WARNING }
        val label = when (stage) {
            WebInspectionStage.BUILD -> "WebView 构建检查"
            WebInspectionStage.RUNTIME -> "WebView 运行检查"
            WebInspectionStage.SELF_TEST -> "WebView 自测"
            WebInspectionStage.TEST_SUITE -> "WebView 测试集"
        }
        val reportSummary = if (passed) {
            "${label}通过${selfTests.takeIf { it.isNotEmpty() }?.let { "（${it.size} 项）" }.orEmpty()}"
        } else {
            buildString {
                append(label).append("失败")
                if (errorCount > 0) append("：").append(errorCount).append(" 个错误")
                if (warningCount > 0) append("，").append(warningCount).append(" 个警告")
                if (failedTests > 0) append("，").append(failedTests).append(" 项测试未通过")
                if (timedOut) append("，检查超时")
            }
        }
        val summary = prefixSummary?.takeIf(String::isNotBlank)?.let { "$it；$reportSummary" }
            ?: reportSummary
        return HarnessCheckResult(
            passed = passed,
            summary = summary,
            diagnostics = (extraDiagnostics + diagnostics.map(::formatDiagnostic)).distinct(),
            artifactPath = artifactPath,
            durationMs = durationMillis
        )
    }

    private fun formatDiagnostic(diagnostic: WebInspectionDiagnostic): String {
        return buildString {
            append(diagnostic.severity.name)
            append(' ').append(diagnostic.code).append(": ").append(diagnostic.message)
            diagnostic.location?.let { location ->
                append(" [")
                append(location.source ?: "inline")
                location.line?.let { append(':').append(it) }
                location.column?.let { append(':').append(it) }
                append(']')
            }
            diagnostic.stackTrace?.takeIf { it.isNotBlank() }?.let { stack ->
                append('\n').append(stack)
            }
        }
    }

    private companion object {
        const val PROJECT_ID_METADATA_KEY = "projectId"
        const val APP_ID_METADATA_KEY = "appId"
    }
}
