package com.hfad.mantou.utils.harness

import com.hfad.mantou.utils.project.WebAppAcceptanceContract
import com.hfad.mantou.utils.project.WebAppAcceptanceCriterion
import com.hfad.mantou.utils.project.WebAppAcceptancePriority
import com.hfad.mantou.utils.project.WebAppDesignSpec
import com.hfad.mantou.utils.project.WebAppProjectFile
import com.hfad.mantou.utils.project.WebAppProjectFileRole
import com.hfad.mantou.utils.project.WebAppProjectManifest
import com.hfad.mantou.utils.project.WebAppScreenSpec
import com.hfad.mantou.utils.project.WebAppSpec
import com.hfad.mantou.utils.project.WebAppStatePersistence
import com.hfad.mantou.utils.project.WebAppStateSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTaskComplexityTest {
    @Test
    fun plannedScreensAndRelevantDeviceCapabilitiesDetermineComplexity() {
        val simple = manifest(screenCount = 1)
        assertEquals(HarnessTaskComplexity.Level.SIMPLE, HarnessTaskComplexity.assess(simple, emptySet()).level)
        assertEquals(HarnessTaskComplexity.Level.STANDARD, HarnessTaskComplexity.assess(simple, setOf("camera")).level)
        val combined = HarnessTaskComplexity.assess(simple, setOf("camera", "photoPicker"))
        assertEquals(HarnessTaskComplexity.Level.COMPLEX, combined.level)
        assertTrue(combined.evidence.contains("2 项相关 Android 能力"))
        assertEquals(
            HarnessTaskComplexity.Level.COMPLEX,
            HarnessTaskComplexity.assess(manifest(screenCount = 3), emptySet()).level
        )
    }

    @Test
    fun unplannedEditUsesRegularScope() {
        assertEquals(
            HarnessTaskComplexity.Level.STANDARD,
            HarnessTaskComplexity.assess(null, emptySet()).level
        )
    }

    @Test
    fun validationProfilesKeepCoreGatesAndDoNotControlModelBudgets() {
        val complexity = HarnessTaskComplexity.assess(manifest(screenCount = 1), emptySet())
        val regular = complexity.limits(HarnessValidationProfile.PROJECT)
        val strict = complexity.limits(HarnessValidationProfile.TABLET_ALL)
        assertEquals(regular.copy(allowSelfTestBypass = false), strict)
        assertTrue(regular.allowSelfTestBypass)
        assertFalse(strict.allowSelfTestBypass)

        HarnessValidationProfile.entries.forEach { profile ->
            val gate = profile.qualityGate(WebQualityGateContract())
            assertTrue(gate.requireScreenshotEvidence)
            assertTrue(gate.requireOfflineDependencies)
            assertTrue(gate.requiredViewports.containsAll(WebQualityGateContract.DEFAULT_MOBILE_VIEWPORTS))
        }
    }

    @Test
    fun legacySettingsPreserveValidationChoiceAndNewSelectionWins() {
        assertEquals(HarnessValidationProfile.PROJECT, HarnessValidationProfile.fromStored(null, "LOW"))
        assertEquals(HarnessValidationProfile.TABLET_PORTRAIT, HarnessValidationProfile.fromStored(null, "HIGH"))
        assertEquals(HarnessValidationProfile.TABLET_ALL, HarnessValidationProfile.fromStored(null, "EXTRA_HIGH"))
        assertEquals(HarnessValidationProfile.PROJECT, HarnessValidationProfile.fromStored("PROJECT", "HIGH"))
        assertEquals(HarnessValidationProfile.PROJECT, HarnessValidationProfile.fromStored(null, null))
    }

    private fun manifest(screenCount: Int) = WebAppProjectManifest(
        projectId = "sample",
        displayName = "记录工具",
        files = listOf(WebAppProjectFile("app.js", WebAppProjectFileRole.SCRIPT)),
        appSpec = WebAppSpec(
            summary = "记录",
            primaryGoal = "保存一条记录",
            selectors = emptyList(),
            screens = List(screenCount) { index -> WebAppScreenSpec("screen-$index", "记录", "显示内容", emptyList()) },
            state = WebAppStateSpec(persistence = WebAppStatePersistence.MEMORY, emptyState = "暂无记录"),
            interactions = emptyList(),
            userFlows = emptyList(),
            design = WebAppDesignSpec("工具", emptyList(), emptyList()),
            acceptanceContract = WebAppAcceptanceContract(
                listOf(WebAppAcceptanceCriterion("AC-001", "保存记录", WebAppAcceptancePriority.P0, expected = emptyList()))
            )
        )
    )
}
