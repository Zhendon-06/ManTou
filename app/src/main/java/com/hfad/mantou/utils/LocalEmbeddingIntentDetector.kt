package com.hfad.mantou.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** 本地 m3e-small embedding 意图识别。模型或索引不可用时返回 Uncertain。 */
object LocalEmbeddingIntentDetector {

    private const val TAG = "LocalEmbeddingIntent"
    private const val VECTOR_INDEX_ASSET = "embedding/m3e-small/intent_vectors.json"
    private const val VECTOR_INDEX_SCHEMA_VERSION = 1

    private val indexMutex = Mutex()
    private var vectorIndex: VectorIndexAsset? = null
    private var indexLoadFailed = false

    enum class Decision {
        GenerateApp,
        Chat,
        Uncertain
    }

    suspend fun detect(context: Context, userMessage: String): Decision = withContext(Dispatchers.IO) {
        if (userMessage.isBlank()) return@withContext Decision.Chat

        val startedAt = System.currentTimeMillis()
        val index = ensureIndex(context)
        val queryVector = LocalTextEmbedder.embed(context, userMessage)
        if (index == null || queryVector == null) {
            Log.d(TAG, "detect result=Uncertain assetsUnavailable=true elapsed=${System.currentTimeMillis() - startedAt}ms")
            return@withContext Decision.Uncertain
        }

        val result = PrototypeIntentScorer.score(
            queryVector,
            index.generateApp.map(VectorRecord::vector),
            index.chat.map(VectorRecord::vector),
        )
        Log.d(
            TAG,
            "scores generate=${formatScore(result.generateScore)} " +
                "chat=${formatScore(result.chatScore)} margin=${formatScore(result.margin)}"
        )
        val decision = when (result.decision) {
            PrototypeIntentScorer.Decision.GenerateApp -> Decision.GenerateApp
            PrototypeIntentScorer.Decision.Chat -> Decision.Chat
            PrototypeIntentScorer.Decision.Uncertain -> Decision.Uncertain
        }
        Log.d(TAG, "detect result=$decision elapsed=${System.currentTimeMillis() - startedAt}ms")
        decision
    }

    private suspend fun ensureIndex(context: Context): VectorIndexAsset? {
        if (indexLoadFailed) return null
        vectorIndex?.let { return it }

        return indexMutex.withLock {
            vectorIndex?.let { return@withLock it }
            if (indexLoadFailed) return@withLock null

            val loaded = runCatching { loadVectorIndex(context.applicationContext) }
                .onFailure { error ->
                    Log.d(TAG, "index load error=${error.javaClass.simpleName}:${error.message.orEmpty()}")
                }
                .getOrNull()
            if (loaded == null) {
                indexLoadFailed = true
            } else {
                vectorIndex = loaded
            }
            loaded
        }
    }

    private fun loadVectorIndex(context: Context): VectorIndexAsset {
        val index = context.assets.open(VECTOR_INDEX_ASSET).bufferedReader().use { reader ->
            Gson().fromJson(reader, VectorIndexAsset::class.java)
        }
        require(index.schemaVersion == VECTOR_INDEX_SCHEMA_VERSION) {
            "Unsupported intent vector schema ${index.schemaVersion}"
        }
        require(index.model == LocalTextEmbedder.MODEL_ID) {
            "Intent vector model ${index.model} does not match ${LocalTextEmbedder.MODEL_ID}"
        }
        require(index.modelRevision == LocalTextEmbedder.MODEL_REVISION) {
            "Intent vector revision ${index.modelRevision} does not match model assets"
        }
        require(index.dimension > 0) { "Intent vector dimension must be positive" }
        validateVectors("generate_app", index.generateApp, index.dimension)
        validateVectors("chat", index.chat, index.dimension)
        return index
    }

    private fun validateVectors(intent: String, records: List<VectorRecord>, dimension: Int) {
        require(records.size >= PrototypeIntentScorer.TOP_K) {
            "Intent index needs at least ${PrototypeIntentScorer.TOP_K} $intent vectors"
        }
        records.forEach { record ->
            require(record.text.isNotBlank() && record.category.isNotBlank()) {
                "Intent index contains an unlabeled $intent vector"
            }
            require(record.vector.size == dimension && record.vector.all { it.isFinite() }) {
                "Invalid $intent vector for ${record.text}"
            }
        }
    }

    private data class VectorIndexAsset(
        @SerializedName("schema_version") val schemaVersion: Int = 0,
        val model: String = "",
        @SerializedName("model_revision") val modelRevision: String = "",
        val dimension: Int = 0,
        @SerializedName("generate_app") val generateApp: List<VectorRecord> = emptyList(),
        val chat: List<VectorRecord> = emptyList(),
    )

    private data class VectorRecord(
        val category: String = "",
        val text: String = "",
        val vector: FloatArray = FloatArray(0),
    )

    private fun formatScore(value: Float): String = String.format(Locale.US, "%.4f", value)
}
