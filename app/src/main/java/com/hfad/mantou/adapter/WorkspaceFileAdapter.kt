package com.hfad.mantou.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.R
import com.hfad.mantou.databinding.ItemWorkspaceNodeBinding
import com.hfad.mantou.utils.WorkspaceNode

class WorkspaceFileAdapter(
    private val onFileClick: (WorkspaceNode) -> Unit,
    private val onFileLongClick: (WorkspaceNode) -> Boolean = { false }
) : RecyclerView.Adapter<WorkspaceFileAdapter.WorkspaceNodeViewHolder>() {

    private val roots = mutableListOf<WorkspaceNode>()
    private val visibleNodes = mutableListOf<WorkspaceNode>()
    private val expandedPaths = mutableSetOf<String>()

    fun submitNodes(nodes: List<WorkspaceNode>) {
        roots.clear()
        roots.addAll(nodes)
        if (expandedPaths.isEmpty()) {
            nodes.forEach(::collectDefaultExpandedPaths)
        }
        rebuildVisibleNodes()
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
            }

            binding.tvNodeBadge.visibility = if (node.isDirectory) View.GONE else View.VISIBLE
            binding.tvNodeBadge.text = fileTypeLabel(node.name)
            binding.root.setOnClickListener {
                if (node.isDirectory) {
                    toggleNode(node)
                } else {
                    onFileClick(node)
                }
            }
            binding.root.setOnLongClickListener {
                !node.isDirectory && onFileLongClick(node)
            }
        }
    }

    private fun fileTypeLabel(fileName: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return extension.ifBlank { "FILE" }.uppercase().take(5)
    }
}
