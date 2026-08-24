package com.hfad.mantou.utils.harness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.hfad.mantou.utils.project.WebAppAcceptanceContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

fun interface WebRequestInterceptor {
    fun intercept(request: WebResourceRequest): WebResourceResponse?
}

class GeneratedAppWebViewInspector(
    private val webView: WebView,
    private val prepareWebView: (WebView) -> Unit = {},
    private val onEvent: (WebInspectionEvent) -> Unit = {}
) {
    private val inspectionMutex = Mutex()
    private var activeSession: InspectionSession? = null

    suspend fun inspectBuild(
        html: String,
        timeoutMillis: Long = DEFAULT_BUILD_TIMEOUT_MILLIS
    ): WebInspectionReport = inspectionMutex.withLock {
        val initialDiagnostics = WebInspectionParsers.preflightBuildDiagnostics(html).toMutableList()
        WebInspectionParsers.externalScriptSources(html).forEach { source ->
            initialDiagnostics += WebInspectionDiagnostic(
                stage = WebInspectionStage.BUILD,
                severity = WebDiagnosticSeverity.WARNING,
                category = WebDiagnosticCategory.JAVASCRIPT,
                code = "JS_EXTERNAL_NOT_COMPILED",
                message = "构建检查未离线编译外部脚本",
                location = WebSourceLocation(source = source)
            )
        }
        inspectOnMain(
            stage = WebInspectionStage.BUILD,
            mode = InspectionMode.Build(html),
            timeoutMillis = timeoutMillis,
            initialDiagnostics = initialDiagnostics
        )
    }

    suspend fun inspectRuntime(
        target: WebInspectionTarget,
        settleMillis: Long = DEFAULT_SETTLE_MILLIS,
        timeoutMillis: Long = DEFAULT_RUNTIME_TIMEOUT_MILLIS,
        requestInterceptor: WebRequestInterceptor? = null
    ): WebInspectionReport = inspectionMutex.withLock {
        inspectOnMain(
            stage = WebInspectionStage.RUNTIME,
            mode = InspectionMode.Runtime(
                target = target,
                settleMillis = settleMillis.coerceAtLeast(0),
                requestInterceptor = requestInterceptor
            ),
            timeoutMillis = timeoutMillis
        )
    }

    suspend fun runSelfTests(
        target: WebInspectionTarget,
        testScript: String,
        settleMillis: Long = DEFAULT_SETTLE_MILLIS,
        timeoutMillis: Long = DEFAULT_TEST_TIMEOUT_MILLIS,
        requestInterceptor: WebRequestInterceptor? = null
    ): WebInspectionReport = runTests(
        stage = WebInspectionStage.SELF_TEST,
        target = target,
        testScript = testScript,
        settleMillis = settleMillis,
        timeoutMillis = timeoutMillis,
        requestInterceptor = requestInterceptor
    )

    suspend fun runTestSuite(
        target: WebInspectionTarget,
        testScript: String,
        settleMillis: Long = DEFAULT_SETTLE_MILLIS,
        timeoutMillis: Long = DEFAULT_TEST_TIMEOUT_MILLIS,
        requestInterceptor: WebRequestInterceptor? = null
    ): WebInspectionReport = runTests(
        stage = WebInspectionStage.TEST_SUITE,
        target = target,
        testScript = testScript,
        settleMillis = settleMillis,
        timeoutMillis = timeoutMillis,
        requestInterceptor = requestInterceptor
    )

    suspend fun runQualityGateSuite(
        target: WebInspectionTarget,
        qualityGateContract: WebQualityGateContract,
        acceptanceContract: WebAppAcceptanceContract?,
        acceptanceRequired: Boolean,
        evidenceRunId: String,
        settleMillis: Long = DEFAULT_SETTLE_MILLIS,
        timeoutMillis: Long = DEFAULT_TEST_TIMEOUT_MILLIS,
        requestInterceptor: WebRequestInterceptor? = null,
        evidenceDirectory: File? = null
    ): WebInspectionReport = inspectionMutex.withLock {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        val startedAt = SystemClock.elapsedRealtime()
        val reports = mutableListOf<WebInspectionReport>()
        val visualEvidence = mutableListOf<WebVisualEvidence>()
        val additionalDiagnostics = mutableListOf<WebInspectionDiagnostic>()
        val initialState = qualityGateContract.requiredVisualStates.singleOrNull {
            it.id == INITIAL_VISUAL_STATE_ID
        }
        qualityGateContract.requiredVisualStates
            .filterNot { it.id == INITIAL_VISUAL_STATE_ID }
            .forEach { state ->
                additionalDiagnostics += testSuiteDiagnostic(
                    code = "VISUAL_STATE_UNSUPPORTED",
                    message = "无法自动驱动视觉状态 ${state.id}: ${state.description}"
                )
            }
        if (initialState == null) {
            additionalDiagnostics += testSuiteDiagnostic(
                code = "INITIAL_VISUAL_STATE_MISSING",
                message = "质量门禁必须声明 initial 视觉状态，且该状态会在验收动作前采集"
            )
        }
        val acceptanceScripts = when (acceptanceContract) {
            null -> {
                if (!acceptanceRequired) {
                    additionalDiagnostics += testSuiteDiagnostic(
                        code = "LEGACY_ACCEPTANCE_NOT_REQUIRED",
                        message = "legacy 项目未声明 typed AcceptanceContract，仅执行质量与视觉门禁",
                        severity = WebDiagnosticSeverity.WARNING
                    )
                    emptyList()
                } else {
                    additionalDiagnostics += testSuiteDiagnostic(
                        code = "ACCEPTANCE_CONTRACT_MISSING",
                        message = "TEST_SUITE 要求 typed AcceptanceContract，但当前合同缺失"
                    )
                    emptyList()
                }
            }
            else -> runCatching {
                GeneratedAppHarnessScripts.acceptanceSuite(acceptanceContract)
                acceptanceContract.criteria.map { criterion ->
                    criterion.id to GeneratedAppHarnessScripts.acceptanceSuite(
                        WebAppAcceptanceContract(criteria = listOf(criterion))
                    )
                }
            }.getOrElse { error ->
                additionalDiagnostics += testSuiteDiagnostic(
                    code = "ACCEPTANCE_CONTRACT_INVALID",
                    message = error.message ?: "typed AcceptanceContract 无效",
                    stackTrace = error.stackTraceToString()
                )
                emptyList()
            }
        }
        val outputDirectory = evidenceDirectory ?: defaultEvidenceDirectory(evidenceRunId)
        val layoutSnapshot = withContext(Dispatchers.Main.immediate) { captureLayoutSnapshot() }

        try {
            for ((viewportIndex, viewport) in qualityGateContract.requiredViewports.withIndex()) {
                val qualityReport = inspectOnMain(
                    stage = WebInspectionStage.TEST_SUITE,
                    mode = InspectionMode.Tests(
                        target = target,
                        testScript = GeneratedAppHarnessScripts.qualityGateSuite(
                            contract = qualityGateContract,
                            expectedViewport = viewport
                        ),
                        settleMillis = settleMillis.coerceAtLeast(0),
                        requestInterceptor = requestInterceptor,
                        expectedViewport = viewport
                    ),
                    timeoutMillis = timeoutMillis
                ).scopedToViewport(viewport.id, QUALITY_PHASE_ID)
                reports += qualityReport

                if (initialState != null) {
                    val dimensions = readCssViewportDimensions()
                    if (dimensions == null) {
                        additionalDiagnostics += testSuiteDiagnostic(
                            code = "VIEWPORT_DIMENSIONS_UNAVAILABLE",
                            message = "无法读取 ${viewport.id} 的实际 CSS viewport"
                        )
                    } else {
                        val screenshotFile = File(
                            outputDirectory,
                            screenshotFileName(viewportIndex, viewport.id, initialState.id)
                        )
                        val screenshotResult = capturePngScreenshot(screenshotFile)
                        val screenshot = screenshotResult.getOrNull()
                        if (screenshot == null) {
                            val error = screenshotResult.exceptionOrNull()
                            additionalDiagnostics += testSuiteDiagnostic(
                                code = "SCREENSHOT_CAPTURE_FAILED",
                                message = "${viewport.id}/${initialState.id} 截图失败: " +
                                    (error?.message ?: "未知错误"),
                                stackTrace = error?.stackTraceToString(),
                                severity = if (qualityGateContract.requireScreenshotEvidence) {
                                    WebDiagnosticSeverity.ERROR
                                } else {
                                    WebDiagnosticSeverity.WARNING
                                }
                            )
                        }
                        visualEvidence += WebVisualEvidence(
                            viewportId = viewport.id,
                            visualStateId = initialState.id,
                            widthCssPixels = dimensions.widthCssPixels,
                            heightCssPixels = dimensions.heightCssPixels,
                            screenshotArtifactPath = screenshot?.file?.absolutePath,
                            screenshotPixelWidth = screenshot?.pixelWidth,
                            screenshotPixelHeight = screenshot?.pixelHeight,
                            screenshotSha256 = screenshot?.sha256
                        )
                    }
                }

                for ((criterionId, acceptanceScript) in acceptanceScripts) {
                    reports += inspectOnMain(
                        stage = WebInspectionStage.TEST_SUITE,
                        mode = InspectionMode.Tests(
                            target = target,
                            testScript = acceptanceScript,
                            settleMillis = settleMillis.coerceAtLeast(0),
                            requestInterceptor = requestInterceptor,
                            expectedViewport = viewport
                        ),
                        timeoutMillis = timeoutMillis
                    ).scopedToViewport(
                        viewportId = viewport.id,
                        phaseId = "$ACCEPTANCE_PHASE_ID:$criterionId"
                    )
                }
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { restoreLayout(layoutSnapshot) }
        }

        val coverage = withContext(Dispatchers.IO) {
            WebQualityGateEvaluator.evaluateEvidence(
                contract = qualityGateContract,
                evidence = visualEvidence
            )
        }
        coverage.diagnostics.forEach { message ->
            additionalDiagnostics += testSuiteDiagnostic(
                code = "VISUAL_EVIDENCE_GATE_FAILED",
                message = message
            )
        }
        mergeWebInspectionReports(
            stage = WebInspectionStage.TEST_SUITE,
            reports = reports,
            visualEvidence = visualEvidence,
            additionalDiagnostics = additionalDiagnostics,
            durationMillis = SystemClock.elapsedRealtime() - startedAt
        )
    }

    fun cancelCurrentInspection() {
        val cancel: () -> Unit = {
            activeSession?.cancel(CancellationException("WebView inspection cancelled"))
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancel()
        } else {
            webView.post { cancel() }
        }
    }

    private suspend fun runTests(
        stage: WebInspectionStage,
        target: WebInspectionTarget,
        testScript: String,
        settleMillis: Long,
        timeoutMillis: Long,
        requestInterceptor: WebRequestInterceptor?
    ): WebInspectionReport = inspectionMutex.withLock {
        inspectOnMain(
            stage = stage,
            mode = InspectionMode.Tests(
                target = target,
                testScript = testScript,
                settleMillis = settleMillis.coerceAtLeast(0),
                requestInterceptor = requestInterceptor
            ),
            timeoutMillis = timeoutMillis
        )
    }

    private suspend fun inspectOnMain(
        stage: WebInspectionStage,
        mode: InspectionMode,
        timeoutMillis: Long,
        initialDiagnostics: List<WebInspectionDiagnostic> = emptyList()
    ): WebInspectionReport = withContext(Dispatchers.Main.immediate) {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        emit(
            WebInspectionEvent.StageStarted(
                stage = stage,
                timeoutMillis = timeoutMillis,
                settleMillis = when (mode) {
                    is InspectionMode.Build -> null
                    is InspectionMode.Runtime -> mode.settleMillis
                    is InspectionMode.Tests -> mode.settleMillis
                }
            )
        )
        initialDiagnostics.forEach { diagnostic ->
            emit(WebInspectionEvent.DiagnosticCaptured(stage, diagnostic))
        }
        suspendCancellableCoroutine { continuation ->
            val session = InspectionSession(
                stage = stage,
                mode = mode,
                timeoutMillis = timeoutMillis,
                initialDiagnostics = initialDiagnostics,
                onComplete = { report ->
                    activeSession = null
                    emitDecision(report)
                    emit(WebInspectionEvent.StageFinished(stage, report))
                    if (continuation.isActive) continuation.resume(report)
                }
            )
            activeSession = session
            session.cancelContinuation = { reason -> continuation.cancel(reason) }
            continuation.invokeOnCancellation {
                webView.post {
                    if (activeSession === session) {
                        session.emitCancelled()
                        session.dispose(stopLoading = true)
                        activeSession = null
                    }
                }
            }
            session.start()
        }
    }

    private fun emit(event: WebInspectionEvent) {
        runCatching { onEvent(event) }
    }

    private fun emitDecision(report: WebInspectionReport) {
        val errorDiagnosticCount = report.diagnostics.count {
            it.severity == WebDiagnosticSeverity.ERROR
        }
        val failedSelfTestCount = report.selfTests.count { !it.passed }
        emit(
            WebInspectionEvent.Decision(
                stage = report.stage,
                status = if (report.passed) {
                    WebInspectionEvent.Status.SUCCEEDED
                } else {
                    WebInspectionEvent.Status.FAILED
                },
                timedOut = report.timedOut,
                hasErrorDiagnostics = errorDiagnosticCount > 0,
                allSelfTestsPassed = failedSelfTestCount == 0,
                diagnosticCount = report.diagnostics.size,
                errorDiagnosticCount = errorDiagnosticCount,
                selfTestCount = report.selfTests.size,
                failedSelfTestCount = failedSelfTestCount,
                durationMillis = report.durationMillis,
                diagnosticCodes = report.diagnostics.map { it.code }
            )
        )
    }

    private inner class InspectionSession(
        private val stage: WebInspectionStage,
        private val mode: InspectionMode,
        private val timeoutMillis: Long,
        initialDiagnostics: List<WebInspectionDiagnostic>,
        private val onComplete: (WebInspectionReport) -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val diagnostics = initialDiagnostics.toMutableList()
        private val diagnosticKeys = initialDiagnostics.mapTo(mutableSetOf()) { diagnosticKey(it) }
        private val startedAt = SystemClock.elapsedRealtime()
        private val previousChromeClient = webView.webChromeClient
        private val previousViewClient = webView.webViewClient
        private var selfTests: List<WebSelfTestCase> = emptyList()
        private var pageStarted = false
        private var pageHandled = false
        private var finished = false
        private var cancellationEventEmitted = false
        private val selfTestResultChannelPrefix = (mode as? InspectionMode.Tests)?.let {
            "$SELF_TEST_RESULT_PREFIX${UUID.randomUUID()}:"
        }
        private var acceptingSelfTestResult = false
        var cancelContinuation: ((CancellationException) -> Unit)? = null
        private val timeoutRunnable = Runnable {
            addDiagnostic(
                severity = WebDiagnosticSeverity.ERROR,
                category = WebDiagnosticCategory.TIMEOUT,
                code = "WEB_INSPECTION_TIMEOUT",
                message = "WebView ${stage.name.lowercase()} 检查在 ${timeoutMillis}ms 内未完成"
            )
            finish(timedOut = true)
        }

        private val chromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message().orEmpty()
                val resultPrefix = selfTestResultChannelPrefix
                if (acceptingSelfTestResult &&
                    resultPrefix != null &&
                    message.startsWith(resultPrefix)
                ) {
                    acceptingSelfTestResult = false
                    handleSelfTestPayload(message.removePrefix(resultPrefix))
                    return true
                }
                WebInspectionParsers.parseConsoleDiagnostic(
                    stage = stage,
                    message = message,
                    level = consoleMessage.messageLevel().name,
                    source = consoleMessage.sourceId(),
                    line = consoleMessage.lineNumber()
                )?.let(::addDiagnostic)
                return true
            }
        }

        private val viewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val interceptor = when (val currentMode = mode) {
                    is InspectionMode.Build -> null
                    is InspectionMode.Runtime -> currentMode.requestInterceptor
                    is InspectionMode.Tests -> currentMode.requestInterceptor
                } ?: return super.shouldInterceptRequest(view, request)
                return runCatching { interceptor.intercept(request) }
                    .onSuccess { response ->
                        emitInterceptorResult(request, response)
                    }
                    .onFailure {
                        emitInterceptorFailure(request)
                    }
                    .getOrElse(::interceptorFailureResponse)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageStarted = true
                val urlSummary = summarizeWebInspectionUrl(url)
                emit(
                    WebInspectionEvent.PageLoading(
                        stage = stage,
                        url = urlSummary.value,
                        urlCharacterCount = urlSummary.characterCount,
                        urlSha256 = urlSummary.sha256
                    )
                )
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val ignoredDiagnosticCode = when {
                    finished -> "PAGE_FINISH_IGNORED_SESSION_FINISHED"
                    pageHandled -> "PAGE_FINISH_IGNORED_ALREADY_HANDLED"
                    !pageStarted -> "PAGE_FINISH_IGNORED_NOT_STARTED"
                    else -> null
                }
                if (ignoredDiagnosticCode != null) {
                    emitPageFinished(
                        url = url,
                        status = WebInspectionEvent.Status.IGNORED,
                        diagnosticCode = ignoredDiagnosticCode
                    )
                    return
                }
                pageHandled = true
                emitPageFinished(
                    url = url,
                    status = WebInspectionEvent.Status.SUCCEEDED
                )
                when (val currentMode = mode) {
                    is InspectionMode.Build -> inspectInlineScripts(currentMode.html)
                    is InspectionMode.Runtime -> ensureRuntimeHarness {
                        handler.postDelayed(
                            { collectRuntimeErrors() },
                            currentMode.settleMillis
                        )
                    }
                    is InspectionMode.Tests -> ensureRuntimeHarness {
                        handler.postDelayed(
                            { executeSelfTests(currentMode.testScript) },
                            currentMode.settleMillis
                        )
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                addDiagnostic(
                    severity = WebDiagnosticSeverity.ERROR,
                    category = WebDiagnosticCategory.RESOURCE,
                    code = "WEB_RESOURCE_${error.errorCode}",
                    message = error.description?.toString().orEmpty().ifBlank { "资源加载失败" },
                    location = WebSourceLocation(source = request.url?.toString())
                )
                if (request.isForMainFrame) finish()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                addDiagnostic(
                    severity = WebDiagnosticSeverity.ERROR,
                    category = WebDiagnosticCategory.HTTP,
                    code = "HTTP_${errorResponse.statusCode}",
                    message = "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase.orEmpty()}".trim(),
                    location = WebSourceLocation(source = request.url?.toString())
                )
                if (request.isForMainFrame) finish()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
                addDiagnostic(
                    severity = WebDiagnosticSeverity.ERROR,
                    category = WebDiagnosticCategory.SSL,
                    code = "SSL_${error.primaryError}",
                    message = "SSL 校验失败 (${error.primaryError})",
                    location = WebSourceLocation(source = error.url)
                )
                if (error.url == view.url) finish()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                addDiagnostic(
                    severity = WebDiagnosticSeverity.ERROR,
                    category = WebDiagnosticCategory.RENDERER,
                    code = "WEB_RENDERER_GONE",
                    message = if (detail.didCrash()) "WebView 渲染进程崩溃" else "WebView 渲染进程被系统终止"
                )
                finish()
                return true
            }
        }

        fun start() {
            runCatching {
                prepareWebView(webView)
                webView.stopLoading()
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                (mode as? InspectionMode.Tests)?.expectedViewport?.let(::applyViewport)
                webView.webChromeClient = chromeClient
                webView.webViewClient = viewClient
                handler.postDelayed(timeoutRunnable, timeoutMillis)
                when (val currentMode = mode) {
                    is InspectionMode.Build -> webView.loadDataWithBaseURL(
                        WebInspectionTarget.DEFAULT_BASE_URL,
                        BUILD_INSPECTOR_PAGE,
                        "text/html",
                        "UTF-8",
                        null
                    )
                    is InspectionMode.Runtime -> loadTarget(currentMode.target)
                    is InspectionMode.Tests -> loadTarget(currentMode.target)
                }
            }.onFailure { error ->
                addDiagnostic(
                    severity = WebDiagnosticSeverity.ERROR,
                    category = WebDiagnosticCategory.RENDERER,
                    code = "WEBVIEW_START_FAILED",
                    message = error.message ?: error::class.java.simpleName,
                    stackTrace = error.stackTraceToString()
                )
                finish()
            }
        }

        fun cancel(reason: CancellationException) {
            if (finished) return
            emitCancelled()
            dispose(stopLoading = true)
            activeSession = null
            cancelContinuation?.invoke(reason)
        }

        fun emitCancelled() {
            if (cancellationEventEmitted) return
            cancellationEventEmitted = true
            emit(
                WebInspectionEvent.StageCancelled(
                    stage = stage,
                    durationMillis = SystemClock.elapsedRealtime() - startedAt
                )
            )
        }

        fun dispose(stopLoading: Boolean) {
            if (finished) return
            finished = true
            acceptingSelfTestResult = false
            handler.removeCallbacksAndMessages(null)
            if (stopLoading) runCatching { webView.stopLoading() }
            restoreClients()
        }

        private fun loadTarget(target: WebInspectionTarget) {
            when (target) {
                is WebInspectionTarget.Html -> webView.loadDataWithBaseURL(
                    target.baseUrl,
                    WebInspectionParsers.injectRuntimeHarness(target.content, RUNTIME_HARNESS_SCRIPT),
                    "text/html",
                    "UTF-8",
                    target.historyUrl
                )
                is WebInspectionTarget.Url -> webView.loadUrl(target.url)
            }
        }

        private fun emitPageFinished(
            url: String?,
            status: WebInspectionEvent.Status,
            diagnosticCode: String? = null
        ) {
            val urlSummary = summarizeWebInspectionUrl(url)
            emit(
                WebInspectionEvent.PageFinished(
                    stage = stage,
                    url = urlSummary.value,
                    urlCharacterCount = urlSummary.characterCount,
                    urlSha256 = urlSummary.sha256,
                    status = status,
                    diagnosticCode = diagnosticCode
                )
            )
        }

        private fun emitInterceptorResult(
            request: WebResourceRequest,
            response: WebResourceResponse?
        ) {
            val urlSummary = summarizeWebInspectionUrl(request.url?.toString())
            val responseStatusCode = response?.let {
                runCatching { it.statusCode }.getOrNull()
            }
            emit(
                WebInspectionEvent.InterceptorResult(
                    stage = stage,
                    url = urlSummary.value,
                    urlCharacterCount = urlSummary.characterCount,
                    urlSha256 = urlSummary.sha256,
                    status = if (response == null) {
                        WebInspectionEvent.Status.PASSTHROUGH
                    } else {
                        WebInspectionEvent.Status.INTERCEPTED
                    },
                    responseStatusCode = responseStatusCode
                )
            )
        }

        private fun emitInterceptorFailure(request: WebResourceRequest) {
            val urlSummary = summarizeWebInspectionUrl(request.url?.toString())
            emit(
                WebInspectionEvent.InterceptorFailure(
                    stage = stage,
                    url = urlSummary.value,
                    urlCharacterCount = urlSummary.characterCount,
                    urlSha256 = urlSummary.sha256
                )
            )
        }

        private fun emitProbeStarted(
            probe: WebInspectionEvent.Probe,
            input: String
        ) {
            val inputSummary = summarizeWebInspectionText(input)
            emit(
                WebInspectionEvent.ProbeStarted(
                    stage = stage,
                    probe = probe,
                    inputCharacterCount = inputSummary.characterCount,
                    inputSha256 = inputSummary.sha256
                )
            )
        }

        private fun emitProbeResult(
            probe: WebInspectionEvent.Probe,
            status: WebInspectionEvent.Status,
            result: String?,
            decoded: String?,
            decodeStatus: WebInspectionEvent.Status,
            parseStatus: WebInspectionEvent.Status,
            diagnosticCodes: List<String> = emptyList()
        ) {
            val resultSummary = result?.let(::summarizeWebInspectionText)
            val decodedSummary = decoded?.let(::summarizeWebInspectionText)
            emit(
                WebInspectionEvent.ProbeResult(
                    stage = stage,
                    probe = probe,
                    status = status,
                    resultCharacterCount = resultSummary?.characterCount,
                    resultSha256 = resultSummary?.sha256,
                    decodedCharacterCount = decodedSummary?.characterCount,
                    decodedSha256 = decodedSummary?.sha256,
                    decodeStatus = decodeStatus,
                    parseStatus = parseStatus,
                    diagnosticCodes = diagnosticCodes
                )
            )
        }

        private fun interceptorFailureResponse(error: Throwable): WebResourceResponse {
            val message = (error.message ?: error::class.java.simpleName)
                .take(1_000)
                .toByteArray(Charsets.UTF_8)
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                500,
                "Internal Server Error",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(message)
            )
        }

        private fun inspectInlineScripts(html: String) {
            val probeScript = buildProbeScript(html)
            emitProbeStarted(WebInspectionEvent.Probe.BUILD_INLINE_SCRIPTS, probeScript)
            webView.evaluateJavascript(probeScript) { value ->
                if (finished) return@evaluateJavascript
                val payload = WebInspectionParsers.decodeJavascriptString(value)
                val parsed = payload?.let {
                    WebInspectionParsers.parseDiagnosticPayload(
                        stage = stage,
                        payload = it,
                        defaultCategory = WebDiagnosticCategory.JAVASCRIPT
                    )
                }
                emitProbeResult(
                    probe = WebInspectionEvent.Probe.BUILD_INLINE_SCRIPTS,
                    status = if (parsed == null) {
                        WebInspectionEvent.Status.FAILED
                    } else {
                        WebInspectionEvent.Status.SUCCEEDED
                    },
                    result = value,
                    decoded = payload,
                    decodeStatus = if (payload == null) {
                        WebInspectionEvent.Status.FAILED
                    } else {
                        WebInspectionEvent.Status.SUCCEEDED
                    },
                    parseStatus = when {
                        payload == null -> WebInspectionEvent.Status.NOT_ATTEMPTED
                        parsed == null -> WebInspectionEvent.Status.FAILED
                        else -> WebInspectionEvent.Status.SUCCEEDED
                    },
                    diagnosticCodes = parsed?.map { it.code }
                        ?: listOf("BUILD_PROBE_INVALID_RESULT")
                )
                if (parsed == null) {
                    addDiagnostic(
                        severity = WebDiagnosticSeverity.ERROR,
                        category = WebDiagnosticCategory.JAVASCRIPT,
                        code = "BUILD_PROBE_INVALID_RESULT",
                        message = "无法解析 WebView 构建检查结果"
                    )
                } else {
                    parsed.forEach(::addDiagnostic)
                }
                finish()
            }
        }

        private fun ensureRuntimeHarness(block: () -> Unit) {
            if (finished) return
            emitProbeStarted(WebInspectionEvent.Probe.RUNTIME_HARNESS, RUNTIME_HARNESS_SCRIPT)
            webView.evaluateJavascript(RUNTIME_HARNESS_SCRIPT) { value ->
                if (finished) return@evaluateJavascript
                emitProbeResult(
                    probe = WebInspectionEvent.Probe.RUNTIME_HARNESS,
                    status = WebInspectionEvent.Status.SUCCEEDED,
                    result = value,
                    decoded = null,
                    decodeStatus = WebInspectionEvent.Status.NOT_REQUIRED,
                    parseStatus = WebInspectionEvent.Status.NOT_REQUIRED
                )
                block()
            }
        }

        private fun collectRuntimeErrors() {
            if (finished) return
            emitProbeStarted(WebInspectionEvent.Probe.RUNTIME_DIAGNOSTICS, RUNTIME_COLLECT_SCRIPT)
            webView.evaluateJavascript(RUNTIME_COLLECT_SCRIPT) { value ->
                if (finished) return@evaluateJavascript
                val payload = WebInspectionParsers.decodeJavascriptString(value)
                val parsed = payload?.let {
                    WebInspectionParsers.parseDiagnosticPayload(
                        stage = stage,
                        payload = it,
                        defaultCategory = WebDiagnosticCategory.JAVASCRIPT
                    )
                }
                emitProbeResult(
                    probe = WebInspectionEvent.Probe.RUNTIME_DIAGNOSTICS,
                    status = if (parsed == null) {
                        WebInspectionEvent.Status.FAILED
                    } else {
                        WebInspectionEvent.Status.SUCCEEDED
                    },
                    result = value,
                    decoded = payload,
                    decodeStatus = if (payload == null) {
                        WebInspectionEvent.Status.FAILED
                    } else {
                        WebInspectionEvent.Status.SUCCEEDED
                    },
                    parseStatus = when {
                        payload == null -> WebInspectionEvent.Status.NOT_ATTEMPTED
                        parsed == null -> WebInspectionEvent.Status.FAILED
                        else -> WebInspectionEvent.Status.SUCCEEDED
                    },
                    diagnosticCodes = parsed?.map { it.code }
                        ?: listOf("RUNTIME_PROBE_INVALID_RESULT")
                )
                if (parsed == null) {
                    addDiagnostic(
                        severity = WebDiagnosticSeverity.ERROR,
                        category = WebDiagnosticCategory.JAVASCRIPT,
                        code = "RUNTIME_PROBE_INVALID_RESULT",
                        message = "无法读取页面运行时错误"
                    )
                } else {
                    parsed.forEach(::addDiagnostic)
                }
                finish()
            }
        }

        private fun executeSelfTests(testScript: String) {
            if (finished) return
            emitProbeStarted(WebInspectionEvent.Probe.SELF_TEST, testScript)
            if (testScript.isBlank()) {
                emitProbeResult(
                    probe = WebInspectionEvent.Probe.SELF_TEST,
                    status = WebInspectionEvent.Status.FAILED,
                    result = null,
                    decoded = null,
                    decodeStatus = WebInspectionEvent.Status.NOT_ATTEMPTED,
                    parseStatus = WebInspectionEvent.Status.NOT_ATTEMPTED,
                    diagnosticCodes = listOf("SELF_TEST_EMPTY")
                )
                addDiagnostic(
                    severity = WebDiagnosticSeverity.ERROR,
                    category = testCategory(),
                    code = "SELF_TEST_EMPTY",
                    message = "自测脚本为空"
                )
                finish()
                return
            }
            val resultPrefix = checkNotNull(selfTestResultChannelPrefix)
            acceptingSelfTestResult = true
            webView.evaluateJavascript(selfTestRunnerScript(testScript, resultPrefix), null)
        }

        private fun handleSelfTestPayload(payload: String) {
            if (finished) return
            val result = WebInspectionParsers.parseSelfTestResult(payload)
            if (result == null) {
                emitProbeResult(
                    probe = WebInspectionEvent.Probe.SELF_TEST,
                    status = WebInspectionEvent.Status.FAILED,
                    result = payload,
                    decoded = null,
                    decodeStatus = WebInspectionEvent.Status.NOT_REQUIRED,
                    parseStatus = WebInspectionEvent.Status.FAILED,
                    diagnosticCodes = listOf("SELF_TEST_INVALID_RESULT")
                )
                emit(
                    WebInspectionEvent.SelfTestRoot(
                        stage = stage,
                        status = WebInspectionEvent.Status.FAILED,
                        caseCount = 0,
                        diagnosticCode = "SELF_TEST_INVALID_RESULT"
                    )
                )
                addDiagnostic(
                    severity = WebDiagnosticSeverity.ERROR,
                    category = testCategory(),
                    code = "SELF_TEST_INVALID_RESULT",
                    message = "自测脚本返回了无效结果"
                )
            } else {
                val failedCases = result.cases.filterNot { it.passed }
                val resultDiagnosticCodes = buildList {
                    if (!result.passed) add("SELF_TEST_FAILED")
                }
                emitProbeResult(
                    probe = WebInspectionEvent.Probe.SELF_TEST,
                    status = WebInspectionEvent.Status.SUCCEEDED,
                    result = payload,
                    decoded = null,
                    decodeStatus = WebInspectionEvent.Status.NOT_REQUIRED,
                    parseStatus = WebInspectionEvent.Status.SUCCEEDED,
                    diagnosticCodes = resultDiagnosticCodes
                )
                emit(
                    WebInspectionEvent.SelfTestRoot(
                        stage = stage,
                        status = if (result.passed) {
                            WebInspectionEvent.Status.SUCCEEDED
                        } else {
                            WebInspectionEvent.Status.FAILED
                        },
                        caseCount = result.cases.size,
                        diagnosticCode = "SELF_TEST_FAILED".takeUnless { result.passed }
                    )
                )
                result.cases.forEachIndexed { index, testCase ->
                    val nameSummary = summarizeWebInspectionText(testCase.name)
                    emit(
                        WebInspectionEvent.SelfTestCaseResult(
                            stage = stage,
                            index = index,
                            status = if (testCase.passed) {
                                WebInspectionEvent.Status.SUCCEEDED
                            } else {
                                WebInspectionEvent.Status.FAILED
                            },
                            nameCharacterCount = nameSummary.characterCount,
                            nameSha256 = nameSummary.sha256,
                            diagnosticCode = "SELF_TEST_FAILED".takeUnless { testCase.passed }
                        )
                    )
                }
                selfTests = result.cases
                failedCases.forEach { testCase ->
                    addDiagnostic(
                        severity = WebDiagnosticSeverity.ERROR,
                        category = testCategory(),
                        code = "SELF_TEST_FAILED",
                        message = buildString {
                            append(testCase.name)
                            testCase.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                        },
                        stackTrace = testCase.details
                    )
                }
                if (!result.passed && result.cases.isEmpty()) {
                    addDiagnostic(
                        severity = WebDiagnosticSeverity.ERROR,
                        category = testCategory(),
                        code = "SELF_TEST_FAILED",
                        message = "自测未通过"
                    )
                }
            }
            collectRuntimeErrors()
        }

        private fun addDiagnostic(
            severity: WebDiagnosticSeverity,
            category: WebDiagnosticCategory,
            code: String,
            message: String,
            location: WebSourceLocation? = null,
            stackTrace: String? = null
        ) {
            addDiagnostic(
                WebInspectionDiagnostic(
                    stage = stage,
                    severity = severity,
                    category = category,
                    code = code,
                    message = message,
                    location = location,
                    stackTrace = stackTrace
                )
            )
        }

        private fun testCategory(): WebDiagnosticCategory {
            return if (stage == WebInspectionStage.TEST_SUITE) {
                WebDiagnosticCategory.TEST_SUITE
            } else {
                WebDiagnosticCategory.SELF_TEST
            }
        }

        private fun addDiagnostic(diagnostic: WebInspectionDiagnostic) {
            val key = diagnosticKey(diagnostic)
            if (!diagnosticKeys.add(key)) return
            diagnostics += diagnostic
            emit(WebInspectionEvent.DiagnosticCaptured(stage, diagnostic))
        }

        private fun finish(timedOut: Boolean = false) {
            if (finished) return
            finished = true
            acceptingSelfTestResult = false
            handler.removeCallbacksAndMessages(null)
            if (timedOut) runCatching { webView.stopLoading() }
            restoreClients()
            onComplete(
                WebInspectionReport(
                    stage = stage,
                    diagnostics = diagnostics.toList(),
                    selfTests = selfTests,
                    durationMillis = SystemClock.elapsedRealtime() - startedAt,
                    timedOut = timedOut
                )
            )
        }

        private fun restoreClients() {
            runCatching { webView.webChromeClient = previousChromeClient ?: WebChromeClient() }
            runCatching { webView.webViewClient = previousViewClient }
        }

        private fun diagnosticKey(diagnostic: WebInspectionDiagnostic): String {
            return listOf(
                diagnostic.severity.name,
                diagnostic.message.removePrefix("Uncaught ").removePrefix("Uncaught (in promise) "),
                diagnostic.location?.source.orEmpty(),
                diagnostic.location?.line?.toString().orEmpty()
            ).joinToString("|")
        }
    }

    private fun testSuiteDiagnostic(
        code: String,
        message: String,
        stackTrace: String? = null,
        severity: WebDiagnosticSeverity = WebDiagnosticSeverity.ERROR
    ): WebInspectionDiagnostic {
        return WebInspectionDiagnostic(
            stage = WebInspectionStage.TEST_SUITE,
            severity = severity,
            category = WebDiagnosticCategory.TEST_SUITE,
            code = code,
            message = message,
            stackTrace = stackTrace
        )
    }

    private fun WebInspectionReport.scopedToViewport(
        viewportId: String,
        phaseId: String
    ): WebInspectionReport {
        val scope = "$viewportId/$phaseId"
        return copy(
            diagnostics = diagnostics.map { diagnostic ->
                diagnostic.copy(message = "[$scope] ${diagnostic.message}")
            },
            selfTests = selfTests.map { testCase ->
                testCase.copy(name = "$scope/${testCase.name}")
            }
        )
    }

    private fun defaultEvidenceDirectory(evidenceRunId: String): File {
        return File(
            webView.context.cacheDir,
            "mantou-harness/visual-evidence/${safeEvidenceComponent(evidenceRunId)}"
        )
    }

    private fun screenshotFileName(
        viewportIndex: Int,
        viewportId: String,
        visualStateId: String
    ): String {
        return "%02d-%s-%s.png".format(
            viewportIndex + 1,
            safeEvidenceComponent(viewportId),
            safeEvidenceComponent(visualStateId)
        )
    }

    private fun safeEvidenceComponent(value: String): String {
        return value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(80)
            .ifBlank { "test-suite" }
    }

    private fun captureLayoutSnapshot(): WebViewLayoutSnapshot {
        val layoutParams = webView.layoutParams
        return WebViewLayoutSnapshot(
            left = webView.left,
            top = webView.top,
            width = webView.width,
            height = webView.height,
            layoutParamsWidth = layoutParams?.width,
            layoutParamsHeight = layoutParams?.height
        )
    }

    private fun applyViewport(viewport: WebQualityViewport) {
        val density = webView.resources.displayMetrics.density
            .takeIf { value -> value.isFinite() && value > 0f }
            ?: 1f
        val pixelWidth = (viewport.widthCssPixels * density).roundToInt().coerceAtLeast(1)
        val pixelHeight = (viewport.heightCssPixels * density).roundToInt().coerceAtLeast(1)
        webView.layoutParams?.let { layoutParams ->
            layoutParams.width = pixelWidth
            layoutParams.height = pixelHeight
            webView.layoutParams = layoutParams
        }
        webView.requestLayout()
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(pixelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY)
        )
        webView.layout(
            webView.left,
            webView.top,
            webView.left + pixelWidth,
            webView.top + pixelHeight
        )
    }

    private fun restoreLayout(snapshot: WebViewLayoutSnapshot) {
        webView.layoutParams?.let { layoutParams ->
            snapshot.layoutParamsWidth?.let { layoutParams.width = it }
            snapshot.layoutParamsHeight?.let { layoutParams.height = it }
            webView.layoutParams = layoutParams
        }
        webView.requestLayout()
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(snapshot.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(snapshot.height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(
            snapshot.left,
            snapshot.top,
            snapshot.left + snapshot.width,
            snapshot.top + snapshot.height
        )
    }

    private suspend fun readCssViewportDimensions(): CssViewportDimensions? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    webView.evaluateJavascript(VIEWPORT_DIMENSIONS_SCRIPT) { value ->
                        if (!continuation.isActive) return@evaluateJavascript
                        val payload = WebInspectionParsers.decodeJavascriptString(value)
                        val dimensions = payload?.let { json ->
                            runCatching {
                                gson.fromJson(json, CssViewportDimensions::class.java)
                            }.getOrNull()
                        }?.takeIf { item ->
                            item.widthCssPixels > 0 && item.heightCssPixels > 0
                        }
                        continuation.resume(dimensions)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private suspend fun capturePngScreenshot(file: File): Result<CapturedScreenshot> {
        val bitmapResult = withContext(Dispatchers.Main.immediate) {
            runCatching {
                val width = webView.width
                val height = webView.height
                require(width > 0 && height > 0) { "WebView 没有可截图的像素尺寸" }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    webView.draw(Canvas(bitmap))
                }
            }
        }
        val bitmap = bitmapResult.getOrElse { return Result.failure(it) }
        return try {
            withContext(Dispatchers.IO) {
                runCatching {
                    val parent = requireNotNull(file.parentFile) { "截图目录无效" }
                    check(parent.isDirectory || parent.mkdirs()) { "无法创建截图目录 ${parent.absolutePath}" }
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "WebView 截图 PNG 编码失败"
                        }
                        output.toByteArray()
                    }
                    file.writeBytes(bytes)
                    CapturedScreenshot(
                        file = file,
                        pixelWidth = bitmap.width,
                        pixelHeight = bitmap.height,
                        sha256 = sha256(bytes)
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class WebViewLayoutSnapshot(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val layoutParamsWidth: Int?,
        val layoutParamsHeight: Int?
    )

    private data class CssViewportDimensions(
        val widthCssPixels: Int,
        val heightCssPixels: Int
    )

    private data class CapturedScreenshot(
        val file: File,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val sha256: String
    )

    private sealed interface InspectionMode {
        data class Build(val html: String) : InspectionMode
        data class Runtime(
            val target: WebInspectionTarget,
            val settleMillis: Long,
            val requestInterceptor: WebRequestInterceptor?
        ) : InspectionMode
        data class Tests(
            val target: WebInspectionTarget,
            val testScript: String,
            val settleMillis: Long,
            val requestInterceptor: WebRequestInterceptor?,
            val expectedViewport: WebQualityViewport? = null
        ) : InspectionMode
    }

    companion object {
        const val DEFAULT_BUILD_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_RUNTIME_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_TEST_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_SETTLE_MILLIS = 500L
        internal const val RUNTIME_HARNESS_MARKER = "__mantouHarnessInspector"
        private const val INITIAL_VISUAL_STATE_ID = "initial"
        private const val QUALITY_PHASE_ID = "quality"
        private const val ACCEPTANCE_PHASE_ID = "acceptance"
        private const val SELF_TEST_RESULT_PREFIX = "__MANTOU_SELF_TEST_RESULT__"
        private val VIEWPORT_DIMENSIONS_SCRIPT = """
            (function () {
              return JSON.stringify({
                widthCssPixels: Math.round(window.innerWidth),
                heightCssPixels: Math.round(window.innerHeight)
              });
            })();
        """.trimIndent()
        private val BUILD_INSPECTOR_PAGE =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                WebInspectionParsers.EMPTY_FAVICON_LINK +
                "</head><body></body></html>"

        private val gson = Gson()

        internal fun prepareProjectHtmlForInspection(html: String): String {
            return WebInspectionParsers.injectRuntimeHarness(html, RUNTIME_HARNESS_SCRIPT)
        }

        private val RUNTIME_HARNESS_SCRIPT = """
            (function () {
              if (window.$RUNTIME_HARNESS_MARKER) return;
              var state = { errors: [], nativeToolsMode: 'dry-run' };
              window.$RUNTIME_HARNESS_MARKER = state;
              window.__MANTOU_HARNESS_DRY_RUN__ = true;
              if (!window.MantouApp || typeof window.MantouApp.isMantouApp !== 'function') {
                var toolCache = {};
                var storageState = {};
                var storageResponse = function (data, error) {
                  return JSON.stringify({ success: !error, data: data || null, error: error || null });
                };
                var parseStorageObject = function (value) {
                  var parsed = JSON.parse(value || '{}');
                  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
                    throw new Error('storage root must be a JSON object');
                  }
                  return parsed;
                };
                var resetStorageState = function () {
                  Object.keys(storageState).forEach(function (key) { delete storageState[key]; });
                };
                var storage = {
                  storageRead: function () {
                    return storageResponse({ content: JSON.stringify(storageState) });
                  },
                  storageWrite: function (jsonContent) {
                    try {
                      var nextState = parseStorageObject(jsonContent);
                      resetStorageState();
                      Object.keys(nextState).forEach(function (key) { storageState[key] = nextState[key]; });
                      return storageResponse({ bytes: String(jsonContent || '').length });
                    } catch (error) {
                      return storageResponse(null, error.message || String(error));
                    }
                  },
                  storageGet: function (key) {
                    var exists = Object.prototype.hasOwnProperty.call(storageState, key);
                    return storageResponse({
                      exists: exists,
                      valueJson: exists ? JSON.stringify(storageState[key]) : 'null'
                    });
                  },
                  storageSet: function (key, valueJson) {
                    try {
                      storageState[key] = JSON.parse(valueJson);
                      return storageResponse({ key: key });
                    } catch (error) {
                      return storageResponse(null, error.message || String(error));
                    }
                  },
                  storageRemove: function (key) {
                    delete storageState[key];
                    return storageResponse({ key: key });
                  },
                  storageClear: function () {
                    resetStorageState();
                    return storageResponse({ cleared: true });
                  }
                };
                state.resetStorage = resetStorageState;
                var dryResult = function (method, args) {
                  var data = {
                    dryRun: true,
                    method: method,
                    content: '{}',
                    exists: false,
                    valueJson: 'null',
                    text: '',
                    uri: '',
                    bytes: args && args.length ? String(args[0] == null ? '' : args[0]).length : 0
                  };
                  return JSON.stringify({ success: true, data: data, error: null });
                };
                var tool = function (name) {
                  if (!toolCache[name]) {
                    toolCache[name] = new Proxy({}, {
                      get: function (_, method) {
                        if (method === 'then') return undefined;
                        return function () { return dryResult(name + '.' + String(method), arguments); };
                      }
                    });
                  }
                  return toolCache[name];
                };
                window.MantouApp = new Proxy({
                  isMantouApp: function () { return true; },
                  getToolNames: function () { return '[]'; },
                  storage: storage
                }, {
                  get: function (target, name) {
                    if (name in target) return target[name];
                    if (name === 'then') return undefined;
                    return tool(String(name));
                  },
                  set: function (target, name, value) {
                    target[name] = value;
                    return true;
                  }
                });
              }
              function text(value) {
                if (value == null) return '';
                if (value instanceof Error) return value.message || String(value);
                if (typeof value === 'string') return value;
                try { return JSON.stringify(value); } catch (_) { return String(value); }
              }
              function record(error) {
                state.errors.push(error);
              }
              window.addEventListener('error', function (event) {
                record({
                  severity: 'ERROR',
                  category: 'JAVASCRIPT',
                  code: 'JS_RUNTIME_ERROR',
                  message: event.message || text(event.error) || 'JavaScript runtime error',
                  source: event.filename || location.href,
                  line: event.lineno || null,
                  column: event.colno || null,
                  stack: event.error && event.error.stack ? String(event.error.stack) : null
                });
              }, true);
              window.addEventListener('unhandledrejection', function (event) {
                var reason = event.reason;
                record({
                  severity: 'ERROR',
                  category: 'JAVASCRIPT',
                  code: 'JS_UNHANDLED_REJECTION',
                  message: text(reason) || 'Unhandled promise rejection',
                  source: location.href,
                  line: null,
                  column: null,
                  stack: reason && reason.stack ? String(reason.stack) : null
                });
              });
            })();
        """.trimIndent()

        private val RUNTIME_COLLECT_SCRIPT = """
            (function () {
              var state = window.$RUNTIME_HARNESS_MARKER;
              return JSON.stringify(state && Array.isArray(state.errors) ? state.errors : []);
            })();
        """.trimIndent()

        private fun buildProbeScript(html: String): String {
            val scripts = WebInspectionParsers.extractInlineScripts(html).map { script ->
                mapOf(
                    "index" to script.index,
                    "source" to script.source,
                    "startLine" to script.startLine,
                    "type" to script.type
                )
            }
            return """
                (function () {
                  var scripts = ${gson.toJson(scripts)};
                  var diagnostics = [];
                  scripts.forEach(function (script) {
                    var type = (script.type || '').toLowerCase();
                    if (type === 'module') {
                      diagnostics.push({
                        severity: 'WARNING', category: 'JAVASCRIPT', code: 'JS_MODULE_NOT_COMPILED',
                        message: '构建检查未静态编译 module 脚本', source: 'inline-script-' + script.index,
                        line: script.startLine, column: null, stack: null
                      });
                      return;
                    }
                    if (type && type !== 'text/javascript' && type !== 'application/javascript' &&
                        type !== 'text/ecmascript' && type !== 'application/ecmascript') return;
                    try {
                      new Function(String(script.source) + '\n//# sourceURL=mantou-inline-' + script.index + '.js');
                    } catch (error) {
                      diagnostics.push({
                        severity: 'ERROR', category: 'JAVASCRIPT', code: 'JS_SYNTAX_ERROR',
                        message: error && error.message ? String(error.message) : String(error),
                        source: 'inline-script-' + script.index, line: script.startLine,
                        column: null, stack: error && error.stack ? String(error.stack) : null
                      });
                    }
                  });
                  return JSON.stringify(diagnostics);
                })();
            """.trimIndent()
        }

        private fun selfTestRunnerScript(testScript: String, resultPrefix: String): String {
            return """
                (function () {
                  var source = ${gson.toJson(testScript)};
                  var resultPrefix = ${gson.toJson(resultPrefix)};
                  function safeText(value) {
                    if (value == null) return null;
                    if (typeof value === 'string') return value;
                    try { return JSON.stringify(value); } catch (_) { return String(value); }
                  }
                  function asCase(value, index) {
                    if (typeof value === 'boolean') {
                      return { name: 'self-test-' + (index + 1), passed: value, message: value ? null : '断言返回 false', details: null };
                    }
                    if (value && typeof value === 'object') {
                      return {
                        name: value.name ? String(value.name) : 'self-test-' + (index + 1),
                        passed: value.passed === true,
                        message: safeText(value.message),
                        details: safeText(value.details || value.stack)
                      };
                    }
                    return { name: 'self-test-' + (index + 1), passed: false, message: '测试必须返回 boolean 或结果对象', details: safeText(value) };
                  }
                  function normalize(value) {
                    var values;
                    if (Array.isArray(value)) values = value;
                    else if (value && Array.isArray(value.cases)) values = value.cases;
                    else values = [value];
                    var cases = values.map(asCase);
                    return { passed: cases.length > 0 && cases.every(function (item) { return item.passed; }), cases: cases };
                  }
                  function send(result) {
                    console.log(resultPrefix + JSON.stringify(result));
                  }
                  var value;
                  try {
                    value = (new Function('"use strict";\n' + source)).call(window);
                  } catch (error) {
                    send({ passed: false, cases: [{ name: 'self-test-script', passed: false, message: error.message || String(error), details: error.stack || null }] });
                    return;
                  }
                  Promise.resolve(value).then(function (result) {
                    send(normalize(result));
                  }, function (error) {
                    send({ passed: false, cases: [{ name: 'self-test-promise', passed: false, message: error && error.message ? error.message : String(error), details: error && error.stack ? error.stack : null }] });
                  });
                })();
            """.trimIndent()
        }
    }
}
