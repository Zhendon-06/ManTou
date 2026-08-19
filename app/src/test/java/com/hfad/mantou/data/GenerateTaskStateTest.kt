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

    @Test
    fun pairedRunningHarnessEventsAreHiddenFromVisibleHistory() {
        val buildRunning = GenerateTaskState.HarnessEvent(
            stage = GenerateTaskState.Stage.BUILD,
            outcome = GenerateTaskState.Outcome.RUNNING,
            message = "正在构建"
        )
        val buildPassed = GenerateTaskState.HarnessEvent(
            stage = GenerateTaskState.Stage.BUILD,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "构建通过"
        )
        val inspectRunning = GenerateTaskState.HarnessEvent(
            stage = GenerateTaskState.Stage.INSPECT,
            outcome = GenerateTaskState.Outcome.RUNNING,
            message = "正在检查"
        )
        val running = GenerateTaskState(
            sessionId = 1L,
            phase = GenerateTaskState.Phase.INSPECTING,
            status = "正在检查",
            harnessEvents = listOf(buildRunning, buildPassed, inspectRunning)
        )

        assertEquals(listOf(buildPassed, inspectRunning), running.visibleHarnessEvents())
        assertEquals(inspectRunning, running.activeHarnessEvent())
        assertEquals(null, running.copy(phase = GenerateTaskState.Phase.COMPLETED).activeHarnessEvent())
    }

    @Test
    fun runningEventsOnlyPairWithinSameStageAndIteration() {
        val firstIterationRunning = GenerateTaskState.HarnessEvent(
            stage = GenerateTaskState.Stage.BUILD,
            outcome = GenerateTaskState.Outcome.RUNNING,
            message = "第一轮构建中",
            iteration = 1
        )
        val secondIterationPassed = GenerateTaskState.HarnessEvent(
            stage = GenerateTaskState.Stage.BUILD,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "第二轮构建通过",
            iteration = 2
        )
        val state = GenerateTaskState(
            sessionId = 1L,
            phase = GenerateTaskState.Phase.COMPLETED,
            status = "完成",
            harnessEvents = listOf(firstIterationRunning, secondIterationPassed)
        )

        assertEquals(listOf(firstIterationRunning, secondIterationPassed), state.visibleHarnessEvents())
    }
}
