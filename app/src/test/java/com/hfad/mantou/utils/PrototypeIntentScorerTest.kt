package com.hfad.mantou.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PrototypeIntentScorerTest {

    @Test
    fun confidentGenerateScoreReturnsGenerateApp() {
        val result = PrototypeIntentScorer.decide(generateScore = 0.82f, chatScore = 0.76f)

        assertEquals(PrototypeIntentScorer.Decision.GenerateApp, result.decision)
    }

    @Test
    fun confidentChatScoreReturnsChat() {
        val result = PrototypeIntentScorer.decide(generateScore = 0.72f, chatScore = 0.78f)

        assertEquals(PrototypeIntentScorer.Decision.Chat, result.decision)
    }

    @Test
    fun lowConfidenceOrSmallMarginReturnsUncertain() {
        assertEquals(
            PrototypeIntentScorer.Decision.Uncertain,
            PrototypeIntentScorer.decide(generateScore = 0.68f, chatScore = 0.67f).decision,
        )
        assertEquals(
            PrototypeIntentScorer.Decision.Uncertain,
            PrototypeIntentScorer.decide(generateScore = 0.80f, chatScore = 0.78f).decision,
        )
    }

    @Test
    fun similarityUsesThreeNearestPrototypes() {
        val query = floatArrayOf(1f, 0f)
        val vectors = listOf(
            floatArrayOf(1f, 0f),
            floatArrayOf(0.8f, 0.6f),
            floatArrayOf(0.6f, 0.8f),
            floatArrayOf(0f, 1f),
        )

        assertEquals(
            0.8f,
            PrototypeIntentScorer.averageTopKSimilarity(query, vectors),
            0.0001f,
        )
    }
}
