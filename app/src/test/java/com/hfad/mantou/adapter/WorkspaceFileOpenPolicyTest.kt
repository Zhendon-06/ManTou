package com.hfad.mantou.adapter

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFileOpenPolicyTest {

    @Test
    fun keepsExistingHtmlAndJsonRoutes() {
        listOf("index.html", "page.HTM").forEach { fileName ->
            assertEquals(
                fileName,
                WorkspaceFileOpenMode.WEB_APP,
                WorkspaceFileOpenPolicy.modeFor(fileName)
            )
        }
        assertEquals(
            WorkspaceFileOpenMode.JSON,
            WorkspaceFileOpenPolicy.modeFor("data/config.JSON")
        )
    }

    @Test
    fun opensWebAndCommonSourceFilesAsText() {
        val sourceFiles = listOf(
            "styles/app.css",
            "scripts/app.js",
            "scripts/module.mjs",
            "scripts/worker.cjs",
            "icons/logo.svg",
            "manifest.webmanifest",
            "types/app.ts",
            "README.md",
            ".gitignore"
        )

        sourceFiles.forEach { fileName ->
            assertEquals(
                fileName,
                WorkspaceFileOpenMode.TEXT,
                WorkspaceFileOpenPolicy.modeFor(fileName)
            )
        }
    }

    @Test
    fun opensHtmlJsonAndGeneratedAppSourceInCodeViewer() {
        listOf("index.html", "project.json", "styles/app.css", "scripts/app.js").forEach { fileName ->
            assertEquals(
                fileName,
                WorkspaceFileOpenMode.CODE_VIEWER,
                WorkspaceFileOpenPolicy.modeForWorkspacePath(
                    "/workspace/generated_apps/demo/$fileName",
                    fileName
                )
            )
        }

        assertEquals(
            WorkspaceFileOpenMode.CODE_VIEWER,
            WorkspaceFileOpenPolicy.modeForWorkspacePath("/workspace/other/index.html", "index.html")
        )
        assertEquals(
            WorkspaceFileOpenMode.CODE_VIEWER,
            WorkspaceFileOpenPolicy.modeForWorkspacePath("/workspace/memory/data.json", "data.json")
        )
        assertEquals(
            WorkspaceFileOpenMode.TEXT,
            WorkspaceFileOpenPolicy.modeForWorkspacePath("/workspace/other/styles.css", "styles.css")
        )
        assertEquals(
            WorkspaceFileOpenMode.UNSUPPORTED,
            WorkspaceFileOpenPolicy.modeForWorkspacePath(
                "/workspace/generated_apps/demo/image.png",
                "image.png"
            )
        )
    }

    @Test
    fun leavesBinaryFilesUnsupportedAndBuildsStableBadges() {
        listOf("photo.png", "font.woff2", "module.wasm", "archive.zip").forEach { fileName ->
            assertEquals(
                fileName,
                WorkspaceFileOpenMode.UNSUPPORTED,
                WorkspaceFileOpenPolicy.modeFor(fileName)
            )
        }
        assertEquals("MJS", WorkspaceFileOpenPolicy.badgeLabel("module.mjs"))
        assertEquals("WEBMA", WorkspaceFileOpenPolicy.badgeLabel("manifest.webmanifest"))
        assertEquals("FILE", WorkspaceFileOpenPolicy.badgeLabel("LICENSE"))
    }
}
