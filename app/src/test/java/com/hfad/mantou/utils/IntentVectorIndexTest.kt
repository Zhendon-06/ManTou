package com.hfad.mantou.utils

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class IntentVectorIndexTest {

    @Test
    fun bundledIndexMatchesSourceCorpus() {
        val indexFile = findFile(
            "src/main/assets/embedding/m3e-small/intent_vectors.json",
            "app/src/main/assets/embedding/m3e-small/intent_vectors.json",
        )
        val corpusFile = findFile(
            "../scripts/intent_samples.json",
            "scripts/intent_samples.json",
        )
        val index = JsonParser.parseString(indexFile.readText()).asJsonObject
        val corpus = JsonParser.parseString(corpusFile.readText()).asJsonObject

        assertEquals(1, index.get("schema_version").asInt)
        assertEquals(corpus.get("version").asInt, index.get("corpus_version").asInt)
        assertEquals(corpus.get("model").asString, index.get("model").asString)
        assertEquals(
            "44c696631b2a8c200220aaaad5f987f096e986df",
            index.get("model_revision").asString,
        )
        assertEquals(sha256(corpusFile.readBytes()), index.get("corpus_sha256").asString)

        val dimension = index.get("dimension").asInt
        assertTrue(dimension > 0)
        listOf("generate_app", "chat").forEach { intent ->
            val samples = corpus.getAsJsonArray(intent)
            val vectors = index.getAsJsonArray(intent)
            assertEquals(samples.size(), vectors.size())
            assertTrue(vectors.size() >= PrototypeIntentScorer.TOP_K)
            vectors.forEach { record ->
                assertEquals(dimension, record.asJsonObject.getAsJsonArray("vector").size())
            }
        }
    }

    private fun findFile(vararg candidates: String): File {
        return candidates.asSequence()
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("Missing test asset: ${candidates.joinToString()}")
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
