package com.hfad.mantou.utils.harness

import java.io.File
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

data class WebQualityViewport(
    val id: String,
    val widthCssPixels: Int,
    val heightCssPixels: Int,
    val toleranceCssPixels: Int = 2
) {
    init {
        require(id.isNotBlank()) { "viewport id must not be blank" }
        require(widthCssPixels > 0) { "viewport width must be greater than zero" }
        require(heightCssPixels > 0) { "viewport height must be greater than zero" }
        require(toleranceCssPixels >= 0) { "viewport tolerance must not be negative" }
    }
}

data class WebVisualStateRequirement(
    val id: String,
    val description: String,
    val stableSelector: String? = null
) {
    init {
        require(id.isNotBlank()) { "visual state id must not be blank" }
        require(description.isNotBlank()) { "visual state description must not be blank" }
        require(stableSelector == null || stableSelector.isNotBlank()) {
            "visual state selector must be null or non-blank"
        }
    }
}

data class WebQualityGateContract(
    val minTouchTargetCssPixels: Int = 44,
    val maxHorizontalOverflowCssPixels: Int = 2,
    val minNormalTextContrastRatio: Double = 4.5,
    val minLargeTextContrastRatio: Double = 3.0,
    val requireMantouBridge: Boolean = true,
    val requireOfflineDependencies: Boolean = true,
    val allowInlineTextTargetException: Boolean = true,
    val requiredViewports: List<WebQualityViewport> = DEFAULT_MOBILE_VIEWPORTS,
    val requiredVisualStates: List<WebVisualStateRequirement> = DEFAULT_VISUAL_STATES,
    val requireScreenshotEvidence: Boolean = true
) {
    init {
        require(minTouchTargetCssPixels > 0) {
            "minimum touch target must be greater than zero"
        }
        require(maxHorizontalOverflowCssPixels >= 0) {
            "maximum horizontal overflow must not be negative"
        }
        require(minNormalTextContrastRatio in 1.0..21.0) {
            "normal text contrast ratio must be between 1 and 21"
        }
        require(minLargeTextContrastRatio in 1.0..21.0) {
            "large text contrast ratio must be between 1 and 21"
        }
        require(minNormalTextContrastRatio >= minLargeTextContrastRatio) {
            "normal text contrast ratio must not be lower than large text contrast ratio"
        }
        require(requiredViewports.isNotEmpty()) { "at least one viewport is required" }
        require(requiredViewports.map(WebQualityViewport::id).distinct().size == requiredViewports.size) {
            "viewport ids must be unique"
        }
        require(requiredVisualStates.isNotEmpty()) { "at least one visual state is required" }
        require(
            requiredVisualStates.map(WebVisualStateRequirement::id).distinct().size ==
                requiredVisualStates.size
        ) {
            "visual state ids must be unique"
        }
    }

    companion object {
        val DEFAULT_MOBILE_VIEWPORTS: List<WebQualityViewport> = listOf(
            WebQualityViewport(id = "compact-portrait", widthCssPixels = 360, heightCssPixels = 640),
            WebQualityViewport(id = "standard-portrait", widthCssPixels = 393, heightCssPixels = 852),
            WebQualityViewport(id = "compact-landscape", widthCssPixels = 844, heightCssPixels = 390)
        )
        val DEFAULT_VISUAL_STATES: List<WebVisualStateRequirement> = listOf(
            WebVisualStateRequirement(
                id = "initial",
                description = "应用完成首次加载后的稳定首屏"
            )
        )
    }
}

fun WebQualityGateContract.withDesignRequirements(
    viewportWidths: List<Int>,
    minTouchTargetCssPixels: Int
): WebQualityGateContract {
    val defaultByWidth = requiredViewports.associateBy(WebQualityViewport::widthCssPixels)
    val additionalViewports = viewportWidths
        .distinct()
        .filterNot(defaultByWidth::containsKey)
        .map { width ->
            WebQualityViewport(
                id = "spec-portrait-$width",
                widthCssPixels = width,
                heightCssPixels = portraitHeightFor(width)
            )
        }
    return copy(
        minTouchTargetCssPixels = minTouchTargetCssPixels,
        requiredViewports = requiredViewports + additionalViewports
    )
}

