package com.hfad.mantou.utils.harness

import com.hfad.mantou.utils.GenerationInputFilter

data class HarnessRunRequest(
    val runId: String,
    val workspacePath: String,
    val systemPrompt: String,
    val userInput: String,
    val artifactPath: String? = null,
    val selfTestScript: String? = null,
    val testSuiteScript: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class HarnessPreparedPrompt(
    val systemPrompt: String,
    val userPrompt: String,
    val filteredUserInput: String,
    val notices: List<String> = emptyList()
)

fun interface HarnessPromptFilter {
    fun prepare(systemPrompt: String, userInput: String): HarnessPreparedPrompt
}

object DefaultHarnessPromptFilter : HarnessPromptFilter {

    const val MAX_SYSTEM_PROMPT_CHARS = GenerationInputFilter.MAX_SYSTEM_PROMPT_CHARS

    override fun prepare(systemPrompt: String, userInput: String): HarnessPreparedPrompt {
        val userResult = GenerationInputFilter.filter(userInput)
        val systemResult = GenerationInputFilter.filterSystemPrompt(systemPrompt)
        return HarnessPreparedPrompt(
            systemPrompt = systemResult.content,
            userPrompt = userResult.promptPayload,
            filteredUserInput = userResult.content,
            notices = userResult.notices + systemResult.notices
        )
    }
}

enum class HarnessMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

data class HarnessMessage(
    val role: HarnessMessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<HarnessToolCall> = emptyList()
)

enum class HarnessModelPurpose {
    INITIAL,
    TOOL_FOLLOW_UP,
    REPAIR
}

data class HarnessModelRequest(
    val runId: String,
    val purpose: HarnessModelPurpose,
    val iteration: Int,
    val messages: List<HarnessMessage>,
    val diagnostics: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class HarnessModelTurn(
    val content: String = "",
    val toolCalls: List<HarnessToolCall> = emptyList()
)

fun interface HarnessModelRepair {
    suspend fun requestTurn(request: HarnessModelRequest): HarnessModelTurn
}

data class HarnessToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

data class HarnessToolRequest(
    val runId: String,
    val workspacePath: String,
    val iteration: Int,
    val call: HarnessToolCall,
    val metadata: Map<String, String> = emptyMap()
)

data class HarnessToolResult(
    val callId: String,
    val success: Boolean,
    val output: String,
    val diagnostics: List<String> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val artifactPath: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

fun interface HarnessFileTool {
    suspend fun execute(request: HarnessToolRequest): HarnessToolResult
}

enum class HarnessCheckKind {
    DEVELOPMENT_BUILD,
    RUNTIME,
    SELF_TEST,
    FINAL_BUILD,
    TEST_SUITE
}

data class HarnessCheckRequest(
    val runId: String,
    val workspacePath: String,
    val artifactPath: String?,
    val kind: HarnessCheckKind,
    val iteration: Int,
    val testScript: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class HarnessCheckResult(
    val passed: Boolean,
    val summary: String,
    val diagnostics: List<String> = emptyList(),
    val artifactPath: String? = null,
    val durationMs: Long = 0L
)

fun interface HarnessBuilder {
    suspend fun build(request: HarnessCheckRequest): HarnessCheckResult
}

fun interface HarnessInspector {
    suspend fun inspect(request: HarnessCheckRequest): HarnessCheckResult
}

fun interface HarnessTestRunner {
    suspend fun runTests(request: HarnessCheckRequest): HarnessCheckResult
}

data class HarnessLimits(
    val maxCodeIterations: Int = 8,
    val maxSelfTestFailuresBeforeBypass: Int = 2,
    val maxModelRequestRetries: Int = 2,
    val modelRetryBaseDelayMs: Long = 600,
    val modelRetryMaxDelayMs: Long = 2_400,
    val maxDiagnostics: Int = 40,
    val maxDiagnosticChars: Int = 24_000
) {
    init {
        require(maxCodeIterations > 0)
        require(maxSelfTestFailuresBeforeBypass > 0)
        require(maxModelRequestRetries in 0..MAX_MODEL_REQUEST_RETRIES)
        require(modelRetryBaseDelayMs >= 0)
        require(modelRetryMaxDelayMs >= modelRetryBaseDelayMs)
        require(maxDiagnostics > 0)
        require(maxDiagnosticChars > 0)
    }

    private companion object {
        const val MAX_MODEL_REQUEST_RETRIES = 10
    }
}

sealed class HarnessRunResult {
    abstract val runId: String
    abstract val iterations: Int
    abstract val artifactPath: String?

    data class Delivered(
        override val runId: String,
        override val iterations: Int,
        override val artifactPath: String?,
        val filteredUserInput: String,
        val selfTestBypassed: Boolean = false
    ) : HarnessRunResult()

    data class Failed(
        override val runId: String,
        override val iterations: Int,
        override val artifactPath: String?,
        val reason: String,
        val diagnostics: List<String> = emptyList()
    ) : HarnessRunResult()
}
