package com.hfad.mantou.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPromptRetrieverTest {

    private val names = listOf("alarm", "calendar", "camera", "clipboard", "flashlight", "toast", "vibration")

    @Test
    fun keywordMatchIsKeptWhenEmbeddingIsUnavailable() {
        val result = ToolPromptRetriever.selectNames("做一个拍照记录应用", names, null)

        assertEquals(listOf("camera", "toast", "vibration"), result.names)
        assertEquals("required+keyword-embedding-unavailable", result.source)
    }

    @Test
    fun unavailableEmbeddingFallsBackToFullCatalogWithoutKeyword() {
        val result = ToolPromptRetriever.selectNames("做一个记账应用", names, null)

        assertEquals(names, result.names)
        assertEquals("required+fallback-all", result.source)
    }

    @Test
    fun semanticSelectionKeepsOnlyCloseHighScoringTools() {
        val result = ToolPromptRetriever.selectNames(
            userMessage = "做一个户外求救工具",
            availableNames = names,
            semanticScores = mapOf(
                "alarm" to 0.55f,
                "calendar" to 0.48f,
                "camera" to 0.67f,
                "clipboard" to 0.50f,
                "flashlight" to 0.76f,
                "toast" to 0.58f,
                "vibration" to 0.71f,
            ),
        )

        assertEquals(listOf("flashlight", "toast", "vibration"), result.names)
        assertEquals("required+embedding", result.source)
    }

    @Test
    fun lowSemanticScoresDoNotInjectOptionalTools() {
        val result = ToolPromptRetriever.selectNames(
            userMessage = "做一个记账应用",
            availableNames = names,
            semanticScores = names.associateWith { 0.52f },
        )

        assertEquals(listOf("toast", "vibration"), result.names)
        assertEquals("required+embedding-none", result.source)
    }
}
