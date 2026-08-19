package com.hfad.mantou.utils.harness

import java.security.MessageDigest

enum class WebInspectionStage {
    BUILD,
    RUNTIME,
    SELF_TEST,
    TEST_SUITE
}

enum class WebDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

enum class WebDiagnosticCategory {
    HTML,
    JAVASCRIPT,
    CONSOLE,
    RESOURCE,
    HTTP,
    SSL,
    RENDERER,
    SELF_TEST,
    TEST_SUITE,
    TIMEOUT
}

data class WebSourceLocation(
    val source: String? = null,
    val line: Int? = null,
    val column: Int? = null
)

data class WebInspectionDiagnostic(
    val stage: WebInspectionStage,
    val severity: WebDiagnosticSeverity,
    val category: WebDiagnosticCategory,
    val code: String,
    val message: String,
    val location: WebSourceLocation? = null,
    val stackTrace: String? = null
)

data class WebSelfTestCase(
    val name: String,
    val passed: Boolean,
    val message: String? = null,
    val details: String? = null
)

data class WebInspectionReport(
    val stage: WebInspectionStage,
    val diagnostics: List<WebInspectionDiagnostic>,
    val selfTests: List<WebSelfTestCase> = emptyList(),
    val durationMillis: Long,
    val timedOut: Boolean = false
) {
    val passed: Boolean
        get() = !timedOut &&
            diagnostics.none { it.severity == WebDiagnosticSeverity.ERROR } &&
            selfTests.all { it.passed }

    val buildDiagnostics: List<WebInspectionDiagnostic>
        get() = diagnostics.filter { it.stage == WebInspectionStage.BUILD }

    val runtimeDiagnostics: List<WebInspectionDiagnostic>
        get() = diagnostics.filter { it.stage == WebInspectionStage.RUNTIME }

    val selfTestDiagnostics: List<WebInspectionDiagnostic>
        get() = diagnostics.filter { it.stage == WebInspectionStage.SELF_TEST }

    val testSuiteDiagnostics: List<WebInspectionDiagnostic>
        get() = diagnostics.filter { it.stage == WebInspectionStage.TEST_SUITE }
}

sealed interface WebInspectionTarget {
    data class Html(
        val content: String,
        val baseUrl: String? = DEFAULT_BASE_URL,
        val historyUrl: String? = null
    ) : WebInspectionTarget

    data class Url(val url: String) : WebInspectionTarget

    companion object {
        const val DEFAULT_BASE_URL = "https://mantou.local/"
    }
}

sealed interface WebInspectionEvent {
    val stage: WebInspectionStage

    data class StageStarted(
        override val stage: WebInspectionStage,
        val timeoutMillis: Long? = null,
        val settleMillis: Long? = null
    ) : WebInspectionEvent

    data class StageCancelled(
        override val stage: WebInspectionStage,
        val durationMillis: Long,
        val diagnosticCode: String = "WEB_INSPECTION_CANCELLED"
    ) : WebInspectionEvent

    data class PageLoading(
        override val stage: WebInspectionStage,
        val url: String?,
        val urlCharacterCount: Int? = null,
        val urlSha256: String? = null
    ) : WebInspectionEvent

    data class PageFinished(
        override val stage: WebInspectionStage,
        val url: String?,
        val urlCharacterCount: Int?,
        val urlSha256: String?,
        val status: Status,
        val diagnosticCode: String? = null
    ) : WebInspectionEvent

    data class ProbeStarted(
        override val stage: WebInspectionStage,
        val probe: Probe,
        val inputCharacterCount: Int,
        val inputSha256: String
    ) : WebInspectionEvent

    data class ProbeResult(
        override val stage: WebInspectionStage,
        val probe: Probe,
        val status: Status,
        val resultCharacterCount: Int?,
        val resultSha256: String?,
        val decodedCharacterCount: Int?,
        val decodedSha256: String?,
        val decodeStatus: Status,
        val parseStatus: Status,
        val diagnosticCodes: List<String> = emptyList()
    ) : WebInspectionEvent

    data class SelfTestRoot(
        override val stage: WebInspectionStage,
        val status: Status,
        val caseCount: Int,
        val diagnosticCode: String? = null
    ) : WebInspectionEvent

    data class SelfTestCaseResult(
        override val stage: WebInspectionStage,
        val index: Int,
        val status: Status,
        val nameCharacterCount: Int,
        val nameSha256: String,
        val diagnosticCode: String? = null
    ) : WebInspectionEvent

    data class InterceptorResult(
        override val stage: WebInspectionStage,
        val url: String?,
        val urlCharacterCount: Int?,
        val urlSha256: String?,
        val status: Status,
        val responseStatusCode: Int? = null
    ) : WebInspectionEvent

    data class InterceptorFailure(
        override val stage: WebInspectionStage,
        val url: String?,
        val urlCharacterCount: Int?,
        val urlSha256: String?,
        val status: Status = Status.FAILED,
        val diagnosticCode: String = "WEB_INTERCEPTOR_FAILED"
    ) : WebInspectionEvent

    data class Decision(
        override val stage: WebInspectionStage,
        val status: Status,
        val timedOut: Boolean,
        val hasErrorDiagnostics: Boolean,
        val allSelfTestsPassed: Boolean,
        val diagnosticCount: Int,
        val errorDiagnosticCount: Int,
        val selfTestCount: Int,
        val failedSelfTestCount: Int,
        val durationMillis: Long,
        val diagnosticCodes: List<String>
    ) : WebInspectionEvent

    data class DiagnosticCaptured(
        override val stage: WebInspectionStage,
        val diagnostic: WebInspectionDiagnostic
    ) : WebInspectionEvent

    data class StageFinished(
        override val stage: WebInspectionStage,
        val report: WebInspectionReport
    ) : WebInspectionEvent

    enum class Probe {
        BUILD_INLINE_SCRIPTS,
        RUNTIME_HARNESS,
        RUNTIME_DIAGNOSTICS,
        SELF_TEST
    }

    enum class Status {
        SUCCEEDED,
        FAILED,
        IGNORED,
        INTERCEPTED,
        PASSTHROUGH,
        NOT_REQUIRED,
        NOT_ATTEMPTED
    }
}

internal const val WEB_INSPECTION_EVENT_URL_LIMIT = 256

internal data class WebInspectionTextSummary(
    val characterCount: Int,
    val sha256: String
)

internal data class WebInspectionUrlSummary(
    val value: String?,
    val characterCount: Int?,
    val sha256: String?
)

internal fun summarizeWebInspectionText(value: String): WebInspectionTextSummary {
    return WebInspectionTextSummary(
        characterCount = value.length,
        sha256 = webInspectionSha256(value)
    )
}

internal fun summarizeWebInspectionUrl(value: String?): WebInspectionUrlSummary {
    return WebInspectionUrlSummary(
        value = value?.take(WEB_INSPECTION_EVENT_URL_LIMIT),
        characterCount = value?.length,
        sha256 = value?.let(::webInspectionSha256)
    )
}

private fun webInspectionSha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
