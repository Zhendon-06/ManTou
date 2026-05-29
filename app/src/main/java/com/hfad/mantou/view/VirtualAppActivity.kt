package com.hfad.mantou.view

import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.hfad.mantou.databinding.VirtualappBinding

class VirtualAppActivity : AppCompatActivity() {

    private lateinit var binding: VirtualappBinding

    companion object {
        const val EXTRA_HTML_PATH = "html_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = VirtualappBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val decorView = window.decorView
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        decorView.systemUiVisibility = flags

        val htmlPath = intent.getStringExtra(EXTRA_HTML_PATH)

        binding.webView.apply {
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }

        if (!htmlPath.isNullOrEmpty()) {
            binding.webView.loadUrl("file://$htmlPath")
        }

        binding.btnSmallscreen.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            settings.javaScriptEnabled = false
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
