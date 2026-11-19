package com.hfad.mantou.data.preferences

import android.content.Context

internal data class OnlineSearchSettings(val enabled: Boolean, val tavilyKey: String)

internal object OnlineSearchSettingsStore {
    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("online_search", Context.MODE_PRIVATE)

    fun load(context: Context) = preferences(context).let {
        OnlineSearchSettings(it.getBoolean("enabled", false), it.getString("tavily_key", "").orEmpty())
    }

    fun save(context: Context, settings: OnlineSearchSettings) {
        preferences(context).edit()
            .putBoolean("enabled", settings.enabled)
            .putString("tavily_key", settings.tavilyKey)
            .apply()
    }
}
