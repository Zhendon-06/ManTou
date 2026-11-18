package com.hfad.mantou.data.preferences

import android.content.Context
import com.hfad.mantou.utils.harness.HarnessValidationProfile

internal object HarnessSettingsStore {
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("harness_settings", Context.MODE_PRIVATE)

    fun validation(context: Context): HarnessValidationProfile = prefs(context).let { preferences ->
        HarnessValidationProfile.fromStored(
            value = preferences.getString("validation", null),
            legacyIntensity = preferences.getString("intensity", null)
        )
    }

    fun setValidation(context: Context, profile: HarnessValidationProfile) {
        prefs(context).edit().putString("validation", profile.name).apply()
    }
}
