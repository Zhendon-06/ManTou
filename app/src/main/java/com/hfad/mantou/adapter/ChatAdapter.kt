package com.hfad.mantou.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hfad.mantou.R
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.databinding.ItemChatAssistantBinding
import com.hfad.mantou.databinding.ItemChatUserBinding

/**
 * 聊天消息适配器
 * 使用 ListAdapter + DiffUtil 实现高效更新
 * 支持流式输出的实时更新
 */
class ChatAdapter(
    private val onDataChanged: ((itemCount: Int) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatMessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).role) {
            ChatMessage.ROLE_USER -> VIEW_TYPE_USER
            ChatMessage.ROLE_ASSISTANT -> VIEW_TYPE_ASSISTANT
            else -> VIEW_TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val binding = ItemChatUserBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                UserMessageViewHolder(binding)
            }
            VIEW_TYPE_ASSISTANT -> {
                val binding = ItemChatAssistantBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                AssistantMessageViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AssistantMessageViewHolder -> holder.bind(message)
        }
    }

    /**
     * 支持局部更新（用于流式输出）
     */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && holder is AssistantMessageViewHolder) {
            // 只更新文本内容，不重新绑定整个 ViewHolder
            val message = getItem(position)
            holder.updateContent(message.content)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onCurrentListChanged(
        previousList: MutableList<ChatMessage>,
        currentList: MutableList<ChatMessage>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        onDataChanged?.invoke(currentList.size)
    }

    /**
     * 用户消息 ViewHolder（右侧气泡）
     */
    inner class UserMessageViewHolder(
        private val binding: ItemChatUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            binding.tvMessage.text = message.content

            if (message.imagePath != null) {
                binding.ivImage.visibility = View.VISIBLE
                Glide.with(binding.ivImage.context)
                    .load(message.imagePath)
                    .centerCrop()
                    .placeholder(R.drawable.ic_launcher_background)
                    .into(binding.ivImage)
            } else {
                binding.ivImage.visibility = View.GONE
            }
        }
    }

    /**
     * AI 助手消息 ViewHolder（左侧气泡）
     * 支持流式输出的实时更新
     */
    inner class AssistantMessageViewHolder(
        private val binding: ItemChatAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            binding.tvMessage.text = message.content

            if (message.imagePath != null) {
                binding.ivImage.visibility = View.VISIBLE
                Glide.with(binding.ivImage.context)
                    .load(message.imagePath)
                    .centerCrop()
                    .placeholder(R.drawable.ic_launcher_background)
                    .into(binding.ivImage)
            } else {
                binding.ivImage.visibility = View.GONE
            }
        }

        /**
         * 只更新文本内容（用于流式输出）
         */
        fun updateContent(content: String) {
            binding.tvMessage.text = content
        }
    }
}

/**
 * DiffUtil 回调
 * 优化流式输出时的更新效率
 */
class ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.messageId == newItem.messageId
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem == newItem
    }

    /**
     * 返回变化的内容，用于局部更新
     */
    override fun getChangePayload(oldItem: ChatMessage, newItem: ChatMessage): Any? {
        // 如果只是内容变化（流式输出），返回 payload 触发局部更新
        if (oldItem.messageId == newItem.messageId && 
            oldItem.content != newItem.content &&
            oldItem.role == newItem.role) {
            return "content_changed"
        }
        return null
    }
}

