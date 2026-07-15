package com.hfad.mantou.data

data class GenerateTaskState(
    val sessionId: Long,
    val phase: Phase,
    val code: String = "",
    val filePath: String? = null,
    val status: String,
    val isModification: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean
        get() = phase != Phase.COMPLETED && phase != Phase.ERROR

    val languageLabel: String
        get() = if (isModification && phase != Phase.COMPLETED) "DIFF" else "HTML"

    enum class Phase {
        PREPARING,
        WRITING_INITIAL,
        WRITING_DIFF,
        APPLYING_DIFF,
        COMPLETED,
        ERROR
    }
}
