package com.hfad.mantou.utils.project

import com.google.gson.JsonParser
import com.hfad.mantou.utils.LocalDiffFileTool
import com.hfad.mantou.utils.WebProjectContentServer
import com.hfad.mantou.utils.harness.HarnessModelProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ConversationWorkspaceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun sharedResourcesAreScopedToTheirDirectoryAndPublishedWithTheProject() {
        val files = temporaryFolder.root
        val workspaces = ConversationWorkspace(files)
        val gallery = workspaces.create(null, "旅行照片")
        val other = workspaces.create(null, "笔记")
        val bytes = byteArrayOf(1, 7, 42, 13)
        val asset = workspaces.importResource(gallery, "封面 图.PNG") { bytes.inputStream() }
        val shared = ConversationWorkspace(files)
        assertEquals(listOf("旅行照片", "笔记"), shared.children(shared.directory(null)).map(File::getName))
        assertEquals(listOf("assets/workspace/封面_图.png"), shared.assetPaths(gallery))
        assertTrue(shared.assetPaths(other).isEmpty())
        assertTrue(shared.assetPaths(null).isEmpty())

        val projects = WebAppProjectWorkspace()
        val root = shared.directory(gallery)
        File(root, "notes.txt").writeText("Keep the original workspace files")
        val draft = projects.create(
            root, WebAppProjectManifest(projectId = "gallery", displayName = "相册"),
            allowExistingDirectory = true
        )
        assertEquals(root, shared.existingApp(gallery))
        draft.entryFile.writeText("""<!doctype html><html><body><img src="$asset"></body></html>""")
        assertEquals(listOf(asset), shared.copyResources(gallery, shared.assetPaths(gallery), draft.contentRoot))
        val release = projects.publishDraft(root, requireNotNull(draft.version))
        assertEquals("Keep the original workspace files", File(root, "notes.txt").readText())
        assertTrue(shared.containsProject(gallery, release.entryFile.absolutePath))
        assertFalse(shared.containsProject(other, release.entryFile.absolutePath))
        assertTrue(shared.containsProject(null, release.entryFile.absolutePath))
        val nextDraft = projects.createDraft(requireNotNull(shared.existingApp(gallery)))
        nextDraft.entryFile.appendText("<!-- edit -->")
        assertFalse(release.entryFile.readText().contains("<!-- edit -->"))
        assertThrows(IllegalArgumentException::class.java) {
            shared.execute(gallery, WebProjectFileTool.TOOL_WRITE_FILE, mapOf(
                "path" to WebProjectPaths.relativePath(root, release.entryFile),
                "content" to "leave the published version intact"
            ))
        }

        val source = File(root, asset)
        assertTrue(shared.deleteResource(gallery, source))
        assertTrue(shared.assetPaths(gallery).isEmpty())
        assertArrayEquals(bytes, File(release.contentRoot, asset).readBytes())
        val server = WebProjectContentServer.create(release.contentRoot, release.entryFile)
        val imageUrl = server.entryUrl.substringBeforeLast('/') + "/" + asset
        val image = server.resolve(imageUrl) as WebProjectContentServer.Resolution.Resource
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(bytes, image.projectFile.file.readBytes())
        val archive = WebAppProjectExporter.exportZip(release, File(files, "gallery.zip"))
        ZipFile(archive).use { zip ->
            assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(asset)).use { it.readBytes() })
        }
    }

    @Test
    fun duplicateNamesAndFailedImportsPreserveExistingResources() {
        val workspaces = ConversationWorkspace(temporaryFolder.root)
        val name = workspaces.create(null, "素材")
        val content = byteArrayOf(1)
        val original = workspaces.importResource(name, "cover.png") { content.inputStream() }
        val second = workspaces.importResource(name, "cover.png") { content.inputStream() }
        assertEquals("assets/workspace/cover_2.png", second)
        assertThrows(IllegalArgumentException::class.java) {
            workspaces.importResource(name, "index.html") { content.inputStream() }
        }
        assertThrows(IllegalStateException::class.java) {
            workspaces.importResource(name, "empty.png") { ByteArray(0).inputStream() }
        }
        assertTrue(workspaces.assetPaths(name).containsAll(listOf(original, second)))
        assertEquals(2, workspaces.assetPaths(name).size)
    }

    @Test
    fun importsNestedResourceFoldersAndKeepsTheirPathsInTheApp() {
        val workspace = ConversationWorkspace(temporaryFolder.root)
        val scope = workspace.create(null, "素材项目")
        val folder = WorkspaceResourceEntry.Folder("旅行 素材") {
            listOf(
                WorkspaceResourceEntry.Resource("cover.png") { byteArrayOf(1).inputStream() },
                WorkspaceResourceEntry.Folder("相册") {
                    listOf(WorkspaceResourceEntry.Resource("封面 图.JPG") { byteArrayOf(2, 3).inputStream() })
                },
                WorkspaceResourceEntry.Folder("空目录") { emptyList() },
                WorkspaceResourceEntry.Resource("notes.txt") { error("Unsupported file should not be opened") }
            )
        }
        val result = workspace.importResourceFolder(scope, folder)
        assertEquals(2, result.importedCount)
        assertEquals(1, result.skippedCount)
        val nested = "${result.path}/相册/封面 图.JPG"
        val paths = listOf("${result.path}/cover.png", nested)
        assertEquals(paths, workspace.assetPaths(scope))
        assertTrue(File(workspace.directory(scope), "${result.path}/空目录").isDirectory)
        val app = temporaryFolder.newFolder("app")
        assertEquals(paths, workspace.copyResources(scope, paths, app))
        assertArrayEquals(byteArrayOf(2, 3), File(app, nested).readBytes())
        assertEquals("${result.path}_2", workspace.importResourceFolder(scope, folder).path)
        assertTrue(workspace.deleteResource(scope, File(workspace.directory(scope), nested)))
        assertFalse(nested in workspace.assetPaths(scope))
    }

    @Test
    fun incompleteFolderImportsLeaveExistingResourcesIntact() {
        val workspace = ConversationWorkspace(temporaryFolder.root)
        val original = workspace.importResource(null, "saved.png") { byteArrayOf(1).inputStream() }
        val broken = WorkspaceResourceEntry.Folder("相册") {
            listOf(
                WorkspaceResourceEntry.Resource("cover.png") { byteArrayOf(2).inputStream() },
                WorkspaceResourceEntry.Resource("broken.png") { error("Unavailable") }
            )
        }
        assertThrows(IllegalStateException::class.java) { workspace.importResourceFolder(null, broken) }
        assertEquals(listOf(original), workspace.assetPaths(null))
        assertFalse(File(temporaryFolder.root, "assets/workspace/相册").exists())
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { it.name.startsWith(".import-folder-") })
    }

    @Test
    fun rootAndNestedDirectoriesUseTheSameTreeAndScopedFileTools() {
        val root = temporaryFolder.root
        val workspaces = ConversationWorkspace(root)
        val parent = workspaces.create(null, "项目")
        val child = workspaces.create(parent, "笔记")
        assertEquals("/workspace/项目/笔记", child)
        File(root, "memory").mkdir()
        File(root, ConversationWorkspace.PRIVATE_CHECKPOINTS).mkdir()
        val list = workspaces.execute(null, WebProjectFileTool.TOOL_LIST_FILES, mapOf("path" to "."))
        assertTrue(list.contains("memory/"))
        assertFalse(list.contains(ConversationWorkspace.PRIVATE_CHECKPOINTS))
        workspaces.execute(child, WebProjectFileTool.TOOL_WRITE_FILE,
            mapOf("path" to "README.md", "content" to "说明"))
        assertTrue(workspaces.execute(null, WebProjectFileTool.TOOL_READ_FILE,
            mapOf("path" to "项目/笔记/README.md")).contains("说明"))
        assertThrows(WebAppProjectException::class.java) {
            workspaces.execute(child, WebProjectFileTool.TOOL_WRITE_FILE,
                mapOf("path" to "../README.md", "content" to "越界"))
        }
    }

    @Test
    fun agentReadsAScopedLineRangeAndPatchesOnlyTheRequestedText() {
        val files = temporaryFolder.root
        val directory = File(files, "agent").apply { mkdir() }
        val file = File(directory, "SOUL.md").apply { writeText("# SOUL\r\n\r\n语气温和\r\n优先说明结论\r\n") }
        val workspaces = ConversationWorkspace(files)
        val scope = "/workspace/agent"
        fun execute(command: String): String {
            val action = WorkspaceFileActionProtocol.parse(command, "test")
            return workspaces.execute(scope, action.name, action.arguments)
        }
        val read = JsonParser.parseString(execute("""<mantou-read path="SOUL.md" start="3" count="1"/>"""))
            .asJsonObject
        assertEquals(3, read.get("startLine").asInt)
        assertEquals(4, read.get("totalLines").asInt)
        assertEquals(4, read.get("nextStartLine").asInt)
        assertEquals("语气温和", read.get("content").asString)
        val sha = read.get("sha256").asString
        val patch = """
            <mantou-patch path="SOUL.md" sha256="$sha">
            --- a/SOUL.md
            +++ b/SOUL.md
            @@ -3 +3 @@
            -语气温和
            +语气直接
            </mantou-patch>
        """.trimIndent()
        assertTrue(execute(patch).contains("\"applied\":true"))
        val expected = "# SOUL\r\n\r\n语气直接\r\n优先说明结论\r\n"
        assertEquals(expected, file.readText())
        assertThrows(LocalDiffFileTool.DiffException::class.java) { execute(patch) }
        assertThrows(IllegalArgumentException::class.java) {
            execute("""<mantou-write path="SOUL.md">完整覆盖</mantou-write>""")
        }
        assertThrows(HarnessModelProtocolException::class.java) {
            execute(patch.replace("path=\"SOUL.md\"", "path=\"../SOUL.md\""))
        }
        assertEquals(expected, file.readText())
    }

    @Test
    fun editsGeneralTextFilesAfterTheFirstPageUsingAbsoluteLineNumbers() {
        val workspaces = ConversationWorkspace(temporaryFolder.root)
        val scope = workspaces.create(null, "notes")
        val file = File(workspaces.directory(scope), "checklist.rst")
        file.writeText((1..750).joinToString("\n", postfix = "\n") { "Item $it" })
        val read = WorkspaceFileActionProtocol.parse(
            """<mantou-read path="checklist.rst" start="740" count="30"/>""", "read"
        )
        val page = JsonParser.parseString(workspaces.execute(scope, read.name, read.arguments)).asJsonObject
        assertEquals(750, page.get("totalLines").asInt)
        assertEquals("Item 740", page.get("content").asString.lineSequence().first())
        val patch = WorkspaceFileActionProtocol.parse("""
            <mantou-patch path="checklist.rst" sha256="${page.get("sha256").asString}">
            --- checklist.rst
            +++ checklist.rst
            @@ -745 +745 @@
            -Item 745
            +Done 745
            </mantou-patch>
        """.trimIndent(), "patch")
        workspaces.execute(scope, patch.name, patch.arguments)
        assertEquals("Done 745", file.readLines()[744])
    }
}
