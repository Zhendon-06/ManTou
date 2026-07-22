package com.hfad.mantou.utils

import android.content.Context
import com.hfad.mantou.tool.generated.GeneratedMantouToolsDoc
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.sqrt

internal object ToolPromptRetriever {

    internal const val MIN_SEMANTIC_SCORE = 0.66f
    private const val SEMANTIC_SCORE_WINDOW = 0.07f
    private const val MAX_SEMANTIC_TOOLS = 3
    private const val CORE_STORAGE_TOOL = "storage"
    internal val REQUIRED_TOOL_NAMES = setOf("toast", "vibration")

    private val indexMutex = Mutex()
    private var indexedCorpusKey: String? = null
    private var documentVectors: Map<String, FloatArray>? = null

    data class ToolScore(val name: String, val score: Float)

    data class Selection(
        val documents: List<GeneratedMantouToolsDoc.ToolDocument>,
        val source: String,
        val topScores: List<ToolScore>,
    )

    internal data class NameSelection(
        val names: List<String>,
        val source: String,
    )

    suspend fun select(context: Context, userMessage: String): Selection {
        val documents = GeneratedMantouToolsDoc.tools.filterNot { it.name == CORE_STORAGE_TOOL }
        if (documents.isEmpty()) return Selection(emptyList(), "empty-catalog", emptyList())

        val scores = semanticScores(context.applicationContext, userMessage, documents)
        val topScores = scores.orEmpty()
            .map { (name, score) -> ToolScore(name, score) }
            .sortedByDescending(ToolScore::score)
            .take(MAX_SEMANTIC_TOOLS)
        val nameSelection = selectNames(
            userMessage = userMessage,
            availableNames = documents.map { it.name },
            semanticScores = scores,
        )
        val selectedNames = nameSelection.names.toSet()
        return Selection(
            documents = documents.filter { it.name in selectedNames },
            source = nameSelection.source,
            topScores = topScores,
        )
    }

    internal fun selectNames(
        userMessage: String,
        availableNames: List<String>,
        semanticScores: Map<String, Float>?,
    ): NameSelection {
        val normalized = userMessage.lowercase(Locale.ROOT)
        val keywordNames = availableNames.filter { name ->
            normalized.contains(name.lowercase(Locale.ROOT)) ||
                TOOL_KEYWORDS[name].orEmpty().any(normalized::contains)
        }
        val requiredNames = availableNames.filter(REQUIRED_TOOL_NAMES::contains)

        if (semanticScores == null) {
            return if (keywordNames.isNotEmpty()) {
                NameSelection(
                    availableNames.filter { it in requiredNames || it in keywordNames },
                    "required+keyword-embedding-unavailable",
                )
            } else {
                NameSelection(availableNames, "required+fallback-all")
            }
        }

        val ranked = availableNames.mapNotNull { name ->
            semanticScores[name]?.takeIf(Float::isFinite)?.let { score -> ToolScore(name, score) }
        }.sortedByDescending(ToolScore::score)
        val topScore = ranked.firstOrNull()?.score ?: 0f
        val semanticNames = if (topScore >= MIN_SEMANTIC_SCORE) {
            ranked.asSequence()
                .filter { it.score >= MIN_SEMANTIC_SCORE && it.score >= topScore - SEMANTIC_SCORE_WINDOW }
                .take(MAX_SEMANTIC_TOOLS)
                .map(ToolScore::name)
                .toList()
        } else {
            emptyList()
        }

        val selected = availableNames.filter {
            it in requiredNames || it in keywordNames || it in semanticNames
        }
        val dynamicSource = when {
            keywordNames.isNotEmpty() && semanticNames.isNotEmpty() -> "keyword+embedding"
            keywordNames.isNotEmpty() -> "keyword"
            semanticNames.isNotEmpty() -> "embedding"
            else -> "embedding-none"
        }
        return NameSelection(selected, "required+$dynamicSource")
    }

    private suspend fun semanticScores(
        context: Context,
        userMessage: String,
        documents: List<GeneratedMantouToolsDoc.ToolDocument>,
    ): Map<String, Float>? {
        if (userMessage.isBlank()) return emptyMap()
        return indexMutex.withLock {
            val corpusKey = documents.joinToString(separator = "\u0000") { document ->
                "${document.name}\u0001${document.retrievalText}"
            }
            val activeDocumentVectors = if (indexedCorpusKey == corpusKey && documentVectors != null) {
                documentVectors
            } else {
                val vectors = LocalTextEmbedder.embedAll(
                    context,
                    documents.map(GeneratedMantouToolsDoc.ToolDocument::retrievalText),
                ) ?: return@withLock null
                documents.map(GeneratedMantouToolsDoc.ToolDocument::name)
                    .zip(vectors)
                    .toMap()
                    .also { index ->
                        indexedCorpusKey = corpusKey
                        documentVectors = index
                    }
            } ?: return@withLock null

            val queryVector = LocalTextEmbedder.embed(context, userMessage) ?: return@withLock null
            activeDocumentVectors.mapValues { (_, vector) -> cosine(queryVector, vector) }
        }
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
        val denominator = sqrt(firstNorm.toDouble()).toFloat() * sqrt(secondNorm.toDouble()).toFloat()
        return if (denominator == 0f) 0f else dot / denominator
    }

    private val TOOL_KEYWORDS = mapOf(
        "alarm" to listOf("闹钟", "倒计时", "计时器", "定时器", "叫醒", "提醒"),
        "calendar" to listOf("日历", "日程", "行程", "系统事件", "提醒"),
        "camera" to listOf("相机", "拍照", "照片", "录像", "录视频", "摄像头"),
        "clipboard" to listOf("剪贴板", "复制", "粘贴", "clipboard"),
        "flashlight" to listOf("手电筒", "闪光灯", "照明", "flashlight"),
        "toast" to listOf("toast", "原生提示", "安卓提示"),
        "vibration" to listOf("震动", "振动", "震感", "触觉反馈", "vibration"),
    )
}
