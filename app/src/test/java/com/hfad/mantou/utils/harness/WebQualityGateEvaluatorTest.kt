package com.hfad.mantou.utils.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class WebQualityGateEvaluatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun completeViewportAndStateEvidencePasses() {
        val contract = contract()
        val evidence = listOf(
            evidence("portrait", "initial", 390, 844),
            evidence("portrait", "after-create", 390, 844),
            evidence("landscape", "initial", 844, 390),
            evidence("landscape", "after-create", 844, 390)
        )

        val result = WebQualityGateEvaluator.evaluateEvidence(contract, evidence)

        assertTrue(result.passed)
        assertEquals(4, result.requiredEvidenceCount)
        assertEquals(4, result.acceptedEvidenceCount)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun missingScreenshotAndWrongViewportFailCoverage() {
        val result = WebQualityGateEvaluator.evaluateEvidence(
            contract = contract(),
            evidence = listOf(
                evidence("portrait", "initial", 375, 844),
                WebVisualEvidence("portrait", "after-create", 390, 844),
                evidence("landscape", "initial", 844, 390)
            )
        )

        assertFalse(result.passed)
        assertEquals(4, result.requiredEvidenceCount)
        assertEquals(1, result.acceptedEvidenceCount)
        assertTrue(result.diagnostics.any { it.contains("viewport 不匹配") })
        assertTrue(result.diagnostics.any { it.contains("缺少截图") })
        assertTrue(result.diagnostics.any { it.contains("缺少视觉证据") })
    }

    @Test
    fun missingFilePixelDimensionsAndHashMismatchFailCoverage() {
        val missingFile = temporaryFolder.newFile("missing-file.png").apply { delete() }
        val missingDimensionsFile = pngFile("missing-dimensions.png", 780, 1688)
        val tampered = evidence("landscape", "after-create", 844, 390)
        File(tampered.screenshotArtifactPath!!).appendText("tampered")

        val result = WebQualityGateEvaluator.evaluateEvidence(
            contract = contract(),
            evidence = listOf(
                WebVisualEvidence(
                    viewportId = "portrait",
                    visualStateId = "initial",
                    widthCssPixels = 390,
                    heightCssPixels = 844,
                    screenshotArtifactPath = missingFile.absolutePath,
                    screenshotPixelWidth = 780,
                    screenshotPixelHeight = 1688,
                    screenshotSha256 = sha256("missing".toByteArray())
                ),
                WebVisualEvidence(
                    viewportId = "portrait",
                    visualStateId = "after-create",
                    widthCssPixels = 390,
                    heightCssPixels = 844,
                    screenshotArtifactPath = missingDimensionsFile.absolutePath,
                    screenshotSha256 = sha256(missingDimensionsFile.readBytes())
                ),
                evidence("landscape", "initial", 844, 390),
                tampered
            )
        )

        assertFalse(result.passed)
        assertEquals(1, result.acceptedEvidenceCount)
        assertTrue(result.diagnostics.any { it.contains("文件不存在或为空") })
        assertTrue(result.diagnostics.any { it.contains("缺少截图像素尺寸") })
        assertTrue(result.diagnostics.any { it.contains("SHA-256 不匹配") })
    }

    @Test
    fun mergePreservesViewportReportAndEvidenceOrder() {
        val first = WebInspectionReport(
            stage = WebInspectionStage.TEST_SUITE,
            diagnostics = listOf(diagnostic("first")),
            selfTests = listOf(WebSelfTestCase("portrait/quality", true)),
            durationMillis = 10
        )
        val second = WebInspectionReport(
            stage = WebInspectionStage.TEST_SUITE,
            diagnostics = listOf(diagnostic("second")),
            selfTests = listOf(WebSelfTestCase("landscape/quality", true)),
            durationMillis = 20
        )
        val evidence = listOf(
            evidence("portrait", "initial", 390, 844),
            evidence("landscape", "initial", 844, 390)
        )

        val merged = mergeWebInspectionReports(
            stage = WebInspectionStage.TEST_SUITE,
            reports = listOf(first, second),
            visualEvidence = evidence,
            additionalDiagnostics = emptyList(),
            durationMillis = 35
        )

        assertEquals(listOf("first", "second"), merged.diagnostics.map { it.code })
        assertEquals(
            listOf("portrait/quality", "landscape/quality"),
            merged.selfTests.map { it.name }
        )
        assertEquals(listOf("portrait", "landscape"), merged.visualEvidence.map { it.viewportId })
        assertEquals(35, merged.durationMillis)
    }

    @Test
    fun pngPixelDimensionMismatchFailsCoverage() {
        val mismatched = evidence("portrait", "initial", 390, 844).copy(
            screenshotPixelWidth = 779
        )
        val contract = WebQualityGateContract(
            requiredViewports = listOf(WebQualityViewport("portrait", 390, 844))
        )

        val result = WebQualityGateEvaluator.evaluateEvidence(contract, listOf(mismatched))

        assertFalse(result.passed)
        assertEquals(0, result.acceptedEvidenceCount)
        assertTrue(result.diagnostics.any { it.contains("截图像素尺寸不匹配") })
    }

    @Test
    fun designRequirementsStrengthenDefaultsAndAddMissingWidths() {
        val contract = WebQualityGateContract().withDesignRequirements(
            viewportWidths = listOf(393, 412, 412),
            minTouchTargetCssPixels = 52
        )

        assertEquals(52, contract.minTouchTargetCssPixels)
        assertEquals(
            WebQualityGateContract.DEFAULT_MOBILE_VIEWPORTS.map(WebQualityViewport::id) +
                "spec-portrait-412",
            contract.requiredViewports.map(WebQualityViewport::id)
        )
        assertEquals(
            WebQualityViewport("spec-portrait-412", 412, 732),
            contract.requiredViewports.last()
        )
    }

    private fun contract(): WebQualityGateContract {
        return WebQualityGateContract(
            requiredViewports = listOf(
                WebQualityViewport("portrait", 390, 844),
                WebQualityViewport("landscape", 844, 390)
            ),
            requiredVisualStates = listOf(
                WebVisualStateRequirement("initial", "初始页面"),
                WebVisualStateRequirement("after-create", "创建条目后", "#items")
            )
        )
    }

    private fun evidence(
        viewportId: String,
        visualStateId: String,
        widthCssPixels: Int,
        heightCssPixels: Int
    ): WebVisualEvidence {
        val pixelWidth = widthCssPixels * 2
        val pixelHeight = heightCssPixels * 2
        val file = pngFile(
            "$viewportId-$visualStateId-$widthCssPixels-$heightCssPixels.png",
            pixelWidth,
            pixelHeight
        )
        return WebVisualEvidence(
            viewportId = viewportId,
            visualStateId = visualStateId,
            widthCssPixels = widthCssPixels,
            heightCssPixels = heightCssPixels,
            screenshotArtifactPath = file.absolutePath,
            screenshotPixelWidth = pixelWidth,
            screenshotPixelHeight = pixelHeight,
            screenshotSha256 = sha256(file.readBytes())
        )
    }

    private fun pngFile(name: String, width: Int, height: Int): File {
        return temporaryFolder.newFile(name).apply {
            writeBytes(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                ) + byteArrayOf(0, 0, 0, 13) +
                    "IHDR".toByteArray(Charsets.US_ASCII) +
                    intBytes(width) +
                    intBytes(height) +
                    name.toByteArray()
            )
        }
    }

    private fun intBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun diagnostic(code: String): WebInspectionDiagnostic {
        return WebInspectionDiagnostic(
            stage = WebInspectionStage.TEST_SUITE,
            severity = WebDiagnosticSeverity.ERROR,
            category = WebDiagnosticCategory.TEST_SUITE,
            code = code,
            message = code
        )
    }
}
