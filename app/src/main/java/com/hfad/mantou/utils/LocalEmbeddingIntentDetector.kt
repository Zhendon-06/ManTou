package com.hfad.mantou.utils

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.sqrt

/**
 * 本地 m3e-small embedding 意图识别。
 *
 * 模型文件需要放在 assets/embedding/m3e-small/:
 * - model.onnx
 * - vocab.txt
 *
 * 文件不存在或推理失败时返回 Uncertain，由上层回落到 LLM 路由。
 */
object LocalEmbeddingIntentDetector {

    private const val TAG = "LocalEmbeddingIntent"
    private const val MODEL_ASSET = "embedding/m3e-small/model.onnx"
    private const val VOCAB_ASSET = "embedding/m3e-small/vocab.txt"
    private const val VECTOR_INDEX_ASSET = "embedding/m3e-small/intent_vectors.json"
    private const val VECTOR_INDEX_SCHEMA_VERSION = 1
    private const val VECTOR_INDEX_MODEL = "moka-ai/m3e-small"
    private const val VECTOR_INDEX_MODEL_REVISION = "44c696631b2a8c200220aaaad5f987f096e986df"
    private const val MAX_LENGTH = 64

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadFailed = false

    enum class Decision {
        GenerateApp,
        Chat,
        Uncertain
    }

    suspend fun detect(context: Context, userMessage: String): Decision = withContext(Dispatchers.IO) {
        if (userMessage.isBlank()) return@withContext Decision.Chat

        val startMs = System.currentTimeMillis()
        val activeEngine = ensureEngine(context)
        if (activeEngine == null) {
            Log.d(TAG, "detect result=Uncertain engine=null elapsed=${System.currentTimeMillis() - startMs}ms")
            return@withContext Decision.Uncertain
        }
        val decision = runCatching {
            activeEngine.detect(userMessage)
        }.getOrDefault(Decision.Uncertain)
        Log.d(TAG, "detect result=$decision elapsed=${System.currentTimeMillis() - startMs}ms")
        decision
    }

    private suspend fun ensureEngine(context: Context): Engine? {
        if (loadFailed) {
            Log.d(TAG, "load skipped previousFailure=true")
            return null
        }
        engine?.let { return it }

        return mutex.withLock {
            engine?.let { return@withLock it }
            if (loadFailed) return@withLock null

            val startMs = System.currentTimeMillis()
            val loaded = runCatching {
                if (
                    !assetExists(context, MODEL_ASSET) ||
                    !assetExists(context, VOCAB_ASSET) ||
                    !assetExists(context, VECTOR_INDEX_ASSET)
                ) {
                    Log.d(TAG, "load missingAssets=true elapsed=${System.currentTimeMillis() - startMs}ms")
                    return@runCatching null
                }
                Engine.create(context.applicationContext)
            }.onFailure { e ->
                Log.d(TAG, "load error=${e.javaClass.simpleName}:${e.message.orEmpty()} elapsed=${System.currentTimeMillis() - startMs}ms")
            }.getOrNull()

            if (loaded == null) {
                loadFailed = true
            } else {
                engine = loaded
                Log.d(TAG, "load success elapsed=${System.currentTimeMillis() - startMs}ms")
            }
            loaded
        }
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return runCatching {
            context.assets.open(path).use { true }
        }.getOrDefault(false)
    }

