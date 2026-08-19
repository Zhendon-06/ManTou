package com.hfad.mantou.service

import com.hfad.mantou.data.GenerateTaskState

enum class HarnessProgressStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

data class HarnessServiceRequest(
    val runId: String,
    val sessionId: Long? = null,
    val title: String = "ManTou Harness",
    val initialMessage: String = "正在启动 Harness",
    val initialStage: String = "准备中"
) {
    init {
        require(runId.isNotBlank()) { "runId cannot be blank" }
    }
}

data class HarnessProgress(
    val runId: String,
    val sessionId: Long? = null,
    val title: String = "ManTou Harness",
    val message: String = "正在运行 Harness",
    val stage: String? = null,
    val operation: String? = null,
    val iteration: Int = 0,
    val progress: Int? = null,
    val status: HarnessProgressStatus = HarnessProgressStatus.RUNNING,
    val diagnostics: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean
        get() = status == HarnessProgressStatus.RUNNING

    val isTerminal: Boolean
        get() = !isRunning

    fun withRunId(expectedRunId: String): HarnessProgress {
        return if (runId == expectedRunId) this else copy(runId = expectedRunId)
    }

    companion object {
        fun initial(request: HarnessServiceRequest): HarnessProgress {
            return HarnessProgress(
                runId = request.runId,
                sessionId = request.sessionId,
                title = request.title,
                message = request.initialMessage,
                stage = request.initialStage
            )
        }

        fun fromEvent(
            runId: String,
            event: GenerateTaskState.HarnessEvent,
            sessionId: Long? = null,
            title: String = "ManTou Harness"
        ): HarnessProgress {
            return HarnessProgress(
                runId = runId,
                sessionId = sessionId,
                title = title,
                message = event.message,
                stage = event.stage.name,
                operation = event.operation,
                iteration = event.iteration,
                status = when {
                    event.stage == GenerateTaskState.Stage.DELIVER &&
                        event.outcome == GenerateTaskState.Outcome.PASSED -> {
                        HarnessProgressStatus.SUCCEEDED
                    }
                    event.stage == GenerateTaskState.Stage.DELIVER &&
                        event.outcome == GenerateTaskState.Outcome.FAILED -> {
                        HarnessProgressStatus.FAILED
                    }
                    else -> HarnessProgressStatus.RUNNING
                },
                diagnostics = event.diagnostics,
                updatedAt = event.timestamp
            )
        }
    }
}

class HarnessProgressReporter internal constructor(
    private val runId: String,
    private val sessionId: Long?,
    private val title: String
) {

    fun report(
        message: String,
        stage: String? = null,
        operation: String? = null,
        iteration: Int = 0,
        progress: Int? = null,
        diagnostics: List<String> = emptyList()
    ) {
        HarnessForegroundServiceRuntime.update(
            HarnessProgress(
                runId = runId,
                sessionId = sessionId,
                title = title,
                message = message,
                stage = stage,
                operation = operation,
                iteration = iteration,
                progress = progress,
                diagnostics = diagnostics
            )
        )
    }

    fun report(event: GenerateTaskState.HarnessEvent) {
        HarnessForegroundServiceRuntime.update(
            HarnessProgress.fromEvent(
                runId = runId,
                event = event,
                sessionId = sessionId,
                title = title
            )
        )
    }

    fun succeed(message: String = "Harness 已完成", diagnostics: List<String> = emptyList()) {
        HarnessForegroundServiceRuntime.update(
            HarnessProgress(
                runId = runId,
                sessionId = sessionId,
                title = title,
                message = message,
                status = HarnessProgressStatus.SUCCEEDED,
                diagnostics = diagnostics
            )
        )
    }

    fun fail(message: String, diagnostics: List<String> = emptyList()) {
        HarnessForegroundServiceRuntime.update(
            HarnessProgress(
                runId = runId,
                sessionId = sessionId,
                title = title,
                message = message,
                status = HarnessProgressStatus.FAILED,
                diagnostics = diagnostics
            )
        )
    }

    fun cancel(message: String = "Harness 已取消") {
        HarnessForegroundServiceRuntime.update(
            HarnessProgress(
                runId = runId,
                sessionId = sessionId,
                title = title,
                message = message,
                status = HarnessProgressStatus.CANCELLED
            )
        )
    }
}
