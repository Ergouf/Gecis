package com.ergouf.gecis

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ergouf.gecis.auth.OAuthTokenVault
import com.ergouf.gecis.runtime.AntigravityOAuthCoordinator
import com.ergouf.gecis.runtime.AntigravityRuntime
import com.ergouf.gecis.runtime.ChatRuntime
import org.json.JSONObject

class MainActivity : ComponentActivity(), ChatRuntime.Listener, AntigravityOAuthCoordinator.Listener {
    private lateinit var webView: WebView
    private lateinit var runtime: ChatRuntime
    private lateinit var oauth: AntigravityOAuthCoordinator
    private lateinit var tokenVault: OAuthTokenVault
    private var pendingAfterAuth: PendingMessage? = null
    private var inflight: PendingMessage? = null
    private var codeDialog: AlertDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenVault = OAuthTokenVault(applicationContext)
        runtime = AntigravityRuntime(applicationContext, tokenVault)
        oauth = AntigravityOAuthCoordinator(applicationContext, tokenVault)

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
        codeDialog?.dismiss()
        oauth.close()
        runtime.close()
        webView.removeJavascriptInterface("GecisNative")
        webView.destroy()
        super.onDestroy()
    }

    inner class GecisBridge {
        @JavascriptInterface
        fun sendMessage(requestId: String, text: String) {
            val message = PendingMessage(requestId, text)
            if (tokenVault.hasCredential()) {
                inflight = message
                runtime.send(requestId, text, this@MainActivity)
            } else {
                runOnUiThread { beginGoogleOAuth(message) }
            }
        }
    }

    private fun beginGoogleOAuth(message: PendingMessage) {
        if (pendingAfterAuth != null) return
        pendingAfterAuth = message
        Toast.makeText(this, "首次使用需要登录 Google 账号", Toast.LENGTH_SHORT).show()
        oauth.start(this)
    }

    override fun onAuthorizationUrl(url: String) {
        runOnUiThread {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                failPendingAuth("设备上没有可打开 Google 登录页面的浏览器")
            }
        }
    }

    override fun onAuthorizationCodeRequested() {
        runOnUiThread {
            if (codeDialog?.isShowing == true) return@runOnUiThread
            val input = EditText(this).apply {
                hint = "授权码"
                setSingleLine(true)
            }
            codeDialog = AlertDialog.Builder(this)
                .setTitle("完成 Google 登录")
                .setMessage("在浏览器中选择 Google 账号并完成授权后，复制页面显示的一次性授权码并粘贴到这里。")
                .setView(input)
                .setNegativeButton("取消") { _, _ -> failPendingAuth("已取消 Google 登录") }
                .setPositiveButton("继续", null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val code = input.text?.toString()?.trim().orEmpty()
                            if (code.isBlank()) {
                                input.error = "请输入授权码"
                                return@setOnClickListener
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                            oauth.submitAuthorizationCode(code)
                        }
                    }
                    dialog.show()
                }
        }
    }

    override fun onAuthenticated() {
        runOnUiThread {
            codeDialog?.dismiss()
            codeDialog = null
            val pending = pendingAfterAuth
            pendingAfterAuth = null
            Toast.makeText(this, "Google 账号已连接", Toast.LENGTH_SHORT).show()
            if (pending != null) {
                inflight = pending
                runtime.send(pending.requestId, pending.text, this@MainActivity)
            }
        }
    }

    override fun onAuthenticationRequired(requestId: String) {
        runOnUiThread {
            val message = inflight?.takeIf { it.requestId == requestId }
            inflight = null
            if (message != null) {
                beginGoogleOAuth(message)
            } else {
                onError(requestId, "Google 登录已失效，请重新登录")
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread { failPendingAuth(message) }
    }

    private fun failPendingAuth(message: String) {
        oauth.cancel()
        codeDialog?.dismiss()
        codeDialog = null
        val pending = pendingAfterAuth
        pendingAfterAuth = null
        if (pending != null) onError(pending.requestId, message)
    }

    override fun onDelta(requestId: String, text: String) {
        emit("delta", requestId, text)
    }

    override fun onComplete(requestId: String, text: String) {
        if (inflight?.requestId == requestId) inflight = null
        emit("complete", requestId, text)
    }

    override fun onError(requestId: String, message: String) {
        if (inflight?.requestId == requestId) inflight = null
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

    private data class PendingMessage(val requestId: String, val text: String)
}
