package com.hfad.mantou.utils

/**
 * Keeps model-facing app requirements inside a well-defined data boundary.
 */
object GenerationInputFilter {

    const val MAX_INPUT_CHARS = 24_000
    const val MAX_SYSTEM_PROMPT_CHARS = 128_000

    data class TextResult(
        val content: String,
        val notices: List<String>
    )

    data class Result(
        val content: String,
        val promptPayload: String,
        val notices: List<String>
    )

    fun filter(rawInput: String): Result {
        val notices = mutableListOf<String>()
        var normalized = normalize(rawInput)

        if (normalized.length > MAX_INPUT_CHARS) {
            normalized = normalized.take(MAX_INPUT_CHARS).trimEnd()
            notices += "输入超过 $MAX_INPUT_CHARS 字符，已截断到安全上下文范围"
        }
        if (normalized.isBlank()) {
            normalized = "生成一个可直接运行的移动端网页应用"
            notices += "输入为空，已使用安全的默认应用需求"
        }

        val promptPayload = buildString(normalized.length + 160) {
            append("<user_requirement>\n")
            append(escapeXml(normalized))
            append("\n</user_requirement>")
        }
        return Result(
            content = normalized,
            promptPayload = promptPayload,
            notices = notices
        )
    }

    fun filterSystemPrompt(rawPrompt: String): TextResult {
        val notices = mutableListOf<String>()
        var normalized = normalize(rawPrompt)
        if (normalized.length > MAX_SYSTEM_PROMPT_CHARS) {
            normalized = normalized.take(MAX_SYSTEM_PROMPT_CHARS).trimEnd()
            notices += "System Prompt 超过 $MAX_SYSTEM_PROMPT_CHARS 字符，已截断到安全上下文范围"
        }
        require(normalized.isNotBlank()) { "System Prompt 不能为空" }
        return TextResult(normalized, notices)
    }

    internal fun escapeXml(value: String): String {
        return buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(character)
                }
            }
        }
    }

    private fun normalize(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { character ->
                character == '\n' || character == '\t' || !character.isISOControl()
            }
            .trim()
    }
}
