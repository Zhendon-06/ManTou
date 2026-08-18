package com.hfad.mantou.utils.project

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebAppProjectTest {
    private lateinit var temporaryRoot: File

    @Before
    fun setUp() {
        temporaryRoot = Files.createTempDirectory("mantou_web_project").toFile()
    }

    @After
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun workspacePublishesValidatedDraftAndCreatesIndependentNextDraft() {
        val projectRoot = File(temporaryRoot, "todo")
        val manifest = WebAppProjectManifest(
            projectId = "todo-app",
            displayName = "馒头待办",
            entryPoint = "index.html",
            stateFile = "data/state.json",
            files = listOf(
                WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY),
                WebAppProjectFile("styles/app.css", WebAppProjectFileRole.STYLE),
                WebAppProjectFile("scripts/app.js", WebAppProjectFileRole.SCRIPT),
                WebAppProjectFile("scripts/model.js", WebAppProjectFileRole.SCRIPT),
                WebAppProjectFile("data/state.json", WebAppProjectFileRole.DATA),
                WebAppProjectFile("assets/check.svg", WebAppProjectFileRole.ASSET)
            )
        )
        val workspace = WebAppProjectWorkspace()
        val firstDraft = workspace.create(projectRoot, manifest)
        val tool = WebProjectFileTool(firstDraft.contentRoot)

        val runtimeState = WebAppProjectWorkspace.runtimeStateFile(projectRoot)
        assertEquals("{}", runtimeState.readText())
        runtimeState.writeText("{\"todos\":[1]}")

        tool.write(
            "index.html",
            """
                <!DOCTYPE html>
                <html>
                <head><link rel="stylesheet" href="styles/app.css"></head>
                <body><img src="assets/check.svg"><script type="module" src="scripts/app.js"></script></body>
                </html>
            """.trimIndent()
        )
        tool.write("styles/app.css", "body { background-image: url('../assets/check.svg'); }")
        tool.write(
            "scripts/app.js",
            "import { initialState } from './model.js'; fetch('data/state.json'); console.log(initialState);"
        )
        tool.write("scripts/model.js", "export const initialState = {};")
        tool.write("data/state.json", "{}")
        tool.write("assets/check.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")

        val draftReport = WebAppProjectValidator().validate(firstDraft)
        assertTrue(draftReport.diagnostics.joinToString(), draftReport.passed)
        assertEquals(6, draftReport.fileCount)

        val release = workspace.publishDraft(projectRoot, firstDraft.version!!)
        assertEquals(WebAppProjectSnapshotKind.RELEASE, release.kind)
        assertEquals(1L, release.version)
        assertTrue(release.entryFile.isFile)
        assertThrows(WebAppProjectException::class.java) {
            WebProjectFileTool(release.contentRoot).write("index.html", "changed")
        }

        val secondDraft = workspace.createDraft(projectRoot)
        assertEquals(2L, secondDraft.version)
        assertEquals(release.entryFile.readText(), secondDraft.entryFile.readText())
        WebProjectFileTool(secondDraft.contentRoot).write(
            "styles/app.css",
            "body { color: rebeccapurple; }"
        )
        assertFalse(release.contentRoot.resolve("styles/app.css").readText().contains("rebeccapurple"))
        assertEquals("{\"todos\":[1]}", runtimeState.readText())
        assertFalse(File(release.contentRoot, ".mantou/app-state.json").exists())

        assertEquals(
            WebAppProjectVersions(
                latestDraftVersion = 2L,
                latestReleaseVersion = 1L,
                activeReleaseVersion = 1L
            ),
            workspace.versions(projectRoot)
        )
        assertEquals(release.entryFile, WebAppProjectResolver.resolve(projectRoot).entryFile)
        val resolvedDraft = WebAppProjectResolver.resolve(secondDraft.contentRoot)
        assertEquals(WebAppProjectSnapshotKind.DRAFT, resolvedDraft.kind)
        assertEquals(2L, resolvedDraft.version)
    }

    @Test
    fun resolverSupportsLegacyEntryFilesAndExistingProjectDirectories() {
        val legacyRoot = File(temporaryRoot, "馒头番茄钟_20260818_120000").apply { mkdirs() }
        val index = File(legacyRoot, "index.html").apply {
            writeText("<!DOCTYPE html><html><body>index</body></html>")
        }
        val namedEntry = File(legacyRoot, "${legacyRoot.name}.html").apply {
            writeText("<!DOCTYPE html><html><body>named</body></html>")
        }
        File(legacyRoot, "${legacyRoot.name}.json").writeText("{}")

        val resolvedDirectory = WebAppProjectResolver.resolve(legacyRoot)
        assertEquals(WebAppProjectSnapshotKind.LEGACY, resolvedDirectory.kind)
        assertEquals(namedEntry.canonicalFile, resolvedDirectory.entryFile)
        assertEquals(namedEntry.name, resolvedDirectory.manifest.entryPoint)
        assertEquals("${legacyRoot.name}.json", resolvedDirectory.manifest.stateFile)

        val resolvedFile = WebAppProjectResolver.resolve(index)
        assertEquals(index.canonicalFile, resolvedFile.entryFile)
        assertEquals("index.html", resolvedFile.manifest.entryPoint)
        assertEquals(resolvedDirectory.manifest.projectId, resolvedFile.manifest.projectId)
    }

    @Test
    fun workspaceAdoptsLegacyProjectWithoutChangingOriginalFiles() {
        val legacyRoot = File(temporaryRoot, "legacy-notes").apply { mkdirs() }
        val originalHtml = """
            <!DOCTYPE html>
            <html><head><link rel="stylesheet" href="styles.css"></head><body></body></html>
        """.trimIndent()
        File(legacyRoot, "index.html").writeText(originalHtml)
        File(legacyRoot, "styles.css").writeText("body { color: navy; }")
        val legacyState = "{\"notes\":[{\"text\":\"保留我\"}]}"
        File(legacyRoot, "index.json").writeText(legacyState)
        val legacy = WebAppProjectResolver.resolve(legacyRoot)
        val workspace = WebAppProjectWorkspace()

        val firstDraft = workspace.adoptLegacy(legacy)

        assertEquals(WebAppProjectSnapshotKind.DRAFT, firstDraft.kind)
        assertEquals(1L, firstDraft.version)
        assertEquals(originalHtml, firstDraft.entryFile.readText())
        assertEquals(originalHtml, File(legacyRoot, "index.html").readText())
        assertTrue(File(legacyRoot, "styles.css").isFile)
        assertTrue(File(firstDraft.contentRoot, "styles.css").isFile)
        assertEquals(legacyState, WebAppProjectWorkspace.runtimeStateFile(legacyRoot).readText())
        assertEquals(WebAppProjectVersions(1L, 0L, null), workspace.versions(legacyRoot))
        assertEquals(firstDraft.entryFile, WebAppProjectResolver.resolve(legacyRoot).entryFile)

        val baselineRelease = workspace.publishDraft(
            projectRoot = legacyRoot,
            draftVersion = firstDraft.version!!,
            manifest = firstDraft.manifest
        )
        val modificationDraft = workspace.createDraft(legacyRoot)
        assertEquals(WebAppProjectSnapshotKind.RELEASE, baselineRelease.kind)
        assertEquals(1L, baselineRelease.version)
        assertEquals(2L, modificationDraft.version)
        assertEquals(baselineRelease.entryFile, WebAppProjectResolver.resolve(legacyRoot).entryFile)

        WebProjectFileTool(modificationDraft.contentRoot).write("styles.css", "body { color: red; }")
        assertEquals("body { color: navy; }", File(legacyRoot, "styles.css").readText())
        assertEquals("body { color: navy; }", File(baselineRelease.contentRoot, "styles.css").readText())
        assertThrows(WebAppProjectException::class.java) {
            workspace.adoptLegacy(legacy)
        }
    }

    @Test
    fun validatorReportsBrokenProjectReferencesJsonAndBareModules() {
        val contentRoot = File(temporaryRoot, "broken").apply { mkdirs() }
        File(contentRoot, "index.html").writeText(
            """
                <!DOCTYPE html>
                <html><head>
                <link rel="stylesheet" href="styles/missing.css">
                <script src="https://cdn.example.com/app.js"></script>
                <script type="module" src="scripts/app.js"></script>
                </head><body></body></html>
            """.trimIndent()
        )
        File(contentRoot, "scripts").mkdirs()
        File(contentRoot, "scripts/app.js").writeText("import React from 'react';")
        File(contentRoot, "data").mkdirs()
        File(contentRoot, "data/state.json").writeText("{broken")
        val manifest = WebAppProjectManifest(
            projectId = "broken-project",
            displayName = "Broken",
            stateFile = "data/state.json",
            files = listOf(
                WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY),
                WebAppProjectFile("missing.js", WebAppProjectFileRole.SCRIPT)
            )
        )

        val report = WebAppProjectValidator().validate(manifest, contentRoot)
        val codes = report.diagnostics.map(WebProjectValidationDiagnostic::code).toSet()

        assertFalse(report.passed)
        assertTrue("LOCAL_RESOURCE_MISSING" in codes)
        assertTrue("REMOTE_RESOURCE_FORBIDDEN" in codes)
        assertTrue("DECLARED_FILE_MISSING" in codes)
        assertTrue("JSON_INVALID" in codes)
        assertTrue("BARE_MODULE_UNSUPPORTED" in codes)
    }

    @Test
    fun publishingInvalidDraftLeavesReleaseStateUntouched() {
        val projectRoot = File(temporaryRoot, "invalid-release")
        val workspace = WebAppProjectWorkspace()
        val draft = workspace.create(
            projectRoot,
            WebAppProjectManifest(
                projectId = "invalid-release",
                displayName = "Invalid release"
            )
        )
        WebProjectFileTool(draft.contentRoot).write(
            "index.html",
            "<!DOCTYPE html><html><script src=\"missing.js\"></script></html>"
        )

        assertThrows(WebAppProjectValidationException::class.java) {
            workspace.publishDraft(projectRoot, draft.version!!)
        }
        assertEquals(
            WebAppProjectVersions(1L, 0L, null),
            workspace.versions(projectRoot)
        )
        assertTrue(WebAppProjectWorkspace.releasesDirectory(projectRoot).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun publishingWithCandidateManifestUpdatesHostManifestOnlyAfterValidation() {
        val projectRoot = File(temporaryRoot, "candidate-manifest")
        val workspace = WebAppProjectWorkspace()
        val originalManifest = WebAppProjectManifest(
            projectId = "candidate-manifest",
            displayName = "Original",
            files = listOf(
                WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY)
            )
        )
        val draft = workspace.create(projectRoot, originalManifest)
        val fileTool = WebProjectFileTool(draft.contentRoot)
        fileTool.write(
            "index.html",
            "<!DOCTYPE html><html><link rel=\"stylesheet\" href=\"styles/app.css\">" +
                "<script src=\"scripts/app.js\"></script></html>"
        )
        fileTool.write("styles/app.css", "body { color: navy; }")
        fileTool.write("scripts/app.js", "document.documentElement.dataset.ready = 'true';")
        val candidateManifest = originalManifest.copy(
            displayName = "Published",
            files = originalManifest.files + listOf(
                WebAppProjectFile("styles/app.css", WebAppProjectFileRole.STYLE),
                WebAppProjectFile("scripts/app.js", WebAppProjectFileRole.SCRIPT)
            )
        )

        assertEquals(originalManifest, workspace.readManifest(projectRoot))
        val release = workspace.publishDraft(
            projectRoot = projectRoot,
            draftVersion = draft.version!!,
            manifest = candidateManifest
        )

        assertEquals(candidateManifest, workspace.readManifest(projectRoot))
        assertEquals(candidateManifest, release.manifest)
        assertEquals(release.entryFile, workspace.activeRelease(projectRoot)?.entryFile)
    }

    @Test
    fun invalidCandidateManifestDoesNotChangeHostManifestOrReleaseState() {
        val projectRoot = File(temporaryRoot, "invalid-candidate-manifest")
        val workspace = WebAppProjectWorkspace()
        val originalManifest = WebAppProjectManifest(
            projectId = "invalid-candidate-manifest",
            displayName = "Original",
            files = listOf(
                WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY)
            )
        )
        val draft = workspace.create(projectRoot, originalManifest)
        WebProjectFileTool(draft.contentRoot).write(
            "index.html",
            "<!DOCTYPE html><html><body>ready</body></html>"
        )
        val invalidCandidate = originalManifest.copy(
            displayName = "Must not persist",
            files = originalManifest.files + WebAppProjectFile(
                "scripts/missing.js",
                WebAppProjectFileRole.SCRIPT
            )
        )

        assertThrows(WebAppProjectValidationException::class.java) {
            workspace.publishDraft(projectRoot, draft.version!!, invalidCandidate)
        }

        assertEquals(originalManifest, workspace.readManifest(projectRoot))
        assertEquals(WebAppProjectVersions(1L, 0L, null), workspace.versions(projectRoot))
        assertTrue(WebAppProjectWorkspace.releasesDirectory(projectRoot).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun validatorRequiresReferencedNonEmptyCssAndJavaScriptForStructuredProjects() {
        val contentRoot = File(temporaryRoot, "structured-quality").apply { mkdirs() }
        File(contentRoot, "styles").mkdirs()
        File(contentRoot, "scripts").mkdirs()
        val entry = File(contentRoot, "index.html")
        val style = File(contentRoot, "styles/app.css").apply { writeText("   ") }
        val script = File(contentRoot, "scripts/app.js").apply { writeText("\n") }
        val manifest = WebAppProjectManifest(
            projectId = "structured-quality",
            displayName = "Structured quality",
            files = listOf(
                WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY),
                WebAppProjectFile("styles/app.css", WebAppProjectFileRole.STYLE),
                WebAppProjectFile("scripts/app.js", WebAppProjectFileRole.SCRIPT)
            )
        )

        entry.writeText("<!DOCTYPE html><html><head></head><body></body></html>")
        val missingReferences = WebAppProjectValidator().validate(manifest, contentRoot)
            .diagnostics.map(WebProjectValidationDiagnostic::code)
        assertTrue("ENTRY_STYLESHEET_REFERENCE_MISSING" in missingReferences)
        assertTrue("ENTRY_SCRIPT_REFERENCE_MISSING" in missingReferences)

        entry.writeText(
            "<!DOCTYPE html><html><head><link href=\"styles/app.css\" rel=\"stylesheet\"></head>" +
                "<body><script type=\"module\" src=\"scripts/app.js\"></script></body></html>"
        )
        val emptyResources = WebAppProjectValidator().validate(manifest, contentRoot)
            .diagnostics.map(WebProjectValidationDiagnostic::code)
        assertTrue("REFERENCED_STYLESHEET_EMPTY" in emptyResources)
        assertTrue("REFERENCED_SCRIPT_EMPTY" in emptyResources)

        style.writeText("body { color: navy; }")
        script.writeText("document.documentElement.dataset.ready = 'true';")
        val valid = WebAppProjectValidator().validate(manifest, contentRoot)
        assertTrue(valid.diagnostics.joinToString(), valid.passed)
    }

    @Test
    fun manifestCodecRejectsMissingRequiredFields() {
        val manifestFile = File(temporaryRoot, "project.json").apply {
            writeText("{\"schemaVersion\":1,\"entryPoint\":\"index.html\"}")
        }

        assertThrows(WebAppProjectException::class.java) {
            WebAppProjectManifestCodec.read(manifestFile)
        }
    }
}
