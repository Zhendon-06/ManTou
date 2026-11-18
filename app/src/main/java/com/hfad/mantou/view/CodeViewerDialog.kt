package com.hfad.mantou.view

import android.content.Context
import android.view.LayoutInflater
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.hfad.mantou.R
import com.hfad.mantou.adapter.WorkspaceFileAdapter
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.databinding.DialogGenerateCodeEditorBinding
import com.hfad.mantou.utils.WorkspaceNode
import com.hfad.mantou.utils.project.WebAppProjectResolver
import com.hfad.mantou.utils.project.WebAppProjectSnapshotKind
import com.hfad.mantou.utils.project.WebProjectFileTool
import com.hfad.mantou.utils.project.WebProjectFileToolPolicy
import com.hfad.mantou.view.dialog.ManTouFullscreenDialog
import com.hfad.mantou.view.dialog.dialogRipple
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal class CodeViewerDialog(
    context: Context,
    private val scope: CoroutineScope,
    val sessionId: Long? = null
) : ManTouFullscreenDialog(context, R.color.mt_generate_surface) {
    private val binding = DialogGenerateCodeEditorBinding.inflate(LayoutInflater.from(this.context))
    private val fileAdapter = WorkspaceFileAdapter(onFileClick = { selectFile(it.displayPath) })
    private val closeFilePanel = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showFiles(false)
    }
    private var project: GenerateTaskState? = null
    private var selectedPath: String? = null
    private var displayedContent: String? = null
    private var readJob: Job? = null

    init {
        setFullscreenContent(binding.root)
        onBackPressedDispatcher.addCallback(closeFilePanel)
        binding.btnCloseCodeEditor.background = context.dialogRipple(
            R.color.mt_generate_surface_soft, 13, onDark = true
        )
        binding.btnCodeEditorFiles.background = context.dialogRipple(
            R.color.mt_generate_surface_soft, 13, onDark = true
        )
        binding.tvCodeEditorLines.bindTo(binding.tvCodeEditorContent)
        binding.btnCloseCodeEditor.setOnClickListener { dismiss() }
        binding.btnCodeEditorFiles.setOnClickListener { showFiles(!closeFilePanel.isEnabled) }
        binding.btnCodeEditorFollow.setOnClickListener {
            selectedPath = null
            project?.let(::showLatestFile)
            showFiles(false)
        }
        fileAdapter.updateTextColor(context.getColor(R.color.mt_generate_text))
        binding.rvCodeEditorFiles.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = fileAdapter
            itemAnimator = null
        }
    }

    override fun onStop() {
        readJob?.cancel()
        super.onStop()
    }

    fun showFile(title: String, content: String) {
        renderCode(title, codePreviewLanguage(title), content)
    }

    fun updateProject(state: GenerateTaskState) {
        val previous = project
        project = state
        val paths = state.projectFiles
        if (paths != previous?.projectFiles) {
            fileAdapter.submitNodes(projectFileTree(paths))
            binding.btnCodeEditorFiles.isInvisible = paths.isEmpty()
            binding.tvCodeEditorFileCount.text = context.getString(R.string.code_preview_file_count, paths.size)
            if (paths.isEmpty()) showFiles(false)
        }
        binding.btnCodeEditorFollow.setText(
            if (state.isRunning) R.string.code_preview_follow else R.string.code_preview_latest
        )

        val path = selectedPath
        if (path == null || path !in paths) {
            selectedPath = null
            showLatestFile(state)
            return
        }
        val snapshotChanged = previous?.projectContentRoot != state.projectContentRoot ||
            previous?.filePath != state.filePath
        val selectedFileChanged = state.activeFilePath == path &&
            (previous?.activeFilePath != path || previous.code != state.code)
        if (snapshotChanged || selectedFileChanged) {
            readFile(state, path, showLoading = snapshotChanged)
        }
    }

    private fun showFiles(show: Boolean) {
        closeFilePanel.isEnabled = show
        binding.codeEditorFilePanel.isVisible = show
        binding.btnCodeEditorFiles.setText(if (show) R.string.code_preview_return else R.string.code_preview_files)
        if (show) {
            (selectedPath ?: project?.activeFilePath)?.let(fileAdapter::expandToFile)
        }
        updateContentVisibility()
    }

    private fun selectFile(path: String) {
        val state = project ?: return
        if (path !in state.projectFiles) return
        selectedPath = path
        fileAdapter.selectFile(path)
        showFiles(false)
        readFile(state, path, showLoading = true)
    }

    private fun showLatestFile(state: GenerateTaskState) {
        readJob?.cancel()
        val activePath = state.activeFilePath
        fileAdapter.selectFile(activePath)
        val title = activePath ?: state.filePath?.let { File(it).name }
            ?: if (state.isModification) "应用修改.diff" else "生成应用.html"
        val waiting = state.isRunning && state.code.isEmpty() && activePath !in state.projectFiles
        renderCode(
            title = title,
            language = codePreviewLanguage(state),
            content = state.code,
            message = if (waiting) R.string.code_preview_waiting else null,
            following = state.isRunning && state.projectFiles.isNotEmpty()
        )
    }

    private fun readFile(state: GenerateTaskState, path: String, showLoading: Boolean) {
        readJob?.cancel()
        val language = codePreviewLanguage(path)
        if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in
            WebProjectFileToolPolicy.DEFAULT_WRITABLE_EXTENSIONS
        ) {
            renderCode(path, language, message = R.string.code_preview_binary)
            return
        }
        if (showLoading) renderCode(path, language, message = R.string.code_preview_loading)
        readJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = state.projectContentRoot?.let(::File) ?: run {
                        // Older checkpoints store the entry path instead of the snapshot root.
                        val snapshot = WebAppProjectResolver.resolve(File(checkNotNull(state.filePath)))
                        if (snapshot.kind == WebAppProjectSnapshotKind.LEGACY) {
                            check(snapshot.manifest.files.any { it.path == path })
                        }
                        snapshot.contentRoot
                    }
                    WebProjectFileTool(root, allowMutations = false).read(path).content
                }
            }
            result.fold(
                onSuccess = { renderCode(path, language, it) },
                onFailure = { renderCode(path, language, message = R.string.code_preview_read_error) }
            )
        }
    }

    private fun renderCode(
        title: String,
        language: String,
        content: String? = null,
        message: Int? = null,
        following: Boolean = false
    ) {
        if (binding.tvCodeEditorTitle.text != title) {
            binding.tvCodeEditorTitle.text = title
            binding.codeEditorVerticalScroll.scrollTo(0, 0)
            binding.codeEditorHorizontalScroll.scrollTo(0, 0)
        }
        binding.tvCodeEditorLanguage.text = language
        val metadata = content?.let { codePreviewMetadata(null, it) }.orEmpty()
        binding.tvCodeEditorStatus.text = if (following) {
            context.getString(R.string.code_preview_following_status, metadata)
        } else {
            metadata
        }
        binding.tvCodeEditorStatus.isVisible = metadata.isNotEmpty()
        if (displayedContent != content) {
            displayedContent = content
            binding.tvCodeEditorContent.text = content.orEmpty()
        }
        binding.tvCodeEditorMessage.text = (message ?: R.string.code_preview_empty)
            .takeIf { message != null || content.isNullOrEmpty() }
            ?.let(context::getString)
        updateContentVisibility()
    }

    private fun updateContentVisibility() {
        val showCode = !closeFilePanel.isEnabled
        val hasMessage = !binding.tvCodeEditorMessage.text.isNullOrEmpty()
        binding.codeEditorDetails.isVisible = showCode
        binding.codeEditorVerticalScroll.isVisible = showCode && !hasMessage
        binding.tvCodeEditorMessage.isVisible = showCode && hasMessage
    }
}

