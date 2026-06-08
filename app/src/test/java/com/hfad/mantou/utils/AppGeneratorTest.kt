package com.hfad.mantou.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGeneratorTest {

    @Test
    fun ensureWebAppIdentityAddsIdAndRuntimeGuard() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>Test</title></head>
            <body><main>App</main></body>
            </html>
        """.trimIndent()

        val result = AppGenerator.ensureWebAppIdentity(html)

        assertTrue(result.contains("name=\"mantou-webapp-id\""))
        assertTrue(result.contains("mantou-webapp-runtime-guard:start"))
        assertTrue(result.contains("MantouApp/1"))
        assertTrue(result.contains("请用馒头App打开"))
        assertTrue(result.indexOf("<meta charset=\"utf-8\">") < result.indexOf("mantou-webapp-runtime-guard:start"))
    }

    @Test
    fun ensureWebAppIdentityAddsGuardToExistingIdentifiedFile() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta name="mantou-webapp-id" content="existing-id"></head>
            <body>App</body>
            </html>
        """.trimIndent()

        val result = AppGenerator.ensureWebAppIdentity(html)

        assertEquals("existing-id", AppGenerator.extractWebAppIdentity(result))
        assertTrue(result.contains("mantou-webapp-runtime-guard:start"))
    }

    @Test
    fun ensureWebAppIdentityIsIdempotent() {
        val html = """
            <!DOCTYPE html>
            <html><head></head><body>App</body></html>
        """.trimIndent()

        val once = AppGenerator.ensureWebAppIdentity(html)
        val twice = AppGenerator.ensureWebAppIdentity(once)

        assertEquals(once, twice)
        assertEquals(1, Regex("mantou-webapp-runtime-guard:start").findAll(twice).count())
    }

    @Test
    fun withMantouWebAppUserAgentAppendsTokenOnce() {
        val userAgent = AppGenerator.withMantouWebAppUserAgent("BaseUA")

        assertEquals("BaseUA MantouApp/1", userAgent)
        assertEquals(userAgent, AppGenerator.withMantouWebAppUserAgent(userAgent))
    }
}
