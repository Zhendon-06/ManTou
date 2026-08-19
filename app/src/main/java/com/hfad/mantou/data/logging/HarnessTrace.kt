package com.hfad.mantou.data.logging

enum class HarnessTraceStatus {
    STARTED,
    PROGRESS,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

data class HarnessTraceEvent(
    val runId: String,
    val component: String,
    val operation: String,
    val status: HarnessTraceStatus,
    val message: String,
    val iteration: Int? = null,
    val durationMs: Long? = null,
    val details: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis()
)

fun interface HarnessTraceLogger {
    fun log(event: HarnessTraceEvent)
}

fun HarnessTraceLogger.record(
    runId: String,
    component: String,
    operation: String,
    status: HarnessTraceStatus,
    message: String,
    iteration: Int? = null,
    durationMs: Long? = null,
    details: Map<String, String> = emptyMap()
) {
    runCatching {
        log(
            HarnessTraceEvent(
                runId = runId,
                component = component,
                operation = operation,
                status = status,
                message = message,
                iteration = iteration,
                durationMs = durationMs,
                details = details
            )
        )
    }
}

fun elapsedMillisSince(startedAtNanos: Long): Long {
    return ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
}
