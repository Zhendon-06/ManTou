package com.hfad.mantou.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.hfad.mantou.R
import com.hfad.mantou.data.preferences.AppearanceSettingsStore

object AutoContrastColor {

    private const val SAMPLE_SIZE = 8
    private const val LUMINANCE_THRESHOLD = 0.5

    fun resolve(
        context: Context,
        settings: AppearanceSettingsStore.Settings,
        wallpaperDrawable: Drawable?
    ): Int {
        val baseRgb = sampleBaseColor(context, wallpaperDrawable)
        val effective = if (wallpaperDrawable != null) {
            ColorUtils.compositeColors(AppearanceSettingsStore.maskColor(settings), baseRgb)
        } else {
            baseRgb
        }
        return if (ColorUtils.calculateLuminance(effective) > LUMINANCE_THRESHOLD) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun sampleBaseColor(context: Context, drawable: Drawable?): Int {
        if (drawable == null) {
            return ContextCompat.getColor(context, R.color.mt_background)
        }
        val bitmap = drawableToBitmap(drawable) ?: return ContextCompat.getColor(context, R.color.mt_background)
        return averageColor(bitmap)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap ?: return null
            return Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, true)
        }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: SAMPLE_SIZE
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: SAMPLE_SIZE
        return runCatching {
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            Bitmap.createScaledBitmap(full, SAMPLE_SIZE, SAMPLE_SIZE, true)
        }.getOrNull()
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
        }
        val n = pixels.size.coerceAtLeast(1)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
