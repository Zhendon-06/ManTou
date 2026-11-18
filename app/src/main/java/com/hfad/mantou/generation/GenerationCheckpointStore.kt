package com.hfad.mantou.generation

import com.google.gson.Gson
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.utils.project.WebProjectPaths
import java.io.File

internal data class GenerationCheckpoint(
    val sessionId: Long,
    val runId: String,
    val requirement: String,
    val draftPath: String? = null,
    val taskState: GenerateTaskState? = null,
    val partialResponse: String = "",
    val revision: Long = 0,
    val completed: Boolean = false,
    val workspaceName: String? = null
) {
    fun restoredState(): GenerateTaskState {
        val saved = taskState ?: GenerateTaskState(
            sessionId = sessionId,
            phase = GenerateTaskState.Phase.PAUSED,
            status = "生成进度已保存"
        )
        if (completed) return saved
        val preview = saved.copy(projectContentRoot = saved.projectContentRoot ?: draftPath)
        if (saved.phase == GenerateTaskState.Phase.ERROR) {
            return preview.copy(code = saved.code.ifBlank { partialResponse })
        }
        return preview.copy(
            phase = GenerateTaskState.Phase.PAUSED,
            status = "生成进度已保存，发送“继续”可接着完成",
            code = saved.code.ifBlank { partialResponse },
            updatedAt = System.currentTimeMillis()
        )
    }
}

/** Durable session-to-draft binding. Partial model output stays outside executable project files. */
internal class GenerationCheckpointStore(private val directory: File) {
    private val gson = Gson()

    @Synchronized
    fun read(sessionId: Long, workspaceName: String? = null): GenerationCheckpoint? = runCatching {
        val checkpoint = gson.fromJson(file(sessionId, workspaceName).readText(), GenerationCheckpoint::class.java)
        checkpoint.takeIf {
            it.sessionId == sessionId && it.workspaceName == workspaceName && it.runId.isNotBlank()
        }
    }.getOrNull()

    @Synchronized
    fun begin(checkpoint: GenerationCheckpoint) = write(checkpoint)

    @Synchronized
    fun attachDraft(sessionId: Long, runId: String, draft: File, workspaceName: String? = null) {
        val current = read(sessionId, workspaceName)?.takeIf { it.runId == runId } ?: return
        write(current.copy(draftPath = draft.absolutePath))
    }

    @Synchronized
    fun saveState(
        sessionId: Long, runId: String, state: GenerateTaskState, revision: Long, workspaceName: String? = null
    ) {
        val current = read(sessionId, workspaceName)
            ?.takeIf { it.runId == runId && revision > it.revision } ?: return
        write(current.copy(
            taskState = state,
            revision = revision,
            completed = state.phase == GenerateTaskState.Phase.COMPLETED
        ))
    }

    @Synchronized
    fun savePartial(sessionId: Long, runId: String, response: String, workspaceName: String? = null) {
        val current = read(sessionId, workspaceName)?.takeIf { it.runId == runId && !it.completed } ?: return
        write(current.copy(partialResponse = response))
    }

    @Synchronized
    fun remove(sessionId: Long) {
        val targets = directory.listFiles().orEmpty().filter(File::isDirectory)
            .map { file(sessionId, it.name) } + file(sessionId, null)
        targets.forEach { check(!it.exists() || it.delete()) { "无法删除生成进度" } }
    }

    @Synchronized
    fun clear() {
        directory.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach {
            check(it.delete()) { "无法删除生成进度" }
        }
    }

    private fun file(sessionId: Long, workspaceName: String?) =
        File(workspaceName?.let { WebProjectPaths.resolve(directory, it) } ?: directory, "$sessionId.json")

    private fun write(checkpoint: GenerationCheckpoint) {
        WebProjectPaths.writeTextAtomically(file(checkpoint.sessionId, checkpoint.workspaceName), gson.toJson(checkpoint))
    }
}
