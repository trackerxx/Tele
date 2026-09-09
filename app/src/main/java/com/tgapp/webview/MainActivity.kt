package com.tgapp.webview

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyStatusBarColor()

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Keep login sessions saved
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(HIDE_OPEN_APP_JS, null)
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // "k" is Telegram's modern web client (Webogram K version)
            webView.loadUrl("https://web.telegram.org/k/")
        }
    }

    private fun applyStatusBarColor() {
        val isDarkMode = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            // Telegram's commonly used dark theme background
            window.statusBarColor = Color.parseColor("#17212B")
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = false
        } else {
            window.statusBarColor = Color.WHITE
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        // Continuously watches the page for "Open app" / "Get the app"
        // style native-app install prompts and hides them as they appear.
        // Matches by visible text/aria-label instead of CSS class names.
        private const val HIDE_OPEN_APP_JS = """
            (function() {
                if (window.__openAppHiderInstalled) return;
                window.__openAppHiderInstalled = true;

                var phrases = ['open app', 'get the app', 'open in app', 'install app'];

                function isOpenAppButton(el) {
                    if (!el || el.nodeType !== 1) return false;
                    var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
                    var text = (el.textContent || '').trim().toLowerCase();
                    for (var i = 0; i < phrases.length; i++) {
                        if (label === phrases[i] || text === phrases[i]) return true;
                    }
                    return false;
                }

                function hideIfMatch(el) {
                    if (!el || el.nodeType !== 1) return;
                    if (isOpenAppButton(el)) {
                        var target = el;
                        for (var i = 0; i < 4 && target.parentElement; i++) {
                            target = target.parentElement;
                        }
                        target.style.setProperty('display', 'none', 'important');
                    }
                }

                function scan(root) {
                    try {
                        if (isOpenAppButton(root)) {
                            hideIfMatch(root);
                            return;
                        }
                        var all = root.querySelectorAll('div,a,span,button');
                        for (var i = 0; i < all.length; i++) {
                            hideIfMatch(all[i]);
                        }
                    } catch (e) {}
                }

                scan(document.body);

                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var added = mutations[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            scan(added[j]);
                        }
                    }
                });

                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """
    }
}
