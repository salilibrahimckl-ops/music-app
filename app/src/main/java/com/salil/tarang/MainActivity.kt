package com.salil.tarang

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    companion object {
        const val REPO_RAW = "https://raw.githubusercontent.com/salilibrahimckl-ops/music-app/main"
        const val APK_URL = "https://github.com/salilibrahimckl-ops/music-app/releases/latest/download/Tarang.apk"
        const val HOST = "https://appassets.androidplatform.net"
    }

    private var web: WebView? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Pair<List<MediaItem>, Int>? = null
    private var uiReady = false
    private var loadedUiVersion = 0
    private val prefs by lazy { getSharedPreferences("tarang", MODE_PRIVATE) }
    private val uiDir by lazy { File(filesDir, "ui").apply { mkdirs() } }

    private var visible = false
    private val ticker = object : Runnable {
        override fun run() {
            pushState()
            handler.postDelayed(this, if (visible) 500 else 2000)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { sendQueue() }
        override fun onPlayerError(error: PlaybackException) {
            js("window.toast && toast('That one could not play — skipping')")
        }
    }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            handleFiles(uris)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w == null || !uiReady) { moveTaskToBack(true); return }
                w.evaluateJavascript("window.onBack ? onBack() : false") { result ->
                    if (result != "true") moveTaskToBack(true)
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------- WebView ----------------

    private fun bundledUiVersion(): Int = try {
        assets.open("ui_version.txt").bufferedReader().readText().trim().toInt()
    } catch (e: Exception) { 1 }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        web?.let { old ->
            (old.parent as? android.view.ViewGroup)?.removeView(old)
            try { old.destroy() } catch (e: Exception) { }
        }
        uiReady = false

        val w = WebView(this)
        web = w
        setContentView(w)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/ui/", WebViewAssetLoader.InternalStoragePathHandler(this, uiDir))
            .build()

        w.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Screen engine crashed: rebuild it instead of crashing the whole app.
                if (view == web) handler.post { buildWebView() }
                return true
            }
        }

        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
        }
        w.isVerticalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.setBackgroundColor(0xFF0B0B10.toInt())
        w.addJavascriptInterface(Bridge(), "Native")

        val bundled = bundledUiVersion()
        val ota = prefs.getInt("uiVersion", 0)
        val otaFile = File(uiDir, "index.html")
        if (ota > bundled && otaFile.exists() && otaFile.length() > 1000) {
            loadedUiVersion = ota
            w.loadUrl("$HOST/ui/index.html")
            // Safety net: if the downloaded screen doesn't start, fall back to the built-in one.
            handler.postDelayed({
                if (!uiReady && web == w) {
                    prefs.edit().putInt("uiVersion", 0).apply()
                    otaFile.delete()
                    buildWebView()
                }
            }, 12000)
        } else {
            loadedUiVersion = bundled
            w.loadUrl("$HOST/assets/index.html")
        }
    }

    private fun js(code: String) {
        handler.post {
            try { web?.evaluateJavascript(code, null) } catch (e: Exception) { }
        }
    }

    // ---------------- Player connection ----------------

    override fun onStart() {
        super.onStart()
        visible = true
        if (controller != null || controllerFuture != null) {
            handler.removeCallbacks(ticker); handler.post(ticker)
            js("window.onAppVisible && onAppVisible(true)")
            return
        }
        connectController()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null }
            controller = c
            c?.addListener(playerListener)
            pending?.let { (items, index) -> startQueue(items, index) }
            pending = null
            sendQueue()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        visible = false
        js("window.onAppVisible && onAppVisible(false)")
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        try { web?.destroy() } catch (e: Exception) { }
        web = null
        super.onDestroy()
    }

    private fun startQueue(items: List<MediaItem>, index: Int) {
        val c = controller
        if (c == null) { pending = items to index; return }
        try {
            c.setMediaItems(items, index.coerceIn(0, maxOf(0, items.size - 1)), 0L)
            c.prepare()
            c.play()
        } catch (e: Exception) {
            js("window.toast && toast('Could not start playback')")
        }
    }

    private fun pushState() {
        val c = controller ?: return
        if (!uiReady) return
        try {
            val md = c.mediaMetadata
            val dur = c.duration
            val o = JSONObject()
                .put("playing", c.isPlaying)
                .put("buffering", c.playbackState == Player.STATE_BUFFERING)
                .put("pos", c.currentPosition)
                .put("dur", if (dur == C.TIME_UNSET || dur < 0) 0 else dur)
                .put("index", c.currentMediaItemIndex)
                .put("count", c.mediaItemCount)
                .put("id", c.currentMediaItem?.mediaId ?: "")
                .put("title", md.title?.toString() ?: "")
                .put("artist", md.artist?.toString() ?: "")
                .put("art", md.artworkUri?.toString() ?: "")
                .put("shuffle", c.shuffleModeEnabled)
                .put("repeat", c.repeatMode)
            web?.evaluateJavascript("window.onNativeState && onNativeState($o)", null)
        } catch (e: Exception) { }
    }

    private fun sendQueue() {
        val c = controller ?: return
        if (!uiReady) return
        try {
            val arr = JSONArray()
            for (i in 0 until c.mediaItemCount) {
                val item = c.getMediaItemAt(i)
                arr.put(
                    JSONObject()
                        .put("id", item.mediaId)
                        .put("title", item.mediaMetadata.title?.toString() ?: "")
                        .put("artist", item.mediaMetadata.artist?.toString() ?: "")
                        .put("art", item.mediaMetadata.artworkUri?.toString() ?: "")
                )
            }
            js("window.onNativeQueue && onNativeQueue($arr)")
        } catch (e: Exception) { }
    }

    private fun handleFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val arr = JSONArray()
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            var name = u.lastPathSegment ?: "Track"
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
            } catch (e: Exception) { }
            arr.put(
                JSONObject()
                    .put("id", u.toString())
                    .put("url", u.toString())
                    .put("title", name.substringBeforeLast('.'))
                    .put("artist", "On this phone")
                    .put("art", "")
                    .put("src", "local")
            )
        }
        js("window.onLocalFiles && onLocalFiles($arr)")
    }

    // ---------------- Updates ----------------

    private fun appVersion(): Long = try {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    } catch (e: Exception) { 0L }

    private fun download(url: String, out: File? = null, progress: ((Int) -> Unit)? = null): ByteArray? {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Tarang-Updater")
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            if (code in 300..399) {
                current = conn.getHeaderField("Location") ?: throw Exception("Bad redirect")
                conn.disconnect()
                return@repeat
            }
            if (code != 200) { conn.disconnect(); throw Exception("HTTP $code") }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                if (out == null) return input.readBytes()
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress?.invoke(pct) }
                        }
                    }
                }
            }
            conn.disconnect()
            return null
        }
        throw Exception("Too many redirects")
    }

    private fun checkUpdate(manual: Boolean) {
        Thread {
            try {
                val bytes = download("$REPO_RAW/update.json?t=${System.currentTimeMillis()}")
                    ?: throw Exception("empty")
                val info = JSONObject(String(bytes))
                val o = JSONObject()
                    .put("ui", info.optInt("ui"))
                    .put("apk", info.optLong("apk"))
                    .put("notes", info.optString("notes"))
                    .put("curUi", loadedUiVersion)
                    .put("curApk", appVersion())
                    .put("manual", manual)
                js("window.onUpdateInfo && onUpdateInfo($o)")
            } catch (e: Exception) {
                if (manual) js("window.onUpdateInfo && onUpdateInfo({error:true,manual:true})")
            }
        }.start()
    }

    private fun applyUiUpdate(version: Int) {
        Thread {
            try {
                val tmp = File(uiDir, "index.tmp")
                download("$REPO_RAW/app/src/main/assets/index.html?t=${System.currentTimeMillis()}", tmp)
                if (tmp.length() < 1000) throw Exception("bad file")
                val dest = File(uiDir, "index.html")
                dest.delete()
                tmp.renameTo(dest)
                prefs.edit().putInt("uiVersion", version).commit()
                handler.post { buildWebView() }
            } catch (e: Exception) {
                js("window.toast && toast('Update failed. Check internet and try again.')")
            }
        }.start()
    }

    private fun installApkUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            js("window.toast && toast('Allow Tarang to install updates, then tap Update again')")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) { }
            return
        }
        Thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "Tarang.apk")
                apk.delete()
                download(APK_URL, apk) { pct -> js("window.onApkProgress && onApkProgress($pct)") }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                handler.post {
                    try { startActivity(intent) } catch (e: Exception) {
                        js("window.toast && toast('Could not open installer')")
                    }
                }
            } catch (e: Exception) {
                js("window.onApkProgress && onApkProgress(-1)")
            }
        }.start()
    }

    // ---------------- Bridge to the screen ----------------

    inner class Bridge {
        @JavascriptInterface
        fun ready() {
            handler.post {
                uiReady = true
                sendQueue()
                pushState()
            }
        }

        private fun parseItems(json: String): List<MediaItem> {
            val arr = JSONArray(json)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                if (url.isEmpty()) return@mapNotNull null
                val meta = MediaMetadata.Builder()
                    .setTitle(o.optString("title"))
                    .setArtist(o.optString("artist"))
                val art = o.optString("art")
                if (art.startsWith("http")) meta.setArtworkUri(Uri.parse(art))
                MediaItem.Builder()
                    .setMediaId(o.optString("id"))
                    .setUri(url)
                    .setMediaMetadata(meta.build())
                    .build()
            }
        }

        @JavascriptInterface
        fun addToQueue(json: String) {
            handler.post {
                try {
                    val items = parseItems(json)
                    val c = controller ?: return@post
                    if (items.isEmpty()) return@post
                    if (c.mediaItemCount == 0) startQueue(items, 0) else c.addMediaItems(items)
                } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun playNext(json: String) {
            handler.post {
                try {
                    val items = parseItems(json)
                    val c = controller ?: return@post
                    if (items.isEmpty()) return@post
                    if (c.mediaItemCount == 0) startQueue(items, 0)
                    else c.addMediaItems(minOf(c.currentMediaItemIndex + 1, c.mediaItemCount), items)
                } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun removeAt(index: Int) {
            handler.post { controller?.run { if (index in 0 until mediaItemCount && index != currentMediaItemIndex) removeMediaItem(index) } }
        }

        @JavascriptInterface
        fun playQueue(json: String, index: Int) {
            handler.post {
                try {
                    val items = parseItems(json)
                    if (items.isNotEmpty()) startQueue(items, index)
                } catch (e: Exception) {
                    js("window.toast && toast('Could not play this')")
                }
            }
        }

        @JavascriptInterface
        fun toggle() {
            handler.post {
                controller?.run {
                    if (isPlaying) pause() else {
                        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                            if (playbackState == Player.STATE_ENDED) seekTo(0, 0L)
                            prepare()
                        }
                        play()
                    }
                }
            }
        }

        @JavascriptInterface
        fun next() { handler.post { controller?.run { if (hasNextMediaItem()) seekToNextMediaItem() else seekTo(0, 0L) } } }

        @JavascriptInterface
        fun prev() {
            handler.post {
                controller?.run {
                    if (currentPosition > 3000 || !hasPreviousMediaItem()) seekTo(0) else seekToPreviousMediaItem()
                }
            }
        }

        @JavascriptInterface
        fun jumpTo(index: Int) {
            handler.post {
                controller?.run {
                    if (index in 0 until mediaItemCount) { seekTo(index, 0L); prepare(); play() }
                }
            }
        }

        @JavascriptInterface
        fun seek(ms: Double) { handler.post { controller?.seekTo(ms.toLong().coerceAtLeast(0)) } }

        @JavascriptInterface
        fun setShuffle(on: Boolean) { handler.post { controller?.shuffleModeEnabled = on } }

        @JavascriptInterface
        fun setRepeat(mode: Int) { handler.post { controller?.repeatMode = mode.coerceIn(0, 2) } }

        @JavascriptInterface
        fun pickFiles() { handler.post { try { pickFiles.launch(arrayOf("audio/*")) } catch (e: Exception) { } } }

        @JavascriptInterface
        fun checkUpdate(manual: Boolean) = this@MainActivity.checkUpdate(manual)

        @JavascriptInterface
        fun applyUiUpdate(version: Int) = this@MainActivity.applyUiUpdate(version)

        @JavascriptInterface
        fun installApk() { handler.post { installApkUpdate() } }

        @JavascriptInterface
        fun uiVersion(): Int = loadedUiVersion

        @JavascriptInterface
        fun appVersion(): String = this@MainActivity.appVersion().toString()

        @JavascriptInterface
        fun lastCrash(): String {
            val s = prefs.getString("lastCrash", "") ?: ""
            if (s.isNotEmpty()) prefs.edit().remove("lastCrash").apply()
            return s
        }

        @JavascriptInterface
        fun copy(text: String) {
            handler.post {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Tarang", text))
                } catch (e: Exception) { }
            }
        }
    }
}
