package com.hfad.mantou.service

import com.hfad.mantou.data.GenerateTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessProgressTest {

    @Test
    fun intermediateFailureRemainsRunningForRepair() {
        val progress = HarnessProgress.fromEvent(
            runId = "run-1",
            event = GenerateTaskState.HarnessEvent(
                stage = GenerateTaskState.Stage.BUILD,
                outcome = GenerateTaskState.Outcome.FAILED,
                message = "构建失败，准备修复"
            )
        )

        assertTrue(progress.isRunning)
    }

    @Test
    fun deliverOutcomeControlsTerminalStatus() {
        val passed = HarnessProgress.fromEvent(
            runId = "run-1",
            event = GenerateTaskState.HarnessEvent(
                stage = GenerateTaskState.Stage.DELIVER,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "交付完成"
            )
        )
        val failed = HarnessProgress.fromEvent(
            runId = "run-2",
            event = GenerateTaskState.HarnessEvent(
                stage = GenerateTaskState.Stage.DELIVER,
                outcome = GenerateTaskState.Outcome.FAILED,
                message = "交付失败"
            )
        )

        assertEquals(HarnessProgressStatus.SUCCEEDED, passed.status)
        assertEquals(HarnessProgressStatus.FAILED, failed.status)
    }
}
