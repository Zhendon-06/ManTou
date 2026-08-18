package com.hfad.mantou.utils

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

        val error = assertThrows(IllegalArgumentException::class.java) {
            WebProjectContentServer.create(
                projectRoot = root,
                entryFile = entry,
                limits = WebProjectContentServer.Limits(maxFiles = 1)
            )
        }

        assertTrue(error.message.orEmpty().contains("more than 1 files"))
    }

    private fun write(root: File, path: String, content: String): File {
        return File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
}
