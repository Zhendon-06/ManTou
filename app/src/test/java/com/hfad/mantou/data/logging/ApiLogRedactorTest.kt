package com.hfad.mantou.data.logging

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiLogRedactorTest {

    @Test
    fun bodyRedactionRemovesSecretsAndBinaryPayloads() {
        val raw = """
            {
              "api_key":"secret-key",
              "token":"secret-token",
              "image_url":"data:image/png;base64,AAAABBBBCCCC",
              "source":{"data":"${"A".repeat(160)}"}
            }
        """.trimIndent()

        val redacted = ApiLogRedactor.redactBody(raw)

        assertFalse(redacted.contains("secret-key"))
        assertFalse(redacted.contains("secret-token"))
        assertFalse(redacted.contains("AAAABBBBCCCC"))
        assertFalse(redacted.contains("A".repeat(160)))
        assertTrue(redacted.contains("***"))
        assertTrue(redacted.contains("已省略"))
    }

    @Test
    fun urlRedactionOnlyMasksSensitiveQueryValues() {
        val redacted = ApiLogRedactor.redactUrl(
            "https://example.com/v1/chat?api_key=secret&region=cn".toHttpUrl()
        )

        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("api_key=***"))
        assertTrue(redacted.contains("region=cn"))
    }
}
