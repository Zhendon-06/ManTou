package com.hfad.mantou.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.data.database.ChatSessionEntity
import com.hfad.mantou.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表适配器
 */
class SessionAdapter(
    private val onSessionClick: (ChatSessionEntity) -> Unit,
    private val onSessionLongClick: (ChatSessionEntity) -> Unit
) : ListAdapter<ChatSessionEntity, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    private var runningSessionIds: Set<Long> = emptySet()

    fun setRunningSessionIds(sessionIds: Set<Long>) {
        if (runningSessionIds == sessionIds) return
        val changedSessionIds = runningSessionIds.union(sessionIds) - runningSessionIds.intersect(sessionIds)
        runningSessionIds = sessionIds
        currentList.forEachIndexed { index, session ->
            if (session.sessionId in changedSessionIds) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SessionViewHolder(
        private val binding: ItemSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSessionClick(getItem(position))
                }
            }

            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSessionLongClick(getItem(position))
                    true
                } else {
                    false
                }
            }
        }

        fun bind(session: ChatSessionEntity) {
            // 显示会话标题（第一个用户问题），只显示一行
            binding.tvSessionTitle.text = session.title
            binding.tvSessionTitle.maxLines = 1
            binding.progressSessionLoading.visibility =
                if (session.sessionId in runningSessionIds) View.VISIBLE else View.GONE

            // 显示创建时间
            val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            binding.tvSessionTime.text = dateFormat.format(Date(session.createTime))
        }
    }
}

/**
 * DiffUtil 回调
 */
class SessionDiffCallback : DiffUtil.ItemCallback<ChatSessionEntity>() {
    override fun areItemsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean {
        return oldItem.sessionId == newItem.sessionId
    }

    override fun areContentsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean {
        return oldItem == newItem
    }
}









