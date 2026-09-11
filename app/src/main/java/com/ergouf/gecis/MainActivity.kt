package com.ergouf.gecis

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var lastInsets = Insets.NONE
    private var lastImeBottom = 0

    private val openFenbiDatabase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingAfterDatabase ?: return@registerForActivityResult
        if (uri == null) {
            pendingAfterDatabase = null
            emitStatus("未选择 fenbi.db", "error")
            onError(pending.requestId, "未选择 fenbi.db")
            return@registerForActivityResult
        }

        val displayName = knowledgeBase.importedDisplayName(uri) ?: "fenbi.db"
        emitStatus("正在准备导入 $displayName…", "working")
        importWorker.execute {
            try {
                knowledgeBase.importFrom(uri) { progress ->
                    val status = when (progress.phase) {
                        FenbiKnowledgeBase.ImportPhase.COPYING -> formatCopyProgress(progress)
                        FenbiKnowledgeBase.ImportPhase.VALIDATING -> "文件复制完成，正在校验数据库…"
                        FenbiKnowledgeBase.ImportPhase.SAVING -> "校验通过，正在完成导入…"
                    }
                    emitStatus(status, "working")
                }
                runOnUiThread {
                    if (pendingAfterDatabase?.requestId != pending.requestId) return@runOnUiThread
                    pendingAfterDatabase = null
                    emitStatus("$displayName 导入成功", "success")
                    continueMessage(pending)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (pendingAfterDatabase?.requestId == pending.requestId) {
                        pendingAfterDatabase = null
                        val message = error.message ?: "fenbi.db 导入失败"
                        emitStatus("导入失败：$message", "error")
                        onError(pending.requestId, message)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
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
            setBackgroundColor(Color.TRANSPARENT)
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

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    emitInsets()
                }
            }
            addJavascriptInterface(GecisBridge(), "GecisNative")
            loadUrl(APP_URL)
        }

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            lastInsets = bars
            lastImeBottom = ime.bottom
            emitInsets()
            insets
        }

        setContentView(webView)
        ViewCompat.requestApplyInsets(webView)
        handleAppReturnIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppReturnIntent(intent)
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
            emitStatus("请选择 fenbi.db", "working")
            openFenbiDatabase.launch(arrayOf("*/*"))
            return
        }

        continueMessage(message)
    }

    private fun continueMessage(message: PendingMessage) {
        if (tokenVault.hasCredential()) {
            inflight = message
            emitStatus("正在检索并思考…", "working")
            runtime.send(message.requestId, message.text, this)
        } else {
            beginGoogleOAuth(message)
        }
    }

    private fun beginGoogleOAuth(message: PendingMessage) {
        if (pendingAfterAuth != null) return
        pendingAfterAuth = message
        emitStatus("正在连接 Google 账号…", "working")
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
            emitStatus("Google 账号已连接", "success")
            if (pending != null) {
                inflight = pending
                emitStatus("正在检索并思考…", "working")
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
        emitStatus("Google 登录失败：$message", "error")
        if (pending != null) onError(pending.requestId, message)
    }

    override fun onDelta(requestId: String, text: String) {
        emitStatus("正在回答…", "working")
        emit("delta", requestId, text)
    }

    override fun onComplete(requestId: String, text: String) {
        if (inflight?.requestId == requestId) inflight = null
        emitStatus("已就绪", "idle")
        emit("complete", requestId, text)
    }

    override fun onError(requestId: String, message: String) {
        if (inflight?.requestId == requestId) inflight = null
        emitStatus("发生错误", "error")
        emit("error", requestId, message)
    }

    private fun handleAppReturnIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == APP_RETURN_SCHEME && data.host == APP_RETURN_HOST) {
            emitStatus("正在完成 Google 登录…", "working")
        }
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

    private fun emitStatus(text: String, state: String) {
        runOnUiThread {
            val payload = JSONObject()
                .put("text", text)
                .put("state", state)
                .toString()
            webView.evaluateJavascript(
                "window.GecisChat && window.GecisChat.onStatus($payload);",
                null,
            )
        }
    }

    private fun emitInsets() {
        if (!::webView.isInitialized) return
        val top = lastInsets.top
        val right = lastInsets.right
        val bottom = lastInsets.bottom
        val left = lastInsets.left
        val imeBottom = lastImeBottom
        val script = """
            (() => {
              const root = document.documentElement;
              root.style.setProperty('--android-safe-top', '${top}px');
              root.style.setProperty('--android-safe-right', '${right}px');
              root.style.setProperty('--android-safe-bottom', '${bottom}px');
              root.style.setProperty('--android-safe-left', '${left}px');
              root.style.setProperty('--android-ime-bottom', '${imeBottom}px');

              let style = document.getElementById('gecis-native-insets');
              if (!style) {
                style = document.createElement('style');
                style.id = 'gecis-native-insets';
                style.textContent = `
                  html, body, .app { min-height: 100%; }
                  body { padding: 0; }
                  header {
                    height: calc(58px + var(--android-safe-top, 0px));
                    padding-top: var(--android-safe-top, 0px);
                    padding-left: calc(18px + var(--android-safe-left, 0px));
                    padding-right: calc(18px + var(--android-safe-right, 0px));
                  }
                  main {
                    padding-left: calc(18px + var(--android-safe-left, 0px));
                    padding-right: calc(18px + var(--android-safe-right, 0px));
                  }
                  .composer-wrap {
                    padding-left: calc(14px + var(--android-safe-left, 0px));
                    padding-right: calc(14px + var(--android-safe-right, 0px));
                    padding-bottom: calc(12px + max(var(--android-safe-bottom, 0px), var(--android-ime-bottom, 0px)));
                  }
                `;
                document.head.appendChild(style);
              }
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun formatCopyProgress(progress: FenbiKnowledgeBase.ImportProgress): String {
        val copiedMb = progress.bytesCopied / (1024.0 * 1024.0)
        val total = progress.totalBytes
        return if (total != null && total > 0L) {
            val totalMb = total / (1024.0 * 1024.0)
            val percent = ((progress.fraction ?: 0.0) * 100.0).toInt().coerceIn(0, 100)
            "正在导入 fenbi.db · $percent% · ${"%.1f".format(copiedMb)}/${"%.1f".format(totalMb)} MB"
        } else {
            "正在导入 fenbi.db · 已复制 ${"%.1f".format(copiedMb)} MB"
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
        private const val APP_RETURN_SCHEME = "gecis"
        private const val APP_RETURN_HOST = "oauth-complete"
    }
}
