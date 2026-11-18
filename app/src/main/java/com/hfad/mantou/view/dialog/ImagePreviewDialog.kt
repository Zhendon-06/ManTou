package com.hfad.mantou.view.dialog

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.hfad.mantou.R

internal class ImagePreviewDialog(
    context: Context,
    private val imagePath: String
) : ManTouFullscreenDialog(context, R.color.black) {
    private val requests = Glide.with(context)
    private val preview = ImageView(this.context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.dialog_image_preview)
        setOnClickListener { dismiss() }
    }

    init {
        val panel = FrameLayout(this.context).apply {
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_dialog_close)
                contentDescription = context.getString(R.string.dialog_close)
                background = context.dialogRipple(R.color.mt_generate_surface_soft, 24, onDark = true)
                setOnClickListener { dismiss() }
            }, FrameLayout.LayoutParams(context.dialogDp(48), context.dialogDp(48), Gravity.TOP or Gravity.END).apply {
                topMargin = context.dialogDp(12)
                marginEnd = context.dialogDp(16)
            })
        }
        setFullscreenContent(panel)
    }

    override fun onStart() {
        super.onStart()
        requests.load(imagePath).fitCenter().into(preview)
    }

    override fun onStop() {
        requests.clear(preview)
        super.onStop()
    }
}
