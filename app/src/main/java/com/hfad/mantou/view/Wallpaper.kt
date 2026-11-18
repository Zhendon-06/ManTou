package com.hfad.mantou.view

import android.net.Uri
import android.widget.ImageView
import com.hfad.mantou.R

/** Displays the custom wallpaper, or the bundled wallpaper if loading fails. */
internal fun ImageView.showWallpaper(customUri: Uri?): Boolean {
    if (customUri != null && runCatching {
            setImageURI(customUri)
            drawable != null
        }.getOrDefault(false)
    ) {
        return true
    }
    setImageResource(R.drawable.wallpaper_default)
    return false
}
