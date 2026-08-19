package com.hfad.mantou.data.logging

import java.util.concurrent.atomic.AtomicLong

data class ApiDiagnosticContext(
    val runId: String? = null,
    val operation: String? = null,
    val iteration: Int? = null
)

data class ApiRequestTrace(
    val id: String,
    val startedAtMs: Long,
    val context: ApiDiagnosticContext?
) {
    companion object {
        private val sequence = AtomicLong()

        fun create(
            context: ApiDiagnosticContext? = null,
            nowMs: Long = System.currentTimeMillis()
        ): ApiRequestTrace {
            val timestamp = java.lang.Long.toString(nowMs, 36)
            val suffix = java.lang.Long.toString(sequence.incrementAndGet(), 36)
            return ApiRequestTrace(
                id = "mt-$timestamp-$suffix",
                startedAtMs = nowMs,
                context = context
            )
        }
    }
}
