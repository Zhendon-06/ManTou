package com.hfad.mantou.view.dialog

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentDialog
import androidx.annotation.ColorRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.hfad.mantou.R

internal open class ManTouFullscreenDialog(
    context: Context,
    @param:ColorRes private val surfaceColor: Int
) : ComponentDialog(context, R.style.Theme_ManTou_FullscreenDialog) {
    protected fun setFullscreenContent(content: View) {
        setContentView(content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        val window = window ?: return
        val surface = context.getColor(surfaceColor)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        window.setBackgroundDrawable(ColorDrawable(surface))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
