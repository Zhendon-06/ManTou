package com.hfad.mantou.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.hfad.mantou.R
import com.hfad.mantou.data.preferences.AppearanceSettingsStore
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AutoContrastColor {

    private const val SAMPLE_LONG_EDGE = 32
    private const val SAMPLE_SHORT_EDGE_MIN = 12
    private const val TARGET_CONTRAST = 3.0
    private const val CONTRAST_PERCENTILE = 0.25
    private const val MAX_AUTO_MASK_STRENGTH = 0.24f
    private const val FALLBACK_STATUS_BAR_FRACTION = 0.06f
    private const val FALLBACK_NAVIGATION_BAR_FRACTION = 0.04f

    data class Analysis(
        val textColor: Int,
        val maskColor: Int,
        val statusBarTextColor: Int,
        val navigationBarTextColor: Int
    )

    private data class Samples(
        val colors: IntArray,
        val width: Int,
        val height: Int,
        val statusBarRows: Int,
        val navigationBarRows: Int
    )

    private data class Candidate(
        val textColor: Int,
        val maskStrength: Float,
        val percentileContrast: Double,
        val overallScore: Double,
        val reachesTarget: Boolean
    )

    fun analyze(
        context: Context,
        settings: AppearanceSettingsStore.Settings,
        wallpaperDrawable: Drawable?
    ): Analysis {
        val samples = sampleColors(context, wallpaperDrawable)
        if (wallpaperDrawable != null && !settings.hasFixedTextColor) {
            return automaticAnalysis(settings, samples)
        }
        val maskColor = if (wallpaperDrawable == null) {
                Color.TRANSPARENT
            } else {
                AppearanceSettingsStore.maskColor(settings)
            }
        return buildAnalysis(
            textColor = chooseUnmaskedTextColor(samples.colors),
            maskColor = maskColor,
            samples = samples
        )
    }

    fun resolve(
        context: Context,
        settings: AppearanceSettingsStore.Settings,
        wallpaperDrawable: Drawable?
    ): Int = analyze(context, settings, wallpaperDrawable).textColor

    private fun sampleColors(context: Context, drawable: Drawable?): Samples {
        val fallbackColor = ContextCompat.getColor(context, R.color.mt_background)
        if (drawable == null) return Samples(intArrayOf(fallbackColor), 1, 1, 1, 1)

        val bitmap = drawableToBitmap(context, drawable)
            ?: return Samples(intArrayOf(fallbackColor), 1, 1, 1, 1)
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (index in pixels.indices) {
                if (Color.alpha(pixels[index]) < 255) {
                    pixels[index] = ColorUtils.compositeColors(pixels[index], fallbackColor)
                }
            }
            Samples(
                colors = pixels,
                width = bitmap.width,
                height = bitmap.height,
                statusBarRows = systemBarSampleRows(
                    context,
                    bitmap.height,
                    "status_bar_height",
                    FALLBACK_STATUS_BAR_FRACTION
                ),
                navigationBarRows = systemBarSampleRows(
                    context,
                    bitmap.height,
                    "navigation_bar_height",
                    FALLBACK_NAVIGATION_BAR_FRACTION
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun systemBarSampleRows(
        context: Context,
        sampleHeight: Int,
        dimensionName: String,
        fallbackFraction: Float
    ): Int {
        val resources = context.resources
        val viewportHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val dimensionId = resources.getIdentifier(dimensionName, "dimen", "android")
        val barHeight = dimensionId
            .takeIf { it != 0 }
            ?.let(resources::getDimensionPixelSize)
            ?.takeIf { it > 0 }
        val fraction = if (barHeight != null) {
            barHeight.toFloat() / viewportHeight
        } else {
            fallbackFraction
        }
        return ceil(sampleHeight * fraction)
            .toInt()
            .coerceIn(1, sampleHeight)
    }

    private fun drawableToBitmap(context: Context, drawable: Drawable): Bitmap? {
        val metrics = context.resources.displayMetrics
        val viewportWidth = metrics.widthPixels.coerceAtLeast(1)
        val viewportHeight = metrics.heightPixels.coerceAtLeast(1)
        val viewportRatio = viewportWidth.toFloat() / viewportHeight.toFloat()
        val sampleWidth: Int
        val sampleHeight: Int
        if (viewportRatio >= 1f) {
            sampleWidth = SAMPLE_LONG_EDGE
            sampleHeight = (SAMPLE_LONG_EDGE / viewportRatio)
                .roundToInt()
                .coerceIn(SAMPLE_SHORT_EDGE_MIN, SAMPLE_LONG_EDGE)
        } else {
            sampleHeight = SAMPLE_LONG_EDGE
            sampleWidth = (SAMPLE_LONG_EDGE * viewportRatio)
                .roundToInt()
                .coerceIn(SAMPLE_SHORT_EDGE_MIN, SAMPLE_LONG_EDGE)
        }

        return runCatching {
            Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) {
                    drawCenterCrop(canvas, drawable.bitmap, sampleWidth, sampleHeight)
                } else {
                    drawCenterCrop(canvas, drawable, sampleWidth, sampleHeight)
                }
            }
        }.getOrNull()
    }

    private fun drawCenterCrop(canvas: Canvas, source: Bitmap, targetWidth: Int, targetHeight: Int) {
        val sourceRatio = source.width.toFloat() / source.height.coerceAtLeast(1)
        val targetRatio = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
        val sourceRect = if (sourceRatio > targetRatio) {
            val cropWidth = (source.height * targetRatio).roundToInt().coerceAtLeast(1)
            val left = ((source.width - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + cropWidth).coerceAtMost(source.width), source.height)
        } else {
            val cropHeight = (source.width / targetRatio).roundToInt().coerceAtLeast(1)
            val top = ((source.height - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, source.width, (top + cropHeight).coerceAtMost(source.height))
        }
        canvas.drawBitmap(
            source,
            sourceRect,
            Rect(0, 0, targetWidth, targetHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
    }

    private fun drawCenterCrop(canvas: Canvas, drawable: Drawable, targetWidth: Int, targetHeight: Int) {
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetWidth
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetHeight
        val scale = max(
            targetWidth.toFloat() / sourceWidth,
            targetHeight.toFloat() / sourceHeight
        )
        val drawWidth = (sourceWidth * scale).roundToInt()
        val drawHeight = (sourceHeight * scale).roundToInt()
        val left = (targetWidth - drawWidth) / 2
        val top = (targetHeight - drawHeight) / 2
        val previousBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(left, top, left + drawWidth, top + drawHeight)
            drawable.draw(canvas)
        } finally {
            drawable.bounds = previousBounds
        }
    }

    private fun chooseUnmaskedTextColor(samples: IntArray): Int {
        val blackScore = contrastScore(samples, Color.BLACK)
        val whiteScore = contrastScore(samples, Color.WHITE)
        return if (blackScore >= whiteScore) Color.BLACK else Color.WHITE
    }

    private fun contrastScore(samples: IntArray, textColor: Int): Double {
        return samples.sumOf { background ->
            ln(ColorUtils.calculateContrast(textColor, background).coerceAtLeast(1.0))
        } / samples.size.coerceAtLeast(1)
    }

    private fun automaticAnalysis(
        settings: AppearanceSettingsStore.Settings,
        samples: Samples
    ): Analysis {
        val maximumStrength = min(
            settings.maskStrength.coerceIn(0f, 0.8f),
            MAX_AUTO_MASK_STRENGTH
        )
        val candidates = listOf(Color.BLACK, Color.WHITE).map { textColor ->
            analyzeCandidate(settings, samples.colors, textColor, maximumStrength)
        }
        val qualifyingCandidates = candidates.filter(Candidate::reachesTarget)
        val chosen = if (qualifyingCandidates.isNotEmpty()) {
            qualifyingCandidates.minWithOrNull(
                compareBy<Candidate> { it.maskStrength }
                    .thenByDescending { it.overallScore }
                    .thenByDescending { it.percentileContrast }
            )!!
        } else {
            candidates.maxWithOrNull(
                compareBy<Candidate> { it.overallScore }
                    .thenBy { it.percentileContrast }
            )!!
        }
        val maskColor = if (chosen.maskStrength > 0f) {
            AppearanceSettingsStore.maskColor(settings, chosen.maskStrength)
        } else {
            Color.TRANSPARENT
        }
        return buildAnalysis(
            textColor = chosen.textColor,
            maskColor = maskColor,
            samples = samples
        )
    }

    private fun buildAnalysis(
        textColor: Int,
        maskColor: Int,
        samples: Samples
    ): Analysis {
        return Analysis(
            textColor = textColor,
            maskColor = maskColor,
            statusBarTextColor = chooseRegionTextColor(samples, maskColor, fromTop = true),
            navigationBarTextColor = chooseRegionTextColor(samples, maskColor, fromTop = false)
        )
    }

    private fun chooseRegionTextColor(
        samples: Samples,
        maskColor: Int,
        fromTop: Boolean
    ): Int {
        val rowCount = if (fromTop) samples.statusBarRows else samples.navigationBarRows
        val firstRow = if (fromTop) 0 else samples.height - rowCount
        val regionColors = IntArray(rowCount * samples.width)
        val horizontalRegionWidth = (samples.width * 0.3f)
            .roundToInt()
            .coerceIn(1, samples.width)
        val centerStart = ((samples.width - horizontalRegionWidth) / 2).coerceAtLeast(0)
        val centerEnd = (centerStart + horizontalRegionWidth).coerceAtMost(samples.width)
        var destination = 0
        for (row in firstRow until firstRow + rowCount) {
            val sourceOffset = row * samples.width
            for (column in 0 until samples.width) {
                val isStatusBarColumn =
                    column < horizontalRegionWidth || column >= samples.width - horizontalRegionWidth
                val isNavigationBarColumn = column in centerStart until centerEnd
                if ((fromTop && !isStatusBarColumn) || (!fromTop && !isNavigationBarColumn)) {
                    continue
                }
                val background = samples.colors[sourceOffset + column]
                regionColors[destination++] = if (Color.alpha(maskColor) > 0) {
                    ColorUtils.compositeColors(maskColor, background)
                } else {
                    background
                }
            }
        }
        return chooseUnmaskedTextColor(regionColors.copyOf(destination))
    }

    private fun analyzeCandidate(
        settings: AppearanceSettingsStore.Settings,
        samples: IntArray,
        textColor: Int,
        maximumStrength: Float
    ): Candidate {
        val overallScore = contrastScore(samples, textColor)
        val baseContrast = contrastPercentile(samples, textColor, settings, 0f)
        if (baseContrast >= TARGET_CONTRAST || maximumStrength <= 0f) {
            return Candidate(textColor, 0f, baseContrast, overallScore, baseContrast >= TARGET_CONTRAST)
        }

        val maximumAlpha = (maximumStrength * 255f).toInt()
        for (alpha in 1..maximumAlpha) {
            val strength = alpha / 255f
            val contrast = contrastPercentile(samples, textColor, settings, strength)
            if (contrast >= TARGET_CONTRAST) {
                return Candidate(textColor, strength, contrast, overallScore, reachesTarget = true)
            }
        }
        return Candidate(textColor, 0f, baseContrast, overallScore, reachesTarget = false)
    }

    private fun contrastPercentile(
        samples: IntArray,
        textColor: Int,
        settings: AppearanceSettingsStore.Settings,
        maskStrength: Float
    ): Double {
        val maskColor = AppearanceSettingsStore.maskColor(settings, maskStrength)
        val contrasts = DoubleArray(samples.size) { index ->
            val background = if (maskStrength > 0f) {
                ColorUtils.compositeColors(maskColor, samples[index])
            } else {
                samples[index]
            }
            ColorUtils.calculateContrast(textColor, background)
        }
        contrasts.sort()
        val percentileIndex = ((contrasts.lastIndex) * CONTRAST_PERCENTILE)
            .roundToInt()
            .coerceIn(0, contrasts.lastIndex.coerceAtLeast(0))
        return contrasts[percentileIndex]
    }
}
