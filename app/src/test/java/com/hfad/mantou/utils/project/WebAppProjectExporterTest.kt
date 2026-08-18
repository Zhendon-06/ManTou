package com.hfad.mantou.utils.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class WebAppProjectExporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportsManagedContentTreeWithoutProjectMetadata() {
        val projectRoot = temporaryFolder.newFolder("managed-project")
        File(projectRoot, ".mantou/app-state.json").apply {
            parentFile?.mkdirs()
            writeText("{\"private\":true}")
        }
        val contentRoot = File(projectRoot, "releases/v000001").apply { mkdirs() }
        val entry = write(contentRoot, "index.html", "<!doctype html><script src=\"js/app.js\"></script>")
        write(contentRoot, "js/app.js", "window.ready = true;")
        write(contentRoot, "assets/empty/.keep", "")
        val destination = File(temporaryFolder.newFolder("exports"), "project.zip")

        val archive = WebAppProjectExporter.exportZip(
            snapshot(projectRoot, contentRoot, entry, WebAppProjectSnapshotKind.RELEASE),
            destination
        )

        assertEquals(destination, archive)
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("index.html" in names)
            assertTrue("js/app.js" in names)
            assertTrue("assets/empty/.keep" in names)
            assertFalse(names.any { it.startsWith(".mantou/") })
            assertEquals(
                "window.ready = true;",
                zip.getInputStream(zip.getEntry("js/app.js")).bufferedReader().use { it.readText() }
            )
        }
    }

    @Test
    fun rejectsLegacySnapshotsAndDestinationsInsideContentRoot() {
        val projectRoot = temporaryFolder.newFolder("project")
        val contentRoot = File(projectRoot, "drafts/v000001").apply { mkdirs() }
        val entry = write(contentRoot, "index.html", "<!doctype html>")

        assertThrows(IllegalArgumentException::class.java) {
            WebAppProjectExporter.exportZip(
                snapshot(projectRoot, contentRoot, entry, WebAppProjectSnapshotKind.LEGACY),
                File(temporaryFolder.root, "legacy.zip")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebAppProjectExporter.exportZip(
                snapshot(projectRoot, contentRoot, entry, WebAppProjectSnapshotKind.DRAFT),
                File(contentRoot, "project.zip")
            )
        }
    }

    @Test
    fun rejectsSymbolicLinks() {
        val projectRoot = temporaryFolder.newFolder("linked-project")
        val contentRoot = File(projectRoot, "drafts/v000001").apply { mkdirs() }
        val entry = write(contentRoot, "index.html", "<!doctype html>")
        val outside = temporaryFolder.newFile("secret.txt").apply { writeText("secret") }
        val link = File(contentRoot, "secret.txt")
        val linkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        if (!linkCreated) return

        assertThrows(IllegalArgumentException::class.java) {
            WebAppProjectExporter.exportZip(
                snapshot(projectRoot, contentRoot, entry, WebAppProjectSnapshotKind.DRAFT),
                File(temporaryFolder.root, "linked.zip")
            )
        }
    }

    private fun snapshot(
        projectRoot: File,
        contentRoot: File,
        entry: File,
        kind: WebAppProjectSnapshotKind
    ): WebAppProjectSnapshot {
        return WebAppProjectSnapshot(
            projectRoot = projectRoot,
            contentRoot = contentRoot,
            entryFile = entry,
            manifest = WebAppProjectManifest(
                projectId = "project-id",
                displayName = "Test Project"
            ),
            kind = kind,
            version = 1L
        )
    }

    private fun write(root: File, path: String, content: String): File {
        return File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
}
