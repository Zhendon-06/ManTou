package com.hfad.mantou.view

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hfad.mantou.data.preferences.WallpaperStore
import com.hfad.mantou.databinding.ActivityAppearanceSettingsBinding

class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceSettingsBinding

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

        renderWallpaper(WallpaperStore.getWallpaperUri(this))
    }

    private fun renderWallpaper(uri: Uri?) {
        if (uri == null) {
            binding.ivWallpaperPreview.setImageDrawable(null)
            binding.ivWallpaperPreview.visibility = View.GONE
            binding.tvWallpaperEmpty.visibility = View.VISIBLE
            binding.tvWallpaperPath.text = "未设置自定义壁纸"
            return
        }

        binding.ivWallpaperPreview.visibility = View.VISIBLE
        binding.tvWallpaperEmpty.visibility = View.GONE
        binding.ivWallpaperPreview.setImageURI(uri)
        binding.tvWallpaperPath.text = uri.toString()
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
