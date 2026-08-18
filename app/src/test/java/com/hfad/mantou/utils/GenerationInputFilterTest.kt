package com.hfad.mantou.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationInputFilterTest {

    @Test
    fun filter_normalizesControlCharactersAndProtectsPromptBoundary() {
        val result = GenerationInputFilter.filter("  做一个\u0000 <script>应用</script>\r\n  ")

        assertEquals("做一个 <script>应用</script>", result.content)
        assertTrue(result.promptPayload.startsWith("<user_requirement>\n"))
        assertTrue(result.promptPayload.contains("&lt;script&gt;应用&lt;/script&gt;"))
        assertTrue(result.promptPayload.endsWith("\n</user_requirement>"))
        assertFalse(result.promptPayload.contains('\u0000'))
    }

    @Test
    fun filter_truncatesOversizedInput() {
        val result = GenerationInputFilter.filter("a".repeat(GenerationInputFilter.MAX_INPUT_CHARS + 20))

        assertEquals(GenerationInputFilter.MAX_INPUT_CHARS, result.content.length)
        assertTrue(result.notices.single().contains("已截断"))
    }

    @Test
    fun filter_suppliesDefaultForBlankInput() {
        val result = GenerationInputFilter.filter(" \n\t ")

        assertTrue(result.content.isNotBlank())
        assertTrue(result.notices.single().contains("输入为空"))
    }

    @Test
    fun filterSystemPromptNormalizesAndTruncatesTrustedInstructions() {
        val result = GenerationInputFilter.filterSystemPrompt(
            " system\u0000\r\n" + "x".repeat(GenerationInputFilter.MAX_SYSTEM_PROMPT_CHARS)
        )

        assertEquals(GenerationInputFilter.MAX_SYSTEM_PROMPT_CHARS, result.content.length)
        assertFalse(result.content.contains('\u0000'))
        assertTrue(result.notices.single().contains("System Prompt"))
    }
}
