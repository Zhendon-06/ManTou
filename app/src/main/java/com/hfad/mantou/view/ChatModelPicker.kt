package com.hfad.mantou.view

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.R
import com.hfad.mantou.databinding.ItemChatModelBinding
import com.hfad.mantou.view.dialog.ManTouSheet
import com.hfad.mantou.view.dialog.dialogDp
import com.hfad.mantou.view.dialog.dialogField
import com.hfad.mantou.view.dialog.dialogRipple

internal data class ChatModelOption(val providerId: Long, val providerName: String, val modelName: String)

@DrawableRes
internal fun chatModelIcon(modelName: String, providerName: String = ""): Int {
    fun findIcon(name: String): Int? {
        val value = name.lowercase()
        return when {
            "qwen" in value || "千问" in value || "通义" in value -> R.drawable.ic_model_qwen
            "deepseek" in value || "深度求索" in value -> R.drawable.ic_model_deepseek
            "glm" in value || "智谱" in value -> R.drawable.ic_model_glm
            "kimi" in value || "moonshot" in value || "月之暗面" in value -> R.drawable.ic_model_kimi
            "mimo" in value -> R.drawable.ic_model_mimo
            else -> null
        }
    }
    return findIcon(modelName) ?: findIcon(providerName) ?: R.drawable.ic_model_picker
}

internal class ChatModelPicker(
    context: Context,
    activeProviderId: Long?,
    activeModelName: String?,
    onConfigure: () -> Unit,
    onSelected: (ChatModelOption) -> Unit
) {
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        val inset = context.dialogDp(20)
        setPadding(inset, context.dialogDp(10), inset, context.dialogDp(12))
    }
    val dialog = ManTouSheet(context, root).apply {
        setTitle("选择模型")
        behavior.maxHeight = context.dialogDp((context.resources.configuration.screenHeightDp * 0.68f).toInt())
    }
    private val adapter = ModelAdapter(activeProviderId, activeModelName) { option ->
        onSelected(option)
        dialog.dismiss()
    }
    private val search = context.dialogField(
        "搜索模型或供应商",
        input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    ).apply {
        compoundDrawablePadding = context.dialogDp(8)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search_outline, 0, 0, 0)
        isVisible = false
    }
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@ChatModelPicker.adapter
        clipToPadding = false
        isVisible = false
    }
    private val status = TextView(context).apply {
        text = "正在加载模型…"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.mt_dialog_secondary))
        setPadding(0, context.dialogDp(24), 0, context.dialogDp(16))
    }
    private val configure = TextView(context).apply {
        text = "前往模型配置"
        textSize = 14f
        gravity = Gravity.CENTER
        minimumHeight = context.dialogDp(48)
        setTextColor(context.getColor(R.color.mt_primary_dark))
        background = context.dialogRipple(R.color.mt_surface_blue, 14)
        isFocusable = true
        isVisible = false
        setOnClickListener {
            dialog.dismiss()
            onConfigure()
        }
    }

    init {
        root.addView(TextView(context).apply {
            text = "选择模型"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.mt_text_primary))
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = context.dialogDp(14)
        })
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = context.dialogDp(10)
        })
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(configure, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        root.requestFocus()
    }

    fun showModels(models: List<ChatModelOption>) {
        if (models.isEmpty()) {
            status.text = "暂无可选模型"
            configure.isVisible = true
            return
        }
        val ordered = models.sortedByDescending(adapter::isActive)
        search.isVisible = true
        fun render() {
            val query = search.text.toString().trim()
            val matches = ordered.filter {
                it.modelName.contains(query, ignoreCase = true) ||
                    it.providerName.contains(query, ignoreCase = true)
            }
            status.text = "没有找到匹配的模型"
            status.isVisible = matches.isEmpty()
            list.isVisible = matches.isNotEmpty()
            adapter.submitList(matches) { list.scrollToPosition(0) }
        }
        search.doAfterTextChanged { render() }
        render()
    }

    private class ModelAdapter(
        private val activeProviderId: Long?,
        private val activeModelName: String?,
        private val onSelected: (ChatModelOption) -> Unit
    ) : ListAdapter<ChatModelOption, ModelAdapter.Holder>(object : DiffUtil.ItemCallback<ChatModelOption>() {
        override fun areItemsTheSame(old: ChatModelOption, new: ChatModelOption) =
            old.providerId == new.providerId && old.modelName == new.modelName

        override fun areContentsTheSame(old: ChatModelOption, new: ChatModelOption) = old == new
    }) {
        class Holder(val binding: ItemChatModelBinding) : RecyclerView.ViewHolder(binding.root)

        fun isActive(option: ChatModelOption) =
            option.providerId == activeProviderId && option.modelName == activeModelName

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemChatModelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val option = getItem(position)
            val selected = isActive(option)
            with(holder.binding) {
                root.isSelected = selected
                root.background = root.context.dialogRipple(
                    if (selected) R.color.mt_surface_blue else R.color.mt_surface, 18
                )
                root.setOnClickListener { onSelected(option) }
                modelIcon.setImageResource(chatModelIcon(option.modelName, option.providerName))
                modelName.text = option.modelName
                providerName.text = option.providerName
                selectedCheck.isVisible = selected
            }
        }
    }
}
