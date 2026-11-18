package com.hfad.mantou.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.ColorUtils
import com.hfad.mantou.R

class ContextProgressButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.4f)
        color = ColorUtils.setAlphaComponent(context.getColor(R.color.mt_text_primary), 72)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.4f)
        color = context.getColor(R.color.input_action_blue)
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = context.getColor(R.color.mt_text_primary)
    }
    private val arcBounds = RectF()
    private var progressFraction = 0f

    init {
        scaleType = ScaleType.CENTER
        setWillNotDraw(false)
    }

    fun setProgressFraction(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (progressFraction == next) return
        progressFraction = next
        invalidate()
    }

    fun setForegroundColor(color: Int) {
        trackPaint.color = ColorUtils.setAlphaComponent(color, 72)
        innerPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = minOf(width.toFloat(), height.toFloat(), dp(21f))
        if (size <= 0f) return

        val strokeInset = trackPaint.strokeWidth / 2f
        val left = (width - size) / 2f + strokeInset
        val top = (height - size) / 2f + strokeInset
        arcBounds.set(left, top, left + size - strokeInset * 2f, top + size - strokeInset * 2f)

        canvas.drawOval(arcBounds, trackPaint)
        if (progressFraction > 0f) {
            canvas.drawArc(arcBounds, -90f, 360f * progressFraction, false, progressPaint)
        }

        val centerX = arcBounds.centerX()
        val centerY = arcBounds.centerY()
        val innerRadius = arcBounds.width() * 0.17f
        canvas.drawCircle(centerX, centerY, innerRadius, innerPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
