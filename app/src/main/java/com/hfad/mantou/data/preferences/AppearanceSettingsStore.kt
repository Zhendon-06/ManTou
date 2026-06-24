package com.hfad.mantou.data.preferences

import android.content.Context
import android.graphics.Color
import kotlin.math.roundToInt

object AppearanceSettingsStore {

    private const val PREFS_NAME = "appearance_settings"
    private const val KEY_BACKGROUND_BLUR = "background_blur"
    private const val KEY_MASK_STRENGTH = "mask_strength"
    private const val KEY_MASK_TONE = "mask_tone"
    private const val KEY_CHAT_TEXT_SIZE_SP = "chat_text_size_sp"
    private const val KEY_CHAT_TEXT_COLOR = "chat_text_color"

    const val AUTO_TEXT_COLOR = Int.MIN_VALUE
    const val DEFAULT_BACKGROUND_BLUR = 8f
    const val DEFAULT_MASK_STRENGTH = 0.18f
    const val DEFAULT_MASK_TONE = 1f
    const val DEFAULT_CHAT_TEXT_SIZE_SP = 14f

    data class Settings(
        val backgroundBlur: Float = DEFAULT_BACKGROUND_BLUR,
        val maskStrength: Float = DEFAULT_MASK_STRENGTH,
        val maskTone: Float = DEFAULT_MASK_TONE,
        val chatTextSizeSp: Float = DEFAULT_CHAT_TEXT_SIZE_SP,
        val chatTextColor: Int = AUTO_TEXT_COLOR
    ) {
        val hasFixedTextColor: Boolean
            get() = chatTextColor != AUTO_TEXT_COLOR
    }

    fun getSettings(context: Context): Settings {
        val prefs = prefs(context)
        return Settings(
            backgroundBlur = prefs.getFloat(KEY_BACKGROUND_BLUR, DEFAULT_BACKGROUND_BLUR),
            maskStrength = prefs.getFloat(KEY_MASK_STRENGTH, DEFAULT_MASK_STRENGTH),
            maskTone = prefs.getFloat(KEY_MASK_TONE, DEFAULT_MASK_TONE),
            chatTextSizeSp = prefs.getFloat(KEY_CHAT_TEXT_SIZE_SP, DEFAULT_CHAT_TEXT_SIZE_SP),
            chatTextColor = prefs.getInt(KEY_CHAT_TEXT_COLOR, AUTO_TEXT_COLOR)
        )
    }

    fun setBackgroundBlur(context: Context, value: Float) {
        putFloat(context, KEY_BACKGROUND_BLUR, value.coerceIn(0f, 30f))
    }

    fun setMaskStrength(context: Context, value: Float) {
        putFloat(context, KEY_MASK_STRENGTH, value.coerceIn(0f, 0.8f))
    }

    fun setMaskTone(context: Context, value: Float) {
        putFloat(context, KEY_MASK_TONE, value.coerceIn(-1f, 1f))
    }

    fun setChatTextSizeSp(context: Context, value: Float) {
        putFloat(context, KEY_CHAT_TEXT_SIZE_SP, value.coerceIn(12f, 22f))
    }

    fun setChatTextColor(context: Context, color: Int) {
        prefs(context)
            .edit()
            .putInt(KEY_CHAT_TEXT_COLOR, color)
            .apply()
    }

    fun setAutoChatTextColor(context: Context) {
        setChatTextColor(context, AUTO_TEXT_COLOR)
    }

    fun parseColorOrNull(raw: String): Int? {
        val normalized = raw.trim().removePrefix("#")
        if (normalized.length != 6 && normalized.length != 8) return null
        return runCatching { Color.parseColor("#$normalized") }.getOrNull()
    }

    fun colorToHex(color: Int): String {
        return "#%06X".format(0xFFFFFF and color)
    }

    fun maskColor(settings: Settings): Int {
        val channel = (((settings.maskTone.coerceIn(-1f, 1f) + 1f) / 2f) * 255).roundToInt()
        val alpha = (settings.maskStrength.coerceIn(0f, 0.8f) * 255).roundToInt()
        return Color.argb(alpha, channel, channel, channel)
    }

    private fun putFloat(context: Context, key: String, value: Float) {
        prefs(context)
            .edit()
            .putFloat(key, value)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
