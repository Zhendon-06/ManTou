package com.hfad.mantou.utils

import com.hfad.mantou.utils.project.WebAppProjectFile
import com.hfad.mantou.utils.project.WebAppProjectFileRole
import com.hfad.mantou.utils.project.WebAppProjectManifest
import com.hfad.mantou.utils.project.WebAppProjectWorkspace
import com.hfad.mantou.utils.project.WebProjectFileTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopAppScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun managedProjectShowsOnlyActiveReleaseEntry() {
        val webDir = temporaryFolder.newFolder("generated_apps")
        val projectRoot = File(webDir, "managed-clock")
        val workspace = WebAppProjectWorkspace()
        val manifest = WebAppProjectManifest(
            projectId = "managed-clock",
            displayName = "馒头时钟",
            files = listOf(
                WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY)
            )
        )
        val firstDraft = workspace.create(projectRoot, manifest)
        WebProjectFileTool(firstDraft.contentRoot).write(
            "index.html",
            "<!DOCTYPE html><html><body>released</body></html>"
        )
        val release = workspace.publishDraft(projectRoot, firstDraft.version!!)
        val unpublishedDraft = workspace.createDraft(projectRoot)
        WebProjectFileTool(unpublishedDraft.contentRoot).write(
            "index.html",
            "<!DOCTYPE html><html><body>unpublished</body></html>"
        )

        val app = DesktopAppScanner.scanWebDirectory(webDir).single()

        assertEquals("馒头时钟", app.displayName)
        assertEquals(release.entryFile.absolutePath, app.htmlPath)
        assertTrue(app.htmlPath.contains("/releases/"))
        assertTrue(!app.htmlPath.contains("/drafts/"))
    }

    @Test
    fun managedProjectWithoutActiveReleaseDoesNotFallBackToStaleRootHtml() {
        val webDir = temporaryFolder.newFolder("managed-without-release")
        val projectRoot = File(webDir, "legacy-adopted")
        val workspace = WebAppProjectWorkspace()
        val draft = workspace.create(
            projectRoot,
            WebAppProjectManifest(
                projectId = "legacy-adopted",
                displayName = "馒头草稿"
            )
        )
        WebProjectFileTool(draft.contentRoot).write(
            "index.html",
            "<!DOCTYPE html><html><body>draft</body></html>"
        )
        File(projectRoot, "stale.html").writeText(
            "<!DOCTYPE html><html><body>stale</body></html>"
        )

        assertTrue(DesktopAppScanner.scanWebDirectory(webDir).isEmpty())
    }

    @Test
    fun legacyProjectKeepsNamedHtmlSelectionAndDisplayNameCleanup() {
        val webDir = temporaryFolder.newFolder("legacy-apps")
        val directoryName = "馒头计时器_20260818_120000"
        val projectDir = File(webDir, directoryName).apply { mkdirs() }
        File(projectDir, "index.html").writeText("<html>index</html>")
        val namedEntry = File(projectDir, "$directoryName.html").apply {
            writeText("<html>named</html>")
        }

        val app = DesktopAppScanner.scanWebDirectory(webDir).single()

        assertEquals("馒头计时器", app.displayName)
        assertEquals(namedEntry.absolutePath, app.htmlPath)
    }
}
