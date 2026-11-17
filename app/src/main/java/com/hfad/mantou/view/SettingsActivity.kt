package com.hfad.mantou.view

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hfad.mantou.databinding.ActivitySettingsBinding
import com.hfad.mantou.data.preferences.HarnessSettingsStore
import com.hfad.mantou.utils.harness.HarnessIntensity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        fun refreshIntensity() {
            val intensity = HarnessSettingsStore.intensity(this)
            binding.tvHarnessIntensity.text = "生成强度：${intensity.label}"
        }
        refreshIntensity()
        binding.rowHarnessIntensity.setOnClickListener {
            val options = HarnessIntensity.entries
            MaterialAlertDialogBuilder(this)
                .setTitle("生成强度")
                .setSingleChoiceItems(
                    options.map { "${it.label} · ${it.description}" }.toTypedArray(),
                    options.indexOf(HarnessSettingsStore.intensity(this))
                ) { dialog, index ->
                    HarnessSettingsStore.setIntensity(this, options[index])
                    refreshIntensity()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.rowModelConfig.setOnClickListener {
            startActivity(Intent(this, ModelSettingActivity::class.java))
        }
        binding.rowWorkspaceMemory.setOnClickListener {
            startActivity(Intent(this, WorkspaceMemorySettingsActivity::class.java))
        }
        binding.rowAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }
        binding.logpage.setOnClickListener {
            startActivity(Intent(this, RequestLogActivity::class.java))
        }
    }
}
