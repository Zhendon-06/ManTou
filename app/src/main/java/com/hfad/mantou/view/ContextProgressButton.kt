package com.hfad.mantou.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.min

class ContextProgressButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.4f)
        color = Color.rgb(221, 230, 243)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.4f)
        color = Color.rgb(44, 131, 216)
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = Color.rgb(44, 131, 216)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
            .coerceAtLeast(0)
            .toFloat()
        if (size <= 0f) return

        val strokeInset = trackPaint.strokeWidth / 2f
        val left = paddingLeft + (width - paddingLeft - paddingRight - size) / 2f + strokeInset
        val top = paddingTop + (height - paddingTop - paddingBottom - size) / 2f + strokeInset
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
