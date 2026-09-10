package com.ergouf.gecis

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ergouf.gecis.runtime.AntigravityRuntime
import com.ergouf.gecis.runtime.ChatRuntime
import org.json.JSONObject

class MainActivity : ComponentActivity(), ChatRuntime.Listener {
    private lateinit var webView: WebView
    private lateinit var runtime: ChatRuntime

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = AntigravityRuntime(applicationContext)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ) = assetLoader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(GecisBridge(), "GecisNative")
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }

        setContentView(webView)
    }

    override fun onDestroy() {
        runtime.close()
        webView.removeJavascriptInterface("GecisNative")
        webView.destroy()
        super.onDestroy()
    }

    inner class GecisBridge {
        @JavascriptInterface
        fun sendMessage(requestId: String, text: String) {
            runtime.send(requestId, text, this@MainActivity)
        }
    }

    override fun onDelta(requestId: String, text: String) {
        emit("delta", requestId, text)
    }

    override fun onComplete(requestId: String, text: String) {
        emit("complete", requestId, text)
    }

    override fun onError(requestId: String, message: String) {
        emit("error", requestId, message)
    }

    private fun emit(type: String, requestId: String, text: String) {
        val payload = JSONObject()
            .put("type", type)
            .put("requestId", requestId)
            .put("text", text)
            .toString()
        webView.evaluateJavascript(
            "window.GecisChat && window.GecisChat.onNativeEvent($payload);",
            null,
        )
    }
}
