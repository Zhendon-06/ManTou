package com.hfad.mantou.data.preferences

import android.content.Context
import android.net.Uri

object WallpaperStore {

    private const val PREFS_NAME = "wallpaper_settings"
    private const val KEY_WALLPAPER_URI = "wallpaper_uri"

    fun getWallpaperUri(context: Context): Uri? {
        return prefs(context)
            .getString(KEY_WALLPAPER_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
    }

    fun setWallpaperUri(context: Context, uri: Uri) {
        prefs(context)
            .edit()
            .putString(KEY_WALLPAPER_URI, uri.toString())
            .apply()
    }

    fun clearWallpaper(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_WALLPAPER_URI)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
