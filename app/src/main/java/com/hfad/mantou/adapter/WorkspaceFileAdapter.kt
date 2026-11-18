package com.hfad.mantou.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.R
import com.hfad.mantou.databinding.ItemWorkspaceNodeBinding
import com.hfad.mantou.utils.AgentWorkspace
import com.hfad.mantou.utils.WorkspaceNode
import com.hfad.mantou.utils.project.ConversationWorkspace
import java.util.Locale

class WorkspaceFileAdapter(
    private val onFileClick: (WorkspaceNode) -> Unit,
    private val onFileLongClick: (WorkspaceNode) -> Boolean = { false },
    private val onDirectoryClick: ((WorkspaceNode) -> Unit)? = null
) : RecyclerView.Adapter<WorkspaceFileAdapter.WorkspaceNodeViewHolder>() {

    private val roots = mutableListOf<WorkspaceNode>()
    private val visibleNodes = mutableListOf<WorkspaceNode>()
    private val expandedPaths = mutableSetOf<String>()
    private var textColor: Int = Color.BLACK
    private var selectedFilePath: String? = null

    fun submitNodes(nodes: List<WorkspaceNode>) {
        roots.clear()
        roots.addAll(nodes)
        if (expandedPaths.isEmpty()) {
            nodes.forEach(::collectDefaultExpandedPaths)
        }
        rebuildVisibleNodes()
    }

    fun updateTextColor(color: Int) {
        if (textColor == color) return
        textColor = color
        notifyItemRangeChanged(0, itemCount, PAYLOAD_TEXT_COLOR)
    }

    fun selectFile(path: String?) {
        val previous = selectedFilePath
        if (previous == path) return
        selectedFilePath = path
        visibleNodes.forEachIndexed { index, node ->
            if (node.displayPath == previous || node.displayPath == path) {
                notifyItemChanged(index)
            }
        }
    }

    fun expandToFile(path: String) {
        expandDirectory(path.substringBeforeLast('/'))
    }

    fun expandDirectory(path: String): Int {
        val parents = generateSequence(path) { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }
            .toList()
        if (expandedPaths.addAll(parents)) rebuildVisibleNodes()
        return visibleNodes.indexOfFirst { it.displayPath == path }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkspaceNodeViewHolder {
        val binding = ItemWorkspaceNodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WorkspaceNodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WorkspaceNodeViewHolder, position: Int) {
        holder.bind(visibleNodes[position])
    }

    override fun onBindViewHolder(
        holder: WorkspaceNodeViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_TEXT_COLOR)) {
            holder.applyTextColor()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = visibleNodes.size

    private fun toggleNode(node: WorkspaceNode) {
        if (!node.isDirectory || node.children.isEmpty()) return
        if (expandedPaths.contains(node.displayPath)) {
            expandedPaths.remove(node.displayPath)
        } else {
            expandedPaths.add(node.displayPath)
        }
        rebuildVisibleNodes()
    }

    private fun rebuildVisibleNodes() {
        visibleNodes.clear()
        roots.forEach { appendVisibleNode(it) }
        notifyDataSetChanged()
    }

    private fun appendVisibleNode(node: WorkspaceNode) {
        visibleNodes.add(node)
        if (node.isDirectory && expandedPaths.contains(node.displayPath)) {
            node.children.forEach { appendVisibleNode(it) }
        }
    }

    private fun collectDefaultExpandedPaths(node: WorkspaceNode) {
        if (node.defaultExpanded) expandedPaths.add(node.displayPath)
        node.children.forEach(::collectDefaultExpandedPaths)
    }

    inner class WorkspaceNodeViewHolder(
        private val binding: ItemWorkspaceNodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: WorkspaceNode) {
            binding.tvNodeName.text = node.name
            val isSelected = node.displayPath == selectedFilePath
            binding.root.isSelected = isSelected
            if (onDirectoryClick != null) {
                binding.root.setBackgroundResource(
                    if (isSelected) R.drawable.bg_workspace_directory_selected else 0
                )
            } else {
                binding.root.setBackgroundColor(
                    if (isSelected) binding.root.context.getColor(R.color.mt_generate_surface_soft)
                    else Color.TRANSPARENT
                )
            }
            applyTextColor()
            binding.ivNodeIcon.setImageResource(
                if (node.isDirectory) R.drawable.ic_folder_outline else R.drawable.ic_file_markdown
            )

            val indentParams = binding.indentSpace.layoutParams
            indentParams.width = (node.level * 20 * binding.root.resources.displayMetrics.density).toInt()
            binding.indentSpace.layoutParams = indentParams

            val canExpand = node.isDirectory && node.children.isNotEmpty()
            binding.ivNodeChevron.visibility = if (canExpand) View.VISIBLE else View.INVISIBLE
            if (canExpand) {
                val chevron = if (expandedPaths.contains(node.displayPath)) {
                    R.drawable.ic_chevron_up
                } else {
                    R.drawable.ic_chevron_down
                }
                binding.ivNodeChevron.setImageResource(chevron)
                binding.ivNodeChevron.contentDescription =
                    if (expandedPaths.contains(node.displayPath)) "折叠 ${node.name}" else "展开 ${node.name}"
            }
            binding.ivNodeChevron.setOnClickListener(
                if (canExpand && onDirectoryClick != null) View.OnClickListener { toggleNode(node) } else null
            )
            binding.ivNodeChevron.isClickable = canExpand && onDirectoryClick != null

            binding.tvNodeBadge.visibility = if (node.isDirectory) View.GONE else View.VISIBLE
            binding.tvNodeBadge.text = fileTypeLabel(node.name)
            binding.root.setOnClickListener {
                if (node.isDirectory) {
                    onDirectoryClick?.invoke(node) ?: toggleNode(node)
                } else {
                    onFileClick(node)
                }
            }
            binding.root.setOnLongClickListener {
                !node.isDirectory && onFileLongClick(node)
            }
        }

        fun applyTextColor() {
            binding.tvNodeName.setTextColor(textColor)
            val secondaryColor = ColorUtils.setAlphaComponent(textColor, 184)
            binding.ivNodeIcon.imageTintList = ColorStateList.valueOf(secondaryColor)
            binding.ivNodeChevron.imageTintList = ColorStateList.valueOf(secondaryColor)
        }
    }

    private fun fileTypeLabel(fileName: String): String {
        return WorkspaceFileOpenPolicy.badgeLabel(fileName)
    }

    private companion object {
        const val PAYLOAD_TEXT_COLOR = "text_color"
    }
}

