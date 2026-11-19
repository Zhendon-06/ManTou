package com.hfad.mantou.view

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.util.Linkify
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hfad.mantou.R
import com.hfad.mantou.data.preferences.OnlineSearchSettings
import com.hfad.mantou.data.preferences.OnlineSearchSettingsStore
import com.hfad.mantou.databinding.ActivitySettingsBinding
import com.hfad.mantou.utils.linux.AndroidLinuxWorkspace
import com.hfad.mantou.utils.linux.LinuxWorkspaceSettings
import com.hfad.mantou.view.dialog.ManTouDialog
import com.hfad.mantou.view.dialog.ManTouDialog.Action
import com.hfad.mantou.view.dialog.dialogDp
import com.hfad.mantou.view.dialog.dialogField

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
        updateOnlineSearchSummary()
        binding.rowOnlineSearch.setOnClickListener { showOnlineSearchSettings() }
        binding.linuxWorkspaceSwitch.apply {
            isEnabled = AndroidLinuxWorkspace.isAvailable(this@SettingsActivity)
            isChecked = isEnabled && LinuxWorkspaceSettings.enabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, enabled ->
                LinuxWorkspaceSettings.setEnabled(this@SettingsActivity, enabled)
            }
        }
        if (!binding.linuxWorkspaceSwitch.isEnabled) {
            binding.linuxWorkspaceSummary.setText(R.string.linux_workspace_unavailable)
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

    private fun updateOnlineSearchSummary() {
        val settings = OnlineSearchSettingsStore.load(this)
        binding.onlineSearchSummary.setText(when {
            !settings.enabled -> R.string.online_search_off
            settings.tavilyKey.isBlank() -> R.string.online_search_keyless
            else -> R.string.online_search_key_configured
        })
    }

    private fun showOnlineSearchSettings() {
        val settings = OnlineSearchSettingsStore.load(this)
        val enabled = SwitchMaterial(this).apply {
            setText(R.string.online_search_enable)
            setTextColor(getColor(R.color.mt_text_primary))
            isChecked = settings.enabled
            minimumHeight = dialogDp(48)
        }
        val key = dialogField(
            hint = getString(R.string.online_search_tavily_key),
            value = settings.tavilyKey,
            input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(enabled)
            addView(key)
            addView(TextView(context).apply {
                setText(R.string.online_search_register)
                setTextColor(getColor(R.color.mt_primary_dark))
                setPadding(0, dialogDp(12), 0, 0)
                Linkify.addLinks(this, Linkify.WEB_URLS)
            })
        }
        ManTouDialog(
            context = this,
            title = getString(R.string.online_search_title),
            message = getString(R.string.online_search_explanation),
            content = content,
            primary = Action("保存", dismissOnClick = false) { dialog ->
                val value = key.text.toString().trim()
                if (value.length > 256 || value.any { it !in '!'..'~' }) {
                    key.error = getString(R.string.online_search_invalid_key)
                } else {
                    OnlineSearchSettingsStore.save(this, OnlineSearchSettings(enabled.isChecked, value))
                    updateOnlineSearchSummary()
                    dialog.dismiss()
                }
            },
            secondary = Action("取消")
        ).show()
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
