package com.tgapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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

    // Tracks in-flight DownloadManager requests (url/fileName/mimeType) so that if one
    // fails or gets stuck, we can retry it through the page's own authenticated fetch()
    // instead — that one follows redirects and sends cookies correctly, which fixes the
    // most common cause of "HTTP data error" / stuck-paused downloads: DownloadManager
    // silently drops our custom Cookie/Referer headers whenever the CDN URL redirects.
    private data class PendingDownload(val url: String, val fileName: String, val mimeType: String)
    private val pendingDownloads = mutableMapOf<Long, PendingDownload>()

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

        // Reports the REAL reason a DownloadManager download failed (HTTP code, etc.)
        // instead of us guessing — the system-drawn "Untitled" notification that
        // disappears quickly doesn't say why, so we ask DownloadManager directly.
        val downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                if (id == -1L) return
                reportDownloadOutcome(id)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBlobClickInterceptor()
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
                    setTitle(fileName)
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    // Without these, DownloadManager can sit "paused, queued for wifi"
                    // indefinitely on a mobile-data connection.
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                val id = getSystemService<DownloadManager>()?.enqueue(request)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()

                // Fail-safe: some devices/ROMs don't reliably deliver
                // ACTION_DOWNLOAD_COMPLETE to app-registered receivers. Check the
                // status directly after a delay so we get the diagnostic dialog
                // regardless.
                if (id != null) {
                    pendingDownloads[id] = PendingDownload(url, fileName, mimeType)
                    Handler(Looper.getMainLooper()).postDelayed({
                        reportDownloadOutcome(id)
                    }, 8000)
                }
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
     * Installs a capturing click listener (once per page load) that intercepts clicks on
     * download links (<a download href="blob:...">) at the instant they happen — BEFORE
     * Telegram's own JS gets a chance to revoke the blob URL a moment later. This is the
     * real fix for blob downloads: relying on WebView's setDownloadListener is too late,
     * since it fires after a delay and the blob is often already gone by then (causing
     * "Failed to fetch"). We preventDefault() the click and read the blob ourselves,
     * immediately, in the same event.
     */
    private fun injectBlobClickInterceptor() {
        val js = """
            (function() {
                if (window.__androidBlobInterceptorInstalled) return;
                window.__androidBlobInterceptorInstalled = true;
                document.addEventListener('click', function(e) {
                    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                    if (!a) return;
                    var href = a.getAttribute('href');
                    if (!href || href.indexOf('blob:') !== 0) return;
                    e.preventDefault();
                    e.stopPropagation();
                    var fileName = a.getAttribute('download') || ('file_' + Date.now());
                    fetch(href)
                        .then(function(res) { return res.blob(); })
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
                }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Queries DownloadManager for what actually happened to a finished download and
     * shows the real reason (HTTP status code, or the internal ERROR_* reason) instead
     * of leaving it as an unexplained "Untitled" notification that vanishes.
     */
    private fun reportDownloadOutcome(downloadId: Long, attempt: Int = 1) {
        val dm = getSystemService<DownloadManager>() ?: return
        val cursor: Cursor = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val title = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "file"
            val bytesSoFar = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    pendingDownloads.remove(downloadId)
                    val sizeBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Download finished")
                            .setMessage("File: $title\nSize: $sizeBytes bytes\nSaved at: $localUri")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                DownloadManager.STATUS_FAILED, DownloadManager.STATUS_PAUSED -> {
                    // Both usually mean DownloadManager dropped our Cookie/Referer
                    // headers on a redirect (Telegram's CDN commonly redirects file
                    // URLs), not that the file is genuinely unavailable. Retry once
                    // through the page's own fetch() — same mechanism already used for
                    // blob: downloads — which carries the session and follows redirects
                    // correctly on its own.
                    val pending = pendingDownloads.remove(downloadId)
                    if (pending != null && attempt == 1) {
                        dm.remove(downloadId)
                        runOnUiThread {
                            Toast.makeText(this, "Retrying \"$title\" through Telegram session...", Toast.LENGTH_SHORT).show()
                        }
                        fetchAndSaveViaJs(pending.url, pending.fileName, pending.mimeType)
                    } else {
                        val reasonText = if (status == DownloadManager.STATUS_FAILED) {
                            when (reason) {
                                DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
                                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "HTTP error"
                                DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
                                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage not found"
                                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
                                DownloadManager.ERROR_FILE_ERROR -> "file error"
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient space"
                                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
                                else -> "code $reason (often an HTTP status like 403/404 in the 400+ range)"
                            }
                        } else {
                            "still paused after retry"
                        }
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("Download failed")
                                .setMessage("File: $title\nReason: $reasonText")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    // Large videos genuinely take longer than 8s — don't alarm the user
                    // about a download that's still actively progressing. Re-check a few
                    // more times (up to ~32s total) before calling it stuck.
                    if (attempt < 4) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            reportDownloadOutcome(downloadId, attempt + 1)
                        }, 8000)
                        Unit
                    } else {
                        val statusText = if (status == DownloadManager.STATUS_RUNNING)
                            "still running" else "still pending (not started yet)"
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("Download stuck")
                                .setMessage("File: $title\nStatus: $statusText\nDownloaded so far: $bytesSoFar bytes")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
                else -> Unit
            }
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
