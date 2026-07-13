package com.hfad.mantou.utils

import kotlin.math.sqrt

internal object PrototypeIntentScorer {

    internal const val TOP_K = 3
    private const val GENERATE_THRESHOLD = 0.72f
    private const val CHAT_THRESHOLD = 0.70f
    private const val GENERATE_MARGIN_THRESHOLD = 0.035f
    private const val CHAT_MARGIN_THRESHOLD = 0.025f

    enum class Decision {
        GenerateApp,
        Chat,
        Uncertain,
    }

    data class Result(
        val decision: Decision,
        val generateScore: Float,
        val chatScore: Float,
        val margin: Float,
    )

    fun score(
        query: FloatArray,
        generateVectors: List<FloatArray>,
        chatVectors: List<FloatArray>,
    ): Result {
        val generateScore = averageTopKSimilarity(query, generateVectors)
        val chatScore = averageTopKSimilarity(query, chatVectors)
        return decide(generateScore, chatScore)
    }

    internal fun decide(generateScore: Float, chatScore: Float): Result {
        val margin = generateScore - chatScore
        val decision = when {
            generateScore >= GENERATE_THRESHOLD &&
                margin >= GENERATE_MARGIN_THRESHOLD -> Decision.GenerateApp
            chatScore >= CHAT_THRESHOLD &&
                -margin >= CHAT_MARGIN_THRESHOLD -> Decision.Chat
            else -> Decision.Uncertain
        }
        return Result(decision, generateScore, chatScore, margin)
    }

    internal fun averageTopKSimilarity(
        query: FloatArray,
        vectors: List<FloatArray>,
    ): Float {
        if (query.isEmpty() || vectors.isEmpty()) return 0f
        val scores = vectors.asSequence()
            .map { cosine(query, it) }
            .filter { it.isFinite() }
            .sortedDescending()
            .take(TOP_K)
            .toList()
        return if (scores.isEmpty()) 0f else scores.average().toFloat()
    }

    private fun cosine(first: FloatArray, second: FloatArray): Float {
        if (first.isEmpty() || first.size != second.size) return 0f
        var dot = 0f
        var firstNorm = 0f
        var secondNorm = 0f
        for (index in first.indices) {
            dot += first[index] * second[index]
            firstNorm += first[index] * first[index]
            secondNorm += second[index] * second[index]
        }
        val denominator = sqrt(firstNorm.toDouble()).toFloat() *
            sqrt(secondNorm.toDouble()).toFloat()
        return if (denominator == 0f) 0f else dot / denominator
    }
}
