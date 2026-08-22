package com.hatake716.linuxdesktop.display

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.hatake716.linuxdesktop.BuildConfig
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Local-only noVNC viewer used when the embedded native X11 renderer cannot present reliably.
 * The backing TigerVNC server and websockify process both listen only on 127.0.0.1.
 */
class VncFallbackActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var launchAccepted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchAccepted = isLaunchAllowed(intent)
        if (!launchAccepted) {
            finish()
            return
        }
        instance = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            loadUrl(VNC_URL)
            requestFocus()
        }
        setContentView(webView)
    }

    override fun onResume() {
        super.onResume()
        if (!launchAccepted) return
        instance = WeakReference(this)
        webView?.onResume()
        webView?.requestFocus()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchAccepted = isLaunchAllowed(intent)
        if (!launchAccepted) {
            finish()
            return
        }
        setIntent(intent)
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
        // The repository treats instance disappearance as the teardown ack, so
        // publish it only after WebView/WebSocket destruction has completed.
        synchronized(launchLock) {
            if (instance?.get() === this) instance = null
            if (intent.getStringExtra(EXTRA_LAUNCH_GENERATION) == allowedLaunchGeneration) {
                allowedLaunchGeneration = null
            }
        }
    }

    companion object {
        private const val VNC_URL =
            "http://127.0.0.1:6080/vnc.html?autoconnect=1&resize=scale&reconnect=1&shared=1&view_only=0"
        private const val EXTRA_LAUNCH_GENERATION =
            "com.hatake716.linuxdesktop.extra.VNC_VIEWER_GENERATION"
        private val launchLock = Any()

        @Volatile
        private var instance: WeakReference<VncFallbackActivity>? = null
        private var allowedLaunchGeneration: String? = null

        fun open(context: Context) {
            val generation = UUID.randomUUID().toString()
            synchronized(launchLock) {
                allowedLaunchGeneration = generation
            }
            context.startActivity(
                Intent(context, VncFallbackActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_LAUNCH_GENERATION, generation)
                },
            )
        }

        fun close() {
            val activity = synchronized(launchLock) {
                allowedLaunchGeneration = null
                instance?.get()
            } ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing) activity.finish()
            }
        }

        fun isOpen(): Boolean = instance?.get() != null

        private fun isLaunchAllowed(intent: Intent?): Boolean {
            val generation = intent?.getStringExtra(EXTRA_LAUNCH_GENERATION)
            return synchronized(launchLock) {
                generation != null && generation == allowedLaunchGeneration
            }
        }
    }
}
