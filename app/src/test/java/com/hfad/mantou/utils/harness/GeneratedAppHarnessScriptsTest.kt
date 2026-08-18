package com.hfad.mantou.utils.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedAppHarnessScriptsTest {

    @Test
    fun scriptsCoverSelfTestAndIndependentDeliveryChecks() {
        assertTrue(GeneratedAppHarnessScripts.selfTest.contains("__MANTOU_SELF_TEST__"))
        assertTrue(GeneratedAppHarnessScripts.testSuite.contains("offline-dependencies"))
        assertTrue(GeneratedAppHarnessScripts.testSuite.contains("mantou-tool-bridge"))
    }

    @Test
    fun diagnosticsIncludeInspectorCodeAndFailedTests() {
        val report = WebInspectionReport(
            stage = WebInspectionStage.TEST_SUITE,
            diagnostics = listOf(
                WebInspectionDiagnostic(
                    stage = WebInspectionStage.TEST_SUITE,
                    severity = WebDiagnosticSeverity.ERROR,
                    category = WebDiagnosticCategory.JAVASCRIPT,
                    code = "JS_RUNTIME_ERROR",
                    message = "timer is not defined"
                )
            ),
            selfTests = listOf(WebSelfTestCase("viewport", false, "missing")),
            durationMillis = 12L
        )

        val diagnostics = GeneratedAppHarnessScripts.diagnostics(report)

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics[0].contains("JS_RUNTIME_ERROR"))
        assertTrue(diagnostics[1].contains("viewport"))
    }
}
