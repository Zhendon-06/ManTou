package com.hfad.mantou.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalDiffFileToolTest {

    private lateinit var root: File
    private lateinit var tool: LocalDiffFileTool

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mantou-local-diff-test").toFile()
        tool = LocalDiffFileTool(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun appliesMultipleHunksAndPreservesTargetPath() {
        val target = createFile(
            "app/index.html",
            """
                one
                two
                three
                four
                five
            """.trimIndent() + "\n"
        )
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- a/app/index.html
            +++ b/app/index.html
            @@ -1,3 +1,3 @@
             one
            -two
            +TWO
             three
            @@ -4,2 +4,3 @@
             four
            +four-and-a-half
             five
        """.trimIndent()

        val result = tool.apply(target, snapshot.sha256, diff)

        assertEquals(target.canonicalFile, result.file)
        assertEquals("app/index.html", result.relativePath)
        assertEquals(2, result.hunkCount)
        assertEquals(2, result.additions)
        assertEquals(1, result.deletions)
        assertEquals("one\nTWO\nthree\nfour\nfour-and-a-half\nfive\n", target.readText())
        assertNotEquals(result.beforeSha256, result.afterSha256)
    }

    @Test
    fun recountsIntermediateHunkBeforeFollowingHeader() {
        val target = createFile("page.html", "one\ntwo\nthree\nfour\nfive\n")
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- a/page.html
            +++ b/page.html
            @@ -1,4 +1,2 @@
             one
            -two
            +TWO
             three
            @@ -4,2 +4,2 @@
             four
            -five
            +FIVE
        """.trimIndent()

        val result = tool.apply(target, snapshot.sha256, diff)

        assertEquals(2, result.hunkCount)
        assertEquals("one\nTWO\nthree\nfour\nFIVE\n", target.readText())
    }

    @Test
    fun acceptsFencedGitDiffAndRelocatesUniqueExactContext() {
        val target = createFile("page.html", "zero\none\ntwo\nthree\nfour\n")
        val snapshot = tool.readSnapshot(target)
        val diff = """
            ```diff
            diff --git a/page.html b/page.html
            index 1111111..2222222 100644
            --- a/page.html
            +++ b/page.html
            @@ -99,2 +99,2 @@
             two
            -three
            +THREE
            ```
        """.trimIndent()

        tool.apply(target, snapshot.sha256, diff)

        assertEquals("zero\none\ntwo\nTHREE\nfour\n", target.readText())
    }

    @Test
    fun supportsInsertionsAtBeginningAndEnd() {
        val target = createFile("notes.txt", "middle\n")
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- notes.txt
            +++ notes.txt
            @@ -0,0 +1,1 @@
            +first
            @@ -1,0 +3,1 @@
            +last
        """.trimIndent()

        tool.apply(target, snapshot.sha256, diff)

        assertEquals("first\nmiddle\nlast\n", target.readText())
    }

    @Test
    fun appliesCssCustomPropertyLinesWithUnifiedDiffPrefixes() {
        val original = ":root {\n--font-xs: 12px;\n}\n"
        val target = createFile("app.html", original)
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- a/app.html
            +++ b/app.html
            @@ -1,3 +1,3 @@
             :root {
            ---font-xs: 12px;
            +--font-xs: 14px;
             }
        """.trimIndent()

        tool.apply(target, snapshot.sha256, diff)

        assertEquals(":root {\n--font-xs: 14px;\n}\n", target.readText())
    }

    @Test
    fun rejectsCssSourcePlacedBeforeHunkHeaderWithoutChangingFile() {
        val original = ":root {\n--font-xs: 12px;\n}\n"
        val target = createFile("app.html", original)
        val snapshot = tool.readSnapshot(target)
        val invalidDiff = """
            --- a/app.html
            +++ b/app.html
            --font-xs: 12px;
        """.trimIndent()

        expectDiffFailure { tool.apply(target, snapshot.sha256, invalidDiff) }

        assertEquals(original, target.readText())
    }

    @Test
    fun invalidHunkPrefixReportsDiffLineAndContent() {
        val target = createFile("app.html", "old\n")
        val snapshot = tool.readSnapshot(target)
        val invalidDiff = """
            --- a/app.html
            +++ b/app.html
            @@ -1 +1 @@
            @invalid
        """.trimIndent()

        val error = try {
            tool.apply(target, snapshot.sha256, invalidDiff)
            fail("Expected LocalDiffFileTool.DiffException")
            null
        } catch (error: LocalDiffFileTool.DiffException) {
            error
        }

        assertTrue(error?.message?.contains("Invalid hunk line prefix '@' at diff line 4: @invalid") == true)
        assertEquals("old\n", target.readText())
    }

    @Test
    fun preservesCrLfAndUnderstandsNoFinalNewlineMarker() {
        val target = createFile("page.html", "alpha\r\nbeta")
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- a/page.html
            +++ b/page.html
            @@ -1,2 +1,2 @@
             alpha
            -beta
            \ No newline at end of file
            +BETA
            \ No newline at end of file
        """.trimIndent()

        tool.apply(target, snapshot.sha256, diff)

        assertEquals("alpha\r\nBETA", target.readText())
    }

    @Test
    fun rejectsAmbiguousOffsetWithoutChangingFile() {
        val original = "start\nrepeat\nold\nmiddle\nrepeat\nold\nend\n"
        val target = createFile("page.html", original)
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- a/page.html
            +++ b/page.html
            @@ -99,2 +99,2 @@
             repeat
            -old
            +new
        """.trimIndent()

        expectDiffFailure { tool.apply(target, snapshot.sha256, diff) }

        assertEquals(original, target.readText())
    }

    @Test
    fun rejectsTruncatedOrMismatchedHunkWithoutChangingFile() {
        val original = "one\ntwo\nthree\n"
        val target = createFile("page.html", original)
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- a/page.html
            +++ b/page.html
            @@ -1,3 +1,3 @@
             one
            -two
            +TWO
        """.trimIndent()

        expectDiffFailure { tool.apply(target, snapshot.sha256, diff) }

        assertEquals(original, target.readText())
    }

    @Test
    fun rejectsMultipleFilesCreationDeletionRenameAndTraversal() {
        val target = createFile("app/page.html", "one\n")
        val snapshot = tool.readSnapshot(target)
        val invalidDiffs = listOf(
            """
                --- a/app/page.html
                +++ b/app/page.html
                @@ -1 +1 @@
                -one
                +ONE
                --- a/app/other.html
                +++ b/app/other.html
                @@ -1 +1 @@
                -x
                +y
            """.trimIndent(),
            """
                --- /dev/null
                +++ b/app/page.html
                @@ -0,0 +1 @@
                +one
            """.trimIndent(),
            """
                --- a/app/page.html
                +++ b/app/renamed.html
                @@ -1 +1 @@
                -one
                +ONE
            """.trimIndent(),
            """
                --- a/../page.html
                +++ b/../page.html
                @@ -1 +1 @@
                -one
                +ONE
            """.trimIndent()
        )

        invalidDiffs.forEach { diff ->
            expectDiffFailure { tool.apply(target, snapshot.sha256, diff) }
            assertEquals("one\n", target.readText())
        }
    }

    @Test
    fun rejectsTargetsOutsideRootTraversalAndStaleHash() {
        val target = createFile("app/page.html", "one\n")
        val outside = Files.createTempFile("mantou-outside", ".html").toFile().apply {
            writeText("outside\n")
        }
        try {
            expectDiffFailure { tool.readSnapshot(outside) }
            expectDiffFailure { tool.readSnapshot("app/../app/page.html") }

            val snapshot = tool.readSnapshot(target)
            target.writeText("changed\n")
            val diff = """
                --- a/app/page.html
                +++ b/app/page.html
                @@ -1 +1 @@
                -one
                +ONE
            """.trimIndent()
            expectDiffFailure { tool.apply(target, snapshot.sha256, diff) }
            assertEquals("changed\n", target.readText())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun rejectsSymbolicLinkEscapeWhenSupported() {
        val outside = Files.createTempFile("mantou-symlink-target", ".txt").toFile().apply {
            writeText("outside\n")
        }
        val link = File(root, "link.txt")
        val linked = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        try {
            if (!linked) return
            expectDiffFailure { tool.readSnapshot(link) }
            assertEquals("outside\n", outside.readText())
        } finally {
            link.delete()
            outside.delete()
        }
    }

    @Test
    fun acceptsCanonicalTargetWhenWorkspaceWasOpenedThroughAnAlias() {
        val realRoot = Files.createTempDirectory("mantou-real-workspace").toFile()
        val alias = File(root, "workspace-alias")
        val linked = runCatching {
            Files.createSymbolicLink(alias.toPath(), realRoot.toPath())
        }.isSuccess
        try {
            if (!linked) return
            val aliasedTool = LocalDiffFileTool(alias)
            val target = File(alias, "page.html").apply { writeText("app\n") }

            val snapshot = aliasedTool.readSnapshot(target)

            assertEquals("page.html", snapshot.relativePath)
            assertEquals("app\n", snapshot.content)
        } finally {
            alias.delete()
            realRoot.deleteRecursively()
        }
    }

    @Test
    fun preservesUnicodeAndRejectsWhitespaceFuzz() {
        val original = "标题：馒头\n按钮：保存\n"
        val target = createFile("页面.html", original)
        val snapshot = tool.readSnapshot(target)
        val validDiff = """
            --- a/页面.html
            +++ b/页面.html
            @@ -1,2 +1,2 @@
             标题：馒头
            -按钮：保存
            +按钮：完成
        """.trimIndent()

        tool.apply(target, snapshot.sha256, validDiff)
        assertEquals("标题：馒头\n按钮：完成\n", target.readText())

        val updated = tool.readSnapshot(target)
        val fuzzyDiff = """
            --- a/页面.html
            +++ b/页面.html
            @@ -1,2 +1,2 @@
             标题：馒头 
            -按钮：完成
            +按钮：确定
        """.trimIndent()
        expectDiffFailure { tool.apply(target, updated.sha256, fuzzyDiff) }
        assertEquals("标题：馒头\n按钮：完成\n", target.readText())
    }

    @Test
    fun leavesNoTemporaryFilesAfterSuccessOrValidationFailure() {
        val target = createFile("page.html", "one\n")
        val snapshot = tool.readSnapshot(target)
        val validDiff = """
            --- a/page.html
            +++ b/page.html
            @@ -1 +1 @@
            -one
            +ONE
        """.trimIndent()

        tool.apply(target, snapshot.sha256, validDiff)
        assertFalse(root.listFiles().orEmpty().any { it.name.contains("mantou-diff") })

        val updated = tool.readSnapshot(target)
        val invalidDiff = validDiff.replace("-one", "-missing")
        expectDiffFailure { tool.apply(target, updated.sha256, invalidDiff) }
        assertTrue(root.listFiles().orEmpty().none { it.name.contains("mantou-diff") })
    }

    @Test
    fun validatesPatchedContentBeforeReplacingTarget() {
        val original = "<html>\n<body>old</body>\n</html>\n"
        val target = createFile("page.html", original)
        val snapshot = tool.readSnapshot(target)
        val diff = """
            --- a/page.html
            +++ b/page.html
            @@ -1,3 +1,3 @@
             <html>
            -<body>old</body>
            +<body>new</body>
             </html>
        """.trimIndent()

        expectDiffFailure {
            tool.apply(target, snapshot.sha256, diff) {
                throw IllegalArgumentException("rejected by app validator")
            }
        }

        assertEquals(original, target.readText())
    }

    private fun createFile(relativePath: String, content: String): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun expectDiffFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected LocalDiffFileTool.DiffException")
        } catch (_: LocalDiffFileTool.DiffException) {
            Unit
        }
    }
}
