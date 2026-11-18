package com.hfad.mantou.view.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.hfad.mantou.R
import kotlin.math.roundToInt

internal class ManTouDialog(
    context: Context,
    title: CharSequence? = null,
    message: CharSequence? = null,
    content: View? = null,
    options: List<Option> = emptyList(),
    primary: Action? = null,
    secondary: Action? = null,
    wide: Boolean = false
) : ComponentDialog(context, R.style.Theme_ManTou_Modal) {
    data class Action(
        val label: CharSequence,
        val destructive: Boolean = false,
        val dismissOnClick: Boolean = true,
        val onClick: (ManTouDialog) -> Unit = {}
    )

    data class Option(
        val label: CharSequence,
        val description: CharSequence? = null,
        @param:DrawableRes val icon: Int? = null,
        val checked: Boolean? = null,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    private val maxWidth = if (wide) 480 else 384
    private val panel = LinearLayout(this.context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        background = context.dialogShape(R.color.mt_surface, 24)
        setPadding(
            context.dialogDp(20), context.dialogDp(if (title == null) 16 else 22),
            context.dialogDp(20), context.dialogDp(16)
        )
    }
    private val messageView = label(14f, R.color.mt_dialog_secondary).apply {
        text = message
        isVisible = !message.isNullOrEmpty()
        setLineSpacing(context.dialogDp(3).toFloat(), 1f)
    }
    private val field = content as? EditText
    private val fieldError = field?.let {
        label(13f, R.color.mt_dialog_danger).apply {
            isVisible = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }
    private val body = LinearLayout(this.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(messageView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        options.forEachIndexed { index, option ->
            addView(optionRow(option), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = when {
                    index > 0 -> context.dialogDp(8)
                    messageView.isVisible -> context.dialogDp(12)
                    else -> 0
                }
            })
        }
        content?.let {
            addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = if (messageView.isVisible || options.isNotEmpty()) context.dialogDp(14) else 0
            })
        }
        fieldError?.let {
            addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = context.dialogDp(6)
                marginStart = context.dialogDp(2)
            })
        }
    }
    val primaryButton = primary?.let { actionButton(it, filled = true) }

    init {
        if (title != null) {
            setTitle(title)
            val heading = label(20f, R.color.mt_text_primary, medium = true).apply {
                text = title
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                ViewCompat.setAccessibilityHeading(this, true)
            }
            panel.addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = context.dialogDp(10)
            })
        }
        val scroll = ScrollView(this.context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        val buttons = listOfNotNull(secondary?.let { actionButton(it, filled = false) }, primaryButton)
        if (buttons.isNotEmpty()) {
            val actions = LinearLayout(this.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBaselineAligned(false)
                gravity = Gravity.BOTTOM
                buttons.forEachIndexed { index, button ->
                    addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = context.dialogDp(10)
                    })
                }
            }
            panel.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = context.dialogDp(16)
            })
        }
        setContentView(panel)
        setCanceledOnTouchOutside(true)
        panel.requestFocus()
        field?.doAfterTextChanged {
            field.isActivated = false
            fieldError?.isVisible = false
        }
    }

    override fun onStart() {
        super.onStart()
        val available = context.resources.configuration.screenWidthDp
        window?.apply {
            setLayout(context.dialogDp((available - 48).coerceIn(1, maxWidth)), WRAP_CONTENT)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
        }
    }

    fun setMessage(message: CharSequence) {
        messageView.text = message
        messageView.isVisible = message.isNotEmpty()
    }

    fun showFieldError(message: CharSequence) {
        field?.isActivated = true
        fieldError?.apply {
            text = message
            isVisible = true
        }
    }

    private fun actionButton(action: Action, filled: Boolean) = Button(context).apply {
        val color = when {
            action.destructive -> R.color.mt_dialog_danger
            filled -> R.color.mt_primary_dark
            else -> R.color.mt_dialog_control
        }
        text = action.label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.getColor(if (filled) R.color.mt_on_primary else R.color.mt_text_primary))
        gravity = Gravity.CENTER
        minHeight = context.dialogDp(48)
        minimumHeight = minHeight
        minWidth = 0
        minimumWidth = 0
        setPadding(context.dialogDp(8), context.dialogDp(8), context.dialogDp(8), context.dialogDp(8))
        backgroundTintList = null
        stateListAnimator = null
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), context.dialogShape(R.color.mt_dialog_secondary, 13))
            addState(intArrayOf(), context.dialogRipple(color, 13, onDark = filled))
        }
        setOnClickListener {
            if (action.dismissOnClick) dismiss()
            action.onClick(this@ManTouDialog)
        }
    }

    private fun optionRow(option: Option) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dialogDp(if (option.description == null) 50 else 68)
        setPadding(context.dialogDp(12), context.dialogDp(10), context.dialogDp(12), context.dialogDp(10))
        background = context.dialogRipple(
            if (option.checked == true) R.color.mt_surface_blue else R.color.mt_dialog_control, 14
        )
        isSelected = option.checked == true
        val accent = if (option.destructive) R.color.mt_dialog_danger else R.color.mt_primary_dark
        option.icon?.let { icon ->
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(context.getColor(accent))
                background = context.dialogShape(
                    if (option.destructive) R.color.mt_dialog_danger_soft else R.color.mt_surface_blue, 12
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dialogDp(9), context.dialogDp(9), context.dialogDp(9), context.dialogDp(9))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dialogDp(40), context.dialogDp(40)).apply {
                marginEnd = context.dialogDp(12)
            })
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val titleColor = if (option.destructive || option.checked == true) accent else R.color.mt_text_primary
            addView(label(15f, titleColor, medium = true).apply {
                text = option.label
            })
            option.description?.let { description ->
                addView(
                    label(13f, R.color.mt_dialog_secondary).apply { text = description },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = context.dialogDp(3)
                    }
                )
            }
        }
        addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val marker = when (option.checked) {
            null -> R.drawable.ic_chevron_right
            true -> R.drawable.ic_check_circle
            false -> R.drawable.ic_dialog_radio
        }
        addView(ImageView(context).apply {
            setImageResource(marker)
            imageTintList = ColorStateList.valueOf(context.getColor(
                if (option.checked == true) accent else R.color.mt_dialog_secondary
            ))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dialogDp(18), context.dialogDp(18)).apply {
            marginStart = context.dialogDp(10)
        })
        isFocusable = true
        ViewCompat.setScreenReaderFocusable(this, true)
        option.checked?.let { checked ->
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"
                    info.isCheckable = true
                    info.isChecked = checked
                }
            })
        }
        setOnClickListener {
            dismiss()
            option.onClick()
        }
    }

    private fun label(size: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(context.getColor(color))
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

internal fun Context.dialogField(
    hint: CharSequence,
    value: CharSequence = "",
    input: Int = InputType.TYPE_CLASS_TEXT,
    multiline: Boolean = false
) = AppCompatEditText(this).apply {
    this.hint = hint
    setSingleLine(!multiline)
    inputType = input
    setText(value)
    setSelection(length())
    textSize = 15f
    setTextColor(context.getColor(R.color.mt_text_primary))
    setHintTextColor(context.getColor(R.color.mt_dialog_secondary))
    backgroundTintList = null
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_activated), context.dialogShape(
            R.color.mt_surface, 12, R.color.mt_dialog_danger
        ))
        addState(intArrayOf(android.R.attr.state_focused), context.dialogShape(
            R.color.mt_surface, 12, R.color.mt_primary_dark
        ))
        addState(intArrayOf(), context.dialogShape(R.color.mt_dialog_control, 12, R.color.mt_outline))
    }
    setPadding(context.dialogDp(14), context.dialogDp(12), context.dialogDp(14), context.dialogDp(12))
    minimumHeight = context.dialogDp(48)
    if (multiline) gravity = Gravity.TOP or Gravity.START
}

internal fun Context.dialogShape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
    setColor(getColor(color))
    cornerRadius = dialogDp(radius).toFloat()
    stroke?.let { setStroke(dialogDp(1), getColor(it)) }
}

internal fun Context.dialogRipple(color: Int, radius: Int, onDark: Boolean = false) = RippleDrawable(
    ColorStateList.valueOf(if (onDark) 0x26FFFFFF else 0x141F5FBF),
    dialogShape(color, radius),
    dialogShape(R.color.white, radius)
)

internal fun Context.dialogDp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
