package com.hfad.mantou.data

data class GenerateTaskState(
    val sessionId: Long,
    val phase: Phase,
    val code: String = "",
    val filePath: String? = null,
    val activeFilePath: String? = null,
    val projectFiles: List<String> = emptyList(),
    val status: String,
    val isModification: Boolean = false,
    val harnessIteration: Int = 0,
    val harnessEvents: List<HarnessEvent> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isRunning: Boolean
        get() = phase != Phase.COMPLETED && phase != Phase.ERROR

    val languageLabel: String
        get() = when {
            phase == Phase.SANITIZING || phase == Phase.PROMPTING -> "INPUT"
            phase == Phase.BUILDING || phase == Phase.INSPECTING || phase == Phase.TESTING -> "CHECK"
            isModification && phase != Phase.COMPLETED -> "DIFF"
            else -> activeFilePath
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.takeIf(String::isNotBlank)
                ?.uppercase()
                ?: "HTML"
        }

    val phaseTitle: String
        get() = when (phase) {
            Phase.SANITIZING -> "正在过滤输入"
            Phase.PROMPTING -> "正在组装提示词"
            Phase.PREPARING -> "正在准备代码"
            Phase.REQUESTING_MODEL -> "正在请求模型"
            Phase.WRITING_INITIAL -> "正在写入 HTML"
            Phase.WRITING_DIFF -> "正在写入 DIFF"
            Phase.APPLYING_DIFF, Phase.APPLYING_TOOL -> "正在调用本地代码工具"
            Phase.BUILDING -> "正在构建应用"
            Phase.INSPECTING -> "正在读取运行错误"
            Phase.SELF_TESTING -> "正在运行应用自测"
            Phase.TESTING -> "正在运行测试集"
            Phase.REPAIRING -> "正在自动修复"
            Phase.COMPLETED -> if (isModification) "应用源码已更新" else "应用已通过验证"
            Phase.ERROR -> "生成任务出错"
        }

    fun appendHarnessEvent(event: HarnessEvent): GenerateTaskState {
        return copy(
            harnessIteration = maxOf(harnessIteration, event.iteration),
            harnessEvents = (harnessEvents + event).takeLast(MAX_HARNESS_EVENTS),
            diagnostics = event.diagnostics,
            updatedAt = event.timestamp
        )
    }

    fun visibleHarnessEvents(): List<HarnessEvent> {
        return harnessEvents.filterIndexed { index, event ->
            event.outcome != Outcome.RUNNING || harnessEvents
                .asSequence()
                .drop(index + 1)
                .none { later ->
                    later.stage == event.stage &&
                        later.iteration == event.iteration &&
                        later.outcome != Outcome.RUNNING
                }
        }
    }

    fun activeHarnessEvent(): HarnessEvent? {
        if (!isRunning) return null
        return visibleHarnessEvents().lastOrNull()?.takeIf { it.outcome == Outcome.RUNNING }
    }

    enum class Phase {
        SANITIZING,
        PROMPTING,
        PREPARING,
        REQUESTING_MODEL,
        WRITING_INITIAL,
        WRITING_DIFF,
        APPLYING_DIFF,
        APPLYING_TOOL,
        BUILDING,
        INSPECTING,
        SELF_TESTING,
        TESTING,
        REPAIRING,
        COMPLETED,
        ERROR
    }

    data class HarnessEvent(
        val stage: Stage,
        val outcome: Outcome,
        val message: String,
        val iteration: Int = 0,
        val operation: String? = null,
        val diagnostics: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class Stage {
        INPUT,
        PROMPT,
        MODEL,
        TOOL,
        BUILD,
        INSPECT,
        SELF_TEST,
        TEST,
        DELIVER
    }

    enum class Outcome {
        RUNNING,
        PASSED,
        FAILED,
        RETRYING
    }

    private companion object {
        const val MAX_HARNESS_EVENTS = 80
    }
}
