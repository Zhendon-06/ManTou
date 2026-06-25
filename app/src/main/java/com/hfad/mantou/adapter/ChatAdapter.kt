package com.hfad.mantou.adapter

import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.Glide
import com.hfad.mantou.R
import com.hfad.mantou.data.ChatMessage
import com.hfad.mantou.data.preferences.AppearanceSettingsStore
import com.hfad.mantou.databinding.ItemChatAssistantBinding
import com.hfad.mantou.databinding.ItemChatLoadingBinding
import com.hfad.mantou.databinding.ItemChatUserBinding
import com.hfad.mantou.utils.MantouWebViewRuntime
import com.hfad.mantou.utils.RichTextFormatter
import java.io.File

class ChatAdapter(
    private val onDataChanged: ((itemCount: Int) -> Unit)? = null,
    private val onFullscreenClick: ((htmlPath: String) -> Unit)? = null,
    private val onMessageLongClick: ((ChatMessage) -> Unit)? = null,
    private val onActiveWebViewChanged: ((WebView) -> Unit)? = null,
    private val onWebViewReleased: ((WebView) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatMessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_LOADING = 3
        private const val PAYLOAD_APPEARANCE_CHANGED = "appearance_changed"
    }

    private var appearanceSettings = AppearanceSettingsStore.Settings()
    private var autoTextColor: Int = android.graphics.Color.BLACK

    fun updateAppearance(settings: AppearanceSettingsStore.Settings) {
        if (appearanceSettings == settings) return
        appearanceSettings = settings
        notifyItemRangeChanged(0, itemCount, PAYLOAD_APPEARANCE_CHANGED)
    }

    fun updateAutoTextColor(color: Int) {
        if (autoTextColor == color) return
        autoTextColor = color
        if (!appearanceSettings.hasFixedTextColor) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_APPEARANCE_CHANGED)
        }
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
                is UserMessageViewHolder -> {
                    if (payloads.contains(PAYLOAD_APPEARANCE_CHANGED)) {
                        holder.bind(message)
                    } else {
                        super.onBindViewHolder(holder, position, payloads)
                    }
                }
                is AssistantMessageViewHolder -> {
                    if (payloads.contains(PAYLOAD_APPEARANCE_CHANGED)) {
                        holder.bind(message)
                    } else {
                        holder.updateContent(message.content)
                    }
                }
                is LoadingViewHolder -> {
                    if (payloads.contains(PAYLOAD_APPEARANCE_CHANGED)) {
                        holder.bind(message)
                    } else {
                        holder.updateThinking(message.thinking)
                    }
                }
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
        when (holder) {
            is AssistantMessageViewHolder -> holder.clearWebView()
            is LoadingViewHolder -> holder.unbind()
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
            bindRichText(binding.tvMessage, message.content, RichTextRole.USER)
            bindImage(binding.ivImage, message.imagePath)
        }
    }

    inner class AssistantMessageViewHolder(
        private val binding: ItemChatAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var appWebView: WebView? = null

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
            bindRichText(binding.tvMessage, message.content, RichTextRole.ASSISTANT)
            bindImage(binding.ivImage, message.imagePath)

            if (!message.appHtmlPath.isNullOrEmpty()) {
                binding.webViewContainer.visibility = View.VISIBLE
                setupWebView(message.appHtmlPath)
            } else {
                binding.webViewContainer.visibility = View.GONE
                clearWebView()
            }
        }

        private fun setupWebView(htmlPath: String) {
            ensureWebView().apply {
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
                MantouWebViewRuntime.install(this, File(htmlPath))
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        (view as? WebView)?.let { onActiveWebViewChanged?.invoke(it) }
                    }
                    false
                }
                loadUrl("file://$htmlPath")
                onActiveWebViewChanged?.invoke(this)
            }

            binding.btnFullscreen.setOnClickListener {
                onFullscreenClick?.invoke(htmlPath)
            }
        }

        private fun ensureWebView(): WebView {
            return appWebView ?: WebView(binding.appWebViewHost.context).also { webView ->
                binding.appWebViewHost.addView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                appWebView = webView
            }
        }

        fun clearWebView() {
            appWebView?.let { webView ->
                onWebViewReleased?.invoke(webView)
                webView.loadUrl("about:blank")
            }
        }

        fun updateContent(content: String) {
            bindRichText(binding.tvMessage, content, RichTextRole.ASSISTANT)
        }
    }

    inner class LoadingViewHolder(
        private val binding: ItemChatLoadingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var animatorSet: AnimatorSet? = null

        fun bind(message: ChatMessage) {
            val context = binding.root.context
            binding.tvThinkingTitle.text = message.content.ifBlank { "正在处理" }
            binding.tvThinkingTitle.textSize = appearanceSettings.chatTextSizeSp
            binding.tvThinkingTitle.setTextColor(effectiveTextColor)
            updateThinking(message.thinking)
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
            bindRichText(binding.tvThinking, thinking, RichTextRole.THINKING)
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

    private fun bindRichText(textView: TextView, content: String, role: RichTextRole) {
        val context = textView.context
        val palette = when (role) {
            RichTextRole.USER -> RichTextFormatter.Palette(
                textColor = effectiveTextColor,
                secondaryColor = ContextCompat.getColor(context, R.color.mt_text_secondary),
                accentColor = effectiveTextColor,
                codeBackgroundColor = ContextCompat.getColor(context, R.color.mt_code_bg),
                codeTextColor = ContextCompat.getColor(context, R.color.mt_code_text)
            )
            RichTextRole.ASSISTANT -> RichTextFormatter.Palette(
                textColor = effectiveTextColor,
                secondaryColor = ContextCompat.getColor(context, R.color.mt_text_secondary),
                accentColor = effectiveTextColor,
                codeBackgroundColor = ContextCompat.getColor(context, R.color.mt_code_bg),
                codeTextColor = ContextCompat.getColor(context, R.color.mt_code_text)
            )
            RichTextRole.THINKING -> RichTextFormatter.Palette(
                textColor = effectiveTextColor,
                secondaryColor = ContextCompat.getColor(context, R.color.mt_text_muted),
                accentColor = effectiveTextColor,
                codeBackgroundColor = ContextCompat.getColor(context, R.color.mt_code_bg),
                codeTextColor = ContextCompat.getColor(context, R.color.mt_code_text)
            )
        }
        textView.textSize = appearanceSettings.chatTextSizeSp
        textView.setTextColor(palette.textColor)
        textView.setTextIsSelectable(true)
        textView.text = RichTextFormatter.format(content, palette)
    }

    private fun bindImage(imageView: ImageView, imagePath: String?) {
        if (imagePath.isNullOrEmpty()) {
            Glide.with(imageView).clear(imageView)
            imageView.visibility = View.GONE
            imageView.setOnClickListener(null)
            return
        }

        imageView.visibility = View.VISIBLE
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.layoutParams = imageView.layoutParams.apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        imageView.setOnClickListener { showImagePreview(imageView, imagePath) }
        Glide.with(imageView.context)
            .load(imagePath)
            .fitCenter()
            .placeholder(R.drawable.ic_launcher_background)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    resizeImageView(imageView, resource.intrinsicWidth, resource.intrinsicHeight)
                    return false
                }
            })
            .into(imageView)
    }

    private fun resizeImageView(imageView: ImageView, intrinsicWidth: Int, intrinsicHeight: Int) {
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return

        val metrics = imageView.resources.displayMetrics
        val maxWidth = (metrics.widthPixels * 0.68f).toInt().coerceAtMost(dp(imageView, 280))
        val maxHeight = (metrics.heightPixels * 0.45f).toInt().coerceAtMost(dp(imageView, 420))
        val scale = minOf(
            1f,
            maxWidth.toFloat() / intrinsicWidth.toFloat(),
            maxHeight.toFloat() / intrinsicHeight.toFloat()
        )
        val targetWidth = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (intrinsicHeight * scale).toInt().coerceAtLeast(1)

        imageView.layoutParams = imageView.layoutParams.apply {
            width = targetWidth
            height = targetHeight
        }
    }

    private fun showImagePreview(anchor: ImageView, imagePath: String) {
        val context = anchor.context
        val dialog = Dialog(context)
        val preview = ImageView(context).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(
            preview,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.window?.setWindowAnimations(R.style.MtDialogAnimation)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        Glide.with(context)
            .load(imagePath)
            .fitCenter()
            .into(preview)
    }

    private fun dp(view: View, value: Int): Int {
        return (value * view.resources.displayMetrics.density).toInt()
    }

    private val effectiveTextColor: Int
        get() = if (appearanceSettings.hasFixedTextColor) {
            appearanceSettings.chatTextColor
        } else {
            autoTextColor
        }

    private enum class RichTextRole {
        USER,
        ASSISTANT,
        THINKING
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
