package com.hfad.mantou.view.dialog

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hfad.mantou.R

internal class ManTouSheet(
    context: Context,
    content: View,
    expandedHeightFraction: Float? = null
) : BottomSheetDialog(context, R.style.Theme_ManTou_BottomSheet) {
    init {
        val fraction = expandedHeightFraction ?: 0.90f
        behavior.maxHeight = context.dialogDp((context.resources.configuration.screenHeightDp * fraction).toInt())
        val panel = LinearLayout(this.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context).apply {
                background = context.dialogShape(R.color.mt_outline, 2)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dialogDp(32), context.dialogDp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = context.dialogDp(10)
                bottomMargin = context.dialogDp(6)
            })
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        }
        setContentView(panel)
        if (expandedHeightFraction != null) {
            panel.layoutParams = panel.layoutParams.apply { height = behavior.maxHeight }
        }
        setDismissWithAnimation(true)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setDimAmount(0.42f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
