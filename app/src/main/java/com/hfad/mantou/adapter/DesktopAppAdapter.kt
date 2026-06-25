package com.hfad.mantou.adapter

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

    fun submit(newItems: List<DesktopAppItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
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

    override fun getItemCount(): Int = items.size

    inner class DesktopAppViewHolder(
        private val binding: ItemDesktopWebappBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DesktopAppItem) {
            binding.ivAppIcon.setImageResource(item.iconRes)
            binding.tvAppName.text = item.displayName
            binding.root.setOnClickListener {
                onAppClick(item, binding.root)
            }
        }
    }
}
