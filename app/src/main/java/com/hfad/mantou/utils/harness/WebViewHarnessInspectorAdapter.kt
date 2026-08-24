package com.hfad.mantou.utils.harness

import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import com.hfad.mantou.data.logging.elapsedMillisSince
import com.hfad.mantou.data.logging.record
import com.hfad.mantou.utils.WebProjectContentServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class WebViewHarnessInspectorAdapter(
    private val inspector: GeneratedAppWebViewInspector,
    private val staticChecker: WebProjectStaticChecker = DefaultWebProjectStaticChecker,
    private val traceLogger: HarnessTraceLogger = HarnessTraceLogger {}
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
        val executeStartedAt = System.nanoTime()
        traceLogger.record(
            runId = request.runId,
            component = "CHECK_ADAPTER",
            operation = request.kind.name,
            status = HarnessTraceStatus.STARTED,
            message = "Web 项目检查适配器开始执行",
            iteration = request.iteration,
            details = buildMap {
                put("workspace", request.workspacePath)
                request.artifactPath?.let { put("artifact", it) }
            }
        )
        return try {
            val result = executeCheck(request, executeStartedAt)
            traceLogger.record(
                runId = request.runId,
                component = "CHECK_ADAPTER",
                operation = request.kind.name,
                status = if (result.passed) {
                    HarnessTraceStatus.SUCCEEDED
                } else {
                    HarnessTraceStatus.FAILED
                },
                message = "Web 项目检查适配器执行完成",
                iteration = request.iteration,
                durationMs = elapsedMillisSince(executeStartedAt),
                details = mapOf(
                    "passed" to result.passed.toString(),
                    "summary_chars" to result.summary.length.toString(),
                    "summary_sha256" to sha256(result.summary),
                    "diagnostic_count" to result.diagnostics.size.toString(),
                    "diagnostic_codes" to diagnosticCodes(result.diagnostics),
                    "artifact" to result.artifactPath.orEmpty()
                )
            )
            result
        } catch (error: CancellationException) {
            traceLogger.record(
                runId = request.runId,
                component = "CHECK_ADAPTER",
                operation = request.kind.name,
                status = HarnessTraceStatus.CANCELLED,
                message = "Web 项目检查适配器已取消",
                iteration = request.iteration,
                durationMs = elapsedMillisSince(executeStartedAt)
            )
            throw error
        } catch (error: Exception) {
            traceLogger.record(
                runId = request.runId,
                component = "CHECK_ADAPTER",
                operation = request.kind.name,
                status = HarnessTraceStatus.FAILED,
                message = "Web 项目检查适配器异常退出",
                iteration = request.iteration,
                durationMs = elapsedMillisSince(executeStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            throw error
        }
    }

    private suspend fun executeCheck(
        request: HarnessCheckRequest,
        executeStartedAt: Long
    ): HarnessCheckResult {
        val prepareStartedAt = System.nanoTime()
        val projectResult = withContext(Dispatchers.IO) {
            runCatching { createProjectServer(request) }
        }
        val project = projectResult.getOrElse { error ->
            traceLogger.record(
                runId = request.runId,
                component = "PROJECT_PREPARE",
                operation = request.kind.name,
                status = HarnessTraceStatus.FAILED,
                message = "Web 项目准备失败",
                iteration = request.iteration,
                durationMs = elapsedMillisSince(prepareStartedAt),
                details = mapOf(
                    "error_type" to error::class.java.simpleName,
                    "workspace" to request.workspacePath,
                    "artifact" to request.artifactPath.orEmpty()
                )
            )
            return HarnessCheckResult(
                passed = false,
                summary = "无法准备 Web 项目",
                diagnostics = listOf(
                    "PROJECT_PREPARE_FAILED: ${error.message ?: error::class.java.simpleName}"
                ),
                artifactPath = request.artifactPath
            )
        }
        traceLogger.record(
            runId = request.runId,
            component = "PROJECT_PREPARE",
            operation = request.kind.name,
            status = HarnessTraceStatus.SUCCEEDED,
            message = "Web 项目准备完成",
            iteration = request.iteration,
            durationMs = elapsedMillisSince(prepareStartedAt),
            details = mapOf(
                "project_root" to project.projectRoot.absolutePath,
                "entry" to project.entryRelativePath,
                "entry_url" to project.entryUrl,
                "revision" to project.revision,
                "file_count" to project.files.size.toString(),
                "project_bytes" to project.files.sumOf { it.sizeBytes }.toString()
            )
        )
        coroutineContext.ensureActive()

        if (request.kind == HarnessCheckKind.DEVELOPMENT_BUILD ||
            request.kind == HarnessCheckKind.FINAL_BUILD
        ) {
            val staticStartedAt = System.nanoTime()
            traceLogger.record(
                runId = request.runId,
                component = "STATIC_CHECK",
                operation = request.kind.name,
                status = HarnessTraceStatus.STARTED,
                message = "项目静态检查开始",
                iteration = request.iteration,
                details = mapOf("entry" to project.entryRelativePath)
            )
            val staticResult = runCatching { staticChecker.check(project) }.getOrElse { error ->
                traceLogger.record(
                    runId = request.runId,
                    component = "STATIC_CHECK",
                    operation = request.kind.name,
                    status = HarnessTraceStatus.FAILED,
                    message = "项目静态检查执行失败",
                    iteration = request.iteration,
                    durationMs = elapsedMillisSince(staticStartedAt),
                    details = mapOf("error_type" to error::class.java.simpleName)
                )
                return HarnessCheckResult(
                    passed = false,
                    summary = "项目静态检查执行失败",
                    diagnostics = listOf(
                        "PROJECT_STATIC_CHECK_FAILED: ${error.message ?: error::class.java.simpleName}"
                    ),
                    artifactPath = project.entryFile.absolutePath
                )
            }
            traceLogger.record(
                runId = request.runId,
                component = "STATIC_CHECK",
                operation = request.kind.name,
                status = if (staticResult.passed) {
                    HarnessTraceStatus.SUCCEEDED
                } else {
                    HarnessTraceStatus.FAILED
                },
                message = staticResult.summary,
                iteration = request.iteration,
                durationMs = elapsedMillisSince(staticStartedAt),
                details = mapOf(
                    "passed" to staticResult.passed.toString(),
                    "diagnostic_count" to staticResult.diagnostics.size.toString(),
                    "diagnostic_codes" to diagnosticCodes(staticResult.diagnostics)
                )
            )
            if (!staticResult.passed) {
                return HarnessCheckResult(
                    passed = false,
                    summary = staticResult.summary,
                    diagnostics = staticResult.diagnostics,
                    artifactPath = project.entryFile.absolutePath
                )
            }
            val readStartedAt = System.nanoTime()
            traceLogger.record(
                runId = request.runId,
                component = "ENTRY_FILE",
                operation = "read",
                status = HarnessTraceStatus.STARTED,
                message = "开始读取项目入口 HTML",
                iteration = request.iteration,
                details = mapOf("path" to project.entryFile.absolutePath)
            )
            val html = readHtml(project.entryFile).getOrElse { error ->
                traceLogger.record(
                    runId = request.runId,
                    component = "ENTRY_FILE",
                    operation = "read",
                    status = HarnessTraceStatus.FAILED,
                    message = "项目入口 HTML 读取失败",
                    iteration = request.iteration,
                    durationMs = elapsedMillisSince(readStartedAt),
                    details = mapOf(
                        "path" to project.entryFile.absolutePath,
                        "error_type" to error::class.java.simpleName
                    )
                )
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
            traceLogger.record(
                runId = request.runId,
                component = "ENTRY_FILE",
                operation = "read",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目入口 HTML 读取完成",
                iteration = request.iteration,
                durationMs = elapsedMillisSince(readStartedAt),
                details = mapOf(
                    "path" to project.entryFile.absolutePath,
                    "chars" to html.length.toString(),
                    "bytes" to html.toByteArray(Charsets.UTF_8).size.toString(),
                    "sha256" to sha256(html)
                )
            )
            val report = inspector.inspectBuild(html)
            val result = report.toHarnessCheckResult(
                artifactPath = project.entryFile.absolutePath,
                prefixSummary = staticResult.summary,
                extraDiagnostics = staticResult.diagnostics
            )
            recordDecision(request, report, result, executeStartedAt)
            return result
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

            HarnessCheckKind.TEST_SUITE -> inspector.runQualityGateSuite(
                target = target,
                qualityGateContract = request.qualityGateContract,
                acceptanceContract = request.acceptanceContract,
                acceptanceRequired = request.acceptanceRequired,
                evidenceRunId = "${request.runId}-iteration-${request.iteration}",
                requestInterceptor = requestInterceptor
            )

            HarnessCheckKind.DEVELOPMENT_BUILD,
            HarnessCheckKind.FINAL_BUILD -> error("Build checks returned before runtime inspection")
        }
        val result = report.toHarnessCheckResult(project.entryFile.absolutePath)
        recordDecision(request, report, result, executeStartedAt)
        return result
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
            htmlTransformer = htmlTransformer,
            runId = request.runId,
            iteration = request.iteration,
            traceLogger = traceLogger
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
            buildString {
                append(label).append("通过")
                selfTests.takeIf { it.isNotEmpty() }?.let { append("（${it.size} 项）") }
                visualEvidence.takeIf { it.isNotEmpty() }?.let {
                    append("，已采集 ${it.size} 份视觉证据")
                }
            }
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
            diagnostics = (
                extraDiagnostics +
                    diagnostics.map(::formatDiagnostic) +
                    visualEvidence.map(::formatVisualEvidence)
                ).distinct(),
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

    private fun formatVisualEvidence(evidence: WebVisualEvidence): String {
        return buildString {
            append("VISUAL_EVIDENCE ")
            append(evidence.viewportId).append('/').append(evidence.visualStateId)
            append(": css=").append(evidence.widthCssPixels).append('x')
                .append(evidence.heightCssPixels)
            append(", pixels=").append(evidence.screenshotPixelWidth ?: "missing").append('x')
                .append(evidence.screenshotPixelHeight ?: "missing")
            append(", sha256=").append(evidence.screenshotSha256 ?: "missing")
            append(", path=").append(evidence.screenshotArtifactPath ?: "missing")
        }
    }

    private fun recordDecision(
        request: HarnessCheckRequest,
        report: WebInspectionReport,
        result: HarnessCheckResult,
        executeStartedAt: Long
    ) {
        val errorCount = report.diagnostics.count { it.severity == WebDiagnosticSeverity.ERROR }
        val warningCount = report.diagnostics.count { it.severity == WebDiagnosticSeverity.WARNING }
        val failedCases = report.selfTests.count { !it.passed }
        traceLogger.record(
            runId = request.runId,
            component = "INSPECTION_DECISION",
            operation = request.kind.name,
            status = if (result.passed) HarnessTraceStatus.SUCCEEDED else HarnessTraceStatus.FAILED,
            message = result.summary,
            iteration = request.iteration,
            durationMs = elapsedMillisSince(executeStartedAt),
            details = mapOf(
                "stage" to report.stage.name,
                "timeout_ok" to (!report.timedOut).toString(),
                "error_free" to (errorCount == 0).toString(),
                "all_cases_pass" to report.selfTests.all { it.passed }.toString(),
                "timed_out" to report.timedOut.toString(),
                "error_count" to errorCount.toString(),
                "warning_count" to warningCount.toString(),
                "case_count" to report.selfTests.size.toString(),
                "case_passed" to (report.selfTests.size - failedCases).toString(),
                "case_failed" to failedCases.toString(),
                "visual_evidence_count" to report.visualEvidence.size.toString(),
                "visual_evidence_viewports" to report.visualEvidence
                    .map(WebVisualEvidence::viewportId)
                    .distinct()
                    .joinToString(","),
                "report_passed" to report.passed.toString(),
                "final_passed" to result.passed.toString(),
                "diagnostic_codes" to report.diagnostics
                    .map(WebInspectionDiagnostic::code)
                    .distinct()
                    .joinToString(",")
            )
        )
    }

    private fun diagnosticCodes(diagnostics: List<String>): String {
        return diagnostics.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.substringBefore(':').substringBefore(' ').take(80) }
            .distinct()
            .joinToString(",")
    }

    private fun sha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val PROJECT_ID_METADATA_KEY = "projectId"
        const val APP_ID_METADATA_KEY = "appId"
    }
}
