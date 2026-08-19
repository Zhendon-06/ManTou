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

class WebProjectFileToolTest {
    private lateinit var temporaryRoot: File
    private lateinit var workspaceRoot: File

    @Before
    fun setUp() {
        temporaryRoot = Files.createTempDirectory("mantou_project_tool").toFile()
        workspaceRoot = File(temporaryRoot, "workspace").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun writesListsReadsAndDeletesFilesWithOptimisticHashes() {
        val tool = WebProjectFileTool(workspaceRoot)
        val created = tool.write("scripts/app.js", "export const value = 1;", createOnly = true)
        tool.write("index.html", "<!DOCTYPE html><html></html>")

        assertTrue(created.created)
        val read = tool.read("scripts/app.js")
        assertEquals(created.afterSha256, read.sha256)
        assertEquals("export const value = 1;", read.content)
        assertTrue(tool.list().any { it.path == "scripts/app.js" && !it.directory })
        assertTrue(tool.list("scripts", recursive = false).single().path == "scripts/app.js")

        val updated = tool.write(
            path = "scripts/app.js",
            content = "export const value = 2;",
            expectedSha256 = read.sha256
        )
        assertFalse(updated.created)
        assertEquals(read.sha256, updated.beforeSha256)
        assertThrows(WebAppProjectException::class.java) {
            tool.write("scripts/app.js", "stale", expectedSha256 = read.sha256)
        }

        val deleted = tool.delete("scripts/app.js", updated.afterSha256)
        assertEquals(updated.afterSha256, deleted.deletedSha256)
        assertFalse(File(workspaceRoot, "scripts").exists())
    }

    @Test
    fun executesStableHarnessToolNamesAndReportsChangedFiles() {
        val tool = WebProjectFileTool(workspaceRoot)
        val write = tool.execute(
            WebProjectFileTool.TOOL_WRITE_FILE,
            mapOf("path" to "styles/app.css", "content" to "body {}")
        )
        assertTrue(write.success)
        assertEquals(listOf("styles/app.css"), write.changedFiles)
        assertEquals("styles/app.css", write.metadata["path"])
        assertEquals("body {}".toByteArray().size.toString(), write.metadata["bytes"])
        assertTrue(write.metadata["after_sha256"].orEmpty().isNotBlank())

        val read = tool.execute(
            WebProjectFileTool.TOOL_READ_FILE,
            mapOf("path" to "styles/app.css")
        )
        assertTrue(read.success)
        assertTrue(read.output.contains("body {}"))
        assertEquals("styles/app.css", read.metadata["path"])
        assertEquals(write.metadata["after_sha256"], read.metadata["sha256"])

        val listed = tool.execute(
            WebProjectFileTool.TOOL_LIST_FILES,
            mapOf("path" to ".", "recursive" to "true")
        )
        assertTrue(listed.success)
        assertTrue(listed.output.contains("styles/app.css"))

        val unsupported = tool.execute("shell", emptyMap())
        assertFalse(unsupported.success)
        assertTrue(unsupported.diagnostics.single().contains("Unsupported project tool"))
        assertEquals("io", unsupported.metadata["failure_stage"])
        assertEquals("WebAppProjectException", unsupported.metadata["error_type"])
    }

    @Test
    fun reportsReadFailureStageWithoutReturningFileContent() {
        val tool = WebProjectFileTool(
            workspaceRoot,
            WebProjectFileToolPolicy(maxFileBytes = 4)
        )

        val missingArgument = tool.execute(WebProjectFileTool.TOOL_READ_FILE, emptyMap())
        assertFalse(missingArgument.success)
        assertEquals("arguments", missingArgument.metadata["failure_stage"])

        val unsafePath = tool.execute(
            WebProjectFileTool.TOOL_READ_FILE,
            mapOf("path" to "../secret.js")
        )
        assertFalse(unsafePath.success)
        assertEquals("normalize_path", unsafePath.metadata["failure_stage"])

        val missingFile = tool.execute(
            WebProjectFileTool.TOOL_READ_FILE,
            mapOf("path" to "missing.js")
        )
        assertFalse(missingFile.success)
        assertEquals("not_found", missingFile.metadata["failure_stage"])

        File(workspaceRoot, "large.js").writeText("12345")
        val tooLarge = tool.execute(
            WebProjectFileTool.TOOL_READ_FILE,
            mapOf("path" to "large.js")
        )
        assertFalse(tooLarge.success)
        assertEquals("too_large", tooLarge.metadata["failure_stage"])

        File(workspaceRoot, "invalid.js").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        val invalidUtf8 = tool.execute(
            WebProjectFileTool.TOOL_READ_FILE,
            mapOf("path" to "invalid.js")
        )
        assertFalse(invalidUtf8.success)
        assertEquals("decode_utf8", invalidUtf8.metadata["failure_stage"])
        assertFalse(invalidUtf8.metadata.values.any { "12345" in it })
    }

    @Test
    fun rejectsTraversalAbsoluteReservedUnsupportedAndBinaryPaths() {
        val tool = WebProjectFileTool(workspaceRoot)

        listOf("../escape.js", "/tmp/escape.js", "a/../escape.js", "a\\escape.js", ".mantou/state.json")
            .forEach { path ->
                assertThrows(path, WebAppProjectException::class.java) {
                    tool.write(path, "bad")
                }
            }
        assertThrows(WebAppProjectException::class.java) {
            tool.write("assets/photo.png", "not an image")
        }

        File(workspaceRoot, "invalid.js").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(WebAppProjectException::class.java) {
            tool.read("invalid.js")
        }
    }

    @Test
    fun enforcesFileCountFileSizeAndTotalSizeBeforeWriting() {
        val tool = WebProjectFileTool(
            workspaceRoot,
            WebProjectFileToolPolicy(
                maxFiles = 2,
                maxFileBytes = 8,
                maxTotalBytes = 12
            )
        )

        tool.write("a.js", "123456")
        tool.write("b.css", "1234")
        assertThrows(WebAppProjectException::class.java) {
            tool.write("c.json", "{}")
        }
        assertThrows(WebAppProjectException::class.java) {
            tool.write("b.css", "1234567")
        }
        assertThrows(WebAppProjectException::class.java) {
            tool.write("a.js", "123456789")
        }
        assertEquals("1234", File(workspaceRoot, "b.css").readText())
    }

    @Test
    fun rejectsSymbolicLinkEscapesWhenSupported() {
        val outside = File(temporaryRoot, "outside").apply { mkdirs() }
        File(outside, "secret.js").writeText("secret")
        val link = File(workspaceRoot, "linked")
        val linked = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        if (!linked) return

        val tool = WebProjectFileTool(workspaceRoot)
        assertThrows(WebAppProjectException::class.java) {
            tool.read("linked/secret.js")
        }
        assertThrows(WebAppProjectException::class.java) {
            tool.list()
        }
    }
}
