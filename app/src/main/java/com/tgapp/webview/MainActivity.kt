package com.tgapp.webview

import android.Manifest
import android.annotation.SuppressLint
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
        // Note: DownloadManager fetching these URLs itself (in a separate process, with
        // just a copied cookie header) does NOT work reliably here — web.telegram.org/k/
        // serves downloads through its own service worker / in-page session, so an
        // external fetch to the "same" URL can hang forever (stuck "downloading", file
        // never finishes, never shows up anywhere). Instead we always fetch the bytes
        // from *inside* the page via JS (same cookies, same service worker) and hand them
        // to Android as base64 to write straight to the Downloads folder.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            fetchAndSaveViaJs(url, fileName, mimeType)
            Toast.makeText(this, "Preparing download: $fileName", Toast.LENGTH_SHORT).show()
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://web.telegram.org/k/")
        }
    }

    /**
     * Runs JS inside the page to fetch a URL's real bytes (using the page's own cookies
     * and, importantly, going through its service worker like a normal in-page download
     * would) and hands them back to Android as base64 via BlobDownloadInterface.
     * Works for both blob: URLs and normal-looking https: URLs that only resolve
     * correctly from inside the page's own context.
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
