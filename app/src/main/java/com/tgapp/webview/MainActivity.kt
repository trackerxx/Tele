package com.tgapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
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
        // Needed because web.telegram.org/k/'s download URLs (blob: or otherwise) only
        // resolve correctly from inside the page's own session/service-worker context.
        webView.addJavascriptInterface(BlobDownloadInterface(), "AndroidDownloader")

        webView.webViewClient = WebViewClient()

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

        // Handles files that Telegram Web pushes out for download (media, documents, etc.)
        //
        // Two different mechanisms are needed depending on the URL scheme:
        // - blob: URLs (in-memory objects created by the page's own JS) can't be fetched
        //   by DownloadManager at all, since there's no real network request behind them.
        //   For these we read the bytes from inside the page via JS and hand them to
        //   Android as base64.
        // - Regular http(s) URLs (usually a CDN, different domain than web.telegram.org)
        //   are handled by DownloadManager directly. We do NOT use JS fetch() for these,
        //   because that hits the browser's CORS policy on cross-origin resources and
        //   fails with "Failed to fetch". DownloadManager is a native HTTP client, not a
        //   browser context, so CORS doesn't apply — but it needs the same Referer/
        //   User-Agent/cookie headers a real in-page request would send, since CDNs with
        //   hotlink protection silently drop requests missing them (this is what caused
        //   downloads to sit at "downloading" forever with no file ever appearing).
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            if (url.startsWith("blob:")) {
                fetchAndSaveViaJs(url, fileName, mimeType)
                Toast.makeText(this, "Preparing download: $fileName", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }

            try {
                val cookie = CookieManager.getInstance().getCookie(url) ?: ""
                val referer = webView.url ?: "https://web.telegram.org/k/"

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("cookie", cookie)
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Referer", referer)
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }

                getSystemService<DownloadManager>()?.enqueue(request)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://web.telegram.org/k/")
        }
    }

    /**
     * Runs JS inside the page to read a blob: URL's real bytes and hands them back to
     * Android as base64 via BlobDownloadInterface. Used only for blob: URLs, since
     * DownloadManager has no way to fetch that scheme itself.
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

    /** Writes decoded bytes into the public Downloads collection (scoped-storage safe). */
    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create entry in Downloads")

            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open output stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            // Pre-Q, files written directly to disk need a media-scan nudge to show up
            // immediately in file managers / gallery apps.
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType), null)
        }
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
