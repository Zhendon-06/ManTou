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
    val completed: Boolean = false
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
    fun read(sessionId: Long): GenerationCheckpoint? = runCatching {
        val checkpoint = gson.fromJson(file(sessionId).readText(), GenerationCheckpoint::class.java)
        checkpoint.takeIf { it.sessionId == sessionId && it.runId.isNotBlank() }
    }.getOrNull()

    @Synchronized
    fun begin(checkpoint: GenerationCheckpoint) = write(checkpoint)

    @Synchronized
    fun attachDraft(sessionId: Long, runId: String, draft: File) {
        val current = read(sessionId)?.takeIf { it.runId == runId } ?: return
        write(current.copy(draftPath = draft.absolutePath))
    }

    @Synchronized
    fun saveState(sessionId: Long, runId: String, state: GenerateTaskState, revision: Long) {
        val current = read(sessionId)?.takeIf { it.runId == runId && revision > it.revision } ?: return
        write(current.copy(
            taskState = state,
            revision = revision,
            completed = state.phase == GenerateTaskState.Phase.COMPLETED
        ))
    }

    @Synchronized
    fun savePartial(sessionId: Long, runId: String, response: String) {
        val current = read(sessionId)?.takeIf { it.runId == runId && !it.completed } ?: return
        write(current.copy(partialResponse = response))
    }

    @Synchronized
    fun remove(sessionId: Long) {
        val target = file(sessionId)
        check(!target.exists() || target.delete()) { "无法删除生成进度" }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.filter { it.extension == "json" }?.forEach {
            check(it.delete()) { "无法删除生成进度" }
        }
    }

    private fun file(sessionId: Long) = File(directory, "$sessionId.json")

    private fun write(checkpoint: GenerationCheckpoint) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建生成进度目录" }
        WebProjectPaths.writeTextAtomically(file(checkpoint.sessionId), gson.toJson(checkpoint))
    }
}
