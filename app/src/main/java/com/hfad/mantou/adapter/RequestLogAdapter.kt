package com.hfad.mantou.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.R
import com.hfad.mantou.data.logging.ApiLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RequestLogAdapter : ListAdapter<ApiLogEntry, RequestLogAdapter.VH>(DIFF) {

    private val expanded = mutableSetOf<Long>()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_request_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val isExpanded = expanded.contains(entry.id)
        holder.bind(entry, isExpanded) {
            if (expanded.contains(entry.id)) expanded.remove(entry.id)
            else expanded.add(entry.id)
            notifyItemChanged(holder.bindingAdapterPosition)
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerRow: LinearLayout = itemView.findViewById(R.id.headerRow)
        private val dot: View = itemView.findViewById(R.id.dot)
        private val tvModel: TextView = itemView.findViewById(R.id.tvModel)
        private val tvEndpoint: TextView = itemView.findViewById(R.id.tvEndpoint)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvTags: TextView = itemView.findViewById(R.id.tvTags)
        private val ivExpand: ImageView = itemView.findViewById(R.id.ivExpand)
        private val detailBox: LinearLayout = itemView.findViewById(R.id.detailBox)
        private val tvRequestBody: TextView = itemView.findViewById(R.id.tvRequestBody)
        private val tvResponseBody: TextView = itemView.findViewById(R.id.tvResponseBody)

        fun bind(entry: ApiLogEntry, isExpanded: Boolean, onToggle: () -> Unit) {
            tvModel.text = entry.model ?: "(未指定模型)"
            tvEndpoint.text = entry.endpointLabel
            tvTime.text = timeFormatter.format(Date(entry.timestampMs))

            val statusText = when {
                entry.errorMessage != null && entry.httpStatus == null -> "异常"
                entry.httpStatus != null -> "HTTP ${entry.httpStatus}"
                else -> "未知"
            }
            val streamLabel = if (entry.isStream) "流式" else "非流式"
            tvTags.text = "${entry.provider} · $streamLabel · $statusText"
            tvTags.setTextColor(
                if (entry.success) 0xFF27AE60.toInt() else 0xFFE65353.toInt()
            )

            dot.setBackgroundResource(
                if (entry.success) R.drawable.bg_dot_success else R.drawable.bg_dot_error
            )

            ivExpand.setImageResource(
                if (isExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
            )
            detailBox.visibility = if (isExpanded) View.VISIBLE else View.GONE
            if (isExpanded) {
                tvRequestBody.text = entry.requestBody.ifBlank { "(无请求体)" }
                val responseText = buildString {
                    if (entry.errorMessage != null) {
                        append("错误: ${entry.errorMessage}\n\n")
                    }
                    append(entry.responseBody.ifBlank { "(无响应体)" })
                }
                tvResponseBody.text = responseText
            }

            headerRow.setOnClickListener { onToggle() }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ApiLogEntry>() {
            override fun areItemsTheSame(oldItem: ApiLogEntry, newItem: ApiLogEntry): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ApiLogEntry, newItem: ApiLogEntry): Boolean =
                oldItem == newItem
        }
    }
}
