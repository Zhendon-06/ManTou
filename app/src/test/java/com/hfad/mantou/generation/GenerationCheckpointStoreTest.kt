package com.hfad.mantou.generation

import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.utils.project.WebAppProjectFile
import com.hfad.mantou.utils.project.WebAppProjectFileRole
import com.hfad.mantou.utils.project.WebAppProjectManifest
import com.hfad.mantou.utils.project.WebAppProjectWorkspace
import com.hfad.mantou.utils.project.WebProjectFileTool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenerationCheckpointStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun checkpointsForOneSessionAreIndependentAcrossWorkspaces() {
        val directory = temporaryFolder.newFolder("checkpoints")
        val store = GenerationCheckpointStore(directory)
        store.begin(GenerationCheckpoint(1, "default", "original"))
        store.begin(GenerationCheckpoint(1, "first", "first task", workspaceName = "设计"))
        store.begin(GenerationCheckpoint(1, "second", "second task", workspaceName = "相册"))
        val draft = temporaryFolder.newFolder("draft")
        store.attachDraft(1, "first", draft, "设计")

        val restored = GenerationCheckpointStore(directory)
        assertEquals("default", restored.read(1)?.runId)
        assertEquals(draft.absolutePath, restored.read(1, "设计")?.draftPath)
        assertEquals("second", restored.read(1, "相册")?.runId)
        assertNull(restored.read(1, "相册")?.draftPath)

        restored.remove(1)
        assertNull(store.read(1))
        assertNull(store.read(1, "设计"))
        assertNull(store.read(1, "相册"))
    }

    @Test
    fun newStoreRestoresRequirementDraftCodeAndHistoryAfterRestart() {
        val directory = temporaryFolder.newFolder("checkpoints")
        val store = GenerationCheckpointStore(directory)
        val draft = temporaryFolder.newFolder("draft")
        store.begin(GenerationCheckpoint(1, "run-1", "做一个计数器"))
        store.attachDraft(1, "run-1", draft)
        val state = GenerateTaskState(
            sessionId = 1,
            phase = GenerateTaskState.Phase.REQUESTING_MODEL,
            code = "body { color: red; }",
            activeFilePath = "app.css",
            projectFiles = listOf("app.css"),
            status = "正在生成"
        ).appendHarnessEvent(GenerateTaskState.HarnessEvent(
            stage = GenerateTaskState.Stage.TOOL,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "app.css 已写入"
        ))
        store.saveState(1, "run-1", state, 1)
        store.savePartial(1, "run-1", "<mantou-write path='app.js'>const count")

        val restored = GenerationCheckpointStore(directory).read(1)!!
        assertEquals("做一个计数器", restored.requirement)
        assertEquals(draft.absolutePath, restored.draftPath)
        assertEquals(state.code, restored.restoredState().code)
        assertEquals(state.harnessEvents, restored.restoredState().harnessEvents)
        assertFalse(restored.restoredState().isRunning)
        assertTrue(restored.restoredState().status.contains("继续"))
        assertTrue(restored.partialResponse.contains("const count"))
    }

    @Test
    fun unfinishedDraftWithoutEntryResumesInSameDirectory() {
        val root = temporaryFolder.newFolder("project")
        val workspace = WebAppProjectWorkspace()
        val draft = workspace.create(root, WebAppProjectManifest(
            projectId = "test",
            displayName = "馒头计数器",
            files = listOf(
                WebAppProjectFile("index.html", WebAppProjectFileRole.ENTRY),
                WebAppProjectFile("app.css", WebAppProjectFileRole.STYLE),
                WebAppProjectFile("app.js", WebAppProjectFileRole.SCRIPT)
            )
        ))
        WebProjectFileTool(draft.contentRoot).write("app.css", "body { color: red; }")
        val store = GenerationCheckpointStore(temporaryFolder.newFolder("checkpoint"))
        store.begin(GenerationCheckpoint(1, "run", "计数器", draft.contentRoot.absolutePath))

        val resumed = ProjectWorkspaceOperations.prepareExistingWebProject(File(store.read(1)!!.draftPath!!))
        assertEquals(draft.contentRoot.canonicalFile, resumed.snapshot.contentRoot.canonicalFile)
        assertEquals(draft.version, resumed.snapshot.version)
        assertFalse(resumed.snapshot.entryFile.exists())
        assertEquals("body { color: red; }", File(resumed.snapshot.contentRoot, "app.css").readText())
        assertNull(workspace.activeRelease(root))
    }

    @Test
    fun lateWritesCannotOverwriteNewerStateOrReplacementRun() {
        val store = GenerationCheckpointStore(temporaryFolder.newFolder("checkpoint"))
        val state = GenerateTaskState(1, GenerateTaskState.Phase.BUILDING, status = "new")
        store.begin(GenerationCheckpoint(1, "a", "old requirement"))
        store.saveState(1, "a", state, 2)
        store.saveState(1, "a", state.copy(status = "old"), 1)
        assertEquals("new", store.read(1)!!.taskState!!.status)
        store.begin(GenerationCheckpoint(1, "b", "new requirement"))
        store.saveState(1, "a", state, 3)
        store.savePartial(1, "a", "stale")
        assertEquals("b", store.read(1)!!.runId)
        assertEquals("", store.read(1)!!.partialResponse)
        assertNull(store.read(1)!!.taskState)
    }

    @Test
    fun completedRunRestoresAsCompleted() {
        val store = GenerationCheckpointStore(temporaryFolder.newFolder("checkpoint"))
        store.begin(GenerationCheckpoint(1, "a", "requirement"))
        store.saveState(1, "a", GenerateTaskState(1, GenerateTaskState.Phase.COMPLETED, status = "已发布"), 1)
        store.savePartial(1, "a", "late chunk")
        assertTrue(store.read(1)!!.completed)
        assertEquals(GenerateTaskState.Phase.COMPLETED, store.read(1)!!.restoredState().phase)
        assertEquals("", store.read(1)!!.partialResponse)
    }

    @Test
    fun failedRunKeepsItsErrorAndDraftForRetryAfterRestart() {
        val directory = temporaryFolder.newFolder("checkpoint")
        val store = GenerationCheckpointStore(directory)
        val draft = temporaryFolder.newFolder("draft")
        val failed = GenerateTaskState(
            sessionId = 1,
            phase = GenerateTaskState.Phase.ERROR,
            status = "当前模型的上游产品未开通",
            diagnostics = listOf("诊断 ID: mt-test")
        )
        store.begin(GenerationCheckpoint(1, "run", "相机记事本", draft.absolutePath))
        store.saveState(1, "run", failed, 1)
        store.savePartial(1, "run", "partial")

        val restored = GenerationCheckpointStore(directory).read(1)!!
        assertEquals(
            failed.copy(code = "partial", projectContentRoot = draft.absolutePath),
            restored.restoredState()
        )
        assertEquals(draft.absolutePath, restored.draftPath)
        assertFalse(restored.completed)
    }

    @Test
    fun deletedCheckpointCannotBeRecreatedByLateStateWrites() {
        val store = GenerationCheckpointStore(temporaryFolder.newFolder("checkpoint"))
        store.begin(GenerationCheckpoint(1, "a", "requirement"))
        store.remove(1)
        store.saveState(1, "a", GenerateTaskState(1, GenerateTaskState.Phase.ERROR, status = "stopped"), 1)
        assertNull(store.read(1))
    }

    @Test
    fun corruptCheckpointFallsBackWithoutBreakingSession() {
        val directory = temporaryFolder.newFolder("checkpoint")
        File(directory, "1.json").writeText("{")
        assertNull(GenerationCheckpointStore(directory).read(1))
    }
}
