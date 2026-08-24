package com.hfad.mantou.utils.harness

import com.hfad.mantou.utils.project.WebAppAcceptanceAction
import com.hfad.mantou.utils.project.WebAppAcceptanceActionType
import com.hfad.mantou.utils.project.WebAppAcceptanceAssertion
import com.hfad.mantou.utils.project.WebAppAcceptanceAssertionType
import com.hfad.mantou.utils.project.WebAppAcceptanceContract
import com.hfad.mantou.utils.project.WebAppAcceptanceCriterion
import com.hfad.mantou.utils.project.WebAppAcceptancePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedAppHarnessScriptsTest {

    @Test
    fun scriptsCoverSelfTestAndIndependentDeliveryChecks() {
        assertTrue(GeneratedAppHarnessScripts.selfTest.contains("__MANTOU_SELF_TEST__"))
        assertTrue(GeneratedAppHarnessScripts.testSuite.contains("offline-dependencies"))
        assertTrue(GeneratedAppHarnessScripts.testSuite.contains("mantou-tool-bridge"))
        assertTrue(GeneratedAppHarnessScripts.testSuite.contains("touch-target-size"))
        assertTrue(GeneratedAppHarnessScripts.testSuite.contains("interactive-accessible-names"))
        assertTrue(GeneratedAppHarnessScripts.testSuite.contains("text-color-contrast"))
    }

    @Test
    fun qualityGateEmbedsThresholdsAndExpectedViewport() {
        val script = GeneratedAppHarnessScripts.qualityGateSuite(
            contract = WebQualityGateContract(minTouchTargetCssPixels = 48),
            expectedViewport = WebQualityViewport(
                id = "test-portrait",
                widthCssPixels = 390,
                heightCssPixels = 844
            )
        )

        assertTrue(script.contains("\"minTouchTargetCssPixels\":48"))
        assertTrue(script.contains("\"id\":\"test-portrait\""))
        assertTrue(script.contains("expected-viewport:"))
    }

    @Test
    fun acceptanceSuiteUsesTypedContractAndHostOwnedActions() {
        val script = GeneratedAppHarnessScripts.acceptanceSuite(validAcceptanceContract())

        assertTrue(script.contains("acceptance-contract-coverage"))
        assertTrue(script.contains("\"type\":\"CLICK\""))
        assertTrue(script.contains("\"type\":\"TEXT_CONTAINS\""))
        assertTrue(!script.contains("window.MantouApp.storage ="))
        assertTrue(script.contains("querySelectorAll"))
    }

    @Test
    fun runtimeHarnessInstallsStatefulStorageBeforeApplicationScripts() {
        val prepared = GeneratedAppWebViewInspector.prepareProjectHtmlForInspection(
            """
                <!DOCTYPE html><html><head><script>
                  window.cachedStorage = window.MantouApp.storage;
                </script></head><body></body></html>
            """.trimIndent()
        )

        val harnessIndex = prepared.indexOf(GeneratedAppWebViewInspector.RUNTIME_HARNESS_MARKER)
        val storageIndex = prepared.indexOf("storageSet: function")
        val applicationIndex = prepared.indexOf("window.cachedStorage")
        assertTrue(harnessIndex >= 0)
        assertTrue(storageIndex in (harnessIndex + 1) until applicationIndex)
        assertTrue(prepared.contains("storage: storage"))
        assertTrue(prepared.contains("state.resetStorage = resetStorageState"))
    }

    @Test
    fun deliverySuiteCombinesQualityAndAcceptanceGates() {
        val script = GeneratedAppHarnessScripts.deliverySuite(validAcceptanceContract())

        assertTrue(script.contains("qualityCases"))
        assertTrue(script.contains("acceptanceCases"))
        assertTrue(script.contains("new Function"))
    }

    @Test
    fun acceptanceSuiteRejectsEmptyContract() {
        val result = runCatching {
            GeneratedAppHarnessScripts.acceptanceSuite(WebAppAcceptanceContract(emptyList()))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("declare criteria"))
    }

    @Test
    fun acceptanceSuiteRejectsCriteriaWithoutRealInteraction() {
        val criterion = validAcceptanceContract().criteria.single().copy(
            actions = listOf(
                WebAppAcceptanceAction(
                    type = WebAppAcceptanceActionType.WAIT,
                    timeoutMs = 0
                )
            )
        )

        val result = runCatching {
            GeneratedAppHarnessScripts.acceptanceSuite(
                WebAppAcceptanceContract(listOf(criterion))
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("non-WAIT action"))
    }

    @Test
    fun acceptanceSuiteRejectsBlankContainsAssertions() {
        val criterion = validAcceptanceContract().criteria.single()
        val assertions = listOf(
            WebAppAcceptanceAssertion(
                type = WebAppAcceptanceAssertionType.TEXT_CONTAINS,
                target = "#items",
                value = "  "
            ),
            WebAppAcceptanceAssertion(
                type = WebAppAcceptanceAssertionType.URL_CONTAINS,
                value = ""
            )
        )

        assertions.forEach { assertion ->
            val result = runCatching {
                GeneratedAppHarnessScripts.acceptanceSuite(
                    WebAppAcceptanceContract(
                        listOf(criterion.copy(expected = listOf(assertion)))
                    )
                )
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("non-blank value"))
        }
    }

    @Test
    fun acceptanceSuiteRejectsNonNumericWaitDuration() {
        val original = validAcceptanceContract().criteria.single()
        val criterion = original.copy(
            actions = original.actions +
                WebAppAcceptanceAction(
                    type = WebAppAcceptanceActionType.WAIT,
                    value = "soon"
                )
        )

        val result = runCatching {
            GeneratedAppHarnessScripts.acceptanceSuite(
                WebAppAcceptanceContract(listOf(criterion))
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("must be an integer"))
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

    private fun validAcceptanceContract(): WebAppAcceptanceContract {
        return WebAppAcceptanceContract(
            criteria = listOf(
                WebAppAcceptanceCriterion(
                    id = "create-item",
                    title = "创建条目",
                    priority = WebAppAcceptancePriority.P0,
                    actions = listOf(
                        WebAppAcceptanceAction(
                            type = WebAppAcceptanceActionType.CLICK,
                            target = "#add-item"
                        )
                    ),
                    expected = listOf(
                        WebAppAcceptanceAssertion(
                            type = WebAppAcceptanceAssertionType.TEXT_CONTAINS,
                            target = "#items",
                            value = "新条目"
                        )
                    )
                )
            )
        )
    }
}
