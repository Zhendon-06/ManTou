package com.hfad.mantou.adapter

import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hfad.mantou.R
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.databinding.ItemChatAssistantBinding
import com.hfad.mantou.databinding.ItemChatLoadingBinding
import com.hfad.mantou.databinding.ItemChatUserBinding
import com.hfad.mantou.utils.MantouWebViewRuntime

class ChatAdapter(
    private val onDataChanged: ((itemCount: Int) -> Unit)? = null,
    private val onFullscreenClick: ((htmlPath: String) -> Unit)? = null,
    private val onMessageLongClick: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatMessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_LOADING = 3
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
            message.isStreaming -> VIEW_TYPE_LOADING
            message.role == ChatMessage.ROLE_USER -> VIEW_TYPE_USER
            message.role == ChatMessage.ROLE_ASSISTANT -> VIEW_TYPE_ASSISTANT
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
            VIEW_TYPE_LOADING -> {
                val binding = ItemChatLoadingBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                LoadingViewHolder(binding)
            }
            else -> throw IllegalArgumentException("未知的视图类型: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AssistantMessageViewHolder -> holder.bind(message)
            is LoadingViewHolder -> holder.bind(message)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            val message = getItem(position)
            when (holder) {
                is AssistantMessageViewHolder -> holder.updateContent(message.content)
                is LoadingViewHolder -> holder.updateThinking(message.thinking)
                else -> super.onBindViewHolder(holder, position, payloads)
            }
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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is LoadingViewHolder) {
            holder.unbind()
        }
    }

    inner class UserMessageViewHolder(
        private val binding: ItemChatUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onMessageLongClick?.invoke(getItem(pos))
                    true
                } else false
            }
        }

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

    inner class AssistantMessageViewHolder(
        private val binding: ItemChatAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onMessageLongClick?.invoke(getItem(pos))
                    true
                } else false
            }
        }

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

            if (!message.appHtmlPath.isNullOrEmpty()) {
                binding.webViewContainer.visibility = View.VISIBLE
                setupWebView(message.appHtmlPath)
            } else {
                binding.webViewContainer.visibility = View.GONE
                binding.appWebView.loadUrl("about:blank")
            }
        }

        private fun setupWebView(htmlPath: String) {
            binding.appWebView.apply {
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                MantouWebViewRuntime.install(this)
                loadUrl("file://$htmlPath")
            }

            binding.btnFullscreen.setOnClickListener {
                onFullscreenClick?.invoke(htmlPath)
            }
        }

        fun updateContent(content: String) {
            binding.tvMessage.text = content
        }
    }

    inner class LoadingViewHolder(
        private val binding: ItemChatLoadingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var animatorSet: AnimatorSet? = null

        fun bind(message: ChatMessage) {
            binding.tvThinkingTitle.text = message.content.ifBlank { "正在处理" }
            updateThinking(message.thinking)
            val context = binding.root.context
            val animator = AnimatorInflater.loadAnimator(context, R.animator.loading_animation)
            if (animator is AnimatorSet) {
                animatorSet = animator
                animatorSet?.setTarget(binding.loadingContainer)
                animatorSet?.start()
            }
        }

        fun updateThinking(thinking: String?) {
            if (thinking.isNullOrEmpty()) {
                binding.thinkingPanel.visibility = View.GONE
                return
            }
            binding.thinkingPanel.visibility = View.VISIBLE
            binding.tvThinking.text = thinking
            val sv = binding.svThinking
            sv.post {
                sv.fullScroll(View.FOCUS_DOWN)
            }
        }

        fun unbind() {
            animatorSet?.cancel()
            animatorSet = null
        }
    }
}

class ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {

    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.messageId == newItem.messageId
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: ChatMessage, newItem: ChatMessage): Any? {
        if (oldItem.messageId == newItem.messageId &&
            oldItem.content != newItem.content &&
            oldItem.role == newItem.role &&
            !oldItem.isStreaming && !newItem.isStreaming) {
            return "content_changed"
        }

        if (oldItem.messageId == newItem.messageId &&
            oldItem.isStreaming && newItem.isStreaming &&
            oldItem.thinking != newItem.thinking) {
            return "thinking_changed"
        }

        if (oldItem.isStreaming != newItem.isStreaming) {
            return null
        }

        return null
    }
}
