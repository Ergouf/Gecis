package com.ergouf.gecis

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ergouf.gecis.auth.OAuthTokenVault
import com.ergouf.gecis.knowledge.FenbiKnowledgeBase
import com.ergouf.gecis.runtime.AntigravityOAuthCoordinator
import com.ergouf.gecis.runtime.AntigravityRuntime
import com.ergouf.gecis.runtime.ChatRuntime
import com.ergouf.gecis.runtime.KnowledgeAugmentingRuntime
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), ChatRuntime.Listener, AntigravityOAuthCoordinator.Listener {
    private lateinit var webView: WebView
    private lateinit var runtime: ChatRuntime
    private lateinit var oauth: AntigravityOAuthCoordinator
    private lateinit var tokenVault: OAuthTokenVault
    private lateinit var knowledgeBase: FenbiKnowledgeBase
    private val importWorker = Executors.newSingleThreadExecutor()

    private var pendingAfterDatabase: PendingMessage? = null
    private var pendingAfterAuth: PendingMessage? = null
    private var inflight: PendingMessage? = null

    private val openFenbiDatabase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingAfterDatabase ?: return@registerForActivityResult
        if (uri == null) {
            pendingAfterDatabase = null
            onError(pending.requestId, "未选择 fenbi.db")
            return@registerForActivityResult
        }

        Toast.makeText(this, "正在导入 fenbi.db…", Toast.LENGTH_SHORT).show()
        importWorker.execute {
            try {
                knowledgeBase.importFrom(uri)
                runOnUiThread {
                    if (pendingAfterDatabase?.requestId != pending.requestId) return@runOnUiThread
                    pendingAfterDatabase = null
                    val name = knowledgeBase.importedDisplayName(uri) ?: "fenbi.db"
                    Toast.makeText(this, "$name 已就绪", Toast.LENGTH_SHORT).show()
                    continueMessage(pending)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (pendingAfterDatabase?.requestId == pending.requestId) {
                        pendingAfterDatabase = null
                        onError(pending.requestId, error.message ?: "fenbi.db 导入失败")
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenVault = OAuthTokenVault(applicationContext)
        knowledgeBase = FenbiKnowledgeBase(applicationContext)
        runtime = KnowledgeAugmentingRuntime(
            AntigravityRuntime(applicationContext, tokenVault),
            knowledgeBase,
        )
        oauth = AntigravityOAuthCoordinator(tokenVault)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClientCompat() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    if (isBundledAsset(url)) return false
                    if (request.isForMainFrame && (url.scheme == "https" || url.scheme == "http")) {
                        openExternalUrl(url)
                    }
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (isBundledAsset(request.url)) {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }
                    return blockedResource()
                }
            }
            addJavascriptInterface(GecisBridge(), "GecisNative")
            loadUrl(APP_URL)
        }

        setContentView(webView)
    }

    override fun onDestroy() {
        oauth.close()
        runtime.close()
        importWorker.shutdownNow()
        webView.removeJavascriptInterface("GecisNative")
        webView.destroy()
        super.onDestroy()
    }

    inner class GecisBridge {
        @JavascriptInterface
        fun sendMessage(requestId: String, text: String) {
            val message = PendingMessage(requestId, text)
            runOnUiThread { handleSubmittedMessage(message) }
        }
    }

    private fun handleSubmittedMessage(message: PendingMessage) {
        if (pendingAfterDatabase != null || pendingAfterAuth != null || inflight != null) return

        if (!knowledgeBase.hasDatabase()) {
            pendingAfterDatabase = message
            Toast.makeText(this, "首次使用请选择 fenbi.db", Toast.LENGTH_SHORT).show()
            openFenbiDatabase.launch(arrayOf("*/*"))
            return
        }

        continueMessage(message)
    }

    private fun continueMessage(message: PendingMessage) {
        if (tokenVault.hasCredential()) {
            inflight = message
            runtime.send(message.requestId, message.text, this)
        } else {
            beginGoogleOAuth(message)
        }
    }

    private fun beginGoogleOAuth(message: PendingMessage) {
        if (pendingAfterAuth != null) return
        pendingAfterAuth = message
        Toast.makeText(this, "请使用 Google 账号登录", Toast.LENGTH_SHORT).show()
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

    override fun onAuthenticated() {
        runOnUiThread {
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
        runOnUiThread {
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

    private fun isBundledAsset(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == APP_HOST &&
            uri.path?.startsWith("/assets/") == true

    private fun openExternalUrl(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun blockedResource(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private data class PendingMessage(val requestId: String, val text: String)

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val APP_URL = "https://$APP_HOST/assets/index.html"
    }
}
