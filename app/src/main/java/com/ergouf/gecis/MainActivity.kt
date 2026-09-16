package com.ergouf.gecis

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
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
import com.ergouf.gecis.history.ChatHistoryStore
import com.ergouf.gecis.history.PendingChatMessage
import com.ergouf.gecis.history.PendingChatState
import com.ergouf.gecis.history.PendingChatStore
import com.ergouf.gecis.knowledge.FenbiKnowledgeBase
import com.ergouf.gecis.runtime.AntigravityModelCatalog
import com.ergouf.gecis.runtime.AntigravityOAuthCoordinator
import com.ergouf.gecis.runtime.OAuthSessionService
import com.ergouf.gecis.runtime.AntigravityRuntime
import com.ergouf.gecis.runtime.ChatRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), ChatRuntime.Listener, AntigravityOAuthCoordinator.Listener {
    private lateinit var webView: WebView
    private lateinit var runtime: ChatRuntime
    private lateinit var oauth: AntigravityOAuthCoordinator
    private lateinit var tokenVault: OAuthTokenVault
    private lateinit var knowledgeBase: FenbiKnowledgeBase
    private lateinit var historyStore: ChatHistoryStore
    private lateinit var pendingStore: PendingChatStore
    private lateinit var runtimePrefs: SharedPreferences
    private lateinit var modelCatalog: AntigravityModelCatalog
    private val importWorker = Executors.newSingleThreadExecutor()
    private val historyWorker = Executors.newSingleThreadExecutor()

    private var pendingAfterDatabase: PendingChatMessage? = null
    private var pendingAfterAuth: PendingChatMessage? = null
    private var inflight: PendingChatMessage? = null
    private var currentConversationId: Long? = null
    private var currentProjectId: Long? = null
    private var lastInsets = Insets.NONE
    private var lastImeBottom = 0
    private var pageReady = false

    /** Menu-driven import: no chat message is waiting. */
    private var fenbiImportOnly = false

    private val openFenbiDatabase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handleFenbiDatabasePicked(uri)
    }

    private val fallbackFenbiDatabase = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleFenbiDatabasePicked(result.data?.data)
    }

    private fun handleFenbiDatabasePicked(uri: Uri?) {
        val importOnly = fenbiImportOnly
        val pending = pendingAfterDatabase
        if (!importOnly && pending == null) return

        if (uri == null) {
            fenbiImportOnly = false
            if (pending != null) {
                pendingAfterDatabase = null
                persistPendingState()
                emitStatus("未选择 fenbi.db", "error")
                onError(pending.requestId, "未选择 fenbi.db")
            } else {
                emitStatus("已取消导入", "idle")
            }
            return
        }

        val displayName = knowledgeBase.importedDisplayName(uri) ?: "fenbi.db"
        emitStatus("正在导入 $displayName…", "working")
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
                    if (isDestroyed) return@runOnUiThread
                    fenbiImportOnly = false
                    if (pendingAfterDatabase != null && pendingAfterDatabase?.requestId == pending?.requestId) {
                        pendingAfterDatabase = null
                        persistPendingState()
                        emitStatus("$displayName 导入成功", "success")
                        persistUserMessageThenContinue(pending!!)
                    } else {
                        emitStatus("$displayName 导入成功", "success")
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    fenbiImportOnly = false
                    val message = error.message ?: "fenbi.db 导入失败"
                    if (pendingAfterDatabase != null && pendingAfterDatabase?.requestId == pending?.requestId) {
                        pendingAfterDatabase = null
                        persistPendingState()
                        emitStatus("导入失败：$message", "error")
                        onError(pending!!.requestId, message)
                    } else {
                        emitStatus("导入失败：$message", "error")
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
        val app = application as GecisApp
        tokenVault = app.tokenVault
        pendingStore = app.pendingChatStore
        knowledgeBase = FenbiKnowledgeBase(applicationContext)
        historyStore = ChatHistoryStore(applicationContext)
        runtimePrefs = getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE)
        modelCatalog = AntigravityModelCatalog(applicationContext, tokenVault)
        currentProjectId = runCatching { historyStore.ensureDefaultProject() }.getOrNull()
        restorePendingState()
        runtime = createConfiguredRuntime(currentConversationId?.let { historyStore.getAgyConversationId(it) })
        oauth = app.oauth
        oauth.setListener(this)

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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (isBundledAsset(url)) return false
                    if (request.isForMainFrame && (url.scheme == "https" || url.scheme == "http")) openExternalUrl(url)
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (isBundledAsset(request.url)) return assetLoader.shouldInterceptRequest(request.url)
                    return blockedResource()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    emitInsets()
                    emitHistory(resumeAfter = true)
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

    override fun onResume() {
        super.onResume()
        if (tokenVault.hasCredential() && !oauth.isRunning()) OAuthSessionService.cancelReturn(this)
        if (pageReady) resumePendingIfReady()
    }

    override fun onDestroy() {
        oauth.setListener(null)
        runtime.close()
        importWorker.shutdownNow()
        historyWorker.shutdownNow()
        runCatching { historyStore.close() }
        if (::webView.isInitialized) {
            runCatching { webView.removeJavascriptInterface("GecisNative") }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    inner class GecisBridge {
        @JavascriptInterface
        fun sendMessage(requestId: String, text: String) {
            runOnUiThread {
                try {
                    if (pendingAfterDatabase != null || pendingAfterAuth != null || inflight != null) {
                        onError(requestId, "正在处理上一条消息")
                        return@runOnUiThread
                    }
                    handleSubmittedMessage(PendingChatMessage(requestId, text, null))
                } catch (error: Throwable) {
                    onError(requestId, "准备对话失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }

        @JavascriptInterface
        fun getHistory(): String = runCatching { historyStore.snapshot(currentConversationId) }
            .getOrElse { historyErrorSnapshot(it) }

        @JavascriptInterface
        fun getSetupStatus(): String {
            return try {
                JSONObject()
                    .put("platform", "android")
                    .put("runtime", "ready")
                    .put("auth", if (tokenVault.hasCredential()) "connected" else "required")
                    .put(
                        "knowledge",
                        JSONObject()
                            .put("available", knowledgeBase.hasDatabase())
                            .put("name", if (knowledgeBase.hasDatabase()) "fenbi.db" else JSONObject.NULL),
                    )
                    .put("agyInstalled", true)
                    .put("loggedIn", tokenVault.hasCredential())
                    .put("hasFenbi", knowledgeBase.hasDatabase())
                    .put("model", configuredModel() ?: JSONObject.NULL)
                    .put("effort", configuredModelEffort() ?: JSONObject.NULL)
                    .toString()
            } catch (error: Throwable) {
                JSONObject()
                    .put("platform", "android")
                    .put("runtime", "ready")
                    .put("auth", "unknown")
                    .put("knowledge", JSONObject().put("available", false).put("name", JSONObject.NULL))
                    .put("agyInstalled", true)
                    .put("loggedIn", false)
                    .put("hasFenbi", false)
                    .put("model", JSONObject.NULL)
                    .put("effort", JSONObject.NULL)
                    .put("error", error.message)
                    .toString()
            }
        }

        @JavascriptInterface
        fun getRuntimeSettings(): String = runtimeSettingsJson().toString()

        @JavascriptInterface
        fun getAvailableModels(): String = runCatching {
            modelCatalog.loadJson()
                .put("selected", configuredModel() ?: JSONObject.NULL)
                .toString()
        }.getOrElse { error ->
            JSONObject()
                .put("models", JSONArray())
                .put("selected", configuredModel() ?: JSONObject.NULL)
                .put("error", error.message ?: "无法读取上游模型列表")
                .toString()
        }

        @JavascriptInterface
        fun setRuntimeSettings(model: String, effort: String): String {
            val normalizedModel = model.trim().takeIf { it.isNotEmpty() }
            if (normalizedModel != null && !MODEL_SLUG.matches(normalizedModel)) {
                throw IllegalArgumentException("模型标识无效")
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            var payload: String? = null
            runOnUiThread {
                try {
                    require(inflight == null && pendingAfterAuth == null && pendingAfterDatabase == null) {
                        "请等待当前回答完成后再切换模型"
                    }
                    runtimePrefs.edit().apply {
                        if (normalizedModel == null) remove(PREF_MODEL) else putString(PREF_MODEL, normalizedModel)
                        remove(PREF_EFFORT)
                    }.apply()
                    restartRuntimeForCurrentConversation()
                    payload = runtimeSettingsJson().toString()
                    emitStatus("模型设置已应用", "success")
                } catch (error: Throwable) {
                    payload = JSONObject().put("error", error.message ?: "模型设置失败").toString()
                    emitStatus(error.message ?: "模型设置失败", "error")
                } finally {
                    latch.countDown()
                }
            }
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            return payload ?: JSONObject().put("error", "模型设置超时").toString()
        }

        @JavascriptInterface
        fun createProject(name: String): String = runCatching {
            require(inflight == null && pendingAfterAuth == null && pendingAfterDatabase == null) { "当前消息尚未完成" }
            val projectId = historyStore.createProject(name)
            currentProjectId = projectId
            currentConversationId = null
            persistPendingState()
            historyStore.snapshot(currentConversationId)
        }.getOrElse { historyErrorSnapshot(it) }

        @JavascriptInterface
        fun newConversation(projectId: Long): String = runCatching {
            require(inflight == null && pendingAfterAuth == null && pendingAfterDatabase == null) { "当前消息尚未完成" }
            val resolvedProject = projectId.takeIf(historyStore::projectExists) ?: historyStore.ensureDefaultProject()
            currentProjectId = resolvedProject
            currentConversationId = historyStore.createConversation(resolvedProject)
            restartRuntimeForCurrentConversation()
            persistPendingState()
            historyStore.snapshot(currentConversationId)
        }.getOrElse { historyErrorSnapshot(it) }

        @JavascriptInterface
        fun openConversation(conversationId: Long): String = runCatching {
            require(inflight == null && pendingAfterAuth == null && pendingAfterDatabase == null) { "当前消息尚未完成" }
            require(historyStore.conversationExists(conversationId)) { "历史会话不存在" }
            currentConversationId = conversationId
            restartRuntimeForCurrentConversation()
            persistPendingState()
            historyStore.snapshot(currentConversationId)
        }.getOrElse { historyErrorSnapshot(it) }

        @JavascriptInterface
        fun moveConversation(conversationId: Long, projectId: Long): String = runCatching {
            require(inflight == null && pendingAfterAuth == null && pendingAfterDatabase == null) { "当前消息尚未完成" }
            historyStore.moveConversation(conversationId, projectId)
            if (currentConversationId == conversationId) currentProjectId = projectId
            persistPendingState()
            historyStore.snapshot(currentConversationId)
        }.getOrElse { historyErrorSnapshot(it) }

        @JavascriptInterface
        fun deleteConversation(conversationId: Long): String = runCatching {
            require(inflight == null && pendingAfterAuth == null && pendingAfterDatabase == null) { "当前消息尚未完成" }
            historyStore.deleteConversation(conversationId)
            if (currentConversationId == conversationId) {
                currentConversationId = null
                restartRuntimeForCurrentConversation()
            }
            persistPendingState()
            historyStore.snapshot(currentConversationId)
        }.getOrElse { historyErrorSnapshot(it) }

        @JavascriptInterface
        fun getConversationId(): String {
            val localId = currentConversationId ?: return JSONObject()
                .put("localId", JSONObject.NULL)
                .put("agyConversationId", JSONObject.NULL)
                .toString()
            val agy = historyStore.getAgyConversationId(localId)
            return JSONObject()
                .put("localId", localId)
                .put("title", historyStore.conversationTitle(localId))
                .put("agyConversationId", agy ?: JSONObject.NULL)
                .toString()
        }

        @JavascriptInterface
        fun exportConversation(format: String): String {
            val localId = currentConversationId ?: throw IllegalStateException("当前没有打开的会话")
            val title = historyStore.conversationTitle(localId)
            val messages = historyStore.listMessages(localId)
            val agyId = historyStore.getAgyConversationId(localId)
            val body = when (format.lowercase()) {
                "md", "markdown" -> buildMarkdownExport(title, localId, agyId, messages)
                "html" -> buildHtmlExport(title, localId, agyId, messages)
                else -> throw IllegalArgumentException("不支持的导出格式：$format")
            }
            val safeTitle = title.map { if (it.isLetterOrDigit() || it in "-_ ") it else '_' }.joinToString("")
                .trim().ifBlank { "会话" }.take(40)
            val fileName = "Gecis-$safeTitle-$localId.${if (format.equals("html", true)) "html" else "md"}"
            val ctx = applicationContext
            val file = java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, fileName)
            file.writeText(body)
            emitStatus("已导出 ${file.absolutePath}", "success")
            return file.absolutePath
        }

        @JavascriptInterface
        fun importFenbi(): String {
            runOnUiThread {
                try {
                    if (inflight != null || pendingAfterAuth != null || pendingAfterDatabase != null) {
                        emitStatus("请等待当前消息完成后再导入题库", "error")
                        return@runOnUiThread
                    }
                    fenbiImportOnly = true
                    emitStatus("请选择 fenbi.db 文件…", "working")
                    try {
                        openFenbiDatabase.launch(arrayOf("*/*"))
                    } catch (primary: Throwable) {
                        try {
                            val intent = Intent(Intent.ACTION_GET_CONTENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("*/*")
                            fallbackFenbiDatabase.launch(intent)
                        } catch (fallback: Throwable) {
                            fenbiImportOnly = false
                            emitStatus("无法打开文件选择器：${fallback.message ?: primary.message}", "error")
                        }
                    }
                } catch (error: Throwable) {
                    fenbiImportOnly = false
                    emitStatus("导入失败：${error.message}", "error")
                }
            }
            return "started"
        }

        @JavascriptInterface
        fun startLogin(requestId: String): String {
            runOnUiThread {
                if (tokenVault.hasCredential()) {
                    emitStatus("已登录 Google 账号", "success")
                    resumePendingIfReady()
                    return@runOnUiThread
                }
                emitStatus("正在打开 Google 登录…", "working")
                if (!oauth.isRunning()) oauth.start(this@MainActivity)
            }
            return "started"
        }

        @JavascriptInterface
        fun retryMessage(requestId: String, text: String) {
            runOnUiThread {
                try {
                    require(inflight == null) { "当前消息尚未完成" }
                    val pending = PendingChatMessage(requestId, text, currentConversationId)
                    continueMessage(pending)
                } catch (error: Throwable) {
                    onError(requestId, error.message ?: "重试失败")
                }
            }
        }

        @JavascriptInterface
        fun resumeConversation(agyId: String): String {
            val id = agyId.trim()
            if (id.isEmpty()) return historyErrorSnapshot(IllegalArgumentException("会话 ID 不能为空"))
            val latch = java.util.concurrent.CountDownLatch(1)
            var payload: String? = null
            runOnUiThread {
                try {
                    require(inflight == null && pendingAfterAuth == null && pendingAfterDatabase == null) { "当前消息尚未完成" }
                    val project = currentProjectId ?: historyStore.ensureDefaultProject()
                    val conversationId = historyStore.createConversation(project)
                    historyStore.setAgyConversationId(conversationId, id)
                    currentConversationId = conversationId
                    restartRuntimeForCurrentConversation()
                    persistPendingState()
                    payload = historyStore.snapshot(conversationId)
                    emitStatus("已接入共享会话，可继续提问", "success")
                } catch (error: Throwable) {
                    payload = historyErrorSnapshot(error)
                    emitStatus(error.message ?: "无法续聊", "error")
                }
                latch.countDown()
            }
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            return payload ?: historyErrorSnapshot(IllegalStateException("续聊超时"))
        }
    }

    private fun configuredModel(): String? =
        runtimePrefs.getString(PREF_MODEL, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun configuredModelEffort(): String? =
        configuredModel()?.substringAfterLast('-', "")?.takeIf { it in EFFORT_LEVELS }

    private fun runtimeSettingsJson(): JSONObject = JSONObject()
        .put("model", configuredModel() ?: JSONObject.NULL)
        .put("effort", configuredModelEffort() ?: JSONObject.NULL)
        .put("contextPolicy", "antigravity-compaction")

    private fun createConfiguredRuntime(resumeId: String?): AntigravityRuntime =
        AntigravityRuntime(applicationContext, tokenVault, knowledgeBase).also {
            it.resumeConversationId = resumeId
            it.modelSlug = configuredModel()
            // The selected provider slug already encodes any supported reasoning variant.
            // Never combine it with a stale independent --effort flag.
            it.reasoningEffort = null
            it.onConversationId = { id ->
                val localId = currentConversationId
                if (localId != null) runCatching { historyStore.setAgyConversationId(localId, id) }
            }
        }

    private fun restartRuntimeForCurrentConversation() {
        val agyId = currentConversationId?.let { historyStore.getAgyConversationId(it) }
        runtime.close()
        runtime = createConfiguredRuntime(agyId)
    }

    private fun buildMarkdownExport(
        title: String,
        localId: Long,
        agyId: String?,
        messages: List<Pair<String, String>>,
    ): String = buildString {
        append("# ").append(title).append("\n\n")
        append("- 本地会话 ID: `").append(localId).append("`\n")
        if (!agyId.isNullOrBlank()) {
            append("- Antigravity 会话 ID: `").append(agyId).append("`\n")
            append("  - 继续对话：`agy --conversation ").append(agyId).append("`\n")
        }
        append('\n')
        for ((role, content) in messages) {
            val label = if (role == "user") "用户" else "助手"
            append("## ").append(label).append("\n\n")
            append(content.trim()).append("\n\n---\n\n")
        }
    }

    private fun buildHtmlExport(
        title: String,
        localId: Long,
        agyId: String?,
        messages: List<Pair<String, String>>,
    ): String {
        val blocks = buildString {
            for ((role, content) in messages) {
                val label = if (role == "user") "用户" else "助手"
                val klass = if (role == "user") "user" else "assistant"
                val escaped = content
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                append("<section class=\"message $klass\"><div class=\"meta\">$label</div>")
                append("<div class=\"bubble\" data-md=\"$escaped\"></div></section>\n")
            }
        }
        val agyLine = if (!agyId.isNullOrBlank()) {
            "<li>Antigravity 会话 ID：<code>$agyId</code> · 继续：<code>agy --conversation $agyId</code></li>"
        } else ""
        return """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8" /><title>$title</title>
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css" />
            <style>
            body{font-family:Inter,system-ui,sans-serif;background:#f7f7f5;color:#171717;margin:0;padding:32px}
            main{max-width:760px;margin:0 auto}.message{margin:0 0 28px;line-height:1.72}
            .user .bubble{max-width:82%;margin-left:auto;background:#e9e9e5;border-radius:20px 20px 5px 20px;padding:12px 16px;white-space:pre-wrap}
            .assistant img{max-width:100%}.katex-display{overflow-x:auto}
            </style></head><body><main>
            <h1>$title</h1>
            <ul><li>本地会话 ID：<code>$localId</code></li>$agyLine</ul>
            $blocks
            </main>
            <script src="https://cdn.jsdelivr.net/npm/marked@14.1.0/marked.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/dompurify@3.1.6/dist/purify.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
            <script>
            function protectMath(s){const e=[],p='GECISMATHTOKEN',x='ENDTOKEN';
            const re=/\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\)|\$[^$\n]+?\$/g;
            return {text:s.replace(re,m=>{e.push(m);return p+(e.length-1)+x}),e,p,x};}
            function restore(h,pm){return h.replace(new RegExp(pm.p+'(\\d+)'+pm.x,'g'),(_,i)=>pm.e[Number(i)]||'');}
            document.querySelectorAll('.bubble[data-md]').forEach(el=>{
              const md=el.getAttribute('data-md')||'';const pm=protectMath(md);
              const dirty=marked.parse(pm.text,{gfm:true,breaks:true});
              const clean=DOMPurify.sanitize(dirty,{USE_PROFILES:{html:true},FORBID_TAGS:['iframe','object','embed','style','form','input','button','script']});
              el.innerHTML=restore(clean,pm);
              if(window.renderMathInElement)renderMathInElement(el,{delimiters:[{left:'$$',right:'$$',display:true},{left:'\\[',right:'\\]',display:true},{left:'$',right:'$',display:false},{left:'\\(',right:'\\)',display:false}],ignoredTags:['script','noscript','style','textarea','pre','code','option'],throwOnError:false,trust:false});
            });
            </script></body></html>
        """.trimIndent()
    }

    private fun handleSubmittedMessage(message: PendingChatMessage) {
        persistUserMessageThenContinue(message)
    }

    private fun launchFenbiPicker(message: PendingChatMessage) {
        try {
            openFenbiDatabase.launch(arrayOf("*/*"))
        } catch (primary: Throwable) {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                fallbackFenbiDatabase.launch(intent)
            } catch (fallback: Throwable) {
                pendingAfterDatabase = null
                persistPendingState()
                val reason = fallback.message ?: primary.message ?: "系统文件选择器不可用"
                emitStatus("无法打开文件选择器", "error")
                onError(message.requestId, "无法打开系统文件选择器：$reason")
            }
        }
    }

    private fun persistUserMessageThenContinue(message: PendingChatMessage) {
        if (message.conversationId != null) {
            continueMessage(message)
            return
        }
        val requestedConversation = currentConversationId
        val requestedProject = currentProjectId
        emitStatus("正在准备对话…", "working")
        historyWorker.execute {
            val conversationResult = runCatching {
                requestedConversation?.takeIf(historyStore::conversationExists)
                    ?: historyStore.createConversation(requestedProject)
            }
            val conversationId = conversationResult.getOrNull()
            val saveResult = if (conversationId != null) {
                runCatching { historyStore.appendMessage(conversationId, "user", message.text) }
            } else {
                Result.failure(conversationResult.exceptionOrNull() ?: IllegalStateException("无法创建本地历史会话"))
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (conversationId != null) currentConversationId = conversationId
                saveResult.exceptionOrNull()?.let { error -> emitStatus("历史记录保存失败：${shortError(error)}", "error") }
                persistPendingState()
                emitHistory()
                continueMessage(message.copy(conversationId = conversationId))
            }
        }
    }

    private fun continueMessage(message: PendingChatMessage) {
        if (tokenVault.hasCredential()) {
            inflight = message
            persistPendingState()
            emitStatus("正在检索并思考…", "working")
            runtime.send(message.requestId, message.text, this)
        } else {
            pendingAfterAuth = message
            persistPendingState()
            emitActionRequired(
                requestId = message.requestId,
                kind = "auth",
                title = "需要连接 Google 账号",
                message = "连接账号后会自动继续刚才的问题。",
                actions = arrayOf("login"),
            )
            emitStatus("等待连接 Google 账号", "idle")
        }
    }

    private fun beginGoogleOAuth(message: PendingChatMessage) {
        pendingAfterAuth = message
        persistPendingState()
        emitStatus("正在连接 Google 账号…", "working")
        if (oauth.isRunning()) return
        oauth.start(this)
    }

    override fun onAuthorizationUrl(url: String) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { putExtra(Browser.EXTRA_APPLICATION_ID, packageName) },
                )
            } catch (_: ActivityNotFoundException) {
                failPendingAuth("设备上没有可打开 Google 登录页面的浏览器")
            }
        }
    }

    override fun onAuthenticated() {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            OAuthSessionService.bringAppToFront(this)
            emitStatus("Google 账号已连接", "success")
            resumePendingIfReady()
        }
    }

    override fun onAuthenticationRequired(requestId: String) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val message = inflight?.takeIf { it.requestId == requestId }
            inflight = null
            persistPendingState()
            if (message != null) {
                pendingAfterAuth = message
                persistPendingState()
                emitActionRequired(
                    requestId = requestId,
                    kind = "auth",
                    title = "Google 登录已失效",
                    message = "重新连接后会自动继续刚才的问题。",
                    actions = arrayOf("login"),
                )
            } else {
                onError(requestId, "Google 登录已失效，请重新登录")
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            failPendingAuth(message)
        }
    }

    private fun failPendingAuth(message: String) {
        oauth.cancel()
        val pending = pendingAfterAuth
        pendingAfterAuth = null
        persistPendingState()
        emitStatus("Google 登录失败：$message", "error")
        if (pending != null) {
            pendingAfterAuth = pending
            persistPendingState()
            emitActionRequired(
                requestId = pending.requestId,
                kind = "auth",
                title = "账号连接没有完成",
                message = message,
                actions = arrayOf("login"),
            )
        }
    }

    override fun onDelta(requestId: String, text: String) {
        emitStatus("正在回答…", "working")
        emit("delta", requestId, text)
    }

    override fun onComplete(requestId: String, text: String) {
        val completed = inflight?.takeIf { it.requestId == requestId }
        if (completed != null) inflight = null
        persistPendingState()
        emitStatus("已就绪", "idle")
        emit("complete", requestId, text)

        val conversationId = completed?.conversationId ?: return
        historyWorker.execute {
            val result = runCatching { historyStore.appendMessage(conversationId, "assistant", text) }
            if (result.isSuccess) emitHistory()
            else emitStatus("回答完成，但历史记录保存失败：${shortError(result.exceptionOrNull())}", "error")
        }
    }

    override fun onError(requestId: String, message: String) {
        if (inflight?.requestId == requestId) inflight = null
        persistPendingState()
        emitStatus("发生错误", "error")
        emit("error", requestId, message)
    }

    private fun handleAppReturnIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == APP_RETURN_SCHEME && data.host == APP_RETURN_HOST) {
            emitStatus("正在完成 Google 登录…", "working")
            resumePendingIfReady()
        }
    }

    private fun resumePendingIfReady() {
        if (isDestroyed || !pageReady) return
        val imported = pendingAfterDatabase
        if (imported != null && knowledgeBase.hasDatabase()) {
            pendingAfterDatabase = null
            persistPendingState()
            persistUserMessageThenContinue(imported)
            return
        }
        val pending = pendingAfterAuth ?: return
        if (!tokenVault.hasCredential() || inflight != null) return
        pendingAfterAuth = null
        persistPendingState()
        inflight = pending
        emitStatus("正在检索并思考…", "working")
        runtime.send(pending.requestId, pending.text, this)
    }

    private fun restorePendingState() {
        val state = pendingStore.load()
        currentProjectId = state.currentProjectId?.takeIf {
            runCatching { historyStore.projectExists(it) }.getOrDefault(false)
        } ?: currentProjectId
        currentConversationId = state.currentConversationId?.takeIf {
            runCatching { historyStore.conversationExists(it) }.getOrDefault(false)
        }
        pendingAfterDatabase = state.pendingAfterDatabase
        pendingAfterAuth = state.pendingAfterAuth
        persistPendingState()
    }

    private fun persistPendingState() {
        if (!::pendingStore.isInitialized) return
        pendingStore.save(
            PendingChatState(
                currentConversationId = currentConversationId,
                currentProjectId = currentProjectId,
                pendingAfterDatabase = pendingAfterDatabase,
                pendingAfterAuth = pendingAfterAuth,
            ),
        )
    }

    private fun emit(type: String, requestId: String, text: String) {
        runOnLiveWebView { webView ->
            val payload = JSONObject().put("type", type).put("requestId", requestId).put("text", text).toString()
            webView.evaluateJavascript("window.GecisChat && window.GecisChat.onNativeEvent($payload);", null)
        }
    }

    private fun emitActionRequired(
        requestId: String,
        kind: String,
        title: String,
        message: String,
        actions: Array<String>,
    ) {
        runOnLiveWebView { webView ->
            val payload = JSONObject()
                .put("type", "action_required")
                .put("requestId", requestId)
                .put("kind", kind)
                .put("title", title)
                .put("message", message)
                .put("actions", JSONArray(actions))
                .put("autoResume", kind == "auth")
                .toString()
            webView.evaluateJavascript("window.GecisChat && window.GecisChat.onNativeEvent($payload);", null)
        }
    }

    private fun emitStatus(text: String, state: String) {
        runOnLiveWebView { webView ->
            val payload = JSONObject().put("text", text).put("state", state).toString()
            webView.evaluateJavascript("window.GecisChat && window.GecisChat.onStatus($payload);", null)
        }
    }

    private fun emitHistory(resumeAfter: Boolean = false) {
        if (!::webView.isInitialized || historyWorker.isShutdown) return
        historyWorker.execute {
            val snapshot = runCatching { historyStore.snapshot(currentConversationId) }
                .getOrElse { historyErrorSnapshot(it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                runOnLiveWebView { webView ->
                    webView.evaluateJavascript("window.GecisChat && window.GecisChat.onHistory($snapshot);", null)
                    if (resumeAfter) {
                        pageReady = true
                        emitResumeTurn { resumePendingIfReady() }
                    }
                }
            }
        }
    }

    private fun emitResumeTurn(then: (() -> Unit)? = null) {
        val authPending = pendingAfterAuth
        if (authPending != null && !tokenVault.hasCredential()) {
            emitActionRequired(
                requestId = authPending.requestId,
                kind = "auth",
                title = "需要连接 Google 账号",
                message = "连接账号后会自动继续刚才的问题。",
                actions = arrayOf("login"),
            )
            then?.invoke()
            return
        }
        val pending = pendingAfterDatabase ?: pendingAfterAuth ?: inflight
        if (pending == null) {
            then?.invoke()
            return
        }
        runOnLiveWebView { webView ->
            val payload = JSONObject().put("requestId", pending.requestId).put("text", pending.text).toString()
            webView.evaluateJavascript("window.GecisChat && window.GecisChat.onResumeTurn($payload);") { then?.invoke() }
        }
    }

    private fun historyErrorSnapshot(error: Throwable): String = JSONObject()
        .put("projects", JSONArray())
        .put("messages", JSONArray())
        .put("currentConversationId", JSONObject.NULL)
        .put("currentProjectId", JSONObject.NULL)
        .put("error", error.message ?: "历史记录不可用")
        .toString()

    private fun shortError(error: Throwable?): String {
        if (error == null) return "未知 SQLite 错误"
        val name = error.javaClass.simpleName
        val message = error.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (message.isBlank()) name else "$name: ${message.take(140)}"
    }

    private fun emitInsets() {
        if (isDestroyed || !::webView.isInitialized) return
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        fun cssPx(value: Int): Int = (value / density).toInt()
        val top = cssPx(lastInsets.top)
        val right = cssPx(lastInsets.right)
        val bottom = cssPx(lastInsets.bottom)
        val left = cssPx(lastInsets.left)
        val imeBottom = cssPx(lastImeBottom)
        val script = """
            (() => {
              const root = document.documentElement;
              root.style.setProperty('--android-safe-top', '${top}px');
              root.style.setProperty('--android-safe-right', '${right}px');
              root.style.setProperty('--android-safe-bottom', '${bottom}px');
              root.style.setProperty('--android-safe-left', '${left}px');
              root.style.setProperty('--android-ime-bottom', '${imeBottom}px');
            })();
        """.trimIndent()
        webView.post {
            if (isDestroyed || !::webView.isInitialized) return@post
            runCatching { webView.evaluateJavascript(script, null) }
        }
    }

    private fun runOnLiveWebView(block: (WebView) -> Unit) {
        runOnUiThread {
            if (isDestroyed || !::webView.isInitialized) return@runOnUiThread
            runCatching { block(webView) }
        }
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
        uri.scheme == "https" && uri.host == APP_HOST && uri.path?.startsWith("/assets/") == true

    private fun openExternalUrl(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show() }
    }

    private fun blockedResource(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val APP_URL = "https://$APP_HOST/assets/index.html"
        private const val APP_RETURN_SCHEME = "gecis"
        private const val APP_RETURN_HOST = "oauth-complete"
        private const val RUNTIME_PREFS = "gecis_runtime_settings"
        private const val PREF_MODEL = "model"
        private const val PREF_EFFORT = "effort"
        private val EFFORT_LEVELS = setOf("low", "medium", "high")
        private val MODEL_SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}
