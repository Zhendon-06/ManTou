package com.hfad.mantou.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.R

/**
 * 模型列表适配器：单选高亮，点击回调暴露给 Activity 持久化为全局活跃模型。
 */
class ProviderModelAdapter(
    private val onModelClick: (String) -> Unit
) : ListAdapter<String, ProviderModelAdapter.ViewHolder>(DIFF) {

    private var selectedModelName: String? = null

    fun setSelected(modelName: String?) {
        if (selectedModelName == modelName) return
        val prev = selectedModelName
        selectedModelName = modelName
        val list = currentList
        list.indexOfFirst { it == prev }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        list.indexOfFirst { it == modelName }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    fun getSelected(): String? = selectedModelName

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_provider_model, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val modelName = getItem(position)
        holder.bind(modelName, modelName == selectedModelName)
        holder.itemView.setOnClickListener {
            if (modelName != selectedModelName) {
                setSelected(modelName)
                onModelClick(modelName)
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvModelName: TextView = view.findViewById(R.id.tvModelName)
        private val ivTick: ImageView = view.findViewById(R.id.ivSelectedTick)

        fun bind(modelName: String, selected: Boolean) {
            tvModelName.text = modelName
            itemView.isSelected = selected
            ivTick.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        }
    }
}
