package com.hfad.mantou.utils.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebInspectionParsersTest {

    @Test
    fun preflightRejectsMarkdownFenceAndMissingHtmlRoot() {
        val diagnostics = WebInspectionParsers.preflightBuildDiagnostics(
            "```html\n<body>not finished</body>\n```"
        )

        assertTrue(diagnostics.any { it.code == "HTML_MARKDOWN_FENCE" })
        assertTrue(diagnostics.any { it.code == "HTML_ROOT_MISSING" })
        assertTrue(diagnostics.any { it.code == "HTML_DOCTYPE_MISSING" })
    }

    @Test
    fun extractsOnlyInlineScriptsWithOriginalLine() {
        val html = """
            <!DOCTYPE html>
            <html><head>
            <script src="https://cdn.example.com/app.js"></script>
            <script type="text/javascript">
            const answer = 42;
            </script>
            </head></html>
        """.trimIndent()

        val scripts = WebInspectionParsers.extractInlineScripts(html)

        assertEquals(1, scripts.size)
        assertEquals(2, scripts.single().index)
        assertEquals(4, scripts.single().startLine)
        assertEquals("text/javascript", scripts.single().type)
        assertTrue(scripts.single().source.contains("answer"))
        assertEquals(
            listOf("https://cdn.example.com/app.js"),
            WebInspectionParsers.externalScriptSources(html)
        )
    }

    @Test
    fun injectsRuntimeHarnessBeforeApplicationScriptsAndOnlyOnce() {
        val html = "<!DOCTYPE html><html><head><script>boot()</script></head><body></body></html>"
        val harness = "window.__mantouHarnessInspector = { errors: [] };"

        val once = WebInspectionParsers.injectRuntimeHarness(html, harness)
        val twice = WebInspectionParsers.injectRuntimeHarness(once, harness)

        assertTrue(once.indexOf(harness) < once.indexOf("boot()"))
        assertEquals(once, twice)
        assertEquals(1, Regex("__mantouHarnessInspector").findAll(twice).count())
        assertTrue(once.contains("href=\"data:image/png;base64,"))
    }

    @Test
    fun preservesExplicitFaviconWhenInjectingRuntimeHarness() {
        val html = """
            <!DOCTYPE html>
            <html><head>
            <link href="app-icon.png" rel="shortcut ICON">
            <script>boot()</script>
            </head><body></body></html>
        """.trimIndent()

        val injected = WebInspectionParsers.injectRuntimeHarness(
            html,
            "window.__mantouHarnessInspector = { errors: [] };"
        )

        assertTrue(injected.contains("href=\"app-icon.png\""))
        assertFalse(injected.contains("href=\"data:image/png;base64,"))
    }

    @Test
    fun consoleSyntaxErrorPreservesSourceAndLine() {
        val diagnostic = WebInspectionParsers.parseConsoleDiagnostic(
            stage = WebInspectionStage.RUNTIME,
            message = "Uncaught SyntaxError: Unexpected token '}'",
            level = "ERROR",
            source = "file:///generated/app.html",
            line = 27
        )

        requireNotNull(diagnostic)
        assertEquals("JS_SYNTAX_ERROR", diagnostic.code)
        assertEquals(WebDiagnosticCategory.JAVASCRIPT, diagnostic.category)
        assertEquals(WebDiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("file:///generated/app.html", diagnostic.location?.source)
        assertEquals(27, diagnostic.location?.line)
    }

    @Test
    fun consoleStackLocationIsUsedWhenCallbackHasNoLocation() {
        val diagnostic = WebInspectionParsers.parseConsoleDiagnostic(
            stage = WebInspectionStage.RUNTIME,
            message = "Uncaught TypeError: bad\n    at render (https://mantou.local/app.html:18:9)",
            level = "ERROR",
            source = null,
            line = null
        )

        requireNotNull(diagnostic)
        assertEquals("JS_RUNTIME_ERROR", diagnostic.code)
        assertEquals("https://mantou.local/app.html", diagnostic.location?.source)
        assertEquals(18, diagnostic.location?.line)
        assertEquals(9, diagnostic.location?.column)
    }

    @Test
    fun informationalConsoleMessagesAreIgnored() {
        assertNull(
            WebInspectionParsers.parseConsoleDiagnostic(
                stage = WebInspectionStage.RUNTIME,
                message = "rendered",
                level = "LOG",
                source = "app.html",
                line = 1
            )
        )
    }

    @Test
    fun decodesEvaluateJavascriptQuotedJson() {
        val decoded = WebInspectionParsers.decodeJavascriptString(
            "\"[{\\\"message\\\":\\\"boom\\\"}]\""
        )

        assertEquals("[{\"message\":\"boom\"}]", decoded)
    }

    @Test
    fun parsesIndependentSelfTestCases() {
        val result = WebInspectionParsers.parseSelfTestResult(
            """{"passed":false,"cases":[{"name":"loads","passed":true},{"name":"adds item","passed":false,"message":"missing row","details":"expected 1"}]}"""
        )

        requireNotNull(result)
        assertFalse(result.passed)
        assertEquals(2, result.cases.size)
        assertTrue(result.cases.first().passed)
        assertEquals("missing row", result.cases.last().message)
        assertEquals("expected 1", result.cases.last().details)
    }

    @Test
    fun parsesRuntimeProbeDiagnostics() {
        val diagnostics = WebInspectionParsers.parseDiagnosticPayload(
            stage = WebInspectionStage.RUNTIME,
            payload = """[{"severity":"ERROR","code":"JS_UNHANDLED_REJECTION","message":"network failed","source":"app.html","line":8,"column":3}]""",
            defaultCategory = WebDiagnosticCategory.JAVASCRIPT
        )

        requireNotNull(diagnostics)
        assertEquals(1, diagnostics.size)
        assertEquals("JS_UNHANDLED_REJECTION", diagnostics.single().code)
        assertEquals(8, diagnostics.single().location?.line)
        assertEquals(3, diagnostics.single().location?.column)
    }
}