private fun projectFileTree(
    paths: List<String>,
    parentPath: String = "",
    level: Int = 0
): List<WorkspaceNode> = paths.groupBy { it.substringBefore('/') }.map { (name, entries) ->
    val path = "$parentPath$name"
    val childPaths = entries.mapNotNull { it.substringAfter('/', "").takeIf(String::isNotEmpty) }
    WorkspaceNode(
        name = name,
        displayPath = path,
        isDirectory = childPaths.isNotEmpty(),
        level = level,
        children = projectFileTree(childPaths, "$path/", level + 1)
    )
}.sortedWith(compareBy<WorkspaceNode> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })

internal fun codePreviewLanguage(state: GenerateTaskState): String {
    val looksLikeDiff = state.code.lineSequence().take(8).any { line ->
        line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("@@ ")
    }
    if (looksLikeDiff) return "DIFF"
    val path = state.activeFilePath ?: state.filePath ?: return "HTML"
    return codePreviewLanguage(path)
}

private fun codePreviewLanguage(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
    .substringAfterLast('.', "")
    .takeIf(String::isNotBlank)
    ?.uppercase(Locale.ROOT)
    ?: "TEXT"

internal fun codePreviewMetadata(label: String?, code: String): String {
    val lineCount = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1
    val metrics = "$lineCount 行 · ${code.length} 字符"
    return label?.takeIf(String::isNotBlank)?.let { "$it · $metrics" } ?: metrics
}
