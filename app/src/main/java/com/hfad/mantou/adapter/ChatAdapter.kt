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

    override fun onCurrentListChanged(
        previousList: MutableList<ChatMessage>,
        currentList: MutableList<ChatMessage>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        // 通知数据变化，用于控制 flGreeting 的显示/隐藏
        onDataChanged?.invoke(currentList.size)
    }

    /**
     * 用户消息 ViewHolder（右侧气泡）
     */
    inner class UserMessageViewHolder(
        private val binding: ItemChatUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            // 设置文本内容
            binding.tvMessage.text = message.content

            // 处理图片
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
     */
    inner class AssistantMessageViewHolder(
        private val binding: ItemChatAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            // 设置文本内容
            binding.tvMessage.text = message.content

            // AI 消息通常不包含图片，但保留扩展性
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
}

/**
 * DiffUtil 回调，用于高效计算列表差异
 */
class ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.messageId == newItem.messageId
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem == newItem
    }
}

