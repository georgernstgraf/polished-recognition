package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import com.georgernstgraf.polishedrecognition.R

/**
 * In-app help (#107).
 *
 * Renders the bundled, self-contained `help.html` in a WebView, fully offline.
 * The same file is served as the "Help" page on the project site, so the help
 * text has a single source (see `docs/help.html`; the Gradle task
 * `copyHelpAssets` bundles it into the APK). JavaScript is enabled because the
 * page's interactive keyboard demo needs it — only local assets are loaded,
 * never remote content.
 */
class HelpActivity : Activity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        webView = findViewById<WebView>(R.id.help_webview).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            loadUrl("file:///android_asset/help.html")
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