internal enum class WorkspaceFileOpenMode {
    CODE_VIEWER,
    WEB_APP,
    JSON,
    TEXT,
    IMAGE,
    UNSUPPORTED
}

internal object WorkspaceFileOpenPolicy {
    private val textExtensions = setOf(
        "css", "scss", "sass", "less",
        "js", "mjs", "cjs", "jsx",
        "ts", "mts", "cts", "tsx",
        "svg", "xml", "webmanifest", "map",
        "md", "markdown", "txt",
        "yaml", "yml", "toml", "ini", "properties",
        "csv", "tsv", "sql", "graphql", "gql",
        "sh", "bash", "zsh", "env", "gitignore"
    )

    fun modeFor(fileName: String): WorkspaceFileOpenMode {
        return when (extensionOf(fileName)) {
            "html", "htm" -> WorkspaceFileOpenMode.WEB_APP
            "json" -> WorkspaceFileOpenMode.JSON
            in textExtensions -> WorkspaceFileOpenMode.TEXT
            else -> WorkspaceFileOpenMode.UNSUPPORTED
        }
    }

    fun modeForWorkspacePath(displayPath: String, fileName: String): WorkspaceFileOpenMode {
        val defaultMode = modeFor(fileName)
        return when {
            displayPath.startsWith("${ConversationWorkspace.ROOT_PATH}/") &&
                extensionOf(fileName) in imageExtensions -> WorkspaceFileOpenMode.IMAGE
            defaultMode == WorkspaceFileOpenMode.WEB_APP -> WorkspaceFileOpenMode.CODE_VIEWER
            defaultMode == WorkspaceFileOpenMode.JSON -> WorkspaceFileOpenMode.CODE_VIEWER
            isGeneratedAppPath(displayPath) &&
                defaultMode == WorkspaceFileOpenMode.TEXT -> WorkspaceFileOpenMode.CODE_VIEWER
            else -> defaultMode
        }
    }

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "ico")

    fun isGeneratedAppPath(displayPath: String): Boolean {
        val segments = displayPath.trimStart('/').split('/')
        return segments.firstOrNull() == "workspace" &&
            (segments.getOrNull(1) == AgentWorkspace.WEB_DIR ||
                (segments.getOrNull(1) == ConversationWorkspace.DIRECTORY &&
                    segments.getOrNull(3) == ConversationWorkspace.COMPILED_PROJECTS))
    }

    fun badgeLabel(fileName: String): String {
        return extensionOf(fileName).ifBlank { "FILE" }.uppercase(Locale.ROOT).take(5)
    }

    private fun extensionOf(fileName: String): String {
        return fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
    }
}
