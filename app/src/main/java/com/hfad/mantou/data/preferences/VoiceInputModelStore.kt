package com.hfad.mantou.data.preferences

import android.content.Context
import com.hfad.mantou.data.api.VoiceInputConfig
import com.hfad.mantou.data.api.VoiceTranscriptionApiService

object VoiceInputModelStore {
    private const val PREF_NAME = "voice_input_model_pref"
    private const val KEY_ENDPOINT = "voice_endpoint"
    private const val KEY_API_KEY = "voice_api_key"
    private const val KEY_MODEL_NAME = "voice_model_name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun setActive(context: Context, config: VoiceInputConfig) {
        prefs(context).edit()
            .putString(KEY_ENDPOINT, config.endpoint)
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL_NAME, config.model)
            .apply()
    }

    fun getActiveConfig(context: Context): VoiceInputConfig? {
        val p = prefs(context)
        val endpoint = p.getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: return null
        val model = p.getString(KEY_MODEL_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        if (model != VoiceTranscriptionApiService.MIMO_ASR_MODEL) return null
        return VoiceInputConfig(
            endpoint = endpoint,
            apiKey = p.getString(KEY_API_KEY, null).orEmpty(),
            model = model
        )
    }

    fun getActiveApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, null).orEmpty()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
