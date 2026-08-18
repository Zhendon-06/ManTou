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

    private fun write(root: File, path: String, content: String): File {
        return File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
}
