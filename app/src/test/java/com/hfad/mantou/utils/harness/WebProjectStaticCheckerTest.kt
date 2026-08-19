package com.hfad.mantou.utils.harness

import com.hfad.mantou.utils.WebProjectContentServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebProjectStaticCheckerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validMultiFileProjectPassesStaticChecks() = runBlocking {
        val root = temporaryFolder.newFolder("valid-project")
        val entry = write(
            root,
            "index.html",
            """
                <!DOCTYPE html>
                <html><head>
                  <link rel="stylesheet" href="./styles/app.css">
                  <script type="module" src="./scripts/app.js"></script>
                </head><body><img src="./images/logo.svg"></body></html>
            """.trimIndent()
        )
        write(root, "styles/app.css", "body { background: url('../images/logo.svg'); }")
        write(
            root,
            "scripts/app.js",
            "import { value } from './module.js'; fetch('./data/config.json').then(() => value);"
        )
        write(root, "scripts/module.js", "export const value = 1;")
        write(root, "data/config.json", "{\"enabled\":true}")
        write(root, "images/logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
        val project = WebProjectContentServer.create(root, entry)

        val result = DefaultWebProjectStaticChecker.check(project)

        assertTrue(result.diagnostics.joinToString("\n"), result.passed)
        assertTrue(result.summary.contains("6 个文件"))
    }

    @Test
    fun reportsMissingExternalAndInvalidJsonResources() = runBlocking {
        val root = temporaryFolder.newFolder("invalid-project")
        val entry = write(
            root,
            "index.html",
            """
                <!DOCTYPE html>
                <html><head>
                  <script src="./missing.js"></script>
                  <link rel="stylesheet" href="https://example.com/app.css">
                </head><body></body></html>
            """.trimIndent()
        )
        write(root, "data.json", "{broken")
        val project = WebProjectContentServer.create(root, entry)

        val result = DefaultWebProjectStaticChecker.check(project)

        assertFalse(result.passed)
        assertTrue(result.diagnostics.any { it.startsWith("LOCAL_RESOURCE_MISSING") })
        assertTrue(result.diagnostics.any { it.startsWith("EXTERNAL_RESOURCE_BLOCKED") })
        assertTrue(result.diagnostics.any { it.startsWith("JSON_INVALID") })
    }

    @Test
    fun reportsWhenEntryClassesDoNotMatchLoadedStylesheetSelectors() = runBlocking {
        val root = temporaryFolder.newFolder("class-mismatch-project")
        val entry = write(
            root,
            "index.html",
            """
                <!doctype html>
                <html><head><link rel="stylesheet" href="styles/app.css"></head>
                <body>
                  <main class="game-shell hud status-card quest-card touch-controls hotbar overlay panel brand hunger meter meter-track meter-fill health quest-icon coordinates">
                    <button class="control-button direction jump action-pad primary-button">开始</button>
                  </main>
                </body></html>
            """.trimIndent()
        )
        write(
            root,
            "styles/app.css",
            """
                .app-shell .topbar .game-layout .game-card .canvas-wrap .canvas-hud .stat-chip
                { display: block; }
                .inventory .slot .slot-name .slot-count .action-list .action-row { color: #111; }
                .panel, .brand, .hunger, .primary-button { color: #222; }
            """.trimIndent()
        )
        val project = WebProjectContentServer.create(root, entry)

        val result = DefaultWebProjectStaticChecker.check(project)

        assertFalse(result.passed)
        assertTrue(
            result.diagnostics.joinToString("\n"),
            result.diagnostics.any { it.startsWith("HTML_CSS_CLASS_MISMATCH") }
        )
    }

    @Test
    fun classAlignmentCheckAllowsTagAndIdDrivenStyles() = runBlocking {
        val root = temporaryFolder.newFolder("class-aligned-project")
        val entry = write(
            root,
            "index.html",
            """
                <!doctype html>
                <html><head><link rel="stylesheet" href="styles/app.css"></head>
                <body id="app" class="js-ready js-mobile js-loaded js-theme js-touch js-online js-compact js-visible">
                  <main><button>开始</button></main>
                </body></html>
            """.trimIndent()
        )
        write(
            root,
            "styles/app.css",
            """
                #app { min-height: 100vh; }
                body { margin: 0; }
                main { display: grid; }
                button { min-height: 44px; }
            """.trimIndent()
        )
        val project = WebProjectContentServer.create(root, entry)

        val result = DefaultWebProjectStaticChecker.check(project)

        assertTrue(result.diagnostics.joinToString("\n"), result.passed)
        assertFalse(result.diagnostics.any { it.startsWith("HTML_CSS_CLASS_MISMATCH") })
    }

    @Test
    fun classAlignmentCheckAllowsMatchingClassContracts() = runBlocking {
        val root = temporaryFolder.newFolder("matching-class-project")
        val entry = write(
            root,
            "index.html",
            """
                <!doctype html>
                <html><head><link rel="stylesheet" href="styles/app.css"></head>
                <body class="shell header content footer controls panel card toolbar">
                  <main class="shell header content footer controls panel card toolbar">开始</main>
                </body></html>
            """.trimIndent()
        )
        write(
            root,
            "styles/app.css",
            ".shell, .header, .content, .footer, .controls, .panel, .card, .toolbar { display: block; }"
        )
        val project = WebProjectContentServer.create(root, entry)

        val result = DefaultWebProjectStaticChecker.check(project)

        assertTrue(result.diagnostics.joinToString("\n"), result.passed)
        assertFalse(result.diagnostics.any { it.startsWith("HTML_CSS_CLASS_MISMATCH") })
    }

    private fun write(root: File, path: String, content: String): File {
        return File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
}
