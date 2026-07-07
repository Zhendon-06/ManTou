package com.hfad.mantou.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.databinding.ItemDesktopWebappBinding
import com.hfad.mantou.utils.DesktopAppScanner.DesktopAppItem

class DesktopAppAdapter(
    private val onAppClick: (DesktopAppItem, View) -> Unit
) : RecyclerView.Adapter<DesktopAppAdapter.DesktopAppViewHolder>() {

    private val items = mutableListOf<DesktopAppItem>()
    private var textColor: Int = Color.BLACK

    fun submit(newItems: List<DesktopAppItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateTextColor(color: Int) {
        if (textColor == color) return
        textColor = color
        notifyItemRangeChanged(0, itemCount, PAYLOAD_TEXT_COLOR)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DesktopAppViewHolder {
        val binding = ItemDesktopWebappBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DesktopAppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DesktopAppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(
        holder: DesktopAppViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_TEXT_COLOR)) {
            holder.applyTextColor()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = items.size

    inner class DesktopAppViewHolder(
        private val binding: ItemDesktopWebappBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DesktopAppItem) {
            binding.ivAppIcon.setImageResource(item.iconRes)
            binding.tvAppName.text = item.displayName
            applyTextColor()
            binding.root.setOnClickListener {
                onAppClick(item, binding.root)
            }
        }

        fun applyTextColor() {
            binding.tvAppName.setTextColor(textColor)
        }
    }

    companion object {
        private const val PAYLOAD_TEXT_COLOR = "text_color"
    }
}
