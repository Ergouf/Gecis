package com.ergouf.gecis

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ergouf.gecis.auth.ApiKeyStore
import com.ergouf.gecis.runtime.AntigravityRuntime
import com.ergouf.gecis.runtime.ChatRuntime
import org.json.JSONObject

class MainActivity : ComponentActivity(), ChatRuntime.Listener {
    private lateinit var webView: WebView
    private lateinit var runtime: ChatRuntime
    private lateinit var apiKeyStore: ApiKeyStore

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apiKeyStore = ApiKeyStore(applicationContext)
        runtime = AntigravityRuntime(applicationContext, apiKeyStore)

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
            if (apiKeyStore.hasKey()) {
                runtime.send(requestId, text, this@MainActivity)
                return
            }
            runOnUiThread { promptForApiKey(requestId, text) }
        }
    }

    private fun promptForApiKey(requestId: String, pendingText: String) {
        val input = EditText(this).apply {
            hint = "Gemini API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("连接 Gemini")
            .setMessage("首次使用需要 Gemini API Key。密钥只会加密保存在 Android Keystore 中，不会传给网页界面。")
            .setView(input)
            .setNegativeButton("取消") { _, _ ->
                onError(requestId, "未设置 Gemini API Key")
            }
            .setPositiveButton("保存并继续", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    input.error = "请输入 API Key"
                    return@setOnClickListener
                }
                try {
                    apiKeyStore.save(key)
                    dialog.dismiss()
                    runtime.send(requestId, pendingText, this@MainActivity)
                } catch (error: Throwable) {
                    input.error = error.message ?: "保存失败"
                }
            }
        }
        dialog.show()
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
