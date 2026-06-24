package com.hfad.mantou.view

import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hfad.mantou.R
import com.hfad.mantou.data.preferences.AppearanceSettingsStore
import com.hfad.mantou.data.preferences.WallpaperStore
import com.hfad.mantou.databinding.ActivityAppearanceSettingsBinding
import java.util.Locale
import kotlin.math.roundToInt

class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceSettingsBinding
    private var selectedTextColor = AppearanceSettingsStore.AUTO_TEXT_COLOR
    private var suppressCustomColorWatcher = false

    private val colorChoices = listOf(
        AppearanceSettingsStore.AUTO_TEXT_COLOR,
        Color.WHITE,
        Color.rgb(54, 65, 85),
        Color.rgb(209, 226, 244),
        Color.rgb(34, 76, 138),
        Color.rgb(47, 139, 83),
        Color.rgb(245, 158, 11)
    )

    private val wallpaperPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        WallpaperStore.setWallpaperUri(this, uri)
        renderWallpaper(uri)
        Toast.makeText(this, "壁纸已更新", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAppearanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnChooseWallpaper.setOnClickListener {
            wallpaperPicker.launch(arrayOf("image/*"))
        }
        binding.btnClearWallpaper.setOnClickListener {
            WallpaperStore.clearWallpaper(this)
            renderWallpaper(null)
            Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show()
        }

        setupColorOptions()
        setupSeekBars()
        setupCustomColorInput()
        renderSettings(AppearanceSettingsStore.getSettings(this))
        renderWallpaper(WallpaperStore.getWallpaperUri(this))
    }

    private fun renderWallpaper(uri: Uri?) {
        if (uri == null) {
            binding.ivWallpaperPreview.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.ivWallpaperPreview.setRenderEffect(null)
            }
            binding.ivWallpaperPreview.visibility = View.GONE
            binding.wallpaperPreviewMask.visibility = View.GONE
            binding.tvWallpaperEmpty.visibility = View.VISIBLE
            binding.tvWallpaperPath.text = "未设置自定义壁纸"
            return
        }

        binding.ivWallpaperPreview.visibility = View.VISIBLE
        binding.wallpaperPreviewMask.visibility = View.VISIBLE
        binding.tvWallpaperEmpty.visibility = View.GONE
        binding.ivWallpaperPreview.setImageURI(uri)
        binding.tvWallpaperPath.text = uri.toString()
        updatePreviewEffects()
    }

    private fun setupSeekBars() {
        binding.seekBlur.setOnSeekBarChangeListener(simpleSeekListener {
            AppearanceSettingsStore.setBackgroundBlur(this, binding.seekBlur.progress.toFloat())
            updateLabels()
            updatePreviewEffects()
        })
        binding.seekMaskStrength.setOnSeekBarChangeListener(simpleSeekListener {
            AppearanceSettingsStore.setMaskStrength(this, binding.seekMaskStrength.progress / 100f)
            updateLabels()
            updatePreviewEffects()
        })
        binding.seekMaskTone.setOnSeekBarChangeListener(simpleSeekListener {
            AppearanceSettingsStore.setMaskTone(this, binding.seekMaskTone.progress / 100f - 1f)
            updateLabels()
            updatePreviewEffects()
        })
        binding.seekTextSize.setOnSeekBarChangeListener(simpleSeekListener {
            AppearanceSettingsStore.setChatTextSizeSp(this, 12f + binding.seekTextSize.progress / 10f)
            updateLabels()
        })
    }

    private fun setupColorOptions() {
        binding.colorOptions.removeAllViews()
        colorChoices.forEach { color ->
            val option = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    if (color == AppearanceSettingsStore.AUTO_TEXT_COLOR) dp(108) else dp(56),
                    dp(46)
                ).also { it.marginEnd = dp(10) }
                gravity = android.view.Gravity.CENTER
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                isClickable = true
                isFocusable = true
                background = createColorOptionBackground(color, selected = false)
                text = if (color == AppearanceSettingsStore.AUTO_TEXT_COLOR) "✓ 自动" else ""
                setTextColor(getColor(R.color.mt_text_primary))
                setOnClickListener {
                    selectedTextColor = color
                    if (color == AppearanceSettingsStore.AUTO_TEXT_COLOR) {
                        AppearanceSettingsStore.setAutoChatTextColor(this@AppearanceSettingsActivity)
                        suppressCustomColorWatcher = true
                        binding.etCustomColor.setText("")
                        suppressCustomColorWatcher = false
                    } else {
                        AppearanceSettingsStore.setChatTextColor(this@AppearanceSettingsActivity, color)
                        setCustomColorText(AppearanceSettingsStore.colorToHex(color))
                    }
                    refreshColorOptions()
                }
            }
            binding.colorOptions.addView(option)
        }
    }

    private fun setupCustomColorInput() {
        binding.etCustomColor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressCustomColorWatcher) return
                val raw = s?.toString().orEmpty()
                if (raw.isBlank()) return
                val color = AppearanceSettingsStore.parseColorOrNull(raw) ?: return
                selectedTextColor = color
                AppearanceSettingsStore.setChatTextColor(this@AppearanceSettingsActivity, color)
                refreshColorOptions()
            }
        })
    }

    private fun renderSettings(settings: AppearanceSettingsStore.Settings) {
        selectedTextColor = settings.chatTextColor
        binding.seekBlur.progress = settings.backgroundBlur.roundToInt()
        binding.seekMaskStrength.progress = (settings.maskStrength * 100).roundToInt()
        binding.seekMaskTone.progress = ((settings.maskTone + 1f) * 100).roundToInt()
        binding.seekTextSize.progress = ((settings.chatTextSizeSp - 12f) * 10).roundToInt()
        if (settings.hasFixedTextColor) {
            setCustomColorText(AppearanceSettingsStore.colorToHex(settings.chatTextColor))
        }
        updateLabels()
        updatePreviewEffects()
        refreshColorOptions()
    }

    private fun updateLabels() {
        val settings = AppearanceSettingsStore.getSettings(this)
        binding.tvBlurTitle.text = "背景柔化  ${formatNumber(settings.backgroundBlur, 2)}"
        binding.tvMaskStrengthTitle.text = "蒙版强度  ${formatNumber(settings.maskStrength, 2)}"
        binding.tvMaskToneTitle.text = "蒙版明暗  ${formatNumber(settings.maskTone, 2)}"
        binding.tvTextSizeTitle.text = "聊天文本大小  ${formatNumber(settings.chatTextSizeSp, 1)}sp"
    }

    private fun updatePreviewEffects() {
        val settings = AppearanceSettingsStore.getSettings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = settings.backgroundBlur
            binding.ivWallpaperPreview.setRenderEffect(
                if (blur > 0f) {
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                } else {
                    null
                }
            )
        }
        binding.wallpaperPreviewMask.setBackgroundColor(
            AppearanceSettingsStore.maskColor(settings)
        )
    }

    private fun refreshColorOptions() {
        for (index in 0 until binding.colorOptions.childCount) {
            val child = binding.colorOptions.getChildAt(index) as? TextView ?: continue
            val color = colorChoices[index]
            child.background = createColorOptionBackground(
                color,
                selected = if (color == AppearanceSettingsStore.AUTO_TEXT_COLOR) {
                    selectedTextColor == AppearanceSettingsStore.AUTO_TEXT_COLOR
                } else {
                    selectedTextColor == color
                }
            )
        }
    }

    private fun createColorOptionBackground(color: Int, selected: Boolean): GradientDrawable {
        val radius = if (color == AppearanceSettingsStore.AUTO_TEXT_COLOR) dp(18).toFloat() else dp(23).toFloat()
        return GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (color == AppearanceSettingsStore.AUTO_TEXT_COLOR) Color.WHITE else color)
            setStroke(
                dp(if (selected) 2 else 1),
                if (selected) getColor(R.color.mt_primary) else getColor(R.color.mt_outline)
            )
        }
    }

    private fun setCustomColorText(text: String) {
        suppressCustomColorWatcher = true
        binding.etCustomColor.setText(text)
        binding.etCustomColor.setSelection(binding.etCustomColor.text?.length ?: 0)
        suppressCustomColorWatcher = false
    }

    private fun simpleSeekListener(onChanged: () -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun formatNumber(value: Float, digits: Int): String {
        return String.format(Locale.US, "%.${digits}f", value)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }
}
