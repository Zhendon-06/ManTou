package com.hfad.mantou.view.markdown

import android.content.Context
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import com.hfad.mantou.R

class MarkdownTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private val renderer by lazy(LazyThreadSafetyMode.NONE) { createMarkdownRenderer(this.context) }
    private var requestedMarkdown: String? = null
    private var renderedMarkdown: String? = null
    private var renderedTextSize = 0f
    private var selectionActionMode: ActionMode? = null

    internal val isSelectingText: Boolean
        get() = selectionActionMode != null || hasSelection()

    // Keep TextView's native selection gestures while recognizing a quick tap on a link.
    private val linkTapDetector by lazy(LazyThreadSafetyMode.NONE) {
        GestureDetector(this.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isSelectingText) return false
                val link = linkAt(e.x, e.y) ?: return false
                link.onClick(this@MarkdownTextView)
                return true
            }
        })
    }

    init {
        setTextIsSelectable(true)
        setLinkTextColor(this.context.getColor(R.color.mt_primary_dark))
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                selectionActionMode = mode
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

            override fun onDestroyActionMode(mode: ActionMode) {
                selectionActionMode = null
                post { renderRequestedMarkdown() }
            }
        }
    }

    fun renderMarkdown(markdown: String) {
        requestedMarkdown = markdown
        renderRequestedMarkdown()
    }

    internal fun clearMarkdown() {
        requestedMarkdown = null
        selectionActionMode?.finish()
        if (renderedMarkdown != null) text = null
        renderedMarkdown = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val openedLink = linksClickable && linkTapDetector.onTouchEvent(event)
        val handled = super.onTouchEvent(event)
        return openedLink || handled
    }

    private fun renderRequestedMarkdown() {
        val markdown = requestedMarkdown ?: return
        if (isSelectingText || (markdown == renderedMarkdown && textSize == renderedTextSize)) return

        if (markdown.isBlank()) {
            text = null
        } else {
            renderer.setMarkdown(this, markdown)
        }
        renderedMarkdown = markdown
        renderedTextSize = textSize
    }

    private fun linkAt(x: Float, y: Float): ClickableSpan? {
        val content = text as? Spanned ?: return null
        val textLayout = layout ?: return null
        val textY = y - totalPaddingTop + scrollY
        if (textY < 0 || textY >= textLayout.height) return null

        val line = textLayout.getLineForVertical(textY.toInt())
        val textX = x - totalPaddingLeft + scrollX
        if (textX < textLayout.getLineLeft(line) || textX > textLayout.getLineRight(line)) return null

        val offset = textLayout.getOffsetForHorizontal(line, textX)
        return content.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}
