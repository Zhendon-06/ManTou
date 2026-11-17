package com.hfad.mantou.utils.harness

import org.junit.Assert.*
import org.junit.Test

class HarnessIntensityTest {
    @Test fun increasingEffortNeverReducesBudgetOrCoverage() {
        HarnessIntensity.entries.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher.iterations > lower.iterations)
            assertTrue(higher.turns > lower.turns)
            assertTrue(higher.totalTurns > lower.totalTurns)
            val base = WebQualityGateContract()
            assertTrue(higher.qualityGate(base).requiredViewports.containsAll(lower.qualityGate(base).requiredViewports))
        }
    }

    @Test fun everyLevelKeepsCoreQualityEvidence() {
        HarnessIntensity.entries.forEach {
            val gate = it.qualityGate(WebQualityGateContract())
            assertTrue(gate.requireScreenshotEvidence)
            assertTrue(gate.requireOfflineDependencies)
            assertTrue(gate.requiredViewports.containsAll(WebQualityGateContract.DEFAULT_MOBILE_VIEWPORTS))
        }
    }

    @Test fun highEffortCannotBypassSelfTests() {
        assertFalse(HarnessIntensity.HIGH.limits().allowSelfTestBypass)
        assertFalse(HarnessIntensity.EXTRA_HIGH.limits().allowSelfTestBypass)
        assertEquals(HarnessIntensity.HIGH, HarnessIntensity.fromStored("unknown"))
    }
}
