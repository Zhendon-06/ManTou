package com.hfad.mantou.view.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView

class CodeLineNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var codeView: TextView? = null
    private val codeTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(text: Editable?) {
            refreshLineNumbers()
        }
    }
    private val codeLayoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        refreshLineNumbers()
    }

    fun bindTo(textView: TextView) {
        if (codeView === textView) return
        unbind()
        codeView = textView
        textView.addTextChangedListener(codeTextWatcher)
        textView.addOnLayoutChangeListener(codeLayoutChangeListener)
        refreshLineNumbers()
    }

    fun refreshLineNumbers() {
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val target = codeView ?: return
        val codeLayout = target.layout ?: return
        val code = target.text
        val numberPaint = paint.apply {
            color = currentTextColor
            textSize = target.paint.textSize
            typeface = target.paint.typeface
            textAlign = Paint.Align.RIGHT
        }
        val drawX = (width - compoundPaddingRight).toFloat()
        val codeTop = target.compoundPaddingTop
        var logicalLineNumber = 1

        for (layoutLine in 0 until codeLayout.lineCount) {
            val characterOffset = codeLayout.getLineStart(layoutLine)
            val startsLogicalLine = layoutLine == 0 ||
                characterOffset > 0 && code.getOrNull(characterOffset - 1) == '\n'
            if (!startsLogicalLine) continue

            val baseline = codeTop + codeLayout.getLineBaseline(layoutLine)
            canvas.drawText(logicalLineNumber.toString(), drawX, baseline.toFloat(), numberPaint)
            logicalLineNumber += 1
        }
    }

    override fun onDetachedFromWindow() {
        unbind()
        super.onDetachedFromWindow()
    }

    private fun unbind() {
        codeView?.removeTextChangedListener(codeTextWatcher)
        codeView?.removeOnLayoutChangeListener(codeLayoutChangeListener)
        codeView = null
    }
}
