package com.hfad.mantou.utils

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.sqrt

internal object LocalTextEmbedder {

    internal const val MODEL_ID = "moka-ai/m3e-small"
    internal const val MODEL_REVISION = "44c696631b2a8c200220aaaad5f987f096e986df"

    private const val TAG = "LocalTextEmbedder"
    private const val MODEL_ASSET = "embedding/m3e-small/model.onnx"
    private const val VOCAB_ASSET = "embedding/m3e-small/vocab.txt"
    private const val MAX_LENGTH = 64
    private const val CACHE_LIMIT = 32

    private val engineMutex = Mutex()
    private val cacheLock = Any()
    private val embeddingCache = object : LinkedHashMap<String, FloatArray>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, FloatArray>?
        ): Boolean = size > CACHE_LIMIT
    }
    private var engine: Engine? = null
    private var loadFailed = false

    suspend fun embed(context: Context, text: String): FloatArray? {
        return embedAll(context, listOf(text))?.singleOrNull()
    }

    suspend fun embedAll(context: Context, texts: List<String>): List<FloatArray>? =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            if (texts.any(String::isBlank)) return@withContext null

            val keys = texts.map(::cacheKey)
            val missingKeys = keys.distinct().filter { key ->
                synchronized(cacheLock) { embeddingCache[key] == null }
            }

            if (missingKeys.isNotEmpty()) {
                val activeEngine = ensureEngine(context) ?: return@withContext null
                val vectors = runCatching { activeEngine.embed(missingKeys) }
                    .onFailure { error ->
                        Log.d(TAG, "embed error=${error.javaClass.simpleName}:${error.message.orEmpty()}")
                    }
                    .getOrNull()
                    ?: return@withContext null
                synchronized(cacheLock) {
                    missingKeys.zip(vectors).forEach { (key, vector) ->
                        embeddingCache[key] = vector
                    }
                }
            }

            val result = ArrayList<FloatArray>(keys.size)
            for (key in keys) {
                val vector = synchronized(cacheLock) { embeddingCache[key] }
                    ?: return@withContext null
                result += vector
            }
            result
        }

    private suspend fun ensureEngine(context: Context): Engine? {
        if (loadFailed) return null
        engine?.let { return it }

        return engineMutex.withLock {
            engine?.let { return@withLock it }
            if (loadFailed) return@withLock null

            val startedAt = System.currentTimeMillis()
            val loaded = runCatching {
                if (!assetExists(context, MODEL_ASSET) || !assetExists(context, VOCAB_ASSET)) {
                    return@runCatching null
                }
                Engine.create(context.applicationContext)
            }.onFailure { error ->
                Log.d(TAG, "load error=${error.javaClass.simpleName}:${error.message.orEmpty()}")
            }.getOrNull()

            if (loaded == null) {
                loadFailed = true
                Log.d(TAG, "load failed elapsed=${System.currentTimeMillis() - startedAt}ms")
            } else {
                engine = loaded
                Log.d(TAG, "load success elapsed=${System.currentTimeMillis() - startedAt}ms")
            }
            loaded
        }
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return runCatching { context.assets.open(path).use { true } }.getOrDefault(false)
    }

    private fun cacheKey(text: String): String = text.trim().lowercase(Locale.ROOT)

    private class Engine(
        private val environment: OrtEnvironment,
        private val session: OrtSession,
        private val tokenizer: WordPieceTokenizer,
    ) {
        fun embed(texts: List<String>): List<FloatArray> {
            val encodedInputs = texts.map { tokenizer.encode(it, MAX_LENGTH) }
            val batchSize = encodedInputs.size
            val shape = longArrayOf(batchSize.toLong(), MAX_LENGTH.toLong())
            val inputIds = flatten(encodedInputs) { it.inputIds }
            val attentionMasks = flatten(encodedInputs) { it.attentionMask }
            val tokenTypeIds = flatten(encodedInputs) { it.tokenTypeIds }

            OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), shape).use { inputTensor ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(attentionMasks), shape).use { maskTensor ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(tokenTypeIds), shape).use { typeTensor ->
                        val inputs = linkedMapOf(
                            "input_ids" to inputTensor,
                            "attention_mask" to maskTensor,
                            "token_type_ids" to typeTensor,
                        )
                        session.run(inputs).use { output ->
                            @Suppress("UNCHECKED_CAST")
                            val hidden = output[0].value as Array<Array<FloatArray>>
                            require(hidden.size == batchSize) {
                                "Embedding batch mismatch: expected $batchSize, actual ${hidden.size}"
                            }
                            return hidden.mapIndexed { index, tokenEmbeddings ->
                                normalize(meanPool(tokenEmbeddings, encodedInputs[index].attentionMask))
                            }
                        }
                    }
                }
            }
        }

        private fun flatten(
            inputs: List<EncodedInput>,
            values: (EncodedInput) -> LongArray,
        ): LongArray {
            val flattened = LongArray(inputs.size * MAX_LENGTH)
            inputs.forEachIndexed { index, input ->
                values(input).copyInto(flattened, destinationOffset = index * MAX_LENGTH)
            }
            return flattened
        }

        companion object {
            fun create(context: Context): Engine {
                val environment = OrtEnvironment.getEnvironment()
                val modelFile = copyAssetToCache(context, MODEL_ASSET)
                val session = environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
                val tokenizer = WordPieceTokenizer.fromAssets(context, VOCAB_ASSET)
                return Engine(environment, session, tokenizer)
            }

            private fun copyAssetToCache(context: Context, assetPath: String): File {
                val revision = MODEL_REVISION.take(12)
                val outFile = File(context.cacheDir, "m3e-small_${revision}_model.onnx")
                if (outFile.exists() && outFile.length() > 0L) return outFile

                context.assets.open(assetPath).use { input ->
                    outFile.outputStream().use(input::copyTo)
                }
                return outFile
            }
        }
    }

    private data class EncodedInput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    private class WordPieceTokenizer(private val vocab: Map<String, Int>) {
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
            val pieces = mutableListOf<String>()
            for (token in basicTokenize(text)) {
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

            normalized.forEach { character ->
                when {
                    character.isWhitespace() -> flushCurrent()
                    isCjkChar(character) -> {
                        flushCurrent()
                        tokens += character.toString()
                    }
                    isPunctuation(character) -> {
                        flushCurrent()
                        tokens += character.toString()
                    }
                    else -> current.append(character)
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
                    val piece = if (start == 0) token.substring(start, end) else "##${token.substring(start, end)}"
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
                    lines.forEachIndexed { index, token -> vocab[token] = index }
                }
                return WordPieceTokenizer(vocab)
            }
        }
    }

    private fun meanPool(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val dimension = tokenEmbeddings.firstOrNull()?.size ?: return FloatArray(0)
        val pooled = FloatArray(dimension)
        var tokenCount = 0
        tokenEmbeddings.forEachIndexed { index, tokenVector ->
            if (attentionMask.getOrElse(index) { 0L } == 0L) return@forEachIndexed
            tokenCount += 1
            for (dimensionIndex in 0 until dimension) {
                pooled[dimensionIndex] += tokenVector[dimensionIndex]
            }
        }
        if (tokenCount > 0) {
            for (index in pooled.indices) pooled[index] /= tokenCount.toFloat()
        }
        return pooled
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var squaredNorm = 0f
        for (value in vector) squaredNorm += value * value
        val denominator = sqrt(squaredNorm.toDouble()).toFloat()
        if (denominator == 0f) return vector
        for (index in vector.indices) vector[index] /= denominator
        return vector
    }

    private fun isCjkChar(character: Char): Boolean {
        val code = character.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF
    }

    private fun isPunctuation(character: Char): Boolean {
        val type = Character.getType(character)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt()
    }
}
