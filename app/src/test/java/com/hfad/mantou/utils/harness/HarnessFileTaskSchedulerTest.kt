package com.hfad.mantou.utils.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessFileTaskSchedulerTest {

    @Test
    fun schedulesDependenciesBeforeDependentsWithStableReadyOrder() {
        val scheduled = HarnessFileTaskScheduler.schedule(
            listOf(
                HarnessFileTask("index.html", dependsOn = listOf("styles.css", "app.js")),
                HarnessFileTask("app.js", dependsOn = listOf("state.js")),
                HarnessFileTask("styles.css"),
                HarnessFileTask("state.js"),
                HarnessFileTask("README.md")
            )
        )

        assertEquals(
            listOf("styles.css", "state.js", "app.js", "index.html", "README.md"),
            scheduled.map(HarnessFileTask::path)
        )
    }

    @Test
    fun rejectsDuplicatePaths() {
        val error = runCatching {
            HarnessFileTaskScheduler.schedule(
                listOf(HarnessFileTask("app.js"), HarnessFileTask("app.js"))
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("unique"))
    }

    @Test
    fun rejectsMissingDependencies() {
        val error = runCatching {
            HarnessFileTaskScheduler.schedule(
                listOf(HarnessFileTask("index.html", dependsOn = listOf("missing.js")))
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("missing task missing.js"))
    }

    @Test
    fun rejectsCycles() {
        val error = runCatching {
            HarnessFileTaskScheduler.schedule(
                listOf(
                    HarnessFileTask("a.js", dependsOn = listOf("b.js")),
                    HarnessFileTask("b.js", dependsOn = listOf("a.js"))
                )
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("cycle"))
    }
}
