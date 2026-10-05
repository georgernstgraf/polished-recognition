package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebView
import com.georgernstgraf.polishedrecognition.R

/**
 * In-app help (#107).
 *
 * Renders the bundled, self-contained `help.html` in a WebView, fully offline.
 * The same file is served as the "Help" page on the project site, so the help
 * text has a single source (see `docs/help.html`; the Gradle task
 * `stageHelpAssets` bundles it into the APK). JavaScript is enabled because the
 * page's interactive keyboard demo needs it — only local assets are loaded,
 * never remote content.
 *
 * Light/dark (#107): the app has no theme setting of its own and follows the
 * system. The current night mode is passed to the page explicitly
 * (`?theme=dark|light`) and the WebView background is matched, so the help
 * follows the system with no flash; the page also falls back to
 * `prefers-color-scheme`. The Activity is recreated on a ui-mode change, so it
 * tracks a live system switch.
 */
class HelpActivity : Activity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        webView = findViewById<WebView>(R.id.help_webview).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            // Match the page background so there is no light flash before the
            // CSS variables apply.
            setBackgroundColor(if (night) 0xFF0E1116.toInt() else 0xFFFFFFFF.toInt())
            loadUrl("file:///android_asset/help.html?theme=" + if (night) "dark" else "light")
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