private fun portraitHeightFor(widthCssPixels: Int): Int {
    return ((widthCssPixels * 16L + 4L) / 9L).toInt()
}

data class WebVisualEvidence(
    val viewportId: String,
    val visualStateId: String,
    val widthCssPixels: Int,
    val heightCssPixels: Int,
    val screenshotArtifactPath: String? = null,
    val screenshotPixelWidth: Int? = null,
    val screenshotPixelHeight: Int? = null,
    val screenshotSha256: String? = null
) {
    init {
        require(viewportId.isNotBlank()) { "evidence viewport id must not be blank" }
        require(visualStateId.isNotBlank()) { "evidence visual state id must not be blank" }
        require(widthCssPixels > 0) { "evidence width must be greater than zero" }
        require(heightCssPixels > 0) { "evidence height must be greater than zero" }
        require(screenshotArtifactPath == null || screenshotArtifactPath.isNotBlank()) {
            "screenshot artifact path must be null or non-blank"
        }
        require(screenshotPixelWidth == null || screenshotPixelWidth > 0) {
            "screenshot pixel width must be null or greater than zero"
        }
        require(screenshotPixelHeight == null || screenshotPixelHeight > 0) {
            "screenshot pixel height must be null or greater than zero"
        }
        require(screenshotSha256 == null || SCREENSHOT_SHA256_PATTERN.matches(screenshotSha256)) {
            "screenshot sha256 must be null or a lowercase SHA-256 digest"
        }
    }

    private companion object {
        val SCREENSHOT_SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class WebQualityGateCoverage(
    val passed: Boolean,
    val requiredEvidenceCount: Int,
    val acceptedEvidenceCount: Int,
    val diagnostics: List<String>
)

object WebQualityGateEvaluator {
    fun evaluateEvidence(
        contract: WebQualityGateContract,
        evidence: List<WebVisualEvidence>
    ): WebQualityGateCoverage {
        val diagnostics = mutableListOf<String>()
        val expectedKeys = contract.requiredViewports.flatMap { viewport ->
            contract.requiredVisualStates.map { state -> viewport.id to state.id }
        }
        val groupedEvidence = evidence.groupBy { item -> item.viewportId to item.visualStateId }

        groupedEvidence.filterValues { it.size > 1 }.keys.forEach { (viewportId, stateId) ->
            diagnostics += "重复的视觉证据: $viewportId/$stateId"
        }
        evidence.filter { item -> (item.viewportId to item.visualStateId) !in expectedKeys }
            .forEach { item ->
                diagnostics += "未声明的视觉证据: ${item.viewportId}/${item.visualStateId}"
            }

        var acceptedEvidenceCount = 0
        expectedKeys.forEach { (viewportId, stateId) ->
            val item = groupedEvidence[viewportId to stateId]?.singleOrNull()
            if (item == null) {
                diagnostics += "缺少视觉证据: $viewportId/$stateId"
                return@forEach
            }
            val viewport = contract.requiredViewports.first { it.id == viewportId }
            val widthMatches = kotlin.math.abs(item.widthCssPixels - viewport.widthCssPixels) <=
                viewport.toleranceCssPixels
            val heightMatches = kotlin.math.abs(item.heightCssPixels - viewport.heightCssPixels) <=
                viewport.toleranceCssPixels
            if (!widthMatches || !heightMatches) {
                diagnostics += buildString {
                    append("视觉证据 viewport 不匹配: ")
                    append(viewportId).append('/').append(stateId)
                    append("，实际 ").append(item.widthCssPixels).append('x').append(item.heightCssPixels)
                    append("，期望 ").append(viewport.widthCssPixels).append('x')
                        .append(viewport.heightCssPixels)
                }
                return@forEach
            }
            if (contract.requireScreenshotEvidence) {
                val screenshotPath = item.screenshotArtifactPath
                val screenshotFile = screenshotPath?.let(::File)
                val pngDimensions = screenshotFile
                    ?.takeIf { file -> file.isFile && file.length() > 0L }
                    ?.let { file -> runCatching { file.pngPixelDimensions() }.getOrNull() }
                when {
                    screenshotFile == null -> {
                        diagnostics += "视觉证据缺少截图: $viewportId/$stateId"
                        return@forEach
                    }
                    !screenshotFile.isFile || screenshotFile.length() <= 0L -> {
                        diagnostics += "视觉证据截图文件不存在或为空: $viewportId/$stateId"
                        return@forEach
                    }
                    item.screenshotPixelWidth == null || item.screenshotPixelHeight == null -> {
                        diagnostics += "视觉证据缺少截图像素尺寸: $viewportId/$stateId"
                        return@forEach
                    }
                    pngDimensions == null -> {
                        diagnostics += "视觉证据截图不是有效 PNG: $viewportId/$stateId"
                        return@forEach
                    }
                    pngDimensions !=
                        PngPixelDimensions(
                            width = item.screenshotPixelWidth,
                            height = item.screenshotPixelHeight
                        ) -> {
                        diagnostics += "视觉证据截图像素尺寸不匹配: $viewportId/$stateId"
                        return@forEach
                    }
                    item.screenshotSha256 == null -> {
                        diagnostics += "视觉证据缺少截图 SHA-256: $viewportId/$stateId"
                        return@forEach
                    }
                    runCatching { screenshotFile.webVisualEvidenceSha256() }.getOrNull() !=
                        item.screenshotSha256 -> {
                        diagnostics += "视觉证据截图 SHA-256 不匹配: $viewportId/$stateId"
                        return@forEach
                    }
                }
            }
            acceptedEvidenceCount += 1
        }

        return WebQualityGateCoverage(
            passed = diagnostics.isEmpty() && acceptedEvidenceCount == expectedKeys.size,
            requiredEvidenceCount = expectedKeys.size,
            acceptedEvidenceCount = acceptedEvidenceCount,
            diagnostics = diagnostics
        )
    }
}

data class WebInspectionReport(
    val stage: WebInspectionStage,
    val diagnostics: List<WebInspectionDiagnostic>,
    val selfTests: List<WebSelfTestCase> = emptyList(),
    val visualEvidence: List<WebVisualEvidence> = emptyList(),
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

internal fun mergeWebInspectionReports(
    stage: WebInspectionStage,
    reports: List<WebInspectionReport>,
    visualEvidence: List<WebVisualEvidence>,
    additionalDiagnostics: List<WebInspectionDiagnostic>,
    durationMillis: Long
): WebInspectionReport {
    return WebInspectionReport(
        stage = stage,
        diagnostics = reports.flatMap(WebInspectionReport::diagnostics) + additionalDiagnostics,
        selfTests = reports.flatMap(WebInspectionReport::selfTests),
        visualEvidence = visualEvidence,
        durationMillis = durationMillis,
        timedOut = reports.any(WebInspectionReport::timedOut)
    )
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

private fun File.webVisualEvidenceSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private data class PngPixelDimensions(
    val width: Int,
    val height: Int
)

private fun File.pngPixelDimensions(): PngPixelDimensions? {
    val header = ByteArray(24)
    inputStream().buffered().use { input ->
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read < 0) return null
            offset += read
        }
    }
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
    if (!header.copyOfRange(0, signature.size).contentEquals(signature)) return null
    if (!header.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII))) return null
    fun readInt(offset: Int): Int {
        return ((header[offset].toInt() and 0xff) shl 24) or
            ((header[offset + 1].toInt() and 0xff) shl 16) or
            ((header[offset + 2].toInt() and 0xff) shl 8) or
            (header[offset + 3].toInt() and 0xff)
    }
    val width = readInt(16)
    val height = readInt(20)
    return PngPixelDimensions(width, height).takeIf { width > 0 && height > 0 }
}