    private class Engine(
        private val env: OrtEnvironment,
        private val session: OrtSession,
        private val tokenizer: WordPieceTokenizer,
        private val generateVectors: List<FloatArray>,
        private val chatVectors: List<FloatArray>,
    ) {

        fun detect(text: String): Decision {
            val vector = embed(text)
            val result = PrototypeIntentScorer.score(vector, generateVectors, chatVectors)
            Log.d(
                TAG,
                "scores generate=${formatScore(result.generateScore)} " +
                    "chat=${formatScore(result.chatScore)} margin=${formatScore(result.margin)}"
            )

            return when (result.decision) {
                PrototypeIntentScorer.Decision.GenerateApp -> Decision.GenerateApp
                PrototypeIntentScorer.Decision.Chat -> Decision.Chat
                PrototypeIntentScorer.Decision.Uncertain -> Decision.Uncertain
            }
        }

        private fun embed(text: String): FloatArray {
            val encoded = tokenizer.encode(text, MAX_LENGTH)
            val shape = longArrayOf(1L, MAX_LENGTH.toLong())

            OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape).use { inputIds ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape).use { attentionMask ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.tokenTypeIds), shape).use { tokenTypeIds ->
                        val inputs = linkedMapOf(
                            "input_ids" to inputIds,
                            "attention_mask" to attentionMask,
                            "token_type_ids" to tokenTypeIds,
                        )
                        session.run(inputs).use { result ->
                            val hidden = result[0].value as Array<Array<FloatArray>>
                            return normalize(meanPool(hidden[0], encoded.attentionMask))
                        }
                    }
                }
            }
        }

        private fun meanPool(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
            val dim = tokenEmbeddings.firstOrNull()?.size ?: return FloatArray(0)
            val pooled = FloatArray(dim)
            var tokenCount = 0

            for (i in tokenEmbeddings.indices) {
                if (attentionMask.getOrElse(i) { 0L } == 0L) continue
                tokenCount += 1
                val tokenVector = tokenEmbeddings[i]
                for (j in 0 until dim) {
                    pooled[j] += tokenVector[j]
                }
            }

            if (tokenCount == 0) return pooled
            for (j in 0 until dim) {
                pooled[j] /= tokenCount.toFloat()
            }
            return pooled
        }

        companion object {
            fun create(context: Context): Engine {
                val index = loadVectorIndex(context)
                val env = OrtEnvironment.getEnvironment()
                val modelFile = copyAssetToCache(context, MODEL_ASSET)
                val options = OrtSession.SessionOptions()
                val session = env.createSession(modelFile.absolutePath, options)
                val tokenizer = WordPieceTokenizer.fromAssets(context, VOCAB_ASSET)

                return Engine(
                    env = env,
                    session = session,
                    tokenizer = tokenizer,
                    generateVectors = index.generateApp.map { normalize(it.vector) },
                    chatVectors = index.chat.map { normalize(it.vector) },
                )
            }

            private fun loadVectorIndex(context: Context): VectorIndexAsset {
                val index = context.assets.open(VECTOR_INDEX_ASSET).bufferedReader().use { reader ->
                    Gson().fromJson(reader, VectorIndexAsset::class.java)
                }
                require(index.schemaVersion == VECTOR_INDEX_SCHEMA_VERSION) {
                    "Unsupported intent vector schema ${index.schemaVersion}"
                }
                require(index.model == VECTOR_INDEX_MODEL) {
                    "Intent vector model ${index.model} does not match $VECTOR_INDEX_MODEL"
                }
                require(index.modelRevision == VECTOR_INDEX_MODEL_REVISION) {
                    "Intent vector revision ${index.modelRevision} does not match model assets"
                }
                require(index.dimension > 0) { "Intent vector dimension must be positive" }
                validateVectors("generate_app", index.generateApp, index.dimension)
                validateVectors("chat", index.chat, index.dimension)
                return index
            }

            private fun validateVectors(
                intent: String,
                records: List<VectorRecord>,
                dimension: Int,
            ) {
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

            private fun copyAssetToCache(context: Context, assetPath: String): File {
                val revision = VECTOR_INDEX_MODEL_REVISION.take(12)
                val outFile = File(context.cacheDir, "m3e-small_${revision}_model.onnx")
                if (outFile.exists() && outFile.length() > 0L) return outFile

                context.assets.open(assetPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return outFile
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

    private data class EncodedInput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    private class WordPieceTokenizer(
        private val vocab: Map<String, Int>,
    ) {
        private val clsId = vocab.getValue("[CLS]").toLong()
        private val sepId = vocab.getValue("[SEP]").toLong()
        private val padId = vocab.getValue("[PAD]").toLong()
        private val unkId = vocab.getValue("[UNK]").toLong()

        fun encode(text: String, maxLength: Int): EncodedInput {
            val tokenIds = mutableListOf<Long>()
            tokenIds += clsId
            tokenIds += truncatePreservingTail(tokenize(text), maxLength - 2).map { token ->
                (vocab[token] ?: unkId).toLong()
            }
            tokenIds += sepId

            val inputIds = LongArray(maxLength) { padId }
            val attentionMask = LongArray(maxLength)
            val tokenTypeIds = LongArray(maxLength)

            tokenIds.take(maxLength).forEachIndexed { index, tokenId ->
                inputIds[index] = tokenId
                attentionMask[index] = 1L
            }

            return EncodedInput(inputIds, attentionMask, tokenTypeIds)
        }

        private fun truncatePreservingTail(tokens: List<String>, limit: Int): List<String> {
            if (tokens.size <= limit) return tokens
            val headSize = limit / 2
            return tokens.take(headSize) + tokens.takeLast(limit - headSize)
        }

        private fun tokenize(text: String): List<String> {
            val basicTokens = basicTokenize(text)
            val pieces = mutableListOf<String>()
            for (token in basicTokens) {
                pieces += wordPieceTokenize(token)
            }
            return pieces
        }

        private fun basicTokenize(text: String): List<String> {
            val normalized = text.lowercase(Locale.ROOT)
            val tokens = mutableListOf<String>()
            val current = StringBuilder()

            fun flushCurrent() {
                if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }
            }

            normalized.forEach { char ->
                when {
                    char.isWhitespace() -> flushCurrent()
                    isCjkChar(char) -> {
                        flushCurrent()
                        tokens += char.toString()
                    }
                    isPunctuation(char) -> {
                        flushCurrent()
                        tokens += char.toString()
                    }
                    else -> current.append(char)
                }
            }
            flushCurrent()
            return tokens
        }

        private fun wordPieceTokenize(token: String): List<String> {
            if (token.length > 100) return listOf("[UNK]")
            val subTokens = mutableListOf<String>()
            var start = 0

            while (start < token.length) {
                var end = token.length
                var current: String? = null

                while (start < end) {
                    val piece = if (start == 0) {
                        token.substring(start, end)
                    } else {
                        "##${token.substring(start, end)}"
                    }
                    if (vocab.containsKey(piece)) {
                        current = piece
                        break
                    }
                    end -= 1
                }

                if (current == null) return listOf("[UNK]")
                subTokens += current
                start = end
            }

            return subTokens
        }

        companion object {
            fun fromAssets(context: Context, assetPath: String): WordPieceTokenizer {
                val vocab = linkedMapOf<String, Int>()
                context.assets.open(assetPath).bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, token ->
                        vocab[token] = index
                    }
                }
                return WordPieceTokenizer(vocab)
            }
        }
    }

    private fun isCjkChar(char: Char): Boolean {
        val code = char.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x20000..0x2A6DF ||
            code in 0x2A700..0x2B73F ||
            code in 0x2B740..0x2B81F ||
            code in 0x2B820..0x2CEAF ||
            code in 0xF900..0xFAFF
    }

    private fun isPunctuation(char: Char): Boolean {
        val type = Character.getType(char)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt()
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var norm = 0f
        for (value in vector) {
            norm += value * value
        }
        val denominator = sqrt(norm.toDouble()).toFloat()
        if (denominator == 0f) return vector
        for (i in vector.indices) {
            vector[i] /= denominator
        }
        return vector
    }

    private fun formatScore(value: Float): String {
        return String.format(Locale.US, "%.4f", value)
    }
}
