package com.salil.tarang

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            pushState()
            handler.postDelayed(this, 500)
        }
    }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            handleFiles(uris)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        web.setBackgroundColor(0xFF0B0B10.toInt())
        web.addJavascriptInterface(Bridge(), "Native")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.onBack ? onBack() : false") { result ->
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

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = try { future.get() } catch (e: Exception) { null }
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun pushState() {
        val c = controller ?: return
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
        web.evaluateJavascript("window.onNativeState && onNativeState($o)", null)
    }

    private fun handleFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val arr = JSONArray()
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // not persistable; still playable this session
            }
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
        web.evaluateJavascript("window.onLocalFiles && onLocalFiles($arr)", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun playQueue(json: String, index: Int) {
            runOnUiThread {
                try {
                    val arr = JSONArray(json)
                    val items = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        val meta = MediaMetadata.Builder()
                            .setTitle(o.optString("title"))
                            .setArtist(o.optString("artist"))
                        val art = o.optString("art")
                        if (art.isNotEmpty()) meta.setArtworkUri(Uri.parse(art))
                        MediaItem.Builder()
                            .setMediaId(o.optString("id"))
                            .setUri(o.getString("url"))
                            .setMediaMetadata(meta.build())
                            .build()
                    }
                    controller?.run {
                        setMediaItems(items, index.coerceIn(0, maxOf(0, items.size - 1)), 0L)
                        prepare()
                        play()
                    }
                } catch (e: Exception) {
                    web.evaluateJavascript("window.toast && toast('Could not play this')", null)
                }
            }
        }

        @JavascriptInterface
        fun toggle() {
            runOnUiThread {
                controller?.run {
                    if (isPlaying) pause() else {
                        if (playbackState == Player.STATE_IDLE) prepare()
                        play()
                    }
                }
            }
        }

        @JavascriptInterface
        fun next() { runOnUiThread { controller?.seekToNextMediaItem() } }

        @JavascriptInterface
        fun prev() {
            runOnUiThread {
                controller?.run {
                    if (currentPosition > 3000) seekTo(0) else seekToPreviousMediaItem()
                }
            }
        }

        @JavascriptInterface
        fun jumpTo(index: Int) { runOnUiThread { controller?.run { seekTo(index, 0L); play() } } }

        @JavascriptInterface
        fun seek(ms: Double) { runOnUiThread { controller?.seekTo(ms.toLong()) } }

        @JavascriptInterface
        fun setShuffle(on: Boolean) { runOnUiThread { controller?.shuffleModeEnabled = on } }

        @JavascriptInterface
        fun setRepeat(mode: Int) { runOnUiThread { controller?.repeatMode = mode } }

        @JavascriptInterface
        fun pickFiles() { runOnUiThread { pickFiles.launch(arrayOf("audio/*")) } }
    }
}
