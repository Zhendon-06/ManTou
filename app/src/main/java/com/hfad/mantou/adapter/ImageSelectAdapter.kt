package com.hfad.mantou.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hfad.mantou.R
import com.hfad.mantou.data.ImageItem
import com.hfad.mantou.databinding.ItemImageSelectBinding

/**
 * 图片选择适配器
 * 支持多选，显示选中状态
 */
class ImageSelectAdapter(
    private val onSelectionChanged: ((List<ImageItem>) -> Unit)? = null
) : RecyclerView.Adapter<ImageSelectAdapter.ImageViewHolder>() {

    private val imageList = mutableListOf<ImageItem>()

    /**
     * 设置图片列表
     */
    fun setImages(images: List<ImageItem>) {
        imageList.clear()
        imageList.addAll(images)
        notifyDataSetChanged()
    }

    /**
     * 获取选中的图片列表
     */
    fun getSelectedImages(): List<ImageItem> {
        return imageList.filter { it.isSelected }
    }

    /**
     * 清除所有选中状态
     */
    fun clearSelection() {
        imageList.forEachIndexed { index, item ->
            if (item.isSelected) {
                item.isSelected = false
                notifyItemChanged(index)
            }
        }
        onSelectionChanged?.invoke(emptyList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageSelectBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(imageList[position])
    }

    override fun getItemCount(): Int = imageList.size

    inner class ImageViewHolder(
        private val binding: ItemImageSelectBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 设置正方形高度
            binding.root.post {
                val width = binding.root.width
                if (width > 0) {
                    val layoutParams = binding.root.layoutParams
                    layoutParams.height = width
                    binding.root.layoutParams = layoutParams
                }
            }

            // 点击切换选中状态
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = imageList[position]
                    item.isSelected = !item.isSelected
                    updateSelectionUI(item.isSelected)
                    onSelectionChanged?.invoke(getSelectedImages())
                }
            }
        }

        fun bind(item: ImageItem) {
            // 使用 Glide 加载图片
            Glide.with(binding.ivImage.context)
                .load(item.uri)
                .centerCrop()
                .placeholder(R.drawable.ic_launcher_background)
                .into(binding.ivImage)

            // 更新选中状态UI
            updateSelectionUI(item.isSelected)
        }

        private fun updateSelectionUI(isSelected: Boolean) {
            binding.viewOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }
}




