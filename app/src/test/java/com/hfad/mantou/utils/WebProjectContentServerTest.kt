package com.hfad.mantou.utils

import com.hfad.mantou.data.logging.HarnessTraceEvent
import com.hfad.mantou.data.logging.HarnessTraceLogger
import com.hfad.mantou.data.logging.HarnessTraceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WebProjectContentServerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesRevisionScopedAndRootRelativeProjectResources() {
        val root = temporaryFolder.newFolder("project")
        val entry = write(root, "index.html", "<!DOCTYPE html><html><body>ok</body></html>")
        write(root, "assets/app.js", "window.ready = true;")
        write(root, "assets/poster.avif", "avif")
        write(root, "data/config.json", "{\"enabled\":true}")

        val server = WebProjectContentServer.create(root, entry, projectId = "project-one")

        assertTrue(server.origin.startsWith("https://app-"))
        assertTrue(server.entryUrl.startsWith(server.origin + server.revisionBasePath))
        val entryResolution = server.resolve(server.entryUrl) as WebProjectContentServer.Resolution.Resource
        assertEquals(WebProjectContentServer.MIME_HTML, entryResolution.mimeType)
        assertEquals("public, max-age=31536000, immutable", entryResolution.headers["Cache-Control"])

        val scriptResolution = server.resolve(
            server.origin + server.revisionBasePath + "assets/app.js"
        ) as WebProjectContentServer.Resolution.Resource
        assertEquals("application/javascript", scriptResolution.mimeType)
        val imageResolution = server.resolve(
            server.origin + server.revisionBasePath + "assets/poster.avif"
        ) as WebProjectContentServer.Resolution.Resource
        assertEquals("image/avif", imageResolution.mimeType)

        val rootAliasResolution = server.resolve(
            server.origin + "/data/config.json"
        ) as WebProjectContentServer.Resolution.Resource
        assertEquals("application/json", rootAliasResolution.mimeType)
        assertEquals("no-cache", rootAliasResolution.headers["Cache-Control"])
    }

    @Test
    fun blocksExternalOriginsUnsafePathsAndHiddenMetadata() {
        val root = temporaryFolder.newFolder("blocked-project")
        val entry = write(root, "index.html", "<!DOCTYPE html><html></html>")
        write(root, ".mantou/state.json", "{}")
        val server = WebProjectContentServer.create(root, entry)

        assertEquals(
            403,
            (server.resolve("https://example.com/app.js") as WebProjectContentServer.Resolution.Error).statusCode
        )
        assertEquals(
            403,
            (server.resolve("http://${server.originHost}/index.html") as WebProjectContentServer.Resolution.Error).statusCode
        )
        assertEquals(
            403,
            (server.resolve(
                server.origin + server.revisionBasePath + "%2e%2e/secret.txt"
            ) as WebProjectContentServer.Resolution.Error).statusCode
        )
        assertEquals(
            403,
            (server.resolve(
                server.origin + server.revisionBasePath + "assets%2Fsecret.js"
            ) as WebProjectContentServer.Resolution.Error).statusCode
        )
        assertEquals(
            404,
            (server.resolve(server.origin + "/.mantou/state.json") as WebProjectContentServer.Resolution.Error).statusCode
        )
        assertTrue(server.resolve("data:text/plain,ok") is WebProjectContentServer.Resolution.Passthrough)
    }

    @Test
    fun revisionChangesWithContentWhileProjectOriginStaysStable() {
        val root = temporaryFolder.newFolder("revision-project")
        val entry = write(root, "index.html", "<!DOCTYPE html><html></html>")
        val script = write(root, "app.js", "window.value = 1;")
        val first = WebProjectContentServer.create(root, entry, projectId = "stable-project")

        script.writeText("window.value = 2;")
        val second = WebProjectContentServer.create(root, entry, projectId = "stable-project")
        val anotherProject = WebProjectContentServer.create(root, entry, projectId = "another-project")

        assertEquals(first.origin, second.origin)
        assertNotEquals(first.revision, second.revision)
        assertNotEquals(first.entryUrl, second.entryUrl)
        assertNotEquals(second.origin, anotherProject.origin)
    }

    @Test
    fun resolvesNestedReferencesWithoutEscapingProjectRoot() {
        val root = temporaryFolder.newFolder("references-project")
        val entry = write(root, "pages/index.html", "<!DOCTYPE html><html></html>")
        val image = write(root, "assets/background.png", "png")
        val server = WebProjectContentServer.create(root, entry)

        assertEquals(
            image.canonicalFile,
            server.resolveProjectReference("pages/styles/app.css", "../../assets/background.png")?.file
        )
        assertNull(server.resolveProjectReference("pages/index.html", "../../outside.txt"))
        assertNull(server.resolveProjectReference("pages/index.html", "https://example.com/app.js"))
    }

    @Test
    fun rejectsEntryOutsideRootAndSymbolicLinks() {
        val root = temporaryFolder.newFolder("safe-project")
        write(root, "index.html", "<!DOCTYPE html><html></html>")
        val outside = temporaryFolder.newFile("outside.html").apply {
            writeText("<!DOCTYPE html><html></html>")
        }

        assertThrows(IllegalArgumentException::class.java) {
            WebProjectContentServer.create(root, outside)
        }

        val link = File(root, "linked.html")
        val symbolicLinkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        if (symbolicLinkCreated) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                WebProjectContentServer.create(root, File(root, "index.html"))
            }
            assertTrue(error.message.orEmpty().contains("Symbolic links"))
        }
    }

    @Test
    fun enforcesProjectFileLimits() {
        val root = temporaryFolder.newFolder("limited-project")
        val entry = write(root, "index.html", "<!DOCTYPE html><html></html>")
        write(root, "app.js", "window.ready = true;")
        val events = mutableListOf<HarnessTraceEvent>()

        val error = assertThrows(IllegalArgumentException::class.java) {
            WebProjectContentServer.create(
                projectRoot = root,
                entryFile = entry,
                limits = WebProjectContentServer.Limits(maxFiles = 1),
                runId = "limit-run",
                iteration = 2,
                traceLogger = HarnessTraceLogger(events::add)
            )
        }

        assertTrue(error.message.orEmpty().contains("more than 1 files"))
        assertTrue(events.any {
            it.operation == "scan_project" && it.status == HarnessTraceStatus.FAILED
        })
        assertTrue(events.any {
            it.operation == "create" && it.status == HarnessTraceStatus.FAILED
        })
    }

    @Test
    fun recordsCreateAndResolutionMetadataWithoutRequestOrFileContent() {
        val root = temporaryFolder.newFolder("traced-project")
        val source = "<!DOCTYPE html><html><body>private-file-content</body></html>"
        val entry = write(root, "index.html", source)
        val events = mutableListOf<HarnessTraceEvent>()
        val server = WebProjectContentServer.create(
            projectRoot = root,
            entryFile = entry,
            projectId = "traced-project",
            htmlTransformer = { "$it<!-- transformed -->" },
            runId = "trace-run",
            iteration = 7,
            traceLogger = HarnessTraceLogger(events::add)
        )

        val scanSucceeded = events.single {
            it.operation == "scan_project" && it.status == HarnessTraceStatus.SUCCEEDED
        }
        assertEquals("trace-run", scanSucceeded.runId)
        assertEquals(7, scanSucceeded.iteration)
        assertEquals("1", scanSucceeded.details["file_count"])
        assertEquals(entry.length().toString(), scanSucceeded.details["total_bytes"])
        assertNotNull(scanSucceeded.durationMs)

        val createSucceeded = events.single {
            it.operation == "create" && it.status == HarnessTraceStatus.SUCCEEDED
        }
        assertEquals("CONTENT_SERVER", createSucceeded.component)
        assertEquals("index.html", createSucceeded.details["entry_relative_path"])
        assertEquals("true", createSucceeded.details["html_transformer"])

        server.resolve(server.entryUrl + "?token=private-query-value")
        server.resolve(server.entryUrl, method = "HEAD")
        server.resolve("https://example.com/private-external-path.js")
        server.resolve("data:text/plain,private-data-value")

        val resolveEvents = events.filter { it.operation == "resolve" }
        assertEquals(4, resolveEvents.size)
        val served = resolveEvents.first { it.details["method"] == "GET" && it.details["resolution"] == "served" }
        assertEquals(HarnessTraceStatus.SUCCEEDED, served.status)
        assertEquals("project:/index.html", served.details["target"])
        assertEquals("index.html", served.details["relative_path"])
        assertEquals("200", served.details["response_status"])
        assertEquals(WebProjectContentServer.MIME_HTML, served.details["mime"])
        assertEquals(entry.length().toString(), served.details["bytes"])
        assertEquals("source", served.details["bytes_kind"])
        assertEquals("true", served.details["transform_html"])
        assertEquals("false", served.details["head"])
        assertNotNull(served.durationMs)

        val head = resolveEvents.first { it.details["method"] == "HEAD" }
        assertEquals("0", head.details["bytes"])
        assertEquals("true", head.details["head"])

        val blocked = resolveEvents.first { it.details["resolution"] == "error" }
        assertEquals(HarnessTraceStatus.FAILED, blocked.status)
        assertEquals("external-origin", blocked.details["target"])
        assertEquals("403", blocked.details["response_status"])

        val passthrough = resolveEvents.first { it.details["resolution"] == "passthrough" }
        assertEquals(HarnessTraceStatus.SUCCEEDED, passthrough.status)
        assertEquals("scheme:data", passthrough.details["target"])
        assertEquals("passthrough", passthrough.details["response_status"])

        val traceText = events.joinToString("\n")
        assertFalse(traceText.contains("private-file-content"))
        assertFalse(traceText.contains("private-query-value"))
        assertFalse(traceText.contains("private-external-path"))
        assertFalse(traceText.contains("private-data-value"))
    }

    @Test
    fun recordsCreateValidationFailure() {
        val events = mutableListOf<HarnessTraceEvent>()
        val missingRoot = File(temporaryFolder.root, "missing-project")

        assertThrows(IllegalArgumentException::class.java) {
            WebProjectContentServer.create(
                projectRoot = missingRoot,
                entryFile = File(missingRoot, "index.html"),
                runId = "invalid-run",
                iteration = 1,
                traceLogger = HarnessTraceLogger(events::add)
            )
        }

        val createFailed = events.single {
            it.operation == "create" && it.status == HarnessTraceStatus.FAILED
        }
        assertEquals("invalid-run", createFailed.runId)
        assertEquals(1, createFailed.iteration)
        assertEquals("IllegalArgumentException", createFailed.details["error_type"])
        assertNotNull(createFailed.durationMs)
    }

    private fun write(root: File, path: String, content: String): File {
        return File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
}
