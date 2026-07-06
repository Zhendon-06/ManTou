package com.hfad.mantou.utils

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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

    private const val MODEL_ASSET = "embedding/m3e-small/model.onnx"
    private const val VOCAB_ASSET = "embedding/m3e-small/vocab.txt"
    private const val MAX_LENGTH = 64
    private const val GENERATE_THRESHOLD = 0.66f
    private const val CHAT_THRESHOLD = 0.64f
    private const val MARGIN_THRESHOLD = 0.035f

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

        val activeEngine = ensureEngine(context) ?: return@withContext Decision.Uncertain
        runCatching {
            activeEngine.detect(userMessage)
        }.getOrDefault(Decision.Uncertain)
    }

    private suspend fun ensureEngine(context: Context): Engine? {
        if (loadFailed) return null
        engine?.let { return it }

        return mutex.withLock {
            engine?.let { return@withLock it }
            if (loadFailed) return@withLock null

            val loaded = runCatching {
                if (!assetExists(context, MODEL_ASSET) || !assetExists(context, VOCAB_ASSET)) {
                    return@runCatching null
                }
                Engine.create(context.applicationContext)
            }.getOrNull()

            if (loaded == null) {
                loadFailed = true
            } else {
                engine = loaded
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
        private val generateCentroid: FloatArray,
        private val chatCentroid: FloatArray,
    ) {

        fun detect(text: String): Decision {
            val vector = embed(text)
            val generateScore = cosine(vector, generateCentroid)
            val chatScore = cosine(vector, chatCentroid)
            val margin = generateScore - chatScore

            return when {
                generateScore >= GENERATE_THRESHOLD && margin >= MARGIN_THRESHOLD -> Decision.GenerateApp
                chatScore >= CHAT_THRESHOLD && -margin >= MARGIN_THRESHOLD -> Decision.Chat
                else -> Decision.Uncertain
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
                val env = OrtEnvironment.getEnvironment()
                val modelFile = copyAssetToCache(context, MODEL_ASSET)
                val options = OrtSession.SessionOptions()
                val session = env.createSession(modelFile.absolutePath, options)
                val tokenizer = WordPieceTokenizer.fromAssets(context, VOCAB_ASSET)

                val warmupEngine = Engine(
                    env = env,
                    session = session,
                    tokenizer = tokenizer,
                    generateCentroid = FloatArray(0),
                    chatCentroid = FloatArray(0),
                )

                val generateVectors = generateSamples.map { warmupEngine.embed(it) }
                val chatVectors = chatSamples.map { warmupEngine.embed(it) }

                return Engine(
                    env = env,
                    session = session,
                    tokenizer = tokenizer,
                    generateCentroid = centroid(generateVectors),
                    chatCentroid = centroid(chatVectors),
                )
            }

            private fun copyAssetToCache(context: Context, assetPath: String): File {
                val outFile = File(context.cacheDir, assetPath.replace('/', '_'))
                if (outFile.exists() && outFile.length() > 0L) return outFile

                context.assets.open(assetPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return outFile
            }

            private fun centroid(vectors: List<FloatArray>): FloatArray {
                val dim = vectors.firstOrNull()?.size ?: return FloatArray(0)
                val sum = FloatArray(dim)
                vectors.forEach { vector ->
                    for (i in 0 until dim) {
                        sum[i] += vector[i]
                    }
                }
                for (i in 0 until dim) {
                    sum[i] /= vectors.size.toFloat()
                }
                return normalize(sum)
            }

            private val generateSamples = listOf(
                "帮我生成一个番茄钟网页应用",
                "做个可以记账的小工具",
                "写一个计算器 app",
                "创建一个待办事项小程序",
                "来个贪吃蛇小游戏",
                "帮我做一个天气查询网页",
                "生成一个抽奖转盘工具",
                "做一个日历提醒应用",
                "写个单词背诵 web app",
                "制作一个 Markdown 编辑器",
            )

            private val chatSamples = listOf(
                "解释一下这段代码是什么意思",
                "这个报错怎么解决",
                "帮我分析一下这个方案",
                "推荐几个学习 Kotlin 的方法",
                "为什么页面会卡顿",
                "这两个模型有什么区别",
                "帮我润色这段文字",
                "总结一下上面的内容",
                "我应该怎么设计这个功能",
                "请回答我的问题",
            )
        }
    }

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
            tokenIds += tokenize(text).take(maxLength - 2).map { token ->
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

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA.toDouble()).toFloat() * sqrt(normB.toDouble()).toFloat()
        return if (denominator == 0f) 0f else dot / denominator
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
}
