package com.hfad.mantou.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hfad.mantou.R
import com.hfad.mantou.data.GenerateTaskState
import com.hfad.mantou.databinding.ItemChatHarnessActivityBinding
import com.hfad.mantou.databinding.ItemChatHarnessEventBinding

internal class HarnessActivityRenderer(
    private val onEventExpandedChanged: ((GenerateTaskState.HarnessEvent, Boolean) -> Unit)? = null
) {

    private var expansionSessionId: Long? = null
    private val expandedEventKeys = mutableSetOf<String>()
    private val collapsedEventKeys = mutableSetOf<String>()

    fun createViewHolder(parent: ViewGroup): HarnessActivityViewHolder {
        val binding = ItemChatHarnessActivityBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HarnessActivityViewHolder(binding)
    }

    inner class HarnessActivityViewHolder(
        private val binding: ItemChatHarnessActivityBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderedSessionId: Long? = null
        private var renderedEventCount = 0

        fun bind(
            state: GenerateTaskState,
            primaryColor: Int,
            secondaryColor: Int,
            mutedColor: Int
        ) {
            binding.tvHarnessLead.setTextColor(primaryColor)
            binding.tvHarnessResult.setTextColor(primaryColor)
            if (expansionSessionId != state.sessionId) {
                expansionSessionId = state.sessionId
                expandedEventKeys.clear()
                collapsedEventKeys.clear()
            }
            val events = state.harnessEvents
            if (renderedSessionId == state.sessionId && events.size < renderedEventCount) {
                expandedEventKeys.clear()
                collapsedEventKeys.clear()
            }
            val isNewEvent = renderedSessionId == state.sessionId && events.size > renderedEventCount
            if (events.isEmpty()) {
                binding.tvHarnessLead.text = leadText(null)
                binding.tvHarnessResult.text = resultText(state)
                binding.tvHarnessResult.visibility = if (binding.tvHarnessResult.text.isNullOrBlank()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                binding.harnessEventContainer.removeAllViews()
                binding.root.contentDescription = "代码生成流程，正在准备"
                renderedSessionId = state.sessionId
                renderedEventCount = 0
                return
            }
            binding.tvHarnessLead.text = leadText(events.firstOrNull())
            binding.tvHarnessResult.text = resultText(state)
            binding.tvHarnessResult.visibility = if (binding.tvHarnessResult.text.isNullOrBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            binding.harnessEventContainer.removeAllViews()

            events.forEachIndexed { index, event ->
                val eventBinding = ItemChatHarnessEventBinding.inflate(
                    LayoutInflater.from(binding.root.context),
                    binding.harnessEventContainer,
                    false
                )
                bindEvent(eventBinding, event, secondaryColor, mutedColor)
                binding.harnessEventContainer.addView(eventBinding.root)
                if (isNewEvent && index == events.lastIndex) {
                    eventBinding.root.alpha = 0f
                    eventBinding.root.translationY = dp(6f)
                    eventBinding.root.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(180L)
                        .start()
                }
            }

            val latestEvent = events.last()
            val latestIteration = maxOf(state.harnessIteration, latestEvent.iteration, 1)
            binding.root.contentDescription =
                "代码生成流程，第${latestIteration}轮，当前${stageLabel(latestEvent.stage)}，${latestEvent.message}"
            renderedSessionId = state.sessionId
            renderedEventCount = events.size
        }

        private fun bindEvent(
            eventBinding: ItemChatHarnessEventBinding,
            event: GenerateTaskState.HarnessEvent,
            secondaryColor: Int,
            mutedColor: Int
        ) {
            val context = eventBinding.root.context
            val key = eventKey(event)
            val diagnostics = event.diagnostics
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString("\n")
            val hasDiagnostics = diagnostics.isNotEmpty()
            val diagnosticsExpanded = when {
                key in collapsedEventKeys -> false
                key in expandedEventKeys -> true
                event.outcome == GenerateTaskState.Outcome.FAILED -> true
                else -> false
            }
            val stateColor = when (event.outcome) {
                GenerateTaskState.Outcome.FAILED -> ContextCompat.getColor(context, R.color.mt_error)
                GenerateTaskState.Outcome.RETRYING -> ContextCompat.getColor(context, R.color.mt_primary_dark)
                else -> secondaryColor
            }

            eventBinding.tvHarnessEventText.text = event.message
            eventBinding.tvHarnessEventMeta.text = buildString {
                append(stageLabel(event.stage))
                if (event.iteration > 0) append(" · 第 ${event.iteration} 轮")
            }
            eventBinding.tvHarnessEventText.setTextColor(stateColor)
            eventBinding.tvHarnessEventMeta.setTextColor(mutedColor)
            eventBinding.progressHarnessEvent.visibility =
                if (event.outcome == GenerateTaskState.Outcome.RUNNING) View.VISIBLE else View.GONE
            eventBinding.ivHarnessEventState.visibility =
                if (event.outcome == GenerateTaskState.Outcome.RUNNING) View.GONE else View.VISIBLE
            eventBinding.ivHarnessEventState.setImageResource(
                when (event.outcome) {
                    GenerateTaskState.Outcome.PASSED -> R.drawable.ic_harness_check
                    GenerateTaskState.Outcome.FAILED -> R.drawable.ic_harness_error
                    GenerateTaskState.Outcome.RETRYING -> R.drawable.ic_harness_retry
                    GenerateTaskState.Outcome.RUNNING -> R.drawable.ic_harness_check
                }
            )
            eventBinding.ivHarnessEventState.imageTintList = ColorStateList.valueOf(stateColor)
            eventBinding.progressHarnessEvent.indeterminateTintList = ColorStateList.valueOf(mutedColor)

            eventBinding.ivHarnessEventExpand.visibility =
                if (hasDiagnostics) View.VISIBLE else View.GONE
            eventBinding.ivHarnessEventExpand.setImageResource(
                if (diagnosticsExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
            )
            eventBinding.ivHarnessEventExpand.imageTintList = ColorStateList.valueOf(mutedColor)
            eventBinding.tvHarnessEventDiagnostics.text = diagnostics
            eventBinding.tvHarnessEventDiagnostics.visibility =
                if (hasDiagnostics && diagnosticsExpanded) View.VISIBLE else View.GONE
            eventBinding.harnessEventHeader.isClickable = hasDiagnostics
            eventBinding.harnessEventHeader.isFocusable = hasDiagnostics
            eventBinding.harnessEventHeader.setOnClickListener(
                if (!hasDiagnostics) {
                    null
                } else {
                    View.OnClickListener {
                        val expanded = eventBinding.tvHarnessEventDiagnostics.visibility != View.VISIBLE
                        if (expanded) {
                            expandedEventKeys += key
                            collapsedEventKeys -= key
                        } else {
                            collapsedEventKeys += key
                            expandedEventKeys -= key
                        }
                        eventBinding.tvHarnessEventDiagnostics.visibility =
                            if (expanded) View.VISIBLE else View.GONE
                        eventBinding.ivHarnessEventExpand.setImageResource(
                            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
                        )
                        eventBinding.harnessEventHeader.contentDescription =
                            eventContentDescription(event, expanded)
                        onEventExpandedChanged?.invoke(event, expanded)
                    }
                }
            )
            eventBinding.harnessEventHeader.contentDescription =
                eventContentDescription(event, diagnosticsExpanded, hasDiagnostics)
        }

        private fun dp(value: Float): Float {
            return value * binding.root.resources.displayMetrics.density
        }
    }

    private fun eventKey(event: GenerateTaskState.HarnessEvent): String {
        return "${event.timestamp}:${event.iteration}:${event.stage}:${event.outcome}:${event.message}"
    }

    private fun eventContentDescription(
        event: GenerateTaskState.HarnessEvent,
        diagnosticsExpanded: Boolean,
        hasDiagnostics: Boolean = true
    ): String {
        return buildString {
            append(stageLabel(event.stage))
            append("，")
            append(event.message)
            if (hasDiagnostics) {
                append(if (diagnosticsExpanded) "，收起诊断" else "，展开诊断")
            }
        }
    }

    private fun leadText(firstEvent: GenerateTaskState.HarnessEvent?): String {
        return if (firstEvent?.iteration.orZero() > 1) {
            "我会根据上一轮的诊断继续修改代码，并重新执行构建、运行检查和测试。"
        } else {
            "我会先整理输入与系统指令，再让模型调用本地工具修改代码；随后持续构建、检查和测试，直到可以交付。"
        }
    }

    private fun resultText(state: GenerateTaskState): String {
        val latest = state.harnessEvents.lastOrNull() ?: return ""
        return when {
            state.phase == GenerateTaskState.Phase.COMPLETED ||
                (latest.stage == GenerateTaskState.Stage.DELIVER &&
                    latest.outcome == GenerateTaskState.Outcome.PASSED) -> {
                "代码已经通过构建、WebView 检查、自测和测试集，可以交付。"
            }
            state.phase == GenerateTaskState.Phase.ERROR -> {
                "流程已停止：${state.status}"
            }
            latest.outcome == GenerateTaskState.Outcome.FAILED -> {
                "检查发现问题，诊断会返回给模型继续修复。"
            }
            latest.outcome == GenerateTaskState.Outcome.RETRYING -> {
                "正在根据诊断继续修改；完成后会重新构建并运行测试。"
            }
            else -> ""
        }
    }

    private fun stageLabel(stage: GenerateTaskState.Stage): String {
        return when (stage) {
            GenerateTaskState.Stage.INPUT -> "输入过滤"
            GenerateTaskState.Stage.PROMPT -> "系统提示词"
            GenerateTaskState.Stage.MODEL -> "模型编排"
            GenerateTaskState.Stage.TOOL -> "编辑文件"
            GenerateTaskState.Stage.BUILD -> "构建"
            GenerateTaskState.Stage.INSPECT -> "WebView 检查"
            GenerateTaskState.Stage.SELF_TEST -> "自测"
            GenerateTaskState.Stage.TEST -> "测试集"
            GenerateTaskState.Stage.DELIVER -> "交付"
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

}
