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

/**
 * Local-only noVNC viewer used when the device cannot keep Termux:X11's app_process X server alive.
 * The backing TigerVNC server and websockify process both listen only on 127.0.0.1.
 */
class VncFallbackActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
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
        instance = this
        webView?.onResume()
        webView?.requestFocus()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val VNC_URL =
            "http://127.0.0.1:6080/vnc.html?autoconnect=1&resize=scale&reconnect=1&shared=1&view_only=0"

        @Volatile
        private var instance: VncFallbackActivity? = null

        fun open(context: Context) {
            context.startActivity(
                Intent(context, VncFallbackActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }

        fun close() {
            val activity = instance ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing) activity.finish()
            }
        }

        fun isOpen(): Boolean = instance?.let { !it.isFinishing && !it.isDestroyed } == true
    }
}
