package com.hfad.mantou.utils

import android.webkit.JavascriptInterface
import android.webkit.WebView

object MantouWebViewRuntime {

    fun install(webView: WebView) {
        webView.settings.userAgentString =
            AppGenerator.withMantouWebAppUserAgent(webView.settings.userAgentString)
        webView.addJavascriptInterface(Bridge, AppGenerator.WEB_APP_BRIDGE_NAME)
    }

    private object Bridge {
        @JavascriptInterface
        fun isMantouApp(): Boolean = true
    }
}
