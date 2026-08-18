package com.hfad.mantou.utils.harness

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

    data class StageStarted(override val stage: WebInspectionStage) : WebInspectionEvent

    data class PageLoading(
        override val stage: WebInspectionStage,
        val url: String?
    ) : WebInspectionEvent

    data class DiagnosticCaptured(
        override val stage: WebInspectionStage,
        val diagnostic: WebInspectionDiagnostic
    ) : WebInspectionEvent

    data class StageFinished(
        override val stage: WebInspectionStage,
        val report: WebInspectionReport
    ) : WebInspectionEvent
}
