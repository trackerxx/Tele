package com.tgapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Holds the WebView's callback while the system file picker is open (for uploads)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* if denied, the relevant action (upload/download) just won't work until retried */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyStatusBarColor()
        requestNeededPermissions()

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
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Some devices need audio mode explicitly reset to NORMAL for WebView mic capture to work
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.mode = AudioManager.MODE_NORMAL

        // Keep login sessions saved
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Lets JS inside the page hand real file bytes back to Android for saving.
        // Needed because blob: download URLs only resolve correctly from inside the
        // page's own session/service-worker context.
        webView.addJavascriptInterface(BlobDownloadInterface(), "AndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectDownloadClickInterceptor()
            }
        }

        // Lets the "attach file" button inside web.telegram.org open the system file picker
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select file"))
                return true
            }

            // Grants mic (and camera, if requested) access for voice messages / calls,
            // as long as the matching Android runtime permission has already been granted.
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val granted = request.resources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        else -> false
                    }
                }
                if (granted.isNotEmpty()) {
                    runOnUiThread { request.grant(granted.toTypedArray()) }
                } else {
                    runOnUiThread { request.deny() }
                }
            }
        }

        // Secondary path: injectDownloadClickInterceptor() (below) handles the normal
        // case by catching the click on Telegram's own <a download> link before any
        // navigation happens. This listener only fires for downloads that reach WebView
        // as an actual navigation (no JS-clickable anchor involved) — rarer, but still
        // handled the same way: blob: via JS, everything else via the native downloader.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            if (url.startsWith("blob:")) {
                fetchAndSaveViaJs(url, fileName, mimeType)
                Toast.makeText(this, "Preparing download: $fileName", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }

            val cookie = CookieManager.getInstance().getCookie(url) ?: ""
            val referer = webView.url ?: "https://web.telegram.org/k/"
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            downloadHttpFile(url, fileName, mimeType, cookie, referer, userAgent)
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://web.telegram.org/k/")
        }
    }

    /**
     * Installs a capturing click listener (once per page load) that intercepts clicks on
     * download links (<a download href="...">) at the instant they happen, for BOTH
     * blob: and regular http(s) URLs.
     *
     * This turned out to be the real fix for the "Download failed" / "HTTP 302 no
     * Location" / "Failed to fetch" errors we kept seeing for regular URLs too: Telegram
     * signs these download links with a short-lived / single-use token. Letting the click
     * navigate normally means WebView makes its own (silent, first) request to that URL
     * before ever calling setDownloadListener — by the time our code got the URL from
     * that callback and tried to fetch it again ourselves, the token had already been
     * used up, so every retry we tried (DownloadManager, JS fetch, native HttpURLConnection)
     * kept failing in different ways. Fetching it ourselves at the moment of the click,
     * before any navigation happens, means we're always the FIRST and ONLY request for
     * that URL, using it while it's still valid.
     */
    private fun injectDownloadClickInterceptor() {
        val js = """
            (function() {
                if (window.__androidDownloadInterceptorInstalled) return;
                window.__androidDownloadInterceptorInstalled = true;
                document.addEventListener('click', function(e) {
                    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                    if (!a) return;
                    var href = a.getAttribute('href');
                    if (!href) return;
                    e.preventDefault();
                    e.stopPropagation();
                    var fileName = a.getAttribute('download') || ('file_' + Date.now());

                    if (href.indexOf('blob:') === 0) {
                        // blob: URLs only resolve inside the page's own JS context, so
                        // fetch() here is the only option.
                        fetch(href, { credentials: 'include' })
                            .then(function(res) {
                                if (!res.ok) { throw new Error('HTTP ' + res.status); }
                                return res.blob();
                            })
                            .then(function(blob) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var base64 = (reader.result || '').split(',')[1] || '';
                                    AndroidDownloader.saveBase64File(base64, fileName, blob.type || 'application/octet-stream');
                                };
                                reader.readAsDataURL(blob);
                            })
                            .catch(function(err) {
                                AndroidDownloader.reportError(err && err.message ? err.message : String(err));
                            });
                        return;
                    }

                    // Regular http(s) URLs are signed with a short-lived, single-use
                    // token AND are cross-origin (CORS will block reading the response
                    // here anyway). Doing a JS fetch() first would burn that token on a
                    // request whose result we can't even use, leaving the native retry
                    // to hit an already-expired URL (the "Redirect with no Location
                    // header" failure). So for non-blob links, skip straight to the
                    // native downloader — it becomes the first and only request.
                    AndroidDownloader.fallbackNativeDownload(href, fileName, '');
                }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Runs JS inside the page to read a blob: URL's real bytes and hands them back to
     * Android as base64 via BlobDownloadInterface. Used only for blob: URLs.
     */
    private fun fetchAndSaveViaJs(url: String, fileName: String, mimeType: String) {
        val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeFileName = fileName.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeMimeType = (mimeType.ifBlank { "application/octet-stream" })
            .replace("\\", "\\\\").replace("\"", "\\\"")

        val js = """
            (function() {
                fetch("$safeUrl", { credentials: 'include' })
                    .then(function(res) {
                        if (!res.ok) { throw new Error('HTTP ' + res.status); }
                        return res.blob();
                    })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1] || '';
                            AndroidDownloader.saveBase64File(base64, "$safeFileName", "$safeMimeType");
                        };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(e) {
                        AndroidDownloader.reportError(e && e.message ? e.message : String(e));
                    });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * Downloads a regular http(s) URL on a background thread using a plain
     * HttpURLConnection, manually following redirects (re-attaching Cookie/Referer/
     * User-Agent on every hop) and streaming the response straight into the public
     * Downloads collection. This sidesteps both of the failure modes we hit with the
     * other two approaches:
     *  - DownloadManager: drops custom headers across redirects.
     *  - Page JS fetch(): blocked by CORS on the cross-origin CDN ("Failed to fetch").
     * Neither limitation applies to a raw HttpURLConnection made from Kotlin.
     */
    private fun downloadHttpFile(
        startUrl: String,
        fileName: String,
        mimeType: String,
        cookie: String,
        referer: String,
        userAgent: String
    ) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                var currentUrl = startUrl
                var redirects = 0

                while (redirects < 8) {
                    val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("Cookie", cookie)
                        setRequestProperty("User-Agent", userAgent)
                        setRequestProperty("Referer", referer)
                    }
                    conn.connect()
                    val code = conn.responseCode

                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location.isNullOrBlank()) {
                            throw IOException("Redirect with no Location header (HTTP $code)")
                        }
                        currentUrl = URL(URL(currentUrl), location).toString()
                        redirects++
                        continue
                    }

                    if (code !in 200..299) {
                        conn.disconnect()
                        throw IOException("HTTP $code")
                    }

                    connection = conn
                    break
                }

                val finalConn = connection ?: throw IOException("Too many redirects")
                val actualMime = finalConn.contentType
                    ?.substringBefore(";")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: mimeType.ifBlank { "application/octet-stream" }

                finalConn.inputStream.use { input ->
                    saveStreamToDownloads(input, fileName, actualMime)
                }

                runOnUiThread {
                    Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Download failed")
                        .setMessage("File: $fileName\nReason: ${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    /** Streams an input stream into the public Downloads collection (scoped-storage safe). */
    private fun saveStreamToDownloads(input: InputStream, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create entry in Downloads")

            resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                ?: throw IllegalStateException("Could not open output stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { output -> input.copyTo(output) }
            // Pre-Q, files written directly to disk need a media-scan nudge to show up
            // immediately in file managers / gallery apps.
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType), null)
        }
    }

    /** Writes decoded bytes into the public Downloads collection (scoped-storage safe). */
    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        saveStreamToDownloads(bytes.inputStream(), fileName, mimeType)
    }

    /** JS-facing bridge: receives a blob's bytes as base64 and saves them as a real file. */
    private inner class BlobDownloadInterface {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    saveBytesToDownloads(bytes, fileName, mimeType)
                    Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun reportError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Download failed: $message", Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Called when the page's own click-time fetch() fails (e.g. a genuine
         * cross-origin CORS block). The URL is still fresh at this point — the click
         * handler hasn't let WebView navigate anywhere yet — so it's worth one native
         * HttpURLConnection attempt, which isn't subject to browser CORS rules at all.
         */
        @JavascriptInterface
        fun fallbackNativeDownload(url: String, fileName: String, mimeType: String) {
            runOnUiThread {
                if (url.startsWith("blob:")) {
                    // No real network request behind a blob: URL — nothing more to try.
                    Toast.makeText(this@MainActivity, "Download failed: $fileName", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val cookie = CookieManager.getInstance().getCookie(url) ?: ""
                val referer = webView.url ?: "https://web.telegram.org/k/"
                val userAgent = webView.settings.userAgentString
                Toast.makeText(this@MainActivity, "Downloading $fileName", Toast.LENGTH_SHORT).show()
                downloadHttpFile(url, fileName, mimeType.ifBlank { "application/octet-stream" }, cookie, referer, userAgent)
            }
        }
    }

    /** Asks for the storage/media/notification permissions needed for upload & download. */
    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        val notGranted = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun applyStatusBarColor() {
        val barColor = Color.parseColor("#212121")

        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
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
}
