package com.ergouf.gecis

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    request: android.webkit.WebResourceRequest
                ) = assetLoader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(GecisBridge(), "GecisNative")
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }

        setContentView(webView)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("GecisNative")
        webView.destroy()
        super.onDestroy()
    }

    inner class GecisBridge {
        @JavascriptInterface
        fun sendMessage(text: String): String {
            // TODO: replace with the local Antigravity runtime bridge.
            // Keep the WebView isolated from fenbi.db and runtime process details.
            return "你问的是：$text\n\n当前原生桥已经接通。下一步把这里替换成本地 AI runtime 调用即可。\n\n公式示例：$$\\frac{a}{b}+\\sqrt{x^2+y^2}$$"
        }
    }
}
