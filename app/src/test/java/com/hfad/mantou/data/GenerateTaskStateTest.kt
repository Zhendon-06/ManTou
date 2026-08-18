package com.hfad.mantou.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateTaskStateTest {

    @Test
    fun harnessEventsAreBoundedAndCarryLatestDiagnostics() {
        var state = GenerateTaskState(
            sessionId = 1L,
            phase = GenerateTaskState.Phase.BUILDING,
            status = "构建中"
        )
        repeat(85) { index ->
            state = state.appendHarnessEvent(
                GenerateTaskState.HarnessEvent(
                    stage = GenerateTaskState.Stage.BUILD,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "event-$index",
                    iteration = index,
                    diagnostics = listOf("diagnostic-$index")
                )
            )
        }

        assertEquals(80, state.harnessEvents.size)
        assertEquals("event-5", state.harnessEvents.first().message)
        assertEquals(listOf("diagnostic-84"), state.diagnostics)
        assertEquals(84, state.harnessIteration)
    }

    @Test
    fun buildAndTestPhasesRemainRunning() {
        val building = GenerateTaskState(1L, GenerateTaskState.Phase.BUILDING, status = "构建")
        val completed = building.copy(phase = GenerateTaskState.Phase.COMPLETED)

        assertTrue(building.isRunning)
        assertFalse(completed.isRunning)
        assertEquals("CHECK", building.languageLabel)
    }
}
