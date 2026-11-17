package com.hfad.mantou.generation

import android.util.Log
import android.webkit.WebView
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.data.logging.ApiLogRedactor
import com.hfad.mantou.data.logging.HarnessTraceEvent
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import com.hfad.mantou.data.logging.record
import com.hfad.mantou.utils.harness.WebInspectionEvent
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal object GenerationTrace {
    fun logHarnessEvent(
        sessionId: Long,
        runId: String,
        event: GenerateTaskState.HarnessEvent
    ) {
        val diagnosticCodes = event.diagnostics.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.substringBefore(':').substringBefore(' ').take(80) }
            .distinct()
            .joinToString(",")
        val logMessage = buildString {
            append("run_id=").append(runId)
            append(" session_id=").append(sessionId)
            append(" stage=").append(event.stage.name)
            append(" operation=").append(event.operation ?: "-")
            append(" outcome=").append(event.outcome.name)
            append(" iteration=").append(event.iteration)
            append(" trace_format=legacy_ui_event")
            append(" message_chars=").append(event.message.length)
            append(" message_sha256=").append(traceSha256(event.message))
            append(" diagnostic_count=").append(event.diagnostics.size)
            if (diagnosticCodes.isNotBlank()) {
                append(" diagnostic_codes=").append(diagnosticCodes)
            }
        }
        when (event.outcome) {
            GenerateTaskState.Outcome.FAILED -> Log.e(WEB_HARNESS_LOG_TAG, logMessage)
            GenerateTaskState.Outcome.RETRYING -> Log.w(WEB_HARNESS_LOG_TAG, logMessage)
            else -> Log.i(WEB_HARNESS_LOG_TAG, logMessage)
        }
    }

    fun createHarnessTraceLogger(
        sessionId: Long,
        expectedRunId: String
    ): HarnessTraceLogger {
        val sequence = AtomicLong()
        return HarnessTraceLogger { event ->
            logHarnessTraceEvent(
                sessionId = sessionId,
                expectedRunId = expectedRunId,
                sequence = sequence.incrementAndGet(),
                event = event
            )
        }
    }

    private fun logHarnessTraceEvent(
        sessionId: Long,
        expectedRunId: String,
        sequence: Long,
        event: HarnessTraceEvent
    ) {
        val details = buildMap {
            putAll(event.details)
            if (event.runId != expectedRunId) put("expected_run_id", expectedRunId)
        }.toSortedMap().entries.joinToString(",") { (key, value) ->
            "${sanitizeHarnessLogValue(key)}=${sanitizeHarnessLogValue(value)}"
        }.take(HARNESS_TRACE_DETAILS_CHARS)
        val logMessage = buildString {
            append("run_id=").append(sanitizeHarnessLogValue(event.runId))
            append(" seq=").append(sequence)
            append(" session_id=").append(sessionId)
            append(" component=").append(sanitizeHarnessLogValue(event.component))
            append(" operation=").append(sanitizeHarnessLogValue(event.operation))
            append(" status=").append(event.status.name)
            append(" iteration=").append(event.iteration ?: 0)
            append(" duration_ms=").append(event.durationMs ?: -1)
            append(" message=").append(sanitizeHarnessLogValue(event.message))
            if (details.isNotBlank()) append(" details={").append(details).append('}')
        }
        when (event.status) {
            HarnessTraceStatus.FAILED -> Log.e(WEB_HARNESS_LOG_TAG, logMessage)
            HarnessTraceStatus.CANCELLED -> Log.w(WEB_HARNESS_LOG_TAG, logMessage)
            else -> Log.i(WEB_HARNESS_LOG_TAG, logMessage)
        }
    }

    fun recordWebInspectionEvent(
        traceLogger: HarnessTraceLogger,
        runId: String,
        iteration: Int,
        event: WebInspectionEvent
    ) {
        when (event) {
            is WebInspectionEvent.StageStarted -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW",
                operation = "stage_started",
                status = HarnessTraceStatus.STARTED,
                message = "WebView 检查阶段开始",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    event.timeoutMillis?.let { put("timeout_ms", it.toString()) }
                    event.settleMillis?.let { put("settle_ms", it.toString()) }
                }
            )

            is WebInspectionEvent.StageCancelled -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW",
                operation = "stage_cancelled",
                status = HarnessTraceStatus.CANCELLED,
                message = "WebView 检查阶段已取消",
                iteration = iteration,
                durationMs = event.durationMillis,
                details = mapOf(
                    "stage" to event.stage.name,
                    "diagnostic_code" to event.diagnosticCode
                )
            )

            is WebInspectionEvent.PageLoading -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW",
                operation = "page_started",
                status = HarnessTraceStatus.PROGRESS,
                message = "WebView 页面开始加载",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "url_chars" to event.urlCharacterCount.toString(),
                    "url_sha256" to event.urlSha256.orEmpty()
                )
            )

            is WebInspectionEvent.PageFinished -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_PAGE",
                operation = "finished",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 页面加载回调完成",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("status", event.status.name)
                    put("url_chars", event.urlCharacterCount.toString())
                    put("url_sha256", event.urlSha256.orEmpty())
                    event.diagnosticCode?.let { put("diagnostic_code", it) }
                }
            )

            is WebInspectionEvent.ProbeStarted -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_PROBE",
                operation = event.probe.name,
                status = HarnessTraceStatus.STARTED,
                message = "WebView 探针开始执行",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "input_chars" to event.inputCharacterCount.toString(),
                    "input_sha256" to event.inputSha256
                )
            )

            is WebInspectionEvent.ProbeResult -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_PROBE",
                operation = event.probe.name,
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 探针执行结果",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "status" to event.status.name,
                    "result_chars" to event.resultCharacterCount.toString(),
                    "result_sha256" to event.resultSha256.orEmpty(),
                    "decoded_chars" to event.decodedCharacterCount.toString(),
                    "decoded_sha256" to event.decodedSha256.orEmpty(),
                    "decode_status" to event.decodeStatus.name,
                    "parse_status" to event.parseStatus.name,
                    "diagnostic_codes" to event.diagnosticCodes.joinToString(",")
                )
            )

            is WebInspectionEvent.SelfTestRoot -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_TEST",
                operation = "root_result",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 自测根结果",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("status", event.status.name)
                    put("case_count", event.caseCount.toString())
                    event.diagnosticCode?.let { put("diagnostic_code", it) }
                }
            )

            is WebInspectionEvent.SelfTestCaseResult -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_TEST",
                operation = "case_result",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 自测用例结果",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("index", event.index.toString())
                    put("status", event.status.name)
                    put("name_chars", event.nameCharacterCount.toString())
                    put("name_sha256", event.nameSha256)
                    event.diagnosticCode?.let { put("diagnostic_code", it) }
                }
            )

            is WebInspectionEvent.InterceptorResult -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_INTERCEPTOR",
                operation = "request",
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 请求拦截结果",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "status" to event.status.name,
                    "url_chars" to event.urlCharacterCount.toString(),
                    "url_sha256" to event.urlSha256.orEmpty(),
                    "response_status" to event.responseStatusCode.toString()
                )
            )

            is WebInspectionEvent.InterceptorFailure -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_INTERCEPTOR",
                operation = "request",
                status = HarnessTraceStatus.FAILED,
                message = "WebView 请求拦截执行失败",
                iteration = iteration,
                details = mapOf(
                    "stage" to event.stage.name,
                    "status" to event.status.name,
                    "url_chars" to event.urlCharacterCount.toString(),
                    "url_sha256" to event.urlSha256.orEmpty(),
                    "diagnostic_code" to event.diagnosticCode
                )
            )

            is WebInspectionEvent.Decision -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_DECISION",
                operation = event.stage.name,
                status = event.status.toHarnessTraceStatus(),
                message = "WebView 最终判定完成",
                iteration = iteration,
                durationMs = event.durationMillis,
                details = mapOf(
                    "timed_out" to event.timedOut.toString(),
                    "timeout_ok" to (!event.timedOut).toString(),
                    "has_error_diagnostics" to event.hasErrorDiagnostics.toString(),
                    "error_free" to (!event.hasErrorDiagnostics).toString(),
                    "all_self_tests_passed" to event.allSelfTestsPassed.toString(),
                    "diagnostic_count" to event.diagnosticCount.toString(),
                    "error_count" to event.errorDiagnosticCount.toString(),
                    "case_count" to event.selfTestCount.toString(),
                    "case_failed" to event.failedSelfTestCount.toString(),
                    "final_passed" to (event.status == WebInspectionEvent.Status.SUCCEEDED).toString(),
                    "diagnostic_codes" to event.diagnosticCodes.joinToString(",")
                )
            )

            is WebInspectionEvent.DiagnosticCaptured -> traceLogger.record(
                runId = runId,
                component = "WEBVIEW_DIAGNOSTIC",
                operation = "captured",
                status = if (
                    event.diagnostic.severity ==
                    com.hfad.mantou.utils.harness.WebDiagnosticSeverity.ERROR
                ) {
                    HarnessTraceStatus.FAILED
                } else {
                    HarnessTraceStatus.PROGRESS
                },
                message = "WebView 诊断已捕获",
                iteration = iteration,
                details = buildMap {
                    put("stage", event.stage.name)
                    put("severity", event.diagnostic.severity.name)
                    put("category", event.diagnostic.category.name)
                    put("code", event.diagnostic.code)
                    event.diagnostic.location?.source?.let { source ->
                        put("source_chars", source.length.toString())
                        put("source_sha256", traceSha256(source))
                    }
                    event.diagnostic.location?.line?.let { put("line", it.toString()) }
                    event.diagnostic.location?.column?.let { put("column", it.toString()) }
                }
            )

            is WebInspectionEvent.StageFinished -> {
                val errorCount = event.report.diagnostics.count {
                    it.severity == com.hfad.mantou.utils.harness.WebDiagnosticSeverity.ERROR
                }
                val failedCases = event.report.selfTests.count { !it.passed }
                traceLogger.record(
                    runId = runId,
                    component = "WEBVIEW",
                    operation = "stage_finished",
                    status = if (event.report.passed) {
                        HarnessTraceStatus.SUCCEEDED
                    } else {
                        HarnessTraceStatus.FAILED
                    },
                    message = "WebView 检查阶段完成",
                    iteration = iteration,
                    durationMs = event.report.durationMillis,
                    details = mapOf(
                        "stage" to event.stage.name,
                        "timed_out" to event.report.timedOut.toString(),
                        "error_count" to errorCount.toString(),
                        "diagnostic_count" to event.report.diagnostics.size.toString(),
                        "case_count" to event.report.selfTests.size.toString(),
                        "case_failed" to failedCases.toString(),
                        "final_passed" to event.report.passed.toString()
                    )
                )
            }
        }
    }

    private fun WebInspectionEvent.Status.toHarnessTraceStatus(): HarnessTraceStatus {
        return when (this) {
            WebInspectionEvent.Status.SUCCEEDED,
            WebInspectionEvent.Status.INTERCEPTED -> HarnessTraceStatus.SUCCEEDED

            WebInspectionEvent.Status.FAILED -> HarnessTraceStatus.FAILED
            WebInspectionEvent.Status.IGNORED,
            WebInspectionEvent.Status.PASSTHROUGH,
            WebInspectionEvent.Status.NOT_REQUIRED,
            WebInspectionEvent.Status.NOT_ATTEMPTED -> HarnessTraceStatus.PROGRESS
        }
    }

    private fun sanitizeHarnessLogValue(value: String): String {
        return ApiLogRedactor.redactBody(value)
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(HARNESS_TRACE_VALUE_CHARS)
    }

    fun traceSha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

}

private const val WEB_HARNESS_LOG_TAG = "ManTouHarness"
private const val HARNESS_TRACE_VALUE_CHARS = 1_000
private const val HARNESS_TRACE_DETAILS_CHARS = 8_000
