package com.hfad.mantou.data.preferences

import android.content.Context
import com.hfad.mantou.utils.harness.HarnessIntensity

object HarnessSettingsStore {
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("harness_settings", Context.MODE_PRIVATE)

    fun intensity(context: Context): HarnessIntensity =
        HarnessIntensity.fromStored(prefs(context).getString("intensity", null))

    fun setIntensity(context: Context, intensity: HarnessIntensity) {
        prefs(context).edit().putString("intensity", intensity.name).apply()
    }
}
