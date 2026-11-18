package com.hfad.mantou.view

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hfad.mantou.databinding.ActivitySettingsBinding
import com.hfad.mantou.view.dialog.ManTouDialog
import com.hfad.mantou.view.dialog.ManTouDialog.Action
import com.hfad.mantou.view.dialog.ManTouDialog.Option

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rowModelConfig.setOnClickListener {
            startActivity(Intent(this, ModelSettingActivity::class.java))
        }
        binding.rowWorkspaceMemory.setOnClickListener {
            startActivity(Intent(this, WorkspaceMemorySettingsActivity::class.java))
        }
        binding.rowAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }
        binding.rowTaskProgressNotifications.setOnClickListener {
            openTaskProgressNotificationSettings()
        }
        binding.logpage.setOnClickListener {
            startActivity(Intent(this, RequestLogActivity::class.java))
        }
    }

    private fun openTaskProgressNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
        }
    }
}
